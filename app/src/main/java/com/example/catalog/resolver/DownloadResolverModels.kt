package com.example.catalog.resolver

import com.example.catalog.matching.TrackMatchCandidate
import com.example.catalog.matching.TrackMatchEvidence
import com.example.catalog.matching.TrackMatchQuery

/**
 * Canonical catalog track identity passed into a [DownloadSourceResolver].
 *
 * Catalog identifiers ([catalogProviderId], [catalogTrackId]) are strictly for
 * traceability and metadata identity — they must NEVER be assumed to map
 * automatically to a downloadable source identity.
 */
data class CanonicalTrackIdentity(
    val localTrackId: Long? = null,
    val catalogProviderId: String,
    val catalogTrackId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val trackNumber: Int? = null,
    val releaseYear: Int? = null,
    val isrc: String? = null
) {
    fun toMatchQuery(): TrackMatchQuery = TrackMatchQuery(
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        trackNumber = trackNumber,
        releaseYear = releaseYear,
        isrc = isrc
    )
}

/**
 * Explicit capability declaration for a future download-source resolver.
 */
data class DownloadSourceCapabilities(
    val providerId: String,
    val displayName: String,
    val supportsCandidateLookup: Boolean = true,
    val supportsIsrcLookup: Boolean = false,
    val supportsTextSearch: Boolean = true,
    val supportsResolution: Boolean = true,
    val requiresAuthentication: Boolean = false,
    val timeoutMs: Long = 10_000L,
    val maxCandidates: Int = 10
)

/**
 * Source-level candidate evaluated by [DownloadSourceResolver].
 * Intentionally excludes raw audio streams, decryption keys, or DRM data.
 * [qualityHints] are purely informational and must not be treated as guaranteed audio quality.
 */
data class DownloadSourceCandidate(
    val providerId: String,
    val sourceTrackId: String,
    val sourceReference: String,
    val metadata: TrackMatchCandidate,
    val isAvailable: Boolean = true,
    val qualityHints: Set<String> = emptySet(),
    val musicBrainzRecordingId: String? = null
)

/**
 * Validated download-source identity produced only after [com.example.catalog.matching.TrackMatcher]
 * verifies a candidate from an actual source lookup.
 */
data class ResolvedSourceIdentity(
    val providerId: String,
    val sourceTrackId: String,
    val sourceReference: String,
    val resolvedAtMs: Long,
    val expiresAtMs: Long
)

data class ScoredDownloadCandidate(
    val candidate: DownloadSourceCandidate,
    val evidence: TrackMatchEvidence
)

enum class ResolverErrorType(val isRetryable: Boolean) {
    NETWORK(isRetryable = true),
    TIMEOUT(isRetryable = true),
    RATE_LIMITED(isRetryable = true),
    UPSTREAM_UNAVAILABLE(isRetryable = true),
    AUTHENTICATION(isRetryable = false),
    INVALID_REQUEST(isRetryable = false),
    UNKNOWN(isRetryable = false)
}

/**
 * Exception thrown by a [DownloadSourceCandidateProvider] to report classified failures.
 */
class DownloadSourceLookupException(
    val errorType: ResolverErrorType,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Strongly typed result contract for download-source resolution.
 */
sealed class DownloadResolutionResult {
    data class Resolved(
        val identity: ResolvedSourceIdentity,
        val candidate: DownloadSourceCandidate,
        val evidence: TrackMatchEvidence
    ) : DownloadResolutionResult()

    data class Unverified(
        val candidate: DownloadSourceCandidate,
        val evidence: TrackMatchEvidence,
        val reason: String
    ) : DownloadResolutionResult()

    data class Ambiguous(
        val candidates: List<ScoredDownloadCandidate>,
        val reason: String
    ) : DownloadResolutionResult()

    data class Unavailable(
        val providerId: String,
        val reason: String
    ) : DownloadResolutionResult()

    data class Error(
        val providerId: String,
        val errorType: ResolverErrorType,
        val message: String,
        val retryable: Boolean = errorType.isRetryable
    ) : DownloadResolutionResult()
}

/**
 * Domain-level retry policy: retries only bounded transient retryable errors.
 * Never retries ambiguous, unavailable, authentication, or validation errors.
 */
data class ResolverRetryPolicy(
    val maxRetries: Int = 2,
    val baseBackoffMs: Long = 500L
) {
    fun shouldRetry(errorType: ResolverErrorType, attemptIndex: Int): Boolean {
        return errorType.isRetryable && attemptIndex < maxRetries.coerceAtLeast(0)
    }

    fun backoffMsForAttempt(attemptIndex: Int): Long {
        val safeAttempt = attemptIndex.coerceIn(0, 10)
        return baseBackoffMs.coerceAtLeast(0L) * (1L shl safeAttempt)
    }
}

/**
 * Domain-level cache TTL policy for volatile source availability (15-60 minutes).
 * Kept strictly separate from long-TTL Room catalog metadata.
 */
data class ResolverCachePolicy(
    val availabilityTtlMs: Long = DEFAULT_AVAILABILITY_TTL_MS
) {
    companion object {
        const val DEFAULT_AVAILABILITY_TTL_MS: Long = 30L * 60L * 1000L
    }
}
