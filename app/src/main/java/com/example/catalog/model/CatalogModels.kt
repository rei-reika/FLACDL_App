package com.example.catalog.model

object CatalogProviders {
    const val ITUNES = "apple_itunes"
    const val MUSICBRAINZ = "musicbrainz"
}

enum class CatalogSyncStatus(val wireValue: String) {
    SUCCESS("success"),
    CACHED("cached"),
    FAILED("failed")
}

object CatalogQueryNormalizer {
    /**
     * Normalize search queries and indexed text for consistent case-insensitive matching
     * and whitespace folding without relying on locale-specific rules.
     */
    fun normalize(raw: String): String {
        return raw.trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }
}

data class CatalogArtist(
    val id: Long,
    val providerArtistId: String,
    val musicBrainzArtistId: String? = null,
    val name: String,
    val primaryGenre: String? = null,
    val country: String? = null,
    val artworkUrl: String? = null,
    val provider: String = CatalogProviders.ITUNES,
    val lastSyncedAt: Long = 0L
)

data class CatalogAlbum(
    val id: Long,
    val providerAlbumId: String,
    val musicBrainzReleaseGroupId: String? = null,
    val artistId: Long,
    val artistName: String,
    val title: String,
    val releaseDate: String? = null,
    val genre: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val provider: String = CatalogProviders.ITUNES,
    val lastSyncedAt: Long = 0L
)

data class CatalogTrack(
    val id: Long,
    val providerTrackId: String,
    val musicBrainzRecordingId: String? = null,
    val albumId: Long? = null,
    val albumTitle: String? = null,
    val artistId: Long,
    val artistName: String,
    val title: String,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val isrc: String? = null,
    val artworkUrl: String? = null,
    val provider: String = CatalogProviders.ITUNES,
    val lastSyncedAt: Long = 0L
)

data class CatalogPage<T>(
    val items: List<T>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int
) {
    val hasMore: Boolean
        get() = (offset + items.size) < totalCount
}

data class CatalogSyncResult(
    val provider: String,
    val entityType: String,
    val query: String,
    val fromCache: Boolean,
    val importedArtists: Int = 0,
    val importedAlbums: Int = 0,
    val importedTracks: Int = 0,
    val status: CatalogSyncStatus = CatalogSyncStatus.SUCCESS,
    val errorMessage: String? = null
)

data class CatalogEnrichmentResult(
    val matched: Boolean,
    val musicBrainzId: String? = null,
    val updatedFields: List<String> = emptyList(),
    val errorMessage: String? = null
)

// =============================================================================
// Upstream Provider DTOs (kept strictly separate from Room entities & Amazon URLs)
// =============================================================================

data class RemoteArtistDto(
    val providerArtistId: String,
    val name: String,
    val primaryGenre: String? = null,
    val country: String? = null,
    val artworkUrl: String? = null,
    val provider: String = CatalogProviders.ITUNES
)

data class RemoteAlbumDto(
    val providerAlbumId: String,
    val providerArtistId: String,
    val artistName: String,
    val title: String,
    val releaseDate: String? = null,
    val genre: String? = null,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val provider: String = CatalogProviders.ITUNES
)

data class RemoteTrackDto(
    val providerTrackId: String,
    val providerArtistId: String,
    val artistName: String,
    val providerAlbumId: String? = null,
    val albumTitle: String? = null,
    val albumArtworkUrl: String? = null,
    val albumReleaseDate: String? = null,
    val albumTrackCount: Int = 0,
    val title: String,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val genre: String? = null,
    val provider: String = CatalogProviders.ITUNES
)

data class RemoteAlbumLookupResult(
    val album: RemoteAlbumDto?,
    val tracks: List<RemoteTrackDto>
)

data class MusicBrainzArtistEnrichment(
    val musicBrainzArtistId: String,
    val country: String? = null,
    val disambiguation: String? = null
)

data class MusicBrainzReleaseGroupEnrichment(
    val musicBrainzReleaseGroupId: String,
    val firstReleaseDate: String? = null
)

data class MusicBrainzRecordingEnrichment(
    val musicBrainzRecordingId: String,
    val durationMs: Long? = null,
    val isrc: String? = null
)
