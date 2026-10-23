package com.karen.search

/**
 * Deterministic query expansion. No model call and no hidden network request:
 * the agent gets at most two queries, and the second is only tried when the
 * first pass did not produce enough evidence.
 */
object QueryExpander {
    /**
     * Kept in sync with the on-device trigger list in the UI facade. This copy
     * is used only to decide whether a second "latest" query is useful.
     */
    private val FRESH_MARKERS = listOf(
        "latest", "news", "today", "current", "price", "weather", "score",
        "stock", "release date", "right now", "this week", "this month",
        "this year", "recent", "live", "trending", "upcoming", "schedule",
        "winner", "as of", "breaking", "announced"
    )

    /** Markers that already make a "latest" suffix redundant. */
    private val RECENCY_SUFFIX_MARKERS = listOf(
        "latest", "news", "today", "right now", "live", "breaking", "current",
        "recent"
    )

    private val STOPWORDS = setOf(
        "a", "an", "and", "are", "as", "at", "be", "by", "can", "could",
        "did", "do", "does", "for", "from", "has", "have", "how", "i", "if",
        "in", "into", "is", "it", "its", "me", "my", "of", "on", "or",
        "please", "should", "so", "that", "the", "their", "them", "then",
        "there", "these", "they", "this", "to", "was", "we", "were", "what",
        "when", "where", "which", "who", "why", "will", "with", "would",
        "you", "your"
    )

    /** Same prefix stripping the chat screen has always used. */
    fun clean(text: String): String {
        val trimmed = text.trim()
        val cleaned = when {
            trimmed.startsWith("/search", ignoreCase = true) ->
                trimmed.removePrefix("/search").trim(' ', ':')
            trimmed.startsWith("search:", ignoreCase = true) ->
                trimmed.removePrefix("search:").trim()
            trimmed.startsWith("search ", ignoreCase = true) ->
                trimmed.removePrefix("search ").trim()
            else -> trimmed
        }.ifBlank { trimmed }
        return cleaned.replace(Regex("\\s+"), " ").trim().take(MAX_QUERY)
    }

    fun primary(question: String): String = clean(question)

    /** Query list is ordered: primary first, then at most one deterministic alternative. */
    fun expand(question: String, max: Int = 2): List<String> {
        val capped = max.coerceIn(1, 3)
        val first = primary(question)
        if (first.isBlank()) return emptyList()
        val out = mutableListOf(first)
        if (capped == 1) return out

        val keywords = keywordVariant(first)
        if (keywords.isNotBlank() && !keywords.equals(first, ignoreCase = true)) {
            out += keywords
        }
        if (out.size < capped && isFreshQuery(first) && !containsRecencySuffix(first)) {
            out += "$first latest".take(MAX_QUERY)
        }
        return out.distinct().take(capped)
    }

    /** First not-yet-attempted alternative, or null when the bounded set is exhausted. */
    fun followUp(question: String, attempted: Collection<String>): String? {
        val seen = attempted.map { it.trim().lowercase() }.toSet()
        return expand(question, 3).firstOrNull { it.trim().lowercase() !in seen }
    }

    fun keywordVariant(text: String): String {
        val body = clean(text).lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s'-]+"), " ")
        val words = body.split(Regex("\\s+"))
            .map { it.trim('\'', '-') }
            .filter { it.length >= 2 && it !in STOPWORDS }
            .distinct()
        if (words.size < 2) return ""
        return words.take(8).joinToString(" ").take(MAX_QUERY)
    }

    fun isFreshQuery(text: String): Boolean {
        val t = text.lowercase()
        return FRESH_MARKERS.any { t.contains(it) }
    }

    fun contentTerms(text: String): Set<String> =
        text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]+"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in STOPWORDS }
            .toSet()

    private fun containsRecencySuffix(text: String): Boolean {
        val t = text.lowercase()
        return RECENCY_SUFFIX_MARKERS.any { t.contains(it) }
    }

    private const val MAX_QUERY = 300
}
