package com.karen.ui

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Speech output for Karen.
 *
 * Preferred engine: Fish Audio through the direct API when a Fish Audio key is
 * saved, otherwise OpenRouter ([CloudTts]) — cloud voice, MediaPlayer playback,
 * sentence-by-sentence pipeline for Live mode. Fallback: Android's system TTS
 * (zero MB, offline),
 * with personas mapped to pitch/rate variants. STT input stays on the system
 * SpeechRecognizer (VoiceSttManager).
 */
object TtsManager {
    var speaking by mutableStateOf(false)
        private set
    var speakingText by mutableStateOf<String?>(null)
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Triple<Context, String, String>? = null

    // Realtime stream state: sentences speak as they complete.
    private var streamPending = 0
    private var streamClosed = true
    private var streamSpokenLen = 0
    private var streamLatest = ""
    private var streamPersona = "Juniper"
    private var streamCtx: Context? = null
    private var streamDone: (() -> Unit)? = null

    // Cloud voice state (Fish Audio via OpenRouter).
    private var streamCloud = false
    private var cloudSentences: Channel<String>? = null
    private var cloudJob: Job? = null
    private var cloudPlayer: MediaPlayer? = null
    private var cloudGen = 0
    private var cloudWarned = false
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class CloudClip(val file: File, val text: String)

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun onMain(fn: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) fn()
        else mainHandler.post { fn() }
    }

    /** Persona voice character: pitch multiplier + speech rate. */
    private fun personaProsody(persona: String): Pair<Float, Float> = when (persona) {
        "Sol" -> 0.9f to 0.95f
        "Cove" -> 1.1f to 1.0f
        "Breeze" -> 1.05f to 1.12f
        "Ember" -> 0.85f to 0.9f
        else -> 1.0f to 1.0f // Juniper
    }

    private fun applyProsody(engine: TextToSpeech, ctx: Context, persona: String) {
        val (pitch, rate) = personaProsody(persona)
        try {
            val locale = Locale.getDefault()
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = locale
            } else {
                engine.language = Locale.US
            }
        } catch (_: Exception) {
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
    }

    private fun ensure(ctx: Context, onReady: () -> Unit) {
        val cur = tts
        if (ready && cur != null) {
            onReady()
            return
        }
        if (cur == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                onMain {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        val p = pending
                        pending = null
                        if (p != null) {
                            speak(p.first, p.second, p.third)
                        } else {
                            onReady()
                        }
                    } else {
                        pending = null
                        speaking = false
                        speakingText = null
                    }
                }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onMain { speaking = true }
                }

                override fun onDone(utteranceId: String?) {
                    onMain {
                        if (utteranceId?.startsWith("karen_stream_") == true) {
                            streamPending = (streamPending - 1).coerceAtLeast(0)
                            checkStreamDone()
                        } else {
                            speaking = false
                            speakingText = null
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onMain {
                        if (utteranceId?.startsWith("karen_stream_") == true) {
                            streamPending = (streamPending - 1).coerceAtLeast(0)
                            checkStreamDone()
                        } else {
                            speaking = false
                            speakingText = null
                        }
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onMain {
                        if (utteranceId?.startsWith("karen_stream_") == true) {
                            streamPending = (streamPending - 1).coerceAtLeast(0)
                            checkStreamDone()
                        } else {
                            speaking = false
                            speakingText = null
                        }
                    }
                }
            })
        } else {
            pending = null
        }
    }

    /** Speaks text aloud (interrupts anything playing). Blank text stops. */
    fun speak(ctx: Context, text: String, persona: String = "Juniper") {
        val clean = text.trim()
        if (clean.isEmpty()) {
            stop()
            return
        }
        if (CloudTts.available(ctx)) speakCloud(ctx, clean, persona) else speakSystem(ctx, clean, persona)
    }

    /** Android's on-device engine (offline fallback and no-key default). */
    private fun speakSystem(ctx: Context, clean: String, persona: String) {
        ensure(ctx) {
            val engine = tts ?: return@ensure
            applyProsody(engine, ctx, persona)
            speakingText = clean
            speaking = true
            engine.speak(clean.take(2000), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
        // Engine warming up: queue the request for onInit.
        if (!ready) {
            pending = Triple(ctx.applicationContext, clean, persona)
            speakingText = clean
        }
    }

    /** Pre-warms the engine so the first streamed sentence has no init lag. */
    fun warmup(ctx: Context) {
        if (CloudTts.available(ctx)) return // nothing to initialize for cloud voice
        ensure(ctx) {
            if (!streamClosed && streamCtx != null) pumpStream()
        }
    }

    // ---- Cloud voice (Fish Audio via OpenRouter) ----

    /** One notice per app session when cloud voice silently falls back. */
    private fun warnCloudFallback(ctx: Context) {
        if (cloudWarned) return
        cloudWarned = true
        android.widget.Toast.makeText(
            ctx,
            "Fish Audio unavailable — using the system voice",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Speaks a full reply through Fish Audio: clean markdown, split into
     * request-sized pieces, synthesize and play in order. If a request fails,
     * the remaining text is spoken by the system engine instead of going
     * silent.
     */
    private fun speakCloud(ctx: Context, text: String, persona: String) {
        stop()
        val gen = ++cloudGen
        val appCtx = ctx.applicationContext
        val voice = UserPrefs.fishVoiceId(appCtx)
        // Code-only replies clean to nothing: fall back to the raw text so the
        // read-aloud button is never silent.
        val speakable = CloudTts.cleanForSpeech(text).ifBlank { text }
        val chunks = CloudTts.chunks(speakable, 1800)
        if (chunks.isEmpty()) {
            stop()
            return
        }
        speakingText = text
        speaking = true
        cloudJob = cloudScope.launch {
            var fellBack = false
            var index = 0
            try {
                while (index < chunks.size) {
                    if (gen != cloudGen) return@launch
                    val file = CloudTts.synthesize(appCtx, chunks[index], voice)
                    if (gen != cloudGen) {
                        file.delete()
                        return@launch
                    }
                    // Keep speakingText as the full reply: ChatScreen and
                    // Workspace match it to highlight the active speaker icon.
                    playFile(file, gen)
                    index++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (gen == cloudGen && index < chunks.size) {
                    fellBack = true
                    speaking = false
                    speakingText = null
                    warnCloudFallback(appCtx)
                    speakSystem(appCtx, chunks.drop(index).joinToString(" "), persona)
                }
            } finally {
                if (!fellBack && gen == cloudGen) {
                    speaking = false
                    speakingText = null
                }
            }
        }
    }

    /** Plays one MP3 and suspends until it ends (or is cancelled). */
    private suspend fun playFile(file: File, gen: Int) {
        if (gen != cloudGen) {
            file.delete()
            return
        }
        val player = MediaPlayer()
        cloudPlayer = player
        val done = CompletableDeferred<Unit>()
        try {
            player.setOnCompletionListener { done.complete(Unit) }
            player.setOnErrorListener { _, _, _ ->
                done.complete(Unit)
                true
            }
            player.setDataSource(file.absolutePath)
            withContext(Dispatchers.IO) { player.prepare() }
            if (gen != cloudGen) return
            player.start()
            done.await()
        } finally {
            try {
                player.release()
            } catch (_: Exception) {
            }
            if (cloudPlayer === player) cloudPlayer = null
            file.delete()
        }
    }

    // ---- Realtime sentence streaming ----

    /** Starts a stream: completed sentences speak as tokens arrive. */
    fun startStream(ctx: Context, persona: String = "Juniper", onDone: (() -> Unit)? = null) {
        stop()
        streamCtx = ctx.applicationContext
        streamPersona = persona
        streamPending = 0
        streamSpokenLen = 0
        streamLatest = ""
        streamClosed = false
        streamDone = onDone
        streamCloud = CloudTts.available(ctx)
        speaking = true
        if (streamCloud) {
            val sentences = Channel<String>(Channel.UNLIMITED)
            cloudSentences = sentences
            startCloudPipeline(ctx.applicationContext, sentences)
        } else {
            ensure(ctx) {
                pumpStream()
            }
        }
    }

    /**
     * Synthesizes sentences on IO while the main coroutine plays them in
     * order. The unbounded clip channel lets the next sentence render during
     * playback, so Live mode keeps a natural cadence instead of a network
     * pause per sentence.
     */
    private fun startCloudPipeline(ctx: Context, sentences: Channel<String>) {
        val gen = ++cloudGen
        val voice = UserPrefs.fishVoiceId(ctx)
        cloudJob = cloudScope.launch {
            val clips = Channel<CloudClip>(Channel.UNLIMITED)
            val producer = launch(Dispatchers.IO) {
                try {
                    for (sentence in sentences) {
                        if (gen != cloudGen) break
                        try {
                            clips.send(CloudClip(CloudTts.synthesize(ctx, sentence, voice), sentence))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Skip a sentence the service could not voice.
                        }
                    }
                } finally {
                    clips.close()
                }
            }
            var played = false
            for (clip in clips) {
                if (gen != cloudGen) {
                    clip.file.delete()
                    break
                }
                speakingText = clip.text
                try {
                    playFile(clip.file, gen)
                    played = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A malformed clip is skipped; the queue keeps playing.
                }
            }
            producer.cancel()
            if (gen == cloudGen) {
                // Whole cloud stream produced nothing (key/credit/network):
                // speak the reply with the offline engine rather than silence.
                if (!played && streamLatest.isNotBlank()) {
                    streamCtx = null
                    cloudSentences = null
                    streamDone = null
                    val fallback = CloudTts.cleanForSpeech(streamLatest).ifBlank { streamLatest }
                    warnCloudFallback(ctx)
                    speakSystem(ctx, fallback.take(2000), streamPersona)
                    return@launch
                }
                speaking = false
                speakingText = null
                streamCtx = null
                cloudSentences = null
                val cb = streamDone
                streamDone = null
                cb?.invoke()
            }
        }
    }

    /** Feeds the latest full text; newly completed sentences are spoken. */
    fun pushStream(fullText: String) {
        streamLatest = fullText
        onMain { pumpStream() }
    }

    /** Speaks whatever is left and closes the stream. */
    fun endStream(fullText: String) {
        streamLatest = fullText
        streamClosed = true
        onMain {
            pumpStream()
            val rest = streamLatest.substring(streamSpokenLen.coerceIn(0, streamLatest.length)).trim()
            streamSpokenLen = streamLatest.length
            val ctx = streamCtx
            if (streamCloud) {
                if (rest.isNotBlank()) {
                    val spoken = CloudTts.cleanForSpeech(rest)
                    if (spoken.isNotBlank()) cloudSentences?.trySend(spoken)
                }
                cloudSentences?.close()
                cloudSentences = null
                // The pipeline plays the queue, then clears speaking/onDone.
            } else if (rest.isNotBlank() && ctx != null) {
                if (ready) speakChunkNow(ctx, rest) else speakSystem(ctx, rest, streamPersona)
            } else {
                checkStreamDone()
            }
        }
    }

    private fun pumpStream() {
        val ctx = streamCtx ?: return
        if (streamClosed) return
        if (!streamCloud && !ready) return
        val text = streamLatest
        var end = -1
        var idx = streamSpokenLen.coerceIn(0, text.length)
        while (idx < text.length) {
            val c = text[idx]
            val boundary = c == '!' || c == '?' || c == '\n' ||
                (c == '.' && (idx + 1 >= text.length || text[idx + 1].isWhitespace()))
            if (boundary && idx + 1 - streamSpokenLen >= 12) end = idx + 1
            idx++
        }
        if (end > streamSpokenLen) {
            val chunk = text.substring(streamSpokenLen, end).trim()
            streamSpokenLen = end
            emitChunk(ctx, chunk)
        }
    }

    private fun emitChunk(ctx: Context, chunk: String) {
        if (chunk.isBlank()) return
        if (streamCloud) {
            val spoken = CloudTts.cleanForSpeech(chunk)
            if (spoken.isNotBlank()) cloudSentences?.trySend(spoken)
        } else {
            speakChunkNow(ctx, chunk)
        }
    }

    private fun speakChunkNow(ctx: Context, chunk: String) {
        val engine = tts ?: return
        try {
            applyProsody(engine, ctx, streamPersona)
            speakingText = chunk
            speaking = true
            val id = "karen_stream_${streamPending}"
            streamPending++
            engine.speak(chunk.take(1200), TextToSpeech.QUEUE_ADD, null, id)
        } catch (_: Exception) {
        }
    }

    private fun checkStreamDone() {
        if (streamClosed && streamPending <= 0) {
            speaking = false
            speakingText = null
            streamCtx = null
            val cb = streamDone
            streamDone = null
            cb?.invoke()
        }
    }

    /** Toggles playback for the given text. */
    fun toggle(ctx: Context, text: String, persona: String = "Juniper") {
        if (speaking) stop() else speak(ctx, text, persona)
    }

    fun stop() {
        cloudGen++
        cloudJob?.cancel()
        cloudJob = null
        cloudSentences?.close()
        cloudSentences = null
        streamCloud = false
        try {
            cloudPlayer?.release()
        } catch (_: Exception) {
        }
        cloudPlayer = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        pending = null
        streamPending = 0
        streamClosed = true
        streamSpokenLen = 0
        streamLatest = ""
        streamCtx = null
        streamDone = null
        speaking = false
        speakingText = null
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
