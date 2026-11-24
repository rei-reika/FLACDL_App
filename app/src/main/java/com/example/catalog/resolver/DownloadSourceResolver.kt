package com.example.catalog.resolver

import com.example.catalog.matching.TrackMatchResult
import com.example.catalog.matching.TrackMatcher
import kotlinx.coroutines.delay

/**
 * Abstract candidate provider boundary for future authorized download sources.
 * Implementations must NOT invent source identities from catalog IDs.
 */
interface DownloadSourceCandidateProvider {
    val capabilities: DownloadSourceCapabilities

    suspend fun fetchCandidates(track: CanonicalTrackIdentity): List<DownloadSourceCandidate>
}

/**
 * Provider-independent domain contract for resolving a [CanonicalTrackIdentity]
 * into a validated [DownloadResolutionResult].
 */
interface DownloadSourceResolver {
    val capabilities: DownloadSourceCapabilities

    suspend fun resolve(track: CanonicalTrackIdentity): DownloadResolutionResult
}

/**
 * Default placeholder resolver used when no authorized audio source provider is configured.
 * Keeps catalog discovery strictly decoupled from source resolution without fabricating availability.
 */
object UnconfiguredDownloadSourceResolver : DownloadSourceResolver {
    override val capabilities: DownloadSourceCapabilities = DownloadSourceCapabilities(
        providerId = "unconfigured",
        displayName = "No Source Configured",
        supportsCandidateLookup = false,
        supportsIsrcLookup = false,
        supportsTextSearch = false,
        supportsResolution = false,
        requiresAuthentication = false,
        timeoutMs = 0L,
        maxCandidates = 0
    )

    override suspend fun resolve(track: CanonicalTrackIdentity): DownloadResolutionResult {
        return DownloadResolutionResult.Unavailable(
            providerId = capabilities.providerId,
            reason = "No authorized download source provider is configured."
        )
    }
}

/**
 * Pure Kotlin domain orchestrator for download-source resolution.
 *
 * Flow:
 * 1. Validate canonical track identity and provider capabilities.
 * 2. Fetch candidates from [candidateProvider] with bounded retry on retryable errors only.
 * 3. Filter to available candidates and evaluate via existing [TrackMatcher].
 * 4. Emit strongly typed [DownloadResolutionResult] without fabricating source identities.
 */
