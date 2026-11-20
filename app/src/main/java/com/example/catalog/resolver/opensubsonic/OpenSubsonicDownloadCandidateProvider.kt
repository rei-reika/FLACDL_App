package com.example.catalog.resolver.opensubsonic

import com.example.catalog.matching.TrackMatchCandidate
import com.example.catalog.matching.TrackTextNormalizer
import com.example.catalog.resolver.CanonicalTrackIdentity
import com.example.catalog.resolver.DownloadSourceCandidate
import com.example.catalog.resolver.DownloadSourceCandidateProvider
import com.example.catalog.resolver.DownloadSourceCapabilities
import com.example.catalog.resolver.DownloadSourceLookupException
import com.example.catalog.resolver.ResolverErrorType

/**
 * Candidate provider for user-owned, explicitly authorized OpenSubsonic-compatible servers
 * (e.g., Navidrome).
 *
 * Guarantees:
 * - Discovers and maps candidates only (does NOT perform audio file downloads).
 * - Preserves server-specific song IDs; never substitutes Apple/catalog IDs.
 * - Never embeds credentials or tokens in [DownloadSourceCandidate.sourceReference].
 * - Single focused song search per resolution (no bulk library crawling).
 */
class OpenSubsonicDownloadCandidateProvider(
    private val config: OpenSubsonicServerConfig,
    private val client: OpenSubsonicClient
) : DownloadSourceCandidateProvider {

    override val capabilities: DownloadSourceCapabilities = DownloadSourceCapabilities(
        providerId = config.providerId,
        displayName = config.displayName,
        supportsCandidateLookup = true,
        supportsIsrcLookup = false,
        supportsTextSearch = true,
        supportsResolution = true,
        requiresAuthentication = true,
        timeoutMs = config.timeoutMs,
        maxCandidates = config.maxCandidates
    )

    override suspend fun fetchCandidates(track: CanonicalTrackIdentity): List<DownloadSourceCandidate> {
        if (config.serverBaseUrl.isBlank()) {
            throw DownloadSourceLookupException(
                errorType = ResolverErrorType.INVALID_REQUEST,
                message = "OpenSubsonic server base URL is not configured."
            )
        }

        val query = buildFocusedSearchQuery(track)
        if (query.isEmpty()) {
            throw DownloadSourceLookupException(
                errorType = ResolverErrorType.INVALID_REQUEST,
                message = "Track title and artist are required for OpenSubsonic search."
            )
        }

        val songs = client.searchSongs(
            query = query,
            songCount = config.maxCandidates.coerceAtLeast(1)
        )

        return songs.mapNotNull { song -> mapSongToCandidate(song) }
    }

    private fun buildFocusedSearchQuery(track: CanonicalTrackIdentity): String {
        val cleanTitle = track.title.trim()
        val cleanArtist = track.artist.trim()
        return listOf(cleanTitle, cleanArtist)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .trim()
    }

    private fun mapSongToCandidate(song: OpenSubsonicSongDto): DownloadSourceCandidate? {
        val songId = song.id.trim()
        val title = song.title.trim()
        val artist = song.artist.trim()
        if (songId.isEmpty() || title.isEmpty() || artist.isEmpty()) return null

        val durationMs = song.durationSeconds?.takeIf { it > 0 }?.toLong()?.times(1_000L)
        val normalizedIsrc = song.isrcs
            .mapNotNull { TrackTextNormalizer.normalizeIsrc(it) }
            .distinct()
            .sorted()
            .firstOrNull()

        val qualityHints = buildQualityHints(song)

        return DownloadSourceCandidate(
            providerId = config.providerId,
            sourceTrackId = songId,
            // Credential-free logical source reference; download execution is out of scope for Phase 2D.7
            sourceReference = "opensubsonic://${config.providerId}/song/$songId",
            metadata = TrackMatchCandidate(
                candidateId = songId,
                providerId = config.providerId,
                title = title,
                artist = artist,
                album = song.album?.trim()?.takeIf { it.isNotEmpty() },
                durationMs = durationMs,
                trackNumber = song.trackNumber?.takeIf { it > 0 },
                releaseYear = song.year?.takeIf { it > 0 },
                isrc = normalizedIsrc
            ),
            isAvailable = true,
            qualityHints = qualityHints,
            musicBrainzRecordingId = song.musicBrainzId?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    private fun buildQualityHints(song: OpenSubsonicSongDto): Set<String> {
        val hints = LinkedHashSet<String>()
        val suffix = song.suffix?.trim()?.lowercase()
        if (!suffix.isNullOrEmpty()) {
            hints.add(suffix.uppercase())
            if (suffix in LOSSLESS_SUFFIXES) {
                hints.add("LOSSLESS")
            }
        }
        val contentType = song.contentType?.trim()?.lowercase()
        if (!contentType.isNullOrEmpty()) {
            hints.add(contentType)
            if (contentType.contains("flac") || contentType.contains("alac")) {
                hints.add("LOSSLESS")
            }
        }
        if (song.bitDepth != null && song.samplingRate != null) {
            val khz = song.samplingRate / 1000.0
            val khzStr = if (song.samplingRate % 1000 == 0) "${song.samplingRate / 1000}kHz" else "${khz}kHz"
            hints.add("${song.bitDepth}bit/$khzStr")
        } else if (song.bitRate != null) {
            hints.add("${song.bitRate}kbps")
        }
        return hints
    }

    companion object {
        private val LOSSLESS_SUFFIXES = setOf("flac", "alac", "wav", "aiff", "ape", "wv")
    }
}
