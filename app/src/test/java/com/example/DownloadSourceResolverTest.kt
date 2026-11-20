package com.example

import com.example.catalog.matching.TrackMatchCandidate
import com.example.catalog.matching.TrackMatchMethod
import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadResolutionResult
import com.example.catalog.resolver.DownloadSourceCandidate
import com.example.catalog.resolver.DownloadSourceCandidateProvider
import com.example.catalog.resolver.DownloadSourceCapabilities
import com.example.catalog.resolver.DownloadSourceLookupException
import com.example.catalog.resolver.ResolverCachePolicy
import com.example.catalog.resolver.ResolverErrorType
import com.example.catalog.resolver.ResolverRetryPolicy
import com.example.catalog.resolver.TrackMatchingDownloadSourceResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceResolverTest {

    private val sampleTrack = CanonicalTrackIdentity(
        localTrackId = 42L,
        catalogProviderId = "apple_itunes",
        catalogTrackId = "1536243223",
        title = "Yoru ni Kakeru",
        artist = "YOASOBI",
        album = "THE BOOK",
        durationMs = 261_000L,
        trackNumber = 3,
        releaseYear = 2021,
        isrc = "JPXS01900101"
    )

    @Test
    fun `1 single verified candidate returns Resolved with identity and evidence`() = runTest {
        val candidate = makeCandidate(
            sourceTrackId = "src-track-1",
            sourceReference = "source://provider_x/track/src-track-1",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_200L,
            isrc = "JPXS01900101"
        )
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = listOf(candidate)),
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 1_800_000L),
            clockMs = { 1_700_000_000_000L }
        )

        val result = resolver.resolve(sampleTrack)
        assertTrue(result is DownloadResolutionResult.Resolved)
        val resolved = result as DownloadResolutionResult.Resolved
        assertEquals("src-track-1", resolved.identity.sourceTrackId)
        assertEquals("source://provider_x/track/src-track-1", resolved.identity.sourceReference)
        assertEquals(1_700_001_800_000L, resolved.identity.expiresAtMs)
        assertEquals(TrackMatchMethod.ISRC_EXACT, resolved.evidence.matchMethod)
    }

    @Test
    fun `2 unverified candidate returns Unverified and is not silently promoted`() = runTest {
        val trackWithoutIsrcOrDuration = sampleTrack.copy(isrc = null, durationMs = null)
        val candidate = makeCandidate(
            sourceTrackId = "src-unver-1",
            sourceReference = "source://provider_x/track/src-unver-1",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_000L,
            isrc = null
        )
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = listOf(candidate))
        )

        val result = resolver.resolve(trackWithoutIsrcOrDuration)
        assertTrue(result is DownloadResolutionResult.Unverified)
        assertEquals("src-unver-1", (result as DownloadResolutionResult.Unverified).candidate.sourceTrackId)
    }

    @Test
    fun `3 multiple competing candidates return Ambiguous with alternatives preserved`() = runTest {
        val trackNoIsrc = sampleTrack.copy(isrc = null, album = null)
        val candA = makeCandidate(
            sourceTrackId = "src-a",
            sourceReference = "source://provider_x/track/src-a",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_100L
        )
        val candB = makeCandidate(
            sourceTrackId = "src-b",
            sourceReference = "source://provider_x/track/src-b",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_200L
        )
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = listOf(candA, candB))
        )

        val result = resolver.resolve(trackNoIsrc)
        assertTrue(result is DownloadResolutionResult.Ambiguous)
        val ambiguous = result as DownloadResolutionResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `4 no candidate returns Unavailable`() = runTest {
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = emptyList())
        )

        val result = resolver.resolve(sampleTrack)
        assertTrue(result is DownloadResolutionResult.Unavailable)
        assertEquals("fake_source", (result as DownloadResolutionResult.Unavailable).providerId)
    }

    @Test
    fun `5 provider error returns Error`() = runTest {
        val provider = FakeCandidateProvider(
            failuresBeforeSuccess = 5,
            failureType = ResolverErrorType.UPSTREAM_UNAVAILABLE
        )
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = provider,
            retryPolicy = ResolverRetryPolicy(maxRetries = 1, baseBackoffMs = 10L),
            delayFn = {}
        )

        val result = resolver.resolve(sampleTrack)
        assertTrue(result is DownloadResolutionResult.Error)
        val error = result as DownloadResolutionResult.Error
        assertEquals(ResolverErrorType.UPSTREAM_UNAVAILABLE, error.errorType)
        assertTrue(error.retryable)
    }

    @Test
    fun `6 retryable error retries up to maxRetries and succeeds on recovery`() = runTest {
        val candidate = makeCandidate(
            sourceTrackId = "src-recovered",
            sourceReference = "source://provider_x/track/src-recovered",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_000L,
            isrc = "JPXS01900101"
        )
        val delays = mutableListOf<Long>()
        val provider = FakeCandidateProvider(
            candidates = listOf(candidate),
            failuresBeforeSuccess = 2,
            failureType = ResolverErrorType.RATE_LIMITED
        )
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = provider,
            retryPolicy = ResolverRetryPolicy(maxRetries = 2, baseBackoffMs = 100L),
            delayFn = { delays.add(it) }
        )

        val result = resolver.resolve(sampleTrack)
        assertTrue(result is DownloadResolutionResult.Resolved)
        assertEquals(3, provider.fetchCalls)
        assertEquals(listOf(100L, 200L), delays)
    }

    @Test
    fun `7 non-retryable error is not retried`() = runTest {
        val provider = FakeCandidateProvider(
            failuresBeforeSuccess = 3,
            failureType = ResolverErrorType.AUTHENTICATION
        )
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = provider,
            retryPolicy = ResolverRetryPolicy(maxRetries = 3, baseBackoffMs = 100L),
            delayFn = {}
        )

        val result = resolver.resolve(sampleTrack)
        assertTrue(result is DownloadResolutionResult.Error)
        val error = result as DownloadResolutionResult.Error
        assertEquals(ResolverErrorType.AUTHENTICATION, error.errorType)
        assertFalse(error.retryable)
        assertEquals(1, provider.fetchCalls)
    }

    @Test
    fun `8 deterministic candidate ordering independent of provider order`() = runTest {
        val trackNoIsrc = sampleTrack.copy(isrc = null, album = null)
        val cand1 = makeCandidate(
            sourceTrackId = "src-alpha",
            sourceReference = "source://provider_x/track/src-alpha",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_000L
        )
        val cand2 = makeCandidate(
            sourceTrackId = "src-beta",
            sourceReference = "source://provider_x/track/src-beta",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_000L
        )

        val forward = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = listOf(cand1, cand2))
        ).resolve(trackNoIsrc) as DownloadResolutionResult.Ambiguous

        val reversed = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = listOf(cand2, cand1))
        ).resolve(trackNoIsrc) as DownloadResolutionResult.Ambiguous

        assertEquals(
            forward.candidates.map { it.candidate.sourceTrackId },
            reversed.candidates.map { it.candidate.sourceTrackId }
        )
        assertEquals(listOf("src-alpha", "src-beta"), forward.candidates.map { it.candidate.sourceTrackId })
    }

    @Test
    fun `9 no source identity is fabricated from catalog IDs or ISRC`() = runTest {
        val unavailableCandidate = makeCandidate(
            sourceTrackId = "src-unavailable",
            sourceReference = "source://provider_x/track/src-unavailable",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_000L,
            isrc = "JPXS01900101",
            isAvailable = false
        )
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = listOf(unavailableCandidate))
        )

        val result = resolver.resolve(sampleTrack)
        assertTrue(result is DownloadResolutionResult.Unavailable)

        // Even when a valid candidate resolves, its identity comes from the source provider, not catalogTrackId
        val validCandidate = unavailableCandidate.copy(isAvailable = true)
        val resolved = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = listOf(validCandidate))
        ).resolve(sampleTrack) as DownloadResolutionResult.Resolved
        assertNotEquals(sampleTrack.catalogTrackId, resolved.identity.sourceTrackId)
    }

    @Test
    fun `10 resolver delegates conflict detection to existing TrackMatcher`() = runTest {
        val trackStudio = sampleTrack.copy(isrc = null)
        val liveCandidate = makeCandidate(
            sourceTrackId = "src-live",
            sourceReference = "source://provider_x/track/src-live",
            title = "Yoru ni Kakeru (Live)",
            artist = "YOASOBI",
            durationMs = 261_500L
        )
        val resolver = TrackMatchingDownloadSourceResolver(
            candidateProvider = FakeCandidateProvider(candidates = listOf(liveCandidate))
        )

        val result = resolver.resolve(trackStudio)
        assertTrue(result is DownloadResolutionResult.Unavailable)
        assertTrue((result as DownloadResolutionResult.Unavailable).reason.contains("version conflict", ignoreCase = true))
    }

    private fun makeCandidate(
        sourceTrackId: String,
        sourceReference: String,
        title: String,
        artist: String,
        durationMs: Long? = null,
        isrc: String? = null,
        isAvailable: Boolean = true
    ): DownloadSourceCandidate {
        return DownloadSourceCandidate(
            providerId = "fake_source",
            sourceTrackId = sourceTrackId,
            sourceReference = sourceReference,
            metadata = TrackMatchCandidate(
                candidateId = sourceTrackId,
                providerId = "fake_source",
                title = title,
                artist = artist,
                durationMs = durationMs,
                isrc = isrc
            ),
            isAvailable = isAvailable,
            qualityHints = setOf("LOSSLESS")
        )
    }

    private class FakeCandidateProvider(
        private val candidates: List<DownloadSourceCandidate> = emptyList(),
        private val failuresBeforeSuccess: Int = 0,
        private val failureType: ResolverErrorType = ResolverErrorType.NETWORK
    ) : DownloadSourceCandidateProvider {
        var fetchCalls: Int = 0

        override val capabilities = DownloadSourceCapabilities(
            providerId = "fake_source",
            displayName = "Fake Source",
            supportsCandidateLookup = true,
            supportsIsrcLookup = true,
            supportsTextSearch = true,
            supportsResolution = true,
            requiresAuthentication = false,
            timeoutMs = 5_000L,
            maxCandidates = 10
        )

        override suspend fun fetchCandidates(track: CanonicalTrackIdentity): List<DownloadSourceCandidate> {
            fetchCalls++
            if (fetchCalls <= failuresBeforeSuccess) {
                throw DownloadSourceLookupException(failureType, "Simulated $failureType failure")
            }
            return candidates
        }
    }
}
