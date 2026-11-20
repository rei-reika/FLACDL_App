package com.example.catalog.resolver.opensubsonic

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

enum class OpenSubsonicAuthType {
    TOKEN,
    API_KEY
}

/**
 * User-configured profile for an owned, explicitly authorized OpenSubsonic-compatible server.
 * Credentials are redacted in [toString] and must never be logged or persisted in plaintext.
 */
data class OpenSubsonicServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val serverUrl: String,
    val username: String = "",
    val credential: String,
    val authType: OpenSubsonicAuthType = OpenSubsonicAuthType.TOKEN,
    val salt: String? = null,
    val enabled: Boolean = true,
    val timeoutMs: Long = 10_000L,
    val maxCandidates: Int = 10
) {
    override fun toString(): String =
        "OpenSubsonicServerProfile(id=$id, name=$name, serverUrl=$serverUrl, username=$username, authType=$authType, enabled=$enabled, credential=[REDACTED])"

    fun toServerConfig(): OpenSubsonicServerConfig {
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val resolvedAuth = when (authType) {
            OpenSubsonicAuthType.API_KEY -> OpenSubsonicAuth.ApiKeyAuth(apiKey = credential.trim())
            OpenSubsonicAuthType.TOKEN -> {
                val explicitSalt = salt?.trim()?.takeIf { it.isNotEmpty() }
                if (explicitSalt != null) {
                    OpenSubsonicAuth.TokenAuth(
                        username = username.trim(),
                        token = credential.trim(),
                        salt = explicitSalt
                    )
                } else {
                    // Subsonic 1.13+ salted MD5 token derived from password without sending plaintext password
                    val generatedSalt = generateSalt()
                    val derivedToken = md5Hex(credential + generatedSalt)
                    OpenSubsonicAuth.TokenAuth(
                        username = username.trim(),
                        token = derivedToken,
                        salt = generatedSalt
                    )
                }
            }
        }
        return OpenSubsonicServerConfig(
            serverBaseUrl = normalizedUrl,
            auth = resolvedAuth,
            timeoutMs = timeoutMs.coerceAtLeast(1_000L),
            maxCandidates = maxCandidates.coerceAtLeast(1),
            providerId = "${OpenSubsonicServerConfig.DEFAULT_PROVIDER_ID}:$id",
            displayName = name.trim().ifEmpty { "OpenSubsonic Library" }
        )
    }

    companion object {
        private val secureRandom = SecureRandom()

        private fun generateSalt(): String {
            val bytes = ByteArray(12)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

sealed class OpenSubsonicConfigValidationResult {
    data class Valid(val normalizedProfile: OpenSubsonicServerProfile) : OpenSubsonicConfigValidationResult()
    data class Invalid(val errors: List<String>) : OpenSubsonicConfigValidationResult()
}

/**
 * Validates [OpenSubsonicServerProfile] before persistence or network access.
 * Enforces HTTPS for all non-local hosts and never silently downgrades HTTPS.
 */
object OpenSubsonicConfigValidator {

    fun validate(profile: OpenSubsonicServerProfile): OpenSubsonicConfigValidationResult {
        val errors = mutableListOf<String>()

        val cleanId = profile.id.trim()
        val cleanName = profile.name.trim()
        val cleanUrl = profile.serverUrl.trim().trimEnd('/')
        val cleanUsername = profile.username.trim()
        val cleanCredential = profile.credential.trim()
        val cleanSalt = profile.salt?.trim()?.takeIf { it.isNotEmpty() }

        if (cleanId.isEmpty()) {
            errors.add("Server ID cannot be blank.")
        }
        if (cleanName.isEmpty()) {
            errors.add("Server name cannot be blank.")
        }

        val parsedUrl = cleanUrl.toHttpUrlOrNull()
        if (cleanUrl.isEmpty() || parsedUrl == null) {
            errors.add("Server URL must be a valid HTTP or HTTPS URL.")
        } else {
            val scheme = parsedUrl.scheme.lowercase()
            if (scheme != "https" && !(scheme == "http" && OkHttpOpenSubsonicClient.isAllowedLocalHttpHost(parsedUrl.host))) {
                errors.add("HTTPS is required for non-local OpenSubsonic server URLs.")
            }
        }

        if (profile.authType == OpenSubsonicAuthType.TOKEN && cleanUsername.isEmpty()) {
            errors.add("Username is required for token authentication.")
        }
        if (cleanCredential.isEmpty()) {
            errors.add("Authentication credential cannot be blank.")
        }
        if (profile.timeoutMs < 1_000L) {
            errors.add("Timeout must be at least 1000 ms.")
        }
        if (profile.maxCandidates < 1) {
            errors.add("Max candidates must be at least 1.")
        }

        return if (errors.isNotEmpty()) {
            OpenSubsonicConfigValidationResult.Invalid(errors)
        } else {
            OpenSubsonicConfigValidationResult.Valid(
                profile.copy(
                    id = cleanId,
                    name = cleanName,
                    serverUrl = cleanUrl,
                    username = cleanUsername,
                    credential = cleanCredential,
                    salt = cleanSalt
                )
            )
        }
    }
}
