package com.karen.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Keyless web search (DuckDuckGo Instant Answer + Wikipedia fallback).
 * Same HttpURLConnection + org.json stack as CloudChatApi — no new deps.
 * Only the query string leaves the device, and only after first-run consent.
 */
object WebSearch {
    data class Result(val title: String, val snippet: String, val source: String)

    private val TRIGGERS = listOf(
        "latest", "news", "today's", "current price", "price of", "weather",
        "who won", "score", "stock price", "release date", "happening",
        "right now", "this week", "announced", "breaking"
    )

    /** Conservative on-device trigger: explicit intent or fresh-info keywords. */
    fun needsSearch(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isBlank()) return false
        if (t.startsWith("/search") || t.startsWith("search:") || t.startsWith("search ")) return true
        return TRIGGERS.any { t.contains(it) }
    }

    /** Strips an explicit search prefix before sending the query out. */
    fun cleanQuery(text: String): String {
        val t = text.trim()
        return when {
            t.startsWith("/search", ignoreCase = true) -> t.removePrefix("/search").trim(' ', ':')
            t.startsWith("search:", ignoreCase = true) -> t.removePrefix("search:").trim()
            t.startsWith("search ", ignoreCase = true) -> t.removePrefix("search ").trim()
            else -> t
        }.ifBlank { t }.take(300)
    }

    suspend fun search(query: String): List<Result> = withContext(Dispatchers.IO) {
        val ddg = try {
            duckDuckGo(query)
        } catch (_: Exception) {
            emptyList()
        }
        if (ddg.isNotEmpty()) return@withContext ddg.take(5)
        try {
            wikipedia(query)
        } catch (_: Exception) {
            emptyList()
        }.take(5)
    }

    /** Compact block appended to the model prompt (or shown directly by the mock). */
    fun formatForModel(results: List<Result>): String {
        if (results.isEmpty()) return ""
        return buildString {
            append("Fresh web results (may be incomplete — say so if they don't answer):\n")
            results.forEachIndexed { i, r ->
                append("${i + 1}. ${r.title} [${r.source}]\n${r.snippet}\n")
            }
        }.take(3000)
    }

    private fun duckDuckGo(query: String): List<Result> {
        val url = URL("https://api.duckduckgo.com/?q=${enc(query)}&format=json&no_html=1&skip_disambig=1")
        val json = JSONObject(get(url))
        val out = mutableListOf<Result>()
        val heading = json.optString("Heading", "")
        val abstract = json.optString("AbstractText", "")
        if (abstract.isNotBlank()) {
            out.add(Result(heading.ifBlank { "Summary" }, abstract.take(400), json.optString("AbstractSource", "DuckDuckGo").ifBlank { "DuckDuckGo" }))
        }
        val related = json.optJSONArray("RelatedTopics") ?: return out
        for (i in 0 until related.length()) {
            val item = related.optJSONObject(i) ?: continue
            // Nested entries are topic groups — descend one level.
            if (item.has("Topics")) {
                val nested = item.optJSONArray("Topics") ?: continue
                for (j in 0 until nested.length()) {
                    val sub = nested.optJSONObject(j) ?: continue
                    val text = sub.optString("Text", "")
                    if (text.isNotBlank()) {
                        out.add(Result(firstSentence(text), text.take(300), domainOf(sub.optString("FirstURL", ""))))
                        if (out.size >= 5) return out
                    }
                }
            } else {
                val text = item.optString("Text", "")
                if (text.isNotBlank()) {
                    out.add(Result(firstSentence(text), text.take(300), domainOf(item.optString("FirstURL", ""))))
                    if (out.size >= 5) return out
                }
            }
        }
        return out
    }

    private fun wikipedia(query: String): List<Result> {
        val url = URL("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${enc(query)}&format=json&srlimit=5")
        val json = JSONObject(get(url))
        val hits = json.optJSONObject("query")?.optJSONArray("search") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { i ->
            val hit = hits.optJSONObject(i) ?: return@mapNotNull null
            val title = hit.optString("title", "")
            val snippet = hit.optString("snippet", "").replace(Regex("<[^>]*>"), "")
            if (title.isBlank() || snippet.isBlank()) null
            else Result(title, snippet.take(300), "Wikipedia")
        }
    }

    private fun get(url: URL): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Karen/1.0 (on-device assistant)")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        conn.disconnect()
        if (code !in 200..299) throw WebSearchException(code, text.take(200))
        return text
    }

    private fun enc(q: String): String = URLEncoder.encode(q, "UTF-8")

    private fun firstSentence(text: String): String {
        val cut = listOf(". ", "! ", "? ").mapNotNull {
            val i = text.indexOf(it)
            if (i > 0) i + 1 else null
        }.minOrNull()
        val head = if (cut != null) text.take(cut) else text.take(80)
        return head.ifBlank { "Result" }.take(80)
    }

    private fun domainOf(url: String): String {
        if (url.isBlank()) return "DuckDuckGo"
        return try {
            URL(url).host.removePrefix("www.").ifBlank { "DuckDuckGo" }
        } catch (_: Exception) {
            "DuckDuckGo"
        }
    }
}

class WebSearchException(val status: Int, message: String) : Exception(message)
