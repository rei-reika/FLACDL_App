package com.example.data.repository

import com.example.core.config.SessionConfigRepository
import com.example.core.config.TestMode
import com.example.core.config.TesterSessionConfig
import com.example.core.security.LogSanitizer
import com.example.data.model.ApiCallResult
import com.example.data.model.ConnectionStatus
import com.example.data.model.DashboardMetrics
import com.example.data.model.DiagnosticReportRequest
import com.example.data.model.EndpointDiagnosticsSummary
import com.example.data.model.HandoffResponse
import com.example.data.model.HealthCheckInfo
import com.example.data.model.NetworkDiagnosticsReport
import com.example.data.model.NetworkLatencySample
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
 * Sensitive tokens and download URLs are never persisted to disk.
 */
class TesterRepository(
    val configRepository: SessionConfigRepository,
    private val liveClient: FlacdlApiClient = OkHttpFlacdlApiClient(),
    private val demoClient: FlacdlApiClient = DemoFlacdlApiClient()
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

    private fun activeClient(config: TesterSessionConfig): FlacdlApiClient {
        return when (config.mode) {
            TestMode.DEMO -> demoClient
            TestMode.LIVE -> liveClient
        }
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
