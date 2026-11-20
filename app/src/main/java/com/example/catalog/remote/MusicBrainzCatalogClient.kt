package com.example.catalog.remote

import com.example.catalog.enrichment.MusicBrainzRecordingCandidate
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

class MusicBrainzRateLimitException(
    message: String,
    val httpCode: Int
) : IOException(message)

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

    /**
     * Searches MusicBrainz recording candidates for TrackMatcher verification.
     * Defaults to wrapping [findRecordingEnrichment] for backward compatibility with existing fakes.
     */
    suspend fun searchRecordingCandidates(
        artistName: String,
        trackTitle: String,
        limit: Int = 5
    ): List<MusicBrainzRecordingCandidate> {
        val single = findRecordingEnrichment(artistName, trackTitle) ?: return emptyList()
        return listOf(
            MusicBrainzRecordingCandidate(
                musicBrainzRecordingId = single.musicBrainzRecordingId,
                title = trackTitle,
                artistName = artistName,
                durationMs = single.durationMs,
                isrcs = listOfNotNull(single.isrc)
            )
        )
    }

    /**
     * Performs `/ws/2/recording/{mbid}?inc=isrcs&fmt=json` detail lookup when a verified
     * recording search result did not include ISRC data.
     */
    suspend fun fetchRecordingIsrcs(recordingMbid: String): List<String> = emptyList()
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

    override suspend fun searchRecordingCandidates(
        artistName: String,
        trackTitle: String,
        limit: Int
    ): List<MusicBrainzRecordingCandidate> {
        val cleanTrack = trackTitle.trim()
        val cleanArtist = artistName.trim()
        if (cleanTrack.isEmpty() || cleanArtist.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, 10)
        val query = """recording:"$cleanTrack" AND artist:"$cleanArtist""""
        val url = "$BASE_URL/recording".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("limit", safeLimit.toString())
            .addQueryParameter("fmt", "json")
            .build()
        val body = executeWithBackoff(url.toString())
        return parseRecordingCandidates(body)
    }

    override suspend fun fetchRecordingIsrcs(recordingMbid: String): List<String> {
        val cleanMbid = recordingMbid.trim()
        if (cleanMbid.isEmpty()) return emptyList()
        val url = "$BASE_URL/recording/$cleanMbid".toHttpUrl().newBuilder()
            .addQueryParameter("inc", "isrcs")
            .addQueryParameter("fmt", "json")
            .build()
        val body = executeWithBackoff(url.toString())
        return parseRecordingDetailIsrcs(body)
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
                    if (response.code == 429 || response.code == 503) {
                        throw MusicBrainzRateLimitException(
                            message = "MusicBrainz API rate-limited (HTTP ${response.code}).",
                            httpCode = response.code
                        )
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

        fun parseRecordingCandidates(rawJson: String): List<MusicBrainzRecordingCandidate> {
            val arr = JSONObject(rawJson).optJSONArray("recordings") ?: return emptyList()
            val out = mutableListOf<MusicBrainzRecordingCandidate>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val recid = obj.optString("id", "").trim()
                val title = obj.optString("title", "").trim()
                if (recid.isEmpty() || title.isEmpty()) continue

                val artistName = extractArtistCredit(obj)
                if (artistName.isEmpty()) continue

                val lengthMs = if (obj.has("length") && !obj.isNull("length")) {
                    obj.optLong("length", -1L).takeIf { it > 0L }
                } else {
                    null
                }

                val releases = obj.optJSONArray("releases")
                val firstRelease = if (releases != null && releases.length() > 0) releases.optJSONObject(0) else null
                val albumTitle = firstRelease?.optString("title", "")?.trim()?.takeIf { it.isNotEmpty() }
                val releaseDate = firstRelease?.optString("date", "")?.trim()
                val releaseYear = extractYear(releaseDate)

                val isrcs = extractIsrcList(obj)

                out.add(
                    MusicBrainzRecordingCandidate(
                        musicBrainzRecordingId = recid,
                        title = title,
                        artistName = artistName,
                        albumTitle = albumTitle,
                        durationMs = lengthMs,
                        releaseYear = releaseYear,
                        isrcs = isrcs
                    )
                )
            }
            return out
        }

        fun parseRecordingDetailIsrcs(rawJson: String): List<String> {
            val root = JSONObject(rawJson)
            return extractIsrcList(root)
        }

        private fun extractArtistCredit(obj: JSONObject): String {
            val credits = obj.optJSONArray("artist-credit") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until credits.length()) {
                val item = credits.optJSONObject(i) ?: continue
                val name = item.optString("name", "").trim()
                    .ifEmpty { item.optJSONObject("artist")?.optString("name", "")?.trim().orEmpty() }
                if (name.isEmpty()) continue
                sb.append(name)
                val joinPhrase = item.optString("joinphrase", "")
                if (joinPhrase.isNotEmpty()) {
                    sb.append(joinPhrase)
                } else if (i < credits.length() - 1) {
                    sb.append(", ")
                }
            }
            return sb.toString().trim()
        }

        private fun extractIsrcList(obj: JSONObject): List<String> {
            val arr = obj.optJSONArray("isrcs") ?: return emptyList()
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val raw = arr.optString(i, "").trim()
                if (raw.isNotEmpty()) out.add(raw)
            }
            return out
        }

        private fun extractYear(rawDate: String?): Int? {
            if (rawDate.isNullOrBlank() || rawDate.length < 4) return null
            return rawDate.substring(0, 4).toIntOrNull()
        }
    }
}
