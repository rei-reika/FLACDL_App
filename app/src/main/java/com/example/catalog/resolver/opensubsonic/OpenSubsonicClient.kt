package com.example.catalog.resolver.opensubsonic

import com.example.catalog.resolver.DownloadSourceLookupException
import com.example.catalog.resolver.ResolverErrorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Minimal client boundary for documented OpenSubsonic song search operations.
 * Intentionally excludes stream/download execution and bulk library crawling.
 */
interface OpenSubsonicClient {
    suspend fun searchSongs(query: String, songCount: Int): List<OpenSubsonicSongDto>
}

/**
 * OkHttp implementation of [OpenSubsonicClient] for user-owned OpenSubsonic servers.
 *
 * Security & reliability guarantees:
 * - Never logs credentials, tokens, API keys, or sensitive response bodies.
 * - Enforces configured [OpenSubsonicServerConfig.timeoutMs].
 * - Maps HTTP, timeout, network, and Subsonic protocol errors to [DownloadSourceLookupException].
 */
class OkHttpOpenSubsonicClient(
    private val config: OpenSubsonicServerConfig,
    baseClient: OkHttpClient
) : OpenSubsonicClient {

    private val httpClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(config.timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
        .callTimeout(config.timeoutMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun searchSongs(query: String, songCount: Int): List<OpenSubsonicSongDto> =
        withContext(Dispatchers.IO) {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty()) {
                throw DownloadSourceLookupException(
                    errorType = ResolverErrorType.INVALID_REQUEST,
                    message = "OpenSubsonic search query cannot be empty."
                )
            }

            val url = buildSearch3Url(config, cleanQuery, songCount.coerceIn(1, config.maxCandidates.coerceAtLeast(1)))
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    when {
                        response.isRedirect -> throw DownloadSourceLookupException(
                            errorType = ResolverErrorType.INVALID_REQUEST,
                            message = "OpenSubsonic server returned a redirect (HTTP ${response.code}); redirects are disabled to prevent credential leakage."
                        )

                        response.code == 401 || response.code == 403 -> throw DownloadSourceLookupException(
                            errorType = ResolverErrorType.AUTHENTICATION,
                            message = "OpenSubsonic server rejected authentication (HTTP ${response.code})."
                        )

                        response.code == 429 -> throw DownloadSourceLookupException(
                            errorType = ResolverErrorType.RATE_LIMITED,
                            message = "OpenSubsonic server rate-limited request (HTTP 429)."
                        )

                        response.code >= 500 -> throw DownloadSourceLookupException(
                            errorType = ResolverErrorType.UPSTREAM_UNAVAILABLE,
                            message = "OpenSubsonic server error (HTTP ${response.code})."
                        )

                        !response.isSuccessful -> throw DownloadSourceLookupException(
                            errorType = ResolverErrorType.INVALID_REQUEST,
                            message = "OpenSubsonic server returned HTTP ${response.code}."
                        )

                        body.isBlank() -> throw DownloadSourceLookupException(
                            errorType = ResolverErrorType.UNKNOWN,
                            message = "OpenSubsonic server returned an empty response."
                        )

                        else -> parseSearch3Response(body)
                    }
                }
            } catch (e: DownloadSourceLookupException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw DownloadSourceLookupException(
                    errorType = ResolverErrorType.TIMEOUT,
                    message = "OpenSubsonic search timed out after ${config.timeoutMs}ms.",
                    cause = e
                )
            } catch (e: IOException) {
                throw DownloadSourceLookupException(
                    errorType = ResolverErrorType.NETWORK,
                    message = "Network error communicating with OpenSubsonic server.",
                    cause = e
                )
            }
        }

    companion object {
        fun buildSearch3Url(
            config: OpenSubsonicServerConfig,
            query: String,
            songCount: Int
        ): HttpUrl {
            val base = config.serverBaseUrl.trim().trimEnd('/')
            val parsedBase = base.toHttpUrlOrNull()
                ?: throw DownloadSourceLookupException(
                    errorType = ResolverErrorType.INVALID_REQUEST,
                    message = "Configured OpenSubsonic server URL is invalid."
                )

            val scheme = parsedBase.scheme.lowercase()
            if (scheme != "https" && !(scheme == "http" && isAllowedLocalHttpHost(parsedBase.host))) {
                throw DownloadSourceLookupException(
                    errorType = ResolverErrorType.INVALID_REQUEST,
                    message = "HTTPS is required for non-local OpenSubsonic endpoints."
                )
            }

            val builder = parsedBase.newBuilder()
                .addPathSegments("rest/search3.view")
                .addQueryParameter("query", query)
                .addQueryParameter("artistCount", "0")
                .addQueryParameter("albumCount", "0")
                .addQueryParameter("songCount", songCount.toString())
                .addQueryParameter("f", "json")
                .addQueryParameter("v", config.apiVersion)
                .addQueryParameter("c", config.clientName)

            when (val auth = config.auth) {
                is OpenSubsonicAuth.TokenAuth -> {
                    if (auth.username.isBlank() || auth.token.isBlank() || auth.salt.isBlank()) {
                        throw DownloadSourceLookupException(
                            errorType = ResolverErrorType.AUTHENTICATION,
                            message = "OpenSubsonic token credentials are incomplete."
                        )
                    }
                    builder.addQueryParameter("u", auth.username)
                    builder.addQueryParameter("t", auth.token)
                    builder.addQueryParameter("s", auth.salt)
                }

                is OpenSubsonicAuth.ApiKeyAuth -> {
                    if (auth.apiKey.isBlank()) {
                        throw DownloadSourceLookupException(
                            errorType = ResolverErrorType.AUTHENTICATION,
                            message = "OpenSubsonic API key is blank."
                        )
                    }
                    builder.addQueryParameter("apiKey", auth.apiKey)
                }
            }

            return builder.build()
        }

        fun parseSearch3Response(rawJson: String): List<OpenSubsonicSongDto> {
            val root = try {
                JSONObject(rawJson)
            } catch (e: JSONException) {
                throw DownloadSourceLookupException(
                    errorType = ResolverErrorType.UNKNOWN,
                    message = "Malformed JSON response from OpenSubsonic server.",
                    cause = e
                )
            }

            val subsonic = root.optJSONObject("subsonic-response")
                ?: throw DownloadSourceLookupException(
                    errorType = ResolverErrorType.UNKNOWN,
                    message = "Missing subsonic-response envelope in OpenSubsonic payload."
                )

            val status = subsonic.optString("status", "").trim().lowercase()
            if (status == "failed") {
                val errObj = subsonic.optJSONObject("error")
                val code = errObj?.optInt("code", -1) ?: -1
                val msg = errObj?.optString("message", "")?.trim().orEmpty()
                val errorType = when (code) {
                    40, 41, 44, 50 -> ResolverErrorType.AUTHENTICATION
                    10, 20, 30 -> ResolverErrorType.INVALID_REQUEST
                    else -> ResolverErrorType.UPSTREAM_UNAVAILABLE
                }
                throw DownloadSourceLookupException(
                    errorType = errorType,
                    message = if (msg.isNotEmpty()) "OpenSubsonic error ($code): $msg" else "OpenSubsonic error ($code)."
                )
            }
            if (status != "ok") {
                throw DownloadSourceLookupException(
                    errorType = ResolverErrorType.UNKNOWN,
                    message = "Unexpected OpenSubsonic response status."
                )
            }

            val searchResult = subsonic.optJSONObject("searchResult3") ?: return emptyList()
            val songs = searchResult.optJSONArray("song") ?: return emptyList()
            val out = mutableListOf<OpenSubsonicSongDto>()

            for (i in 0 until songs.length()) {
                val obj = songs.optJSONObject(i) ?: continue
                val id = optNonBlank(obj, "id") ?: continue
                val title = optNonBlank(obj, "title") ?: continue
                val artist = optNonBlank(obj, "artist") ?: continue

                out.add(
                    OpenSubsonicSongDto(
                        id = id,
                        title = title,
                        artist = artist,
                        album = optNonBlank(obj, "album"),
                        durationSeconds = optPositiveInt(obj, "duration"),
                        trackNumber = optPositiveInt(obj, "track"),
                        year = optPositiveInt(obj, "year"),
                        isrcs = extractIsrcs(obj),
                        musicBrainzId = optNonBlank(obj, "musicBrainzId"),
                        suffix = optNonBlank(obj, "suffix"),
                        contentType = optNonBlank(obj, "contentType"),
                        bitRate = optPositiveInt(obj, "bitRate"),
                        bitDepth = optPositiveInt(obj, "bitDepth"),
                        samplingRate = optPositiveInt(obj, "samplingRate")
                    )
                )
            }
            return out
        }

        fun isAllowedLocalHttpHost(rawHost: String): Boolean {
            val host = rawHost.trim().lowercase().removePrefix("[").removeSuffix("]")
            if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
            if (host.endsWith(".local") || host.endsWith(".lan")) return true
            if (host.startsWith("10.") || host.startsWith("192.168.")) return true
            if (host.startsWith("172.")) {
                val secondOctet = host.split(".").getOrNull(1)?.toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) return true
            }
            return false
        }

        private fun optNonBlank(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optString(key, "").trim()
                .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }

        private fun optPositiveInt(obj: JSONObject, key: String): Int? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val v = obj.optInt(key, -1)
            return if (v > 0) v else null
        }

        private fun extractIsrcs(obj: JSONObject): List<String> {
            if (!obj.has("isrc") || obj.isNull("isrc")) return emptyList()
            val arr = obj.optJSONArray("isrc")
            if (arr != null) {
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i, "").trim()
                    if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) {
                        list.add(value)
                    }
                }
                return list
            }
            val single = optNonBlank(obj, "isrc")
            return listOfNotNull(single)
        }
    }
}
