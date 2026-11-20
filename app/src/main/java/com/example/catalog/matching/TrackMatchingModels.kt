package com.example.catalog.matching

/**
 * Canonical track metadata to be matched against external candidates.
 * Contains no download URLs or provider-specific download tokens.
 */
data class TrackMatchQuery(
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val releaseYear: Int? = null,
    val isrc: String? = null
)

/**
 * Provider-agnostic candidate track metadata evaluated by [TrackMatcher].
 * Intentionally excludes download URLs and never invents source identities.
 */
data class TrackMatchCandidate(
    val candidateId: String,
    val providerId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val releaseYear: Int? = null,
    val isrc: String? = null
)

enum class TrackMatchStatus {
    VERIFIED,
    UNVERIFIED,
    AMBIGUOUS,
    UNAVAILABLE
}

enum class TrackMatchMethod {
    ISRC_EXACT,
    TITLE_ARTIST_DURATION,
    TITLE_ARTIST_INCOMPLETE,
    NONE
}

data class TrackMatchEvidence(
    val matchMethod: TrackMatchMethod,
    val score: Double,
    val titleSimilarity: Double,
    val artistSimilarity: Double,
    val albumSimilarity: Double? = null,
    val durationDeltaMs: Long? = null,
    val matchedFields: Set<String> = emptySet(),
    val notes: List<String> = emptyList()
)

data class ScoredTrackCandidate(
    val candidate: TrackMatchCandidate,
    val evidence: TrackMatchEvidence
)

/**
 * Strongly typed result contract distinguishing Verified, Unverified,
 * Ambiguous, and Unavailable match outcomes.
 */
sealed class TrackMatchResult {
    abstract val status: TrackMatchStatus

    data class Verified(
        val candidate: TrackMatchCandidate,
        val evidence: TrackMatchEvidence
    ) : TrackMatchResult() {
        override val status: TrackMatchStatus = TrackMatchStatus.VERIFIED
    }

    data class Unverified(
        val candidate: TrackMatchCandidate,
        val evidence: TrackMatchEvidence,
        val reason: String
    ) : TrackMatchResult() {
        override val status: TrackMatchStatus = TrackMatchStatus.UNVERIFIED
    }

    data class Ambiguous(
        val candidates: List<ScoredTrackCandidate>,
        val reason: String
    ) : TrackMatchResult() {
        override val status: TrackMatchStatus = TrackMatchStatus.AMBIGUOUS
    }

    data class Unavailable(
        val reason: String,
        val rejectedCandidates: List<ScoredTrackCandidate> = emptyList()
    ) : TrackMatchResult() {
        override val status: TrackMatchStatus = TrackMatchStatus.UNAVAILABLE
    }
}

data class TrackMatchConfig(
    val verifiedDurationToleranceMs: Long = 3_000L,
    val unverifiedDurationToleranceMs: Long = 7_000L,
    val maxIsrcDurationDiscrepancyMs: Long = 15_000L,
    val minTitleSimilarityForIsrc: Double = 0.65,
    val minArtistSimilarityForIsrc: Double = 0.50,
    val minTitleSimilarityForVerified: Double = 0.85,
    val minArtistSimilarityForVerified: Double = 0.80,
    val minTitleSimilarityForCandidate: Double = 0.72,
    val minArtistSimilarityForCandidate: Double = 0.65,
    val ambiguityScoreMargin: Double = 0.04
)
