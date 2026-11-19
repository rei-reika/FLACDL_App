package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.catalog.db.LocalCatalogDatabase
import com.example.catalog.model.CatalogProviders
import com.example.catalog.model.CatalogSyncStatus
import com.example.catalog.model.MusicBrainzArtistEnrichment
import com.example.catalog.model.MusicBrainzRecordingEnrichment
import com.example.catalog.model.MusicBrainzReleaseGroupEnrichment
import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto
import com.example.catalog.remote.CatalogRateLimiter
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.remote.MusicBrainzEnrichmentProvider
import com.example.catalog.repository.LocalCatalogRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class LocalCatalogEngineTest {

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

    // -------------------------------------------------------------------------
    // 1. Database: Insert, FK Relationships, Upsert Without Duplicates, Nullable Fields
    // -------------------------------------------------------------------------
    @Test
    fun `database maintains foreign keys upserts without duplicates and handles nullable fields`() = runTest {
        val repo = LocalCatalogRepository(
            database = db,
            itunesProvider = FakeItunesProvider(),
            clockMs = { fakeNowMs }
        )

        val artistId = repo.upsertArtist(
            RemoteArtistDto(
                providerArtistId = "1490256993",
                name = "YOASOBI",
                primaryGenre = null,
                country = null,
                artworkUrl = null
            )
        )
        assertTrue(artistId > 0L)

        val albumId = repo.upsertAlbum(
            RemoteAlbumDto(
                providerAlbumId = "1542182291",
                providerArtistId = "1490256993",
                artistName = "YOASOBI",
                title = "THE BOOK",
                releaseDate = null,
                genre = null,
                artworkUrl = "https://is1-ssl.mzstatic.com/image/thumb/test/100x100bb.jpg",
                trackCount = 9
            ),
            localArtistId = artistId
        )
        assertTrue(albumId > 0L)

        val track1Id = repo.upsertTrack(
            RemoteTrackDto(
                providerTrackId = "1542182299",
                providerArtistId = "1490256993",
                artistName = "YOASOBI",
                providerAlbumId = "1542182291",
                albumTitle = "THE BOOK",
                title = "Epilogue",
                durationMs = null,
                trackNumber = 1,
                discNumber = 1
            ),
            localArtistId = artistId,
            localAlbumId = albumId
        )
        // Standalone track with nullable album_id
        val trackStandaloneId = repo.upsertTrack(
            RemoteTrackDto(
                providerTrackId = "999000111",
                providerArtistId = "1490256993",
                artistName = "YOASOBI",
                title = "Standalone Track",
                durationMs = 210_000L
            ),
            localArtistId = artistId,
            localAlbumId = null
        )

        // Upsert same artist & album with updated metadata -> keeps exact same local PK, zero duplicates
        val artistIdUpdated = repo.upsertArtist(
            RemoteArtistDto(
                providerArtistId = "1490256993",
                name = "YOASOBI",
                primaryGenre = "J-Pop",
                country = "JP"
            )
        )
        assertEquals(artistId, artistIdUpdated)
        assertEquals(1, db.artistDao().countAll())

        val albumIdUpdated = repo.upsertAlbum(
            RemoteAlbumDto(
                providerAlbumId = "1542182291",
                providerArtistId = "1490256993",
                artistName = "YOASOBI",
                title = "THE BOOK",
                releaseDate = "2021-01-06",
                genre = "J-Pop",
                trackCount = 9
            ),
            localArtistId = artistId
        )
        assertEquals(albumId, albumIdUpdated)
        assertEquals(1, db.albumDao().countAll())
        assertEquals(2, db.trackDao().countAll())

        val details = repo.getArtistDetails(artistId)
        assertEquals("J-Pop", details?.primaryGenre)
        assertEquals("JP", details?.country)

        val albDetails = repo.getAlbumDetails(albumId)
        assertEquals("2021-01-06", albDetails?.releaseDate)
        assertEquals("YOASOBI", albDetails?.artistName)

        val tr1 = repo.getTrackDetails(track1Id)
        assertEquals(albumId, tr1?.albumId)
        assertNull(tr1?.durationMs)

        val trStandalone = repo.getTrackDetails(trackStandaloneId)
        assertNull(trStandalone?.albumId)
        assertEquals(210_000L, trStandalone?.durationMs)
    }

    // -------------------------------------------------------------------------
    // 2. Local Search, Case-Insensitivity, Pagination, Empty & No-Result
    // -------------------------------------------------------------------------
    @Test
    fun `local search supports case insensitivity whitespace normalization pagination and empty queries`() = runTest {
        val repo = LocalCatalogRepository(
            database = db,
            itunesProvider = FakeItunesProvider(),
            clockMs = { fakeNowMs }
        )

        val artistId = repo.upsertArtist(RemoteArtistDto("479756766", "The Weeknd", "R&B/Soul"))
        val album1 = repo.upsertAlbum(
            RemoteAlbumDto("1499378108", "479756766", "The Weeknd", "After Hours", "2020-03-20", "R&B/Soul", trackCount = 5),
            artistId
        )
        repo.upsertAlbum(
            RemoteAlbumDto("1363309866", "479756766", "The Weeknd", "My Dear Melancholy,", "2018-03-30", "R&B/Soul", trackCount = 6),
            artistId
        )

        for (i in 1..5) {
            repo.upsertTrack(
                RemoteTrackDto(
                    providerTrackId = "trk_$i",
                    providerArtistId = "479756766",
                    artistName = "The Weeknd",
                    providerAlbumId = "1499378108",
                    albumTitle = "After Hours",
                    title = "Night Track $i",
                    durationMs = (180 + i * 10) * 1000L,
                    trackNumber = i,
                    discNumber = 1
                ),
                localArtistId = artistId,
                localAlbumId = album1
            )
        }

        // Empty query returns empty page cleanly
        val emptyPage = repo.searchTracks("   ")
        assertEquals(0, emptyPage.totalCount)
        assertTrue(emptyPage.items.isEmpty())

        // No-result query
        val noResult = repo.searchArtists("NonExistentArtistXYZ")
        assertEquals(0, noResult.totalCount)
        assertTrue(noResult.items.isEmpty())

        // Case-insensitive + extra whitespace normalization
        val artPage = repo.searchArtists("  the   WEEKND  ")
        assertEquals(1, artPage.totalCount)
        assertEquals("The Weeknd", artPage.items.first().name)

        // Search album by title & by artist
        val albByTitle = repo.searchAlbums("after hours")
        assertEquals(1, albByTitle.totalCount)
        val albByArtist = repo.searchAlbumsByArtist("weeknd")
        assertEquals(2, albByArtist.totalCount)

        // Local pagination (LIMIT 2 OFFSET 0, 2, 4) with zero overlap and stable ordering
        val p1 = repo.searchTracksByArtist("the weeknd", limit = 2, offset = 0)
        val p2 = repo.searchTracksByArtist("the weeknd", limit = 2, offset = 2)
        val p3 = repo.searchTracksByArtist("the weeknd", limit = 2, offset = 4)
        assertEquals(5, p1.totalCount)
        assertEquals(2, p1.items.size)
        assertTrue(p1.hasMore)
        assertEquals(2, p2.items.size)
        assertTrue(p2.hasMore)
        assertEquals(1, p3.items.size)
        assertFalse(p3.hasMore)

        val allIds = (p1.items + p2.items + p3.items).map { it.id }.toSet()
        assertEquals(5, allIds.size)
        assertEquals(listOf(1, 2, 3, 4, 5), (p1.items + p2.items + p3.items).map { it.trackNumber })
    }

    // -------------------------------------------------------------------------
    // 3. Cache Hit, Expiration, Upstream Failure, Retry, and Offline Operation
    // -------------------------------------------------------------------------
    @Test
    fun `cache hit prevents duplicate calls expiration refetches failures retry cleanly and offline works`() = runTest {
        val fakeItunes = FakeItunesProvider()
        val repo = LocalCatalogRepository(
            database = db,
            itunesProvider = fakeItunes,
            cacheTtlMs = 60_000L,
            clockMs = { fakeNowMs }
        )

        // 1. First sync imports from Apple iTunes
        val sync1 = repo.syncSearchTracks("YOASOBI")
        assertEquals(CatalogSyncStatus.SUCCESS, sync1.status)
        assertFalse(sync1.fromCache)
        assertEquals(1, sync1.importedTracks)
        assertEquals(1, fakeItunes.trackSearchCalls)

        // 2. Second sync within TTL hits cache -> ZERO duplicate API call!
        val sync2 = repo.syncSearchTracks("  yoasobi  ")
        assertEquals(CatalogSyncStatus.CACHED, sync2.status)
        assertTrue(sync2.fromCache)
        assertEquals(1, fakeItunes.trackSearchCalls)

        // 3. Advance clock past TTL -> cache expires -> upstream called again without duplicating Room rows
        fakeNowMs += 120_000L
        val sync3 = repo.syncSearchTracks("YOASOBI")
        assertEquals(CatalogSyncStatus.SUCCESS, sync3.status)
        assertFalse(sync3.fromCache)
        assertEquals(2, fakeItunes.trackSearchCalls)
        assertEquals(1, db.trackDao().countAll())

        // 4. Import album tracklist while online
        val album = repo.searchAlbums("THE BOOK").items.first()
        val tlSync = repo.syncAlbumTracklist(album.id)
        assertEquals(CatalogSyncStatus.SUCCESS, tlSync.status)
        assertEquals(2, tlSync.importedTracks)

        // 5. Simulate network offline / upstream failure
        fakeItunes.shouldFail = true
        val failedSync = repo.syncSearchArtists("Ado")
        assertEquals(CatalogSyncStatus.FAILED, failedSync.status)
        assertNotNull(failedSync.errorMessage)

        // 6. Verify local search & album tracklist STILL WORK 100% OFFLINE!
        val offlineTracks = repo.searchTracks("夜に駆ける")
        assertEquals(1, offlineTracks.totalCount)
        val offlineTracklist = repo.getAlbumTracklist(album.id)
        assertEquals(2, offlineTracklist.totalCount)
        assertEquals(listOf("Epilogue", "夜に駆ける"), offlineTracklist.items.map { it.title })

        // 7. Network recovers -> retry succeeds cleanly
        fakeItunes.shouldFail = false
        val retrySync = repo.syncSearchArtists("Ado")
        assertEquals(CatalogSyncStatus.SUCCESS, retrySync.status)
        assertEquals(1, repo.searchArtists("Ado").totalCount)
    }

    // -------------------------------------------------------------------------
    // 4. Request Throttling & Optional MusicBrainz Enrichment
    // -------------------------------------------------------------------------
    @Test
    fun `rate limiter enforces minimum interval and musicbrainz enriches without blocking search`() = runTest {
        var virtualClock = 10_000L
        val recordedDelays = mutableListOf<Long>()
        val limiter = CatalogRateLimiter(
            maxRequestsPerWindow = 2,
            windowMs = 10_000L,
            minIntervalMs = 1_150L,
            clockMs = { virtualClock },
            delayFn = { ms ->
                recordedDelays.add(ms)
                virtualClock += ms
            }
        )

        limiter.acquirePermit()
        limiter.acquirePermit()
        limiter.acquirePermit()
        assertTrue(recordedDelays.any { it >= 1_150L })
        assertTrue(recordedDelays.sum() >= 10_000L)

        // MusicBrainz enrichment populates MBIDs and ISRC on existing local records
        val fakeMb = object : MusicBrainzEnrichmentProvider {
            override suspend fun findArtistEnrichment(artistName: String) =
                MusicBrainzArtistEnrichment("df6c619f-4334-43e2-8b6a-4a32af1e4f85", country = "JP")

            override suspend fun findReleaseGroupEnrichment(
                artistName: String,
                albumTitle: String,
                musicBrainzArtistId: String?
            ) = MusicBrainzReleaseGroupEnrichment("6ddc7e3d-6fc7-4859-9e35-b666e49b40f5", firstReleaseDate = "2021-01-06")

            override suspend fun findRecordingEnrichment(artistName: String, trackTitle: String) =
                MusicBrainzRecordingEnrichment("3b43e6db-7604-4724-bf47-832328124966", durationMs = 261_000L, isrc = "JPU902000123")
        }

        val repo = LocalCatalogRepository(
            database = db,
            itunesProvider = FakeItunesProvider(),
            musicBrainzProvider = fakeMb,
            clockMs = { fakeNowMs }
        )

        val artistId = repo.upsertArtist(RemoteArtistDto("1490256993", "YOASOBI"))
        val albumId = repo.upsertAlbum(RemoteAlbumDto("1542182291", "1490256993", "YOASOBI", "THE BOOK"), artistId)
        val trackId = repo.upsertTrack(
            RemoteTrackDto("1490256995", "1490256993", "YOASOBI", "1542182291", "THE BOOK", title = "夜に駆ける"),
            artistId,
            albumId
        )

        val resA = repo.enrichArtistWithMusicBrainz(artistId)
        val resAl = repo.enrichAlbumWithMusicBrainz(albumId)
        val resT = repo.enrichTrackWithMusicBrainz(trackId)

        assertTrue(resA.matched)
        assertTrue(resAl.matched)
        assertTrue(resT.matched)
        assertEquals("df6c619f-4334-43e2-8b6a-4a32af1e4f85", repo.getArtistDetails(artistId)?.musicBrainzArtistId)
        assertEquals("6ddc7e3d-6fc7-4859-9e35-b666e49b40f5", repo.getAlbumDetails(albumId)?.musicBrainzReleaseGroupId)
        assertEquals("JPU902000123", repo.getTrackDetails(trackId)?.isrc)
    }

    private class FakeItunesProvider : ItunesCatalogProvider {
        var artistSearchCalls = 0
        var albumSearchCalls = 0
        var trackSearchCalls = 0
        var shouldFail = false

        override suspend fun fetchSearchArtistsJson(query: String, limit: Int, country: String): String {
            artistSearchCalls++
            if (shouldFail) throw IOException("Network disabled")
            return """
                {
                  "resultCount": 1,
                  "results": [
                    {
                      "wrapperType": "artist",
                      "artistId": 1492604670,
                      "artistName": "Ado",
                      "primaryGenreName": "J-Pop"
                    }
                  ]
                }
            """.trimIndent()
        }

        override suspend fun fetchSearchAlbumsJson(query: String, limit: Int, country: String): String {
            albumSearchCalls++
            if (shouldFail) throw IOException("Network disabled")
            return """{"resultCount":0,"results":[]}"""
        }

        override suspend fun fetchSearchTracksJson(query: String, limit: Int, country: String): String {
            trackSearchCalls++
            if (shouldFail) throw IOException("Network disabled")
            return """
                {
                  "resultCount": 1,
                  "results": [
                    {
                      "wrapperType": "track",
                      "kind": "song",
                      "artistId": 1490256993,
                      "collectionId": 1542182291,
                      "trackId": 1490256995,
                      "artistName": "YOASOBI",
                      "collectionName": "THE BOOK",
                      "trackName": "夜に駆ける",
                      "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/test/100x100bb.jpg",
                      "trackTimeMillis": 261013,
                      "trackNumber": 2,
                      "discNumber": 1,
                      "trackCount": 9,
                      "releaseDate": "2021-01-06T08:00:00Z",
                      "primaryGenreName": "J-Pop"
                    }
                  ]
                }
            """.trimIndent()
        }

        override suspend fun fetchArtistAlbumsJson(providerArtistId: String, limit: Int, country: String): String {
            if (shouldFail) throw IOException("Network disabled")
            return """{"resultCount":0,"results":[]}"""
        }

        override suspend fun fetchAlbumTracklistJson(providerAlbumId: String, country: String): String {
            if (shouldFail) throw IOException("Network disabled")
            return """
                {
                  "resultCount": 3,
                  "results": [
                    {
                      "wrapperType": "collection",
                      "collectionId": 1542182291,
                      "artistId": 1490256993,
                      "artistName": "YOASOBI",
                      "collectionName": "THE BOOK",
                      "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/test/100x100bb.jpg",
                      "trackCount": 2,
                      "releaseDate": "2021-01-06T08:00:00Z",
                      "primaryGenreName": "J-Pop"
                    },
                    {
                      "wrapperType": "track",
                      "kind": "song",
                      "artistId": 1490256993,
                      "collectionId": 1542182291,
                      "trackId": 1542182299,
                      "artistName": "YOASOBI",
                      "collectionName": "THE BOOK",
                      "trackName": "Epilogue",
                      "trackTimeMillis": 50547,
                      "trackNumber": 1,
                      "discNumber": 1
                    },
                    {
                      "wrapperType": "track",
                      "kind": "song",
                      "artistId": 1490256993,
                      "collectionId": 1542182291,
                      "trackId": 1490256995,
                      "artistName": "YOASOBI",
                      "collectionName": "THE BOOK",
                      "trackName": "夜に駆ける",
                      "trackTimeMillis": 261013,
                      "trackNumber": 2,
                      "discNumber": 1
                    }
                  ]
                }
            """.trimIndent()
        }
    }
}
