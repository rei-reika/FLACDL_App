package com.example

import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadResolutionResult
import com.example.catalog.resolver.UnconfiguredDownloadSourceResolver
import com.example.catalog.search.CandidateDeduplicator
import com.example.catalog.search.StructuredTrackCandidate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchArchitectureFoundationTest {

    @Test
    fun `UnconfiguredDownloadSourceResolver reports unsupported and returns Unavailable`() = runTest {
        assertFalse(UnconfiguredDownloadSourceResolver.capabilities.supportsResolution)
        assertFalse(UnconfiguredDownloadSourceResolver.capabilities.supportsCandidateLookup)

        val track = CanonicalTrackIdentity(
            catalogProviderId = "apple_itunes",
            catalogTrackId = "12345",
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 207_000L
        )
        val result = UnconfiguredDownloadSourceResolver.resolve(track)
        assertTrue(result is DownloadResolutionResult.Unavailable)
        assertEquals("unconfigured", (result as DownloadResolutionResult.Unavailable).providerId)
    }

    @Test
    fun `CandidateDeduplicator merges cross-provider duplicates by ISRC and preserves metadata`() {
        val deduplicator = CandidateDeduplicator()
        val fromItunes = StructuredTrackCandidate(
            canonicalKey = "apple_itunes:1001",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            album = "THE BOOK",
            artworkUrl = "https://example.com/art.jpg",
            durationMs = 261_000L,
            releaseYear = 2021,
            isrc = "JPXS01900101",
            providerIds = mapOf("apple_itunes" to "1001")
        )
        val fromSecondary = StructuredTrackCandidate(
            canonicalKey = "secondary_catalog:abc-99",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            album = null,
            artworkUrl = null,
            durationMs = 261_500L,
            isrc = "jpxs-0190-0101",
            providerIds = mapOf("secondary_catalog" to "abc-99")
        )

        val merged = deduplicator.mergeAndRank(listOf(fromSecondary, fromItunes))
        assertEquals(1, merged.size)

        val item = merged.first()
        assertEquals("isrc:JPXS01900101", item.canonicalKey)
        assertEquals("https://example.com/art.jpg", item.artworkUrl)
        assertEquals("THE BOOK", item.album)
        assertEquals(mapOf("secondary_catalog" to "abc-99", "apple_itunes" to "1001"), item.providerIds)
        assertTrue(item.internalMatch.mergedByIsrc)
        assertEquals(2, item.internalMatch.mergedCandidateCount)
    }

    @Test
    fun `CandidateDeduplicator does not merge distinct versions or incompatible durations`() {
        val deduplicator = CandidateDeduplicator()
        val studio = StructuredTrackCandidate(
            canonicalKey = "apple_itunes:2001",
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 207_000L,
            providerIds = mapOf("apple_itunes" to "2001")
        )
        val acoustic = StructuredTrackCandidate(
            canonicalKey = "apple_itunes:2002",
            title = "Kataomoi (Acoustic Version)",
            artist = "Aimer",
            durationMs = 208_000L,
            providerIds = mapOf("apple_itunes" to "2002")
        )
        val extended = StructuredTrackCandidate(
            canonicalKey = "apple_itunes:2003",
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 260_000L,
            providerIds = mapOf("apple_itunes" to "2003")
        )

        val result = deduplicator.mergeAndRank(listOf(studio, acoustic, extended))
        assertEquals(3, result.size)
    }
}
