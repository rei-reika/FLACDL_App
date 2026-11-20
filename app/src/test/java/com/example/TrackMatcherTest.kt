package com.example

import com.example.catalog.matching.TrackMatchCandidate
import com.example.catalog.matching.TrackMatchMethod
import com.example.catalog.matching.TrackMatchQuery
import com.example.catalog.matching.TrackMatchResult
import com.example.catalog.matching.TrackMatchStatus
import com.example.catalog.matching.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {

    private val matcher = TrackMatcher()

    @Test
    fun `1 exact ISRC match returns Verified with ISRC_EXACT method`() {
        val query = TrackMatchQuery(
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            album = "THE BOOK",
            durationMs = 261_000L,
            isrc = "jp-xs0-19-00101"
        )
        val candidate = TrackMatchCandidate(
            candidateId = "cand-1",
            providerId = "provider_a",
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            album = "THE BOOK",
            durationMs = 261_500L,
            isrc = "JPXS01900101"
        )

        val result = matcher.match(query, listOf(candidate))
        assertTrue(result is TrackMatchResult.Verified)
        val verified = result as TrackMatchResult.Verified
        assertEquals(TrackMatchStatus.VERIFIED, verified.status)
        assertEquals(TrackMatchMethod.ISRC_EXACT, verified.evidence.matchMethod)
        assertEquals("cand-1", verified.candidate.candidateId)
        assertTrue(verified.evidence.matchedFields.contains("isrc"))
    }

    @Test
    fun `2 ISRC match with conflicting title is rejected as Unavailable`() {
        val query = TrackMatchQuery(
            title = "Yoru ni Kakeru",
            artist = "YOASOBI",
            durationMs = 261_000L,
            isrc = "JPXS01900101"
        )
        val conflicting = TrackMatchCandidate(
            candidateId = "cand-bad",
            providerId = "provider_a",
            title = "Completely Different Song",
            artist = "YOASOBI",
            durationMs = 261_000L,
            isrc = "JPXS01900101"
        )

        val result = matcher.match(query, listOf(conflicting))
        assertTrue(result is TrackMatchResult.Unavailable)
        val unavailable = result as TrackMatchResult.Unavailable
        assertTrue(unavailable.reason.contains("ISRC", ignoreCase = true))
    }

    @Test
    fun `3 normalized title and artist match handles case punctuation and whitespace`() {
        val query = TrackMatchQuery(
            title = "  Don't  Stop   Believin'!! ",
            artist = "JOURNEY",
            durationMs = 250_000L
        )
        val candidate = TrackMatchCandidate(
            candidateId = "cand-3",
            providerId = "provider_b",
            title = "Dont Stop Believin",
            artist = "Journey",
            durationMs = 250_800L
        )

        val result = matcher.match(query, listOf(candidate))
        assertTrue(result is TrackMatchResult.Verified)
        assertEquals(TrackMatchMethod.TITLE_ARTIST_DURATION, (result as TrackMatchResult.Verified).evidence.matchMethod)
    }

    @Test
    fun `4 featuring notation differences match cleanly`() {
        val query = TrackMatchQuery(
            title = "Stay (feat. Justin Bieber)",
            artist = "The Kid LAROI",
            durationMs = 141_800L
        )
        val candidate = TrackMatchCandidate(
            candidateId = "cand-4",
            providerId = "provider_b",
            title = "STAY",
            artist = "The Kid LAROI & Justin Bieber",
            durationMs = 141_000L
        )

        val result = matcher.match(query, listOf(candidate))
        assertTrue(result is TrackMatchResult.Verified)
        assertEquals("cand-4", (result as TrackMatchResult.Verified).candidate.candidateId)
    }

    @Test
    fun `5 remaster notation differences match cleanly`() {
        val query = TrackMatchQuery(
            title = "Bohemian Rhapsody - 2011 Remaster",
            artist = "Queen",
            album = "A Night at the Opera (Deluxe Edition)",
            durationMs = 354_300L
        )
        val candidate = TrackMatchCandidate(
            candidateId = "cand-5",
            providerId = "provider_b",
            title = "Bohemian Rhapsody (Remastered 2011)",
            artist = "Queen",
            album = "A Night at the Opera",
            durationMs = 355_000L
        )

        val result = matcher.match(query, listOf(candidate))
        assertTrue(result is TrackMatchResult.Verified)
        assertEquals("cand-5", (result as TrackMatchResult.Verified).candidate.candidateId)
    }

    @Test
    fun `6 duration difference within 3000 ms returns Verified`() {
        val query = TrackMatchQuery(
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 207_000L
        )
        val candidate = TrackMatchCandidate(
            candidateId = "cand-6",
            providerId = "provider_b",
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 209_900L // 2,900 ms delta
        )

        val result = matcher.match(query, listOf(candidate))
        assertTrue(result is TrackMatchResult.Verified)
        assertEquals(2_900L, (result as TrackMatchResult.Verified).evidence.durationDeltaMs)
    }

    @Test
    fun `7 duration difference outside verified threshold returns Unverified or Unavailable`() {
        val query = TrackMatchQuery(
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 207_000L
        )
        val borderline = TrackMatchCandidate(
            candidateId = "cand-borderline",
            providerId = "provider_b",
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 212_000L // 5,000 ms delta -> Unverified
        )
        val farOff = TrackMatchCandidate(
            candidateId = "cand-far",
            providerId = "provider_b",
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 225_000L // 18,000 ms delta -> Unavailable
        )

        val borderlineResult = matcher.match(query, listOf(borderline))
        assertTrue(borderlineResult is TrackMatchResult.Unverified)
        assertEquals(5_000L, (borderlineResult as TrackMatchResult.Unverified).evidence.durationDeltaMs)

        val farResult = matcher.match(query, listOf(farOff))
        assertTrue(farResult is TrackMatchResult.Unavailable)
    }

    @Test
    fun `8 missing duration returns Unverified and is never auto-verified without ISRC`() {
        val query = TrackMatchQuery(
            title = "Idol",
            artist = "YOASOBI",
            album = "THE BOOK 3",
            durationMs = null
        )
        val candidate = TrackMatchCandidate(
            candidateId = "cand-8",
            providerId = "provider_b",
            title = "Idol",
            artist = "YOASOBI",
            album = "THE BOOK 3",
            durationMs = 213_000L
        )

        val result = matcher.match(query, listOf(candidate))
        assertTrue(result is TrackMatchResult.Unverified)
        val unverified = result as TrackMatchResult.Unverified
        assertEquals(TrackMatchStatus.UNVERIFIED, unverified.status)
        assertNull(unverified.evidence.durationDeltaMs)
    }

    @Test
    fun `9 missing album metadata still verifies strong title artist and duration match`() {
        val query = TrackMatchQuery(
            title = "Gurenge",
            artist = "LiSA",
            album = null,
            durationMs = 237_000L
        )
        val candidate = TrackMatchCandidate(
            candidateId = "cand-9",
            providerId = "provider_b",
            title = "Gurenge",
            artist = "LiSA",
            album = null,
            durationMs = 237_400L
        )

        val result = matcher.match(query, listOf(candidate))
        assertTrue(result is TrackMatchResult.Verified)
        assertNull((result as TrackMatchResult.Verified).evidence.albumSimilarity)
    }

    @Test
    fun `10 multiple equally plausible candidates return Ambiguous instead of silently picking first`() {
        val query = TrackMatchQuery(
            title = "Sparkle",
            artist = "RADWIMPS",
            durationMs = 408_000L
        )
        val candA = TrackMatchCandidate(
            candidateId = "cand-a",
            providerId = "provider_b",
            title = "Sparkle",
            artist = "RADWIMPS",
            album = "Your Name.",
            durationMs = 408_200L
        )
        val candB = TrackMatchCandidate(
            candidateId = "cand-b",
            providerId = "provider_b",
            title = "Sparkle",
            artist = "RADWIMPS",
            album = "Human Bloom",
            durationMs = 408_300L
        )

        val result = matcher.match(query, listOf(candA, candB))
        assertTrue(result is TrackMatchResult.Ambiguous)
        val ambiguous = result as TrackMatchResult.Ambiguous
        assertEquals(TrackMatchStatus.AMBIGUOUS, ambiguous.status)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `11 live versus studio version conflict is rejected`() {
        val studioQuery = TrackMatchQuery(
            title = "Brave Shine",
            artist = "Aimer",
            durationMs = 233_000L
        )
        val liveCandidate = TrackMatchCandidate(
            candidateId = "cand-live",
            providerId = "provider_b",
            title = "Brave Shine (Live at Budokan)",
            artist = "Aimer",
            durationMs = 234_000L
        )

        val result = matcher.match(studioQuery, listOf(liveCandidate))
        assertTrue(result is TrackMatchResult.Unavailable)
        assertTrue((result as TrackMatchResult.Unavailable).reason.contains("version conflict", ignoreCase = true))
    }

    @Test
    fun `12 no valid candidates returns Unavailable`() {
        val query = TrackMatchQuery(
            title = "Unravel",
            artist = "TK from Ling tosite sigure",
            durationMs = 238_000L
        )
        val unrelated = TrackMatchCandidate(
            candidateId = "cand-unrelated",
            providerId = "provider_b",
            title = "Blinding Lights",
            artist = "The Weeknd",
            durationMs = 200_000L
        )

        val emptyResult = matcher.match(query, emptyList())
        assertTrue(emptyResult is TrackMatchResult.Unavailable)

        val mismatchResult = matcher.match(query, listOf(unrelated))
        assertTrue(mismatchResult is TrackMatchResult.Unavailable)
    }

    @Test
    fun `13 missing or incomplete query or candidate metadata returns Unavailable`() {
        val blankTitleQuery = TrackMatchQuery(title = "   ", artist = "Aimer", durationMs = 200_000L)
        val validCandidate = TrackMatchCandidate(
            candidateId = "cand-valid",
            providerId = "provider_b",
            title = "Kataomoi",
            artist = "Aimer",
            durationMs = 200_000L
        )
        assertTrue(matcher.match(blankTitleQuery, listOf(validCandidate)) is TrackMatchResult.Unavailable)

        val validQuery = TrackMatchQuery(title = "Kataomoi", artist = "Aimer", durationMs = 200_000L)
        val blankArtistCandidate = TrackMatchCandidate(
            candidateId = "cand-blank",
            providerId = "provider_b",
            title = "Kataomoi",
            artist = "",
            durationMs = 200_000L
        )
        assertTrue(matcher.match(validQuery, listOf(blankArtistCandidate)) is TrackMatchResult.Unavailable)
    }

    @Test
    fun `14 deterministic result ordering independent of candidate input order`() {
        val query = TrackMatchQuery(
            title = "Sparkle",
            artist = "RADWIMPS",
            durationMs = 408_000L
        )
        val cand1 = TrackMatchCandidate(
            candidateId = "cand-alpha",
            providerId = "provider_b",
            title = "Sparkle",
            artist = "RADWIMPS",
            durationMs = 408_000L
        )
        val cand2 = TrackMatchCandidate(
            candidateId = "cand-beta",
            providerId = "provider_b",
            title = "Sparkle",
            artist = "RADWIMPS",
            durationMs = 408_000L
        )

        val forward = matcher.match(query, listOf(cand1, cand2)) as TrackMatchResult.Ambiguous
        val reversed = matcher.match(query, listOf(cand2, cand1)) as TrackMatchResult.Ambiguous

        assertEquals(
            forward.candidates.map { it.candidate.candidateId },
            reversed.candidates.map { it.candidate.candidateId }
        )
        assertEquals(listOf("cand-alpha", "cand-beta"), forward.candidates.map { it.candidate.candidateId })
    }
}
