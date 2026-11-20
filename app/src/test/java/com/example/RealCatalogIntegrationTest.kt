package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.catalog.db.LocalCatalogDatabase
import com.example.catalog.model.CatalogProviders
import com.example.catalog.model.CatalogSyncStatus
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.remote.OkHttpItunesCatalogClient
import com.example.catalog.remote.OkHttpMusicBrainzCatalogClient
import com.example.catalog.repository.LocalCatalogRepository
import com.example.data.remote.OkHttpFlacdlApiClient
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Phase 2B: Real Catalog Integration Test against live Apple iTunes API & MusicBrainz API.
 *
 * Strictly opt-in (`-Dflacdl.runLiveCatalogTest=true`) so deterministic offline unit tests
 * (`./gradlew :app:testDebugUnitTest`) never depend on live network connectivity.
 *
 * Run explicitly with:
 *   ./gradlew :app:testDebugUnitTest \
 *     --tests "com.example.RealCatalogIntegrationTest" \
 *     -Dflacdl.runLiveCatalogTest=true
 */
@RunWith(RobolectricTestRunner::class)
class RealCatalogIntegrationTest {

    private lateinit var db: LocalCatalogDatabase

    @Before
    fun setUp() {
        assumeTrue(
            "Skipping live Phase 2B integration test (pass -Dflacdl.runLiveCatalogTest=true to execute).",
            System.getProperty("flacdl.runLiveCatalogTest", "false").equals("true", ignoreCase = true)
        )
        db = LocalCatalogDatabase.createInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) {
            db.close()
        }
    }

    @Test
    fun `real itunes import resync deduplication offline search and limited musicbrainz enrichment`() = runTest {
        val httpClient = OkHttpFlacdlApiClient.defaultOkHttpClient()
        val liveRepo = LocalCatalogRepository(
            database = db,
            itunesProvider = OkHttpItunesCatalogClient(client = httpClient),
            musicBrainzProvider = OkHttpMusicBrainzCatalogClient(client = httpClient)
        )

        val targetArtists = listOf("YOASOBI", "The Weeknd", "Ado")
        val importedArtistIds = mutableMapOf<String, Long>()
        val importedAlbumIds = mutableMapOf<String, Long>()

        // 1. Live import: artist search -> artist lookup (albums) -> album lookup (tracklist)
        for (artistQuery in targetArtists) {
            val artSync = liveRepo.syncSearchArtists(query = artistQuery, limit = 5, country = "ID")
            assertEquals(CatalogSyncStatus.SUCCESS, artSync.status)
            assertTrue("Expected at least 1 artist for $artistQuery", artSync.importedArtists >= 1)

            val matchedArtist = liveRepo.searchArtists(artistQuery, limit = 5).items.firstOrNull {
                it.name.equals(artistQuery, ignoreCase = true)
            } ?: liveRepo.searchArtists(artistQuery, limit = 5).items.first()
            importedArtistIds[artistQuery] = matchedArtist.id

            val discSync = liveRepo.syncArtistDiscography(localArtistId = matchedArtist.id, limit = 15, country = "ID")
            assertEquals(CatalogSyncStatus.SUCCESS, discSync.status)
            assertTrue("Expected albums for $artistQuery", discSync.importedAlbums >= 1)

            val albums = liveRepo.getArtistDiscography(matchedArtist.id, limit = 15).items
            val targetAlbum = albums.firstOrNull { it.trackCount >= 5 } ?: albums.first()
            importedAlbumIds[artistQuery] = targetAlbum.id

            val tlSync = liveRepo.syncAlbumTracklist(localAlbumId = targetAlbum.id, country = "ID")
            assertEquals(CatalogSyncStatus.SUCCESS, tlSync.status)
            assertTrue("Expected tracks for ${targetAlbum.title}", tlSync.importedTracks >= 1)
        }

        val artistsBefore = db.artistDao().countAll()
        val albumsBefore = db.albumDao().countAll()
        val tracksBefore = db.trackDao().countAll()
        assertTrue(artistsBefore >= 3)
        assertTrue(albumsBefore >= 3)
        assertTrue(tracksBefore >= 3)

        // 2. Forced re-sync of the exact same dataset -> ZERO duplicate growth
        for (artistQuery in targetArtists) {
            val resyncArt = liveRepo.syncSearchArtists(query = artistQuery, limit = 5, country = "ID", forceRefresh = true)
            assertEquals(CatalogSyncStatus.SUCCESS, resyncArt.status)
            val aid = importedArtistIds.getValue(artistQuery)
            val resyncDisc = liveRepo.syncArtistDiscography(localArtistId = aid, limit = 15, country = "ID", forceRefresh = true)
            assertEquals(CatalogSyncStatus.SUCCESS, resyncDisc.status)
            val albId = importedAlbumIds.getValue(artistQuery)
            val resyncTl = liveRepo.syncAlbumTracklist(localAlbumId = albId, country = "ID", forceRefresh = true)
            assertEquals(CatalogSyncStatus.SUCCESS, resyncTl.status)
        }

        assertEquals("Re-sync must not duplicate artists", artistsBefore, db.artistDao().countAll())
        assertEquals("Re-sync must not duplicate albums", albumsBefore, db.albumDao().countAll())
        assertEquals("Re-sync must not duplicate tracks", tracksBefore, db.trackDao().countAll())

        // 3. Offline verification using a failing provider on the SAME Room DB (zero network calls allowed)
        val offlineProvider = object : ItunesCatalogProvider {
            private fun fail(): Nothing = throw IOException("Network disabled for offline verification")
            override suspend fun fetchSearchArtistsJson(query: String, limit: Int, country: String): String = fail()
            override suspend fun fetchSearchAlbumsJson(query: String, limit: Int, country: String): String = fail()
            override suspend fun fetchSearchTracksJson(query: String, limit: Int, country: String): String = fail()
            override suspend fun fetchArtistAlbumsJson(providerArtistId: String, limit: Int, country: String): String = fail()
            override suspend fun fetchAlbumTracklistJson(providerAlbumId: String, country: String): String = fail()
        }
        val offlineRepo = LocalCatalogRepository(
            database = db,
            itunesProvider = offlineProvider,
            musicBrainzProvider = null
        )

        for (artistQuery in targetArtists) {
            // Case-insensitive + extra whitespace normalization
            val artPage = offlineRepo.searchArtists("  ${artistQuery.lowercase()}  ")
            assertTrue("Offline artist search failed for $artistQuery", artPage.totalCount >= 1)

            val aid = importedArtistIds.getValue(artistQuery)
            val discPage1 = offlineRepo.getArtistDiscography(aid, limit = 2, offset = 0)
            val discPage2 = offlineRepo.getArtistDiscography(aid, limit = 2, offset = 2)
            assertTrue(discPage1.totalCount >= 1)
            if (discPage2.items.isNotEmpty()) {
                val overlap = discPage1.items.map { it.id }.toSet() intersect discPage2.items.map { it.id }.toSet()
                assertTrue("Pagination pages must not overlap", overlap.isEmpty())
            }

            val albId = importedAlbumIds.getValue(artistQuery)
            val tracklist = offlineRepo.getAlbumTracklist(albId, limit = 50, offset = 0)
            assertTrue("Offline tracklist must not be empty", tracklist.items.isNotEmpty())
            assertTrue(tracklist.items.all { it.albumId == albId && it.provider == CatalogProviders.ITUNES })
        }

        // 4. Limited MusicBrainz enrichment (1 artist + 1 album + 1 track, preserving Apple provider IDs)
        val yoasobiId = importedArtistIds.getValue("YOASOBI")
        val beforeProviderId = liveRepo.getArtistDetails(yoasobiId)?.providerArtistId
        val artEnrich = liveRepo.enrichArtistWithMusicBrainz(yoasobiId)
        assertTrue(artEnrich.matched)
        assertNotNull(artEnrich.musicBrainzId)
        assertEquals(beforeProviderId, liveRepo.getArtistDetails(yoasobiId)?.providerArtistId)

        val yoasobiAlbumId = importedAlbumIds.getValue("YOASOBI")
        val albTracks = liveRepo.getAlbumTracklist(yoasobiAlbumId).items
        assertFalse(albTracks.isEmpty())
        liveRepo.enrichAlbumWithMusicBrainz(yoasobiAlbumId)
        liveRepo.enrichTrackWithMusicBrainz(albTracks.first().id)
    }
}
