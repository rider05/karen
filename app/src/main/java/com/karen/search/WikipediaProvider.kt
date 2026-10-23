package com.karen.search

import org.json.JSONObject

/**
 * Wikipedia search API fallback. Titles and snippets come from the API; the
 * canonical-ish curid URL is derived from the returned page id.
 */
class WikipediaProvider(
    private val http: HttpTransport = UrlConnectionTransport()
) : SearchProvider {
    override val id: String = "wikipedia"
    override val displayName: String = "Wikipedia"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = query.trim().take(300)
        if (q.isBlank()) return emptyList()
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=${UrlTools.encodeQuery(q)}&format=json&srlimit=${limit.coerceIn(1, 10)}"
        val response = http.get(
            url = url,
            headers = mapOf("Accept" to "application/json"),
            connectTimeoutMs = 5_000,
            readTimeoutMs = 8_000,
            maxBytes = 256 * 1024
        )
        if (response.code !in 200..299) {
            throw HttpException(response.code, "Wikipedia HTTP ${response.code}")
        }
        return parse(response.body, limit)
    }

    companion object {
        fun parse(json: String, limit: Int = 5): List<SearchHit> {
            val max = limit.coerceIn(1, 10)
            val root = try {
                JSONObject(json)
            } catch (_: Exception) {
                return emptyList()
            }
            val hits = root.optJSONObject("query")?.optJSONArray("search")
                ?: return emptyList()
            return (0 until hits.length()).mapNotNull { index ->
                if (index >= max) return@mapNotNull null
                val hit = hits.optJSONObject(index) ?: return@mapNotNull null
                val title = hit.optString("title", "").trim()
                val snippet = hit.optString("snippet", "")
                    .replace(Regex("<[^>]*>"), "")
                    .replace("&quot;", "\"")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .trim()
                if (title.isBlank() || snippet.isBlank()) return@mapNotNull null
                val pageId = hit.optLong("pageid", 0L)
                val pageUrl = if (pageId > 0) "https://en.wikipedia.org/?curid=$pageId" else ""
                SearchHit(
                    title = title,
                    url = pageUrl,
                    snippet = snippet.take(400),
                    source = "Wikipedia",
                    provider = "wikipedia"
                )
            }.take(max)
        }
    }
}
