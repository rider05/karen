package com.karen.search

import kotlinx.coroutines.CancellationException

/**
 * Provider order preserves Karen's existing behavior: optional Exa key first,
 * then an explicitly configured SearXNG instance, then keyless DuckDuckGo,
 * then Wikipedia. The first provider with usable hits wins; failures are
 * reported, never thrown, so one outage cannot take down the whole search.
 */
class SearchRouter(
    private val config: SearchConfig,
    private val http: HttpTransport = UrlConnectionTransport()
) {
    private val searxng = SearXNGProvider(config.searxngUrl, http)

    fun variant(): String {
        val parts = mutableListOf<String>()
        if (config.exaApiKey.isNotBlank()) parts += "exa"
        if (searxng.isConfigured) {
            parts += "searx-" + SearchCacheKeys.sha256(config.searxngUrl.trim()).take(12)
        }
        parts += "keyless"
        return parts.joinToString(",")
    }

    fun providers(): List<SearchProvider> {
        val out = ArrayList<SearchProvider>(4)
        if (config.exaApiKey.isNotBlank()) out += ExaProvider(config.exaApiKey, http)
        if (searxng.isConfigured) out += searxng
        out += DuckDuckGoProvider(http)
        out += WikipediaProvider(http)
        return out
    }

    suspend fun search(query: String, limit: Int = 5): SearchBatch {
        val cleaned = query.trim().take(300)
        val capped = limit.coerceIn(1, 10)
        if (cleaned.isBlank()) return SearchBatch(errors = listOf("empty query"))
        val errors = mutableListOf<String>()
        for (provider in providers()) {
            try {
                val hits = provider.search(cleaned, capped)
                    .filter { it.title.isNotBlank() || it.snippet.isNotBlank() }
                if (hits.isNotEmpty()) {
                    return SearchBatch(hits = hits.take(capped), errors = errors.toList())
                }
                errors += "${provider.displayName}: no results"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${provider.displayName}: ${safeMessage(e)}"
            }
        }
        return SearchBatch(errors = errors)
    }

    private fun safeMessage(error: Exception): String {
        val message = error.message?.replace(Regex("[\\r\\n]+"), " ")?.trim()
        return if (message.isNullOrBlank()) {
            error::class.simpleName ?: "search failed"
        } else {
            message.take(180)
        }
    }
}

/**
 * Cache-aware search used by the agent and by the existing UI facade.
 * Expired results are served only after a live attempt fails and only while
 * they remain inside the offline-retention window.
 */
class SearchService(
    private val router: SearchRouter,
    private val cache: SearchCache? = null
) {
    suspend fun search(query: String, limit: Int = 5, allowCache: Boolean = true): SearchBatch {
        val capped = limit.coerceIn(1, 10)
        val variant = router.variant()
        val cached = if (allowCache) cache?.search(query, variant) else null
        if (cached != null && cache != null && cache.isFresh(cached)) {
            return SearchBatch(
                hits = cached.hits.take(capped),
                fromCache = true
            )
        }

        cache?.trimIfDue()
        val live = try {
            router.search(query, capped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SearchBatch(errors = listOf(e.message?.take(180) ?: "search failed"))
        }

        if (live.hits.isNotEmpty()) {
            cache?.putSearch(query, variant, live.hits)
            return SearchBatch(
                hits = live.hits.take(capped),
                errors = live.errors
            )
        }

        if (cached != null && cache != null && cache.canServeStale(cached)) {
            return SearchBatch(
                hits = cached.hits.take(capped),
                fromCache = true,
                stale = true,
                errors = live.errors
            )
        }
        return SearchBatch(errors = live.errors)
    }
}
