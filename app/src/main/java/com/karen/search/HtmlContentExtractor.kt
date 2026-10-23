package com.karen.search

/**
 * Small, tolerant HTML reader. It is intentionally not a full HTML5 parser:
 * page fetching is bounded, scripts are stripped, and the densest readable
 * region is extracted. Malformed markup must never throw.
 */
object HtmlContentExtractor {
    private val TITLE = Regex(
        "<title\\b[^>]*>(.*?)</title\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val H1 = Regex(
        "<h1\\b[^>]*>(.*?)</h1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val TIME = Regex("<time\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex(
        "([a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))"
    )
    private val COMMENTS = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val SCRIPT_JSON = Regex(
        "\"datePublished\"\\s*:\\s*\"([^\"]+)\"",
        RegexOption.IGNORE_CASE
    )
    private val BLOCK_TAGS = Regex(
        "(?i)</?(?:p|div|section|article|main|header|footer|nav|aside|blockquote|pre|" +
            "table|thead|tbody|tfoot|tr|ul|ol|dl|dt|dd|figure|figcaption|h[1-6])\\b[^>]*>"
    )
    private val BR = Regex("(?i)<br\\b[^>]*>")
    private val LI = Regex("(?i)<li\\b[^>]*>")
    private val TD = Regex("(?i)</t[dh]\\s*>")
    private val ANY_TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]+);")
    private val NOISE_LINE = Regex(
        "^(?:advertisement|skip to (?:main )?content|accept (?:all )?cookies?|" +
            "cookie (?:settings|policy)|sign in|log in|subscribe(?: now)?|" +
            "share (?:this|on).*|menu|search)$",
        RegexOption.IGNORE_CASE
    )

    private val PUBLISHED_KEYS = listOf(
        "article:published_time", "datepublished", "date", "pubdate",
        "publishdate", "article:modified_time", "og:updated_time", "last-modified"
    )

    /**
     * @return page title, readable text, optional canonical URL and published
     * date. Returns null when nothing usable remains.
     */
    fun extract(html: String, baseUrl: String, maxChars: Int = 12_000): ExtractedPage? {
        if (html.isBlank()) return null
        val raw = html.take(MAX_HTML)
        val title = pageTitle(raw).ifBlank { UrlTools.host(baseUrl) }
        val canonical = canonicalUrl(raw, baseUrl)
        val published = publishedAt(raw)

        var body = COMMENTS.replace(raw, " ")
        for (tag in listOf("script", "style", "noscript", "template", "svg", "canvas", "iframe", "object", "embed")) {
            body = stripElement(body, tag)
        }

        val article = largestBlock(body, "article")
        val main = largestBlock(body, "main")
        val region = when {
            article != null && article.length >= MIN_REGION -> article
            main != null && main.length >= MIN_REGION -> main
            else -> body
        }

        var text = toText(region)
        if (text.length < MIN_TEXT) {
            val fallback = toText(body)
            if (fallback.length > text.length) text = fallback
        }
        if (title.isBlank() && text.length < MIN_TEXT) return null
        if (text.length < MIN_TEXT && canonical == null && published == null) return null

        text = cleanText(text, title)
        if (text.isBlank() && title.isBlank()) return null
        return ExtractedPage(
            title = title.take(300),
            text = trimTo(text, maxChars.coerceIn(500, 40_000)),
            canonicalUrl = canonical,
            publishedAtMillis = published
        )
    }

    fun pageTitle(html: String): String {
        val raw = firstGroup(TITLE, html)
            ?: metaContent(html, listOf("og:title", "twitter:title"))
            ?: firstGroup(H1, html)
            ?: return ""
        return decodeEntities(stripTags(raw)).replace(Regex("\\s+"), " ").trim().take(300)
    }

    fun canonicalUrl(html: String, baseUrl: String): String? {
        for (match in LINK.findAll(html)) {
            val tag = match.value
            val rel = attr(tag, "rel") ?: continue
            if (rel.split(Regex("\\s+")).none { it.equals("canonical", ignoreCase = true) }) continue
            val href = attr(tag, "href")?.takeIf { it.isNotBlank() } ?: continue
            val resolved = UrlTools.resolve(baseUrl, decodeEntities(href)) ?: continue
            if (UrlTools.isPublicHttpUrl(resolved)) return resolved
        }
        return null
    }

    fun publishedAt(html: String): Long? {
        metaContent(html, PUBLISHED_KEYS)?.let { value ->
            DateParser.parse(value)?.let { return it }
        }
        for (match in TIME.findAll(html)) {
            val datetime = attr(match.value, "datetime") ?: continue
            DateParser.parse(datetime)?.let { return it }
        }
        firstGroup(SCRIPT_JSON, html)?.let { value ->
            DateParser.parse(value)?.let { return it }
        }
        return null
    }

    private fun toText(html: String): String {
        var value = html
        for (tag in listOf("nav", "footer", "aside", "form", "button")) {
            value = stripElement(value, tag)
        }
        value = BLOCK_TAGS.replace(value, "\n")
        value = BR.replace(value, "\n")
        value = LI.replace(value, "\n• ")
        value = TD.replace(value, " | ")
        value = ANY_TAG.replace(value, " ")
        value = decodeEntities(value)

        val lines = value.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>(lines.size)
        var previous = ""
        for (rawLine in lines) {
            val line = rawLine.replace(Regex("[\\t\\u000B\\f ]+"), " ").trim()
            if (line.length <= 1) continue
            if (NOISE_LINE.matches(line)) continue
            if (line == previous) continue
            if (line.length > MAX_LINE) out.add(line.take(MAX_LINE))
            else out.add(line)
            previous = line
        }
        return out.joinToString("\n")
    }

    private fun cleanText(text: String, title: String): String {
        var value = text.replace(Regex("\\n{3,}"), "\n\n").trim()
        if (title.isNotBlank()) {
            val first = value.lineSequence().firstOrNull()?.trim().orEmpty()
            if (first.equals(title, ignoreCase = true) && value.contains('\n')) {
                value = value.substringAfter('\n').trim()
            }
        }
        return value
    }

    private fun trimTo(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val head = text.take(maxChars)
        val boundary = head.lastIndexOf('\n')
        return if (boundary > maxChars / 2) head.take(boundary).trimEnd() else head.trimEnd()
    }

    private fun largestBlock(html: String, tag: String): String? {
        val lower = html.lowercase()
        var search = 0
        var best: String? = null
        while (search < lower.length) {
            val open = lower.indexOf("<$tag", search)
            if (open < 0) break
            val openEnd = lower.indexOf('>', open)
            if (openEnd < 0) break
            val close = lower.indexOf("</$tag", openEnd)
            if (close < 0) break
            val closeEnd = lower.indexOf('>', close)
            if (closeEnd < 0) break
            val candidate = html.substring(openEnd + 1, close)
            if (best == null || candidate.length > best.length) best = candidate
            search = closeEnd + 1
        }
        return best
    }

    private fun stripElement(html: String, tag: String): String {
        val lower = html.lowercase()
        val out = StringBuilder(html.length)
        var index = 0
        while (index < html.length) {
            val start = lower.indexOf("<$tag", index)
            if (start < 0) {
                out.append(html, index, html.length)
                break
            }
            val afterName = start + tag.length + 1
            if (afterName < lower.length) {
                val next = lower[afterName]
                if (!next.isWhitespace() && next != '>' && next != '/') {
                    out.append(html, index, start + 1)
                    index = start + 1
                    continue
                }
            }
            out.append(html, index, start)
            val openEnd = lower.indexOf('>', start)
            if (openEnd < 0) break
            if (lower.startsWith("/>", openEnd - 1)) {
                index = openEnd + 1
                continue
            }
            val close = lower.indexOf("</$tag", openEnd)
            if (close < 0) {
                // Unclosed script/style blocks are usually junk; drop the rest.
                break
            }
            val closeEnd = lower.indexOf('>', close)
            index = if (closeEnd < 0) html.length else closeEnd + 1
        }
        return out.toString()
    }

    private fun metaContent(html: String, keys: List<String>): String? {
        for (match in META.findAll(html)) {
            val tag = match.value
            val key = attr(tag, "property")
                ?: attr(tag, "name")
                ?: attr(tag, "itemprop")
                ?: continue
            if (keys.none { it.equals(key, ignoreCase = true) }) continue
            val value = attr(tag, "content")?.takeIf { it.isNotBlank() } ?: continue
            return decodeEntities(value).trim()
        }
        return null
    }

    private fun attr(tag: String, name: String): String? {
        for (match in ATTR.findAll(tag)) {
            if (!match.groupValues[1].equals(name, ignoreCase = true)) continue
            val value = match.groupValues.drop(2).firstOrNull { it.isNotBlank() }
            if (value != null) return value
        }
        return null
    }

    private fun firstGroup(regex: Regex, html: String): String? =
        regex.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    private fun stripTags(value: String): String = ANY_TAG.replace(value, " ")

    fun decodeEntities(value: String): String {
        if (value.indexOf('&') < 0) return value
        return ENTITY.replace(value) { match ->
            val token = match.groupValues[1]
            if (token.startsWith("#")) {
                val number = token.drop(1).let {
                    if (it.startsWith("x", ignoreCase = true)) {
                        it.drop(1).toIntOrNull(16)
                    } else {
                        it.toIntOrNull()
                    }
                }
                if (number != null && number in 1..0x10FFFF &&
                    !(number in 0xD800..0xDFFF)
                ) {
                    try {
                        String(Character.toChars(number))
                    } catch (_: Exception) {
                        match.value
                    }
                } else {
                    match.value
                }
            } else {
                NAMED_ENTITIES[token.lowercase()] ?: match.value
            }
        }
    }

    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "middot" to "·",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "laquo" to "«", "raquo" to "»", "copy" to "©", "reg" to "®",
        "trade" to "™", "deg" to "°", "plusmn" to "±", "frac12" to "½",
        "times" to "×", "divide" to "÷", "euro" to "€", "pound" to "£",
        "yen" to "¥", "sect" to "§", "para" to "¶", "dagger" to "†",
        "bull" to "•", "rarr" to "→", "larr" to "←", "harr" to "↔",
        "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç",
        "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß",
        "ntilde" to "ñ", "oacute" to "ó", "iacute" to "í", "uacute" to "ú"
    )

    private const val MAX_HTML = 768 * 1024
    private const val MAX_LINE = 2_000
    private const val MIN_TEXT = 120
    private const val MIN_REGION = 240
}
