package com.example

import com.example.catalog.matching.TrackMatchMethod
import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadResolutionResult
import com.example.catalog.resolver.DownloadSourceLookupException
import com.example.catalog.resolver.ResolverErrorType
import com.example.catalog.resolver.opensubsonic.AndroidKeystoreOpenSubsonicConfigStore
import com.example.catalog.resolver.opensubsonic.ConfiguredOpenSubsonicResolver
import com.example.catalog.resolver.opensubsonic.InMemoryOpenSubsonicConfigStore
import com.example.catalog.resolver.opensubsonic.OkHttpOpenSubsonicClient
import com.example.catalog.resolver.opensubsonic.OpenSubsonicAuth
import com.example.catalog.resolver.opensubsonic.OpenSubsonicAuthType
import com.example.catalog.resolver.opensubsonic.OpenSubsonicClient
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConfigRepository
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConfigValidationResult
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConfigValidator
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConnectionResult
import com.example.catalog.resolver.opensubsonic.OpenSubsonicConnectionValidator
import com.example.catalog.resolver.opensubsonic.OpenSubsonicPingResponse
import com.example.catalog.resolver.opensubsonic.OpenSubsonicServerConfig
import com.example.catalog.resolver.opensubsonic.OpenSubsonicServerProfile
import com.example.catalog.resolver.opensubsonic.OpenSubsonicSongDto
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OpenSubsonicConfigAndWiringTest {

    private val sampleTrack = CanonicalTrackIdentity(
        localTrackId = 10L,
        catalogProviderId = "apple_itunes",
        catalogTrackId = "apple-1001",
        title = "Kataomoi",
        artist = "Aimer",
        album = "Daydream",
        durationMs = 207_000L,
        isrc = "JPXS01600012"
    )

    // =========================================================================
    // 1. Server Profile Validation & Credential Hygiene
    // =========================================================================

    @Test
    fun `config validator enforces HTTPS for external hosts and redacts credentials`() {
        val externalHttp = OpenSubsonicServerProfile(
            id = "srv-1",
            name = "Public Server",
            serverUrl = "http://music.public.example",
            username = "alice",
            credential = "super-secret-password"
        )
        val invalid = OpenSubsonicConfigValidator.validate(externalHttp)
        assertTrue(invalid is OpenSubsonicConfigValidationResult.Invalid)
        assertTrue((invalid as OpenSubsonicConfigValidationResult.Invalid).errors.any { it.contains("HTTPS", ignoreCase = true) })
        assertFalse(externalHttp.toString().contains("super-secret-password"))

        val localLan = externalHttp.copy(serverUrl = "http://192.168.1.25:4533/")
        val validLan = OpenSubsonicConfigValidator.validate(localLan)
        assertTrue(validLan is OpenSubsonicConfigValidationResult.Valid)
        assertEquals("http://192.168.1.25:4533", (validLan as OpenSubsonicConfigValidationResult.Valid).normalizedProfile.serverUrl)

        // Token derivation produces salted MD5 instead of plaintext password
        val serverConfig = validLan.normalizedProfile.toServerConfig()
        assertTrue(serverConfig.auth is OpenSubsonicAuth.TokenAuth)
        val tokenAuth = serverConfig.auth as OpenSubsonicAuth.TokenAuth
        assertNotEquals("super-secret-password", tokenAuth.token)
        assertTrue(tokenAuth.salt.isNotBlank())
        assertEquals(OpenSubsonicServerProfile.md5Hex("super-secret-password" + tokenAuth.salt), tokenAuth.token)
    }

    @Test
    fun `config repository supports save update toggle remove and multiple servers`() {
        val store = InMemoryOpenSubsonicConfigStore()
        val repo = OpenSubsonicConfigRepository(store)

        val srv1 = OpenSubsonicServerProfile(
            id = "srv-1",
            name = "Home Navidrome",
            serverUrl = "https://navidrome.example.com",
            username = "user1",
            credential = "pw1",
            enabled = true
        )
        val srv2 = OpenSubsonicServerProfile(
            id = "srv-2",
            name = "Backup Library",
            serverUrl = "https://backup.example.com",
            credential = "api-key-secret",
            authType = OpenSubsonicAuthType.API_KEY,
            enabled = false
        )

        assertTrue(repo.saveServer(srv1) is OpenSubsonicConfigValidationResult.Valid)
        assertTrue(repo.saveServer(srv2) is OpenSubsonicConfigValidationResult.Valid)
        assertEquals(2, repo.getAllServers().size)
        assertEquals(listOf("srv-1"), repo.getEnabledServers().map { it.id })

        assertTrue(repo.setServerEnabled("srv-2", true))
        assertEquals(listOf("srv-1", "srv-2"), repo.getEnabledServers().map { it.id })

        val updated = repo.updateServer(srv1.copy(name = "Home Navidrome Primary"))
        assertTrue(updated is OpenSubsonicConfigValidationResult.Valid)
        assertEquals("Home Navidrome Primary", repo.getServer("srv-1")?.name)

        assertTrue(repo.removeServer("srv-1"))
        assertNull(repo.getServer("srv-1"))
        assertEquals(1, repo.servers.value.size)

        // Verify JSON round-trip used by AndroidKeystoreOpenSubsonicConfigStore
        val serialized = AndroidKeystoreOpenSubsonicConfigStore.serializeProfiles(listOf(srv1, srv2))
        val deserialized = AndroidKeystoreOpenSubsonicConfigStore.deserializeProfiles(serialized)
        assertEquals(2, deserialized.size)
        assertEquals("srv-2", deserialized[1].id)
        assertEquals(OpenSubsonicAuthType.API_KEY, deserialized[1].authType)
    }

    // =========================================================================
    // 2. Connection Validation (`rest/ping.view`)
    // =========================================================================

    @Test
    fun `connection validator returns structured Connected AuthFailed Timeout and InvalidConfig`() = runTest {
        val validProfile = OpenSubsonicServerProfile(
            id = "srv-ping",
            name = "Navidrome",
            serverUrl = "https://music.example.com",
            username = "alice",
            credential = "secret-pass"
        )

        // Connected
        val okValidator = OpenSubsonicConnectionValidator(
            clientFactory = {
                FakeClient(
                    pingResponse = OpenSubsonicPingResponse(
                        serverVersion = "0.55.0",
                        serverType = "navidrome",
                        openSubsonic = true
                    )
                )
            }
        )
        val ok = okValidator.validateConnection(validProfile)
        assertTrue(ok is OpenSubsonicConnectionResult.Connected)
        assertEquals("navidrome", (ok as OpenSubsonicConnectionResult.Connected).serverType)
        assertTrue(ok.openSubsonic)

        // Authentication failed
        val authValidator = OpenSubsonicConnectionValidator(
            clientFactory = {
                FakeClient(pingError = DownloadSourceLookupException(ResolverErrorType.AUTHENTICATION, "Wrong password"))
            }
        )
        assertTrue(authValidator.validateConnection(validProfile) is OpenSubsonicConnectionResult.AuthenticationFailed)

        // Timeout
        val timeoutValidator = OpenSubsonicConnectionValidator(
            clientFactory = {
                FakeClient(pingError = DownloadSourceLookupException(ResolverErrorType.TIMEOUT, "Timed out"))
            }
        )
        assertTrue(timeoutValidator.validateConnection(validProfile) is OpenSubsonicConnectionResult.Timeout)

        // Invalid config (external plaintext HTTP rejected before network call)
        val invalidProfile = validProfile.copy(serverUrl = "http://music.public.example")
        val invalidRes = okValidator.validateConnection(invalidProfile)
        assertTrue(invalidRes is OpenSubsonicConnectionResult.InvalidConfig)
    }

    @Test
    fun `OkHttpOpenSubsonicClient ping parses response and blocks redirects`() = runTest {
        val pingJson = """
            {
              "subsonic-response": {
                "status": "ok",
                "version": "1.16.1",
                "type": "navidrome",
                "serverVersion": "0.55.0",
                "openSubsonic": true
              }
            }
        """.trimIndent()
        val parsed = OkHttpOpenSubsonicClient.parsePingResponse(pingJson)
        assertEquals("0.55.0", parsed.serverVersion)
        assertEquals("navidrome", parsed.serverType)
        assertTrue(parsed.openSubsonic)

        val redirectHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(302)
                    .message("Found")
                    .header("Location", "https://attacker.example/leak")
                    .body("".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val profile = OpenSubsonicServerProfile(
            id = "srv-redir",
            name = "Redirecting",
            serverUrl = "https://music.example.com",
            credential = "my-secret-api-key",
            authType = OpenSubsonicAuthType.API_KEY
        )
        val validator = OpenSubsonicConnectionValidator(
            clientFactory = { cfg -> OkHttpOpenSubsonicClient(cfg, redirectHttp) }
        )
        val res = validator.validateConnection(profile)
        assertTrue(res is OpenSubsonicConnectionResult.InvalidConfig)
        val msg = (res as OpenSubsonicConnectionResult.InvalidConfig).errors.joinToString(" ")
        assertTrue(msg.contains("redirect", ignoreCase = true))
        assertFalse(msg.contains("my-secret-api-key"))
    }

    // =========================================================================
    // 3. Application-Level Resolver Wiring (`ConfiguredOpenSubsonicResolver`)
    // =========================================================================

    @Test
    fun `configured resolver returns Unavailable when no enabled server exists`() = runTest {
        val repo = OpenSubsonicConfigRepository(InMemoryOpenSubsonicConfigStore())
        val resolver = ConfiguredOpenSubsonicResolver(
            configRepository = repo,
            clientFactory = { FakeClient() }
        )

        assertFalse(resolver.capabilities.supportsResolution)
        val res = resolver.resolve(sampleTrack)
        assertTrue(res is DownloadResolutionResult.Unavailable)
    }

    @Test
    fun `configured resolver reuses cached resolver instance and rebuilds on config update`() = runTest {
        val repo = OpenSubsonicConfigRepository(InMemoryOpenSubsonicConfigStore())
        val profile = OpenSubsonicServerProfile(
            id = "srv-main",
            name = "Main Library",
            serverUrl = "https://music.example.com",
            username = "alice",
            credential = "secret-pass",
            enabled = true
        )
        repo.saveServer(profile)

        var factoryCalls = 0
        val createdClients = mutableListOf<FakeClient>()
        val resolver = ConfiguredOpenSubsonicResolver(
            configRepository = repo,
            clientFactory = {
                factoryCalls++
                FakeClient(
                    songs = listOf(
                        OpenSubsonicSongDto(
                            id = "song-kataomoi",
                            title = "Kataomoi",
                            artist = "Aimer",
                            album = "Daydream",
                            durationSeconds = 207,
                            isrcs = listOf("JPXS01600012"),
                            suffix = "flac"
                        )
                    )
                ).also { createdClients.add(it) }
            }
        )

        val first = resolver.resolve(sampleTrack)
        val second = resolver.resolve(sampleTrack)
        assertTrue(first is DownloadResolutionResult.Resolved)
        assertEquals(TrackMatchMethod.ISRC_EXACT, (first as DownloadResolutionResult.Resolved).evidence.matchMethod)
        assertEquals(first, second)
        // Reused single client & single CachingDownloadSourceResolver instance
        assertEquals(1, factoryCalls)
        assertEquals(1, createdClients.first().searchCalls)

        // Updating server profile rebuilds resolver instance cleanly
        repo.updateServer(profile.copy(name = "Main Library Renamed"))
        resolver.resolve(sampleTrack)
        assertEquals(2, factoryCalls)
    }

    @Test
    fun `configured resolver preserves Ambiguous outcome and falls back across multiple enabled servers`() = runTest {
        val repo = OpenSubsonicConfigRepository(InMemoryOpenSubsonicConfigStore())
        repo.saveServer(
            OpenSubsonicServerProfile(
                id = "srv-empty",
                name = "Empty Server",
                serverUrl = "https://empty.example.com",
                credential = "key-1",
                authType = OpenSubsonicAuthType.API_KEY
            )
        )
        repo.saveServer(
            OpenSubsonicServerProfile(
                id = "srv-ambig",
                name = "Ambiguous Server",
                serverUrl = "https://ambig.example.com",
                credential = "key-2",
                authType = OpenSubsonicAuthType.API_KEY
            )
        )

        val resolver = ConfiguredOpenSubsonicResolver(
            configRepository = repo,
            clientFactory = { cfg ->
                if (cfg.providerId.endsWith(":srv-empty")) {
                    FakeClient(songs = emptyList())
                } else {
                    FakeClient(
                        songs = listOf(
                            OpenSubsonicSongDto(id = "a1", title = "Kataomoi", artist = "Aimer", durationSeconds = 207),
                            OpenSubsonicSongDto(id = "a2", title = "Kataomoi", artist = "Aimer", durationSeconds = 207)
                        )
                    )
                }
            }
        )

        val res = resolver.resolve(sampleTrack.copy(isrc = null, album = null))
        assertTrue(res is DownloadResolutionResult.Ambiguous)
        assertEquals(2, (res as DownloadResolutionResult.Ambiguous).candidates.size)
    }

    private class FakeClient(
        private val songs: List<OpenSubsonicSongDto> = emptyList(),
        private val pingResponse: OpenSubsonicPingResponse = OpenSubsonicPingResponse(),
        private val pingError: Exception? = null
    ) : OpenSubsonicClient {
        var searchCalls: Int = 0

        override suspend fun searchSongs(query: String, songCount: Int): List<OpenSubsonicSongDto> {
            searchCalls++
            return songs
        }

        override suspend fun ping(): OpenSubsonicPingResponse {
            pingError?.let { throw it }
            return pingResponse
        }
    }
}
