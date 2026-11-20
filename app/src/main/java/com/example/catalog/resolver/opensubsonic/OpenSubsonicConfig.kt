package com.example.catalog.resolver.opensubsonic

/**
 * Injected authentication credentials for a user-owned OpenSubsonic-compatible server.
 * Credentials are never hardcoded and `toString()` redacts all secret values.
 */
sealed interface OpenSubsonicAuth {
    data class TokenAuth(
        val username: String,
        val token: String,
        val salt: String
    ) : OpenSubsonicAuth {
        override fun toString(): String = "TokenAuth(username=$username, token=[REDACTED], salt=[REDACTED])"
    }

    data class ApiKeyAuth(
        val apiKey: String
    ) : OpenSubsonicAuth {
        override fun toString(): String = "ApiKeyAuth(apiKey=[REDACTED])"
    }
}

/**
 * Configuration for a user-owned, explicitly authorized OpenSubsonic-compatible server
 * (e.g., Navidrome, Gonic, Airsonic-Advanced).
 */
data class OpenSubsonicServerConfig(
    val serverBaseUrl: String,
    val auth: OpenSubsonicAuth,
    val clientName: String = "FLACDL",
    val apiVersion: String = "1.16.1",
    val timeoutMs: Long = 10_000L,
    val maxCandidates: Int = 10,
    val providerId: String = DEFAULT_PROVIDER_ID,
    val displayName: String = "OpenSubsonic Library"
) {
    override fun toString(): String =
        "OpenSubsonicServerConfig(serverBaseUrl=$serverBaseUrl, providerId=$providerId, timeoutMs=$timeoutMs, auth=[REDACTED])"

    companion object {
        const val DEFAULT_PROVIDER_ID = "opensubsonic"
    }
}

/**
 * Normalized representation of a song returned by OpenSubsonic `search3`.
 */
data class OpenSubsonicSongDto(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationSeconds: Int? = null,
    val trackNumber: Int? = null,
    val year: Int? = null,
    val isrcs: List<String> = emptyList(),
    val musicBrainzId: String? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int? = null,
    val bitDepth: Int? = null,
    val samplingRate: Int? = null
)
