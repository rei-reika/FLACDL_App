package com.example.catalog.matching

import kotlin.math.abs

/**
 * Pure Kotlin deterministic Track Matching Domain Engine.
 *
 * Priorities:
 * - Tier 1: Normalized exact ISRC match (with title/artist/version sanity check).
 * - Tier 2: Normalized title + artist + duration (within ~3,000 ms tolerance).
 * - Tier 3: Corroboration via album, track number, release year, and duration proximity.
 * - Tier 4: Unverified match when metadata is incomplete (e.g. missing or borderline duration).
 * - Ambiguous: Multiple competing candidates within the ambiguity margin.
 * - Unavailable: No candidate satisfies minimum matching requirements.
 */
class TrackMatcher(
    private val config: TrackMatchConfig = TrackMatchConfig()
) {

    fun match(
        query: TrackMatchQuery,
        candidates: List<TrackMatchCandidate>
    ): TrackMatchResult {
        val normQueryTitle = TrackTextNormalizer.normalizeTitle(query.title)
        val queryFeat = TrackTextNormalizer.extractFeaturedArtistsFromTitle(query.title)
        val normQueryArtists = TrackTextNormalizer.normalizeArtistSet(query.artist, queryFeat)

        if (normQueryTitle.isEmpty() || normQueryArtists.isEmpty()) {
            return TrackMatchResult.Unavailable(
                reason = "Incomplete query metadata: non-empty title and artist are required."
            )
        }
        if (candidates.isEmpty()) {
            return TrackMatchResult.Unavailable(
                reason = "No candidates available for matching."
            )
        }

        val normQueryIsrc = TrackTextNormalizer.normalizeIsrc(query.isrc)
        val normQueryAlbum = TrackTextNormalizer.normalizeAlbum(query.album)
        val queryDuration = query.durationMs?.takeIf { it > 0L }

        val verifiedCandidates = mutableListOf<ScoredTrackCandidate>()
        val unverifiedCandidates = mutableListOf<ScoredTrackCandidate>()
        val rejectedCandidates = mutableListOf<ScoredTrackCandidate>()

        for (candidate in candidates) {
            val evaluation = evaluateCandidate(
                query = query,
                normQueryTitle = normQueryTitle,
                normQueryArtists = normQueryArtists,
                normQueryAlbum = normQueryAlbum,
                normQueryIsrc = normQueryIsrc,
                queryDuration = queryDuration,
                candidate = candidate
            )
            when (evaluation.bucket) {
                EvaluationBucket.VERIFIED -> verifiedCandidates.add(evaluation.scored)
                EvaluationBucket.UNVERIFIED -> unverifiedCandidates.add(evaluation.scored)
                EvaluationBucket.REJECTED -> rejectedCandidates.add(evaluation.scored)
            }
        }

        val sortedVerified = verifiedCandidates.sortedWith(CANDIDATE_COMPARATOR)
        if (sortedVerified.isNotEmpty()) {
            val best = sortedVerified.first()
            val closeCompetitors = sortedVerified.filter {
                (best.evidence.score - it.evidence.score) <= config.ambiguityScoreMargin
            }
            return if (closeCompetitors.size > 1) {
                TrackMatchResult.Ambiguous(
                    candidates = closeCompetitors,
                    reason = "Multiple verified candidates have comparable matching evidence."
                )
            } else {
                TrackMatchResult.Verified(
                    candidate = best.candidate,
                    evidence = best.evidence
                )
            }
        }

        val sortedUnverified = unverifiedCandidates.sortedWith(CANDIDATE_COMPARATOR)
        if (sortedUnverified.isNotEmpty()) {
            val best = sortedUnverified.first()
            val closeCompetitors = sortedUnverified.filter {
                (best.evidence.score - it.evidence.score) <= config.ambiguityScoreMargin
            }
            return if (closeCompetitors.size > 1) {
                TrackMatchResult.Ambiguous(
                    candidates = closeCompetitors,
                    reason = "Multiple unverified candidates have comparable matching evidence."
                )
            } else {
                val reason = best.evidence.notes.firstOrNull { it.startsWith("unverified:") }
                    ?.removePrefix("unverified:")
                    ?: "Insufficient metadata to confirm a verified match."
                TrackMatchResult.Unverified(
                    candidate = best.candidate,
                    evidence = best.evidence,
                    reason = reason
                )
            }
        }

        val sortedRejected = rejectedCandidates.sortedWith(CANDIDATE_COMPARATOR)
        val rejectionSummary = sortedRejected.firstOrNull()?.evidence?.notes?.firstOrNull()
            ?: "No candidate met minimum matching requirements."
        return TrackMatchResult.Unavailable(
            reason = rejectionSummary,
            rejectedCandidates = sortedRejected
        )
    }

    private enum class EvaluationBucket {
        VERIFIED,
        UNVERIFIED,
        REJECTED
    }

    private data class CandidateEvaluation(
        val bucket: EvaluationBucket,
        val scored: ScoredTrackCandidate
    )

    private fun evaluateCandidate(
        query: TrackMatchQuery,
        normQueryTitle: String,
        normQueryArtists: Set<String>,
        normQueryAlbum: String?,
        normQueryIsrc: String?,
        queryDuration: Long?,
        candidate: TrackMatchCandidate
    ): CandidateEvaluation {
        val normCandTitle = TrackTextNormalizer.normalizeTitle(candidate.title)
        val candFeat = TrackTextNormalizer.extractFeaturedArtistsFromTitle(candidate.title)
        val normCandArtists = TrackTextNormalizer.normalizeArtistSet(candidate.artist, candFeat)

        if (normCandTitle.isEmpty() || normCandArtists.isEmpty()) {
            return CandidateEvaluation(
                bucket = EvaluationBucket.REJECTED,
                scored = ScoredTrackCandidate(
                    candidate = candidate,
                    evidence = TrackMatchEvidence(
                        matchMethod = TrackMatchMethod.NONE,
                        score = 0.0,
                        titleSimilarity = 0.0,
                        artistSimilarity = 0.0,
                        notes = listOf("Candidate has incomplete title or artist metadata.")
                    )
                )
            )
        }

        val titleSim = TrackTextNormalizer.stringSimilarity(normQueryTitle, normCandTitle)
        val artistSim = TrackTextNormalizer.artistSetSimilarity(normQueryArtists, normCandArtists)
        val normCandAlbum = TrackTextNormalizer.normalizeAlbum(candidate.album)
        val albumSim = if (normQueryAlbum != null && normCandAlbum != null) {
            TrackTextNormalizer.stringSimilarity(normQueryAlbum, normCandAlbum)
        } else {
            null
        }

        val candDuration = candidate.durationMs?.takeIf { it > 0L }
        val durationDeltaMs = if (queryDuration != null && candDuration != null) {
            abs(queryDuration - candDuration)
        } else {
            null
        }

        val versionConflict = TrackTextNormalizer.hasVersionConflict(query.title, candidate.title)
        val matchedFields = LinkedHashSet<String>()
        val notes = mutableListOf<String>()

        if (titleSim >= config.minTitleSimilarityForCandidate) matchedFields.add("title")
        if (artistSim >= config.minArtistSimilarityForCandidate) matchedFields.add("artist")

        // Tier 3 corroboration bonus (strictly bounded, never overrides conflicts)
        var corroborationBonus = 0.0
        if (albumSim != null && albumSim >= 0.80) {
            matchedFields.add("album")
            corroborationBonus += 0.03 * albumSim
        }
        if (query.trackNumber != null && candidate.trackNumber != null && query.trackNumber == candidate.trackNumber) {
            matchedFields.add("trackNumber")
            corroborationBonus += 0.015
        }
        if (query.releaseYear != null && candidate.releaseYear != null && abs(query.releaseYear - candidate.releaseYear) <= 1) {
            matchedFields.add("releaseYear")
            corroborationBonus += 0.01
        }
        if (durationDeltaMs != null && durationDeltaMs <= config.verifiedDurationToleranceMs) {
            matchedFields.add("duration")
            val closeness = 1.0 - (durationDeltaMs.toDouble() / config.verifiedDurationToleranceMs.toDouble())
            corroborationBonus += 0.02 * closeness
        }

        // Reject recording-variant conflicts (e.g. Live vs Studio, Acoustic vs Studio, Remix vs Original)
        if (versionConflict) {
            notes.add("Recording version conflict between query and candidate.")
            return CandidateEvaluation(
                bucket = EvaluationBucket.REJECTED,
                scored = ScoredTrackCandidate(
                    candidate = candidate,
                    evidence = TrackMatchEvidence(
                        matchMethod = TrackMatchMethod.NONE,
                        score = 0.0,
                        titleSimilarity = titleSim,
                        artistSimilarity = artistSim,
                        albumSimilarity = albumSim,
                        durationDeltaMs = durationDeltaMs,
                        matchedFields = matchedFields,
                        notes = notes
                    )
                )
            )
        }

        // Tier 1: Exact normalized ISRC match (must still pass metadata consistency sanity checks)
        val normCandIsrc = TrackTextNormalizer.normalizeIsrc(candidate.isrc)
        if (normQueryIsrc != null && normCandIsrc != null && normQueryIsrc == normCandIsrc) {
            val titleConsistent = titleSim >= config.minTitleSimilarityForIsrc
            val artistConsistent = artistSim >= config.minArtistSimilarityForIsrc
            val durationConsistent = durationDeltaMs == null || durationDeltaMs <= config.maxIsrcDurationDiscrepancyMs

            if (titleConsistent && artistConsistent && durationConsistent) {
                matchedFields.add("isrc")
                val score = (0.94 + corroborationBonus).coerceAtMost(1.0)
                return CandidateEvaluation(
                    bucket = EvaluationBucket.VERIFIED,
                    scored = ScoredTrackCandidate(
                        candidate = candidate,
                        evidence = TrackMatchEvidence(
                            matchMethod = TrackMatchMethod.ISRC_EXACT,
                            score = score,
                            titleSimilarity = titleSim,
                            artistSimilarity = artistSim,
                            albumSimilarity = albumSim,
                            durationDeltaMs = durationDeltaMs,
                            matchedFields = matchedFields,
                            notes = listOf("Exact ISRC match verified with consistent metadata.")
                        )
                    )
                )
            } else {
                notes.add("ISRC matched but candidate metadata conflicts with query title, artist, or duration.")
                return CandidateEvaluation(
                    bucket = EvaluationBucket.REJECTED,
                    scored = ScoredTrackCandidate(
                        candidate = candidate,
                        evidence = TrackMatchEvidence(
                            matchMethod = TrackMatchMethod.NONE,
                            score = 0.0,
                            titleSimilarity = titleSim,
                            artistSimilarity = artistSim,
                            albumSimilarity = albumSim,
                            durationDeltaMs = durationDeltaMs,
                            matchedFields = matchedFields,
                            notes = notes
                        )
                    )
                )
            }
        }

        // Minimum baseline for Tier 2 / Tier 4
        if (titleSim < config.minTitleSimilarityForCandidate || artistSim < config.minArtistSimilarityForCandidate) {
            notes.add("Title or artist similarity below minimum threshold.")
            return CandidateEvaluation(
                bucket = EvaluationBucket.REJECTED,
                scored = ScoredTrackCandidate(
                    candidate = candidate,
                    evidence = TrackMatchEvidence(
                        matchMethod = TrackMatchMethod.NONE,
                        score = (titleSim * 0.5 + artistSim * 0.5) * 0.5,
                        titleSimilarity = titleSim,
                        artistSimilarity = artistSim,
                        albumSimilarity = albumSim,
                        durationDeltaMs = durationDeltaMs,
                        matchedFields = matchedFields,
                        notes = notes
                    )
                )
            )
        }

        val baseScore = (0.52 * titleSim) + (0.38 * artistSim) + corroborationBonus

        // Tier 2: Verified Title + Artist + Duration (within <= 3,000 ms)
        if (durationDeltaMs != null) {
            if (durationDeltaMs <= config.verifiedDurationToleranceMs &&
                titleSim >= config.minTitleSimilarityForVerified &&
                artistSim >= config.minArtistSimilarityForVerified
            ) {
                val verifiedScore = (baseScore + 0.05).coerceAtMost(0.96)
                return CandidateEvaluation(
                    bucket = EvaluationBucket.VERIFIED,
                    scored = ScoredTrackCandidate(
                        candidate = candidate,
                        evidence = TrackMatchEvidence(
                            matchMethod = TrackMatchMethod.TITLE_ARTIST_DURATION,
                            score = verifiedScore,
                            titleSimilarity = titleSim,
                            artistSimilarity = artistSim,
                            albumSimilarity = albumSim,
                            durationDeltaMs = durationDeltaMs,
                            matchedFields = matchedFields,
                            notes = listOf("Matched by normalized title, artist, and duration within tolerance.")
                        )
                    )
                )
            }

            if (durationDeltaMs > config.unverifiedDurationToleranceMs) {
                notes.add("Duration difference (${durationDeltaMs}ms) exceeds maximum allowed tolerance.")
                return CandidateEvaluation(
                    bucket = EvaluationBucket.REJECTED,
                    scored = ScoredTrackCandidate(
                        candidate = candidate,
                        evidence = TrackMatchEvidence(
                            matchMethod = TrackMatchMethod.NONE,
                            score = (baseScore * 0.5).coerceAtMost(0.5),
                            titleSimilarity = titleSim,
                            artistSimilarity = artistSim,
                            albumSimilarity = albumSim,
                            durationDeltaMs = durationDeltaMs,
                            matchedFields = matchedFields,
                            notes = notes
                        )
                    )
                )
            }

            // Borderline duration (between verifiedTolerance and unverifiedTolerance) -> Tier 4 Unverified
            notes.add("unverified:Duration difference (${durationDeltaMs}ms) exceeds verified ${config.verifiedDurationToleranceMs}ms threshold.")
            return CandidateEvaluation(
                bucket = EvaluationBucket.UNVERIFIED,
                scored = ScoredTrackCandidate(
                    candidate = candidate,
                    evidence = TrackMatchEvidence(
                        matchMethod = TrackMatchMethod.TITLE_ARTIST_INCOMPLETE,
                        score = (baseScore * 0.85).coerceAtMost(0.84),
                        titleSimilarity = titleSim,
                        artistSimilarity = artistSim,
                        albumSimilarity = albumSim,
                        durationDeltaMs = durationDeltaMs,
                        matchedFields = matchedFields,
                        notes = notes
                    )
                )
            )
        }

        // Tier 4: Missing duration -> Unverified (never silently promoted to Verified without ISRC)
        notes.add("unverified:Duration metadata is missing; match cannot be fully verified.")
        return CandidateEvaluation(
            bucket = EvaluationBucket.UNVERIFIED,
            scored = ScoredTrackCandidate(
                candidate = candidate,
                evidence = TrackMatchEvidence(
                    matchMethod = TrackMatchMethod.TITLE_ARTIST_INCOMPLETE,
                    score = (baseScore * 0.85).coerceAtMost(0.84),
                    titleSimilarity = titleSim,
                    artistSimilarity = artistSim,
                    albumSimilarity = albumSim,
                    durationDeltaMs = null,
                    matchedFields = matchedFields,
                    notes = notes
                )
            )
        )
    }

    companion object {
        private val CANDIDATE_COMPARATOR = compareByDescending<ScoredTrackCandidate> { it.evidence.score }
            .thenBy { it.evidence.durationDeltaMs ?: Long.MAX_VALUE }
            .thenBy { it.candidate.candidateId }
            .thenBy { it.candidate.providerId }
    }
}
