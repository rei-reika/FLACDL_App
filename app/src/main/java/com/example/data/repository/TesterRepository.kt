package com.example.data.repository

import com.example.core.config.SessionConfigRepository
import com.example.core.config.TestMode
import com.example.core.config.TesterSessionConfig
import com.example.core.security.DeviceCredentialStore
import com.example.core.security.InMemoryDeviceCredentialStore
import com.example.core.security.LogSanitizer
import com.example.core.security.StoredDeviceSession
import com.example.data.model.ApiCallResult
import com.example.data.model.AuthMeProfile
import com.example.data.model.AuthSessionState
import com.example.data.model.AuthSessionStatus
import com.example.data.model.ConnectionStatus
import com.example.data.model.DashboardMetrics
import com.example.data.model.DiagnosticReportRequest
import com.example.data.model.EndpointDiagnosticsSummary
import com.example.data.model.HandoffResponse
import com.example.data.model.HealthCheckInfo
import com.example.data.model.NetworkDiagnosticsReport
import com.example.data.model.NetworkLatencySample
import com.example.data.model.PairDeviceResponse
import com.example.data.model.SearchResponse
import com.example.data.model.SearchTrackItem
import com.example.data.model.WorkerJobStatus
import com.example.data.model.WorkerPollResponse
import com.example.data.remote.DemoFlacdlApiClient
import com.example.data.remote.FlacdlApiClient
import com.example.data.remote.OkHttpFlacdlApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Central repository (`TesterRepository` / `ApiRepository`) bridging Control Plane (VPS)
 * and Data Plane (Lucida Worker) across Demo Mode and Live Mode.
 * Device bearer tokens are stored exclusively via [DeviceCredentialStore] (Android Keystore AES-GCM).
 */
