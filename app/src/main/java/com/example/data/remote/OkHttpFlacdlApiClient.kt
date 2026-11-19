package com.example.data.remote

import com.example.core.config.TesterSessionConfig
import com.example.core.security.LogSanitizer
import com.example.core.security.UrlValidationResult
import com.example.core.security.UrlValidator
import com.example.data.model.ApiCallResult
import com.example.data.model.DiagnosticReportRequest
import com.example.data.model.EndpointDiagnosticsSummary
import com.example.data.model.HandoffRequest
import com.example.data.model.HandoffResponse
import com.example.data.model.HealthCheckInfo
import com.example.data.model.NetworkLatencySample
import com.example.data.model.SearchRequest
import com.example.data.model.SearchResponse
import com.example.data.model.SearchTrackItem
import com.example.data.model.WorkerJobStatus
import com.example.data.model.WorkerPollResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Live Mode HTTP client using OkHttp with standard system TLS verification and Kotlin Coroutines.
 *
 * Security & Contract Guarantees:
 * - Never disables TLS certificate validation.
 * - Rejects cleartext HTTP and Android localhost/127.0.0.1 via [ApiConfig.validateForLiveMode].
 * - Always sends `Authorization: Bearer {session_token}` and `Content-Type: application/json`
 *   on Live Mode requests to the VPS Control Plane.
 * - Never logs Bearer tokens, cookies, or complete handoff/download URLs to Logcat.
 * - Always executes network I/O on [Dispatchers.IO].
 */
