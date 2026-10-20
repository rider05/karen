package com.karen.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cloud text-to-speech for Karen.
 *
 * Preferred route: the direct Fish Audio API (`api.fish.audio`) with the
 * selected Fish Audio model and a stored Fish Audio key. If no Fish Audio key
 * is present, the OpenRouter speech endpoint is used with the equivalent Fish
 * Audio model. Both return raw MP3 bytes, played by [TtsManager] with
 * MediaPlayer. Keys live only in SharedPreferences.
 */
object CloudTts {
    /** OpenRouter's free Fish Audio model. The `:free` suffix is part of the id. */
    const val MODEL = "fish-audio/s2.1-pro-free:free"

    /** Fish Audio's free model header for the direct API. */
    const val FISH_MODEL = "s2.1-pro-free"

    /** Fish Audio "Energetic Male" voice model used when no voice is set. */
    const val DEFAULT_FISH_VOICE = "802e3bc2b27e49c2995d23ef70e6ac89"

    private const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/audio/speech"
    private const val FISH_ENDPOINT = "https://api.fish.audio/v1/tts"
    private const val FISH_MODELS_ENDPOINT = "https://api.fish.audio/model"
    private const val MAX_INPUT_CHARS = 4000

    /** A selectable Fish Audio TTS model (the `model` request header). */
    data class FishModel(
        val id: String,
        val label: String,
        val note: String,
        val free: Boolean
    )

    /** A Fish Audio voice model (a `reference_id` in TTS requests). */
    data class FishVoice(
        val id: String,
        val title: String,
        val author: String = ""
    )

    val KNOWN_MODELS = listOf(
        FishModel(FISH_MODEL, "S2.1 Pro Free", "Free · same model as Pro", true),
        FishModel("s2.1-pro", "S2.1 Pro", "Paid · production", false),
        FishModel("s2-pro", "S2 Pro", "Paid · previous gen", false),
        FishModel("drama-3", "Drama 3", "Paid · expressive", false),
        FishModel("s1", "S1", "Paid · deprecated", false)
    )

    /**
     * Titles are resolved from api.fish.audio. The voice docs used to label
     * `9a9cf477…` as Energetic Male, but the API returns "Hannah" for it; the
     * real Energetic Male is `802e3bc2…` (author lengyue).
     */
    val KNOWN_VOICES = listOf(
        FishVoice(DEFAULT_FISH_VOICE, "Energetic Male", "lengyue"),
        FishVoice("ca3007f96ae7499ab87d27ea3599956a", "E-girl", "twangodev"),
        FishVoice("9a9cf47702da476aa4629e2506d4a857", "Hannah", "Fish Official")
    )

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
            val fishModel = UserPrefs.fishModel(ctx).trim().ifBlank { FISH_MODEL }

            fun viaOpenRouter(): File {
                val payload = JSONObject()
                    .put("model", openRouterModelFor(fishModel))
                    .put("input", input)
                    .put("response_format", "mp3")
                    // Same default as the direct route so the UI label is real.
                    .put("voice", voiceId.trim().ifBlank { DEFAULT_FISH_VOICE })
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
                            extraHeaders = mapOf("model" to fishModel),
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

    /**
     * Maps a Fish Audio model name to OpenRouter's provider-qualified id so the
     * fallback honours the selected model instead of always using the free one.
     */
    fun openRouterModelFor(fishModel: String): String {
        val model = fishModel.trim().ifBlank { FISH_MODEL }
        return when (model) {
            "s2.1-pro-free" -> MODEL
            "s2.1-pro" -> "fish-audio/s2.1-pro"
            "s2-pro" -> "fish-audio/s2-pro"
            "s1" -> "fish-audio/s1"
            "drama-3" -> "fish-audio/drama-3"
            else -> if (model.contains("/")) model else "fish-audio/$model"
        }
    }

    /** The user's own Fish Audio voice models (requires a saved key). */
    suspend fun myVoices(ctx: Context): List<FishVoice> = listVoices(ctx, self = true, query = "")

    /** Public Fish Audio voice search, newest/most relevant first. */
    suspend fun searchVoices(ctx: Context, query: String): List<FishVoice> =
        listVoices(ctx, self = false, query = query.trim())

    private suspend fun listVoices(
        ctx: Context,
        self: Boolean,
        query: String
    ): List<FishVoice> = withContext(Dispatchers.IO) {
        val key = UserPrefs.fishAudioKey(ctx)
        // Public library search is anonymous; the user's own models need a key.
        if (self && key.isBlank()) return@withContext emptyList()
        val pageSize = if (self) 100 else 20
        val title = if (query.isBlank()) "" else
            "title=${URLEncoder.encode(query, "UTF-8")}&"
        val url = URL("$FISH_MODELS_ENDPOINT?${title}self=$self&page_size=$pageSize")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 25000
            // Public search is anonymous; own-model listing uses the key.
            if (self && key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                throw IllegalStateException("Fish Audio voices HTTP $code: ${body.take(200)}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val items = JSONObject(body).optJSONArray("items") ?: return@withContext emptyList()
            val voices = mutableListOf<FishVoice>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val type = item.optString("type")
                if (type.isNotBlank() && type != "tts") continue
                val state = item.optString("state")
                if (state.isNotBlank() && state != "trained") continue
                val id = item.optString("_id").trim()
                if (id.isBlank()) continue
                val author = item.optJSONObject("author")?.optString("nickname").orEmpty()
                voices += FishVoice(
                    id = id,
                    title = item.optString("title").ifBlank { "Untitled voice" },
                    author = author
                )
            }
            voices.distinctBy { it.id }
        } finally {
            conn.disconnect()
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
