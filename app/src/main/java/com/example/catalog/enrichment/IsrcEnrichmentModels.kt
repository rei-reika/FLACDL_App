package com.example.catalog.enrichment

import com.example.catalog.matching.TrackMatchEvidence

enum class IsrcEnrichmentStatus {
    VERIFIED_ENRICHED,
    ALREADY_ENRICHED,
    NOT_FOUND,
    AMBIGUOUS,
    CONFLICT_REJECTED,
    RATE_LIMITED,
    NETWORK_ERROR,
    UNSUPPORTED_OR_INCOMPLETE
}

data class IsrcEnrichmentResult(
    val status: IsrcEnrichmentStatus,
    val musicBrainzRecordingId: String? = null,
    val isrc: String? = null,
    val fromCache: Boolean = false,
    val matchEvidence: TrackMatchEvidence? = null,
    val updatedFields: List<String> = emptyList(),
    val reason: String? = null
)

/**
 * Configurable TTL policy for enrichment entries stored in existing Room `catalog_cache`.
 * - Positive enrichment: 14 days default (bounded 14-30 days).
 * - Negative enrichment (not found / ambiguous / conflict): 24 hours default.
 */
data class EnrichmentCachePolicy(
    val positiveTtlMs: Long = DEFAULT_POSITIVE_TTL_MS,
    val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS
) {
    companion object {
        const val DEFAULT_POSITIVE_TTL_MS: Long = 14L * 24L * 60L * 60L * 1000L
        const val DEFAULT_NEGATIVE_TTL_MS: Long = 24L * 60L * 60L * 1000L
    }
}

/**
 * Normalized MusicBrainz recording candidate returned by recording search before
 * TrackMatcher verification and optional `inc=isrcs` detail lookup.
 */
data class MusicBrainzRecordingCandidate(
    val musicBrainzRecordingId: String,
    val title: String,
    val artistName: String,
    val albumTitle: String? = null,
    val durationMs: Long? = null,
    val releaseYear: Int? = null,
    val isrcs: List<String> = emptyList()
)
