package com.example.catalog.repository

import androidx.room.withTransaction
import com.example.catalog.db.AlbumEntity
import com.example.catalog.db.AlbumWithArtistRow
import com.example.catalog.db.ArtistEntity
import com.example.catalog.db.CatalogCacheEntity
import com.example.catalog.db.CatalogSyncEntity
import com.example.catalog.db.LocalCatalogDatabase
import com.example.catalog.db.TrackEntity
import com.example.catalog.db.TrackWithRelationsRow
import com.example.catalog.enrichment.EnrichmentCachePolicy
import com.example.catalog.enrichment.IsrcEnrichmentResult
import com.example.catalog.enrichment.IsrcEnrichmentStatus
import com.example.catalog.matching.TrackMatchCandidate
import com.example.catalog.matching.TrackMatchQuery
import com.example.catalog.matching.TrackMatchResult
import com.example.catalog.matching.TrackMatcher
import com.example.catalog.matching.TrackTextNormalizer
import com.example.catalog.model.CatalogAlbum
import com.example.catalog.model.CatalogArtist
import com.example.catalog.model.CatalogEnrichmentResult
import com.example.catalog.model.CatalogPage
import com.example.catalog.model.CatalogProviders
import com.example.catalog.model.CatalogQueryNormalizer
import com.example.catalog.model.CatalogSyncResult
import com.example.catalog.model.CatalogSyncStatus
import com.example.catalog.model.CatalogTrack
import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto
import com.example.catalog.provider.CatalogProvider
import com.example.catalog.provider.CatalogProviderResult
import com.example.catalog.provider.ItunesCatalogAdapter
import com.example.catalog.provider.ProviderDescriptor
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.remote.MusicBrainzEnrichmentProvider
import com.example.catalog.remote.MusicBrainzRateLimitException
import org.json.JSONObject
import java.io.IOException

/**
 * Local Music Catalog Engine repository backed by Room.
 *
 * Architectural guarantees:
 * - Completely independent of LucidaDL Search and production VPS backend.
 * - Delegates remote catalog discovery to a provider-agnostic [CatalogProvider]
 *   (with Apple iTunes as the current primary discovery adapter).
 * - MusicBrainz is strictly optional, explicit metadata enrichment (never blocks search).
 * - All local search & detail queries run 100% offline against Room with LIMIT/OFFSET pagination.
 * - Never generates or fabricates Amazon Music URLs or fake playlists.
 */
