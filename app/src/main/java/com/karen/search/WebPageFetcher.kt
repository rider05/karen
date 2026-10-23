package com.karen.search

import kotlinx.coroutines.CancellationException

class PageFetchException(message: String) : Exception(message)

/**
 * Fetches one public HTML page, extracts readable text, and caches the result.
 * Only explicit http(s) URLs are requested; private/link-local hosts, robots
 * exclusions, oversized bodies and non-HTML responses are refused.
 */
class WebPageFetcher(
    private val http: HttpTransport = UrlConnectionTransport(),
    private val cache: SearchCache? = null,
    private val robots: RobotsPolicy = RobotsPolicy(http),
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun fetch(url: String, maxChars: Int = 12_000): FetchedPage {
        val requested = UrlTools.unwrapRedirect(url).trim().take(2_048)
        if (!UrlTools.isPublicHttpUrl(requested)) {
            throw PageFetchException("not a public http(s) URL")
        }

        val cached = cache?.page(requested)
        if (cached != null && cache.isFresh(cached)) {
            return fromCached(cached, requested, stale = false)
        }
        val stale = cached?.takeIf { cache.canServeStale(it) }

        try {
            if (!robots.isAllowed(requested)) {
                throw PageFetchException("blocked by robots.txt")
            }
            val response = http.get(
                url = requested,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.1",
                    "Accept-Language" to "en-US,en;q=0.8"
                ),
                connectTimeoutMs = 5_000,
                readTimeoutMs = 8_000,
                maxBytes = 512 * 1024,
                allowPrivateHosts = false
            )
            if (response.code !in 200..299) {
                throw HttpException(response.code, "page HTTP ${response.code}")
            }

            val contentType = response.contentType.lowercase()
            val plainText = contentType.contains("text/plain")
            if (contentType.isNotBlank() &&
                !contentType.contains("text/html") &&
                !contentType.contains("application/xhtml") &&
                !plainText
            ) {
                throw UnsupportedContentTypeException(response.contentType)
            }
            if (contentType.isBlank() && !looksLikeHtml(response.body)) {
                throw UnsupportedContentTypeException("")
            }

            val extracted = if (plainText) {
                ExtractedPage(
                    title = UrlTools.host(response.finalUrl).ifBlank { "Page" },
                    text = response.body.take(maxChars.coerceIn(500, 40_000))
                )
            } else {
                HtmlContentExtractor.extract(
                    html = response.body,
                    baseUrl = response.finalUrl,
                    maxChars = maxChars
                ) ?: throw UnsupportedContentTypeException("no readable text")
            }

            val page = FetchedPage(
                requestedUrl = requested,
                finalUrl = response.finalUrl,
                title = extracted.title,
                text = extracted.text,
                canonicalUrl = extracted.canonicalUrl ?: response.finalUrl.takeIf {
                    UrlTools.isPublicHttpUrl(it)
                },
                publishedAtMillis = extracted.publishedAtMillis,
                fetchedAt = now(),
                fromCache = false,
                stale = false
            )
            cache?.putPage(page)
            return page
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (stale != null) return fromCached(stale, requested, stale = true)
            throw e
        }
    }

    private fun fromCached(cached: CachedPage, requested: String, stale: Boolean): FetchedPage =
        FetchedPage(
            requestedUrl = requested,
            finalUrl = cached.url.ifBlank { requested },
            title = cached.title,
            text = cached.text,
            canonicalUrl = cached.canonicalUrl,
            publishedAtMillis = cached.publishedAtMillis,
            fetchedAt = cached.fetchedAt,
            fromCache = true,
            stale = stale
        )

    private fun looksLikeHtml(body: String): Boolean {
        val head = body.take(4_096).lowercase()
        return head.contains("<!doctype html") ||
            head.contains("<html") ||
            head.contains("<body") ||
            head.contains("<article") ||
            head.contains("<p")
    }
}
