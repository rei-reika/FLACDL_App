package com.example.ui.catalog

import com.example.catalog.model.CatalogAlbum
import com.example.catalog.model.CatalogArtist
import com.example.catalog.model.CatalogTrack

enum class CatalogSearchCategory(val label: String) {
    ALL("All"),
    ARTISTS("Artists"),
    ALBUMS("Albums"),
    TRACKS("Tracks")
}

data class CatalogArtistUi(
    val id: Long,
    val name: String,
    val primaryGenre: String? = null,
    val country: String? = null,
    val artworkUrl: String? = null,
    val musicBrainzArtistId: String? = null,
    val provider: String = ""
)

data class CatalogAlbumUi(
    val id: Long,
    val artistId: Long,
    val artistName: String,
    val title: String,
    val releaseDate: String? = null,
    val releaseYear: String? = null,
    val genre: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int? = null,
    val musicBrainzReleaseGroupId: String? = null,
    val provider: String = ""
)

data class CatalogTrackUi(
    val id: Long,
    val artistId: Long,
    val artistName: String,
    val albumId: Long? = null,
    val albumTitle: String? = null,
    val title: String,
    val durationMs: Long? = null,
    val durationText: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val artworkUrl: String? = null,
    val isrc: String? = null
)

data class CatalogSearchUiState(
    val query: String = "",
    val selectedCategory: CatalogSearchCategory = CatalogSearchCategory.ALL,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasSearched: Boolean = false,
    val artists: List<CatalogArtistUi> = emptyList(),
    val albums: List<CatalogAlbumUi> = emptyList(),
    val tracks: List<CatalogTrackUi> = emptyList(),
    val artistsTotal: Int = 0,
    val albumsTotal: Int = 0,
    val tracksTotal: Int = 0,
    val hasMoreArtists: Boolean = false,
    val hasMoreAlbums: Boolean = false,
    val hasMoreTracks: Boolean = false,
    val syncNotice: String? = null,
    val errorMessage: String? = null
) {
    val isEmptyResults: Boolean
        get() = when (selectedCategory) {
            CatalogSearchCategory.ALL -> artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
            CatalogSearchCategory.ARTISTS -> artists.isEmpty()
            CatalogSearchCategory.ALBUMS -> albums.isEmpty()
            CatalogSearchCategory.TRACKS -> tracks.isEmpty()
        }
}

data class ArtistDetailUiState(
    val artistId: Long? = null,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val artist: CatalogArtistUi? = null,
    val albums: List<CatalogAlbumUi> = emptyList(),
    val totalAlbums: Int = 0,
    val hasMoreAlbums: Boolean = false,
    val syncNotice: String? = null,
    val errorMessage: String? = null
)

data class AlbumDetailUiState(
    val albumId: Long? = null,
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val album: CatalogAlbumUi? = null,
    val tracks: List<CatalogTrackUi> = emptyList(),
    val totalTracks: Int = 0,
    val hasMoreTracks: Boolean = false,
    val syncNotice: String? = null,
    val errorMessage: String? = null
)

internal object CatalogFormatters {
    fun formatDurationMs(durationMs: Long?): String? {
        if (durationMs == null || durationMs <= 0L) return null
        val totalSeconds = durationMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    fun extractReleaseYear(releaseDate: String?): String? {
        val trimmed = releaseDate?.trim().orEmpty()
        if (trimmed.length < 4) return null
        val year = trimmed.substring(0, 4)
        return if (year.all { it.isDigit() }) year else null
    }

    fun formatReleaseDate(releaseDate: String?): String? {
        val trimmed = releaseDate?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        return if (trimmed.length >= 10 && trimmed[4] == '-' && trimmed[7] == '-') {
            trimmed.substring(0, 10)
        } else {
            trimmed
        }
    }
}

internal fun CatalogArtist.toUi(): CatalogArtistUi = CatalogArtistUi(
    id = id,
    name = name,
    primaryGenre = primaryGenre?.takeIf { it.isNotBlank() },
    country = country?.takeIf { it.isNotBlank() },
    artworkUrl = artworkUrl?.takeIf { it.isNotBlank() },
    musicBrainzArtistId = musicBrainzArtistId?.takeIf { it.isNotBlank() },
    provider = provider
)

internal fun CatalogAlbum.toUi(): CatalogAlbumUi = CatalogAlbumUi(
    id = id,
    artistId = artistId,
    artistName = artistName,
    title = title,
    releaseDate = CatalogFormatters.formatReleaseDate(releaseDate),
    releaseYear = CatalogFormatters.extractReleaseYear(releaseDate),
    genre = genre?.takeIf { it.isNotBlank() },
    artworkUrl = artworkUrl?.takeIf { it.isNotBlank() },
    trackCount = trackCount.takeIf { it > 0 },
    musicBrainzReleaseGroupId = musicBrainzReleaseGroupId?.takeIf { it.isNotBlank() },
    provider = provider
)

internal fun CatalogTrack.toUi(): CatalogTrackUi = CatalogTrackUi(
    id = id,
    artistId = artistId,
    artistName = artistName,
    albumId = albumId,
    albumTitle = albumTitle?.takeIf { it.isNotBlank() },
    title = title,
    durationMs = durationMs,
    durationText = CatalogFormatters.formatDurationMs(durationMs),
    trackNumber = trackNumber?.takeIf { it > 0 },
    discNumber = discNumber?.takeIf { it > 0 },
    artworkUrl = artworkUrl?.takeIf { it.isNotBlank() },
    isrc = isrc?.takeIf { it.isNotBlank() }
)
