package com.karen.search

import org.json.JSONObject

/**
 * Optional Exa neural search using the key the user may already have stored in
 * Settings. Karen's existing behavior is preserved: Exa is tried first when a
 * key exists, then the keyless providers.
 */
class ExaProvider(
    private val apiKey: String,
    private val http: HttpTransport = UrlConnectionTransport()
) : SearchProvider {
    override val id: String = "exa"
    override val displayName: String = "Exa"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = query.trim().take(300)
        if (q.isBlank() || apiKey.isBlank()) return emptyList()
        val body = JSONObject()
            .put("query", q)
            .put("numResults", limit.coerceIn(1, 10))
            .put("type", "auto")
            .put(
                "contents",
                JSONObject().put("text", JSONObject().put("maxCharacters", 800))
            )
            .toString()
        val response = http.post(
            url = "https://api.exa.ai/search",
            body = body,
            headers = mapOf(
                "x-api-key" to apiKey,
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ),
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
            maxBytes = 512 * 1024
        )
        if (response.code !in 200..299) {
            val reason = when (response.code) {
                401, 403 -> "Exa key rejected"
                429 -> "Exa quota exhausted"
                else -> "Exa HTTP ${response.code}"
            }
            throw HttpException(response.code, reason)
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
            val arr = root.optJSONArray("results") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { index ->
                if (index >= max) return@mapNotNull null
                val item = arr.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url", "").trim()
                val snippet = item.optString("text", "").trim().take(800)
                if (snippet.isBlank() && url.isBlank()) return@mapNotNull null
                val title = item.optString("title", "").trim()
                    .ifBlank { UrlTools.host(url).ifBlank { "Result" } }
                SearchHit(
                    title = title,
                    url = url,
                    snippet = snippet,
                    source = UrlTools.host(url).ifBlank { "Exa" },
                    publishedAtMillis = DateParser.parse(item.optString("publishedDate", "")),
                    provider = "exa"
                )
            }.take(max)
        }
    }
}
