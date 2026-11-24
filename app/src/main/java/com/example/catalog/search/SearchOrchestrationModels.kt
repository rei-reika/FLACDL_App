package com.example.catalog.search

import com.example.catalog.model.CatalogTrack
import com.example.catalog.resolver.CanonicalTrackIdentity

/**
 * Internal matching & provenance metadata attached to a [StructuredTrackCandidate].
 * Kept strictly separate from consumer-facing display properties so UI screens
 * never expose raw matching diagnostics, scores, or provider internals.
 */
data class CandidateMatchMetadata(
    val mergedCandidateCount: Int = 1,
    val mergedByIsrc: Boolean = false,
    val confidenceScore: Double = 1.0,
    val sourceProviders: Set<String> = emptySet()
)

/**
 * Provider-agnostic, consumer-ready track search candidate.
 *
 * Separates consumer presentation fields (title, artist, album, artwork, duration, releaseYear)
 * from provider identifiers ([providerIds]) and internal matching state ([internalMatch]).
 */
data class StructuredTrackCandidate(
    val canonicalKey: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationMs: Long? = null,
    val releaseYear: Int? = null,
    val trackNumber: Int? = null,
    val isrc: String? = null,
    val providerIds: Map<String, String> = emptyMap(),
    val internalMatch: CandidateMatchMetadata = CandidateMatchMetadata()
) {
    /**
     * Converts this catalog discovery candidate into a [CanonicalTrackIdentity]
     * for downstream source resolution without coupling discovery to any source provider.
     */
    fun toCanonicalTrackIdentity(
        localTrackId: Long? = null,
        preferredProviderId: String? = null
    ): CanonicalTrackIdentity {
        val chosenProvider = preferredProviderId
            ?.takeIf { providerIds.containsKey(it) }
            ?: providerIds.keys.firstOrNull()
            ?: "catalog"
        val chosenTrackId = providerIds[chosenProvider] ?: canonicalKey
        return CanonicalTrackIdentity(
            localTrackId = localTrackId,
            catalogProviderId = chosenProvider,
            catalogTrackId = chosenTrackId,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            trackNumber = trackNumber,
            releaseYear = releaseYear,
            isrc = isrc
        )
    }

    companion object {
        fun fromCatalogTrack(
            track: CatalogTrack,
            releaseYear: Int? = null
        ): StructuredTrackCandidate {
            val providerKey = track.provider.ifBlank { "local_catalog" }
            val providerTrackId = track.providerTrackId.ifBlank { track.id.toString() }
            return StructuredTrackCandidate(
                canonicalKey = "$providerKey:$providerTrackId",
                title = track.title,
                artist = track.artistName,
                album = track.albumTitle?.takeIf { it.isNotBlank() },
                artworkUrl = track.artworkUrl?.takeIf { it.isNotBlank() },
                durationMs = track.durationMs?.takeIf { it > 0L },
                releaseYear = releaseYear,
                trackNumber = track.trackNumber?.takeIf { it > 0 },
                isrc = track.isrc?.takeIf { it.isNotBlank() },
                providerIds = mapOf(providerKey to providerTrackId),
                internalMatch = CandidateMatchMetadata(
                    mergedCandidateCount = 1,
                    mergedByIsrc = false,
                    confidenceScore = 1.0,
                    sourceProviders = setOf(providerKey)
                )
            )
        }
    }
}

data class StructuredSearchQuery(
    val query: String,
    val limit: Int = 20,
    val offset: Int = 0,
    val country: String = "ID"
)

data class StructuredSearchResponse(
    val query: String,
    val tracks: List<StructuredTrackCandidate>,
    val totalCount: Int = tracks.size,
    val hasMore: Boolean = false
)

/**
 * Architecture boundary for catalog discovery.
 *
 * Implementations may back this interface with local on-device discovery
 * (Room + iTunes + MusicBrainz) today, or a future remote Search & Catalog Backend API
 * without changing consumer UI ViewModels or downstream source resolution.
 */
interface CatalogDiscoveryGateway {
    suspend fun searchTracks(request: StructuredSearchQuery): StructuredSearchResponse
}
