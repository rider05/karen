package com.karen.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cloud text-to-speech for Karen.
 *
 * Preferred route: the direct Fish Audio API (`api.fish.audio`) with the
 * S2.1 Pro Free model and a stored Fish Audio key. If no Fish Audio key is
 * present, the OpenRouter speech endpoint is used with its free Fish Audio
 * model (`fish-audio/s2.1-pro-free:free`). Both return raw MP3 bytes, played
 * by [TtsManager] with MediaPlayer. Keys live only in SharedPreferences.
 */
object CloudTts {
    /** OpenRouter's free Fish Audio model. The `:free` suffix is part of the id. */
    const val MODEL = "fish-audio/s2.1-pro-free:free"

    /** Fish Audio's free model header for the direct API. */
    const val FISH_MODEL = "s2.1-pro-free"

    /** Fish Audio "Energetic Male" voice model used when no voice is set. */
    const val DEFAULT_FISH_VOICE = "9a9cf47702da476aa4629e2506d4a857"

    private const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/audio/speech"
    private const val FISH_ENDPOINT = "https://api.fish.audio/v1/tts"
    private const val MAX_INPUT_CHARS = 4000

    enum class Route { DIRECT, OPENROUTER, NONE }

    fun route(ctx: Context): Route = when {
        UserPrefs.fishAudioKey(ctx).isNotBlank() -> Route.DIRECT
        UserPrefs.apiKey(ctx, "openrouter").isNotBlank() -> Route.OPENROUTER
        else -> Route.NONE
    }

    /** Cloud voice is on, a key exists, and the user kept the preference on. */
    fun available(ctx: Context): Boolean =
        UserPrefs.fishTts(ctx) && route(ctx) != Route.NONE

    /**
     * Synthesizes [text] to a local MP3 cache file. Throws on missing key,
     * HTTP failure, or a non-audio response; callers fall back to system TTS.
     */
    suspend fun synthesize(ctx: Context, text: String, voiceId: String = ""): File =
        withContext(Dispatchers.IO) {
            val input = text.trim().take(MAX_INPUT_CHARS)
            if (input.isEmpty()) throw IllegalArgumentException("empty TTS input")
            val fishKey = UserPrefs.fishAudioKey(ctx)
            val openRouterKey = UserPrefs.apiKey(ctx, "openrouter")

            fun viaOpenRouter(): File {
                val payload = JSONObject()
                    .put("model", MODEL)
                    .put("input", input)
                    .put("response_format", "mp3")
                if (voiceId.isNotBlank()) payload.put("voice", voiceId.trim())
                return requestAudio(
                    ctx = ctx,
                    endpoint = OPENROUTER_ENDPOINT,
                    apiKey = openRouterKey,
                    extraHeaders = mapOf(
                        "HTTP-Referer" to "https://karen.local",
                        "X-Title" to "Karen"
                    ),
                    payload = payload,
                    provider = "OpenRouter"
                )
            }

            when {
                fishKey.isNotBlank() -> {
                    val payload = JSONObject()
                        .put("text", input)
                        .put("reference_id", voiceId.trim().ifBlank { DEFAULT_FISH_VOICE })
                        .put("format", "mp3")
                    try {
                        requestAudio(
                            ctx = ctx,
                            endpoint = FISH_ENDPOINT,
                            apiKey = fishKey,
                            extraHeaders = mapOf("model" to FISH_MODEL),
                            payload = payload,
                            provider = "Fish Audio"
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Invalid/expired Fish key or a model hiccup: keep audio
                        // working through OpenRouter when that key is present.
                        if (openRouterKey.isBlank()) throw e
                        viaOpenRouter()
                    }
                }
                openRouterKey.isNotBlank() -> viaOpenRouter()
                else -> throw IllegalStateException("No Fish Audio or OpenRouter key")
            }
        }

    private fun requestAudio(
        ctx: Context,
        endpoint: String,
        apiKey: String,
        extraHeaders: Map<String, String>,
        payload: JSONObject,
        provider: String
    ): File {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            extraHeaders.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                throw IllegalStateException("$provider TTS HTTP $code: ${body.take(300)}")
            }
            val type = conn.contentType.orEmpty()
            if (!type.startsWith("audio/")) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                throw IllegalStateException("$provider TTS returned $type: ${body.take(300)}")
            }
            val dir = File(ctx.cacheDir, "tts").apply { mkdirs() }
            // Crash leftovers: clips are deleted after playback.
            dir.listFiles()?.forEach {
                if (System.currentTimeMillis() - it.lastModified() > 10 * 60 * 1000L) it.delete()
            }
            val file = File(dir, "karen_${System.nanoTime()}.mp3")
            try {
                conn.inputStream.use { inputStream ->
                    file.outputStream().use { output -> inputStream.copyTo(output) }
                }
                if (file.length() <= 0L) throw IllegalStateException("$provider returned empty audio")
                file
            } catch (e: Exception) {
                file.delete()
                throw e
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Splits a reply into request-sized pieces at sentence boundaries so each
     * MP3 stays short and the stream can start playing before the full text is
     * synthesized.
     */
    fun chunks(text: String, maxChars: Int = 1800): List<String> {
        val src = text.trim()
        if (src.isEmpty()) return emptyList()
        if (src.length <= maxChars) return listOf(src)
        val out = mutableListOf<String>()
        var start = 0
        while (start < src.length) {
            var end = (start + maxChars).coerceAtMost(src.length)
            if (end < src.length) {
                val window = src.substring(start, end)
                val cut = maxOf(
                    window.lastIndexOf(". "),
                    window.lastIndexOf("? "),
                    window.lastIndexOf("! "),
                    window.lastIndexOf('\n')
                )
                if (cut > maxChars / 3) end = start + cut + 1
            }
            src.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { out += it }
            start = end
        }
        return out
    }

    /**
     * Markdown-safe speech text: code blocks are skipped (not read aloud),
     * inline code keeps its content, links keep their label, and formatting
     * characters are dropped so the model voices words instead of asterisks.
     */
    fun cleanForSpeech(text: String): String {
        var s = text
        s = s.replace(Regex("""```[\s\S]*?```"""), " ")
        s = s.replace(Regex("""`([^`\n]+)`"""), "$1")
        s = s.replace(Regex("""!\[([^\]]*)\]\([^)]*\)"""), "$1")
        s = s.replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
        s = s.replace(Regex("""<[^>]+>"""), " ")
        s = s.replace(Regex("""(?m)^#{1,6}\s*"""), "")
        s = s.replace(Regex("""(?m)^\s*[-*+]\s+"""), "")
        s = s.replace(Regex("""[*_~]{1,3}"""), "")
        s = s.replace(Regex("""\s+"""), " ")
        return s.trim()
    }
}
