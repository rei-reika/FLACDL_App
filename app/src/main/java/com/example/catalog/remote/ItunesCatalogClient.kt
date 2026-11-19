package com.example.catalog.remote

import com.example.catalog.model.CatalogProviders
import com.example.catalog.model.RemoteAlbumDto
import com.example.catalog.model.RemoteAlbumLookupResult
import com.example.catalog.model.RemoteArtistDto
import com.example.catalog.model.RemoteTrackDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

interface ItunesCatalogProvider {
    suspend fun fetchSearchArtistsJson(query: String, limit: Int = 25, country: String = DEFAULT_COUNTRY): String
    suspend fun fetchSearchAlbumsJson(query: String, limit: Int = 50, country: String = DEFAULT_COUNTRY): String
    suspend fun fetchSearchTracksJson(query: String, limit: Int = 50, country: String = DEFAULT_COUNTRY): String
    suspend fun fetchArtistAlbumsJson(providerArtistId: String, limit: Int = 50, country: String = DEFAULT_COUNTRY): String
    suspend fun fetchAlbumTracklistJson(providerAlbumId: String, country: String = DEFAULT_COUNTRY): String

    companion object {
        const val DEFAULT_COUNTRY = "ID"
        const val MAX_SEARCH_LIMIT = 200
    }
}

/**
 * Official Apple iTunes Search & Lookup API client.
 *
 * Limitations documented in Phase 1B:
 * - Maximum search limit is 200; upstream offset pagination is NOT reliable, so pagination
 *   is performed locally against Room after import.
 * - Artwork URLs are stored as metadata only (subject to Apple promotional usage guidelines).
 * - Never generates or implies Amazon Music URLs from Apple IDs.
 */
