package com.karen.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset
import kotlin.coroutines.coroutineContext

data class HttpResult(
    val code: Int,
    val body: String,
    val finalUrl: String,
    val contentType: String = "",
    val truncated: Boolean = false
)

class HttpException(val code: Int, message: String) : Exception(message)

class UnsupportedContentTypeException(val contentType: String) :
    Exception("unsupported content type: ${contentType.ifBlank { "unknown" }}")

/**
 * Minimal HTTP seam so provider parsing and ranking can be unit tested with a
 * fake transport. The production transport is HttpURLConnection, matching the
 * rest of Karen and avoiding a new networking dependency.
 */
interface HttpTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 8_000,
        maxBytes: Int = 512 * 1024,
        allowPrivateHosts: Boolean = true
    ): HttpResult

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 12_000,
        maxBytes: Int = 256 * 1024
    ): HttpResult
}

class UrlConnectionTransport : HttpTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxBytes: Int,
        allowPrivateHosts: Boolean
    ): HttpResult = withContext(Dispatchers.IO) {
        val active = ActiveConnection()
        coroutineContext.job.invokeOnCompletion { active.disconnect() }
        try {
            getBlocking(
                url = url,
                headers = headers,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                maxBytes = maxBytes,
                allowPrivateHosts = allowPrivateHosts,
                active = active
            )
        } finally {
            active.disconnect()
        }
    }

    override suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        maxBytes: Int
    ): HttpResult = withContext(Dispatchers.IO) {
        val active = ActiveConnection()
        coroutineContext.job.invokeOnCompletion { active.disconnect() }
        try {
            postBlocking(
                url = url,
                body = body,
                headers = headers,
                connectTimeoutMs = connectTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                maxBytes = maxBytes,
                active = active
            )
        } finally {
            active.disconnect()
        }
    }

    fun getBlocking(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 5_000,
        readTimeoutMs: Int = 8_000,
        maxBytes: Int = 512 * 1024,
        allowPrivateHosts: Boolean = true,
        active: ActiveConnection = ActiveConnection()
    ): HttpResult {
        var current = url.trim()
        var redirects = 0
        while (true) {
            if (!UrlTools.isHttpUrl(current)) {
                throw IllegalArgumentException("only http(s) URLs are supported")
            }
            if (!allowPrivateHosts && !UrlTools.isPublicHttpUrl(current)) {
                throw IllegalArgumentException("private or local URL blocked")
            }
            val conn = openConnection(current, connectTimeoutMs, readTimeoutMs)
            active.connection = conn
            conn.requestMethod = "GET"
            applyCommonHeaders(conn, headers)
            conn.instanceFollowRedirects = false
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
            if (code in REDIRECT_CODES) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                redirects++
                if (location.isNullOrBlank() || redirects > MAX_REDIRECTS) {
                    throw HttpException(code, "redirect limit reached")
                }
                current = UrlTools.resolve(current, location)
                    ?: throw HttpException(code, "unsafe redirect")
                continue
            }
            return readResponse(conn, code, current, maxBytes)
        }
    }

    fun postBlocking(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 8_000,
        readTimeoutMs: Int = 12_000,
        maxBytes: Int = 256 * 1024,
        active: ActiveConnection = ActiveConnection()
    ): HttpResult {
        if (!UrlTools.isHttpUrl(url)) throw IllegalArgumentException("only http(s) URLs are supported")
        val conn = openConnection(url, connectTimeoutMs, readTimeoutMs)
        active.connection = conn
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = false
        applyCommonHeaders(conn, headers)
        if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            conn.setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            return readResponse(conn, code, conn.url.toString(), maxBytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(
        rawUrl: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int
    ): HttpURLConnection {
        val parsed = URI(rawUrl)
        return (URL(parsed.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs.coerceIn(1_000, 60_000)
            readTimeout = readTimeoutMs.coerceIn(1_000, 60_000)
            useCaches = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun applyCommonHeaders(conn: HttpURLConnection, headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) conn.setRequestProperty(name, value)
        }
    }

    private fun readResponse(
        conn: HttpURLConnection,
        code: Int,
        finalUrl: String,
        maxBytes: Int
    ): HttpResult {
        val contentType = conn.contentType ?: conn.getHeaderField("Content-Type") ?: ""
        val stream = try {
            if (code in 200..299) conn.inputStream else conn.errorStream
        } catch (_: Exception) {
            null
        }
        val bytes = stream?.use { readLimited(it, maxBytes) } ?: LimitedBytes(ByteArray(0), false)
        val text = decode(bytes.value, contentType)
        conn.disconnect()
        return HttpResult(
            code = code,
            body = text,
            finalUrl = finalUrl,
            contentType = contentType,
            truncated = bytes.truncated
        )
    }

    private fun readLimited(stream: java.io.InputStream, maxBytes: Int): LimitedBytes {
        val limit = maxBytes.coerceAtLeast(1)
        val out = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        var truncated = false
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            val room = limit - total
            if (room <= 0) {
                truncated = true
                break
            }
            val take = minOf(read, room)
            out.write(buffer, 0, take)
            total += take
            if (take < read) {
                truncated = true
                break
            }
        }
        return LimitedBytes(out.toByteArray(), truncated)
    }

    private fun decode(bytes: ByteArray, contentType: String): String {
        val charsetName = Regex("charset\\s*=\\s*[\"']?([^;\"'\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.getOrNull(1)
        val charset = try {
            if (charsetName.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(charsetName)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        return String(bytes, charset)
    }

    private data class LimitedBytes(val value: ByteArray, val truncated: Boolean)

    /** Mutable handle used to abort a blocking read when the coroutine is cancelled. */
    class ActiveConnection {
        @Volatile
        var connection: HttpURLConnection? = null

        fun disconnect() {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val USER_AGENT = "Karen/1.0 (on-device assistant; +https://github.com/rider05/karen)"
        private const val MAX_REDIRECTS = 3
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
