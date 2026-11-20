package com.example.catalog.resolver

import com.example.catalog.matching.TrackTextNormalizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lightweight, thread-safe, bounded in-memory LRU cache decorator around [DownloadSourceResolver].
 *
 * Guarantees:
 * - Honors [ResolverCachePolicy.availabilityTtlMs] and [ResolvedSourceIdentity.expiresAtMs].
 * - Caches only terminal outcomes ([Resolved], [Unavailable]).
 * - Never caches [DownloadResolutionResult.Error], [DownloadResolutionResult.Ambiguous], or [DownloadResolutionResult.Unverified].
 * - Evicts least-recently-used entries when exceeding [maxEntries].
 * - Keeps all state strictly in memory (zero Room persistence or schema changes).
 */
class CachingDownloadSourceResolver(
    private val delegate: DownloadSourceResolver,
    private val cachePolicy: ResolverCachePolicy = ResolverCachePolicy(),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clockMs: () -> Long = System::currentTimeMillis
) : DownloadSourceResolver {

    override val capabilities: DownloadSourceCapabilities
        get() = delegate.capabilities

    private val mutex = Mutex()
    private val boundedMaxEntries = maxEntries.coerceAtLeast(1)

    // Access-order LinkedHashMap for O(1) LRU eviction
    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean {
            return size > boundedMaxEntries
        }
    }

    private data class CacheEntry(
        val result: DownloadResolutionResult,
        val expiresAtMs: Long
    )

    override suspend fun resolve(track: CanonicalTrackIdentity): DownloadResolutionResult {
        val key = buildCacheKey(track)
        val now = clockMs()

        val cached = mutex.withLock {
            val entry = cache[key]
            if (entry != null) {
                if (entry.expiresAtMs > now) {
                    entry.result
                } else {
                    cache.remove(key)
                    null
                }
            } else {
                null
            }
        }
        if (cached != null) {
            return cached
        }

        val fresh = delegate.resolve(track)
        if (shouldCache(fresh)) {
            val resolvedNow = clockMs()
            val ttlExpiry = resolvedNow + cachePolicy.availabilityTtlMs.coerceAtLeast(1L)
            val expiresAt = when (fresh) {
                is DownloadResolutionResult.Resolved -> minOf(ttlExpiry, fresh.identity.expiresAtMs)
                else -> ttlExpiry
            }
            if (expiresAt > resolvedNow) {
                mutex.withLock {
                    cache[key] = CacheEntry(result = fresh, expiresAtMs = expiresAt)
                }
            }
        }
        return fresh
    }

    suspend fun clear() {
        mutex.withLock {
            cache.clear()
        }
    }

    suspend fun size(): Int = mutex.withLock { cache.size }

    private fun shouldCache(result: DownloadResolutionResult): Boolean {
        return when (result) {
            is DownloadResolutionResult.Resolved -> true
            is DownloadResolutionResult.Unavailable -> true
            is DownloadResolutionResult.Ambiguous -> false
            is DownloadResolutionResult.Unverified -> false
            is DownloadResolutionResult.Error -> false
        }
    }

    private fun buildCacheKey(track: CanonicalTrackIdentity): String {
        val normTitle = TrackTextNormalizer.normalizeTitle(track.title)
        val normArtist = TrackTextNormalizer.normalizeArtistSet(track.artist).sorted().joinToString(",")
        val normAlbum = TrackTextNormalizer.normalizeAlbum(track.album).orEmpty()
        val normIsrc = TrackTextNormalizer.normalizeIsrc(track.isrc).orEmpty()
        val durationPart = track.durationMs?.takeIf { it > 0L }?.toString().orEmpty()
        val trackNumPart = track.trackNumber?.takeIf { it > 0 }?.toString().orEmpty()
        val yearPart = track.releaseYear?.takeIf { it > 0 }?.toString().orEmpty()
        return listOf(
            capabilities.providerId,
            track.catalogProviderId.trim().lowercase(),
            track.catalogTrackId.trim(),
            normTitle,
            normArtist,
            normAlbum,
            normIsrc,
            durationPart,
            trackNumPart,
            yearPart
        ).joinToString("|")
    }

    companion object {
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
