package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.catalog.db.LocalCatalogDatabase
import com.example.catalog.model.CatalogProviders
import com.example.catalog.model.CatalogSyncStatus
import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto
import com.example.catalog.provider.CatalogProvider
import com.example.catalog.provider.CatalogProviderResult
import com.example.catalog.provider.ItunesCatalogAdapter
import com.example.catalog.provider.ProviderCapabilities
import com.example.catalog.provider.ProviderDescriptor
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.repository.LocalCatalogRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogProviderAbstractionTest {

    private lateinit var db: LocalCatalogDatabase

    @Before
    fun setUp() {
        db = LocalCatalogDatabase.createInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `repository delegates sync to custom CatalogProvider without iTunes coupling`() = runTest {
        val customProvider = FakeNormalizedCatalogProvider(
            providerId = "custom_catalog",
            artists = listOf(
                RemoteArtistDto(
                    providerArtistId = "art-100",
                    name = "Kenshi Yonezu",
                    primaryGenre = "J-Pop",
                    country = "JP",
                    provider = "custom_catalog"
                )
            ),
            tracks = listOf(
                RemoteTrackDto(
                    providerTrackId = "trk-200",
                    providerArtistId = "art-100",
                    artistName = "Kenshi Yonezu",
                    providerAlbumId = "alb-300",
                    albumTitle = "STRAY SHEEP",
                    albumReleaseDate = "2020-08-05",
                    albumTrackCount = 15,
                    title = "Lemon",
                    durationMs = 256_000L,
                    trackNumber = 3,
                    discNumber = 1,
                    genre = "J-Pop",
                    provider = "custom_catalog"
                )
            )
        )
        val repo = LocalCatalogRepository(
            database = db,
            catalogProvider = customProvider
        )

        assertEquals("custom_catalog", repo.activeCatalogProviderDescriptor.id)

        val artistSync = repo.syncSearchArtists("Kenshi Yonezu")
        assertEquals(CatalogSyncStatus.SUCCESS, artistSync.status)
        assertEquals("custom_catalog", artistSync.provider)
        assertEquals(1, artistSync.importedArtists)

        val trackSync = repo.syncSearchTracks("Lemon")
        assertEquals(CatalogSyncStatus.SUCCESS, trackSync.status)
        assertEquals("custom_catalog", trackSync.provider)
        assertEquals(1, trackSync.importedTracks)

        val localTracks = repo.searchTracks("Lemon")
        assertEquals(1, localTracks.totalCount)
        assertEquals("Lemon", localTracks.items.first().title)
        assertEquals("custom_catalog", localTracks.items.first().provider)
    }

    @Test
    fun `itunes adapter parses normalized models and exposes explicit capabilities`() = runTest {
        val fakeItunesClient = object : ItunesCatalogProvider {
            override suspend fun fetchSearchArtistsJson(query: String, limit: Int, country: String): String =
                """{"resultCount":1,"results":[{"wrapperType":"artist","artistId":446184566,"artistName":"Aimer","primaryGenreName":"J-Pop"}]}"""

            override suspend fun fetchSearchAlbumsJson(query: String, limit: Int, country: String): String =
                """{"resultCount":1,"results":[{"wrapperType":"collection","collectionId":1536243222,"artistId":446184566,"artistName":"Aimer","collectionName":"Sun Dance","releaseDate":"2019-04-10T07:00:00Z","trackCount":10}]}"""

            override suspend fun fetchSearchTracksJson(query: String, limit: Int, country: String): String =
                """{"resultCount":1,"results":[{"wrapperType":"track","kind":"song","trackId":1536243223,"artistId":446184566,"artistName":"Aimer","collectionId":1536243222,"collectionName":"Sun Dance","trackName":"Stand By You","trackTimeMillis":241000,"trackNumber":1,"discNumber":1}]}"""

            override suspend fun fetchArtistAlbumsJson(providerArtistId: String, limit: Int, country: String): String =
                fetchSearchAlbumsJson("", limit, country)

            override suspend fun fetchAlbumTracklistJson(providerAlbumId: String, country: String): String =
                """{"resultCount":2,"results":[{"wrapperType":"collection","collectionId":1536243222,"artistId":446184566,"artistName":"Aimer","collectionName":"Sun Dance","trackCount":1},{"wrapperType":"track","kind":"song","trackId":1536243223,"artistId":446184566,"artistName":"Aimer","collectionId":1536243222,"collectionName":"Sun Dance","trackName":"Stand By You","trackTimeMillis":241000,"trackNumber":1}]}"""
        }

        val adapter = ItunesCatalogAdapter(fakeItunesClient)
        assertEquals(CatalogProviders.ITUNES, adapter.providerId)
        assertTrue(adapter.capabilities.supportsArtistSearch)
        assertTrue(adapter.capabilities.supportsAlbumSearch)
        assertTrue(adapter.capabilities.supportsTrackSearch)
        assertTrue(adapter.capabilities.supportsAlbumDetail)
        assertTrue(adapter.capabilities.supportsAlbumTracklist)
        assertFalse(adapter.capabilities.supportsArtistDetail)
        assertFalse(adapter.capabilities.supportsIsrc)

        val artistResult = adapter.searchArtists("Aimer")
        assertTrue(artistResult is CatalogProviderResult.Success)
        val artists = (artistResult as CatalogProviderResult.Success).data
        assertEquals(1, artists.size)
        assertEquals("Aimer", artists.first().name)
        assertNotNull(artistResult.rawPayloadForCache)

        val albumDetail = adapter.getAlbumDetail("1536243222")
        assertTrue(albumDetail is CatalogProviderResult.Success)
        assertEquals("Sun Dance", (albumDetail as CatalogProviderResult.Success).data?.title)

        // Unsupported operation returns explicit Unsupported instead of throwing
        val artistDetail = adapter.getArtistDetail("446184566")
        assertTrue(artistDetail is CatalogProviderResult.Unsupported)
        assertEquals(CatalogProvider.OP_ARTIST_DETAIL, (artistDetail as CatalogProviderResult.Unsupported).operation)
    }

    @Test
    fun `repository handles unsupported provider capabilities gracefully without throwing`() = runTest {
        val limitedProvider = object : CatalogProvider {
            override val descriptor = ProviderDescriptor(
                id = "artist_only_provider",
                displayName = "Artist Only",
                capabilities = ProviderCapabilities(
                    supportsArtistSearch = true,
                    supportsAlbumSearch = false,
                    supportsTrackSearch = false,
                    supportsArtistAlbums = false,
                    supportsAlbumTracklist = false
                )
            )

            override suspend fun searchArtists(
                query: String,
                limit: Int,
                country: String
            ): CatalogProviderResult<List<RemoteArtistDto>> {
                return CatalogProviderResult.Success(
                    listOf(
                        RemoteArtistDto(
                            providerArtistId = "only-1",
                            name = "Ado",
                            provider = providerId
                        )
                    )
                )
            }
        }

        val repo = LocalCatalogRepository(database = db, catalogProvider = limitedProvider)

        val artistSync = repo.syncSearchArtists("Ado")
        assertEquals(CatalogSyncStatus.SUCCESS, artistSync.status)

        val trackSync = repo.syncSearchTracks("Usseewa")
        assertEquals(CatalogSyncStatus.FAILED, trackSync.status)
        assertTrue(trackSync.errorMessage?.contains("does not support") == true)

        val albumSync = repo.syncSearchAlbums("Kyougen")
        assertEquals(CatalogSyncStatus.FAILED, albumSync.status)
        assertTrue(albumSync.errorMessage?.contains("does not support") == true)

        val localArtistId = repo.searchArtists("Ado").items.first().id
        val discographySync = repo.syncArtistDiscography(localArtistId)
        assertEquals(CatalogSyncStatus.FAILED, discographySync.status)
        assertTrue(discographySync.errorMessage?.contains("does not support") == true)
    }

    private class FakeNormalizedCatalogProvider(
        providerId: String,
        private val artists: List<RemoteArtistDto> = emptyList(),
        private val albums: List<RemoteAlbumDto> = emptyList(),
        private val tracks: List<RemoteTrackDto> = emptyList()
    ) : CatalogProvider {
        override val descriptor = ProviderDescriptor(
            id = providerId,
            displayName = "Fake Provider",
            capabilities = ProviderCapabilities(
                supportsArtistSearch = true,
                supportsAlbumSearch = true,
                supportsTrackSearch = true,
                supportsArtistAlbums = false,
                supportsAlbumTracklist = false,
                maxSearchLimit = 50
            )
        )

        override suspend fun searchArtists(
            query: String,
            limit: Int,
            country: String
        ): CatalogProviderResult<List<RemoteArtistDto>> =
            CatalogProviderResult.Success(artists)

        override suspend fun searchAlbums(
            query: String,
            limit: Int,
            country: String
        ): CatalogProviderResult<List<RemoteAlbumDto>> =
            CatalogProviderResult.Success(albums)

        override suspend fun searchTracks(
            query: String,
            limit: Int,
            country: String
        ): CatalogProviderResult<List<RemoteTrackDto>> =
            CatalogProviderResult.Success(tracks)
    }
}
