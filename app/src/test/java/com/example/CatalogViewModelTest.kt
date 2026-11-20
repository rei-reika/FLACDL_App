package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.catalog.db.LocalCatalogDatabase
import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.repository.LocalCatalogRepository
import com.example.ui.catalog.CatalogSearchCategory
import com.example.ui.catalog.CatalogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CatalogViewModelTest {

    private lateinit var db: LocalCatalogDatabase
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        db = LocalCatalogDatabase.createInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `debounced local search queries Room only and never calls remote API on keystrokes`() = runTest(testDispatcher) {
        val fakeItunes = CountingFakeItunesProvider()
        val repo = LocalCatalogRepository(database = db, itunesProvider = fakeItunes)
        seedSampleCatalog(repo)

        val vm = CatalogViewModel(
            repository = repo,
            ioDispatcher = testDispatcher,
            debounceMs = 300L
        )

        vm.onQueryChanged("A")
        advanceTimeBy(100L)
        vm.onQueryChanged("Aim")
        advanceTimeBy(100L)
        vm.onQueryChanged("Aimer")
        // Before debounce expires, query has not executed yet
        assertFalse(vm.searchUiState.value.hasSearched)

        advanceUntilIdle()

        val state = vm.searchUiState.value
        assertTrue(state.hasSearched)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals(1, state.artists.size)
        assertEquals("Aimer", state.artists.first().name)
        assertEquals(0, fakeItunes.totalCalls)
    }

    @Test
    fun `category switching and clear query update search state cleanly`() = runTest(testDispatcher) {
        val fakeItunes = CountingFakeItunesProvider()
        val repo = LocalCatalogRepository(database = db, itunesProvider = fakeItunes)
        seedSampleCatalog(repo)

        val vm = CatalogViewModel(repository = repo, ioDispatcher = testDispatcher, debounceMs = 0L)

        vm.onQueryChanged("Sun")
        advanceUntilIdle()
        assertEquals(CatalogSearchCategory.ALL, vm.searchUiState.value.selectedCategory)
        assertEquals(1, vm.searchUiState.value.albums.size)
        assertEquals("Sun Dance", vm.searchUiState.value.albums.first().title)

        vm.onCategorySelected(CatalogSearchCategory.TRACKS)
        advanceUntilIdle()
        assertEquals(CatalogSearchCategory.TRACKS, vm.searchUiState.value.selectedCategory)
        assertEquals(1, vm.searchUiState.value.tracks.size)
        assertEquals("Sun Dance Intro", vm.searchUiState.value.tracks.first().title)

        vm.clearQuery()
        advanceUntilIdle()
        val cleared = vm.searchUiState.value
        assertEquals("", cleared.query)
        assertFalse(cleared.hasSearched)
        assertTrue(cleared.artists.isEmpty())
        assertTrue(cleared.albums.isEmpty())
        assertTrue(cleared.tracks.isEmpty())
        assertEquals(0, fakeItunes.totalCalls)
    }

    @Test
    fun `local pagination loads next page of tracks without duplicates`() = runTest(testDispatcher) {
        val fakeItunes = CountingFakeItunesProvider()
        val repo = LocalCatalogRepository(database = db, itunesProvider = fakeItunes)
        val artistId = repo.upsertArtist(
            RemoteArtistDto(providerArtistId = "9001", name = "Pagination Artist")
        )
        val albumId = repo.upsertAlbum(
            RemoteAlbumDto(
                providerAlbumId = "9002",
                providerArtistId = "9001",
                artistName = "Pagination Artist",
                title = "Big Album",
                trackCount = 25
            ),
            localArtistId = artistId
        )
        for (i in 1..25) {
            repo.upsertTrack(
                RemoteTrackDto(
                    providerTrackId = "tr-$i",
                    providerArtistId = "9001",
                    artistName = "Pagination Artist",
                    providerAlbumId = "9002",
                    albumTitle = "Big Album",
                    title = "Track Sample %02d".format(i),
                    durationMs = 180_000L + i * 1000L,
                    trackNumber = i,
                    discNumber = 1
                ),
                localArtistId = artistId,
                localAlbumId = albumId
            )
        }

        val vm = CatalogViewModel(repository = repo, ioDispatcher = testDispatcher, debounceMs = 0L)
        vm.onCategorySelected(CatalogSearchCategory.TRACKS)
        vm.onQueryChanged("Track Sample")
        advanceUntilIdle()

        val firstPage = vm.searchUiState.value
        assertEquals(20, firstPage.tracks.size)
        assertEquals(25, firstPage.tracksTotal)
        assertTrue(firstPage.hasMoreTracks)

        vm.loadMore(CatalogSearchCategory.TRACKS)
        advanceUntilIdle()

        val secondPage = vm.searchUiState.value
        assertEquals(25, secondPage.tracks.size)
        assertFalse(secondPage.hasMoreTracks)
        assertEquals(25, secondPage.tracks.map { it.id }.distinct().size)
        assertEquals(0, fakeItunes.totalCalls)
    }

    @Test
    fun `explicit syncCurrentQuery imports from iTunes and refreshes local Room results`() = runTest(testDispatcher) {
        val fakeItunes = CountingFakeItunesProvider(
            artistSearchJson = """
                {
                  "resultCount": 1,
                  "results": [
                    {
                      "wrapperType": "artist",
                      "artistType": "Artist",
                      "artistName": "YOASOBI",
                      "artistId": 1490256993,
                      "primaryGenreName": "J-Pop"
                    }
                  ]
                }
            """.trimIndent()
        )
        val repo = LocalCatalogRepository(database = db, itunesProvider = fakeItunes)
        val vm = CatalogViewModel(repository = repo, ioDispatcher = testDispatcher, debounceMs = 0L)

        vm.onCategorySelected(CatalogSearchCategory.ARTISTS)
        vm.onQueryChanged("YOASOBI")
        advanceUntilIdle()
        assertTrue(vm.searchUiState.value.artists.isEmpty())
        assertEquals(0, fakeItunes.totalCalls)

        vm.syncCurrentQuery()
        advanceUntilIdle()

        val state = vm.searchUiState.value
        assertEquals(1, fakeItunes.totalCalls)
        assertFalse(state.isSyncing)
        assertNull(state.errorMessage)
        assertNotNull(state.syncNotice)
        assertEquals(1, state.artists.size)
        assertEquals("YOASOBI", state.artists.first().name)
    }

    @Test
    fun `artist detail and album detail load ordered metadata without fabricating missing fields`() = runTest(testDispatcher) {
        val fakeItunes = CountingFakeItunesProvider()
        val repo = LocalCatalogRepository(database = db, itunesProvider = fakeItunes)
        val ids = seedSampleCatalog(repo)

        val vm = CatalogViewModel(repository = repo, ioDispatcher = testDispatcher, debounceMs = 0L)

        vm.loadArtistDetail(ids.artistId)
        advanceUntilIdle()
        val artistState = vm.artistDetailUiState.value
        assertNotNull(artistState.artist)
        assertEquals("Aimer", artistState.artist?.name)
        assertNull(artistState.artist?.country) // Not fabricated
        assertEquals(1, artistState.albums.size)
        assertEquals("Sun Dance", artistState.albums.first().title)

        vm.loadAlbumDetail(ids.albumId)
        advanceUntilIdle()
        val albumState = vm.albumDetailUiState.value
        assertNotNull(albumState.album)
        assertEquals("Sun Dance", albumState.album?.title)
        assertEquals("2019", albumState.album?.releaseYear)
        assertEquals(2, albumState.tracks.size)
        assertEquals(1, albumState.tracks[0].trackNumber)
        assertEquals("3:35", albumState.tracks[0].durationText)
        assertEquals(2, albumState.tracks[1].trackNumber)
        assertNull(albumState.tracks[1].durationText) // Missing duration stays null
    }

    @Test
    fun `sync failure surfaces error message without crashing`() = runTest(testDispatcher) {
        val failingItunes = CountingFakeItunesProvider(shouldFail = true)
        val repo = LocalCatalogRepository(database = db, itunesProvider = failingItunes)
        val vm = CatalogViewModel(repository = repo, ioDispatcher = testDispatcher, debounceMs = 0L)

        vm.onCategorySelected(CatalogSearchCategory.ARTISTS)
        vm.onQueryChanged("Ado")
        advanceUntilIdle()

        vm.syncCurrentQuery()
        advanceUntilIdle()

        val state = vm.searchUiState.value
        assertFalse(state.isSyncing)
        assertNotNull(state.errorMessage)
        assertTrue(state.artists.isEmpty())
    }

    private data class SeedIds(val artistId: Long, val albumId: Long)

    private suspend fun seedSampleCatalog(repo: LocalCatalogRepository): SeedIds {
        val artistId = repo.upsertArtist(
            RemoteArtistDto(
                providerArtistId = "446184566",
                name = "Aimer",
                primaryGenre = "J-Pop",
                country = null
            )
        )
        val albumId = repo.upsertAlbum(
            RemoteAlbumDto(
                providerAlbumId = "1536243222",
                providerArtistId = "446184566",
                artistName = "Aimer",
                title = "Sun Dance",
                releaseDate = "2019-04-10T07:00:00Z",
                genre = "J-Pop",
                trackCount = 2
            ),
            localArtistId = artistId
        )
        repo.upsertTrack(
            RemoteTrackDto(
                providerTrackId = "1536243224",
                providerArtistId = "446184566",
                artistName = "Aimer",
                providerAlbumId = "1536243222",
                albumTitle = "Sun Dance",
                title = "Second Song",
                durationMs = null,
                trackNumber = 2,
                discNumber = 1
            ),
            localArtistId = artistId,
            localAlbumId = albumId
        )
        repo.upsertTrack(
            RemoteTrackDto(
                providerTrackId = "1536243223",
                providerArtistId = "446184566",
                artistName = "Aimer",
                providerAlbumId = "1536243222",
                albumTitle = "Sun Dance",
                title = "Sun Dance Intro",
                durationMs = 215_000L,
                trackNumber = 1,
                discNumber = 1
            ),
            localArtistId = artistId,
            localAlbumId = albumId
        )
        return SeedIds(artistId = artistId, albumId = albumId)
    }

    private class CountingFakeItunesProvider(
        private val artistSearchJson: String = """{"resultCount":0,"results":[]}""",
        private val albumSearchJson: String = """{"resultCount":0,"results":[]}""",
        private val trackSearchJson: String = """{"resultCount":0,"results":[]}""",
        private val artistAlbumsJson: String = """{"resultCount":0,"results":[]}""",
        private val albumTracklistJson: String = """{"resultCount":0,"results":[]}""",
        private val shouldFail: Boolean = false
    ) : ItunesCatalogProvider {
        var totalCalls: Int = 0
            private set

        private fun checkFailure() {
            totalCalls++
            if (shouldFail) throw IOException("Simulated iTunes network error")
        }

        override suspend fun fetchSearchArtistsJson(query: String, limit: Int, country: String): String {
            checkFailure()
            return artistSearchJson
        }

        override suspend fun fetchSearchAlbumsJson(query: String, limit: Int, country: String): String {
            checkFailure()
            return albumSearchJson
        }

        override suspend fun fetchSearchTracksJson(query: String, limit: Int, country: String): String {
            checkFailure()
            return trackSearchJson
        }

        override suspend fun fetchArtistAlbumsJson(providerArtistId: String, limit: Int, country: String): String {
            checkFailure()
            return artistAlbumsJson
        }

        override suspend fun fetchAlbumTracklistJson(providerAlbumId: String, country: String): String {
            checkFailure()
            return albumTracklistJson
        }
    }
}
