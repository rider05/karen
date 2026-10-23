package com.karen.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight robots.txt handling for page fetching. It is deliberately
 * forgiving: a missing/unreachable robots file allows the page, an explicit
 * 401/403 blocks it, and only rules for the most specific matching agent are
 * applied.
 */
class RobotsPolicy(
    private val http: HttpTransport = UrlConnectionTransport(),
    private val userAgent: String = "Karen"
) {
    private val cache = ConcurrentHashMap<String, List<Rule>>()

    suspend fun isAllowed(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val authority = uri.rawAuthority ?: return false
        val key = "$scheme://$authority"
        val rules = cache[key] ?: load(key).also { cache[key] = it }
        val path = buildString {
            append(uri.rawPath?.ifBlank { "/" } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        return allows(rules, path)
    }

    private suspend fun load(base: String): List<Rule> {
        return try {
            val response = withTimeoutOrNull(ROBOTS_TIMEOUT_MS) {
                http.get(
                    url = "$base/robots.txt",
                    headers = mapOf("Accept" to "text/plain,*/*;q=0.1"),
                    connectTimeoutMs = 3_000,
                    readTimeoutMs = 3_000,
                    maxBytes = 64 * 1024
                )
            } ?: return emptyList()
            when (response.code) {
                in 200..299 -> parse(response.body, userAgent)
                401, 403 -> listOf(Rule(false, "/"))
                else -> emptyList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    data class Rule(val allow: Boolean, val pattern: String)

    companion object {
        fun parse(text: String, userAgent: String = "Karen"): List<Rule> {
            val groups = mutableListOf<Group>()
            var agents = mutableListOf<String>()
            var rules = mutableListOf<Rule>()

            fun flush() {
                if (agents.isNotEmpty()) groups += Group(agents.toList(), rules.toList())
                agents = mutableListOf()
                rules = mutableListOf()
            }

            for (rawLine in text.lineSequence()) {
                val line = rawLine.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val key = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                when (key) {
                    "user-agent" -> {
                        if (rules.isNotEmpty()) flush()
                        if (value.isNotBlank()) agents += value
                    }
                    "allow" -> if (agents.isNotEmpty()) {
                        rules += Rule(true, value)
                    }
                    "disallow" -> if (agents.isNotEmpty() && value.isNotBlank()) {
                        rules += Rule(false, value)
                    }
                }
            }
            flush()

            val token = userAgent.substringBefore('/').substringBefore(' ').lowercase()
            val specific = groups.filter { group ->
                group.agents.any { it.lowercase() == token || it.lowercase().startsWith("$token-") }
            }
            val wildcard = groups.filter { group -> group.agents.any { it == "*" } }
            return when {
                specific.isNotEmpty() -> specific.flatMap { it.rules }
                wildcard.isNotEmpty() -> wildcard.flatMap { it.rules }
                else -> emptyList()
            }
        }

        fun allows(rules: List<Rule>, path: String): Boolean {
            if (rules.isEmpty()) return true
            val target = path.ifBlank { "/" }
            var bestLength = -1
            var bestAllow = true
            for (rule in rules) {
                if (rule.pattern.isBlank()) continue
                val regex = patternRegex(rule.pattern) ?: continue
                if (!regex.containsMatchIn(target)) continue
                val length = rule.pattern.count { it != '*' && it != '$' }
                if (length > bestLength || (length == bestLength && rule.allow)) {
                    bestLength = length
                    bestAllow = rule.allow
                }
            }
            return if (bestLength < 0) true else bestAllow
        }

        private fun patternRegex(pattern: String): Regex? {
            val anchored = pattern.endsWith("$")
            val body = if (anchored) pattern.dropLast(1) else pattern
            if (body.isBlank() && !anchored) return null
            val out = StringBuilder("^")
            for (char in body) {
                when (char) {
                    '*' -> out.append(".*")
                    else -> out.append(Regex.escape(char.toString()))
                }
            }
            if (anchored) out.append('$')
            return try {
                Regex(out.toString())
            } catch (_: Exception) {
                null
            }
        }

        private data class Group(val agents: List<String>, val rules: List<Rule>)
        private const val ROBOTS_TIMEOUT_MS = 3_500L
    }
}