class OkHttpItunesCatalogClient(
    private val client: OkHttpClient,
    private val rateLimiter: CatalogRateLimiter = CatalogRateLimiter.forItunes()
) : ItunesCatalogProvider {

    override suspend fun fetchSearchArtistsJson(query: String, limit: Int, country: String): String {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("term", query.trim())
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "musicArtist")
            .addQueryParameter("country", country.uppercase())
            .addQueryParameter("limit", limit.coerceIn(1, ItunesCatalogProvider.MAX_SEARCH_LIMIT).toString())
            .build()
        return executeGet(url.toString())
    }

    override suspend fun fetchSearchAlbumsJson(query: String, limit: Int, country: String): String {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("term", query.trim())
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "album")
            .addQueryParameter("country", country.uppercase())
            .addQueryParameter("limit", limit.coerceIn(1, ItunesCatalogProvider.MAX_SEARCH_LIMIT).toString())
            .build()
        return executeGet(url.toString())
    }

    override suspend fun fetchSearchTracksJson(query: String, limit: Int, country: String): String {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("term", query.trim())
            .addQueryParameter("media", "music")
            .addQueryParameter("entity", "song")
            .addQueryParameter("country", country.uppercase())
            .addQueryParameter("limit", limit.coerceIn(1, ItunesCatalogProvider.MAX_SEARCH_LIMIT).toString())
            .build()
        return executeGet(url.toString())
    }

    override suspend fun fetchArtistAlbumsJson(providerArtistId: String, limit: Int, country: String): String {
        val url = LOOKUP_URL.toHttpUrl().newBuilder()
            .addQueryParameter("id", providerArtistId.trim())
            .addQueryParameter("entity", "album")
            .addQueryParameter("country", country.uppercase())
            .addQueryParameter("limit", limit.coerceIn(1, ItunesCatalogProvider.MAX_SEARCH_LIMIT).toString())
            .build()
        return executeGet(url.toString())
    }

    override suspend fun fetchAlbumTracklistJson(providerAlbumId: String, country: String): String {
        val url = LOOKUP_URL.toHttpUrl().newBuilder()
            .addQueryParameter("id", providerAlbumId.trim())
            .addQueryParameter("entity", "song")
            .addQueryParameter("country", country.uppercase())
            .build()
        return executeGet(url.toString())
    }

    private suspend fun executeGet(url: String): String = withContext(Dispatchers.IO) {
        rateLimiter.acquirePermit()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("iTunes API returned HTTP ${response.code}.")
            }
            if (body.isBlank()) {
                throw IOException("iTunes API returned an empty response body.")
            }
            body
        }
    }

    companion object {
        private const val SEARCH_URL = "https://itunes.apple.com/search"
        private const val LOOKUP_URL = "https://itunes.apple.com/lookup"

        fun parseArtists(rawJson: String): List<RemoteArtistDto> {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<RemoteArtistDto>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val wrapper = obj.optString("wrapperType", "")
                if (wrapper.isNotBlank() && wrapper != "artist") continue
                val id = optPositiveId(obj, "artistId") ?: continue
                val name = obj.optString("artistName", "").trim()
                if (name.isEmpty()) continue
                out.add(
                    RemoteArtistDto(
                        providerArtistId = id,
                        name = name,
                        primaryGenre = optNonBlank(obj, "primaryGenreName"),
                        country = optNonBlank(obj, "country"),
                        artworkUrl = optHttpsUrl(obj, "artworkUrl100"),
                        provider = CatalogProviders.ITUNES
                    )
                )
            }
            return out
        }

        fun parseAlbums(rawJson: String): List<RemoteAlbumDto> {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<RemoteAlbumDto>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val wrapper = obj.optString("wrapperType", "")
                if (wrapper.isNotBlank() && wrapper != "collection") continue
                val colId = optPositiveId(obj, "collectionId") ?: continue
                val artistId = optPositiveId(obj, "artistId") ?: continue
                val title = obj.optString("collectionName", "").trim()
                val artistName = obj.optString("artistName", "").trim()
                if (title.isEmpty() || artistName.isEmpty()) continue
                out.add(
                    RemoteAlbumDto(
                        providerAlbumId = colId,
                        providerArtistId = artistId,
                        artistName = artistName,
                        title = title,
                        releaseDate = normalizeReleaseDate(optNonBlank(obj, "releaseDate")),
                        genre = optNonBlank(obj, "primaryGenreName"),
                        artworkUrl = optHttpsUrl(obj, "artworkUrl100"),
                        trackCount = obj.optInt("trackCount", 0).coerceAtLeast(0),
                        provider = CatalogProviders.ITUNES
                    )
                )
            }
            return out
        }

        fun parseTracks(rawJson: String): List<RemoteTrackDto> {
            val root = JSONObject(rawJson)
            val arr = root.optJSONArray("results") ?: return emptyList()
            val out = mutableListOf<RemoteTrackDto>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val wrapper = obj.optString("wrapperType", "")
                if (wrapper.isNotBlank() && wrapper != "track") continue
                val kind = obj.optString("kind", "")
                if (kind.isNotBlank() && kind != "song") continue
                val trackId = optPositiveId(obj, "trackId") ?: continue
                val artistId = optPositiveId(obj, "artistId") ?: continue
                val title = obj.optString("trackName", "").trim()
                val artistName = obj.optString("artistName", "").trim()
                if (title.isEmpty() || artistName.isEmpty()) continue
                out.add(
                    RemoteTrackDto(
                        providerTrackId = trackId,
                        providerArtistId = artistId,
                        artistName = artistName,
                        providerAlbumId = optPositiveId(obj, "collectionId"),
                        albumTitle = optNonBlank(obj, "collectionName"),
                        albumArtworkUrl = optHttpsUrl(obj, "artworkUrl100"),
                        albumReleaseDate = normalizeReleaseDate(optNonBlank(obj, "releaseDate")),
                        albumTrackCount = obj.optInt("trackCount", 0).coerceAtLeast(0),
                        title = title,
                        durationMs = optPositiveLong(obj, "trackTimeMillis"),
                        trackNumber = optPositiveInt(obj, "trackNumber"),
                        discNumber = optPositiveInt(obj, "discNumber"),
                        genre = optNonBlank(obj, "primaryGenreName"),
                        provider = CatalogProviders.ITUNES
                    )
                )
            }
            return out
        }

        fun parseAlbumLookup(rawJson: String): RemoteAlbumLookupResult {
            val albums = parseAlbums(rawJson)
            val tracks = parseTracks(rawJson)
            return RemoteAlbumLookupResult(
                album = albums.firstOrNull(),
                tracks = tracks
            )
        }

        private fun optPositiveId(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val num = obj.optLong(key, -1L)
            if (num > 0L) return num.toString()
            return obj.optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }
        }

        private fun optNonBlank(obj: JSONObject, key: String): String? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return obj.optString(key, "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        }

        private fun optHttpsUrl(obj: JSONObject, key: String): String? {
            val u = optNonBlank(obj, key) ?: return null
            return if (u.startsWith("https://", ignoreCase = true)) u else null
        }

        private fun optPositiveLong(obj: JSONObject, key: String): Long? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val v = obj.optLong(key, -1L)
            return if (v > 0L) v else null
        }

        private fun optPositiveInt(obj: JSONObject, key: String): Int? {
            if (!obj.has(key) || obj.isNull(key)) return null
            val v = obj.optInt(key, -1)
            return if (v > 0) v else null
        }

        private fun normalizeReleaseDate(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            return if (raw.length >= 10 && raw[4] == '-' && raw[7] == '-') raw.substring(0, 10) else raw
        }
    }
}
