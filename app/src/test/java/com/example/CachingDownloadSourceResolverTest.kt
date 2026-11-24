package com.example

import com.example.catalog.matching.TrackMatchCandidate
import com.example.catalog.matching.TrackMatchEvidence
import com.example.catalog.matching.TrackMatchMethod
import com.example.catalog.resolver.CachingDownloadSourceResolver
import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadResolutionResult
import com.example.catalog.resolver.DownloadSourceCandidate
import com.example.catalog.resolver.DownloadSourceCapabilities
import com.example.catalog.resolver.DownloadSourceResolver
import com.example.catalog.resolver.ResolvedSourceIdentity
import com.example.catalog.resolver.ResolverCachePolicy
import com.example.catalog.resolver.ResolverErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CachingDownloadSourceResolverTest {

    private var fakeNowMs: Long = 1_700_000_000_000L

    private fun track(id: String, title: String = "Kataomoi", artist: String = "Aimer"): CanonicalTrackIdentity =
        CanonicalTrackIdentity(
            catalogProviderId = "apple_itunes",
            catalogTrackId = id,
            title = title,
            artist = artist,
            durationMs = 207_000L
        )

    private fun resolvedFor(sourceId: String, expiresAtMs: Long): DownloadResolutionResult.Resolved {
        val cand = DownloadSourceCandidate(
            providerId = "mock_source",
            sourceTrackId = sourceId,
            sourceReference = "source://mock_source/track/$sourceId",
            metadata = TrackMatchCandidate(
                candidateId = sourceId,
                providerId = "mock_source",
                title = "Kataomoi",
                artist = "Aimer",
                durationMs = 207_000L
            )
        )
        return DownloadResolutionResult.Resolved(
            identity = ResolvedSourceIdentity(
                providerId = "mock_source",
                sourceTrackId = sourceId,
                sourceReference = cand.sourceReference,
                resolvedAtMs = fakeNowMs,
                expiresAtMs = expiresAtMs
            ),
            candidate = cand,
            evidence = TrackMatchEvidence(
                matchMethod = TrackMatchMethod.TITLE_ARTIST_DURATION,
                score = 0.95,
                titleSimilarity = 1.0,
                artistSimilarity = 1.0,
                durationDeltaMs = 0L
            )
        )
    }

    @Test
    fun `cache hit avoids redundant delegate resolution within TTL`() = runTest {
        val delegate = CountingDelegateResolver { resolvedFor("song-1", fakeNowMs + 60_000L) }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 60_000L),
            clockMs = { fakeNowMs }
        )

        val first = caching.resolve(track("101"))
        val second = caching.resolve(track("101"))

        assertTrue(first is DownloadResolutionResult.Resolved)
        assertEquals(first, second)
        assertEquals(1, delegate.calls.get())
        assertEquals(1, caching.size())
    }

    @Test
    fun `TTL expiration triggers fresh delegate resolution`() = runTest {
        val delegate = CountingDelegateResolver {
            resolvedFor("song-v${it}", fakeNowMs + 10_000L)
        }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 5_000L),
            clockMs = { fakeNowMs }
        )

        val first = caching.resolve(track("101")) as DownloadResolutionResult.Resolved
        assertEquals("song-v1", first.identity.sourceTrackId)

        fakeNowMs += 5_001L
        val second = caching.resolve(track("101")) as DownloadResolutionResult.Resolved
        assertEquals("song-v2", second.identity.sourceTrackId)
        assertEquals(2, delegate.calls.get())
    }

    @Test
    fun `bounded cache evicts least recently used entry`() = runTest {
        val delegate = CountingDelegateResolver { idx ->
            resolvedFor("song-$idx", fakeNowMs + 60_000L)
        }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 60_000L),
            maxEntries = 2,
            clockMs = { fakeNowMs }
        )

        caching.resolve(track("1", title = "One"))
        caching.resolve(track("2", title = "Two"))
        // Touch "1" so "2" becomes LRU
        caching.resolve(track("1", title = "One"))
        // Insert "3" -> evicts "2"
        caching.resolve(track("3", title = "Three"))

        assertEquals(2, caching.size())
        assertEquals(3, delegate.calls.get())

        // "1" is still cached -> no new call
        caching.resolve(track("1", title = "One"))
        assertEquals(3, delegate.calls.get())

        // "2" was evicted -> triggers new call
        caching.resolve(track("2", title = "Two"))
        assertEquals(4, delegate.calls.get())
    }

    @Test
    fun `concurrent access does not corrupt cache state`() = runTest {
        val delegate = CountingDelegateResolver { idx ->
            resolvedFor("song-$idx", fakeNowMs + 60_000L)
        }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 60_000L),
            maxEntries = 16,
            clockMs = { fakeNowMs }
        )

        val jobs = (1..32).map { i ->
            async(Dispatchers.Default) {
                caching.resolve(track("trk-${i % 8}", title = "Track ${i % 8}"))
            }
        }
        val results = jobs.awaitAll()

        assertEquals(32, results.size)
        assertTrue(results.all { it is DownloadResolutionResult.Resolved })
        assertTrue(caching.size() <= 8)
    }

    @Test
    fun `transient error is never cached`() = runTest {
        var shouldFail = true
        val delegate = CountingDelegateResolver {
            if (shouldFail) {
                DownloadResolutionResult.Error(
                    providerId = "mock_source",
                    errorType = ResolverErrorType.NETWORK,
                    message = "Temporary network drop",
                    retryable = true
                )
            } else {
                resolvedFor("song-ok", fakeNowMs + 60_000L)
            }
        }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 60_000L),
            clockMs = { fakeNowMs }
        )

        val err = caching.resolve(track("101"))
        assertTrue(err is DownloadResolutionResult.Error)
        assertEquals(0, caching.size())

        shouldFail = false
        val recovered = caching.resolve(track("101"))
        assertTrue(recovered is DownloadResolutionResult.Resolved)
        assertEquals(2, delegate.calls.get())
        assertEquals(1, caching.size())
    }

    @Test
    fun `unavailable result is cached within TTL and refreshed after expiry`() = runTest {
        val delegate = CountingDelegateResolver {
            DownloadResolutionResult.Unavailable(
                providerId = "mock_source",
                reason = "Track not in source catalog."
            )
        }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 10_000L),
            clockMs = { fakeNowMs }
        )

        val first = caching.resolve(track("404"))
        val second = caching.resolve(track("404"))
        assertTrue(first is DownloadResolutionResult.Unavailable)
        assertEquals(first, second)
        assertEquals(1, delegate.calls.get())

        fakeNowMs += 10_001L
        caching.resolve(track("404"))
        assertEquals(2, delegate.calls.get())
    }

    @Test
    fun `deterministic cache key normalizes casing and whitespace`() = runTest {
        val delegate = CountingDelegateResolver { resolvedFor("song-norm", fakeNowMs + 60_000L) }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 60_000L),
            clockMs = { fakeNowMs }
        )

        caching.resolve(track("101", title = "  Kataomoi ", artist = "AIMER"))
        caching.resolve(track("101", title = "kataomoi", artist = "aimer"))

        assertEquals(1, delegate.calls.get())
    }

    @Test
    fun `ambiguous and unverified results are not cached so fresh discovery is not suppressed`() = runTest {
        val delegate = CountingDelegateResolver { idx ->
            if (idx == 1) {
                DownloadResolutionResult.Ambiguous(candidates = emptyList(), reason = "Competing matches")
            } else {
                resolvedFor("song-disambiguated", fakeNowMs + 60_000L)
            }
        }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 60_000L),
            clockMs = { fakeNowMs }
        )

        val first = caching.resolve(track("202"))
        assertTrue(first is DownloadResolutionResult.Ambiguous)
        assertEquals(0, caching.size())

        val second = caching.resolve(track("202"))
        assertTrue(second is DownloadResolutionResult.Resolved)
        assertEquals(2, delegate.calls.get())
    }

    @Test
    fun `cache key isolates tracks when album or release year corroboration changes`() = runTest {
        val delegate = CountingDelegateResolver { idx ->
            resolvedFor("song-$idx", fakeNowMs + 60_000L)
        }
        val caching = CachingDownloadSourceResolver(
            delegate = delegate,
            cachePolicy = ResolverCachePolicy(availabilityTtlMs = 60_000L),
            clockMs = { fakeNowMs }
        )

        val withoutAlbum = track("303")
        val withAlbum = withoutAlbum.copy(album = "Daydream", releaseYear = 2016)

        caching.resolve(withoutAlbum)
        caching.resolve(withAlbum)

        assertEquals(2, delegate.calls.get())
        assertEquals(2, caching.size())
    }

    private class CountingDelegateResolver(
        private val onResolve: (Int) -> DownloadResolutionResult
    ) : DownloadSourceResolver {
        val calls = AtomicInteger(0)

        override val capabilities = DownloadSourceCapabilities(
            providerId = "mock_source",
            displayName = "Mock Source Provider"
        )

        override suspend fun resolve(track: CanonicalTrackIdentity): DownloadResolutionResult {
            val idx = calls.incrementAndGet()
            return onResolve(idx)
        }
    }
}
