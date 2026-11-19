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
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.remote.MusicBrainzEnrichmentProvider
import com.example.catalog.remote.OkHttpItunesCatalogClient

/**
 * Local Music Catalog Engine repository backed by Room.
 *
 * Architectural guarantees:
 * - Completely independent of LucidaDL Search and production VPS backend.
 * - Apple iTunes Search/Lookup is the primary discovery/import provider (`country=ID`).
 * - MusicBrainz is strictly optional, explicit metadata enrichment (never blocks search).
 * - All local search & detail queries run 100% offline against Room with LIMIT/OFFSET pagination.
 * - Never generates or fabricates Amazon Music URLs or fake playlists.
 */
class LocalCatalogRepository(
    private val database: LocalCatalogDatabase,
    private val itunesProvider: ItunesCatalogProvider,
    private val musicBrainzProvider: MusicBrainzEnrichmentProvider? = null,
    private val cacheTtlMs: Long = DEFAULT_CACHE_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
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
    // 2. Controlled Import & Sync Pipeline (Apple iTunes Primary Provider)
    // =========================================================================

    suspend fun syncSearchArtists(
        query: String,
        limit: Int = 25,
        country: String = ItunesCatalogProvider.DEFAULT_COUNTRY,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val norm = CatalogQueryNormalizer.normalize(query)
        if (norm.isEmpty()) {
            return CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "artist",
                query = query,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Query cannot be empty."
            )
        }
        val safeLimit = limit.coerceIn(1, ItunesCatalogProvider.MAX_SEARCH_LIMIT)
        val cacheKey = "${CatalogProviders.ITUNES}:search:artist:${country.uppercase()}:$safeLimit:$norm"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = CatalogProviders.ITUNES,
                    entityType = "artist",
                    query = query.trim(),
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            val rawJson = itunesProvider.fetchSearchArtistsJson(query.trim(), safeLimit, country)
            val artists = OkHttpItunesCatalogClient.parseArtists(rawJson)
            database.withTransaction {
                artists.forEach { upsertArtistInTx(it, now) }
                cacheDao.upsert(
                    CatalogCacheEntity(
                        cacheKey = cacheKey,
                        provider = CatalogProviders.ITUNES,
                        responseJson = rawJson,
                        createdAt = now,
                        expiresAt = now + cacheTtlMs
                    )
                )
                recordSyncInTx(CatalogProviders.ITUNES, "artist", query.trim(), norm, now, artists.size, CatalogSyncStatus.SUCCESS)
            }
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "artist",
                query = query.trim(),
                fromCache = false,
                importedArtists = artists.size,
                status = CatalogSyncStatus.SUCCESS
            )
        } catch (e: Exception) {
            recordSyncFailure(CatalogProviders.ITUNES, "artist", query.trim(), norm, now)
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
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
        country: String = ItunesCatalogProvider.DEFAULT_COUNTRY,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val norm = CatalogQueryNormalizer.normalize(query)
        if (norm.isEmpty()) {
            return CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "album",
                query = query,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Query cannot be empty."
            )
        }
        val safeLimit = limit.coerceIn(1, ItunesCatalogProvider.MAX_SEARCH_LIMIT)
        val cacheKey = "${CatalogProviders.ITUNES}:search:album:${country.uppercase()}:$safeLimit:$norm"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = CatalogProviders.ITUNES,
                    entityType = "album",
                    query = query.trim(),
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            val rawJson = itunesProvider.fetchSearchAlbumsJson(query.trim(), safeLimit, country)
            val albums = OkHttpItunesCatalogClient.parseAlbums(rawJson)
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
                        provider = CatalogProviders.ITUNES,
                        responseJson = rawJson,
                        createdAt = now,
                        expiresAt = now + cacheTtlMs
                    )
                )
                recordSyncInTx(CatalogProviders.ITUNES, "album", query.trim(), norm, now, albums.size, CatalogSyncStatus.SUCCESS)
            }
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "album",
                query = query.trim(),
                fromCache = false,
                importedArtists = artistCount,
                importedAlbums = albums.size,
                status = CatalogSyncStatus.SUCCESS
            )
        } catch (e: Exception) {
            recordSyncFailure(CatalogProviders.ITUNES, "album", query.trim(), norm, now)
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
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
        country: String = ItunesCatalogProvider.DEFAULT_COUNTRY,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val norm = CatalogQueryNormalizer.normalize(query)
        if (norm.isEmpty()) {
            return CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "track",
                query = query,
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Query cannot be empty."
            )
        }
        val safeLimit = limit.coerceIn(1, ItunesCatalogProvider.MAX_SEARCH_LIMIT)
        val cacheKey = "${CatalogProviders.ITUNES}:search:track:${country.uppercase()}:$safeLimit:$norm"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = CatalogProviders.ITUNES,
                    entityType = "track",
                    query = query.trim(),
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            val rawJson = itunesProvider.fetchSearchTracksJson(query.trim(), safeLimit, country)
            val tracks = OkHttpItunesCatalogClient.parseTracks(rawJson)
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
                        provider = CatalogProviders.ITUNES,
                        responseJson = rawJson,
                        createdAt = now,
                        expiresAt = now + cacheTtlMs
                    )
                )
                recordSyncInTx(CatalogProviders.ITUNES, "track", query.trim(), norm, now, tracks.size, CatalogSyncStatus.SUCCESS)
            }
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "track",
                query = query.trim(),
                fromCache = false,
                importedArtists = artistCount,
                importedAlbums = albumCount,
                importedTracks = tracks.size,
                status = CatalogSyncStatus.SUCCESS
            )
        } catch (e: Exception) {
            recordSyncFailure(CatalogProviders.ITUNES, "track", query.trim(), norm, now)
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
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
        country: String = ItunesCatalogProvider.DEFAULT_COUNTRY,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val artist = artistDao.getById(localArtistId)
            ?: return CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "artist_albums",
                query = localArtistId.toString(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Artist not found in local catalog."
            )
        val safeLimit = limit.coerceIn(1, ItunesCatalogProvider.MAX_SEARCH_LIMIT)
        val cacheKey = "${CatalogProviders.ITUNES}:lookup:artist_albums:${country.uppercase()}:$safeLimit:${artist.providerArtistId}"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = CatalogProviders.ITUNES,
                    entityType = "artist_albums",
                    query = artist.providerArtistId,
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            val rawJson = itunesProvider.fetchArtistAlbumsJson(artist.providerArtistId, safeLimit, country)
            val albums = OkHttpItunesCatalogClient.parseAlbums(rawJson)
            database.withTransaction {
                albums.forEach { dto ->
                    upsertAlbumInTx(dto, artist.id, now)
                }
                cacheDao.upsert(
                    CatalogCacheEntity(
                        cacheKey = cacheKey,
                        provider = CatalogProviders.ITUNES,
                        responseJson = rawJson,
                        createdAt = now,
                        expiresAt = now + cacheTtlMs
                    )
                )
                recordSyncInTx(
                    CatalogProviders.ITUNES,
                    "artist_albums",
                    artist.providerArtistId,
                    artist.providerArtistId.lowercase(),
                    now,
                    albums.size,
                    CatalogSyncStatus.SUCCESS
                )
            }
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "artist_albums",
                query = artist.providerArtistId,
                fromCache = false,
                importedAlbums = albums.size,
                status = CatalogSyncStatus.SUCCESS
            )
        } catch (e: Exception) {
            recordSyncFailure(CatalogProviders.ITUNES, "artist_albums", artist.providerArtistId, artist.providerArtistId.lowercase(), now)
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
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
        country: String = ItunesCatalogProvider.DEFAULT_COUNTRY,
        forceRefresh: Boolean = false
    ): CatalogSyncResult {
        val album = albumDao.getEntityById(localAlbumId)
            ?: return CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "album_tracks",
                query = localAlbumId.toString(),
                fromCache = false,
                status = CatalogSyncStatus.FAILED,
                errorMessage = "Album not found in local catalog."
            )
        val cacheKey = "${CatalogProviders.ITUNES}:lookup:album_tracks:${country.uppercase()}:${album.providerAlbumId}"
        val now = clockMs()

        if (!forceRefresh) {
            val cached = cacheDao.get(cacheKey)
            if (cached != null && cached.expiresAt > now) {
                return CatalogSyncResult(
                    provider = CatalogProviders.ITUNES,
                    entityType = "album_tracks",
                    query = album.providerAlbumId,
                    fromCache = true,
                    status = CatalogSyncStatus.CACHED
                )
            }
        }

        return try {
            val rawJson = itunesProvider.fetchAlbumTracklistJson(album.providerAlbumId, country)
            val parsed = OkHttpItunesCatalogClient.parseAlbumLookup(rawJson)
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
                        provider = CatalogProviders.ITUNES,
                        responseJson = rawJson,
                        createdAt = now,
                        expiresAt = now + cacheTtlMs
                    )
                )
                recordSyncInTx(
                    CatalogProviders.ITUNES,
                    "album_tracks",
                    album.providerAlbumId,
                    album.providerAlbumId.lowercase(),
                    now,
                    parsed.tracks.size,
                    CatalogSyncStatus.SUCCESS
                )
            }
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
                entityType = "album_tracks",
                query = album.providerAlbumId,
                fromCache = false,
                importedTracks = parsed.tracks.size,
                status = CatalogSyncStatus.SUCCESS
            )
        } catch (e: Exception) {
            recordSyncFailure(CatalogProviders.ITUNES, "album_tracks", album.providerAlbumId, album.providerAlbumId.lowercase(), now)
            CatalogSyncResult(
                provider = CatalogProviders.ITUNES,
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
