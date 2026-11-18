package com.example

import com.example.core.config.SessionConfigRepository
import com.example.core.config.TestMode
import com.example.core.config.TesterSessionConfig
import com.example.core.security.LogSanitizer
import com.example.core.security.UrlValidationResult
import com.example.core.security.UrlValidator
import com.example.data.model.ConnectionStatus
import com.example.data.model.WorkerJobStatus
import com.example.data.remote.ApiConfig
import com.example.data.remote.ApiConfigValidation
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
}