class LocalCatalogRepository(
    private val database: LocalCatalogDatabase,
    private val catalogProvider: CatalogProvider,
    private val musicBrainzProvider: MusicBrainzEnrichmentProvider? = null,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val trackMatcher: TrackMatcher = TrackMatcher(),
    private val enrichmentCachePolicy: EnrichmentCachePolicy = EnrichmentCachePolicy()
) {
    /**
     * Backward-compatible constructor accepting a raw [ItunesCatalogProvider] and adapting it
     * via [ItunesCatalogAdapter].
     */
    constructor(
        database: LocalCatalogDatabase,
        itunesProvider: ItunesCatalogProvider,
        musicBrainzProvider: MusicBrainzEnrichmentProvider? = null,
        cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
        clockMs: () -> Long = System::currentTimeMillis
    ) : this(
        database = database,
        catalogProvider = ItunesCatalogAdapter(itunesProvider),
        musicBrainzProvider = musicBrainzProvider,
        cacheTtlMs = cacheTtlMs,
        clockMs = clockMs
    )

    val activeCatalogProviderDescriptor: ProviderDescriptor
        get() = catalogProvider.descriptor

    private val artistDao = database.artistDao()
    private val albumDao = database.albumDao()
    private val trackDao = database.trackDao()
    private val syncDao = database.catalogSyncDao()
    private val cacheDao = database.catalogCacheDao()

    // =========================================================================
    // 1. Offline Local Search & Detail Queries (Room Only — Zero Network)
    // =========================================================================

    suspend fun searchArtists(
        query: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): CatalogPage<CatalogArtist> {
        val norm = CatalogQueryNormalizer.normalize(query)
        val safeLimit = limit.coerceIn(1, MAX_LOCAL_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        if (norm.isEmpty()) {
            return CatalogPage(emptyList(), totalCount = 0, limit = safeLimit, offset = safeOffset)
        }
        val total = artistDao.countSearchByName(norm)
        val rows = artistDao.searchByName(norm, safeLimit, safeOffset).map { it.toDomain() }
        return CatalogPage(items = rows, totalCount = total, limit = safeLimit, offset = safeOffset)
    }

    suspend fun searchAlbums(
        query: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): CatalogPage<CatalogAlbum> {
        val norm = CatalogQueryNormalizer.normalize(query)
        val safeLimit = limit.coerceIn(1, MAX_LOCAL_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        if (norm.isEmpty()) {
            return CatalogPage(emptyList(), totalCount = 0, limit = safeLimit, offset = safeOffset)
        }
        val total = albumDao.countSearchByTitle(norm)
        val rows = albumDao.searchByTitle(norm, safeLimit, safeOffset).map { it.toDomain() }
        return CatalogPage(items = rows, totalCount = total, limit = safeLimit, offset = safeOffset)
    }

    suspend fun searchTracks(
        query: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): CatalogPage<CatalogTrack> {
        val norm = CatalogQueryNormalizer.normalize(query)
        val safeLimit = limit.coerceIn(1, MAX_LOCAL_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        if (norm.isEmpty()) {
            return CatalogPage(emptyList(), totalCount = 0, limit = safeLimit, offset = safeOffset)
        }
        val total = trackDao.countSearchByTitle(norm)
        val rows = trackDao.searchByTitle(norm, safeLimit, safeOffset).map { it.toDomain() }
        return CatalogPage(items = rows, totalCount = total, limit = safeLimit, offset = safeOffset)
    }

    suspend fun searchTracksByArtist(
        artistQuery: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): CatalogPage<CatalogTrack> {
        val norm = CatalogQueryNormalizer.normalize(artistQuery)
        val safeLimit = limit.coerceIn(1, MAX_LOCAL_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        if (norm.isEmpty()) {
            return CatalogPage(emptyList(), totalCount = 0, limit = safeLimit, offset = safeOffset)
        }
        val total = trackDao.countSearchByArtistName(norm)
        val rows = trackDao.searchByArtistName(norm, safeLimit, safeOffset).map { it.toDomain() }
        return CatalogPage(items = rows, totalCount = total, limit = safeLimit, offset = safeOffset)
    }

    suspend fun searchAlbumsByArtist(
        artistQuery: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): CatalogPage<CatalogAlbum> {
        val norm = CatalogQueryNormalizer.normalize(artistQuery)
        val safeLimit = limit.coerceIn(1, MAX_LOCAL_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        if (norm.isEmpty()) {
            return CatalogPage(emptyList(), totalCount = 0, limit = safeLimit, offset = safeOffset)
        }
        val total = albumDao.countSearchByArtistName(norm)
        val rows = albumDao.searchByArtistName(norm, safeLimit, safeOffset).map { it.toDomain() }
        return CatalogPage(items = rows, totalCount = total, limit = safeLimit, offset = safeOffset)
    }

    suspend fun getArtistDiscography(
        artistId: Long,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): CatalogPage<CatalogAlbum> {
        val safeLimit = limit.coerceIn(1, MAX_LOCAL_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        val total = albumDao.countByArtistId(artistId)
        val rows = albumDao.getDiscographyByArtistId(artistId, safeLimit, safeOffset).map { it.toDomain() }
        return CatalogPage(items = rows, totalCount = total, limit = safeLimit, offset = safeOffset)
    }

    suspend fun getAlbumTracklist(
        albumId: Long,
        limit: Int = 100,
        offset: Int = 0
    ): CatalogPage<CatalogTrack> {
        val safeLimit = limit.coerceIn(1, MAX_LOCAL_PAGE_SIZE)
        val safeOffset = offset.coerceAtLeast(0)
        val total = trackDao.countByAlbumId(albumId)
        val rows = trackDao.getTracklistByAlbumId(albumId, safeLimit, safeOffset).map { it.toDomain() }
        return CatalogPage(items = rows, totalCount = total, limit = safeLimit, offset = safeOffset)
    }

    suspend fun getArtistDetails(artistId: Long): CatalogArtist? =
        artistDao.getById(artistId)?.toDomain()

    suspend fun getAlbumDetails(albumId: Long): CatalogAlbum? =
        albumDao.getDetailsById(albumId)?.toDomain()

    suspend fun getTrackDetails(trackId: Long): CatalogTrack? =
        trackDao.getDetailsById(trackId)?.toDomain()

    // =========================================================================
    // 2. Controlled Import & Sync Pipeline (Delegated to CatalogProvider)
    // =========================================================================

    suspend fun syncSearchArtists(
        query: String,
        limit: Int = 25,
        country: String = catalogProvider.capabilities.defaultCountry,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val providerId = catalogProvider.providerId
        val norm = CatalogQueryNormalizer.normalize(query)
        if (norm.isEmpty()) {
            return CatalogSyncResult(
                provider = providerId,
                entityType = "artist",
                query = query,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Query cannot be empty."
            )
        }
        if (!catalogProvider.capabilities.supportsArtistSearch) {
            return CatalogSyncResult(
                provider = providerId,
                entityType = "artist",
                query = query.trim(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Provider $providerId does not support ${CatalogProvider.OP_SEARCH_ARTISTS}."
            )
        }
        val safeLimit = limit.coerceIn(1, catalogProvider.capabilities.maxSearchLimit.coerceAtLeast(1))
        val cacheKey = "$providerId:search:artist:${country.uppercase()}:$safeLimit:$norm"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = providerId,
                    entityType = "artist",
                    query = query.trim(),
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            when (val result = catalogProvider.searchArtists(query.trim(), safeLimit, country)) {
                is CatalogProviderResult.Unsupported -> CatalogSyncResult(
                    provider = providerId,
                    entityType = "artist",
                    query = query.trim(),
                    fromCache = false,
                    status = CatalogSyncStatus.FAILED,
                    errorMessage = "Provider ${result.providerId} does not support ${result.operation}."
                )

                is CatalogProviderResult.Success -> {
                    val artists = result.data
                    database.withTransaction {
                        artists.forEach { upsertArtistInTx(it, now) }
                        cacheDao.upsert(
                            CatalogCacheEntity(
                                cacheKey = cacheKey,
                                provider = providerId,
                                responseJson = result.rawPayloadForCache.orEmpty(),
                                createdAt = now,
                                expiresAt = now + cacheTtlMs
                            )
                        )
                        recordSyncInTx(providerId, "artist", query.trim(), norm, now, artists.size, CatalogSyncStatus.SUCCESS)
                    }
                    CatalogSyncResult(
                        provider = providerId,
                        entityType = "artist",
                        query = query.trim(),
                        fromCache = false,
                        importedArtists = artists.size,
                        status = CatalogSyncStatus.SUCCESS
                    )
                }
            }
        } catch (e: Exception) {
            recordSyncFailure(providerId, "artist", query.trim(), norm, now)
            CatalogSyncResult(
                provider = providerId,
                entityType = "artist",
                query = query.trim(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = e.message ?: "Upstream artist search failed."
            )
        }
    }

    suspend fun syncSearchAlbums(
        query: String,
        limit: Int = 50,
        country: String = catalogProvider.capabilities.defaultCountry,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val providerId = catalogProvider.providerId
        val norm = CatalogQueryNormalizer.normalize(query)
        if (norm.isEmpty()) {
            return CatalogSyncResult(
                provider = providerId,
                entityType = "album",
                query = query,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Query cannot be empty."
            )
        }
        if (!catalogProvider.capabilities.supportsAlbumSearch) {
            return CatalogSyncResult(
                provider = providerId,
                entityType = "album",
                query = query.trim(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Provider $providerId does not support ${CatalogProvider.OP_SEARCH_ALBUMS}."
            )
        }
        val safeLimit = limit.coerceIn(1, catalogProvider.capabilities.maxSearchLimit.coerceAtLeast(1))
        val cacheKey = "$providerId:search:album:${country.uppercase()}:$safeLimit:$norm"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = providerId,
                    entityType = "album",
                    query = query.trim(),
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            when (val result = catalogProvider.searchAlbums(query.trim(), safeLimit, country)) {
                is CatalogProviderResult.Unsupported -> CatalogSyncResult(
                    provider = providerId,
                    entityType = "album",
                    query = query.trim(),
                    fromCache = false,
                    status = CatalogSyncStatus.FAILED,
                    errorMessage = "Provider ${result.providerId} does not support ${result.operation}."
                )

                is CatalogProviderResult.Success -> {
                    val albums = result.data
                    var artistCount = 0
                    database.withTransaction {
                        val seenArtists = HashSet<String>()
                        albums.forEach { dto ->
                            val artistId = upsertArtistInTx(
                                RemoteArtistDto(
                                    providerArtistId = dto.providerArtistId,
                                    name = dto.artistName,
                                    primaryGenre = dto.genre,
                                    provider = dto.provider
                                ),
                                now
                            )
                            if (seenArtists.add(dto.providerArtistId)) artistCount++
                            upsertAlbumInTx(dto, artistId, now)
                        }
                        cacheDao.upsert(
                            CatalogCacheEntity(
                                cacheKey = cacheKey,
                                provider = providerId,
                                responseJson = result.rawPayloadForCache.orEmpty(),
                                createdAt = now,
                                expiresAt = now + cacheTtlMs
                            )
                        )
                        recordSyncInTx(providerId, "album", query.trim(), norm, now, albums.size, CatalogSyncStatus.SUCCESS)
                    }
                    CatalogSyncResult(
                        provider = providerId,
                        entityType = "album",
                        query = query.trim(),
                        fromCache = false,
                        importedArtists = artistCount,
                        importedAlbums = albums.size,
                        status = CatalogSyncStatus.SUCCESS
                    )
                }
            }
        } catch (e: Exception) {
            recordSyncFailure(providerId, "album", query.trim(), norm, now)
            CatalogSyncResult(
                provider = providerId,
                entityType = "album",
                query = query.trim(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = e.message ?: "Upstream album search failed."
            )
        }
    }

    suspend fun syncSearchTracks(
        query: String,
        limit: Int = 50,
        country: String = catalogProvider.capabilities.defaultCountry,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val providerId = catalogProvider.providerId
        val norm = CatalogQueryNormalizer.normalize(query)
        if (norm.isEmpty()) {
            return CatalogSyncResult(
                provider = providerId,
                entityType = "track",
                query = query,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Query cannot be empty."
            )
        }
        if (!catalogProvider.capabilities.supportsTrackSearch) {
            return CatalogSyncResult(
                provider = providerId,
                entityType = "track",
                query = query.trim(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Provider $providerId does not support ${CatalogProvider.OP_SEARCH_TRACKS}."
            )
        }
        val safeLimit = limit.coerceIn(1, catalogProvider.capabilities.maxSearchLimit.coerceAtLeast(1))
        val cacheKey = "$providerId:search:track:${country.uppercase()}:$safeLimit:$norm"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = providerId,
                    entityType = "track",
                    query = query.trim(),
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            when (val result = catalogProvider.searchTracks(query.trim(), safeLimit, country)) {
                is CatalogProviderResult.Unsupported -> CatalogSyncResult(
                    provider = providerId,
                    entityType = "track",
                    query = query.trim(),
                    fromCache = false,
                    status = CatalogSyncStatus.FAILED,
                    errorMessage = "Provider ${result.providerId} does not support ${result.operation}."
                )

                is CatalogProviderResult.Success -> {
                    val tracks = result.data
                    var artistCount = 0
                    var albumCount = 0
                    database.withTransaction {
                        val seenArtists = HashSet<String>()
                        val seenAlbums = HashSet<String>()
                        tracks.forEach { dto ->
                            val artistId = upsertArtistInTx(
                                RemoteArtistDto(
                                    providerArtistId = dto.providerArtistId,
                                    name = dto.artistName,
                                    primaryGenre = dto.genre,
                                    provider = dto.provider
                                ),
                                now
                            )
                            if (seenArtists.add(dto.providerArtistId)) artistCount++

                            val albumId = if (!dto.providerAlbumId.isNullOrBlank() && !dto.albumTitle.isNullOrBlank()) {
                                val aid = upsertAlbumInTx(
                                    RemoteAlbumDto(
                                        providerAlbumId = dto.providerAlbumId,
                                        providerArtistId = dto.providerArtistId,
                                        artistName = dto.artistName,
                                        title = dto.albumTitle,
                                        releaseDate = dto.albumReleaseDate,
                                        genre = dto.genre,
                                        artworkUrl = dto.albumArtworkUrl,
                                        trackCount = dto.albumTrackCount,
                                        provider = dto.provider
                                    ),
                                    artistId,
                                    now
                                )
                                if (seenAlbums.add(dto.providerAlbumId)) albumCount++
                                aid
                            } else {
                                null
                            }
                            upsertTrackInTx(dto, artistId, albumId, now)
                        }
                        cacheDao.upsert(
                            CatalogCacheEntity(
                                cacheKey = cacheKey,
                                provider = providerId,
                                responseJson = result.rawPayloadForCache.orEmpty(),
                                createdAt = now,
                                expiresAt = now + cacheTtlMs
                            )
                        )
                        recordSyncInTx(providerId, "track", query.trim(), norm, now, tracks.size, CatalogSyncStatus.SUCCESS)
                    }
                    CatalogSyncResult(
                        provider = providerId,
                        entityType = "track",
                        query = query.trim(),
                        fromCache = false,
                        importedArtists = artistCount,
                        importedAlbums = albumCount,
                        importedTracks = tracks.size,
                        status = CatalogSyncStatus.SUCCESS
                    )
                }
            }
        } catch (e: Exception) {
            recordSyncFailure(providerId, "track", query.trim(), norm, now)
            CatalogSyncResult(
                provider = providerId,
                entityType = "track",
                query = query.trim(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = e.message ?: "Upstream track search failed."
            )
        }
    }

    suspend fun syncArtistDiscography(
        localArtistId: Long,
        limit: Int = 50,
        country: String = catalogProvider.capabilities.defaultCountry,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val providerId = catalogProvider.providerId
        val artist = artistDao.getById(localArtistId)
            ?: return CatalogSyncResult(
                provider = providerId,
                entityType = "artist_albums",
                query = localArtistId.toString(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Artist not found in local catalog."
            )
        if (!catalogProvider.capabilities.supportsArtistAlbums) {
            return CatalogSyncResult(
                provider = providerId,
                entityType = "artist_albums",
                query = artist.providerArtistId,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Provider $providerId does not support ${CatalogProvider.OP_ARTIST_ALBUMS}."
            )
        }
        val safeLimit = limit.coerceIn(1, catalogProvider.capabilities.maxSearchLimit.coerceAtLeast(1))
        val cacheKey = "$providerId:lookup:artist_albums:${country.uppercase()}:$safeLimit:${artist.providerArtistId}"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = providerId,
                    entityType = "artist_albums",
                    query = artist.providerArtistId,
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            when (val result = catalogProvider.getArtistAlbums(artist.providerArtistId, safeLimit, country)) {
                is CatalogProviderResult.Unsupported -> CatalogSyncResult(
                    provider = providerId,
                    entityType = "artist_albums",
                    query = artist.providerArtistId,
                    fromCache = false,
                    status = CatalogSyncStatus.FAILED,
                    errorMessage = "Provider ${result.providerId} does not support ${result.operation}."
                )

                is CatalogProviderResult.Success -> {
                    val albums = result.data
                    database.withTransaction {
                        albums.forEach { dto ->
                            upsertAlbumInTx(dto, artist.id, now)
                        }
                        cacheDao.upsert(
                            CatalogCacheEntity(
                                cacheKey = cacheKey,
                                provider = providerId,
                                responseJson = result.rawPayloadForCache.orEmpty(),
                                createdAt = now,
                                expiresAt = now + cacheTtlMs
                            )
                        )
                        recordSyncInTx(
                            providerId,
                            "artist_albums",
                            artist.providerArtistId,
                            artist.providerArtistId.lowercase(),
                            now,
                            albums.size,
                            CatalogSyncStatus.SUCCESS
                        )
                    }
                    CatalogSyncResult(
                        provider = providerId,
                        entityType = "artist_albums",
                        query = artist.providerArtistId,
                        fromCache = false,
                        importedAlbums = albums.size,
                        status = CatalogSyncStatus.SUCCESS
                    )
                }
            }
        } catch (e: Exception) {
            recordSyncFailure(providerId, "artist_albums", artist.providerArtistId, artist.providerArtistId.lowercase(), now)
            CatalogSyncResult(
                provider = providerId,
                entityType = "artist_albums",
                query = artist.providerArtistId,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = e.message ?: "Upstream artist lookup failed."
            )
        }
    }

    suspend fun syncAlbumTracklist(
        localAlbumId: Long,
        country: String = catalogProvider.capabilities.defaultCountry,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val providerId = catalogProvider.providerId
        val album = albumDao.getEntityById(localAlbumId)
            ?: return CatalogSyncResult(
                provider = providerId,
                entityType = "album_tracks",
                query = localAlbumId.toString(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Album not found in local catalog."
            )
        if (!catalogProvider.capabilities.supportsAlbumTracklist) {
            return CatalogSyncResult(
                provider = providerId,
                entityType = "album_tracks",
                query = album.providerAlbumId,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Provider $providerId does not support ${CatalogProvider.OP_ALBUM_TRACKLIST}."
            )
        }
        val cacheKey = "$providerId:lookup:album_tracks:${country.uppercase()}:${album.providerAlbumId}"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = providerId,
                    entityType = "album_tracks",
                    query = album.providerAlbumId,
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            when (val result = catalogProvider.getAlbumTracklist(album.providerAlbumId, country)) {
                is CatalogProviderResult.Unsupported -> CatalogSyncResult(
                    provider = providerId,
                    entityType = "album_tracks",
                    query = album.providerAlbumId,
                    fromCache = false,
                    status = CatalogSyncStatus.FAILED,
                    errorMessage = "Provider ${result.providerId} does not support ${result.operation}."
                )

                is CatalogProviderResult.Success -> {
                    val parsed = result.data
                    database.withTransaction {
                        parsed.album?.let { dto ->
                            upsertAlbumInTx(dto, album.artistId, now)
                        }
                        parsed.tracks.forEach { trDto ->
                            val trArtistId = if (trDto.providerArtistId == artistDao.getById(album.artistId)?.providerArtistId) {
                                album.artistId
                            } else {
                                upsertArtistInTx(
                                    RemoteArtistDto(
                                        providerArtistId = trDto.providerArtistId,
                                        name = trDto.artistName,
                                        primaryGenre = trDto.genre,
                                        provider = trDto.provider
                                    ),
                                    now
                                )
                            }
                            upsertTrackInTx(trDto, trArtistId, album.id, now)
                        }
                        cacheDao.upsert(
                            CatalogCacheEntity(
                                cacheKey = cacheKey,
                                provider = providerId,
                                responseJson = result.rawPayloadForCache.orEmpty(),
                                createdAt = now,
                                expiresAt = now + cacheTtlMs
                            )
                        )
                        recordSyncInTx(
                            providerId,
                            "album_tracks",
                            album.providerAlbumId,
                            album.providerAlbumId.lowercase(),
                            now,
                            parsed.tracks.size,
                            CatalogSyncStatus.SUCCESS
                        )
                    }
                    CatalogSyncResult(
                        provider = providerId,
                        entityType = "album_tracks",
                        query = album.providerAlbumId,
                        fromCache = false,
                        importedTracks = parsed.tracks.size,
                        status = CatalogSyncStatus.SUCCESS
                    )
                }
            }
        } catch (e: Exception) {
            recordSyncFailure(providerId, "album_tracks", album.providerAlbumId, album.providerAlbumId.lowercase(), now)
            CatalogSyncResult(
                provider = providerId,
                entityType = "album_tracks",
                query = album.providerAlbumId,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = e.message ?: "Upstream album lookup failed."
            )
        }
    }

    // =========================================================================
    // 3. Explicit Optional MusicBrainz Enrichment (Never Blocks Local Search)
    // =========================================================================

    suspend fun enrichArtistWithMusicBrainz(localArtistId: Long): CatalogEnrichmentResult {
        val mb = musicBrainzProvider
            ?: return CatalogEnrichmentResult(matched = false, errorMessage = "MusicBrainz provider not configured.")
        val existing = artistDao.getById(localArtistId)
            ?: return CatalogEnrichmentResult(matched = false, errorMessage = "Artist not found.")
        return try {
            val match = mb.findArtistEnrichment(existing.name)
                ?: return CatalogEnrichmentResult(matched = false)
            val updatedFields = mutableListOf<String>()
            val merged = existing.copy(
                musicBrainzArtistId = match.musicBrainzArtistId.also { updatedFields.add("musicbrainz_artist_id") },
                country = existing.country ?: match.country?.also { updatedFields.add("country") },
                lastSyncedAt = clockMs()
            )
            artistDao.update(merged)
            CatalogEnrichmentResult(
                matched = true,
                musicBrainzId = match.musicBrainzArtistId,
                updatedFields = updatedFields
            )
        } catch (e: Exception) {
            CatalogEnrichmentResult(matched = false, errorMessage = e.message)
        }
    }

    suspend fun enrichAlbumWithMusicBrainz(localAlbumId: Long): CatalogEnrichmentResult {
        val mb = musicBrainzProvider
            ?: return CatalogEnrichmentResult(matched = false, errorMessage = "MusicBrainz provider not configured.")
        val existing = albumDao.getEntityById(localAlbumId)
            ?: return CatalogEnrichmentResult(matched = false, errorMessage = "Album not found.")
        val artist = artistDao.getById(existing.artistId)
            ?: return CatalogEnrichmentResult(matched = false, errorMessage = "Parent artist not found.")
        return try {
            val match = mb.findReleaseGroupEnrichment(
                artistName = artist.name,
                albumTitle = existing.title,
                musicBrainzArtistId = artist.musicBrainzArtistId
            ) ?: return CatalogEnrichmentResult(matched = false)
            val updatedFields = mutableListOf<String>()
            val merged = existing.copy(
                musicBrainzReleaseGroupId = match.musicBrainzReleaseGroupId.also { updatedFields.add("musicbrainz_release_group_id") },
                releaseDate = existing.releaseDate ?: match.firstReleaseDate?.also { updatedFields.add("release_date") },
                lastSyncedAt = clockMs()
            )
            albumDao.update(merged)
            CatalogEnrichmentResult(
                matched = true,
                musicBrainzId = match.musicBrainzReleaseGroupId,
                updatedFields = updatedFields
            )
        } catch (e: Exception) {
            CatalogEnrichmentResult(matched = false, errorMessage = e.message)
        }
    }

    suspend fun enrichTrackWithMusicBrainz(localTrackId: Long): CatalogEnrichmentResult {
        val mb = musicBrainzProvider
            ?: return CatalogEnrichmentResult(matched = false, errorMessage = "MusicBrainz provider not configured.")
        val existing = trackDao.getEntityById(localTrackId)
            ?: return CatalogEnrichmentResult(matched = false, errorMessage = "Track not found.")
        val artist = artistDao.getById(existing.artistId)
            ?: return CatalogEnrichmentResult(matched = false, errorMessage = "Parent artist not found.")
        return try {
            val match = mb.findRecordingEnrichment(
                artistName = artist.name,
                trackTitle = existing.title
            ) ?: return CatalogEnrichmentResult(matched = false)
            val updatedFields = mutableListOf<String>()
            val merged = existing.copy(
                musicBrainzRecordingId = match.musicBrainzRecordingId.also { updatedFields.add("musicbrainz_recording_id") },
                isrc = existing.isrc ?: match.isrc?.also { updatedFields.add("isrc") },
                durationMs = existing.durationMs ?: match.durationMs?.also { updatedFields.add("duration_ms") },
                lastSyncedAt = clockMs()
            )
            trackDao.update(merged)
            CatalogEnrichmentResult(
                matched = true,
                musicBrainzId = match.musicBrainzRecordingId,
                updatedFields = updatedFields
            )
        } catch (e: Exception) {
            CatalogEnrichmentResult(matched = false, errorMessage = e.message)
        }
    }

    /**
     * Verified, on-demand MusicBrainz ISRC enrichment gated by [TrackMatcher] with
     * positive and negative caching in existing Room `catalog_cache`.
     *
     * Guarantees:
     * - Never assigns MBID or ISRC merely because a search result ranked first.
     * - Never persists ambiguous, unverified, or conflicting metadata.
     * - Performs `/ws/2/recording/{mbid}?inc=isrcs&fmt=json` only when a verified
     *   candidate lacks valid ISRC data in its search result.
     */
    suspend fun enrichTrackIsrcWithMusicBrainz(
        localTrackId: Long,
        forceRefresh: Boolean = false
    ): IsrcEnrichmentResult {
        val mb = musicBrainzProvider
            ?: return IsrcEnrichmentResult(
                status = IsrcEnrichmentStatus.UNSUPPORTED_OR_INCOMPLETE,
                reason = "MusicBrainz provider not configured."
            )
        val existing = trackDao.getEntityById(localTrackId)
            ?: return IsrcEnrichmentResult(
                status = IsrcEnrichmentStatus.UNSUPPORTED_OR_INCOMPLETE,
                reason = "Track not found in local catalog."
            )
        val artist = artistDao.getById(existing.artistId)
            ?: return IsrcEnrichmentResult(
                status = IsrcEnrichmentStatus.UNSUPPORTED_OR_INCOMPLETE,
                reason = "Parent artist not found in local catalog."
            )
        if (existing.title.isBlank() || artist.name.isBlank()) {
            return IsrcEnrichmentResult(
                status = IsrcEnrichmentStatus.UNSUPPORTED_OR_INCOMPLETE,
                reason = "Track title and artist name are required for enrichment."
            )
        }

        // 1. Check existing valid ISRC on local track (avoid redundant HTTP calls)
        val existingNormalizedIsrc = TrackTextNormalizer.normalizeIsrc(existing.isrc)
        if (!forceRefresh && existingNormalizedIsrc != null) {
            return IsrcEnrichmentResult(
                status = IsrcEnrichmentStatus.ALREADY_ENRICHED,
                musicBrainzRecordingId = existing.musicBrainzRecordingId,
                isrc = existingNormalizedIsrc,
                fromCache = true,
                reason = "Track already has a valid normalized ISRC."
            )
        }

        val now = clockMs()
        val cacheKey = "${CatalogProviders.MUSICBRAINZ}:isrc_enrich:${existing.provider}:${existing.providerTrackId}"

        // 2. Check existing positive/negative cache in `catalog_cache`
        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                val decoded = decodeEnrichmentCache(cached.responseJson)
                if (decoded != null) {
                    if (decoded.status == IsrcEnrichmentStatus.VERIFIED_ENRICHED && decoded.isrc != null) {
                        val updatedFields = mutableListOf<String>()
                        val merged = existing.copy(
                            musicBrainzRecordingId = existing.musicBrainzRecordingId
                                ?: decoded.musicBrainzRecordingId?.also { updatedFields.add("musicbrainz_recording_id") },
                            isrc = decoded.isrc.also { if (existing.isrc != it) updatedFields.add("isrc") },
                            lastSyncedAt = now
                        )
                        if (updatedFields.isNotEmpty()) {
                            trackDao.update(merged)
                        }
                        return decoded.copy(fromCache = true, updatedFields = updatedFields)
                    }
                    return decoded.copy(fromCache = true)
                }
            }
        }

        val album = existing.albumId?.let { albumDao.getEntityById(it) }
        val albumYear = album?.releaseDate?.trim()?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()

        return try {
            // 3. Search MusicBrainz recording candidates
            val rawCandidates = mb.searchRecordingCandidates(
                artistName = artist.name,
                trackTitle = existing.title,
                limit = 5
            )
            if (rawCandidates.isEmpty()) {
                val notFound = IsrcEnrichmentResult(
                    status = IsrcEnrichmentStatus.NOT_FOUND,
                    reason = "No MusicBrainz recording candidates found."
                )
                saveEnrichmentCache(cacheKey, notFound, now, enrichmentCachePolicy.negativeTtlMs)
                return notFound
            }

            // 4. Convert to TrackMatchCandidate and evaluate with TrackMatcher
            val candidateById = rawCandidates.associateBy { it.musicBrainzRecordingId }
            val matchQuery = TrackMatchQuery(
                title = existing.title,
                artist = artist.name,
                album = album?.title,
                durationMs = existing.durationMs,
                trackNumber = existing.trackNumber,
                releaseYear = albumYear,
                isrc = null
            )
            val matchCandidates = rawCandidates.map { c ->
                TrackMatchCandidate(
                    candidateId = c.musicBrainzRecordingId,
                    providerId = CatalogProviders.MUSICBRAINZ,
                    title = c.title,
                    artist = c.artistName,
                    album = c.albumTitle,
                    durationMs = c.durationMs,
                    releaseYear = c.releaseYear,
                    isrc = c.isrcs.firstNotNullOfOrNull { TrackTextNormalizer.normalizeIsrc(it) }
                )
            }

            // 5-6. Gate strictly on TrackMatcher.Verified
            when (val matchResult = trackMatcher.match(matchQuery, matchCandidates)) {
                is TrackMatchResult.Ambiguous -> {
                    val result = IsrcEnrichmentResult(
                        status = IsrcEnrichmentStatus.AMBIGUOUS,
                        reason = matchResult.reason
                    )
                    saveEnrichmentCache(cacheKey, result, now, enrichmentCachePolicy.negativeTtlMs)
                    result
                }

                is TrackMatchResult.Unverified -> {
                    val result = IsrcEnrichmentResult(
                        status = IsrcEnrichmentStatus.CONFLICT_REJECTED,
                        matchEvidence = matchResult.evidence,
                        reason = "Unverified match rejected for ISRC persistence: ${matchResult.reason}"
                    )
                    saveEnrichmentCache(cacheKey, result, now, enrichmentCachePolicy.negativeTtlMs)
                    result
                }

                is TrackMatchResult.Unavailable -> {
                    val isConflict = matchResult.reason.contains("conflict", ignoreCase = true) ||
                        matchResult.reason.contains("below minimum", ignoreCase = true)
                    val status = if (isConflict) {
                        IsrcEnrichmentStatus.CONFLICT_REJECTED
                    } else {
                        IsrcEnrichmentStatus.NOT_FOUND
                    }
                    val result = IsrcEnrichmentResult(
                        status = status,
                        reason = matchResult.reason
                    )
                    saveEnrichmentCache(cacheKey, result, now, enrichmentCachePolicy.negativeTtlMs)
                    result
                }

                is TrackMatchResult.Verified -> {
                    val verifiedMbid = matchResult.candidate.candidateId
                    val matchedRaw = candidateById[verifiedMbid]
                    val searchValidIsrcs = matchedRaw?.isrcs
                        .orEmpty()
                        .mapNotNull { TrackTextNormalizer.normalizeIsrc(it) }
                        .distinct()
                        .sorted()

                    // 7. Only perform `/ws/2/recording/{mbid}?inc=isrcs&fmt=json` when search lacked valid ISRC
                    val resolvedValidIsrcs = if (searchValidIsrcs.isNotEmpty()) {
                        searchValidIsrcs
                    } else {
                        mb.fetchRecordingIsrcs(verifiedMbid)
                            .mapNotNull { TrackTextNormalizer.normalizeIsrc(it) }
                            .distinct()
                            .sorted()
                    }

                    // 8-9. Validate & resolve ISRC deterministically
                    if (resolvedValidIsrcs.isEmpty()) {
                        val noIsrc = IsrcEnrichmentResult(
                            status = IsrcEnrichmentStatus.NOT_FOUND,
                            musicBrainzRecordingId = verifiedMbid,
                            matchEvidence = matchResult.evidence,
                            reason = "Verified MusicBrainz recording contains no valid ISRC."
                        )
                        saveEnrichmentCache(cacheKey, noIsrc, now, enrichmentCachePolicy.negativeTtlMs)
                        return noIsrc
                    }

                    val distinctPrefixes = resolvedValidIsrcs.map { it.substring(0, 5) }.distinct()
                    if (distinctPrefixes.size > 1) {
                        val conflict = IsrcEnrichmentResult(
                            status = IsrcEnrichmentStatus.CONFLICT_REJECTED,
                            musicBrainzRecordingId = verifiedMbid,
                            matchEvidence = matchResult.evidence,
                            reason = "Multiple conflicting ISRCs returned for recording ($resolvedValidIsrcs)."
                        )
                        saveEnrichmentCache(cacheKey, conflict, now, enrichmentCachePolicy.negativeTtlMs)
                        return conflict
                    }

                    val selectedIsrc = resolvedValidIsrcs.first()

                    // 10. Persist verified MBID + normalized ISRC
                    val updatedFields = mutableListOf<String>()
                    val merged = existing.copy(
                        musicBrainzRecordingId = verifiedMbid.also {
                            if (existing.musicBrainzRecordingId != it) updatedFields.add("musicbrainz_recording_id")
                        },
                        isrc = selectedIsrc.also {
                            if (existing.isrc != it) updatedFields.add("isrc")
                        },
                        durationMs = existing.durationMs ?: matchedRaw?.durationMs?.also {
                            updatedFields.add("duration_ms")
                        },
                        lastSyncedAt = now
                    )
                    trackDao.update(merged)

                    val verifiedResult = IsrcEnrichmentResult(
                        status = IsrcEnrichmentStatus.VERIFIED_ENRICHED,
                        musicBrainzRecordingId = verifiedMbid,
                        isrc = selectedIsrc,
                        fromCache = false,
                        matchEvidence = matchResult.evidence,
                        updatedFields = updatedFields,
                        reason = "Verified MusicBrainz recording enriched with normalized ISRC."
                    )
                    saveEnrichmentCache(cacheKey, verifiedResult, now, enrichmentCachePolicy.positiveTtlMs)
                    verifiedResult
                }
            }
        } catch (e: MusicBrainzRateLimitException) {
            IsrcEnrichmentResult(
                status = IsrcEnrichmentStatus.RATE_LIMITED,
                reason = e.message ?: "MusicBrainz rate limit reached."
            )
        } catch (e: IOException) {
            IsrcEnrichmentResult(
                status = IsrcEnrichmentStatus.NETWORK_ERROR,
                reason = e.message ?: "Network error during MusicBrainz enrichment."
            )
        } catch (e: Exception) {
            IsrcEnrichmentResult(
                status = IsrcEnrichmentStatus.NETWORK_ERROR,
                reason = e.message ?: "Unexpected error during MusicBrainz enrichment."
            )
        }
    }

    private suspend fun saveEnrichmentCache(
        cacheKey: String,
        result: IsrcEnrichmentResult,
        nowMs: Long,
        ttlMs: Long
    ) {
        val json = JSONObject().apply {
            put("status", result.status.name)
            if (result.musicBrainzRecordingId != null) put("mbid", result.musicBrainzRecordingId)
            if (result.isrc != null) put("isrc", result.isrc)
            if (result.reason != null) put("reason", result.reason)
        }.toString()

        cacheDao.upsert(
            CatalogCacheEntity(
                cacheKey = cacheKey,
                provider = CatalogProviders.MUSICBRAINZ,
                responseJson = json,
                createdAt = nowMs,
                expiresAt = nowMs + ttlMs.coerceAtLeast(1L)
            )
        )
    }

    private fun decodeEnrichmentCache(rawJson: String): IsrcEnrichmentResult? {
        return try {
            val obj = JSONObject(rawJson)
            val statusName = obj.optString("status", "")
            val status = IsrcEnrichmentStatus.entries.firstOrNull { it.name == statusName } ?: return null
            IsrcEnrichmentResult(
                status = status,
                musicBrainzRecordingId = obj.optString("mbid", "").takeIf { it.isNotBlank() },
                isrc = obj.optString("isrc", "").takeIf { it.isNotBlank() },
                fromCache = true,
                reason = obj.optString("reason", "").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun purgeExpiredCache(): Int = cacheDao.purgeExpired(clockMs())

    // =========================================================================
    // 4. Safe FK-Preserving Upserts (Never Uses Destructive REPLACE)
    // =========================================================================

    suspend fun upsertArtist(dto: RemoteArtistDto): Long = database.withTransaction {
        upsertArtistInTx(dto, clockMs())
    }

    suspend fun upsertAlbum(dto: RemoteAlbumDto, localArtistId: Long): Long = database.withTransaction {
        upsertAlbumInTx(dto, localArtistId, clockMs())
    }

    suspend fun upsertTrack(dto: RemoteTrackDto, localArtistId: Long, localAlbumId: Long?): Long =
        database.withTransaction {
            upsertTrackInTx(dto, localArtistId, localAlbumId, clockMs())
        }

    private suspend fun upsertArtistInTx(dto: RemoteArtistDto, nowMs: Long): Long {
        val existing = artistDao.getByProviderId(dto.provider, dto.providerArtistId)
        val normName = CatalogQueryNormalizer.normalize(dto.name)
        return if (existing != null) {
            artistDao.update(
                existing.copy(
                    name = dto.name.trim(),
                    nameNormalized = normName,
                    primaryGenre = dto.primaryGenre ?: existing.primaryGenre,
                    country = dto.country ?: existing.country,
                    artworkUrl = dto.artworkUrl ?: existing.artworkUrl,
                    lastSyncedAt = nowMs
                )
            )
            existing.id
        } else {
            artistDao.insert(
                ArtistEntity(
                    providerArtistId = dto.providerArtistId,
                    name = dto.name.trim(),
                    nameNormalized = normName,
                    primaryGenre = dto.primaryGenre,
                    country = dto.country,
                    artworkUrl = dto.artworkUrl,
                    provider = dto.provider,
                    lastSyncedAt = nowMs
                )
            )
        }
    }

    private suspend fun upsertAlbumInTx(dto: RemoteAlbumDto, localArtistId: Long, nowMs: Long): Long {
        val existing = albumDao.getByProviderId(dto.provider, dto.providerAlbumId)
        val normTitle = CatalogQueryNormalizer.normalize(dto.title)
        return if (existing != null) {
            albumDao.update(
                existing.copy(
                    artistId = localArtistId,
                    title = dto.title.trim(),
                    titleNormalized = normTitle,
                    releaseDate = dto.releaseDate ?: existing.releaseDate,
                    genre = dto.genre ?: existing.genre,
                    artworkUrl = dto.artworkUrl ?: existing.artworkUrl,
                    trackCount = if (dto.trackCount > 0) dto.trackCount else existing.trackCount,
                    lastSyncedAt = nowMs
                )
            )
            existing.id
        } else {
            albumDao.insert(
                AlbumEntity(
                    providerAlbumId = dto.providerAlbumId,
                    artistId = localArtistId,
                    title = dto.title.trim(),
                    titleNormalized = normTitle,
                    releaseDate = dto.releaseDate,
                    genre = dto.genre,
                    artworkUrl = dto.artworkUrl,
                    trackCount = dto.trackCount.coerceAtLeast(0),
                    provider = dto.provider,
                    lastSyncedAt = nowMs
                )
            )
        }
    }

    private suspend fun upsertTrackInTx(
        dto: RemoteTrackDto,
        localArtistId: Long,
        localAlbumId: Long?,
        nowMs: Long
    ): Long {
        val existing = trackDao.getByProviderId(dto.provider, dto.providerTrackId)
        val normTitle = CatalogQueryNormalizer.normalize(dto.title)
        return if (existing != null) {
            trackDao.update(
                existing.copy(
                    albumId = localAlbumId ?: existing.albumId,
                    artistId = localArtistId,
                    title = dto.title.trim(),
                    titleNormalized = normTitle,
                    durationMs = dto.durationMs ?: existing.durationMs,
                    trackNumber = dto.trackNumber ?: existing.trackNumber,
                    discNumber = dto.discNumber ?: existing.discNumber,
                    lastSyncedAt = nowMs
                )
            )
            existing.id
        } else {
            trackDao.insert(
                TrackEntity(
                    providerTrackId = dto.providerTrackId,
                    albumId = localAlbumId,
                    artistId = localArtistId,
                    title = dto.title.trim(),
                    titleNormalized = normTitle,
                    durationMs = dto.durationMs,
                    trackNumber = dto.trackNumber,
                    discNumber = dto.discNumber,
                    provider = dto.provider,
                    lastSyncedAt = nowMs
                )
            )
        }
    }

    private suspend fun recordSyncInTx(
        provider: String,
        entityType: String,
        query: String,
        queryNormalized: String,
        nowMs: Long,
        resultCount: Int,
        status: CatalogSyncStatus
    ) {
        val existing = syncDao.getByKey(provider, entityType, queryNormalized)
        if (existing != null) {
            syncDao.update(
                existing.copy(
                    query = query,
                    lastSyncedAt = nowMs,
                    resultCount = resultCount,
                    syncStatus = status.wireValue
                )
            )
        } else {
            syncDao.insert(
                CatalogSyncEntity(
                    provider = provider,
                    entityType = entityType,
                    query = query,
                    queryNormalized = queryNormalized,
                    lastSyncedAt = nowMs,
                    resultCount = resultCount,
                    syncStatus = status.wireValue
                )
            )
        }
    }

    private suspend fun recordSyncFailure(
        provider: String,
        entityType: String,
        query: String,
        queryNormalized: String,
        nowMs: Long
    ) {
        try {
            database.withTransaction {
                recordSyncInTx(provider, entityType, query, queryNormalized, nowMs, 0, CatalogSyncStatus.FAILED)
            }
        } catch (_: Exception) {
            // Never mask upstream failure with sync-log error
        }
    }

    companion object {
        const val DEFAULT_CACHE_TTL_MS: Long = 6L * 60L * 60L * 1000L // 6 hours
        const val DEFAULT_PAGE_SIZE: Int = 20
        const val MAX_LOCAL_PAGE_SIZE: Int = 100
    }
}

private fun ArtistEntity.toDomain(): CatalogArtist = CatalogArtist(
    id = id,
    providerArtistId = providerArtistId,
    musicBrainzArtistId = musicBrainzArtistId,
    name = name,
    primaryGenre = primaryGenre,
    country = country,
    artworkUrl = artworkUrl,
    provider = provider,
    lastSyncedAt = lastSyncedAt
)

private fun AlbumWithArtistRow.toDomain(): CatalogAlbum = CatalogAlbum(
    id = id,
    providerAlbumId = providerAlbumId,
    musicBrainzReleaseGroupId = musicBrainzReleaseGroupId,
    artistId = artistId,
    artistName = artistName,
    title = title,
    releaseDate = releaseDate,
    genre = genre,
    artworkUrl = artworkUrl,
    trackCount = trackCount,
    provider = provider,
    lastSyncedAt = lastSyncedAt
)

private fun TrackWithRelationsRow.toDomain(): CatalogTrack = CatalogTrack(
    id = id,
    providerTrackId = providerTrackId,
    musicBrainzRecordingId = musicBrainzRecordingId,
    albumId = albumId,
    albumTitle = albumTitle,
    artistId = artistId,
    artistName = artistName,
    title = title,
    durationMs = durationMs,
    trackNumber = trackNumber,
    discNumber = discNumber,
    isrc = isrc,
    artworkUrl = albumArtworkUrl,
    provider = provider,
    lastSyncedAt = lastSyncedAt
)
