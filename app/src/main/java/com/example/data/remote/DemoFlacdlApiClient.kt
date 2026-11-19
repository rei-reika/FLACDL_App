package com.example.data.remote

import com.example.core.config.TesterSessionConfig
import com.example.data.model.ApiCallResult
import com.example.data.model.DiagnosticReportRequest
import com.example.data.model.EndpointDiagnosticsSummary
import com.example.data.model.HandoffResponse
import com.example.data.model.HealthCheckInfo
import com.example.data.model.NetworkLatencySample
import com.example.data.model.SearchResponse
import com.example.data.model.SearchTrackItem
import com.example.data.model.WorkerJobStatus
import com.example.data.model.WorkerPollResponse
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Simulated offline client for Demo Mode.
 * Clearly marks all responses with `isSimulated = true` and never sends network requests.
 */
class DemoFlacdlApiClient : FlacdlApiClient {

    private val pollCounters = ConcurrentHashMap<String, AtomicInteger>()

    private val catalog = listOf(
        SearchTrackItem(
            title = "Fly High",
            artist = "Toby Romeo",
            album = "Fly High",
            duration = 166,
            trackUrl = "https://music.amazon.com/tracks/B0H2D1MCPS"
        ),
        SearchTrackItem(
            title = "Nightcall (2021 Remaster)",
            artist = "Kavinsky",
            album = "OutRun (Deluxe Edition)",
            duration = 258,
            trackUrl = "https://music.amazon.com/tracks/B08DEMO001"
        ),
        SearchTrackItem(
            title = "Midnight City",
            artist = "M83",
            album = "Hurry Up, We're Dreaming",
            duration = 243,
            trackUrl = "https://music.amazon.com/tracks/B08DEMO002"
        ),
        SearchTrackItem(
            title = "Instant Crush (feat. Julian Casablancas)",
            artist = "Daft Punk",
            album = "Random Access Memories",
            duration = 337,
            trackUrl = "https://music.amazon.com/tracks/B08DEMO003"
        ),
        SearchTrackItem(
            title = "Resonance",
            artist = "HOME",
            album = "Odyssey",
            duration = 212,
            trackUrl = "https://music.amazon.com/tracks/B08DEMO004"
        ),
        SearchTrackItem(
            title = "The Less I Know The Better",
            artist = "Tame Impala",
            album = "Currents",
            duration = 216,
            trackUrl = "https://music.amazon.com/tracks/B08DEMO005"
        )
    )

    override suspend fun checkVpsHealth(config: TesterSessionConfig): ApiCallResult<HealthCheckInfo> {
        delay(220)
        val simulatedLatency = 42L
        return ApiCallResult(
            isSuccess = true,
            data = HealthCheckInfo(
                status = "ok (simulasi)",
                clearanceAvailable = true,
                hasCfCookie = true,
                lucidadlVersion = "1.4.3-demo",
                serverTime = "2026-11-18T17:00:00Z (Demo Mode)",
                responseTimeMs = simulatedLatency,
                vpsReachable = true,
                workerReachable = true,
                summary = "Simulasi Demo Mode: data lokal (bukan respons server VPS aktual)."
            ),
            httpStatusCode = 200,
            latencyMs = simulatedLatency,
            isSimulated = true
        )
    }

    override suspend fun measureEndpointLatency(
        config: TesterSessionConfig,
        endpointPath: String,
        requestCount: Int,
        onSampleRecorded: ((NetworkLatencySample) -> Unit)?
    ): EndpointDiagnosticsSummary {
        val count = requestCount.coerceAtLeast(1)
        val samples = mutableListOf<NetworkLatencySample>()
        for (i in 1..count) {
            delay(35)
            val sample = NetworkLatencySample(
                requestIndex = i,
                endpointPath = endpointPath,
                dnsLookupMs = if (i == 1) 12L else null,
                tcpConnectMs = if (i == 1) 18L else null,
                tlsHandshakeMs = if (i == 1) 26L else null,
                totalDurationMs = if (i == 1) 74L else (22L + (i % 4) * 3L),
                httpStatusCode = 200,
                isSuccess = true,
                errorMessage = null
            )
            samples.add(sample)
            onSampleRecorded?.invoke(sample)
        }
        return EndpointDiagnosticsSummary.fromSamples(endpointPath, samples)
    }

