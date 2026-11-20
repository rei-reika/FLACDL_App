package com.example.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.catalog.model.CatalogSyncResult
import com.example.catalog.model.CatalogSyncStatus
import com.example.catalog.repository.LocalCatalogRepository
import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadResolutionResult
import com.example.catalog.resolver.DownloadSourceResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CatalogViewModel(
    private val repository: LocalCatalogRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val downloadSourceResolver: DownloadSourceResolver? = null
) : ViewModel() {

    private val _searchUiState = MutableStateFlow(CatalogSearchUiState())
    val searchUiState: StateFlow<CatalogSearchUiState> = _searchUiState.asStateFlow()

    private val _artistDetailUiState = MutableStateFlow(ArtistDetailUiState())
    val artistDetailUiState: StateFlow<ArtistDetailUiState> = _artistDetailUiState.asStateFlow()

    private val _albumDetailUiState = MutableStateFlow(AlbumDetailUiState())
    val albumDetailUiState: StateFlow<AlbumDetailUiState> = _albumDetailUiState.asStateFlow()

    private var searchDebounceJob: Job? = null
    private var artistDetailJob: Job? = null
    private var albumDetailJob: Job? = null
    private var albumResolutionJob: Job? = null

    fun onQueryChanged(query: String) {
        _searchUiState.update {
            it.copy(
                query = query,
                errorMessage = null,
                syncNotice = null
            )
        }
        searchDebounceJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _searchUiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    hasSearched = false,
                    artists = emptyList(),
                    albums = emptyList(),
                    tracks = emptyList(),
                    artistsTotal = 0,
                    albumsTotal = 0,
                    tracksTotal = 0,
                    hasMoreArtists = false,
                    hasMoreAlbums = false,
                    hasMoreTracks = false,
                    errorMessage = null
                )
            }
            return
        }

        searchDebounceJob = viewModelScope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            executeLocalSearch(trimmed, _searchUiState.value.selectedCategory)
        }
    }

    fun clearQuery() {
        onQueryChanged("")
    }

    fun onCategorySelected(category: CatalogSearchCategory) {
        if (_searchUiState.value.selectedCategory == category) return
        _searchUiState.update {
            it.copy(
                selectedCategory = category,
                errorMessage = null
            )
        }
        val trimmed = _searchUiState.value.query.trim()
        if (trimmed.isNotEmpty()) {
            searchDebounceJob?.cancel()
            searchDebounceJob = viewModelScope.launch {
                executeLocalSearch(trimmed, category)
            }
        }
    }

    fun submitLocalSearch() {
        val trimmed = _searchUiState.value.query.trim()
        searchDebounceJob?.cancel()
        if (trimmed.isEmpty()) {
            _searchUiState.update {
                it.copy(
                    isLoading = false,
                    hasSearched = false,
                    errorMessage = null
                )
            }
            return
        }
        searchDebounceJob = viewModelScope.launch {
            executeLocalSearch(trimmed, _searchUiState.value.selectedCategory)
        }
    }

    fun loadMore(category: CatalogSearchCategory = _searchUiState.value.selectedCategory) {
        val state = _searchUiState.value
        val trimmed = state.query.trim()
        if (trimmed.isEmpty() || state.isLoading || state.isLoadingMore) return

        when (category) {
            CatalogSearchCategory.ARTISTS -> {
                if (!state.hasMoreArtists) return
                viewModelScope.launch {
                    _searchUiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
                    try {
                        val page = withContext(ioDispatcher) {
                            repository.searchArtists(
                                query = trimmed,
                                limit = CATEGORY_PAGE_SIZE,
                                offset = state.artists.size
                            )
                        }
                        _searchUiState.update { current ->
                            val merged = (current.artists + page.items.map { it.toUi() })
                                .distinctBy { it.id }
                            current.copy(
                                isLoadingMore = false,
                                artists = merged,
                                artistsTotal = page.totalCount,
                                hasMoreArtists = merged.size < page.totalCount
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _searchUiState.update {
                            it.copy(
                                isLoadingMore = false,
                                errorMessage = e.message ?: "Gagal memuat halaman artis berikutnya."
                            )
                        }
                    }
                }
            }

            CatalogSearchCategory.ALBUMS -> {
                if (!state.hasMoreAlbums) return
                viewModelScope.launch {
                    _searchUiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
                    try {
                        val page = withContext(ioDispatcher) {
                            repository.searchAlbums(
                                query = trimmed,
                                limit = CATEGORY_PAGE_SIZE,
                                offset = state.albums.size
                            )
                        }
                        _searchUiState.update { current ->
                            val merged = (current.albums + page.items.map { it.toUi() })
                                .distinctBy { it.id }
                            current.copy(
                                isLoadingMore = false,
                                albums = merged,
                                albumsTotal = page.totalCount,
                                hasMoreAlbums = merged.size < page.totalCount
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _searchUiState.update {
                            it.copy(
                                isLoadingMore = false,
                                errorMessage = e.message ?: "Gagal memuat halaman album berikutnya."
                            )
                        }
                    }
                }
            }

            CatalogSearchCategory.TRACKS -> {
                if (!state.hasMoreTracks) return
                viewModelScope.launch {
                    _searchUiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
                    try {
                        val page = withContext(ioDispatcher) {
                            repository.searchTracks(
                                query = trimmed,
                                limit = CATEGORY_PAGE_SIZE,
                                offset = state.tracks.size
                            )
                        }
                        _searchUiState.update { current ->
                            val merged = (current.tracks + page.items.map { it.toUi() })
                                .distinctBy { it.id }
                            current.copy(
                                isLoadingMore = false,
                                tracks = merged,
                                tracksTotal = page.totalCount,
                                hasMoreTracks = merged.size < page.totalCount
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        _searchUiState.update {
                            it.copy(
                                isLoadingMore = false,
                                errorMessage = e.message ?: "Gagal memuat halaman lagu berikutnya."
                            )
                        }
                    }
                }
            }

            CatalogSearchCategory.ALL -> {
                // In ALL view, pagination is triggered per section via explicit category argument.
            }
        }
    }

    /**
     * Explicit, user-triggered on-demand import from Apple iTunes into local Room DB.
     * Never called automatically on keystrokes.
     */
    fun syncCurrentQuery(forceRefresh: Boolean = false) {
        val state = _searchUiState.value
        val trimmed = state.query.trim()
        if (trimmed.isEmpty() || state.isSyncing) return

        viewModelScope.launch {
            _searchUiState.update {
                it.copy(
                    isSyncing = true,
                    errorMessage = null,
                    syncNotice = null
                )
            }
            try {
                val results: List<CatalogSyncResult> = withContext(ioDispatcher) {
                    when (state.selectedCategory) {
                        CatalogSearchCategory.ARTISTS -> listOf(
                            repository.syncSearchArtists(
                                query = trimmed,
                                limit = SYNC_ARTIST_LIMIT,
                                forceRefresh = forceRefresh
                            )
                        )

                        CatalogSearchCategory.ALBUMS -> listOf(
                            repository.syncSearchAlbums(
                                query = trimmed,
                                limit = SYNC_ALBUM_LIMIT,
                                forceRefresh = forceRefresh
                            )
                        )

                        CatalogSearchCategory.TRACKS -> listOf(
                            repository.syncSearchTracks(
                                query = trimmed,
                                limit = SYNC_TRACK_LIMIT,
                                forceRefresh = forceRefresh
                            )
                        )

                        CatalogSearchCategory.ALL -> listOf(
                            repository.syncSearchArtists(
                                query = trimmed,
                                limit = SYNC_ALL_ARTIST_LIMIT,
                                forceRefresh = forceRefresh
                            ),
                            repository.syncSearchAlbums(
                                query = trimmed,
                                limit = SYNC_ALL_ALBUM_LIMIT,
                                forceRefresh = forceRefresh
                            ),
                            repository.syncSearchTracks(
                                query = trimmed,
                                limit = SYNC_ALL_TRACK_LIMIT,
                                forceRefresh = forceRefresh
                            )
                        )
                    }
                }

                val failures = results.filter { it.status == CatalogSyncStatus.FAILED }
                val allCached = results.isNotEmpty() && results.all { it.status == CatalogSyncStatus.CACHED }
                val totalArtists = results.sumOf { it.importedArtists }
                val totalAlbums = results.sumOf { it.importedAlbums }
                val totalTracks = results.sumOf { it.importedTracks }

                val notice = when {
                    failures.size == results.size -> null
                    allCached -> "Metadata sudah tersimpan di cache lokal."
                    else -> buildString {
                        append("Sinkronisasi selesai: ")
                        val parts = buildList {
                            if (totalArtists > 0) add("$totalArtists artis")
                            if (totalAlbums > 0) add("$totalAlbums album")
                            if (totalTracks > 0) add("$totalTracks lagu")
                        }
                        append(if (parts.isEmpty()) "tidak ada entri baru." else parts.joinToString(", ") + ".")
                    }
                }

                val errorMsg = if (failures.isNotEmpty()) {
                    failures.firstNotNullOfOrNull { it.errorMessage }
                        ?: "Sebagian sinkronisasi katalog gagal."
                } else {
                    null
                }

                _searchUiState.update {
                    it.copy(
                        isSyncing = false,
                        syncNotice = notice,
                        errorMessage = errorMsg
                    )
                }

                // Refresh local Room view immediately after sync.
                executeLocalSearch(trimmed, _searchUiState.value.selectedCategory, clearSyncNotice = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchUiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = e.message ?: "Sinkronisasi katalog gagal."
                    )
                }
            }
        }
    }

    private suspend fun executeLocalSearch(
        trimmedQuery: String,
        category: CatalogSearchCategory,
        clearSyncNotice: Boolean = true
    ) {
        _searchUiState.update {
            it.copy(
                isLoading = true,
                errorMessage = null,
                syncNotice = if (clearSyncNotice) null else it.syncNotice
            )
        }
        try {
            when (category) {
                CatalogSearchCategory.ALL -> {
                    val (artistsPage, albumsPage, tracksPage) = withContext(ioDispatcher) {
                        Triple(
                            repository.searchArtists(trimmedQuery, limit = ALL_SECTION_PAGE_SIZE, offset = 0),
                            repository.searchAlbums(trimmedQuery, limit = ALL_SECTION_PAGE_SIZE, offset = 0),
                            repository.searchTracks(trimmedQuery, limit = ALL_SECTION_PAGE_SIZE, offset = 0)
                        )
                    }
                    _searchUiState.update {
                        it.copy(
                            isLoading = false,
                            hasSearched = true,
                            artists = artistsPage.items.map { a -> a.toUi() },
                            albums = albumsPage.items.map { a -> a.toUi() },
                            tracks = tracksPage.items.map { t -> t.toUi() },
                            artistsTotal = artistsPage.totalCount,
                            albumsTotal = albumsPage.totalCount,
                            tracksTotal = tracksPage.totalCount,
                            hasMoreArtists = artistsPage.hasMore,
                            hasMoreAlbums = albumsPage.hasMore,
                            hasMoreTracks = tracksPage.hasMore
                        )
                    }
                }

                CatalogSearchCategory.ARTISTS -> {
                    val page = withContext(ioDispatcher) {
                        repository.searchArtists(trimmedQuery, limit = CATEGORY_PAGE_SIZE, offset = 0)
                    }
                    _searchUiState.update {
                        it.copy(
                            isLoading = false,
                            hasSearched = true,
                            artists = page.items.map { a -> a.toUi() },
                            artistsTotal = page.totalCount,
                            hasMoreArtists = page.hasMore
                        )
                    }
                }

                CatalogSearchCategory.ALBUMS -> {
                    val page = withContext(ioDispatcher) {
                        repository.searchAlbums(trimmedQuery, limit = CATEGORY_PAGE_SIZE, offset = 0)
                    }
                    _searchUiState.update {
                        it.copy(
                            isLoading = false,
                            hasSearched = true,
                            albums = page.items.map { a -> a.toUi() },
                            albumsTotal = page.totalCount,
                            hasMoreAlbums = page.hasMore
                        )
                    }
                }

                CatalogSearchCategory.TRACKS -> {
                    val page = withContext(ioDispatcher) {
                        repository.searchTracks(trimmedQuery, limit = CATEGORY_PAGE_SIZE, offset = 0)
                    }
                    _searchUiState.update {
                        it.copy(
                            isLoading = false,
                            hasSearched = true,
                            tracks = page.items.map { t -> t.toUi() },
                            tracksTotal = page.totalCount,
                            hasMoreTracks = page.hasMore
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _searchUiState.update {
                it.copy(
                    isLoading = false,
                    hasSearched = true,
                    errorMessage = e.message ?: "Gagal membaca katalog lokal."
                )
            }
        }
    }

    // =========================================================================
    // Artist Detail
    // =========================================================================

    fun loadArtistDetail(artistId: Long) {
        artistDetailJob?.cancel()
        artistDetailJob = viewModelScope.launch {
            _artistDetailUiState.update {
                ArtistDetailUiState(
                    artistId = artistId,
                    isLoading = true
                )
            }
            try {
                val (artist, discography) = withContext(ioDispatcher) {
                    val a = repository.getArtistDetails(artistId)
                    val d = repository.getArtistDiscography(artistId, limit = DETAIL_PAGE_SIZE, offset = 0)
                    a to d
                }
                if (artist == null) {
                    _artistDetailUiState.update {
                        it.copy(
                            isLoading = false,
                            artist = null,
                            albums = emptyList(),
                            errorMessage = "Artis tidak ditemukan di katalog lokal."
                        )
                    }
                } else {
                    _artistDetailUiState.update {
                        it.copy(
                            isLoading = false,
                            artist = artist.toUi(),
                            albums = discography.items.map { album -> album.toUi() },
                            totalAlbums = discography.totalCount,
                            hasMoreAlbums = discography.hasMore,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _artistDetailUiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Gagal memuat detail artis."
                    )
                }
            }
        }
    }

    fun loadMoreArtistAlbums() {
        val state = _artistDetailUiState.value
        val artistId = state.artistId ?: return
        if (state.isLoading || state.isLoadingMore || !state.hasMoreAlbums) return

        viewModelScope.launch {
            _artistDetailUiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            try {
                val page = withContext(ioDispatcher) {
                    repository.getArtistDiscography(
                        artistId = artistId,
                        limit = DETAIL_PAGE_SIZE,
                        offset = state.albums.size
                    )
                }
                _artistDetailUiState.update { current ->
                    val merged = (current.albums + page.items.map { it.toUi() }).distinctBy { it.id }
                    current.copy(
                        isLoadingMore = false,
                        albums = merged,
                        totalAlbums = page.totalCount,
                        hasMoreAlbums = merged.size < page.totalCount
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _artistDetailUiState.update {
                    it.copy(
                        isLoadingMore = false,
                        errorMessage = e.message ?: "Gagal memuat diskografi berikutnya."
                    )
                }
            }
        }
    }

    fun syncArtistAlbums(artistId: Long, forceRefresh: Boolean = false) {
        if (_artistDetailUiState.value.isSyncing) return
        viewModelScope.launch {
            _artistDetailUiState.update {
                it.copy(isSyncing = true, errorMessage = null, syncNotice = null)
            }
            try {
                val syncResult = withContext(ioDispatcher) {
                    repository.syncArtistDiscography(
                        localArtistId = artistId,
                        limit = SYNC_DISCOGRAPHY_LIMIT,
                        forceRefresh = forceRefresh
                    )
                }
                val (artist, discography) = withContext(ioDispatcher) {
                    val a = repository.getArtistDetails(artistId)
                    val d = repository.getArtistDiscography(artistId, limit = DETAIL_PAGE_SIZE, offset = 0)
                    a to d
                }
                val notice = when (syncResult.status) {
                    CatalogSyncStatus.CACHED -> "Diskografi artis sudah ada di cache lokal."
                    CatalogSyncStatus.SUCCESS -> "Berhasil menyinkronkan ${syncResult.importedAlbums} album."
                    CatalogSyncStatus.FAILED -> null
                }
                _artistDetailUiState.update {
                    it.copy(
                        isSyncing = false,
                        artist = artist?.toUi() ?: it.artist,
                        albums = discography.items.map { album -> album.toUi() },
                        totalAlbums = discography.totalCount,
                        hasMoreAlbums = discography.hasMore,
                        syncNotice = notice,
                        errorMessage = if (syncResult.status == CatalogSyncStatus.FAILED) {
                            syncResult.errorMessage ?: "Sinkronisasi diskografi gagal."
                        } else {
                            null
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _artistDetailUiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = e.message ?: "Sinkronisasi diskografi gagal."
                    )
                }
            }
        }
    }

    // =========================================================================
    // Album Detail
    // =========================================================================

    fun loadAlbumDetail(albumId: Long) {
        albumDetailJob?.cancel()
        albumResolutionJob?.cancel()
        val hasSource = downloadSourceResolver?.capabilities?.supportsResolution == true
        albumDetailJob = viewModelScope.launch {
            _albumDetailUiState.update {
                AlbumDetailUiState(
                    albumId = albumId,
                    isLoading = true,
                    hasConfiguredSource = hasSource
                )
            }
            try {
                val (album, tracklist) = withContext(ioDispatcher) {
                    val a = repository.getAlbumDetails(albumId)
                    val t = repository.getAlbumTracklist(albumId, limit = TRACKLIST_PAGE_SIZE, offset = 0)
                    a to t
                }
                if (album == null) {
                    _albumDetailUiState.update {
                        it.copy(
                            isLoading = false,
                            album = null,
                            tracks = emptyList(),
                            errorMessage = "Album tidak ditemukan di katalog lokal."
                        )
                    }
                } else {
                    _albumDetailUiState.update {
                        it.copy(
                            isLoading = false,
                            album = album.toUi(),
                            tracks = tracklist.items.map { tr -> tr.toUi() },
                            totalTracks = tracklist.totalCount,
                            hasMoreTracks = tracklist.hasMore,
                            errorMessage = null
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _albumDetailUiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Gagal memuat detail album."
                    )
                }
            }
        }
    }

    fun loadMoreAlbumTracks() {
        val state = _albumDetailUiState.value
        val albumId = state.albumId ?: return
        if (state.isLoading || state.isLoadingMore || !state.hasMoreTracks) return

        viewModelScope.launch {
            _albumDetailUiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            try {
                val page = withContext(ioDispatcher) {
                    repository.getAlbumTracklist(
                        albumId = albumId,
                        limit = TRACKLIST_PAGE_SIZE,
                        offset = state.tracks.size
                    )
                }
                _albumDetailUiState.update { current ->
                    val merged = (current.tracks + page.items.map { it.toUi() }).distinctBy { it.id }
                    current.copy(
                        isLoadingMore = false,
                        tracks = merged,
                        totalTracks = page.totalCount,
                        hasMoreTracks = merged.size < page.totalCount
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _albumDetailUiState.update {
                    it.copy(
                        isLoadingMore = false,
                        errorMessage = e.message ?: "Gagal memuat daftar lagu berikutnya."
                    )
                }
            }
        }
    }

    fun syncAlbumTracks(albumId: Long, forceRefresh: Boolean = false) {
        if (_albumDetailUiState.value.isSyncing) return
        viewModelScope.launch {
            _albumDetailUiState.update {
                it.copy(isSyncing = true, errorMessage = null, syncNotice = null)
            }
            try {
                val syncResult = withContext(ioDispatcher) {
                    repository.syncAlbumTracklist(
                        localAlbumId = albumId,
                        forceRefresh = forceRefresh
                    )
                }
                val (album, tracklist) = withContext(ioDispatcher) {
                    val a = repository.getAlbumDetails(albumId)
                    val t = repository.getAlbumTracklist(albumId, limit = TRACKLIST_PAGE_SIZE, offset = 0)
                    a to t
                }
                val notice = when (syncResult.status) {
                    CatalogSyncStatus.CACHED -> "Tracklist album sudah ada di cache lokal."
                    CatalogSyncStatus.SUCCESS -> "Berhasil menyinkronkan ${syncResult.importedTracks} lagu."
                    CatalogSyncStatus.FAILED -> null
                }
                _albumDetailUiState.update {
                    it.copy(
                        isSyncing = false,
                        album = album?.toUi() ?: it.album,
                        tracks = tracklist.items.map { tr -> tr.toUi() },
                        totalTracks = tracklist.totalCount,
                        hasMoreTracks = tracklist.hasMore,
                        syncNotice = notice,
                        errorMessage = if (syncResult.status == CatalogSyncStatus.FAILED) {
                            syncResult.errorMessage ?: "Sinkronisasi tracklist gagal."
                        } else {
                            null
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _albumDetailUiState.update {
                    it.copy(
                        isSyncing = false,
                        errorMessage = e.message ?: "Sinkronisasi tracklist gagal."
                    )
                }
            }
        }
    }

    fun refreshAlbumSourceAvailabilityFlag() {
        val hasSource = downloadSourceResolver?.capabilities?.supportsResolution == true
        _albumDetailUiState.update { it.copy(hasConfiguredSource = hasSource) }
    }

    /**
     * Explicit, user-triggered read-only source availability check for loaded album tracks.
     * Never triggered automatically during recomposition and never initiates audio downloads.
     */
    fun checkAlbumSourceAvailability() {
        val resolver = downloadSourceResolver ?: return
        val currentState = _albumDetailUiState.value
        val album = currentState.album ?: return
        if (currentState.isResolvingSources || currentState.tracks.isEmpty()) return

        if (!resolver.capabilities.supportsResolution) {
            _albumDetailUiState.update {
                it.copy(
                    hasConfiguredSource = false,
                    resolutionSummary = "Belum ada server OpenSubsonic aktif."
                )
            }
            return
        }

        val tracksToCheck = currentState.tracks.take(MAX_TRACKS_PER_AVAILABILITY_CHECK)
        val releaseYear = album.releaseYear?.toIntOrNull()

        albumResolutionJob?.cancel()
        albumResolutionJob = viewModelScope.launch {
            val initialMap = currentState.trackResolutions.toMutableMap()
            tracksToCheck.forEach { tr -> initialMap[tr.id] = TrackResolutionStatusUi.Checking }
            _albumDetailUiState.update {
                it.copy(
                    hasConfiguredSource = true,
                    isResolvingSources = true,
                    trackResolutions = initialMap.toMap(),
                    resolutionSummary = null
                )
            }

            var availableCount = 0
            var ambiguousCount = 0
            var unverifiedCount = 0
            var unavailableCount = 0
            var errorCount = 0

            val updatedMap = initialMap.toMutableMap()
            try {
                for (track in tracksToCheck) {
                    val identity = CanonicalTrackIdentity(
                        localTrackId = track.id,
                        catalogProviderId = album.provider.ifBlank { "local_catalog" },
                        catalogTrackId = track.id.toString(),
                        title = track.title,
                        artist = track.artistName,
                        album = album.title,
                        durationMs = track.durationMs,
                        trackNumber = track.trackNumber,
                        releaseYear = releaseYear,
                        isrc = track.isrc
                    )
                    val uiStatus = when (val res = withContext(ioDispatcher) { resolver.resolve(identity) }) {
                        is DownloadResolutionResult.Resolved -> {
                            availableCount++
                            val hint = res.candidate.qualityHints.firstOrNull { it == "FLAC" || it == "LOSSLESS" }
                            TrackResolutionStatusUi.Available(qualityHint = hint)
                        }

                        is DownloadResolutionResult.Ambiguous -> {
                            ambiguousCount++
                            TrackResolutionStatusUi.Ambiguous(res.candidates.size)
                        }

                        is DownloadResolutionResult.Unverified -> {
                            unverifiedCount++
                            TrackResolutionStatusUi.Unverified(res.reason)
                        }

                        is DownloadResolutionResult.Unavailable -> {
                            unavailableCount++
                            TrackResolutionStatusUi.Unavailable(res.reason)
                        }

                        is DownloadResolutionResult.Error -> {
                            errorCount++
                            TrackResolutionStatusUi.Error(res.message)
                        }
                    }
                    updatedMap[track.id] = uiStatus
                    _albumDetailUiState.update { it.copy(trackResolutions = updatedMap.toMap()) }
                }

                val summaryParts = buildList {
                    if (availableCount > 0) add("$availableCount tersedia")
                    if (ambiguousCount > 0) add("$ambiguousCount ambigu")
                    if (unverifiedCount > 0) add("$unverifiedCount belum terverifikasi")
                    if (unavailableCount > 0) add("$unavailableCount tidak tersedia")
                    if (errorCount > 0) add("$errorCount error")
                }
                _albumDetailUiState.update {
                    it.copy(
                        isResolvingSources = false,
                        resolutionSummary = summaryParts.joinToString(" • ").ifEmpty { "Pemeriksaan selesai." }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _albumDetailUiState.update {
                    it.copy(
                        isResolvingSources = false,
                        resolutionSummary = "Pemeriksaan ketersediaan sumber terhenti."
                    )
                }
            }
        }
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L
        const val ALL_SECTION_PAGE_SIZE = 10
        const val CATEGORY_PAGE_SIZE = 20
        const val DETAIL_PAGE_SIZE = 20
        const val TRACKLIST_PAGE_SIZE = 50
        private const val MAX_TRACKS_PER_AVAILABILITY_CHECK = 20

        private const val SYNC_ARTIST_LIMIT = 20
        private const val SYNC_ALBUM_LIMIT = 25
        private const val SYNC_TRACK_LIMIT = 25
        private const val SYNC_ALL_ARTIST_LIMIT = 10
        private const val SYNC_ALL_ALBUM_LIMIT = 15
        private const val SYNC_ALL_TRACK_LIMIT = 20
        private const val SYNC_DISCOGRAPHY_LIMIT = 50
    }
}
