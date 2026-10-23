package com.karen.search

import java.security.MessageDigest

/**
 * A single normalized web result. Field defaults keep construction compatible
 * with the original three-argument [com.karen.ui.WebSearch.Result] shape.
 */
data class SearchHit(
    val title: String,
    val url: String = "",
    val snippet: String = "",
    val source: String = "",
    val publishedAtMillis: Long? = null,
    val provider: String = ""
)

/** A swappable search backend. Providers never retry and never log credentials. */
interface SearchProvider {
    val id: String
    val displayName: String
    suspend fun search(query: String, limit: Int): List<SearchHit>
}

/** Outcome of one provider pass, including whether it came from the local cache. */
data class SearchBatch(
    val hits: List<SearchHit> = emptyList(),
    val fromCache: Boolean = false,
    val stale: Boolean = false,
    val errors: List<String> = emptyList()
)

/**
 * Runtime settings for the search stack. The UI reads user prefs in
 * [com.karen.ui.WebSearch] and maps them here, so this package has no
 * dependency on the UI or preference layer.
 */
data class SearchConfig(
    /** Optional user-supplied SearXNG base URL. Blank preserves the old path. */
    val searxngUrl: String = "",
    /** Existing optional Exa key, read from UserPrefs by the UI facade. */
    val exaApiKey: String = "",
    val searchCacheTtlMs: Long = 15 * 60_000L,
    val pageCacheTtlMs: Long = 24 * 60 * 60_000L,
    val retainExpiredMs: Long = 7 * 24 * 60 * 60_000L
)

/** Hard limits for one agent run. */
data class SearchOptions(
    val maxQueries: Int = 2,
    val maxResultsPerQuery: Int = 5,
    val maxRanked: Int = 8,
    val maxPages: Int = 2,
    val maxAdditionalPages: Int = 1,
    val maxContextChars: Int = 6_000,
    val overallTimeoutMs: Long = 20_000L,
    val allowFollowUp: Boolean = true
)

/** Readable page content extracted from one HTML document. */
data class ExtractedPage(
    val title: String,
    val text: String,
    val canonicalUrl: String? = null,
    val publishedAtMillis: Long? = null
)

/** A page either fetched live or reused from the local page cache. */
data class FetchedPage(
    val requestedUrl: String,
    val finalUrl: String,
    val title: String,
    val text: String,
    val canonicalUrl: String? = null,
    val publishedAtMillis: Long? = null,
    val fetchedAt: Long = 0L,
    val fromCache: Boolean = false,
    val stale: Boolean = false
)

/** One search result plus any page content gathered for it. */
data class Evidence(
    val hit: SearchHit,
    val snippet: String,
    val page: FetchedPage? = null,
    val fromCache: Boolean = false,
    val stale: Boolean = false,
    val suspiciousInstructions: Boolean = false
)

data class RankedResult(
    val hit: SearchHit,
    val score: Double,
    val canonicalUrl: String,
    val matchedQueries: List<String> = emptyList()
)

/** Everything the bounded agent collected for one user question. */
data class SearchReport(
    val question: String,
    val queries: List<String>,
    val ranked: List<RankedResult> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val fromCache: Boolean = false,
    val stale: Boolean = false,
    val insufficientEvidence: Boolean = false,
    val errors: List<String> = emptyList(),
    val retrievedAt: Long = 0L
)

/** Cached search results are keyed by a hash, never by the raw user query. */
data class CachedSearch(
    val hits: List<SearchHit>,
    val fetchedAt: Long,
    val expiresAt: Long
) {
    fun isFresh(now: Long): Boolean = now < expiresAt
}

data class CachedPage(
    val url: String,
    val title: String,
    val text: String,
    val canonicalUrl: String?,
    val publishedAtMillis: Long?,
    val fetchedAt: Long,
    val expiresAt: Long
) {
    fun isFresh(now: Long): Boolean = now < expiresAt
}

/**
 * Local persistence seam. The SQLite implementation lives in the Android
 * source set; unit tests can use a small in-memory fake without Android.
 */
interface SearchCacheStore {
    fun search(key: String): CachedSearch?
    fun putSearch(key: String, value: CachedSearch)
    fun page(key: String): CachedPage?
    fun putPage(key: String, value: CachedPage)
    fun trim(now: Long)
}

data class SearchCachePolicy(
    val searchTtlMs: Long = 15 * 60_000L,
    val pageTtlMs: Long = 24 * 60 * 60_000L,
    val retainExpiredMs: Long = 7 * 24 * 60 * 60_000L
) {
    fun isRetained(fetchedAt: Long, now: Long): Boolean =
        now - fetchedAt <= retainExpiredMs
}

/** Stable, privacy-preserving cache keys. */
object SearchCacheKeys {
    fun searchKey(query: String, variant: String = ""): String {
        val normalized = query.trim().lowercase().replace(Regex("\\s+"), " ")
        val mode = variant.trim().lowercase().takeIf { it.isNotBlank() }
        val material = if (mode == null) "search-v1|$normalized" else "search-v1|$normalized|$mode"
        return sha256(material)
    }

    fun pageKey(url: String): String = sha256("page-v1|${UrlTools.normalize(url) ?: url.trim()}")

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
