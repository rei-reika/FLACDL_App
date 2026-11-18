package com.example.data.model

import com.example.data.remote.PocJsonParser
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

enum class ConnectionStatus(val label: String, val compactLabel: String) {
    CONNECTED("Connected", "Connected"),
    DISCONNECTED("Disconnected", "Offline"),
    TESTING("Testing", "Testing")
}

enum class WorkerJobStatus(val wireValue: String) {
    METADATA("metadata"),
    COMPLETED("completed"),
    FAILED("failed"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): WorkerJobStatus {
            return when (value?.trim()?.lowercase()) {
                "metadata", "processing", "preparing" -> METADATA
                "completed", "done", "ready", "finished" -> COMPLETED
                "failed", "error" -> FAILED
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Unified API result wrapper (`ApiResult` / `ApiCallResult`) used across the app.
 */
data class ApiCallResult<T>(
    val isSuccess: Boolean,
    val data: T? = null,
    val httpStatusCode: Int? = null,
    val latencyMs: Long = 0L,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val isSimulated: Boolean = false
)

typealias ApiResult<T> = ApiCallResult<T>

/**
 * Actual contract for `GET /poc/v1/health`:
 * - status
 * - clearance_available
 * - has_cf_cookie
 * - lucidadl_version
 * - server_time
 * - responseTimeMs (measured client-side latency)
 */
@JsonClass(generateAdapter = true)
data class HealthCheckInfo(
    @Json(name = "status") val status: String = "unknown",
    @Json(name = "clearance_available") val clearanceAvailable: Boolean = false,
    @Json(name = "has_cf_cookie") val hasCfCookie: Boolean = false,
    @Json(name = "lucidadl_version") val lucidadlVersion: String = "-",
    @Json(name = "server_time") val serverTime: String = "-",
    val responseTimeMs: Long = 0L,
    val vpsReachable: Boolean = status.equals("ok", ignoreCase = true) || status.startsWith("ok", ignoreCase = true),
    val workerReachable: Boolean = false,
    val summary: String = buildDefaultSummary(
        status = status,
        clearanceAvailable = clearanceAvailable,
        hasCfCookie = hasCfCookie,
        lucidadlVersion = lucidadlVersion
    )
) {
    companion object {
        fun buildDefaultSummary(
            status: String,
            clearanceAvailable: Boolean,
            hasCfCookie: Boolean,
            lucidadlVersion: String
        ): String {
            return if (status.equals("ok", ignoreCase = true) || status.startsWith("ok", ignoreCase = true)) {
                val clearanceNote = if (clearanceAvailable) {
                    "Clearance siap (CF cookie: ${if (hasCfCookie) "ada" else "tidak ada"})"
                } else {
                    "Clearance tidak tersedia di VPS"
                }
                "VPS Health: $status • LucidaDL v$lucidadlVersion • $clearanceNote"
            } else {
                "VPS merespons dengan status: $status"
            }
        }
    }
}

@JsonClass(generateAdapter = true)
data class SearchRequest(
    @Json(name = "query") val query: String
)

/**
 * Actual contract for `POST /poc/v1/search`:
 * {
 *   "status": "ok",
 *   "query": "Fly High",
 *   "count": 10,
 *   "results": [ ... ]
 * }
 */
@JsonClass(generateAdapter = true)
data class SearchResponse(
    @Json(name = "status") val status: String = "ok",
    @Json(name = "query") val query: String = "",
    @Json(name = "count") val count: Int = 0,
    @Json(name = "results") val results: List<SearchTrackItem> = emptyList()
)

/**
 * Single track item inside `POST /poc/v1/search` `results`:
 * {
 *   "title": "Fly High",
 *   "artist": "Toby Romeo",
 *   "album": "Fly High",
 *   "duration": 166,
 *   "track_url": "https://music.amazon.com/tracks/B0H2D1MCPS"
 * }
 */
@JsonClass(generateAdapter = true)
data class SearchTrackItem(
    @Json(name = "title") val title: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "album") val album: String? = null,
    @Json(name = "duration") val duration: Int? = null,
    @Json(name = "track_url") val trackUrl: String,
    @Json(name = "cover_url") val coverUrl: String? = null
) {
    /**
     * Album display text with clean fallback if upstream returned `null` or empty string.
     */
    val albumDisplay: String
        get() = album?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: "-"

    /**
     * Duration formatted in minutes and seconds (e.g. 166 -> "02:46").
     */
    val formattedDuration: String
        get() = PocJsonParser.formatDurationSeconds(duration)
}

@JsonClass(generateAdapter = true)
data class HandoffRequest(
    @Json(name = "track_url") val trackUrl: String
)

@JsonClass(generateAdapter = true)
data class HandoffResponse(
    @Json(name = "status") val status: String,
    @Json(name = "test_id") val testId: String,
    @Json(name = "handoff_id") val handoffId: String,
    @Json(name = "server") val server: String,
    @Json(name = "country") val country: String,
    @Json(name = "poll_url") val pollUrl: String,
    @Json(name = "download_url") val downloadUrl: String,
    @Json(name = "title") val title: String,
    @Json(name = "artist") val artist: String,
    @Json(name = "track_url") val trackUrl: String
)

data class WorkerPollResponse(
    val status: WorkerJobStatus,
    val rawStatus: String,
    val server: String,
    val progressPercent: Int? = null,
    val message: String? = null,
    val downloadUrl: String? = null,
    val fileName: String? = null,
    val contentLengthBytes: Long? = null
)

data class StatusHistoryEntry(
    val timestampFormatted: String,
    val status: WorkerJobStatus,
    val rawStatus: String,
    val httpStatus: Int?,
    val latencyMs: Long,
    val note: String
)

enum class DownloadStage(val label: String) {
    IDLE("Siap"),
    CONNECTING("Menghubungkan ke Worker"),
    DOWNLOADING("Mengunduh Langsung dari Worker"),
    VALIDATING("Memvalidasi File FLAC"),
    COMPLETED("Selesai & Tervalidasi"),
    CANCELLED("Dibatalkan"),
    ERROR("Gagal")
}

data class FlacValidationResult(
    val httpStatusValid: Boolean,
    val httpStatusCode: Int?,
    val fileSizeBytes: Long,
    val hasValidExtension: Boolean,
    val hasFlacSignature: Boolean,
    val streamInfoSummary: String,
    val metadataSummary: String,
    val hasCoverArtwork: Boolean,
    val coverArtworkSummary: String,
    val sha256Fingerprint: String,
    val isOverallValid: Boolean,
    val notes: List<String>
)

data class DownloadProgressState(
    val trackTitle: String = "",
    val trackArtist: String = "",
    val workerServer: String = "",
    val stage: DownloadStage = DownloadStage.IDLE,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long? = null,
    val speedBytesPerSec: Long = 0L,
    val elapsedMillis: Long = 0L,
    val httpStatusCode: Int? = null,
    val localStagingPath: String? = null,
    val suggestedFileName: String = "track.flac",
    val errorMessage: String? = null,
    val validationResult: FlacValidationResult? = null,
    val exportedLocationNote: String? = null
)

@JsonClass(generateAdapter = true)
data class DiagnosticReportRequest(
    @Json(name = "test_id") val testId: String,
    @Json(name = "handoff_id") val handoffId: String,
    @Json(name = "server") val server: String,
    @Json(name = "download_success") val downloadSuccess: Boolean,
    @Json(name = "http_status") val httpStatus: Int?,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "flac_signature_valid") val flacSignatureValid: Boolean,
    @Json(name = "sha256_fingerprint") val sha256Fingerprint: String,
    @Json(name = "duration_ms") val durationMs: Long
)

data class DashboardMetrics(
    val vpsStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val workerStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val totalTests: Int = 0,
    val successfulDownloads: Int = 0,
    val failedDownloads: Int = 0,
    val lastHttpStatus: Int? = null,
    val lastLatencyMs: Long? = null,
    val lastStatusNote: String = "Belum ada pengujian koneksi.",
    val latestHealthInfo: HealthCheckInfo? = null,
    val isHealthSimulated: Boolean = false
)
