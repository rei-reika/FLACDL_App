package com.example.data.remote

import com.example.core.config.TestMode
import com.example.core.config.TesterSessionConfig
import com.example.core.security.UrlValidationResult
import com.example.core.security.UrlValidator

sealed interface ApiConfigValidation {
    data class Valid(val config: ApiConfig) : ApiConfigValidation
    data class Invalid(val errorCode: String, val message: String) : ApiConfigValidation
}

/**
 * Ephemeral API connection configuration derived from [TesterSessionConfig].
 * Token is kept strictly in memory for the active session and never persisted.
 */
data class ApiConfig(
    val mode: TestMode,
    val baseUrl: String,
    val bearerToken: String,
    val healthEndpointPath: String = "/poc/v1/health",
    val searchEndpointPath: String = "/poc/v1/search",
    val handoffEndpointPath: String = "/poc/v1/handoff",
    val diagnosticReportPath: String = "/poc/v1/report",
    val connectTimeoutSeconds: Long = 15L,
    val readTimeoutSeconds: Long = 30L,
    val writeTimeoutSeconds: Long = 15L
) {
    fun buildEndpointUrl(path: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanPath = path.trim().let { if (it.startsWith("/")) it else "/$it" }
        return "$cleanBase$cleanPath"
    }

    companion object {
        fun fromSessionConfig(session: TesterSessionConfig): ApiConfig {
            return ApiConfig(
                mode = session.mode,
                baseUrl = session.vpsBaseUrl.trim().trimEnd('/'),
                bearerToken = session.bearerToken.trim(),
                healthEndpointPath = session.healthEndpointPath,
                searchEndpointPath = session.searchEndpointPath,
                handoffEndpointPath = session.handoffEndpointPath,
                diagnosticReportPath = session.diagnosticReportPath
            )
        }

        /**
         * Validates Live Mode prerequisites before making a network request:
         * 1. Base URL must be a valid HTTPS URL (rejects empty, cleartext HTTP, and localhost/127.0.0.1).
         * 2. Session Bearer Token must not be blank (all VPS POC endpoints require `Authorization: Bearer <token>`).
         */
        fun validateForLiveMode(session: TesterSessionConfig): ApiConfigValidation {
            val normalizedBaseUrl = when (val urlCheck = UrlValidator.validateVpsBaseUrl(session.vpsBaseUrl)) {
                is UrlValidationResult.Invalid -> {
                    return ApiConfigValidation.Invalid(
                        errorCode = "invalid_base_url",
                        message = urlCheck.reason
                    )
                }
                is UrlValidationResult.Valid -> urlCheck.normalizedBaseUrl
            }

            val token = session.bearerToken.trim()
            if (token.isEmpty()) {
                return ApiConfigValidation.Invalid(
                    errorCode = "missing_bearer_token",
                    message = "Bearer Token sesi belum diatur. Masukkan Session Bearer Token di Pengaturan Koneksi untuk menggunakan Live Mode."
                )
            }

            return ApiConfigValidation.Valid(
                fromSessionConfig(session).copy(
                    baseUrl = normalizedBaseUrl,
                    bearerToken = token
                )
            )
        }
    }
}
