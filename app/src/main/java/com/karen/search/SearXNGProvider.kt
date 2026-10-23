package com.karen.search

import org.json.JSONObject
import java.net.URI

/**
 * Optional user-supplied SearXNG instance. No instance is hardcoded and no
 * account/key is required. Many public instances disable the JSON API, so this
 * provider degrades to the existing providers when it fails.
 */
class SearXNGProvider(
    instanceUrl: String,
    private val http: HttpTransport = UrlConnectionTransport()
) : SearchProvider {
    override val id: String = "searxng"
    override val displayName: String = "SearXNG"

    private val endpoint: String? = endpointOf(instanceUrl)

    val isConfigured: Boolean get() = endpoint != null

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = query.trim().take(300)
        val base = endpoint ?: return emptyList()
        if (q.isBlank()) return emptyList()
        val url = "$base?q=${UrlTools.encodeQuery(q)}" +
            "&format=json&language=en&safesearch=1&categories=general"
        val response = http.get(
            url = url,
            headers = mapOf("Accept" to "application/json"),
            connectTimeoutMs = 5_000,
            readTimeoutMs = 8_000,
            maxBytes = 512 * 1024,
            // A self-hosted instance is commonly on localhost/LAN, so allow
            // private hosts for this explicitly user-configured endpoint.
            allowPrivateHosts = true
        )
        if (response.code !in 200..299) {
            throw HttpException(response.code, "SearXNG HTTP ${response.code}")
        }
        return parse(response.body, limit)
    }

    companion object {
        fun endpointOf(raw: String): String? {
            val value = raw.trim().take(300)
            if (value.isBlank()) return null
            return try {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null
                if (uri.userInfo != null) return null
                val authority = uri.rawAuthority ?: return null
                if (uri.host.isNullOrBlank()) return null
                val path = (uri.path ?: "").trimEnd('/')
                val base = "$scheme://$authority$path"
                if (path.endsWith("/search")) base else "$base/search"
            } catch (_: Exception) {
                null
            }
        }

        fun parse(json: String, limit: Int = 5): List<SearchHit> {
            val max = limit.coerceIn(1, 10)
            val root = try {
                JSONObject(json)
            } catch (_: Exception) {
                return emptyList()
            }
            val results = root.optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).mapNotNull { index ->
                if (index >= max) return@mapNotNull null
                val item = results.optJSONObject(index) ?: return@mapNotNull null
                val title = item.optString("title", "").trim()
                val url = item.optString("url", "").trim()
                if (title.isBlank() || url.isBlank()) return@mapNotNull null
                val snippet = (item.optString("content", "")
                    .ifBlank { item.optString("snippet", "") })
                    .replace(Regex("<[^>]*>"), "")
                    .trim()
                    .take(800)
                SearchHit(
                    title = title,
                    url = url,
                    snippet = snippet,
                    source = UrlTools.host(url).ifBlank {
                        item.optString("engine", "").ifBlank { "SearXNG" }
                    },
                    publishedAtMillis = DateParser.parse(
                        item.optString("publishedDate", "")
                            .ifBlank { item.optString("published_date", "") }
                    ),
                    provider = "searxng"
                )
            }.take(max)
        }
    }
}
