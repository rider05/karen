package com.karen.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded search agent. It expands at most one query, fetches at most a
 * handful of pages, and stops at an overall deadline. Every network step has
 * its own timeout, and any failure degrades to snippet-only evidence rather
 * than failing the user's turn.
 */
class SearchAgent(
    private val service: SearchService,
    private val fetcher: WebPageFetcher,
    private val options: SearchOptions = SearchOptions(),
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(question: String): SearchReport {
        val started = now()
        val deadline = started + options.overallTimeoutMs.coerceAtLeast(1_000L)
        val cleaned = QueryExpander.primary(question)
        if (cleaned.isBlank()) {
            return SearchReport(
                question = question,
                queries = emptyList(),
                insufficientEvidence = true,
                retrievedAt = started
            )
        }

        val queries = mutableListOf<String>()
        val batches = mutableListOf<Pair<String, List<SearchHit>>>()
        val errors = mutableListOf<String>()
        var anyLive = false
        var stale = false
        val maxQueries = options.maxQueries.coerceIn(1, 3)

        suspend fun attempt(query: String): SearchBatch {
            val remaining = deadline - now()
            if (remaining <= 250L) {
                return SearchBatch(errors = listOf("overall time budget exhausted"))
            }
            val window = minOf(remaining, QUERY_TIMEOUT_MS)
            val result = withTimeoutOrNull(window) {
                service.search(query, options.maxResultsPerQuery)
            }
            return result ?: SearchBatch(errors = listOf("query timed out"))
        }

        suspend fun collect(query: String): SearchBatch {
            val batch = attempt(query)
            queries += query
            batches += query to batch.hits
            errors += batch.errors
            if (!batch.fromCache) anyLive = true
            stale = stale || batch.stale
            return batch
        }

        collect(cleaned)
        var ranked = SearchRanker.rank(cleaned, batches, options.maxRanked, now())
        currentCoroutineContext().ensureActive()

        if (options.allowFollowUp &&
            queries.size < maxQueries &&
            !EvidenceVerifier.sufficientFromHits(ranked.map { it.hit }, cleaned)
        ) {
            val followUp = QueryExpander.followUp(cleaned, queries)
            if (followUp != null && deadline - now() > 500L) {
                collect(followUp)
                ranked = SearchRanker.rank(cleaned, batches, options.maxRanked, now())
            }
        }
        currentCoroutineContext().ensureActive()

        val pages = mutableMapOf<String, FetchedPage>()
        val attempted = mutableSetOf<String>()

        suspend fun fetchCandidate(candidate: RankedResult) {
            val key = UrlTools.canonicalKey(candidate.hit.url)
            if (key.isBlank() || !attempted.add(key)) return
            val remaining = deadline - now()
            if (remaining <= 500L) return
            try {
                val page = withTimeoutOrNull(minOf(remaining, PAGE_TIMEOUT_MS)) {
                    fetcher.fetch(candidate.hit.url, PAGE_MAX_CHARS)
                }
                if (page == null) {
                    errors += "page timeout: ${UrlTools.host(candidate.hit.url)}"
                    return
                }
                pages[key] = page
                UrlTools.normalize(page.finalUrl)?.let { pages[it] = page }
                page.canonicalUrl?.let { canonical ->
                    UrlTools.normalize(canonical)?.let { pages[it] = page }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "page ${UrlTools.host(candidate.hit.url)}: ${safeMessage(e)}"
            }
        }

        val candidates = ranked.filter { UrlTools.isPublicHttpUrl(it.hit.url) }
        for (candidate in candidates.take(options.maxPages.coerceIn(0, 5))) {
            if (deadline - now() <= 500L) break
            fetchCandidate(candidate)
        }
        currentCoroutineContext().ensureActive()

        var evidence = buildEvidence(ranked, pages, cleaned)
        var insufficient = !EvidenceVerifier.sufficient(evidence, cleaned)

        if (insufficient && options.maxAdditionalPages > 0) {
            val extra = candidates
                .filter { UrlTools.canonicalKey(it.hit.url) !in attempted }
                .take(options.maxAdditionalPages.coerceIn(1, 3))
            for (candidate in extra) {
                if (deadline - now() <= 500L) break
                fetchCandidate(candidate)
            }
            currentCoroutineContext().ensureActive()
            evidence = buildEvidence(ranked, pages, cleaned)
            insufficient = !EvidenceVerifier.sufficient(evidence, cleaned)
        }

        return SearchReport(
            question = cleaned,
            queries = queries.distinct(),
            ranked = ranked,
            evidence = evidence,
            fromCache = !anyLive && queries.isNotEmpty(),
            stale = stale || evidence.any { it.stale },
            insufficientEvidence = insufficient,
            errors = errors.distinct().take(12),
            retrievedAt = now()
        )
    }

    private fun buildEvidence(
        ranked: List<RankedResult>,
        pages: Map<String, FetchedPage>,
        question: String
    ): List<Evidence> = ranked.mapNotNull { result ->
        val page = pages[result.canonicalUrl] ?: pages[UrlTools.canonicalKey(result.hit.url)]
        val pageText = page?.text.orEmpty()
        val excerpt = if (pageText.isNotBlank()) {
            EvidenceVerifier.bestExcerpt(pageText, question, EXCERPT_CHARS)
        } else {
            result.hit.snippet.take(EXCERPT_CHARS)
        }
        if (excerpt.isBlank() && page == null) return@mapNotNull null
        Evidence(
            hit = result.hit.copy(
                publishedAtMillis = result.hit.publishedAtMillis ?: page?.publishedAtMillis
            ),
            snippet = excerpt,
            page = page,
            fromCache = page?.fromCache == true,
            stale = page?.stale == true,
            suspiciousInstructions = EvidenceVerifier.suspicious(excerpt) ||
                EvidenceVerifier.suspicious(pageText)
        )
    }

    private fun safeMessage(error: Exception): String {
        val message = error.message?.replace(Regex("[\\r\\n]+"), " ")?.trim()
        return if (message.isNullOrBlank()) {
            error::class.simpleName ?: "fetch failed"
        } else {
            message.take(140)
        }
    }

    companion object {
        private const val QUERY_TIMEOUT_MS = 8_000L
        private const val PAGE_TIMEOUT_MS = 8_000L
        private const val PAGE_MAX_CHARS = 12_000
        private const val EXCERPT_CHARS = 900
    }
}
