package com.example.data.remote

import com.example.core.security.LogSanitizer
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

data class HandledNetworkError(
    val httpStatusCode: Int? = null,
    val errorCode: String,
    val userMessage: String
)

/**
 * Central handler for HTTP status errors, VPS POC JSON error envelopes, and network exceptions.
 * Ensures secrets, tokens, and full handoff URLs are never exposed in error messages.
 */
object NetworkErrorHandler {

    fun fromHttpResponse(
        httpCode: Int,
        httpMessage: String?,
        rawBody: String?,
        retryAfterHeader: String? = null
    ): HandledNetworkError {
        val parsedPair = parseServerErrorJson(rawBody)
        val serverErrorCode = parsedPair?.first
        val serverMessage = parsedPair?.second?.let { LogSanitizer.redactSensitive(it) }

        val defaultCode = serverErrorCode ?: "http_$httpCode"
        val retryNote = retryAfterHeader?.trim()?.takeIf { it.isNotEmpty() }?.let {
            " Coba lagi setelah $it detik."
        }.orEmpty()

        val friendlyMessage = when (httpCode) {
            400 -> serverMessage ?: "Permintaan tidak valid (HTTP 400). Periksa parameter input."
            401 -> "Autentikasi gagal (HTTP 401): Session Bearer Token tidak valid atau tidak sesuai."
            403 -> serverMessage ?: "Akses ditolak oleh server (HTTP 403)."
            404 -> serverMessage ?: "Endpoint API tidak ditemukan (HTTP 404). Periksa konfigurasi path endpoint."
            405 -> serverMessage ?: "Metode HTTP tidak diizinkan pada endpoint ini (HTTP 405)."
            413 -> "Ukuran permintaan melebihi batas maksimum server (HTTP 413)."
            415 -> "Header Content-Type harus application/json (HTTP 415)."
            422 -> {
                when (serverErrorCode) {
                    "track_token_expired" ->
                        "Token halaman lagu telah kedaluwarsa (HTTP 422). Silakan ulangi pencarian lagu."
                    "track_unavailable" ->
                        "Lagu tidak tersedia di katalog wilayah yang diuji (HTTP 422)."
                    else -> serverMessage ?: "Data lagu tidak dapat diproses oleh server (HTTP 422)."
                }
            }
            429 -> "Batas permintaan (Rate Limit) terlampaui (HTTP 429).$retryNote"
            502 -> serverMessage ?: "Koneksi VPS ke upstream Lucida gagal (HTTP 502 Bad Gateway)."
            503 -> {
                when (serverErrorCode) {
                    "clearance_unavailable" ->
                        "Clearance Lucida belum tersedia di VPS (HTTP 503). Pastikan clearance.json tersedia di VPS."
                    "clearance_expired" ->
                        "Clearance Cloudflare di VPS telah kedaluwarsa (HTTP 503)."
                    else -> serverMessage ?: "Layanan VPS sedang tidak tersedia (HTTP 503)."
                }
            }
            in 500..599 -> serverMessage ?: "Terjadi kesalahan internal pada server VPS (HTTP $httpCode)."
            else -> {
                if (!serverMessage.isNullOrBlank()) {
                    "HTTP $httpCode: $serverMessage"
                } else {
                    val sanitizedFallback = LogSanitizer.redactSensitive(rawBody.orEmpty().take(200)).trim()
                    if (sanitizedFallback.isNotEmpty()) {
                        "HTTP $httpCode: $sanitizedFallback"
                    } else {
                        "HTTP $httpCode ${httpMessage.orEmpty()}".trim()
                    }
                }
            }
        }

        return HandledNetworkError(
            httpStatusCode = httpCode,
            errorCode = defaultCode,
            userMessage = friendlyMessage
        )
    }

    fun fromException(throwable: Throwable): HandledNetworkError {
        val rawMsg = LogSanitizer.redactSensitive(throwable.localizedMessage ?: throwable.message.orEmpty()).trim()
        return when (throwable) {
            is SocketTimeoutException -> HandledNetworkError(
                errorCode = "timeout",
                userMessage = "Waktu koneksi habis (Timeout). Pastikan endpoint HTTPS VPS aktif dan dapat dijangkau."
            )
            is UnknownHostException -> HandledNetworkError(
                errorCode = "unknown_host",
                userMessage = "Host server tidak ditemukan (DNS gagal). Periksa Base URL HTTPS di pengaturan."
            )
            is SSLHandshakeException -> HandledNetworkError(
                errorCode = "tls_handshake_failed",
                userMessage = "Validasi sertifikat TLS/HTTPS gagal. Pastikan endpoint menggunakan sertifikat HTTPS yang valid."
            )
            is SSLException -> HandledNetworkError(
                errorCode = "tls_error",
                userMessage = "Koneksi aman TLS/HTTPS terputus atau ditolak."
            )
            is ConnectException -> HandledNetworkError(
                errorCode = "connection_refused",
                userMessage = "Koneksi ditolak oleh server. Pastikan tunnel/endpoint HTTPS VPS sedang berjalan."
            )
            is IOException -> HandledNetworkError(
                errorCode = "network_io_error",
                userMessage = if (rawMsg.isNotEmpty()) {
                    "Kesalahan jaringan/data: $rawMsg"
                } else {
                    "Gagal berkomunikasi dengan server."
                }
            )
            else -> HandledNetworkError(
                errorCode = "unexpected_error",
                userMessage = if (rawMsg.isNotEmpty()) {
                    "Kesalahan tidak terduga: $rawMsg"
                } else {
                    "Terjadi kesalahan saat memproses permintaan."
                }
            )
        }
    }

    private fun parseServerErrorJson(rawBody: String?): Pair<String?, String?>? {
        val trimmed = rawBody?.trim().orEmpty()
        if (!trimmed.startsWith("{")) return null
        return try {
            val json = JSONObject(trimmed)
            val errCode = PocJsonParser.optNullableString(json, "error")
            val errMsg = PocJsonParser.optNullableString(json, "message")
                ?: PocJsonParser.optNullableString(json, "detail")
            if (errCode == null && errMsg == null) null else (errCode to errMsg)
        } catch (_: Exception) {
            null
        }
    }
}
