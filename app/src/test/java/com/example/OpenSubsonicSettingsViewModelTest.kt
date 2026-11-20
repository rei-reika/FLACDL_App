package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.catalog.db.LocalCatalogDatabase
import com.example.catalog.matching.TrackMatchCandidate
import com.example.catalog.matching.TrackMatchEvidence
import com.example.catalog.matching.TrackMatchMethod
import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto
import com.example.catalog.remote.ItunesCatalogProvider
import com.example.catalog.repository.LocalCatalogRepository
import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadResolutionResult
import com.example.catalog.resolver.DownloadSourceCandidate
import com.example.catalog.resolver.DownloadSourceCapabilities
import com.example.catalog.resolver.DownloadSourceLookupException
import com.example.catalog.resolver.DownloadSourceResolver
import com.example.catalog.resolver.ResolvedSourceIdentity
import com.example.catalog.resolver.ResolverErrorType
import com.example.catalog.resolver.opensubsonic.InMemoryOpenSubsonicConfigStore
import com.example.catalog.resolver.opensubsonic.OpenSubsonicAuthType
import com.example.catalog.resolver.opensubsonic.OpenSubsonicClient
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConfigRepository
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConnectionValidator
import com.example.catalog.resolver.opensubsonic.OpenSubsonicPingResponse
import com.example.catalog.resolver.opensubsonic.OpenSubsonicSongDto
import com.example.ui.catalog.CatalogViewModel
import com.example.ui.catalog.TrackResolutionStatusUi
import com.example.ui.settings.OpenSubsonicSettingsViewModel
import com.example.ui.settings.ServerConnectionStatusUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OpenSubsonicSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `add server validates HTTPS and persists encrypted profile`() = runTest(testDispatcher) {
        val repo = OpenSubsonicConfigRepository(InMemoryOpenSubsonicConfigStore())
        val validator = OpenSubsonicConnectionValidator(clientFactory = { FakePingClient() })
        val vm = OpenSubsonicSettingsViewModel(repo, validator, ioDispatcher = testDispatcher)

        vm.openAddServerForm()
        vm.onFormNameChanged("Home Navidrome")
        vm.onFormUrlChanged("http://public.example.com")
        vm.onFormUsernameChanged("alice")
        vm.onFormCredentialChanged("secret-123")
        vm.saveForm()
        advanceUntilIdle()

        // External plaintext HTTP rejected
        assertTrue(vm.uiState.value.form.isOpen)
        assertTrue(vm.uiState.value.form.validationErrors.any { it.contains("HTTPS", ignoreCase = true) })
        assertTrue(repo.getAllServers().isEmpty())

        // Fix URL to HTTPS -> saves cleanly
        vm.onFormUrlChanged("https://music.example.com")
        vm.saveForm()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.form.isOpen)
        assertEquals(1, vm.uiState.value.servers.size)
        assertEquals("Home Navidrome", vm.uiState.value.servers.first().name)
    }

    @Test
    fun `edit server never exposes stored credential and preserves it when left blank`() = runTest(testDispatcher) {
        val repo = OpenSubsonicConfigRepository(InMemoryOpenSubsonicConfigStore())
        val validator = OpenSubsonicConnectionValidator(clientFactory = { FakePingClient() })
        val vm = OpenSubsonicSettingsViewModel(repo, validator, ioDispatcher = testDispatcher)

        vm.openAddServerForm()
        vm.onFormNameChanged("Navidrome")
        vm.onFormUrlChanged("https://music.example.com")
        vm.onFormAuthTypeChanged(OpenSubsonicAuthType.API_KEY)
        vm.onFormCredentialChanged("original-api-key")
        vm.saveForm()
        advanceUntilIdle()

        val savedId = repo.getAllServers().first().id

        // Open edit form -> credentialInput must be blank
        vm.openEditServerForm(savedId)
        assertEquals("", vm.uiState.value.form.credentialInput)
        assertTrue(vm.uiState.value.form.hasExistingCredential)

        // Rename without touching credential -> preserves original-api-key
        vm.onFormNameChanged("Navidrome Renamed")
        vm.saveForm()
        advanceUntilIdle()

        assertEquals("Navidrome Renamed", repo.getServer(savedId)?.name)
        assertEquals("original-api-key", repo.getServer(savedId)?.credential)

        // Explicitly enter new credential -> replaces it
        vm.openEditServerForm(savedId)
        vm.onFormCredentialChanged("rotated-api-key")
        vm.saveForm()
        advanceUntilIdle()

        assertEquals("rotated-api-key", repo.getServer(savedId)?.credential)
    }

    @Test
    fun `toggle enabled and delete confirmation flow update state cleanly`() = runTest(testDispatcher) {
        val repo = OpenSubsonicConfigRepository(InMemoryOpenSubsonicConfigStore())
        val validator = OpenSubsonicConnectionValidator(clientFactory = { FakePingClient() })
        val vm = OpenSubsonicSettingsViewModel(repo, validator, ioDispatcher = testDispatcher)

        vm.openAddServerForm()
        vm.onFormNameChanged("Server A")
        vm.onFormUrlChanged("https://a.example.com")
        vm.onFormAuthTypeChanged(OpenSubsonicAuthType.API_KEY)
        vm.onFormCredentialChanged("key-a")
        vm.saveForm()
        advanceUntilIdle()

        val id = repo.getAllServers().first().id
        vm.toggleServerEnabled(id, false)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.servers.first().enabled)

        vm.requestDeleteServer(id)
        assertNotNull(vm.uiState.value.pendingDeleteServer)
        vm.cancelDeleteServer()
        assertNull(vm.uiState.value.pendingDeleteServer)
        assertEquals(1, repo.getAllServers().size)

        vm.requestDeleteServer(id)
        vm.confirmDeleteServer()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.servers.isEmpty())
        assertTrue(repo.getAllServers().isEmpty())
    }

    @Test
    fun `test connection reports structured Connected and AuthFailed without exposing secrets`() = runTest(testDispatcher) {
        val repo = OpenSubsonicConfigRepository(InMemoryOpenSubsonicConfigStore())
        var failAuth = false
        val validator = OpenSubsonicConnectionValidator(
            clientFactory = {
                if (failAuth) {
                    FakePingClient(error = DownloadSourceLookupException(ResolverErrorType.AUTHENTICATION, "Wrong password"))
                } else {
                    FakePingClient(
                        response = OpenSubsonicPingResponse(
                            serverVersion = "0.55.0",
                            serverType = "navidrome",
                            openSubsonic = true
                        )
                    )
                }
            },
            clockMs = { 1_000L }
        )
        val vm = OpenSubsonicSettingsViewModel(repo, validator, ioDispatcher = testDispatcher)

        vm.openAddServerForm()
        vm.onFormNameChanged("Server B")
        vm.onFormUrlChanged("https://b.example.com")
        vm.onFormUsernameChanged("bob")
        vm.onFormCredentialChanged("super-secret-bob")
        vm.testFormConnection()
        advanceUntilIdle()

        val okStatus = vm.uiState.value.form.testConnectionStatus
        assertTrue(okStatus is ServerConnectionStatusUi.Connected)
        assertTrue((okStatus as ServerConnectionStatusUi.Connected).summary.contains("navidrome"))

        vm.saveForm()
        advanceUntilIdle()
        val savedId = repo.getAllServers().first().id

        failAuth = true
        vm.testServerConnection(savedId)
        advanceUntilIdle()

        val failedStatus = vm.uiState.value.servers.first().connectionStatus
        assertTrue(failedStatus is ServerConnectionStatusUi.Failed)
        assertEquals("Auth Failed", (failedStatus as ServerConnectionStatusUi.Failed).category)
        assertFalse(failedStatus.message.contains("super-secret-bob"))
    }

    @Test
    fun `CatalogViewModel checkAlbumSourceAvailability maps read-only resolver states safely`() = runTest(testDispatcher) {
        val db = LocalCatalogDatabase.createInMemory(ApplicationProvider.getApplicationContext())
        try {
            val noopItunes = object : ItunesCatalogProvider {
                override suspend fun fetchSearchArtistsJson(query: String, limit: Int, country: String) = """{"resultCount":0,"results":[]}"""
                override suspend fun fetchSearchAlbumsJson(query: String, limit: Int, country: String) = """{"resultCount":0,"results":[]}"""
                override suspend fun fetchSearchTracksJson(query: String, limit: Int, country: String) = """{"resultCount":0,"results":[]}"""
                override suspend fun fetchArtistAlbumsJson(providerArtistId: String, limit: Int, country: String) = """{"resultCount":0,"results":[]}"""
                override suspend fun fetchAlbumTracklistJson(providerAlbumId: String, country: String) = """{"resultCount":0,"results":[]}"""
            }
            val catalogRepo = LocalCatalogRepository(database = db, itunesProvider = noopItunes)
            val artistId = catalogRepo.upsertArtist(RemoteArtistDto(providerArtistId = "art-1", name = "Aimer"))
            val albumId = catalogRepo.upsertAlbum(
                RemoteAlbumDto(
                    providerAlbumId = "alb-1",
                    providerArtistId = "art-1",
                    artistName = "Aimer",
                    title = "Daydream",
                    releaseDate = "2016-09-21"
                ),
                localArtistId = artistId
            )
            val t1 = catalogRepo.upsertTrack(
                RemoteTrackDto(
                    providerTrackId = "tr-1",
                    providerArtistId = "art-1",
                    artistName = "Aimer",
                    providerAlbumId = "alb-1",
                    albumTitle = "Daydream",
                    title = "Kataomoi",
                    durationMs = 207_000L,
                    trackNumber = 1
                ),
                localArtistId = artistId,
                localAlbumId = albumId
            )
            val t2 = catalogRepo.upsertTrack(
                RemoteTrackDto(
                    providerTrackId = "tr-2",
                    providerArtistId = "art-1",
                    artistName = "Aimer",
                    providerAlbumId = "alb-1",
                    albumTitle = "Daydream",
                    title = "Missing Track",
                    durationMs = 200_000L,
                    trackNumber = 2
                ),
                localArtistId = artistId,
                localAlbumId = albumId
            )

            val fakeResolver = object : DownloadSourceResolver {
                override val capabilities = DownloadSourceCapabilities(
                    providerId = "opensubsonic",
                    displayName = "Test OpenSubsonic",
                    supportsResolution = true
                )

                override suspend fun resolve(track: CanonicalTrackIdentity): DownloadResolutionResult {
                    return if (track.title == "Kataomoi") {
                        val cand = DownloadSourceCandidate(
                            providerId = "opensubsonic",
                            sourceTrackId = "s-1",
                            sourceReference = "opensubsonic://opensubsonic/song/s-1",
                            metadata = TrackMatchCandidate("s-1", "opensubsonic", "Kataomoi", "Aimer", durationMs = 207_000L),
                            qualityHints = setOf("FLAC", "LOSSLESS")
                        )
                        DownloadResolutionResult.Resolved(
                            identity = ResolvedSourceIdentity("opensubsonic", "s-1", cand.sourceReference, 1L, 10_000L),
                            candidate = cand,
                            evidence = TrackMatchEvidence(TrackMatchMethod.TITLE_ARTIST_DURATION, 0.95, 1.0, 1.0, durationDeltaMs = 0L)
                        )
                    } else {
                        DownloadResolutionResult.Unavailable("opensubsonic", "Not in library")
                    }
                }
            }

            val catalogVm = CatalogViewModel(
                repository = catalogRepo,
                ioDispatcher = testDispatcher,
                debounceMs = 0L,
                downloadSourceResolver = fakeResolver
            )

            catalogVm.loadAlbumDetail(albumId)
            advanceUntilIdle()
            assertTrue(catalogVm.albumDetailUiState.value.hasConfiguredSource)
            assertTrue(catalogVm.albumDetailUiState.value.trackResolutions.isEmpty())

            catalogVm.checkAlbumSourceAvailability()
            advanceUntilIdle()

            val state = catalogVm.albumDetailUiState.value
            assertFalse(state.isResolvingSources)
            assertTrue(state.trackResolutions[t1] is TrackResolutionStatusUi.Available)
            assertEquals("FLAC", (state.trackResolutions[t1] as TrackResolutionStatusUi.Available).qualityHint)
            assertTrue(state.trackResolutions[t2] is TrackResolutionStatusUi.Unavailable)
            assertTrue(state.resolutionSummary.orEmpty().contains("1 tersedia"))
        } finally {
            db.close()
        }
    }

    private class FakePingClient(
        private val response: OpenSubsonicPingResponse = OpenSubsonicPingResponse(),
        private val error: Exception? = null
    ) : OpenSubsonicClient {
        override suspend fun searchSongs(query: String, songCount: Int): List<OpenSubsonicSongDto> = emptyList()
        override suspend fun ping(): OpenSubsonicPingResponse {
            error?.let { throw it }
            return response
        }
    }
}
