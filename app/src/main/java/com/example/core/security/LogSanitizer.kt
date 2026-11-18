package com.example.core.security

import java.net.URI

/**
 * Sanitizes URLs, tokens, cookies, and log payloads so secrets or complete
 * handoff/download URLs are never exposed in logs or persistent UI history.
 */
object LogSanitizer {

    private val bearerRegex = Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+")
    private val cookieRegex = Regex("(?i)(cookie|set-cookie)\\s*[:=]\\s*[^;\\r\\n]+")
    private val querySecretRegex = Regex("([?&](token|key|auth|sig|signature|expires|secret)=)[^&\\s]+", RegexOption.IGNORE_CASE)

    fun maskTokenPreview(token: String): String {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return "Belum diatur"
        if (trimmed.length <= 4) return "••••"
        return "••••${trimmed.takeLast(4)}"
    }

    fun maskUrlForDisplay(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return "-"
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: "unknown-host"
            val portPart = if (uri.port != -1) ":${uri.port}" else ""
            val pathSegments = (uri.path ?: "").split('/').filter { it.isNotBlank() }
            val maskedPath = when {
                pathSegments.isEmpty() -> ""
                pathSegments.size == 1 -> "/${pathSegments.first()}"
                else -> "/${pathSegments.first()}/•••"
            }
            "$scheme://$host$portPart$maskedPath"
        } catch (_: Exception) {
            "[URL tersembunyi]"
        }
    }

    fun redactSensitive(message: String): String {
        return message
            .replace(bearerRegex, "Bearer [REDACTED]")
            .replace(cookieRegex, "$1=[REDACTED]")
            .replace(querySecretRegex, "$1[REDACTED]")
    }
}
