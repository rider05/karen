package com.karen.search

import java.net.URI
import java.net.URLEncoder

/**
 * URL normalization and safety checks used for deduplication and page fetching.
 * These are deliberately conservative: anything that is not an ordinary
 * public http(s) URL is not fetched.
 */
object UrlTools {
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "dclid", "yclid", "msclkid", "mc_cid", "mc_eid",
        "igshid", "mkt_tok", "ref", "ref_src", "ref_url", "_hsenc", "_hsmi"
    )

    /**
     * Lowercase scheme/host, drop default ports and fragments, remove common
     * tracking parameters, sort the remaining query, and trim a trailing slash.
     * Returns null for non-http(s) or malformed URLs.
     */
    fun normalize(raw: String): String? {
        val value = unwrapRedirect(raw).trim().take(MAX_URL)
        if (value.isBlank()) return null
        return try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (uri.userInfo != null) return null
            val host = uri.host?.lowercase() ?: return null
            if (host.isBlank()) return null
            var port = uri.port
            if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) port = -1
            val path = when {
                uri.path.isNullOrBlank() -> "/"
                uri.path.length > 1 && uri.path.endsWith("/") -> uri.path.dropLast(1)
                else -> uri.path
            }
            val query = uri.rawQuery?.let { q ->
                q.split('&')
                    .filter { it.isNotBlank() }
                    .filterNot { part ->
                        val key = part.substringBefore('=').lowercase()
                        key.startsWith("utm_") || key in TRACKING_PARAMS
                    }
                    .sorted()
                    .joinToString("&")
                    .takeIf { it.isNotBlank() }
            }
            buildString {
                append(scheme).append("://").append(host)
                if (port != -1) append(':').append(port)
                append(path)
                if (query != null) append('?').append(query)
            }.take(MAX_URL)
        } catch (_: Exception) {
            null
        }
    }

    fun canonicalKey(raw: String): String = normalize(raw) ?: raw.trim()

    fun host(raw: String): String = try {
        URI(raw.trim()).host?.lowercase()?.removePrefix("www.").orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun resolve(base: String, reference: String): String? {
        val ref = reference.trim()
        if (ref.isBlank()) return null
        return try {
            val resolved = URI(base).resolve(ref).toString()
            if (isHttpUrl(resolved)) resolved else null
        } catch (_: Exception) {
            null
        }
    }

    fun isHttpUrl(raw: String): Boolean = try {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase()
        (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    } catch (_: Exception) {
        false
    }

    /**
     * Blocks obvious loopback/private/link-local literals and single-label
     * hostnames. DNS names that resolve to private addresses are not resolved
     * here (that would add a blocking lookup); page fetches also require normal
     * HTML and are bounded to one request per page.
     */
    fun isPublicHttpUrl(raw: String): Boolean {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return false
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (host == "local" || host.endsWith(".local")) return false
        if (host == "internal" || host.endsWith(".internal")) return false
        if (host == "metadata.google.internal") return false

        if (host.startsWith("[") || host.contains(':')) {
            val v6 = host.removePrefix("[").removeSuffix("]")
            if (v6 == "::1" || v6 == "::" || v6.startsWith("fc") ||
                v6.startsWith("fd") || v6.startsWith("fe80") || v6.startsWith("ff")
            ) return false
            return true
        }

        val v4 = host.split('.')
        if (v4.size == 4 && v4.all { it.toIntOrNull() != null }) {
            val a = v4[0].toInt()
            val b = v4[1].toInt()
            if (a == 0 || a == 10 || a == 127) return false
            if (a == 169 && b == 254) return false
            if (a == 172 && b in 16..31) return false
            if (a == 192 && b == 168) return false
            if (a == 100 && b in 64..127) return false
            if (a == 198 && b in 18..19) return false
            if (a >= 224) return false
            return true
        }

        // Single-label hosts are usually local device names, not public sites.
        if (!host.contains('.')) return false
        return true
    }

    fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun sourceOf(url: String): String = host(url).ifBlank { "web" }

    /**
     * Search engines sometimes return their own redirect wrapper rather than
     * the destination. Unwraps the common keyless ones so page fetching and
     * deduplication work on the real URL. Unknown wrappers are returned as-is.
     */
    fun unwrapRedirect(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return value
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return value
        }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return value
        val target = when {
            (host == "duckduckgo.com" || host.endsWith(".duckduckgo.com")) &&
                (uri.path ?: "").startsWith("/l/") -> queryParam(uri.rawQuery, "uddg")
            (host == "google.com" || host.endsWith(".google.com")) &&
                uri.path == "/url" -> queryParam(uri.rawQuery, "q")
                    ?: queryParam(uri.rawQuery, "url")
            else -> null
        } ?: return value
        return try {
            val decoded = java.net.URLDecoder.decode(target, "UTF-8").trim()
            if (isHttpUrl(decoded)) decoded else value
        } catch (_: Exception) {
            value
        }
    }

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrBlank()) return null
        return rawQuery.split('&').firstOrNull { part ->
            part.substringBefore('=').equals(name, ignoreCase = true)
        }?.substringAfter('=', "")
            ?.takeIf { it.isNotBlank() }
    }

    private const val MAX_URL = 2048
}
