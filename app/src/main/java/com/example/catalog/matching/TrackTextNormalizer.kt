package com.example.catalog.matching

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin deterministic text, ISRC, artist, and variant normalization utilities
 * for cross-provider track matching.
 */
object TrackTextNormalizer {

    private val ISRC_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

    // Featuring clauses inside parentheses/brackets or trailing in title
    private val PAREN_FEAT_REGEX = Regex(
        """[\(\[]\s*(?:feat\.?|ft\.?|featuring|with)\s+([^\)\]]+)[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val INLINE_FEAT_REGEX = Regex(
        """\s+(?:feat\.?|ft\.?|featuring)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )

    // Safe edition suffixes that do not indicate a conflicting recording type
    private val SAFE_PAREN_EDITION_REGEX = Regex(
        """[\(\[]\s*(?:(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?|deluxe(?:\s+edition)?|expanded(?:\s+edition)?|special\s+edition|anniversary\s+edition|explicit|clean|album\s+version|single\s+version|bonus\s+track|digital\s+master)\s*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    private val SAFE_DASH_EDITION_REGEX = Regex(
        """\s*[-–—]\s*(?:(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?(?:\s+version)?|deluxe(?:\s+edition)?|album\s+version|single\s+version|explicit|clean|bonus\s+track)\s*$""",
        RegexOption.IGNORE_CASE
    )

    // Critical recording variant markers that MUST NOT be silently conflated with studio versions
    private val VARIANT_PATTERNS: Map<String, Regex> = mapOf(
        "live" to Regex("""\b(?:live|in\s+concert|mtv\s+unplugged)\b""", RegexOption.IGNORE_CASE),
        "acoustic" to Regex("""\b(?:acoustic|unplugged)\b""", RegexOption.IGNORE_CASE),
        "instrumental" to Regex("""\b(?:instrumental|off\s+vocal|karaoke)\b""", RegexOption.IGNORE_CASE),
        "remix" to Regex("""\b(?:remix|rework|vip\s+mix|club\s+mix|dub\s+mix)\b""", RegexOption.IGNORE_CASE),
        "demo" to Regex("""\bdemo\b""", RegexOption.IGNORE_CASE),
        "radio_edit" to Regex("""\bradio\s+edit\b""", RegexOption.IGNORE_CASE),
        "sped_up" to Regex("""\b(?:sped\s+up|nightcore)\b""", RegexOption.IGNORE_CASE),
        "slowed" to Regex("""\b(?:slowed|reverb)\b""", RegexOption.IGNORE_CASE),
        "tv_size" to Regex("""\b(?:tv\s+size|tv\s+edit|short\s+ver\.?)\b""", RegexOption.IGNORE_CASE),
        "orchestral" to Regex("""\b(?:orchestral|symphonic)\b""", RegexOption.IGNORE_CASE)
    )

    private val ARTIST_SPLIT_REGEX = Regex(
        """\s*(?:,|;|/|&|\+|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\band\b|\bwith\b|\bx\b)\s*""",
        RegexOption.IGNORE_CASE
    )

    private val NON_ALNUM_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    fun normalizeIsrc(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().replace("-", "").replace(" ", "").uppercase()
        return if (ISRC_REGEX.matches(cleaned)) cleaned else null
    }

    fun extractVariantTags(rawTitle: String?): Set<String> {
        if (rawTitle.isNullOrBlank()) return emptySet()
        val folded = foldUnicode(rawTitle)
        val tags = LinkedHashSet<String>()
        for ((tag, regex) in VARIANT_PATTERNS) {
            if (regex.containsMatchIn(folded)) {
                tags.add(tag)
            }
        }
        return tags
    }

    fun hasVersionConflict(queryTitle: String, candidateTitle: String): Boolean {
        return extractVariantTags(queryTitle) != extractVariantTags(candidateTitle)
    }

    fun extractFeaturedArtistsFromTitle(rawTitle: String): Set<String> {
        val folded = foldUnicode(rawTitle)
        val extracted = LinkedHashSet<String>()
        PAREN_FEAT_REGEX.findAll(folded).forEach { match ->
            extracted.addAll(splitArtistTokens(match.groupValues[1]))
        }
        val withoutParens = PAREN_FEAT_REGEX.replace(folded, " ")
        INLINE_FEAT_REGEX.find(withoutParens)?.let { match ->
            extracted.addAll(splitArtistTokens(match.groupValues[1]))
        }
        return extracted
    }

    fun normalizeTitle(rawTitle: String): String {
        var text = foldUnicode(rawTitle)
        text = PAREN_FEAT_REGEX.replace(text, " ")
        text = INLINE_FEAT_REGEX.replace(text, " ")
        text = SAFE_PAREN_EDITION_REGEX.replace(text, " ")
        text = SAFE_DASH_EDITION_REGEX.replace(text, " ")
        text = text.replace("'", "")
        text = NON_ALNUM_REGEX.replace(text, " ")
        return MULTI_SPACE_REGEX.replace(text, " ").trim()
    }

    fun normalizeAlbum(rawAlbum: String?): String? {
        if (rawAlbum.isNullOrBlank()) return null
        var text = foldUnicode(rawAlbum)
        text = SAFE_PAREN_EDITION_REGEX.replace(text, " ")
        text = SAFE_DASH_EDITION_REGEX.replace(text, " ")
        text = text.replace("'", "")
        text = NON_ALNUM_REGEX.replace(text, " ")
        val result = MULTI_SPACE_REGEX.replace(text, " ").trim()
        return result.ifEmpty { null }
    }

    fun normalizeArtistSet(rawArtist: String, extraFromTitle: Set<String> = emptySet()): Set<String> {
        val out = LinkedHashSet<String>()
        out.addAll(splitArtistTokens(foldUnicode(rawArtist)))
        out.addAll(extraFromTitle)
        return out
    }

    fun artistSetSimilarity(queryArtists: Set<String>, candidateArtists: Set<String>): Double {
        if (queryArtists.isEmpty() || candidateArtists.isEmpty()) return 0.0
        if (queryArtists == candidateArtists) return 1.0

        val intersectionCount = queryArtists.count { q ->
            candidateArtists.any { c -> c == q || stringSimilarity(q, c) >= 0.90 }
        }
        val unionSize = (queryArtists.size + candidateArtists.size - intersectionCount).coerceAtLeast(1)
        val jaccard = intersectionCount.toDouble() / unionSize.toDouble()

        val joinedQuery = queryArtists.sorted().joinToString(" ")
        val joinedCandidate = candidateArtists.sorted().joinToString(" ")
        val joinedSim = stringSimilarity(joinedQuery, joinedCandidate)

        return max(jaccard, joinedSim)
    }

    fun stringSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0

        val levSim = normalizedLevenshtein(a, b)
        val tokensA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val tokensB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        val tokenJaccard = if (tokensA.isEmpty() || tokensB.isEmpty()) {
            0.0
        } else {
            val inter = tokensA.intersect(tokensB).size.toDouble()
            val union = tokensA.union(tokensB).size.toDouble()
            inter / union
        }

        return (0.60 * levSim) + (0.40 * tokenJaccard)
    }

    private fun splitArtistTokens(text: String): List<String> {
        return ARTIST_SPLIT_REGEX.split(text)
            .map { NON_ALNUM_REGEX.replace(it, " ") }
            .map { MULTI_SPACE_REGEX.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun foldUnicode(raw: String): String {
        val decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKD)
        val withoutDiacritics = decomposed.replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
        return withoutDiacritics
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .lowercase()
    }

    private fun normalizedLevenshtein(a: String, b: String): Double {
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 1.0
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = min(
                    min(curr[j - 1] + 1, prev[j] + 1),
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = curr
            curr = tmp
        }
        return 1.0 - (prev[b.length].toDouble() / maxLen.toDouble())
    }
}