    override suspend fun searchCatalog(
        config: TesterSessionConfig,
        query: String
    ): ApiCallResult<SearchResponse> {
        delay(200)
        val rawQuery = query.trim()
        val q = rawQuery.lowercase()
        val matched = if (q.isEmpty()) {
            catalog
        } else {
            val filtered = catalog.filter {
                it.title.lowercase().contains(q) ||
                    it.artist.lowercase().contains(q) ||
                    it.albumDisplay.lowercase().contains(q)
            }
            filtered
        }
        return ApiCallResult(
            isSuccess = true,
            data = SearchResponse(
                status = "ok",
                query = rawQuery.ifEmpty { "Demo Catalog" },
                count = matched.size,
                results = matched
            ),
            httpStatusCode = 200,
            latencyMs = 38,
            isSimulated = true
        )
    }

    override suspend fun searchTracks(
        config: TesterSessionConfig,
        query: String
    ): ApiCallResult<List<SearchTrackItem>> {
        val res = searchCatalog(config, query)
        return ApiCallResult(
            isSuccess = res.isSuccess,
            data = res.data?.results,
            httpStatusCode = res.httpStatusCode,
            latencyMs = res.latencyMs,
            errorCode = res.errorCode,
            errorMessage = res.errorMessage,
            isSimulated = true
        )
    }

    override suspend fun createHandoff(
        config: TesterSessionConfig,
        trackUrl: String
    ): ApiCallResult<HandoffResponse> {
        delay(480)
        val matched = catalog.find { it.trackUrl == trackUrl } ?: catalog.first()
        val suffix = (1000..9999).random()
        val handoffId = "demo-hnd-$suffix"
        val testId = "tst-$suffix"
        pollCounters[handoffId] = AtomicInteger(0)

        return ApiCallResult(
            isSuccess = true,
            data = HandoffResponse(
                status = "ready",
                testId = testId,
                handoffId = handoffId,
                server = "lucida-sg-worker-02.demo",
                country = "SG",
                pollUrl = "https://lucida-sg-worker-02.demo/poll/$handoffId",
                downloadUrl = "https://lucida-sg-worker-02.demo/direct/$handoffId.flac",
                title = matched.title,
                artist = matched.artist,
                trackUrl = matched.trackUrl
            ),
            httpStatusCode = 201,
            latencyMs = 64,
            isSimulated = true
        )
    }

    override suspend fun pollWorker(
        config: TesterSessionConfig,
        pollUrl: String,
        serverHint: String
    ): ApiCallResult<WorkerPollResponse> {
        delay(650)
        val key = pollUrl.substringAfterLast('/')
        val step = pollCounters.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()

        val response = when (step) {
            1 -> WorkerPollResponse(
                status = WorkerJobStatus.UNKNOWN,
                rawStatus = "unknown",
                server = serverHint,
                progressPercent = 10,
                message = "Worker menginisialisasi antrean pekerjaan (Demo)."
            )
            2, 3 -> WorkerPollResponse(
                status = WorkerJobStatus.METADATA,
                rawStatus = "metadata",
                server = serverHint,
                progressPercent = if (step == 2) 45 else 80,
                message = "Worker mengekstrak metadata dan menyiapkan stream FLAC."
            )
            else -> WorkerPollResponse(
                status = WorkerJobStatus.COMPLETED,
                rawStatus = "completed",
                server = serverHint,
                progressPercent = 100,
                message = "Stream FLAC siap diunduh langsung dari worker.",
                downloadUrl = "https://lucida-sg-worker-02.demo/direct/$key.flac",
                fileName = "demo_lossless_track.flac",
                contentLengthBytes = 1_248_576L
            )
        }

        return ApiCallResult(
            isSuccess = true,
            data = response,
            httpStatusCode = 200,
            latencyMs = (28..55).random().toLong(),
            isSimulated = true
        )
    }

    override suspend fun sendDiagnosticReport(
        config: TesterSessionConfig,
        report: DiagnosticReportRequest
    ): ApiCallResult<String> {
        delay(250)
        return ApiCallResult(
            isSuccess = true,
            data = "Simulasi Demo Mode: laporan diagnostik tercatat secara lokal.",
            httpStatusCode = 202,
            latencyMs = 25,
            isSimulated = true
        )
    }
}
