package com.example.data.remote

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure Kotlin duration formatting and JSON parsing helpers for the POC API contract.
 */
object PocJsonParser {

    /**
     * Format a duration in seconds (e.g. 166) into "02:46" (MM:SS) or "HH:MM:SS" if >= 1 hour.
     */
    fun formatDurationSeconds(seconds: Int?): String {
        if (seconds == null || seconds < 0) return "--:--"
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            "%02d:%02d:%02d".format(hrs, mins, secs)
        } else {
            "%02d:%02d".format(mins, secs)
        }
    }

    /**
     * Parse either an integer number of seconds (e.g. 166) or a legacy "MM:SS" string into seconds.
     */
    fun parseDurationSeconds(raw: Any?): Int? {
        return when (raw) {
            null, JSONObject.NULL -> null
            is Number -> raw.toInt().takeIf { it >= 0 }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return null
                trimmed.toIntOrNull()?.takeIf { it >= 0 } ?: run {
                    if (":" in trimmed) {
                        val parts = trimmed.split(":")
                        if (parts.all { it.all(Char::isDigit) && it.isNotEmpty() }) {
                            when (parts.size) {
                                2 -> parts[0].toInt() * 60 + parts[1].toInt()
                                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toInt()
                                else -> null
                            }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
            else -> null
        }
    }

    /**
     * Read a nullable JSON string field without turning `JSONObject.NULL` into the literal `"null"`.
     */
    fun optNullableString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.optString(key, "").trim()
        return if (value.isEmpty() || value.equals("null", ignoreCase = true)) null else value
    }

    fun optNonBlankString(json: JSONObject, key: String, fallback: String): String {
        return optNullableString(json, key) ?: fallback
    }

    fun extractResultsArray(root: JSONObject): JSONArray {
        return when {
            root.has("results") && !root.isNull("results") -> root.getJSONArray("results")
            root.has("tracks") && !root.isNull("tracks") -> root.getJSONArray("tracks")
            root.has("items") && !root.isNull("items") -> root.getJSONArray("items")
            root.has("data") && !root.isNull("data") -> root.getJSONArray("data")
            else -> JSONArray()
        }
    }
}
