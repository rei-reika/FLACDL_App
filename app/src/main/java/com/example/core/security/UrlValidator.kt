package com.example.core.security

import java.net.URI

sealed interface UrlValidationResult {
    data class Valid(val normalizedBaseUrl: String) : UrlValidationResult
    data class Invalid(val reason: String) : UrlValidationResult
}

/**
 * Validates VPS and Worker URLs for Live Mode:
 * - Refuses unconfigured / empty URLs.
 * - Rejects cleartext HTTP for public APIs.
 * - Warns against assuming Android localhost equals VPS localhost.
 */
object UrlValidator {

    fun validateVpsBaseUrl(rawUrl: String): UrlValidationResult {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            return UrlValidationResult.Invalid(
                "Base URL VPS belum dikonfigurasi. Atur URL HTTPS di Pengaturan Koneksi terlebih dahulu."
            )
        }

        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return UrlValidationResult.Invalid("Format Base URL tidak valid.")
        }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: ""

        if (host.isEmpty()) {
            return UrlValidationResult.Invalid("Host URL VPS tidak ditemukan. Contoh: https://api.vps-anda.example")
        }

        if (host == "localhost" || host == "127.0.0.1" || host == "::1") {
            return UrlValidationResult.Invalid(
                "Host localhost/127.0.0.1 merujuk ke perangkat Android, bukan VPS. Gunakan domain HTTPS atau tunnel VPS."
            )
        }

        if (scheme != "https") {
            return UrlValidationResult.Invalid(
                "Cleartext HTTP tidak diizinkan untuk API publik. Gunakan protokol HTTPS."
            )
        }

        return UrlValidationResult.Valid(trimmed)
    }

    fun validateWorkerHttpsUrl(rawUrl: String): UrlValidationResult {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return UrlValidationResult.Invalid("URL worker kosong.")
        }
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return UrlValidationResult.Invalid("Format URL worker tidak valid.")
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: ""
        if (host.isEmpty()) {
            return UrlValidationResult.Invalid("Host worker tidak valid.")
        }
        if (scheme != "https") {
            return UrlValidationResult.Invalid("URL worker harus menggunakan HTTPS.")
        }
        return UrlValidationResult.Valid(trimmed)
    }
}