class TrackMatchingDownloadSourceResolver(
    private val candidateProvider: DownloadSourceCandidateProvider,
    private val trackMatcher: TrackMatcher = TrackMatcher(),
    private val retryPolicy: ResolverRetryPolicy = ResolverRetryPolicy(),
    private val cachePolicy: ResolverCachePolicy = ResolverCachePolicy(),
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) }
) : DownloadSourceResolver {

    override val capabilities: DownloadSourceCapabilities
        get() = candidateProvider.capabilities

    override suspend fun resolve(track: CanonicalTrackIdentity): DownloadResolutionResult {
        val providerId = capabilities.providerId

        if (track.title.isBlank() || track.artist.isBlank()) {
            return DownloadResolutionResult.Error(
                providerId = providerId,
                errorType = ResolverErrorType.INVALID_REQUEST,
                message = "Canonical track identity requires non-empty title and artist.",
                retryable = false
            )
        }

        if (!capabilities.supportsCandidateLookup || !capabilities.supportsResolution) {
            return DownloadResolutionResult.Unavailable(
                providerId = providerId,
                reason = "Provider $providerId does not support candidate resolution."
            )
        }

        val fetchedCandidates = when (val fetch = fetchWithRetry(track)) {
            is FetchOutcome.Failure -> return fetch.error
            is FetchOutcome.Success -> fetch.candidates
        }

        val availableCandidates = fetchedCandidates
            .filter { it.isAvailable && it.sourceTrackId.isNotBlank() && it.sourceReference.isNotBlank() }
            .take(capabilities.maxCandidates.coerceAtLeast(1))

        if (availableCandidates.isEmpty()) {
            return DownloadResolutionResult.Unavailable(
                providerId = providerId,
                reason = "No available source candidates returned by $providerId."
            )
        }

        // Map by (providerId, candidateId) to preserve exact candidate binding after TrackMatcher scoring
        val candidateLookup = availableCandidates.associateBy { it.metadata.providerId to it.metadata.candidateId }
        val matchResult = trackMatcher.match(
            query = track.toMatchQuery(),
            candidates = availableCandidates.map { it.metadata }
        )

        return when (matchResult) {
            is TrackMatchResult.Verified -> {
                val matched = candidateLookup[matchResult.candidate.providerId to matchResult.candidate.candidateId]
                    ?: return DownloadResolutionResult.Error(
                        providerId = providerId,
                        errorType = ResolverErrorType.UNKNOWN,
                        message = "Matched candidate could not be bound back to source candidate.",
                        retryable = false
                    )
                val now = clockMs()
                val identity = ResolvedSourceIdentity(
                    providerId = matched.providerId,
                    sourceTrackId = matched.sourceTrackId,
                    sourceReference = matched.sourceReference,
                    resolvedAtMs = now,
                    expiresAtMs = now + cachePolicy.availabilityTtlMs.coerceAtLeast(1L)
                )
                DownloadResolutionResult.Resolved(
                    identity = identity,
                    candidate = matched,
                    evidence = matchResult.evidence
                )
            }

            is TrackMatchResult.Unverified -> {
                val matched = candidateLookup[matchResult.candidate.providerId to matchResult.candidate.candidateId]
                    ?: return DownloadResolutionResult.Unavailable(
                        providerId = providerId,
                        reason = matchResult.reason
                    )
                DownloadResolutionResult.Unverified(
                    candidate = matched,
                    evidence = matchResult.evidence,
                    reason = matchResult.reason
                )
            }

            is TrackMatchResult.Ambiguous -> {
                val scored = matchResult.candidates.mapNotNull { scoredCand ->
                    val sourceCand = candidateLookup[scoredCand.candidate.providerId to scoredCand.candidate.candidateId]
                    sourceCand?.let { ScoredDownloadCandidate(candidate = it, evidence = scoredCand.evidence) }
                }
                DownloadResolutionResult.Ambiguous(
                    candidates = scored,
                    reason = matchResult.reason
                )
            }

            is TrackMatchResult.Unavailable -> DownloadResolutionResult.Unavailable(
                providerId = providerId,
                reason = matchResult.reason
            )
        }
    }

    private sealed class FetchOutcome {
        data class Success(val candidates: List<DownloadSourceCandidate>) : FetchOutcome()
        data class Failure(val error: DownloadResolutionResult.Error) : FetchOutcome()
    }

    private suspend fun fetchWithRetry(track: CanonicalTrackIdentity): FetchOutcome {
        val providerId = capabilities.providerId
        var attempt = 0
        while (true) {
            try {
                val candidates = candidateProvider.fetchCandidates(track)
                return FetchOutcome.Success(candidates)
            } catch (e: DownloadSourceLookupException) {
                if (retryPolicy.shouldRetry(e.errorType, attempt)) {
                    delayFn(retryPolicy.backoffMsForAttempt(attempt))
                    attempt++
                    continue
                }
                return FetchOutcome.Failure(
                    DownloadResolutionResult.Error(
                        providerId = providerId,
                        errorType = e.errorType,
                        message = e.message ?: "Source lookup failed (${e.errorType.name}).",
                        retryable = e.errorType.isRetryable
                    )
                )
            } catch (e: Exception) {
                return FetchOutcome.Failure(
                    DownloadResolutionResult.Error(
                        providerId = providerId,
                        errorType = ResolverErrorType.UNKNOWN,
                        message = e.message ?: "Unexpected resolver error.",
                        retryable = false
                    )
                )
            }
        }
    }
}
