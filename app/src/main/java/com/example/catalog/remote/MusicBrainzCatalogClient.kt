package com.example.catalog.remote

import com.example.catalog.model.MusicBrainzArtistEnrichment
import com.example.catalog.model.MusicBrainzRecordingEnrichment
import com.example.catalog.model.MusicBrainzReleaseGroupEnrichment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

interface MusicBrainzEnrichmentProvider {
    suspend fun findArtistEnrichment(artistName: String): MusicBrainzArtistEnrichment?
    suspend fun findReleaseGroupEnrichment(
        artistName: String,
        albumTitle: String,
        musicBrainzArtistId: String? = null
    ): MusicBrainzReleaseGroupEnrichment?
    suspend fun findRecordingEnrichment(
        artistName: String,
        trackTitle: String
    ): MusicBrainzRecordingEnrichment?
}

/**
 * Secondary MusicBrainz metadata enrichment client.
 *
 * Compliance rules:
 * - Descriptive User-Agent identifying the prototype.
 * - Strict 1 req/sec rate limiting via [CatalogRateLimiter.forMusicBrainz].
 * - Exponential backoff retry for HTTP 503 and transient I/O failures.
 * - Never generates or infers Amazon Music URLs.
 */
class OkHttpMusicBrainzCatalogClient(
    private val client: OkHttpClient,
    private val rateLimiter: CatalogRateLimiter = CatalogRateLimiter.forMusicBrainz(),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val maxRetries: Int = 3,
    private val baseBackoffMs: Long = 1_500L,
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) }
) : MusicBrainzEnrichmentProvider {

    override suspend fun findArtistEnrichment(artistName: String): MusicBrainzArtistEnrichment? {
        val clean = artistName.trim()
        if (clean.isEmpty()) return null
        val url = "$BASE_URL/artist".toHttpUrl().newBuilder()
            .addQueryParameter("query", clean)
            .addQueryParameter("limit", "3")
            .addQueryParameter("fmt", "json")
            .build()
        val body = executeWithBackoff(url.toString())
        return parseArtistEnrichment(body)
    }

    override suspend fun findReleaseGroupEnrichment(
        artistName: String,
        albumTitle: String,
        musicBrainzArtistId: String?
    ): MusicBrainzReleaseGroupEnrichment? {
        val cleanTitle = albumTitle.trim()
        val cleanArtist = artistName.trim()
        if (cleanTitle.isEmpty()) return null
        val query = if (!musicBrainzArtistId.isNullOrBlank()) {
            """releasegroup:"$cleanTitle" AND arid:$musicBrainzArtistId"""
        } else {
            """releasegroup:"$cleanTitle" AND artist:"$cleanArtist""""
        }
        val url = "$BASE_URL/release-group".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", "3")
            .addQueryParameter("fmt", "json")
            .build()
        val body = executeWithBackoff(url.toString())
        return parseReleaseGroupEnrichment(body)
    }

    override suspend fun findRecordingEnrichment(
        artistName: String,
        trackTitle: String
    ): MusicBrainzRecordingEnrichment? {
        val cleanTrack = trackTitle.trim()
        val cleanArtist = artistName.trim()
        if (cleanTrack.isEmpty()) return null
        val query = """recording:"$cleanTrack" AND artist:"$cleanArtist""""
        val url = "$BASE_URL/recording".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", "3")
            .addQueryParameter("fmt", "json")
            .build()
        val body = executeWithBackoff(url.toString())
        return parseRecordingEnrichment(body)
    }

    private suspend fun executeWithBackoff(url: String): String = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        val attempts = maxRetries.coerceAtLeast(1)
        for (attempt in 0 until attempts) {
            rateLimiter.acquirePermit()
            val request = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        return@withContext body
                    }
                    if ((response.code == 503 || response.code == 429 || response.code >= 500) && attempt < attempts - 1) {
                        val backoffMs = baseBackoffMs * (1L shl attempt)
                        delayFn(backoffMs)
                        return@use
                    }
                    throw IOException("MusicBrainz API returned HTTP ${response.code}.")
                }
            } catch (e: IOException) {
                lastError = e
                if (attempt < attempts - 1) {
                    val backoffMs = baseBackoffMs * (1L shl attempt)
                    delayFn(backoffMs)
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: IOException("MusicBrainz API request failed after $attempts attempts.")
    }

    companion object {
        private const val BASE_URL = "https://musicbrainz.org/ws/2"
        const val DEFAULT_USER_AGENT = "FLACDLDirectTester/1.0 ( local-catalog-prototype )"

        fun parseArtistEnrichment(rawJson: String): MusicBrainzArtistEnrichment? {
            val arr = JSONObject(rawJson).optJSONArray("artists") ?: return null
            if (arr.length() == 0) return null
            val top = arr.optJSONObject(0) ?: return null
            val mbid = top.optString("id", "").trim()
            if (mbid.isEmpty()) return null
            val score = top.optInt("score", 0)
            if (score in 1..69) return null
            return MusicBrainzArtistEnrichment(
                musicBrainzArtistId = mbid,
                country = top.optString("country", "").trim().takeIf { it.isNotEmpty() },
                disambiguation = top.optString("disambiguation", "").trim().takeIf { it.isNotEmpty() }
            )
        }

        fun parseReleaseGroupEnrichment(rawJson: String): MusicBrainzReleaseGroupEnrichment? {
            val arr = JSONObject(rawJson).optJSONArray("release-groups") ?: return null
            if (arr.length() == 0) return null
            val top = arr.optJSONObject(0) ?: return null
            val rgid = top.optString("id", "").trim()
            if (rgid.isEmpty()) return null
            val score = top.optInt("score", 0)
            if (score in 1..69) return null
            return MusicBrainzReleaseGroupEnrichment(
                musicBrainzReleaseGroupId = rgid,
                firstReleaseDate = top.optString("first-release-date", "").trim().takeIf { it.isNotEmpty() }
            )
        }

        fun parseRecordingEnrichment(rawJson: String): MusicBrainzRecordingEnrichment? {
            val arr = JSONObject(rawJson).optJSONArray("recordings") ?: return null
            if (arr.length() == 0) return null
            val top = arr.optJSONObject(0) ?: return null
            val recid = top.optString("id", "").trim()
            if (recid.isEmpty()) return null
            val score = top.optInt("score", 0)
            if (score in 1..69) return null
            val lengthMs = if (top.has("length") && !top.isNull("length")) {
                top.optLong("length", -1L).takeIf { it > 0L }
            } else {
                null
            }
            val isrcs = top.optJSONArray("isrcs")
            val isrc = if (isrcs != null && isrcs.length() > 0) {
                isrcs.optString(0, "").trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
            return MusicBrainzRecordingEnrichment(
                musicBrainzRecordingId = recid,
                durationMs = lengthMs,
                isrc = isrc
            )
        }
    }
}
