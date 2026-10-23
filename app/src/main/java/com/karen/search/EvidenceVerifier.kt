package com.karen.search

/**
 * Evidence hygiene at the boundary between "text found on the web" and "text
 * given to the model". Nothing here executes or obeys page instructions; it
 * only flags instruction-like content so the prompt can label it as data.
 */
object EvidenceVerifier {
    private val STRONG_INJECTION = listOf(
        Regex("ignore\\s+(all\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)"),
        Regex("disregard\\s+(all\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)"),
        Regex("forget\\s+(all\\s+)?(the\\s+)?(previous|prior|above|earlier)\\s+(instructions|prompts|rules)"),
        Regex("you\\s+are\\s+now\\s+(a|an|the)\\b"),
        Regex("(reveal|print|show|repeat)\\s+(your|the)\\s+(system\\s+prompt|hidden\\s+prompt|api\\s+key|developer\\s+instructions)"),
        Regex("do\\s+not\\s+tell\\s+the\\s+user"),
        Regex("exfiltrate\\s+(the|all|this|any)")
    )

    private val WEAK_INJECTION = listOf(
        Regex("system\\s+prompt"),
        Regex("developer\\s+(message|instructions)"),
        Regex("ignore\\s+the\\s+user"),
        Regex("execute\\s+(the\\s+)?(following|this)\\s+(command|code)"),
        Regex("send\\s+(all|this|the)\\s+.{0,50}to\\s+https?://"),
        Regex("tool\\s+call"),
        Regex("prompt\\s+injection"),
        Regex("(^|\\n)\\s*(###\\s*)?(system|assistant)\\s*:")
    )

    fun suspicious(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        if (STRONG_INJECTION.any { it.containsMatchIn(lower) }) return true
        return WEAK_INJECTION.count { it.containsMatchIn(lower) } >= 2
    }

    /**
     * Picks the densest relevant window instead of always taking the page
     * opening (which is usually navigation or a cookie banner).
     */
    fun bestExcerpt(text: String, question: String, maxChars: Int = 900): String {
        val clean = text.trim()
        if (clean.isBlank()) return ""
        val cap = maxChars.coerceIn(200, 4_000)
        if (clean.length <= cap) return clean

        val lines = clean.split('\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return clean.take(cap)

        val terms = SearchRanker.terms(question)
        var bestIndex = 0
        var bestScore = Double.NEGATIVE_INFINITY
        lines.forEachIndexed { index, line ->
            val overlap = SearchRanker.overlap(line, terms).toDouble()
            val lengthBonus = if (line.length in 40..600) 0.25 else 0.0
            val score = overlap * 2.0 + lengthBonus
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }

        if (bestScore <= 0.25) {
            return clean.take(cap).trimEnd()
        }

        val start = (bestIndex - 1).coerceAtLeast(0)
        val builder = StringBuilder()
        for (index in start until lines.size) {
            val line = lines[index]
            if (builder.isNotEmpty() && builder.length + line.length + 1 > cap) break
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line.take(cap))
            if (builder.length >= cap) break
        }
        return builder.toString().take(cap).trimEnd()
    }

    fun sufficient(evidence: List<Evidence>, question: String): Boolean {
        if (evidence.isEmpty()) return false
        val joined = evidence.joinToString("\n") { item ->
            item.page?.text?.takeIf { it.isNotBlank() } ?: item.snippet
        }.trim()
        if (joined.length < MIN_EVIDENCE_CHARS) return false

        val terms = SearchRanker.terms(question)
        if (terms.isEmpty()) return true
        val overlap = terms.count { joined.lowercase().contains(it) }
        val required = minOf(2, terms.size)
        if (overlap >= required) return true
        return evidence.any { (it.page?.text?.length ?: 0) >= 500 }
    }

    fun sufficientFromHits(hits: List<SearchHit>, question: String): Boolean =
        sufficient(hits.map { Evidence(hit = it, snippet = it.snippet) }, question)

    private const val MIN_EVIDENCE_CHARS = 120
}
