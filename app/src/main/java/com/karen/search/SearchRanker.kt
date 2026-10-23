package com.karen.search

/**
 * Deterministic merge / dedupe / ranking. Search engines are noisy and often
 * overlap; one stable score keeps ordering reproducible across runs and keeps
 * the agent from spending page-fetch budget on duplicate URLs.
 */
object SearchRanker {
    fun rank(
        question: String,
        batches: List<Pair<String, List<SearchHit>>>,
        limit: Int = 8,
        now: Long = System.currentTimeMillis()
    ): List<RankedResult> {
        val terms = terms(question)
        val merged = LinkedHashMap<String, MergedHit>()

        batches.forEachIndexed { batchIndex, (query, hits) ->
            hits.forEachIndexed { index, hit ->
                val canonical = canonicalOf(hit)
                if (canonical.isBlank()) return@forEachIndexed
                val entry = merged.getOrPut(canonical) { MergedHit(canonical, hit) }
                entry.absorb(hit, query, batchIndex, index)
            }
        }

        val fresh = QueryExpander.isFreshQuery(question)
        return merged.values
            .map { entry ->
                RankedResult(
                    hit = entry.hit,
                    score = score(entry, terms, fresh, now),
                    canonicalUrl = entry.canonical,
                    matchedQueries = entry.queries.toList()
                )
            }
            .sortedWith(
                compareByDescending<RankedResult> { it.score }
                    .thenBy { it.hit.title.lowercase() }
                    .thenBy { it.canonicalUrl }
            )
            .take(limit.coerceAtLeast(1))
    }

    fun canonicalOf(hit: SearchHit): String {
        val normalized = UrlTools.normalize(hit.url)
        if (!normalized.isNullOrBlank()) return normalized
        val title = hit.title.trim().lowercase()
        if (title.isBlank()) return ""
        return "title:${SearchCacheKeys.sha256("$title|${hit.source.lowercase()}").take(24)}"
    }

    /** Content words used by ranking and excerpt selection. */
    fun terms(text: String): Set<String> = QueryExpander.contentTerms(text)

    fun overlap(text: String, terms: Set<String>): Int {
        if (terms.isEmpty()) return 0
        val lower = text.lowercase()
        return terms.count { lower.contains(it) }
    }

    private fun score(
        entry: MergedHit,
        terms: Set<String>,
        fresh: Boolean,
        now: Long
    ): Double {
        val hit = entry.hit
        val title = hit.title.lowercase()
        val body = "${hit.snippet} ${hit.url}".lowercase()
        var score = 0.0

        if (terms.isNotEmpty()) {
            var matched = 0.0
            terms.forEach { term ->
                if (title.contains(term)) matched += 2.0
                else if (body.contains(term)) matched += 1.0
            }
            score += matched / terms.size
        }

        val phrase = hit.title.lowercase().trim()
        if (phrase.isNotBlank() && phrase.length <= 120 && terms.isNotEmpty() &&
            terms.all { title.contains(it) }
        ) {
            score += 0.6
        }

        score += providerWeight(hit.provider) * 0.25
        if (hit.url.startsWith("https://", ignoreCase = true)) score += 0.05
        if (hit.snippet.length >= 180) score += 0.05

        if (fresh) {
            val published = hit.publishedAtMillis
            if (published != null && published > 0L) {
                val ageDays = (now - published).coerceAtLeast(0L) / DAY_MS
                score += when {
                    ageDays <= 30 -> 0.9
                    ageDays <= 180 -> 0.45
                    ageDays <= 365 -> 0.2
                    else -> 0.0
                }
            }
        }

        // Preserve the provider's own order, but only as a small tie-breaker.
        score += (0.20 - entry.batchIndex * 0.05).coerceAtLeast(0.0)
        score += (0.08 - entry.resultIndex * 0.01).coerceAtLeast(0.0)
        return score
    }

    private fun providerWeight(provider: String): Double = when (provider.lowercase()) {
        "exa" -> 1.0
        "searxng" -> 0.8
        "duckduckgo" -> 0.5
        "wikipedia" -> 0.3
        else -> 0.4
    }

    private class MergedHit(val canonical: String, first: SearchHit) {
        var hit: SearchHit = first
        var batchIndex: Int = Int.MAX_VALUE
        var resultIndex: Int = Int.MAX_VALUE
        val queries = LinkedHashSet<String>()

        fun absorb(next: SearchHit, query: String, nextBatch: Int, nextResult: Int) {
            queries += query
            batchIndex = minOf(batchIndex, nextBatch)
            resultIndex = minOf(resultIndex, nextResult)
            hit = SearchHit(
                title = hit.title.ifBlank { next.title },
                url = hit.url.ifBlank { next.url },
                snippet = if (next.snippet.length > hit.snippet.length) next.snippet else hit.snippet,
                source = hit.source.ifBlank { next.source },
                publishedAtMillis = listOfNotNull(hit.publishedAtMillis, next.publishedAtMillis)
                    .maxOrNull(),
                provider = hit.provider.ifBlank { next.provider }
            )
        }
    }

    private const val DAY_MS = 24 * 60 * 60_000L
}
