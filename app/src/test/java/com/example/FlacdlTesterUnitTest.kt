package com.example

import com.example.core.config.SessionConfigRepository
import com.example.core.config.TestMode
import com.example.core.config.TesterSessionConfig
import com.example.core.security.InMemoryDeviceCredentialStore
import com.example.core.security.LogSanitizer
import com.example.core.security.UrlValidationResult
import com.example.core.security.UrlValidator
import com.example.data.model.ApiCallResult
import com.example.data.model.AuthMeProfile
import com.example.data.model.AuthSessionStatus
import com.example.data.model.ConnectionStatus
import com.example.data.model.EndpointDiagnosticsSummary
import com.example.data.model.NetworkLatencySample
import com.example.data.model.PairDeviceResponse
import com.example.data.model.WorkerJobStatus
import com.example.data.remote.ApiConfig
import com.example.data.remote.ApiConfigValidation
import com.example.data.remote.DemoFlacdlApiClient
import com.example.data.remote.FlacdlApiClient
import com.example.data.remote.NetworkErrorHandler
import com.example.data.remote.OkHttpFlacdlApiClient
import com.example.data.remote.PocJsonParser
import com.example.data.repository.TesterRepository
import com.example.validation.FlacFileValidator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class FlacdlTesterUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `session config has expected default endpoints and stays in demo mode`() {
        val repo = SessionConfigRepository()
        val cfg = repo.config.value
        assertEquals(TestMode.DEMO, cfg.mode)
        assertEquals("/poc/v1/health", cfg.healthEndpointPath)
        assertEquals("/poc/v1/search", cfg.searchEndpointPath)
        assertEquals("/poc/v1/handoff", cfg.handoffEndpointPath)
        assertEquals("/poc/v1/report", cfg.diagnosticReportPath)

        repo.updateHealthEndpointPath("custom/health")
        repo.updateSearchEndpointPath("custom/search")
        repo.updateHandoffEndpointPath("custom/handoff")
        repo.updateDiagnosticReportSettings("custom/report", true)

        val updated = repo.config.value
        assertEquals("/custom/health", updated.healthEndpointPath)
        assertEquals("/custom/search", updated.searchEndpointPath)
        assertEquals("/custom/handoff", updated.handoffEndpointPath)
        assertEquals("/custom/report", updated.diagnosticReportPath)
    }

    @Test
    fun `url validator rejects blank localhost and cleartext http`() {
        assertTrue(UrlValidator.validateVpsBaseUrl("") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateVpsBaseUrl("https://localhost:8080") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateVpsBaseUrl("https://127.0.0.1") is UrlValidationResult.Invalid)
        assertTrue(UrlValidator.validateVpsBaseUrl("http://api.example.com") is UrlValidationResult.Invalid)
        val valid = UrlValidator.validateVpsBaseUrl("https://api.example.com/")
        assertTrue(valid is UrlValidationResult.Valid)
        assertEquals("https://api.example.com", (valid as UrlValidationResult.Valid).normalizedBaseUrl)
    }

    @Test
    fun `api config enforces https base url and bearer token in live mode`() {
        val noUrl = TesterSessionConfig(
            mode = TestMode.LIVE,
            vpsBaseUrl = "",
            bearerToken = "session-token-123"
        )
        val resNoUrl = ApiConfig.validateForLiveMode(noUrl)
        assertTrue(resNoUrl is ApiConfigValidation.Invalid)
        assertEquals("invalid_base_url", (resNoUrl as ApiConfigValidation.Invalid).errorCode)

        val noToken = TesterSessionConfig(
            mode = TestMode.LIVE,
            vpsBaseUrl = "https://poc.flacdl.example/",
            bearerToken = "   "
        )
        val resNoToken = ApiConfig.validateForLiveMode(noToken)
        assertTrue(resNoToken is ApiConfigValidation.Invalid)
        assertEquals("missing_bearer_token", (resNoToken as ApiConfigValidation.Invalid).errorCode)

        val validSession = TesterSessionConfig(
            mode = TestMode.LIVE,
            vpsBaseUrl = "https://poc.flacdl.example/",
            bearerToken = "valid-session-token-xyz"
        )
        val resValid = ApiConfig.validateForLiveMode(validSession)
        assertTrue(resValid is ApiConfigValidation.Valid)
        val apiCfg = (resValid as ApiConfigValidation.Valid).config
        assertEquals("https://poc.flacdl.example", apiCfg.baseUrl)
        assertEquals("valid-session-token-xyz", apiCfg.bearerToken)
        assertEquals("https://poc.flacdl.example/poc/v1/health", apiCfg.buildEndpointUrl(apiCfg.healthEndpointPath))
        assertEquals("https://poc.flacdl.example/poc/v1/search", apiCfg.buildEndpointUrl(apiCfg.searchEndpointPath))
    }

    @Test
    fun `okhttp client builds get without content type and post with application json and bearer auth`() {
        val client = OkHttpFlacdlApiClient()
        val getReq = client.buildVpsGetRequest("https://poc.flacdl.example/poc/v1/health", "my-session-token")
        assertEquals("GET", getReq.method)
        assertEquals("Bearer my-session-token", getReq.header("Authorization"))
        assertEquals("application/json", getReq.header("Accept"))
        assertNull(getReq.header("Content-Type"))

        val postReq = client.buildVpsPostRequest(
            "https://poc.flacdl.example/poc/v1/search",
            """{"query":"Fly High"}""",
            "my-session-token"
        )
        assertEquals("POST", postReq.method)
        assertEquals("Bearer my-session-token", postReq.header("Authorization"))
        assertEquals("application/json", postReq.header("Content-Type"))
        assertEquals("application/json", postReq.header("Accept"))
        assertNotNull(postReq.body)
        assertEquals("application", postReq.body?.contentType()?.type)
        assertEquals("json", postReq.body?.contentType()?.subtype)
    }

    @Test
    fun `network error handler parses vps json errors and network exceptions safely`() {
        val err401 = NetworkErrorHandler.fromHttpResponse(
            httpCode = 401,
            httpMessage = "Unauthorized",
            rawBody = """{"status":"error","error":"unauthorized","message":"Missing or invalid Authorization Bearer token."}"""
        )
        assertEquals(401, err401.httpStatusCode)
        assertEquals("unauthorized", err401.errorCode)
        assertTrue(err401.userMessage.contains("HTTP 401"))

        val err429 = NetworkErrorHandler.fromHttpResponse(
            httpCode = 429,
            httpMessage = "Too Many Requests",
            rawBody = """{"status":"error","error":"rate_limit_exceeded","message":"Too many requests."}""",
            retryAfterHeader = "45"
        )
        assertEquals(429, err429.httpStatusCode)
        assertEquals("rate_limit_exceeded", err429.errorCode)
        assertTrue(err429.userMessage.contains("45 detik"))

        val err503 = NetworkErrorHandler.fromHttpResponse(
            httpCode = 503,
            httpMessage = "Service Unavailable",
            rawBody = """{"status":"error","error":"clearance_unavailable","message":"Lucida clearance is not available."}"""
        )
        assertEquals(503, err503.httpStatusCode)
        assertEquals("clearance_unavailable", err503.errorCode)
        assertTrue(err503.userMessage.contains("Clearance"))

        val errTimeout = NetworkErrorHandler.fromException(SocketTimeoutException("Read timed out"))
        assertEquals("timeout", errTimeout.errorCode)

        val errDns = NetworkErrorHandler.fromException(UnknownHostException("Unable to resolve host"))
        assertEquals("unknown_host", errDns.errorCode)

        val errTls = NetworkErrorHandler.fromException(SSLHandshakeException("Chain validation failed"))
        assertEquals("tls_handshake_failed", errTls.errorCode)
    }

    @Test
    fun `health check parser maps actual vps poc contract and keeps worker status independent`() {
        val rawHealthJson = """
            {
              "status": "ok",
              "clearance_available": true,
              "has_cf_cookie": true,
              "lucidadl_version": "1.4.3",
              "server_time": "2026-11-18T17:20:00Z"
            }
        """.trimIndent()

        val parsed = OkHttpFlacdlApiClient.parseHealthResponse(rawHealthJson, latencyMs = 84L)
        assertEquals("ok", parsed.status)
        assertTrue(parsed.clearanceAvailable)
        assertTrue(parsed.hasCfCookie)
        assertEquals("1.4.3", parsed.lucidadlVersion)
        assertEquals("2026-11-18T17:20:00Z", parsed.serverTime)
        assertEquals(84L, parsed.responseTimeMs)
        assertTrue(parsed.vpsReachable)
        // Clearance availability on VPS must NOT be equated with Lucida Worker data-plane reachability
        assertFalse(parsed.workerReachable)
    }

    @Test
    fun `search response parser maps actual vps poc contract and formats duration 166 to 02_46`() {
        val rawSearchJson = """
            {
              "status": "ok",
              "query": "Fly High",
              "count": 10,
              "results": [
                {
                  "title": "Fly High",
                  "artist": "Toby Romeo",
                  "album": "Fly High",
                  "duration": 166,
                  "track_url": "https://music.amazon.com/tracks/B0H2D1MCPS"
                },
                {
                  "title": "Instrumental Dub",
                  "artist": "Toby Romeo",
                  "album": null,
                  "duration": 65,
                  "track_url": "https://music.amazon.com/tracks/B0H2D1MCP2"
                }
              ]
            }
        """.trimIndent()

        val parsed = OkHttpFlacdlApiClient.parseSearchResponse(rawSearchJson, fallbackQuery = "Fly High")
        assertEquals("ok", parsed.status)
        assertEquals("Fly High", parsed.query)
        assertEquals(10, parsed.count)
        assertEquals(2, parsed.results.size)

        val first = parsed.results[0]
        assertEquals("Fly High", first.title)
        assertEquals("Toby Romeo", first.artist)
        assertEquals("Fly High", first.album)
        assertEquals("Fly High", first.albumDisplay)
        assertEquals(166, first.duration)
        assertEquals("02:46", first.formattedDuration)
        assertEquals("https://music.amazon.com/tracks/B0H2D1MCPS", first.trackUrl)

        val second = parsed.results[1]
        assertNull(second.album)
        assertEquals("-", second.albumDisplay)
        assertEquals(65, second.duration)
        assertEquals("01:05", second.formattedDuration)

        assertEquals("02:46", PocJsonParser.formatDurationSeconds(166))
        assertEquals("--:--", PocJsonParser.formatDurationSeconds(null))
    }

    @Test
    fun `log sanitizer redacts tokens cookies and masks full urls`() {
        val raw = "Authorization: Bearer secret-jwt-token-123 cookie=sessionabc"
        val redacted = LogSanitizer.redactSensitive(raw)
        assertFalse(redacted.contains("secret-jwt-token-123"))
        assertFalse(redacted.contains("sessionabc"))

        val maskedUrl = LogSanitizer.maskUrlForDisplay("https://worker.example.com/direct/secret-handoff-id?token=xyz")
        assertEquals("https://worker.example.com/direct/•••", maskedUrl)
        assertFalse(maskedUrl.contains("secret-handoff-id"))
        assertFalse(maskedUrl.contains("xyz"))
    }

    @Test
    fun `demo mode repository flow works end to end and track selection does not auto trigger handoff`() = runTest {
        val configRepo = SessionConfigRepository()
        val repo = TesterRepository(configRepo)

        val health = repo.testApiConnection()
        assertTrue(health.isSuccess)
        assertTrue(health.isSimulated)
        assertEquals(ConnectionStatus.CONNECTED, repo.dashboardMetrics.value.vpsStatus)
        // Worker status remains DISCONNECTED until an actual handoff/worker poll occurs
        assertEquals(ConnectionStatus.DISCONNECTED, repo.dashboardMetrics.value.workerStatus)
        assertNotNull(repo.dashboardMetrics.value.latestHealthInfo)

        val search = repo.searchCatalog("Fly High")
        assertTrue(search.isSuccess)
        assertTrue(search.isSimulated)
        assertEquals(1, search.data?.count)
        val track = search.data!!.results.first()
        assertEquals("Fly High", track.title)
        assertEquals(166, track.duration)
        assertEquals("02:46", track.formattedDuration)

        // Selecting a track must update selectedTrack state and NEVER auto-create a handoff
        repo.selectTrack(track)
        assertEquals(track, repo.selectedTrack.value)
        assertNull(repo.activeHandoff.value)
        assertNull(repo.latestWorkerPoll.value)
        assertEquals(0, repo.dashboardMetrics.value.totalTests)

        // Manual handoff creation still works as expected
        val handoff = repo.createHandoff(track.trackUrl)
        assertTrue(handoff.isSuccess)
        assertEquals(1, repo.dashboardMetrics.value.totalTests)

        val poll1 = repo.pollWorkerStatus()
        assertEquals(WorkerJobStatus.UNKNOWN, poll1.data?.status)

        val poll2 = repo.pollWorkerStatus()
        assertEquals(WorkerJobStatus.METADATA, poll2.data?.status)

        val poll3 = repo.pollWorkerStatus()
        assertEquals(WorkerJobStatus.METADATA, poll3.data?.status)

        val poll4 = repo.pollWorkerStatus()
        assertEquals(WorkerJobStatus.COMPLETED, poll4.data?.status)
    }

    @Test
    fun `live mode blocks requests when base url or bearer token is unconfigured`() = runTest {
        val configRepo = SessionConfigRepository().apply {
            setMode(TestMode.LIVE)
        }
        val repo = TesterRepository(configRepo)
        val healthNoUrl = repo.testApiConnection()
        assertFalse(healthNoUrl.isSuccess)
        assertEquals("invalid_base_url", healthNoUrl.errorCode)
        assertEquals(ConnectionStatus.DISCONNECTED, repo.dashboardMetrics.value.vpsStatus)

        configRepo.updateVpsBaseUrl("https://poc.flacdl.example")
        val healthNoToken = repo.testApiConnection()
        assertFalse(healthNoToken.isSuccess)
        assertEquals("missing_bearer_token", healthNoToken.errorCode)
        assertEquals(ConnectionStatus.DISCONNECTED, repo.dashboardMetrics.value.vpsStatus)
    }

    @Test
    fun `flac validator parses fLaC signature streaminfo picture and sha256`() = runTest {
        val file = tempFolder.newFile("sample.flac")
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))
        // STREAMINFO (last block = 0x80, len = 34)
        out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22))
        val streamInfo = ByteArray(34)
        streamInfo[10] = 0x0A
        streamInfo[11] = 0xC4.toByte()
        streamInfo[12] = 0x42
        streamInfo[13] = 0xF0.toByte()
        streamInfo[17] = 0x10
        out.write(streamInfo)
        file.writeBytes(out.toByteArray())

        val validator = FlacFileValidator()
        val result = validator.validate(file = file, httpStatusCode = 200)
        assertTrue(result.isOverallValid)
        assertTrue(result.hasFlacSignature)
        assertTrue(result.streamInfoSummary.contains("44100 Hz"))
        assertEquals(64, result.sha256Fingerprint.length)
    }

    @Test
    fun `endpoint diagnostics summary computes min avg max and success failure counts`() {
        val samples = listOf(
            NetworkLatencySample(1, "/poc/v1/health", dnsLookupMs = 15, tcpConnectMs = 20, tlsHandshakeMs = 30, totalDurationMs = 120, httpStatusCode = 200, isSuccess = true),
            NetworkLatencySample(2, "/poc/v1/health", totalDurationMs = 40, httpStatusCode = 200, isSuccess = true),
            NetworkLatencySample(3, "/poc/v1/health", totalDurationMs = 50, httpStatusCode = 503, isSuccess = false, errorMessage = "HTTP 503")
        )
        val summary = EndpointDiagnosticsSummary.fromSamples("/poc/v1/health", samples)
        assertEquals("/poc/v1/health", summary.endpointPath)
        assertEquals(2, summary.successCount)
        assertEquals(1, summary.failureCount)
        assertEquals(40L, summary.minLatencyMs)
        assertEquals(70L, summary.avgLatencyMs)
        assertEquals(120L, summary.maxLatencyMs)
    }

    @Test
    fun `okhttp call timing listener measures dns tcp excluding tls and tls handshake monotonically`() {
        var nowMs = 1_000L
        val listener = OkHttpFlacdlApiClient.CallTimingListener(clockNanos = { nowMs * 1_000_000L })
        val dummyCall = OkHttpFlacdlApiClient.defaultOkHttpClient().newCall(
            OkHttpFlacdlApiClient().buildVpsGetRequest("https://poc.flacdl.example/poc/v1/health", "tok")
        )
        val addr = java.net.InetSocketAddress.createUnresolved("poc.flacdl.example", 443)

        // Fresh connection: DNS (12ms), TCP (25ms), TLS (40ms)
        listener.dnsStart(dummyCall, "poc.flacdl.example")
        nowMs += 12L
        listener.dnsEnd(dummyCall, "poc.flacdl.example", emptyList())

        listener.connectStart(dummyCall, addr, java.net.Proxy.NO_PROXY)
        nowMs += 25L
        listener.secureConnectStart(dummyCall)
        nowMs += 40L
        listener.secureConnectEnd(dummyCall, null)
        listener.connectEnd(dummyCall, addr, java.net.Proxy.NO_PROXY, okhttp3.Protocol.HTTP_2)

        assertEquals(12L, listener.dnsLookupMs())
        assertEquals(25L, listener.tcpConnectMs())
        assertEquals(40L, listener.tlsHandshakeMs())

        // Reused pooled connection: no DNS/TCP/TLS callbacks -> all null
        val reusedListener = OkHttpFlacdlApiClient.CallTimingListener(clockNanos = { nowMs * 1_000_000L })
        assertNull(reusedListener.dnsLookupMs())
        assertNull(reusedListener.tcpConnectMs())
        assertNull(reusedListener.tlsHandshakeMs())
    }

    @Test
    fun `demo mode network diagnostics runs 10 sequential requests for health and auth me`() = runTest {
        val configRepo = SessionConfigRepository()
        val repo = TesterRepository(configRepo)
        val report = repo.runNetworkDiagnostics(requestsPerEndpoint = 10)

        assertFalse(report.isRunning)
        assertTrue(report.isSimulated)
        assertEquals(2, report.endpoints.size)
        assertEquals("/poc/v1/health", report.endpoints[0].endpointPath)
        assertEquals("/poc/v1/auth/me", report.endpoints[1].endpointPath)
        assertEquals(10, report.endpoints[0].samples.size)
        assertEquals(10, report.endpoints[1].samples.size)
        assertEquals(10, report.endpoints[0].successCount)
        assertEquals(10, report.endpoints[1].successCount)
        assertNotNull(report.endpoints[0].minLatencyMs)
        assertNotNull(report.endpoints[0].avgLatencyMs)
        assertNotNull(report.endpoints[0].maxLatencyMs)
    }

    @Test
    fun `auth json parsers decode actual phase 2b pair and phase 3a auth me responses`() {
        val pairJson = """
            {
              "status": "ok",
              "device_id": "DEV-AABB11223344",
              "device_name": "Pixel 9 Pro",
              "user_id": 12345678,
              "access_token": "tok_256bit_secret_sample",
              "token_type": "Bearer",
              "paired_at_wib": "2026-11-19 18:00:00 WIB"
            }
        """.trimIndent()
        val parsedPair = OkHttpFlacdlApiClient.parsePairResponse(pairJson)
        assertEquals("DEV-AABB11223344", parsedPair.deviceId)
        assertEquals("Pixel 9 Pro", parsedPair.deviceName)
        assertEquals(12345678L, parsedPair.userId)
        assertEquals("tok_256bit_secret_sample", parsedPair.accessToken)

        val authMeJson = """
            {
              "status": "ok",
              "authenticated": true,
              "auth_type": "android_device",
              "device": {
                "device_id": "DEV-AABB11223344",
                "device_name": "Pixel 9 Pro",
                "paired_at_wib": "2026-11-19 18:00:00 WIB",
                "last_active_at_wib": "2026-11-19 18:05:00 WIB"
              },
              "user": {
                "user_id": 12345678,
                "username": "flacdl_tester",
                "full_name": "FLACDL Tester",
                "is_blocked": false
              },
              "package": {
                "package_id": "donatur",
                "package_name": "Donatur",
                "status": "active",
                "is_unlimited": false,
                "days_remaining": 24
              },
              "quota": {
                "daily_limit": 80,
                "used_today": 5,
                "active_reserved": 0,
                "remaining_quota": 75,
                "reset_countdown_wib": "5j 55m"
              },
              "settings": {
                "audio_format": "flac"
              }
            }
        """.trimIndent()
        val me = OkHttpFlacdlApiClient.parseAuthMeResponse(authMeJson)
        assertEquals("DEV-AABB11223344", me.deviceId)
        assertEquals("flacdl_tester", me.username)
        assertEquals("Donatur", me.packageName)
        assertEquals(24, me.daysRemaining)
        assertEquals(80, me.dailyLimit)
        assertEquals(75, me.remainingQuota)
    }

    @Test
    fun `device pairing restore network error preservation revocation and logout lifecycle`() = runTest {
        val store = InMemoryDeviceCredentialStore()
        var nextMeResult: ApiCallResult<AuthMeProfile> = ApiCallResult(
            isSuccess = true,
            httpStatusCode = 200,
            data = AuthMeProfile(
                deviceId = "DEV-LIVE001",
                deviceName = "Pixel 9",
                pairedAtWib = "2026-11-19 18:00:00 WIB",
                lastActiveAtWib = "2026-11-19 18:01:00 WIB",
                userId = 999L,
                username = "tester",
                fullName = "Tester",
                isBlocked = false,
                packageId = "gratis",
                packageName = "Gratis",
                packageStatus = "active",
                isUnlimited = false,
                daysRemaining = null,
                dailyLimit = 10,
                usedToday = 1,
                activeReserved = 0,
                remainingQuota = 9,
                resetCountdownWib = "6j",
                audioFormat = "flac"
            )
        )
        var logoutCalled = 0

        val fakeLiveClient: FlacdlApiClient = object : DemoFlacdlApiClient() {
            override suspend fun pairDevice(
                baseUrl: String,
                pairingCode: String,
                deviceName: String
            ): ApiCallResult<PairDeviceResponse> {
                if (pairingCode != "654321") {
                    return ApiCallResult(
                        isSuccess = false,
                        httpStatusCode = 401,
                        errorCode = "invalid_pairing_code",
                        errorMessage = "Kode pairing tidak valid atau kedaluwarsa."
                    )
                }
                return ApiCallResult(
                    isSuccess = true,
                    httpStatusCode = 200,
                    data = PairDeviceResponse(
                        deviceId = "DEV-LIVE001",
                        deviceName = deviceName,
                        userId = 999L,
                        accessToken = "secret_live_device_token_256bit"
                    )
                )
            }

            override suspend fun fetchAuthMe(config: TesterSessionConfig): ApiCallResult<AuthMeProfile> {
                return nextMeResult
            }

            override suspend fun logoutDevice(config: TesterSessionConfig): ApiCallResult<Boolean> {
                logoutCalled += 1
                return ApiCallResult(isSuccess = true, httpStatusCode = 200, data = true)
            }
        }

        val repo1 = TesterRepository(
            configRepository = SessionConfigRepository(),
            liveClient = fakeLiveClient,
            credentialStore = store
        )

        // 1. Wrong/expired pairing code rejected -> nothing stored
        val badPair = repo1.pairWithTelegramCode("https://api.auditify.web.id", "000000", "Pixel 9")
        assertFalse(badPair.isSuccess)
        assertNull(store.loadSession())
        assertEquals(AuthSessionStatus.UNAUTHENTICATED, repo1.authSession.value.status)

        // 2. Valid pairing code succeeds -> stored securely & /auth/me authenticates
        val okPair = repo1.pairWithTelegramCode("https://api.auditify.web.id", "654321", "Pixel 9")
        assertTrue(okPair.isSuccess)
        assertNotNull(store.loadSession())
        assertEquals(AuthSessionStatus.AUTHENTICATED, repo1.authSession.value.status)
        assertEquals("tester", repo1.authSession.value.profile?.username)

        // 3. Simulated app restart (new repository instance with same credential store) restores session
        val repoRestarted = TesterRepository(
            configRepository = SessionConfigRepository(),
            liveClient = fakeLiveClient,
            credentialStore = store
        )
        val restored = repoRestarted.restoreSavedSession()
        assertEquals(AuthSessionStatus.AUTHENTICATED, restored.status)
        assertEquals(TestMode.LIVE, repoRestarted.configRepository.config.value.mode)

        // 4. Network error during /auth/me MUST NOT delete valid stored token!
        nextMeResult = ApiCallResult(
            isSuccess = false,
            httpStatusCode = null,
            errorCode = "network_error",
            errorMessage = "Tidak ada koneksi internet."
        )
        val offlineState = repoRestarted.refreshAuthSession()
        assertEquals(AuthSessionStatus.NETWORK_ERROR, offlineState.status)
        assertTrue(offlineState.hasStoredCredentials)
        assertNotNull(store.loadSession())

        // 5. Revoked token (HTTP 401) marks session REVOKED_OR_INVALID and clears stored credentials
        nextMeResult = ApiCallResult(
            isSuccess = false,
            httpStatusCode = 401,
            errorCode = "unauthorized",
            errorMessage = "Token dicabut."
        )
        val revokedState = repoRestarted.refreshAuthSession()
        assertEquals(AuthSessionStatus.REVOKED_OR_INVALID, revokedState.status)
        assertFalse(revokedState.hasStoredCredentials)
        assertNull(store.loadSession())

        // 6. Re-pair and Logout -> calls POST /auth/logout and clears local store
        nextMeResult = ApiCallResult(
            isSuccess = true,
            httpStatusCode = 200,
            data = AuthMeProfile(
                deviceId = "DEV-LIVE001", deviceName = "Pixel 9", pairedAtWib = "-", lastActiveAtWib = "-",
                userId = 999L, username = "tester", fullName = "Tester", isBlocked = false,
                packageId = "gratis", packageName = "Gratis", packageStatus = "active",
                isUnlimited = false, daysRemaining = null, dailyLimit = 10, usedToday = 0,
                activeReserved = 0, remainingQuota = 10, resetCountdownWib = "6j", audioFormat = "flac"
            )
        )
        assertTrue(repoRestarted.pairWithTelegramCode("https://api.auditify.web.id", "654321", "Pixel 9").isSuccess)
        val loggedOut = repoRestarted.logoutCurrentDevice()
        assertEquals(1, logoutCalled)
        assertEquals(AuthSessionStatus.UNAUTHENTICATED, loggedOut.status)
        assertNull(store.loadSession())
        assertEquals("", repoRestarted.configRepository.config.value.bearerToken)
    }
}
