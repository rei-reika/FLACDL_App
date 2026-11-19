package com.example.catalog.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coroutine-safe request throttler enforcing both minimum inter-request spacing
 * and sliding-window rate caps (e.g. MusicBrainz 1 req/sec, Apple iTunes ~20 req/min).
 */
class CatalogRateLimiter(
    private val maxRequestsPerWindow: Int,
    private val windowMs: Long,
    private val minIntervalMs: Long = 0L,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { ms -> delay(ms) }
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Long>()
    private var lastRequestAtMs: Long = Long.MIN_VALUE

    suspend fun acquirePermit() {
        mutex.withLock {
            while (true) {
                val now = clockMs()
                while (timestamps.isNotEmpty() && (now - timestamps.first()) >= windowMs) {
                    timestamps.removeFirst()
                }

                val intervalWaitMs = if (minIntervalMs > 0L && lastRequestAtMs != Long.MIN_VALUE) {
                    (minIntervalMs - (now - lastRequestAtMs)).coerceAtLeast(0L)
                } else {
                    0L
                }

                val windowWaitMs = if (timestamps.size >= maxRequestsPerWindow && timestamps.isNotEmpty()) {
                    (windowMs - (now - timestamps.first())).coerceAtLeast(1L)
                } else {
                    0L
                }

                val waitMs = maxOf(intervalWaitMs, windowWaitMs)
                if (waitMs <= 0L) {
                    val grantedAt = clockMs()
                    timestamps.addLast(grantedAt)
                    lastRequestAtMs = grantedAt
                    return
                }
                delayFn(waitMs)
            }
        }
    }

    companion object {
        /** Conservative ~18 req/min to stay safely under Apple iTunes' ~20 req/min limit. */
        fun forItunes(): CatalogRateLimiter = CatalogRateLimiter(
            maxRequestsPerWindow = 18,
            windowMs = 60_000L,
            minIntervalMs = 250L
        )

        /** Strict >= 1.15s spacing to comply with MusicBrainz's 1 req/sec IP limit. */
        fun forMusicBrainz(): CatalogRateLimiter = CatalogRateLimiter(
            maxRequestsPerWindow = 1,
            windowMs = 1_150L,
            minIntervalMs = 1_150L
        )
    }
}
