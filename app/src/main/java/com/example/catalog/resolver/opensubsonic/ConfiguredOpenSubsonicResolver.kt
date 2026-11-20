package com.example.catalog.resolver.opensubsonic

import com.example.catalog.matching.TrackMatcher
import com.example.catalog.resolver.CachingDownloadSourceResolver
import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadResolutionResult
import com.example.catalog.resolver.DownloadSourceCapabilities
import com.example.catalog.resolver.DownloadSourceResolver
import com.example.catalog.resolver.ResolverCachePolicy
import com.example.catalog.resolver.ResolverRetryPolicy
import com.example.catalog.resolver.TrackMatchingDownloadSourceResolver

/**
 * Application-level [DownloadSourceResolver] wired to [OpenSubsonicConfigRepository].
 *
 * Lifecycle & correctness guarantees:
 * - Reuses a single [CachingDownloadSourceResolver] per `(serverId, configFingerprint)` so
 *   cache instances are preserved across resolutions and automatically recreated only when
 *   a server configuration changes.
 * - Never bypasses [TrackMatcher] verification or weakens thresholds.
 * - Preserves [DownloadResolutionResult.Ambiguous], [DownloadResolutionResult.Unverified],
 *   [DownloadResolutionResult.Unavailable], and [DownloadResolutionResult.Error] outcomes.
 */
class ConfiguredOpenSubsonicResolver(
    private val configRepository: OpenSubsonicConfigRepository,
    private val clientFactory: (OpenSubsonicServerConfig) -> OpenSubsonicClient,
    private val trackMatcher: TrackMatcher = TrackMatcher(),
    private val retryPolicy: ResolverRetryPolicy = ResolverRetryPolicy(),
    private val cachePolicy: ResolverCachePolicy = ResolverCachePolicy(),
    private val clockMs: () -> Long = System::currentTimeMillis
) : DownloadSourceResolver {

    private val lock = Any()
    private val activeResolvers = LinkedHashMap<String, CachedServerEntry>()

    private data class CachedServerEntry(
        val fingerprint: Int,
        val resolver: CachingDownloadSourceResolver
    )

    override val capabilities: DownloadSourceCapabilities
        get() {
            val enabled = configRepository.getEnabledServers()
            return DownloadSourceCapabilities(
                providerId = OpenSubsonicServerConfig.DEFAULT_PROVIDER_ID,
                displayName = "Configured OpenSubsonic Servers",
                supportsCandidateLookup = enabled.isNotEmpty(),
                supportsIsrcLookup = false,
                supportsTextSearch = enabled.isNotEmpty(),
                supportsResolution = enabled.isNotEmpty(),
                requiresAuthentication = true,
                timeoutMs = enabled.maxOfOrNull { it.timeoutMs } ?: 10_000L,
                maxCandidates = enabled.maxOfOrNull { it.maxCandidates } ?: 10
            )
        }

    override suspend fun resolve(track: CanonicalTrackIdentity): DownloadResolutionResult {
        val enabledServers = configRepository.getEnabledServers()
        if (enabledServers.isEmpty()) {
            pruneInactiveResolvers(emptySet())
            return DownloadResolutionResult.Unavailable(
                providerId = OpenSubsonicServerConfig.DEFAULT_PROVIDER_ID,
                reason = "No enabled OpenSubsonic server is configured."
            )
        }

        pruneInactiveResolvers(enabledServers.map { it.id }.toSet())

        var firstAmbiguous: DownloadResolutionResult.Ambiguous? = null
        var firstUnverified: DownloadResolutionResult.Unverified? = null
        var firstError: DownloadResolutionResult.Error? = null
        var lastUnavailable: DownloadResolutionResult.Unavailable? = null

        for (profile in enabledServers) {
            val resolver = getOrCreateResolver(profile)
            when (val result = resolver.resolve(track)) {
                is DownloadResolutionResult.Resolved -> return result
                is DownloadResolutionResult.Ambiguous -> if (firstAmbiguous == null) firstAmbiguous = result
                is DownloadResolutionResult.Unverified -> if (firstUnverified == null) firstUnverified = result
                is DownloadResolutionResult.Error -> if (firstError == null) firstError = result
                is DownloadResolutionResult.Unavailable -> lastUnavailable = result
            }
        }

        return firstAmbiguous
            ?: firstUnverified
            ?: firstError
            ?: lastUnavailable
            ?: DownloadResolutionResult.Unavailable(
                providerId = OpenSubsonicServerConfig.DEFAULT_PROVIDER_ID,
                reason = "Track unavailable across configured OpenSubsonic servers."
            )
    }

    private fun getOrCreateResolver(profile: OpenSubsonicServerProfile): CachingDownloadSourceResolver {
        val fingerprint = profile.hashCode()
        synchronized(lock) {
            val existing = activeResolvers[profile.id]
            if (existing != null && existing.fingerprint == fingerprint) {
                return existing.resolver
            }
            val serverConfig = profile.toServerConfig()
            val candidateProvider = OpenSubsonicDownloadCandidateProvider(
                config = serverConfig,
                client = clientFactory(serverConfig)
            )
            val matchingResolver = TrackMatchingDownloadSourceResolver(
                candidateProvider = candidateProvider,
                trackMatcher = trackMatcher,
                retryPolicy = retryPolicy,
                cachePolicy = cachePolicy,
                clockMs = clockMs
            )
            val cachingResolver = CachingDownloadSourceResolver(
                delegate = matchingResolver,
                cachePolicy = cachePolicy,
                clockMs = clockMs
            )
            activeResolvers[profile.id] = CachedServerEntry(fingerprint, cachingResolver)
            return cachingResolver
        }
    }

    private fun pruneInactiveResolvers(activeIds: Set<String>) {
        synchronized(lock) {
            activeResolvers.keys.retainAll(activeIds)
        }
    }
}
