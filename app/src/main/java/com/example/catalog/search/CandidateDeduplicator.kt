package com.example.catalog.search

import com.example.catalog.matching.TrackTextNormalizer
import kotlin.math.abs

/**
 * Pure Kotlin deterministic candidate deduplicator & merger for catalog discovery results.
 *
 * Uses existing [TrackTextNormalizer] rules to merge duplicate candidates across providers
 * or duplicate releases without discarding provider identifiers or metadata enrichment:
 * - Priority 1: Exact normalized ISRC match (with version-tag conflict protection).
 * - Priority 2: Normalized title + artist set match with compatible duration (within [durationToleranceMs]).
 * - Distinct versions (e.g., Live, Acoustic, Remix, Instrumental) or conflicting durations are NOT merged.
 * - Ranking is deterministic: higher metadata completeness and multi-provider corroboration first,
 *   followed by stable lexical tie-breakers.
 */
class CandidateDeduplicator(
    private val durationToleranceMs: Long = DEFAULT_DURATION_TOLERANCE_MS
) {

    fun mergeAndRank(candidates: List<StructuredTrackCandidate>): List<StructuredTrackCandidate> {
        if (candidates.isEmpty()) return emptyList()

        val groups = mutableListOf<CandidateGroup>()

        for (candidate in candidates) {
            val normTitle = TrackTextNormalizer.normalizeTitle(candidate.title)
            val normArtists = TrackTextNormalizer.normalizeArtistSet(
                candidate.artist,
                TrackTextNormalizer.extractFeaturedArtistsFromTitle(candidate.title)
            )
            if (normTitle.isEmpty() || normArtists.isEmpty()) continue

            val normIsrc = TrackTextNormalizer.normalizeIsrc(candidate.isrc)
            val versionTags = TrackTextNormalizer.extractVariantTags(candidate.title)

            val matchingGroup = groups.firstOrNull { group ->
                canMerge(
                    group = group,
                    normTitle = normTitle,
                    normArtists = normArtists,
                    normIsrc = normIsrc,
                    versionTags = versionTags,
                    durationMs = candidate.durationMs
                )
            }

            if (matchingGroup != null) {
                matchingGroup.add(
                    candidate = candidate,
                    normIsrc = normIsrc,
                    matchedByIsrc = normIsrc != null && normIsrc == matchingGroup.normIsrc
                )
            } else {
                groups.add(
                    CandidateGroup(
                        normTitle = normTitle,
                        normArtists = normArtists,
                        normIsrc = normIsrc,
                        versionTags = versionTags,
                        primaryDurationMs = candidate.durationMs?.takeIf { it > 0L },
                        members = mutableListOf(candidate),
                        mergedByIsrc = false
                    )
                )
            }
        }

        return groups
            .map { it.toMergedCandidate() }
            .sortedWith(DETERMINISTIC_COMPARATOR)
    }

    private fun canMerge(
        group: CandidateGroup,
        normTitle: String,
        normArtists: Set<String>,
        normIsrc: String?,
        versionTags: Set<String>,
        durationMs: Long?
    ): Boolean {
        // Never merge conflicting explicit version tags (e.g. Studio vs Live / Acoustic / Remix)
        if (group.versionTags != versionTags) return false

        // Rule 1: Exact normalized ISRC match
        if (normIsrc != null && group.normIsrc != null && normIsrc == group.normIsrc) {
            return true
        }

        // Rule 2: Normalized title + overlapping artist set + compatible duration
        if (group.normTitle != normTitle) return false
        val artistMatch = group.normArtists == normArtists ||
            (group.normArtists.isNotEmpty() && normArtists.isNotEmpty() &&
                group.normArtists.intersect(normArtists).isNotEmpty())
        if (!artistMatch) return false

        val d1 = group.primaryDurationMs
        val d2 = durationMs?.takeIf { it > 0L }
        if (d1 != null && d2 != null && abs(d1 - d2) > durationToleranceMs) {
            return false
        }
        return true
    }

    private class CandidateGroup(
        val normTitle: String,
        val normArtists: Set<String>,
        var normIsrc: String?,
        val versionTags: Set<String>,
        var primaryDurationMs: Long?,
        val members: MutableList<StructuredTrackCandidate>,
        var mergedByIsrc: Boolean
    ) {
        fun add(candidate: StructuredTrackCandidate, normIsrc: String?, matchedByIsrc: Boolean) {
            members.add(candidate)
            if (this.normIsrc == null && normIsrc != null) {
                this.normIsrc = normIsrc
            }
            if (this.primaryDurationMs == null && (candidate.durationMs ?: 0L) > 0L) {
                this.primaryDurationMs = candidate.durationMs
            }
            if (matchedByIsrc) {
                this.mergedByIsrc = true
            }
        }

        fun toMergedCandidate(): StructuredTrackCandidate {
            val primary = members.maxWith(
                compareBy<StructuredTrackCandidate> { metadataCompleteness(it) }
                    .thenByDescending { it.canonicalKey }
            )
            val combinedProviders = linkedMapOf<String, String>()
            val allSourceProviders = linkedSetOf<String>()
            for (m in members) {
                combinedProviders.putAll(m.providerIds)
                allSourceProviders.addAll(m.providerIds.keys)
                allSourceProviders.addAll(m.internalMatch.sourceProviders)
            }

            val bestAlbum = primary.album ?: members.firstNotNullOfOrNull { it.album?.takeIf(String::isNotBlank) }
            val bestArtwork = primary.artworkUrl ?: members.firstNotNullOfOrNull { it.artworkUrl?.takeIf(String::isNotBlank) }
            val bestDuration = primary.durationMs ?: members.firstNotNullOfOrNull { it.durationMs?.takeIf { d -> d > 0L } }
            val bestYear = primary.releaseYear ?: members.firstNotNullOfOrNull { it.releaseYear }
            val bestTrackNum = primary.trackNumber ?: members.firstNotNullOfOrNull { it.trackNumber }
            val bestIsrc = normIsrc ?: primary.isrc ?: members.firstNotNullOfOrNull { it.isrc?.takeIf(String::isNotBlank) }

            val canonicalKey = if (bestIsrc != null) {
                "isrc:$bestIsrc"
            } else {
                "track:$normTitle|${normArtists.sorted().joinToString(",")}|${bestDuration ?: 0L}"
            }

            val confidence = when {
                mergedByIsrc -> 1.0
                members.size > 1 -> 0.95
                else -> 0.90
            }

            return StructuredTrackCandidate(
                canonicalKey = canonicalKey,
                title = primary.title,
                artist = primary.artist,
                album = bestAlbum,
                artworkUrl = bestArtwork,
                durationMs = bestDuration,
                releaseYear = bestYear,
                trackNumber = bestTrackNum,
                isrc = bestIsrc,
                providerIds = combinedProviders.toMap(),
                internalMatch = CandidateMatchMetadata(
                    mergedCandidateCount = members.size,
                    mergedByIsrc = mergedByIsrc,
                    confidenceScore = confidence,
                    sourceProviders = allSourceProviders.toSet()
                )
            )
        }
    }

    companion object {
        const val DEFAULT_DURATION_TOLERANCE_MS: Long = 3_000L

        private fun metadataCompleteness(c: StructuredTrackCandidate): Int {
            var score = 0
            if (!c.isrc.isNullOrBlank()) score += 4
            if (!c.artworkUrl.isNullOrBlank()) score += 2
            if (!c.album.isNullOrBlank()) score += 2
            if ((c.durationMs ?: 0L) > 0L) score += 2
            if (c.releaseYear != null) score += 1
            if (c.trackNumber != null) score += 1
            return score
        }

        private val DETERMINISTIC_COMPARATOR: Comparator<StructuredTrackCandidate> =
            compareByDescending<StructuredTrackCandidate> { it.internalMatch.sourceProviders.size }
                .thenByDescending { metadataCompleteness(it) }
                .thenByDescending { it.internalMatch.confidenceScore }
                .thenBy { it.title.lowercase() }
                .thenBy { it.artist.lowercase() }
                .thenBy { it.canonicalKey }
    }
}
