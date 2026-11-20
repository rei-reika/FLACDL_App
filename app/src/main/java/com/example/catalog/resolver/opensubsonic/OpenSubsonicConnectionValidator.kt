package com.example.catalog.resolver.opensubsonic

import com.example.catalog.resolver.DownloadSourceLookupException
import com.example.catalog.resolver.ResolverErrorType

data class OpenSubsonicPingResponse(
    val serverVersion: String? = null,
    val serverType: String? = null,
    val openSubsonic: Boolean = false
)

sealed class OpenSubsonicConnectionResult {
    data class Connected(
        val serverVersion: String?,
        val serverType: String?,
        val openSubsonic: Boolean,
        val latencyMs: Long
    ) : OpenSubsonicConnectionResult()

    data class InvalidConfig(val errors: List<String>) : OpenSubsonicConnectionResult()
    data class AuthenticationFailed(val message: String) : OpenSubsonicConnectionResult()
    data class Timeout(val message: String) : OpenSubsonicConnectionResult()
    data class NetworkError(val message: String) : OpenSubsonicConnectionResult()
    data class ServerError(val message: String) : OpenSubsonicConnectionResult()
}

/**
 * Reusable connection and authentication validator for user-configured OpenSubsonic servers.
 * Uses documented `rest/ping.view` (never initiates downloads and never logs credentials).
 */
class OpenSubsonicConnectionValidator(
    private val clientFactory: (OpenSubsonicServerConfig) -> OpenSubsonicClient,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    suspend fun validateConnection(profile: OpenSubsonicServerProfile): OpenSubsonicConnectionResult {
        val normalized = when (val check = OpenSubsonicConfigValidator.validate(profile)) {
            is OpenSubsonicConfigValidationResult.Invalid ->
                return OpenSubsonicConnectionResult.InvalidConfig(check.errors)
            is OpenSubsonicConfigValidationResult.Valid -> check.normalizedProfile
        }

        val config = normalized.toServerConfig()
        val startedAt = clockMs()
        return try {
            val ping = clientFactory(config).ping()
            val latencyMs = (clockMs() - startedAt).coerceAtLeast(0L)
            OpenSubsonicConnectionResult.Connected(
                serverVersion = ping.serverVersion,
                serverType = ping.serverType,
                openSubsonic = ping.openSubsonic,
                latencyMs = latencyMs
            )
        } catch (e: DownloadSourceLookupException) {
            when (e.errorType) {
                ResolverErrorType.AUTHENTICATION ->
                    OpenSubsonicConnectionResult.AuthenticationFailed(e.message ?: "Authentication failed.")
                ResolverErrorType.TIMEOUT ->
                    OpenSubsonicConnectionResult.Timeout(e.message ?: "Connection timed out.")
                ResolverErrorType.NETWORK ->
                    OpenSubsonicConnectionResult.NetworkError(e.message ?: "Network error.")
                ResolverErrorType.INVALID_REQUEST ->
                    OpenSubsonicConnectionResult.InvalidConfig(listOf(e.message ?: "Invalid server configuration."))
                ResolverErrorType.RATE_LIMITED,
                ResolverErrorType.UPSTREAM_UNAVAILABLE,
                ResolverErrorType.UNKNOWN ->
                    OpenSubsonicConnectionResult.ServerError(e.message ?: "OpenSubsonic server error.")
            }
        } catch (e: Exception) {
            OpenSubsonicConnectionResult.ServerError(e.message ?: "Unexpected validation error.")
        }
    }
}