class OkHttpFlacdlApiClient(
    private val client: OkHttpClient = defaultOkHttpClient()
) : FlacdlApiClient {

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val searchRequestAdapter = moshi.adapter(SearchRequest::class.java)
    private val handoffRequestAdapter = moshi.adapter(HandoffRequest::class.java)
    private val handoffResponseAdapter = moshi.adapter(HandoffResponse::class.java)
    private val reportAdapter = moshi.adapter(DiagnosticReportRequest::class.java)

    override suspend fun checkVpsHealth(config: TesterSessionConfig): ApiCallResult<HealthCheckInfo> {
        val apiConfig = when (val validation = ApiConfig.validateForLiveMode(config)) {
            is ApiConfigValidation.Invalid -> {
                return ApiCallResult(
                    isSuccess = false,
                    errorCode = validation.errorCode,
                    errorMessage = validation.message
                )
            }
            is ApiConfigValidation.Valid -> validation.config
        }

        val url = apiConfig.buildEndpointUrl(apiConfig.healthEndpointPath)
        val request = buildVpsGetRequest(url, apiConfig.bearerToken)
        return executeTimedCall(request) { _, body, latencyMs ->
            parseHealthResponse(body, latencyMs)
        }
    }

    override suspend fun measureEndpointLatency(
        config: TesterSessionConfig,
        endpointPath: String,
        requestCount: Int,
        onSampleRecorded: ((NetworkLatencySample) -> Unit)?
    ): EndpointDiagnosticsSummary = withContext(Dispatchers.IO) {
        val apiConfig = when (val validation = ApiConfig.validateForLiveMode(config)) {
            is ApiConfigValidation.Invalid -> {
                val invalidSample = NetworkLatencySample(
                    requestIndex = 1,
                    endpointPath = endpointPath,
                    totalDurationMs = 0L,
                    isSuccess = false,
                    errorMessage = validation.message
                )
                onSampleRecorded?.invoke(invalidSample)
                return@withContext EndpointDiagnosticsSummary.fromSamples(
                    endpointPath = endpointPath,
                    samples = listOf(invalidSample)
                )
            }
            is ApiConfigValidation.Valid -> validation.config
        }

        val count = requestCount.coerceAtLeast(1)
        val url = apiConfig.buildEndpointUrl(endpointPath)
        val samples = mutableListOf<NetworkLatencySample>()

        for (index in 1..count) {
            val timingListener = CallTimingListener()
            // Shallow builder shares the exact same ConnectionPool, Dispatcher, and timeouts as `client`.
            val instrumentedClient = client.newBuilder()
                .eventListener(timingListener)
                .build()
            val request = buildVpsGetRequest(url, apiConfig.bearerToken)
            val startNanos = System.nanoTime()

            val sample = try {
                instrumentedClient.newCall(request).execute().use { response ->
                    val bodyText = response.body?.string().orEmpty()
                    val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                    val errorMsg = if (!response.isSuccessful) {
                        val handled = NetworkErrorHandler.fromHttpResponse(
                            httpCode = response.code,
                            httpMessage = response.message,
                            rawBody = bodyText,
                            retryAfterHeader = response.header("Retry-After")
                        )
                        LogSanitizer.redactSensitive(handled.userMessage)
                    } else {
                        null
                    }
                    NetworkLatencySample(
                        requestIndex = index,
                        endpointPath = endpointPath,
                        dnsLookupMs = timingListener.dnsLookupMs(),
                        tcpConnectMs = timingListener.tcpConnectMs(),
                        tlsHandshakeMs = timingListener.tlsHandshakeMs(),
                        totalDurationMs = totalMs,
                        httpStatusCode = response.code,
                        isSuccess = response.isSuccessful,
                        errorMessage = errorMsg
                    )
                }
            } catch (e: Exception) {
                val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                val handled = NetworkErrorHandler.fromException(e)
                NetworkLatencySample(
                    requestIndex = index,
                    endpointPath = endpointPath,
                    dnsLookupMs = timingListener.dnsLookupMs(),
                    tcpConnectMs = timingListener.tcpConnectMs(),
                    tlsHandshakeMs = timingListener.tlsHandshakeMs(),
                    totalDurationMs = totalMs,
                    httpStatusCode = handled.httpStatusCode,
                    isSuccess = false,
                    errorMessage = LogSanitizer.redactSensitive(handled.userMessage)
                )
            }

            samples.add(sample)
            onSampleRecorded?.invoke(sample)
        }

        EndpointDiagnosticsSummary.fromSamples(endpointPath, samples)
    }

    /**
     * OkHttp [EventListener] measuring per-call DNS, TCP connect, and TLS handshake durations
     * using a monotonic clock ([System.nanoTime]). Returns null for phases skipped via connection reuse.
     */
    internal class CallTimingListener(
        private val clockNanos: () -> Long = System::nanoTime
    ) : EventListener() {
        @Volatile private var dnsStartNanos: Long? = null
        @Volatile private var dnsDurationNanos: Long? = null
        @Volatile private var connectStartNanos: Long? = null
        @Volatile private var connectEndNanos: Long? = null
        @Volatile private var tlsStartNanos: Long? = null
        @Volatile private var tlsDurationNanos: Long? = null

        override fun dnsStart(call: Call, domainName: String) {
            dnsStartNanos = clockNanos()
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            dnsStartNanos?.let { start ->
                dnsDurationNanos = (clockNanos() - start).coerceAtLeast(0L)
            }
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            connectStartNanos = clockNanos()
        }

        override fun secureConnectStart(call: Call) {
            tlsStartNanos = clockNanos()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            tlsStartNanos?.let { start ->
                tlsDurationNanos = (clockNanos() - start).coerceAtLeast(0L)
            }
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?
        ) {
            connectEndNanos = clockNanos()
        }

        override fun connectFailed(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?,
            ioe: IOException
        ) {
            connectEndNanos = clockNanos()
        }

        fun dnsLookupMs(): Long? = dnsDurationNanos?.let { TimeUnit.NANOSECONDS.toMillis(it) }

        fun tcpConnectMs(): Long? {
            val start = connectStartNanos ?: return null
            val end = tlsStartNanos ?: connectEndNanos ?: return null
            return TimeUnit.NANOSECONDS.toMillis((end - start).coerceAtLeast(0L))
        }

        fun tlsHandshakeMs(): Long? = tlsDurationNanos?.let { TimeUnit.NANOSECONDS.toMillis(it) }
    }

    override suspend fun searchCatalog(
        config: TesterSessionConfig,
        query: String
    ): ApiCallResult<SearchResponse> {
        val apiConfig = when (val validation = ApiConfig.validateForLiveMode(config)) {
            is ApiConfigValidation.Invalid -> {
                return ApiCallResult(
                    isSuccess = false,
                    errorCode = validation.errorCode,
                    errorMessage = validation.message
                )
            }
            is ApiConfigValidation.Valid -> validation.config
        }

        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return ApiCallResult(
                isSuccess = false,
                errorCode = "empty_query",
                errorMessage = "Kata kunci pencarian tidak boleh kosong."
            )
        }

        val jsonPayload = searchRequestAdapter.toJson(SearchRequest(trimmedQuery))
        val url = apiConfig.buildEndpointUrl(apiConfig.searchEndpointPath)
        val request = buildVpsPostRequest(url, jsonPayload, apiConfig.bearerToken)
        return executeTimedCall(request) { _, body, _ ->
            parseSearchResponse(body, fallbackQuery = trimmedQuery)
        }
    }

    override suspend fun searchTracks(
        config: TesterSessionConfig,
        query: String
    ): ApiCallResult<List<SearchTrackItem>> {
        val catalogResult = searchCatalog(config, query)
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

    override suspend fun createHandoff(
        config: TesterSessionConfig,
        trackUrl: String
    ): ApiCallResult<HandoffResponse> {
        val apiConfig = when (val validation = ApiConfig.validateForLiveMode(config)) {
            is ApiConfigValidation.Invalid -> {
                return ApiCallResult(
                    isSuccess = false,
                    errorCode = validation.errorCode,
                    errorMessage = validation.message
                )
            }
            is ApiConfigValidation.Valid -> validation.config
        }

        val jsonPayload = handoffRequestAdapter.toJson(HandoffRequest(trackUrl))
        val url = apiConfig.buildEndpointUrl(apiConfig.handoffEndpointPath)
        val request = buildVpsPostRequest(url, jsonPayload, apiConfig.bearerToken)
        return executeTimedCall(request) { _, body, _ ->
            handoffResponseAdapter.fromJson(body)
                ?: throw IOException("Format respons handoff tidak sesuai kontrak JSON.")
        }
    }

    override suspend fun pollWorker(
        config: TesterSessionConfig,
        pollUrl: String,
        serverHint: String
    ): ApiCallResult<WorkerPollResponse> {
        when (val check = UrlValidator.validateWorkerHttpsUrl(pollUrl)) {
            is UrlValidationResult.Invalid -> {
                return ApiCallResult(
                    isSuccess = false,
                    errorCode = "invalid_worker_url",
                    errorMessage = check.reason
                )
            }
            is UrlValidationResult.Valid -> Unit
        }

        // Data plane polls Lucida Worker directly without sending VPS bearer token.
        val request = Request.Builder()
            .url(pollUrl)
            .get()
            .header("Accept", "application/json")
            .build()

        return executeTimedCall(request) { _, body, _ ->
            val json = JSONObject(body)
            val rawStatus = PocJsonParser.optNonBlankString(json, "status", "unknown")
            val status = WorkerJobStatus.fromWire(rawStatus)
            val progress = if (json.has("progress") && !json.isNull("progress")) json.optInt("progress") else null
            val message = PocJsonParser.optNullableString(json, "message")
            val downloadUrl = PocJsonParser.optNullableString(json, "download_url")
            val fileName = PocJsonParser.optNullableString(json, "file_name")
            val contentLength = if (json.has("content_length") && !json.isNull("content_length")) {
                json.optLong("content_length")
            } else {
                null
            }
            WorkerPollResponse(
                status = status,
                rawStatus = rawStatus,
                server = PocJsonParser.optNullableString(json, "server") ?: serverHint,
                progressPercent = progress,
                message = message?.let { LogSanitizer.redactSensitive(it) },
                downloadUrl = downloadUrl,
                fileName = fileName,
                contentLengthBytes = contentLength
            )
        }
    }

    override suspend fun sendDiagnosticReport(
        config: TesterSessionConfig,
        report: DiagnosticReportRequest
    ): ApiCallResult<String> {
        if (!config.diagnosticReportContractVerified) {
            return ApiCallResult(
                isSuccess = false,
                errorCode = "report_contract_unverified",
                errorMessage = "Kontrak endpoint report belum diverifikasi di Pengaturan Koneksi."
            )
        }

        val apiConfig = when (val validation = ApiConfig.validateForLiveMode(config)) {
            is ApiConfigValidation.Invalid -> {
                return ApiCallResult(
                    isSuccess = false,
                    errorCode = validation.errorCode,
                    errorMessage = validation.message
                )
            }
            is ApiConfigValidation.Valid -> validation.config
        }

        val payload = reportAdapter.toJson(report)
        val url = apiConfig.buildEndpointUrl(apiConfig.diagnosticReportPath)
        val request = buildVpsPostRequest(url, payload, apiConfig.bearerToken)
        return executeTimedCall(request) { code, _, _ ->
            "Laporan diagnostik terkirim (HTTP $code)."
        }
    }

    internal fun buildVpsGetRequest(url: String, token: String): Request {
        return Request.Builder()
            .url(url)
            .get()
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Accept", "application/json")
            .build()
    }

    internal fun buildVpsPostRequest(url: String, jsonBody: String, token: String): Request {
        val body = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer ${token.trim()}")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()
    }

    private suspend fun <T> executeTimedCall(
        request: Request,
        parser: (Int, String, Long) -> T
    ): ApiCallResult<T> = withContext(Dispatchers.IO) {
        val startNanos = System.nanoTime()
        try {
            client.newCall(request).execute().use { response ->
                val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
                val bodyText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val handledError = NetworkErrorHandler.fromHttpResponse(
                        httpCode = response.code,
                        httpMessage = response.message,
                        rawBody = bodyText,
                        retryAfterHeader = response.header("Retry-After")
                    )
                    return@withContext ApiCallResult(
                        isSuccess = false,
                        httpStatusCode = response.code,
                        latencyMs = latencyMs,
                        errorCode = handledError.errorCode,
                        errorMessage = handledError.userMessage
                    )
                }

                val parsed = parser(response.code, bodyText, latencyMs)
                ApiCallResult(
                    isSuccess = true,
                    data = parsed,
                    httpStatusCode = response.code,
                    latencyMs = latencyMs
                )
            }
        } catch (e: Exception) {
            val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            val handledError = NetworkErrorHandler.fromException(e)
            ApiCallResult(
                isSuccess = false,
                httpStatusCode = handledError.httpStatusCode,
                latencyMs = latencyMs,
                errorCode = handledError.errorCode,
                errorMessage = handledError.userMessage
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        fun defaultOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        /**
         * Parse `GET /poc/v1/health` JSON response strictly according to the VPS POC contract:
         * - status
         * - clearance_available
         * - has_cf_cookie
         * - lucidadl_version
         * - server_time
         */
        fun parseHealthResponse(body: String, latencyMs: Long): HealthCheckInfo {
            val trimmed = body.trim()
            if (!trimmed.startsWith("{")) {
                throw IOException("Format respons health check bukan objek JSON yang valid.")
            }
            val json = JSONObject(trimmed)
            if (!json.has("status") && !json.has("clearance_available") && !json.has("lucidadl_version")) {
                throw IOException("Respons health check tidak memiliki field kontrak /poc/v1/health.")
            }

            val status = PocJsonParser.optNonBlankString(json, "status", "unknown")
            val clearanceAvailable = json.optBoolean("clearance_available", false)
            val hasCfCookie = json.optBoolean("has_cf_cookie", false)
            val lucidadlVersion = PocJsonParser.optNonBlankString(json, "lucidadl_version", "unknown")
            val serverTime = PocJsonParser.optNonBlankString(json, "server_time", "-")
            val isOk = status.equals("ok", ignoreCase = true)

            return HealthCheckInfo(
                status = status,
                clearanceAvailable = clearanceAvailable,
                hasCfCookie = hasCfCookie,
                lucidadlVersion = lucidadlVersion,
                serverTime = serverTime,
                responseTimeMs = latencyMs,
                vpsReachable = isOk,
                workerReachable = false,
                summary = HealthCheckInfo.buildDefaultSummary(
                    status = status,
                    clearanceAvailable = clearanceAvailable,
                    hasCfCookie = hasCfCookie,
                    lucidadlVersion = lucidadlVersion
                )
            )
        }

        /**
         * Parse `POST /poc/v1/search` JSON response strictly according to the VPS POC contract:
         * {
         *   "status": "ok",
         *   "query": "Fly High",
         *   "count": 10,
         *   "results": [
         *     {
         *       "title": "Fly High",
         *       "artist": "Toby Romeo",
         *       "album": "Fly High",
         *       "duration": 166,
         *       "track_url": "https://music.amazon.com/tracks/B0H2D1MCPS"
         *     }
         *   ]
         * }
         */
        fun parseSearchResponse(body: String, fallbackQuery: String = ""): SearchResponse {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) {
                throw IOException("Respons pencarian kosong dari server.")
            }

            val status: String
            val query: String
            val array: JSONArray
            val explicitCount: Int?

            if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                status = PocJsonParser.optNonBlankString(obj, "status", "ok")
                query = PocJsonParser.optNonBlankString(obj, "query", fallbackQuery)
                array = PocJsonParser.extractResultsArray(obj)
                explicitCount = if (obj.has("count") && !obj.isNull("count")) obj.optInt("count", array.length()) else null
            } else if (trimmed.startsWith("[")) {
                status = "ok"
                query = fallbackQuery
                array = JSONArray(trimmed)
                explicitCount = array.length()
            } else {
                throw IOException("Format respons pencarian bukan JSON yang valid.")
            }

            val items = buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val title = PocJsonParser.optNonBlankString(item, "title", "Tanpa Judul")
                    val artist = PocJsonParser.optNonBlankString(item, "artist", "Artis Tidak Diketahui")
                    val album = PocJsonParser.optNullableString(item, "album")
                    val rawDuration = if (item.has("duration") && !item.isNull("duration")) item.get("duration") else null
                    val durationSec = PocJsonParser.parseDurationSeconds(rawDuration)
                    val trackUrl = PocJsonParser.optNonBlankString(item, "track_url", "")
                    val coverUrl = PocJsonParser.optNullableString(item, "cover_url")
                        ?: PocJsonParser.optNullableString(item, "artwork_url")

                    if (trackUrl.isNotBlank()) {
                        add(
                            SearchTrackItem(
                                title = title,
                                artist = artist,
                                album = album,
                                duration = durationSec,
                                trackUrl = trackUrl,
                                coverUrl = coverUrl
                            )
                        )
                    }
                }
            }

            return SearchResponse(
                status = status,
                query = query,
                count = explicitCount ?: items.size,
                results = items
            )
        }
    }
}
