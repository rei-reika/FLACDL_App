package com.example.core.config

import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TestMode(val label: String) {
    DEMO("Demo Mode"),
    LIVE("Live Mode")
}

/**
 * Ephemeral session configuration.
 * Bearer token is strictly retained in RAM for the current test session only.
 */
data class TesterSessionConfig(
    val mode: TestMode = TestMode.DEMO,
    val vpsBaseUrl: String = "",
    val healthEndpointPath: String = "/poc/v1/health",
    val searchEndpointPath: String = "/poc/v1/search",
    val handoffEndpointPath: String = "/poc/v1/handoff",
    val diagnosticReportPath: String = "/poc/v1/report",
    val diagnosticReportContractVerified: Boolean = false,
    val bearerToken: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME
)

class SessionConfigRepository {
    private val _config = MutableStateFlow(TesterSessionConfig())
    val config: StateFlow<TesterSessionConfig> = _config.asStateFlow()

    fun setMode(mode: TestMode) {
        _config.update { it.copy(mode = mode) }
    }

    fun updateVpsBaseUrl(url: String) {
        _config.update { it.copy(vpsBaseUrl = url) }
    }

    fun updateHealthEndpointPath(path: String) {
        val cleaned = path.trim().ifBlank { "/poc/v1/health" }
        val normalized = if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        _config.update { it.copy(healthEndpointPath = normalized) }
    }

    fun updateSearchEndpointPath(path: String) {
        val cleaned = path.trim().ifBlank { "/poc/v1/search" }
        val normalized = if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        _config.update { it.copy(searchEndpointPath = normalized) }
    }

    fun updateHandoffEndpointPath(path: String) {
        val cleaned = path.trim().ifBlank { "/poc/v1/handoff" }
        val normalized = if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        _config.update { it.copy(handoffEndpointPath = normalized) }
    }

    fun updateDiagnosticReportSettings(path: String, contractVerified: Boolean) {
        val cleaned = path.trim().ifBlank { "/poc/v1/report" }
        val normalized = if (cleaned.startsWith("/")) cleaned else "/$cleaned"
        _config.update {
            it.copy(
                diagnosticReportPath = normalized,
                diagnosticReportContractVerified = contractVerified
            )
        }
    }

    fun updateSessionToken(token: String) {
        _config.update { it.copy(bearerToken = token) }
    }

    fun clearSessionToken() {
        _config.update { it.copy(bearerToken = "") }
    }
}