class TesterRepository(
    val configRepository: SessionConfigRepository,
    private val liveClient: FlacdlApiClient = OkHttpFlacdlApiClient(),
    private val demoClient: FlacdlApiClient = DemoFlacdlApiClient(),
    private val credentialStore: DeviceCredentialStore = InMemoryDeviceCredentialStore()
) {
    private val _dashboardMetrics = MutableStateFlow(DashboardMetrics())
    val dashboardMetrics: StateFlow<DashboardMetrics> = _dashboardMetrics.asStateFlow()

    private val _latestHealthCheck = MutableStateFlow<HealthCheckInfo?>(null)
    val latestHealthCheck: StateFlow<HealthCheckInfo?> = _latestHealthCheck.asStateFlow()

    private val _selectedTrack = MutableStateFlow<SearchTrackItem?>(null)
    val selectedTrack: StateFlow<SearchTrackItem?> = _selectedTrack.asStateFlow()

    private val _activeHandoff = MutableStateFlow<HandoffResponse?>(null)
    val activeHandoff: StateFlow<HandoffResponse?> = _activeHandoff.asStateFlow()

    private val _latestWorkerPoll = MutableStateFlow<WorkerPollResponse?>(null)
    val latestWorkerPoll: StateFlow<WorkerPollResponse?> = _latestWorkerPoll.asStateFlow()

    private val _networkDiagnostics = MutableStateFlow(NetworkDiagnosticsReport())
    val networkDiagnostics: StateFlow<NetworkDiagnosticsReport> = _networkDiagnostics.asStateFlow()

    private val _authSession = MutableStateFlow(AuthSessionState())
    val authSession: StateFlow<AuthSessionState> = _authSession.asStateFlow()

    private fun activeClient(config: TesterSessionConfig): FlacdlApiClient {
        return when (config.mode) {
            TestMode.DEMO -> demoClient
            TestMode.LIVE -> liveClient
        }
    }

    /**
     * Restore encrypted device session from Android Keystore on app startup and validate via `GET /poc/v1/auth/me`.
     * Network failures NEVER delete stored credentials; only HTTP 401 (revoked/invalid) clears local credentials.
     */
    suspend fun restoreSavedSession(): AuthSessionState {
        val stored = credentialStore.loadSession()
        if (stored == null) {
            val unauth = AuthSessionState(
                status = AuthSessionStatus.UNAUTHENTICATED,
                hasStoredCredentials = false,
                isBusy = false,
                profile = null,
                statusMessage = "Belum ditautkan. Buat kode 6 digit melalui /android di Telegram Bot.",
                errorMessage = null
            )
            _authSession.value = unauth
            return unauth
        }

        configRepository.apply {
            setMode(TestMode.LIVE)
            updateVpsBaseUrl(stored.baseUrl)
            updateSessionToken(stored.bearerToken)
        }
        _authSession.value = AuthSessionState(
            status = AuthSessionStatus.RESTORING,
            hasStoredCredentials = true,
            isBusy = true,
            statusMessage = "Memulihkan sesi perangkat (${stored.deviceName.ifBlank { stored.deviceId }})..."
        )
        return refreshAuthSession()
    }

    /**
     * Pair Android device using a 6-digit Telegram `/android` code via `POST /poc/v1/auth/pair`.
     * Stores the resulting 256-bit Bearer token in Android Keystore-backed storage and fetches `/auth/me`.
     */
    suspend fun pairWithTelegramCode(
        baseUrl: String,
        pairingCode: String,
        deviceName: String
    ): ApiCallResult<PairDeviceResponse> {
        val targetBaseUrl = baseUrl.trim().ifBlank { DEFAULT_PRODUCTION_BASE_URL }
        _authSession.update {
            it.copy(
                status = AuthSessionStatus.PAIRING,
                isBusy = true,
                errorMessage = null,
                statusMessage = "Menautkan perangkat ke ${LogSanitizer.maskUrlForDisplay(targetBaseUrl)}..."
            )
        }

        val pairResult = liveClient.pairDevice(
            baseUrl = targetBaseUrl,
            pairingCode = pairingCode,
            deviceName = deviceName
        )
        if (!pairResult.isSuccess || pairResult.data == null) {
            val existingStored = credentialStore.loadSession() != null
            val errMsg = pairResult.errorMessage ?: "Pairing gagal."
            _authSession.update { prev ->
                prev.copy(
                    status = if (existingStored && prev.profile != null) {
                        AuthSessionStatus.AUTHENTICATED
                    } else {
                        AuthSessionStatus.UNAUTHENTICATED
                    },
                    hasStoredCredentials = existingStored,
                    isBusy = false,
                    statusMessage = "Pairing gagal.",
                    errorMessage = LogSanitizer.redactSensitive(errMsg)
                )
            }
            return pairResult
        }

        val paired = pairResult.data
        val cleanBase = targetBaseUrl.trimEnd('/')
        credentialStore.saveSession(
            StoredDeviceSession(
                baseUrl = cleanBase,
                bearerToken = paired.accessToken,
                deviceId = paired.deviceId,
                deviceName = paired.deviceName
            )
        )
        configRepository.apply {
            setMode(TestMode.LIVE)
            updateVpsBaseUrl(cleanBase)
            updateSessionToken(paired.accessToken)
        }

        // Validate immediately against GET /poc/v1/auth/me to load user, package, and quota profile
        refreshAuthSession()
        return pairResult
    }

    /**
     * Validate current session against `GET /poc/v1/auth/me`.
     * - HTTP 200 -> AUTHENTICATED with full profile.
     * - HTTP 401 -> REVOKED_OR_INVALID and clears stored credentials.
     * - Network / 5xx error -> NETWORK_ERROR while PRESERVING stored valid credentials!
     */
    suspend fun refreshAuthSession(): AuthSessionState {
        val cfg = configRepository.config.value
        val hasStored = credentialStore.loadSession() != null
        if (cfg.bearerToken.isBlank()) {
            val unauth = AuthSessionState(
                status = AuthSessionStatus.UNAUTHENTICATED,
                hasStoredCredentials = hasStored,
                isBusy = false,
                statusMessage = "Belum ditautkan. Buat kode 6 digit melalui /android di Telegram Bot."
            )
            _authSession.value = unauth
            return unauth
        }

        _authSession.update { it.copy(isBusy = true, errorMessage = null) }
        val result = liveClient.fetchAuthMe(cfg)
        if (result.isSuccess && result.data != null) {
            val profile = result.data
            val authenticatedState = AuthSessionState(
                status = AuthSessionStatus.AUTHENTICATED,
                hasStoredCredentials = credentialStore.loadSession() != null,
                isBusy = false,
                profile = profile,
                statusMessage = if (profile.isBlocked) {
                    "Sesi terautentikasi, tetapi akun Telegram diblokir."
                } else {
                    "Perangkat terhubung (${profile.deviceName})."
                },
                errorMessage = null,
                isSimulated = result.isSimulated
            )
            _authSession.value = authenticatedState
            return authenticatedState
        }

        // HTTP 401: token is invalid, expired, or revoked on server -> clear local credentials!
        if (result.httpStatusCode == 401) {
            credentialStore.clearSession()
            configRepository.clearSessionToken()
            val revokedState = AuthSessionState(
                status = AuthSessionStatus.REVOKED_OR_INVALID,
                hasStoredCredentials = false,
                isBusy = false,
                profile = null,
                statusMessage = "Sesi tidak valid atau telah dicabut. Silakan pairing ulang.",
                errorMessage = LogSanitizer.redactSensitive(
                    result.errorMessage ?: "Token perangkat dicabut atau tidak valid (HTTP 401)."
                )
            )
            _authSession.value = revokedState
            return revokedState
        }

        // Network error or 5xx/other transient failure -> KEEP stored valid token intact!
        val preservedState = _authSession.value.copy(
            status = AuthSessionStatus.NETWORK_ERROR,
            hasStoredCredentials = credentialStore.loadSession() != null,
            isBusy = false,
            statusMessage = "Tidak dapat memverifikasi sesi (kendala jaringan). Kredensial lokal tetap dipertahankan.",
            errorMessage = LogSanitizer.redactSensitive(
                result.errorMessage ?: "Koneksi ke server gagal."
            )
        )
        _authSession.value = preservedState
        return preservedState
    }

    /**
     * Logout current device: revokes token on server (`POST /poc/v1/auth/logout`) and
     * unconditionally clears encrypted local credentials from Android Keystore.
     */
    suspend fun logoutCurrentDevice(): AuthSessionState {
        val cfg = configRepository.config.value
        _authSession.update { it.copy(isBusy = true, errorMessage = null, statusMessage = "Memutuskan sesi perangkat...") }

        if (cfg.bearerToken.isNotBlank() && cfg.vpsBaseUrl.isNotBlank()) {
            try {
                liveClient.logoutDevice(cfg)
            } catch (_: Exception) {
                // Local logout still proceeds even if server is unreachable
            }
        }

        credentialStore.clearSession()
        configRepository.clearSessionToken()
        val loggedOut = AuthSessionState(
            status = AuthSessionStatus.UNAUTHENTICATED,
            hasStoredCredentials = false,
            isBusy = false,
            profile = null,
            statusMessage = "Perangkat telah logout dan kredensial lokal dihapus.",
            errorMessage = null
        )
        _authSession.value = loggedOut
        return loggedOut
    }

    companion object {
        const val DEFAULT_PRODUCTION_BASE_URL = "https://api.auditify.web.id"
    }

    suspend fun testApiConnection(): ApiCallResult<HealthCheckInfo> {
        val cfg = configRepository.config.value
        _dashboardMetrics.update {
            it.copy(
                vpsStatus = ConnectionStatus.TESTING,
                lastStatusNote = "Menguji koneksi GET ${cfg.healthEndpointPath}..."
            )
        }

        val result = activeClient(cfg).checkVpsHealth(cfg)
        if (result.isSuccess && result.data != null) {
            val healthWithLatency = result.data.copy(responseTimeMs = result.latencyMs)
            _latestHealthCheck.value = healthWithLatency
            _dashboardMetrics.update { current ->
                current.copy(
                    vpsStatus = if (healthWithLatency.vpsReachable) ConnectionStatus.CONNECTED else ConnectionStatus.DISCONNECTED,
                    // Do not equate VPS clearance availability with Lucida Worker data-plane reachability
                    workerStatus = current.workerStatus,
                    lastHttpStatus = result.httpStatusCode,
                    lastLatencyMs = result.latencyMs,
                    lastStatusNote = healthWithLatency.summary,
                    latestHealthInfo = healthWithLatency,
                    isHealthSimulated = result.isSimulated
                )
            }
            return result.copy(data = healthWithLatency)
        } else {
            _latestHealthCheck.value = null
            _dashboardMetrics.update { current ->
                current.copy(
                    vpsStatus = ConnectionStatus.DISCONNECTED,
                    lastHttpStatus = result.httpStatusCode,
                    lastLatencyMs = result.latencyMs,
                    lastStatusNote = result.errorMessage ?: "Koneksi gagal.",
                    latestHealthInfo = null,
                    isHealthSimulated = result.isSimulated
                )
            }
            return result
        }
    }

    suspend fun runNetworkDiagnostics(requestsPerEndpoint: Int = 10): NetworkDiagnosticsReport {
        val cfg = configRepository.config.value
        val isSimulated = cfg.mode == TestMode.DEMO
        val targetEndpoints = listOf(
            cfg.healthEndpointPath.ifBlank { "/poc/v1/health" },
            "/poc/v1/auth/me"
        )
        val count = requestsPerEndpoint.coerceAtLeast(1)
        val completedSummaries = mutableListOf<EndpointDiagnosticsSummary>()

        _networkDiagnostics.value = NetworkDiagnosticsReport(
            isRunning = true,
            isSimulated = isSimulated,
            progressNote = "Menjalankan diagnostik (${targetEndpoints.first()} 0/$count)...",
            endpoints = emptyList(),
            errorMessage = null
        )

        val client = activeClient(cfg)
        for (endpoint in targetEndpoints) {
            val liveSamples = mutableListOf<NetworkLatencySample>()
            val summary = client.measureEndpointLatency(
                config = cfg,
                endpointPath = endpoint,
                requestCount = count,
                onSampleRecorded = { sample ->
                    liveSamples.add(sample)
                    val inProgress = EndpointDiagnosticsSummary.fromSamples(endpoint, liveSamples.toList())
                    _networkDiagnostics.value = NetworkDiagnosticsReport(
                        isRunning = true,
                        isSimulated = isSimulated,
                        progressNote = "Mengukur GET $endpoint (${liveSamples.size}/$count)...",
                        endpoints = completedSummaries + inProgress,
                        errorMessage = null
                    )
                }
            )
            completedSummaries.add(summary)
        }

        val finalReport = NetworkDiagnosticsReport(
            isRunning = false,
            isSimulated = isSimulated,
            progressNote = if (isSimulated) {
                "Selesai (Simulasi Demo Mode — $count req/endpoint)."
            } else {
                "Selesai ($count request berurutan per endpoint)."
            },
            endpoints = completedSummaries.toList(),
            errorMessage = null
        )
        _networkDiagnostics.value = finalReport
        return finalReport
    }

    suspend fun searchCatalog(query: String): ApiCallResult<SearchResponse> {
        val cfg = configRepository.config.value
        val result = activeClient(cfg).searchCatalog(cfg, query)
        _dashboardMetrics.update { current ->
            current.copy(
                vpsStatus = if (result.isSuccess) ConnectionStatus.CONNECTED else current.vpsStatus,
                lastHttpStatus = result.httpStatusCode ?: current.lastHttpStatus,
                lastLatencyMs = result.latencyMs,
                lastStatusNote = if (result.isSuccess && result.data != null) {
                    "Pencarian '${result.data.query}' mengembalikan ${result.data.count} lagu."
                } else {
                    result.errorMessage ?: "Pencarian gagal."
                }
            )
        }
        return result
    }

    suspend fun searchTracks(query: String): ApiCallResult<List<SearchTrackItem>> {
        val catalogResult = searchCatalog(query)
        return ApiCallResult(
            isSuccess = catalogResult.isSuccess,
            data = catalogResult.data?.results,
            httpStatusCode = catalogResult.httpStatusCode,
            latencyMs = catalogResult.latencyMs,
            errorCode = catalogResult.errorCode,
            errorMessage = catalogResult.errorMessage,
            isSimulated = catalogResult.isSimulated
        )
    }

    /**
     * Save the user-selected track in application state for the Handoff screen.
     * NEVER initiates automatic handoff or automatic download.
     */
    fun selectTrack(track: SearchTrackItem) {
        _selectedTrack.value = track
        // Clear previous handoff/worker state when starting with a new track
        _activeHandoff.value = null
        _latestWorkerPoll.value = null
    }

    suspend fun createHandoff(trackUrl: String): ApiCallResult<HandoffResponse> {
        val cfg = configRepository.config.value
        val result = activeClient(cfg).createHandoff(cfg, trackUrl)
        if (result.isSuccess && result.data != null) {
            _activeHandoff.value = result.data
            _latestWorkerPoll.value = null
            _dashboardMetrics.update {
                it.copy(
                    vpsStatus = ConnectionStatus.CONNECTED,
                    workerStatus = ConnectionStatus.TESTING,
                    totalTests = it.totalTests + 1,
                    lastHttpStatus = result.httpStatusCode,
                    lastLatencyMs = result.latencyMs,
                    lastStatusNote = "Handoff dibuat untuk server ${result.data.server} (URL disamarkan: ${LogSanitizer.maskUrlForDisplay(result.data.pollUrl)})."
                )
            }
        } else {
            _dashboardMetrics.update {
                it.copy(
                    lastHttpStatus = result.httpStatusCode,
                    lastLatencyMs = result.latencyMs,
                    lastStatusNote = result.errorMessage ?: "Pembuatan handoff gagal."
                )
            }
        }
        return result
    }

    suspend fun pollWorkerStatus(): ApiCallResult<WorkerPollResponse> {
        val handoff = _activeHandoff.value
            ?: return ApiCallResult(isSuccess = false, errorMessage = "Belum ada handoff aktif untuk di-poll.")
        val cfg = configRepository.config.value
        val result = activeClient(cfg).pollWorker(cfg, handoff.pollUrl, handoff.server)
        if (result.isSuccess && result.data != null) {
            _latestWorkerPoll.value = result.data
            val workerConn = when (result.data.status) {
                WorkerJobStatus.COMPLETED, WorkerJobStatus.METADATA -> ConnectionStatus.CONNECTED
                WorkerJobStatus.FAILED -> ConnectionStatus.DISCONNECTED
                WorkerJobStatus.UNKNOWN -> ConnectionStatus.TESTING
            }
            _dashboardMetrics.update {
                it.copy(
                    workerStatus = workerConn,
                    lastHttpStatus = result.httpStatusCode,
                    lastLatencyMs = result.latencyMs
                )
            }
        } else {
            _dashboardMetrics.update {
                it.copy(
                    workerStatus = ConnectionStatus.DISCONNECTED,
                    lastHttpStatus = result.httpStatusCode,
                    lastLatencyMs = result.latencyMs
                )
            }
        }
        return result
    }

    fun resolveDirectDownloadUrl(): String? {
        return _latestWorkerPoll.value?.downloadUrl?.takeIf { it.isNotBlank() }
            ?: _activeHandoff.value?.downloadUrl?.takeIf { it.isNotBlank() }
    }

    fun recordDownloadOutcome(success: Boolean) {
        _dashboardMetrics.update { current ->
            if (success) {
                current.copy(
                    successfulDownloads = current.successfulDownloads + 1,
                    workerStatus = ConnectionStatus.CONNECTED,
                    lastStatusNote = "Unduhan langsung dari worker selesai & lolos validasi FLAC."
                )
            } else {
                current.copy(
                    failedDownloads = current.failedDownloads + 1,
                    lastStatusNote = "Unduhan atau validasi file FLAC gagal."
                )
            }
        }
    }

    suspend fun sendDiagnosticReport(report: DiagnosticReportRequest): ApiCallResult<String> {
        val cfg = configRepository.config.value
        return activeClient(cfg).sendDiagnosticReport(cfg, report)
    }

    fun resetActiveTestSession() {
        _selectedTrack.value = null
        _activeHandoff.value = null
        _latestWorkerPoll.value = null
    }
}

typealias ApiRepository = TesterRepository
