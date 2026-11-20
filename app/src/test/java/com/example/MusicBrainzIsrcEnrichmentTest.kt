package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.catalog.db.LocalCatalogDatabase
import com.example.catalog.enrichment.EnrichmentCachePolicy
import com.example.catalog.enrichment.IsrcEnrichmentStatus
import com.example.catalog.enrichment.MusicBrainzRecordingCandidate
import com.example.catalog.model.MusicBrainzArtistEnrichment
import com.example.catalog.model.MusicBrainzRecordingEnrichment
import com.example.catalog.model.MusicBrainzReleaseGroupEnrichment
import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto
import com.example.catalog.remote.CatalogRateLimiter
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.remote.MusicBrainzEnrichmentProvider
import com.example.catalog.remote.MusicBrainzRateLimitException
import com.example.catalog.repository.LocalCatalogRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class MusicBrainzIsrcEnrichmentTest {

    private lateinit var db: LocalCatalogDatabase
    private var fakeNowMs: Long = 1_700_000_000_000L

    @Before
    fun setUp() {
        db = LocalCatalogDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        fakeNowMs = 1_700_000_000_000L
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `1 already enriched track avoids unnecessary requests`() = runTest {
        val mb = FakeMbEnrichmentProvider()
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Kataomoi", artist = "Aimer", durationMs = 207_000L)

        // Pre-populate valid ISRC directly via Room entity update
        val entity = db.trackDao().getEntityById(trackId)!!
        db.trackDao().update(entity.copy(isrc = "JPXS01600012"))

        val result = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.ALREADY_ENRICHED, result.status)
        assertEquals("JPXS01600012", result.isrc)
        assertTrue(result.fromCache)
        assertEquals(0, mb.searchCalls)
        assertEquals(0, mb.detailCalls)
    }

    @Test
    fun `2 positive cache prevents repeated requests`() = runTest {
        val mb = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-pos-1",
                    title = "Kataomoi",
                    artistName = "Aimer",
                    durationMs = 207_200L,
                    isrcs = listOf("JPXS01600012")
                )
            )
        )
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Kataomoi", artist = "Aimer", durationMs = 207_000L)

        val first = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.VERIFIED_ENRICHED, first.status)
        assertEquals(1, mb.searchCalls)

        // Clear track.isrc to prove catalog_cache positive entry prevents second HTTP call
        val entity = db.trackDao().getEntityById(trackId)!!
        db.trackDao().update(entity.copy(isrc = null))

        val second = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.VERIFIED_ENRICHED, second.status)
        assertTrue(second.fromCache)
        assertEquals("JPXS01600012", second.isrc)
        assertEquals(1, mb.searchCalls)
    }

    @Test
    fun `3 negative cache prevents repeated requests within TTL`() = runTest {
        val mb = FakeMbEnrichmentProvider(candidates = emptyList())
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Unknown Track", artist = "Unknown Artist", durationMs = 200_000L)

        val first = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.NOT_FOUND, first.status)
        assertFalse(first.fromCache)
        assertEquals(1, mb.searchCalls)

        val second = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.NOT_FOUND, second.status)
        assertTrue(second.fromCache)
        assertEquals(1, mb.searchCalls)
    }

    @Test
    fun `4 verified candidate triggers ISRC detail lookup when search lacks ISRC`() = runTest {
        val mb = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-detail-1",
                    title = "Yoru ni Kakeru",
                    artistName = "YOASOBI",
                    durationMs = 261_000L,
                    isrcs = emptyList()
                )
            ),
            detailIsrcsByMbid = mapOf("mbid-detail-1" to listOf("jp-xs0-19-00101"))
        )
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Yoru ni Kakeru", artist = "YOASOBI", durationMs = 261_300L)

        val result = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.VERIFIED_ENRICHED, result.status)
        assertEquals("JPXS01900101", result.isrc)
        assertEquals("mbid-detail-1", result.musicBrainzRecordingId)
        assertEquals(1, mb.searchCalls)
        assertEquals(1, mb.detailCalls)
        assertEquals("JPXS01900101", repo.getTrackDetails(trackId)?.isrc)
    }

    @Test
    fun `5 verified candidate with existing ISRC does not make unnecessary detail request`() = runTest {
        val mb = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-inline-1",
                    title = "Yoru ni Kakeru",
                    artistName = "YOASOBI",
                    durationMs = 261_000L,
                    isrcs = listOf("JPXS01900101")
                )
            )
        )
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Yoru ni Kakeru", artist = "YOASOBI", durationMs = 261_000L)

        val result = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.VERIFIED_ENRICHED, result.status)
        assertEquals("JPXS01900101", result.isrc)
        assertEquals(1, mb.searchCalls)
        assertEquals(0, mb.detailCalls)
    }

    @Test
    fun `6 ambiguous candidate does not persist ISRC`() = runTest {
        val mb = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-amb-1",
                    title = "Sparkle",
                    artistName = "RADWIMPS",
                    durationMs = 408_100L,
                    isrcs = listOf("JPPO01600001")
                ),
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-amb-2",
                    title = "Sparkle",
                    artistName = "RADWIMPS",
                    durationMs = 408_200L,
                    isrcs = listOf("JPPO01600002")
                )
            )
        )
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Sparkle", artist = "RADWIMPS", durationMs = 408_000L)

        val result = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.AMBIGUOUS, result.status)
        assertNull(result.isrc)
        assertNull(repo.getTrackDetails(trackId)?.isrc)
        assertEquals(0, mb.detailCalls)
    }

    @Test
    fun `7 unverified candidate does not persist ISRC`() = runTest {
        val mb = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-unver-1",
                    title = "Idol",
                    artistName = "YOASOBI",
                    durationMs = 213_000L,
                    isrcs = listOf("JPXS02300001")
                )
            )
        )
        val repo = createRepo(mb)
        // Missing duration on local track -> TrackMatcher returns Unverified
        val trackId = seedTrack(repo, title = "Idol", artist = "YOASOBI", durationMs = null)

        val result = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.CONFLICT_REJECTED, result.status)
        assertNull(repo.getTrackDetails(trackId)?.isrc)
    }

    @Test
    fun `8 conflicting title or artist rejects enrichment`() = runTest {
        val mb = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-wrong-1",
                    title = "Totally Different Song",
                    artistName = "Different Artist",
                    durationMs = 200_000L,
                    isrcs = listOf("USRC17607839")
                )
            )
        )
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Kataomoi", artist = "Aimer", durationMs = 207_000L)

        val result = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.CONFLICT_REJECTED, result.status)
        assertNull(repo.getTrackDetails(trackId)?.isrc)
    }

    @Test
    fun `9 live or remix version conflict rejects enrichment`() = runTest {
        val mb = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-live-1",
                    title = "Brave Shine (Live at Budokan)",
                    artistName = "Aimer",
                    durationMs = 233_500L,
                    isrcs = listOf("JPXS01500099")
                )
            )
        )
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Brave Shine", artist = "Aimer", durationMs = 233_000L)

        val result = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.CONFLICT_REJECTED, result.status)
        assertNull(repo.getTrackDetails(trackId)?.isrc)
    }

    @Test
    fun `10 invalid ISRC is rejected and not persisted`() = runTest {
        val mb = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-bad-isrc",
                    title = "Gurenge",
                    artistName = "LiSA",
                    durationMs = 237_000L,
                    isrcs = listOf("INVALID-123")
                )
            ),
            detailIsrcsByMbid = mapOf("mbid-bad-isrc" to listOf("NOT_AN_ISRC", "12345"))
        )
        val repo = createRepo(mb)
        val trackId = seedTrack(repo, title = "Gurenge", artist = "LiSA", durationMs = 237_200L)

        val result = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.NOT_FOUND, result.status)
        assertNull(repo.getTrackDetails(trackId)?.isrc)
    }

    @Test
    fun `11 multiple ISRC handling is deterministic and rejects conflicting registrant prefixes`() = runTest {
        // Case A: Same country+registrant prefix -> deterministic lexicographically first
        val mbSamePrefix = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-multi-ok",
                    title = "Lemon",
                    artistName = "Kenshi Yonezu",
                    durationMs = 256_000L,
                    isrcs = listOf("JPXS01800022", "JPXS01800011")
                )
            )
        )
        val repoOk = createRepo(mbSamePrefix)
        val trackOk = seedTrack(repoOk, title = "Lemon", artist = "Kenshi Yonezu", durationMs = 256_000L, providerTrackId = "trk-ok")
        val okResult = repoOk.enrichTrackIsrcWithMusicBrainz(trackOk)
        assertEquals(IsrcEnrichmentStatus.VERIFIED_ENRICHED, okResult.status)
        assertEquals("JPXS01800011", okResult.isrc)

        // Case B: Conflicting country+registrant prefixes -> CONFLICT_REJECTED
        val mbConflict = FakeMbEnrichmentProvider(
            candidates = listOf(
                MusicBrainzRecordingCandidate(
                    musicBrainzRecordingId = "mbid-multi-bad",
                    title = "Lemon",
                    artistName = "Kenshi Yonezu",
                    durationMs = 256_000L,
                    isrcs = listOf("JPXS01800011", "USRC11800099")
                )
            )
        )
        val repoConflict = createRepo(mbConflict)
        val trackConflict = seedTrack(repoConflict, title = "Lemon", artist = "Kenshi Yonezu", durationMs = 256_000L, providerTrackId = "trk-bad")
        val conflictResult = repoConflict.enrichTrackIsrcWithMusicBrainz(trackConflict)
        assertEquals(IsrcEnrichmentStatus.CONFLICT_REJECTED, conflictResult.status)
        assertNull(repoConflict.getTrackDetails(trackConflict)?.isrc)
    }

    @Test
    fun `12 HTTP failure and rate limit return explicit errors without negative caching`() = runTest {
        val rateLimitedMb = FakeMbEnrichmentProvider(
            throwOnSearch = MusicBrainzRateLimitException("HTTP 503", 503)
        )
        val repoRate = createRepo(rateLimitedMb)
        val trackId = seedTrack(repoRate, title = "Usseewa", artist = "Ado", durationMs = 206_000L)

        val rateRes = repoRate.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.RATE_LIMITED, rateRes.status)

        val networkMb = FakeMbEnrichmentProvider(
            throwOnSearch = IOException("Connection reset")
        )
        val repoNet = createRepo(networkMb)
        val netRes = repoNet.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.NETWORK_ERROR, netRes.status)
        // Must not be negative-cached on transient failures
        assertEquals(1, networkMb.searchCalls)
    }

    @Test
    fun `13 MusicBrainz rate limiter enforces 1 request per second spacing`() = runTest {
        var virtualNow = 10_000L
        val delays = mutableListOf<Long>()
        val limiter = CatalogRateLimiter(
            minIntervalMs = 1_100L,
            nowMs = { virtualNow },
            delayFn = { ms ->
                delays.add(ms)
                virtualNow += ms
            }
        )

        limiter.acquirePermit()
        limiter.acquirePermit()
        limiter.acquirePermit()

        assertEquals(listOf(1_100L, 1_100L), delays)
    }

    @Test
    fun `14 expired negative cache triggers fresh lookup`() = runTest {
        val mb = FakeMbEnrichmentProvider(candidates = emptyList())
        val shortPolicy = EnrichmentCachePolicy(
            positiveTtlMs = 10_000L,
            negativeTtlMs = 1_000L
        )
        val repo = createRepo(mb, policy = shortPolicy)
        val trackId = seedTrack(repo, title = "New Release", artist = "Artist X", durationMs = 190_000L)

        val first = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.NOT_FOUND, first.status)
        assertEquals(1, mb.searchCalls)

        // Advance clock past negative TTL
        fakeNowMs += 2_000L
        mb.candidates = listOf(
            MusicBrainzRecordingCandidate(
                musicBrainzRecordingId = "mbid-fresh-1",
                title = "New Release",
                artistName = "Artist X",
                durationMs = 190_100L,
                isrcs = listOf("JPXS02500001")
            )
        )

        val second = repo.enrichTrackIsrcWithMusicBrainz(trackId)
        assertEquals(IsrcEnrichmentStatus.VERIFIED_ENRICHED, second.status)
        assertFalse(second.fromCache)
        assertEquals("JPXS02500001", second.isrc)
        assertEquals(2, mb.searchCalls)
    }

    private fun createRepo(
        mb: MusicBrainzEnrichmentProvider,
        policy: EnrichmentCachePolicy = EnrichmentCachePolicy()
    ): LocalCatalogRepository {
        val noopItunes = object : ItunesCatalogProvider {
            override suspend fun fetchSearchArtistsJson(query: String, limit: Int, country: String) = """{"resultCount":0,"results":[]}"""
            override suspend fun fetchSearchAlbumsJson(query: String, limit: Int, country: String) = """{"resultCount":0,"results":[]}"""
            override suspend fun fetchSearchTracksJson(query: String, limit: Int, country: String) = """{"resultCount":0,"results":[]}"""
            override suspend fun fetchArtistAlbumsJson(providerArtistId: String, limit: Int, country: String) = """{"resultCount":0,"results":[]}"""
            override suspend fun fetchAlbumTracklistJson(providerAlbumId: String, country: String) = """{"resultCount":0,"results":[]}"""
        }
        return LocalCatalogRepository(
            database = db,
            catalogProvider = com.example.catalog.provider.ItunesCatalogAdapter(noopItunes),
            musicBrainzProvider = mb,
            clockMs = { fakeNowMs },
            enrichmentCachePolicy = policy
        )
    }

    private suspend fun seedTrack(
        repo: LocalCatalogRepository,
        title: String,
        artist: String,
        durationMs: Long?,
        providerTrackId: String = "trk-1"
    ): Long {
        val artistId = repo.upsertArtist(
            RemoteArtistDto(providerArtistId = "art-$artist", name = artist)
        )
        val albumId = repo.upsertAlbum(
            RemoteAlbumDto(
                providerAlbumId = "alb-$artist",
                providerArtistId = "art-$artist",
                artistName = artist,
                title = "Sample Album"
            ),
            localArtistId = artistId
        )
        return repo.upsertTrack(
            RemoteTrackDto(
                providerTrackId = providerTrackId,
                providerArtistId = "art-$artist",
                artistName = artist,
                providerAlbumId = "alb-$artist",
                albumTitle = "Sample Album",
                title = title,
                durationMs = durationMs
            ),
            localArtistId = artistId,
            localAlbumId = albumId
        )
    }

    private class FakeMbEnrichmentProvider(
        var candidates: List<MusicBrainzRecordingCandidate> = emptyList(),
        private val detailIsrcsByMbid: Map<String, List<String>> = emptyMap(),
        private val throwOnSearch: Exception? = null
    ) : MusicBrainzEnrichmentProvider {
        var searchCalls: Int = 0
        var detailCalls: Int = 0

        override suspend fun findArtistEnrichment(artistName: String): MusicBrainzArtistEnrichment? = null
        override suspend fun findReleaseGroupEnrichment(
            artistName: String,
            albumTitle: String,
            musicBrainzArtistId: String?
        ): MusicBrainzReleaseGroupEnrichment? = null

        override suspend fun findRecordingEnrichment(
            artistName: String,
            trackTitle: String
        ): MusicBrainzRecordingEnrichment? = null

        override suspend fun searchRecordingCandidates(
            artistName: String,
            trackTitle: String,
            limit: Int
        ): List<MusicBrainzRecordingCandidate> {
            searchCalls++
            throwOnSearch?.let { throw it }
            return candidates
        }

        override suspend fun fetchRecordingIsrcs(recordingMbid: String): List<String> {
            detailCalls++
            return detailIsrcsByMbid[recordingMbid].orEmpty()
        }
    }
}
