package com.karen.search

import org.json.JSONObject

/**
 * DuckDuckGo Instant Answer API. This is the project's existing keyless
 * provider; it is not a full web index and its availability is not guaranteed.
 */
class DuckDuckGoProvider(
    private val http: HttpTransport = UrlConnectionTransport()
) : SearchProvider {
    override val id: String = "duckduckgo"
    override val displayName: String = "DuckDuckGo"

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = query.trim().take(300)
        if (q.isBlank()) return emptyList()
        val url = "https://api.duckduckgo.com/?q=${UrlTools.encodeQuery(q)}" +
            "&format=json&no_html=1&skip_disambig=1"
        val response = http.get(
            url = url,
            headers = mapOf("Accept" to "application/json"),
            connectTimeoutMs = 5_000,
            readTimeoutMs = 8_000,
            maxBytes = 256 * 1024
        )
        if (response.code !in 200..299) {
            throw HttpException(response.code, "DuckDuckGo HTTP ${response.code}")
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
            val out = mutableListOf<SearchHit>()
            val heading = root.optString("Heading", "")
            val abstract = root.optString("AbstractText", "")
            if (abstract.isNotBlank()) {
                val abstractUrl = root.optString("AbstractURL", "")
                out += SearchHit(
                    title = heading.ifBlank { "Summary" },
                    url = abstractUrl,
                    snippet = abstract.take(500),
                    source = UrlTools.host(abstractUrl).ifBlank {
                        root.optString("AbstractSource", "").ifBlank { "DuckDuckGo" }
                    },
                    provider = "duckduckgo"
                )
            }

            val related = root.optJSONArray("RelatedTopics") ?: return out.take(max)
            for (i in 0 until related.length()) {
                if (out.size >= max) break
                val item = related.optJSONObject(i) ?: continue
                if (item.has("Topics")) {
                    val nested = item.optJSONArray("Topics") ?: continue
                    for (j in 0 until nested.length()) {
                        if (out.size >= max) break
                        val sub = nested.optJSONObject(j) ?: continue
                        addTopic(sub, out)
                    }
                } else {
                    addTopic(item, out)
                }
            }
            return out.take(max)
        }

        private fun addTopic(item: JSONObject, out: MutableList<SearchHit>) {
            val text = item.optString("Text", "")
            if (text.isBlank()) return
            val firstUrl = item.optString("FirstURL", "")
            out += SearchHit(
                title = firstSentence(text),
                url = firstUrl,
                snippet = text.take(400),
                source = UrlTools.host(firstUrl).ifBlank { "DuckDuckGo" },
                provider = "duckduckgo"
            )
        }

        private fun firstSentence(text: String): String {
            val cut = listOf(". ", "! ", "? ").mapNotNull {
                val index = text.indexOf(it)
                if (index > 0) index + 1 else null
            }.minOrNull()
            val head = if (cut != null) text.take(cut) else text.take(80)
            return head.ifBlank { "Result" }.take(120)
        }
    }
}
