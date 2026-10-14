package com.karen.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.UUID

/**
 * Real on-device speech output through Android's system TTS engine
 * (zero MB, offline voices). Voice personas map to pitch/rate variants.
 * STT input stays on the system SpeechRecognizer (VoiceSttManager).
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
        ensure(ctx) {
            if (!streamClosed && streamCtx != null) pumpStream()
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
        speaking = true
        ensure(ctx) {
            pumpStream()
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
            val ctx = streamCtx
            if (rest.isNotBlank() && ctx != null) {
                if (ready) speakChunkNow(ctx, rest)
                else speak(ctx, rest, streamPersona)
            } else {
                checkStreamDone()
            }
            streamSpokenLen = streamLatest.length
        }
    }

    private fun pumpStream() {
        val ctx = streamCtx ?: return
        if (!ready || streamClosed) return
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
            if (chunk.isNotBlank()) speakChunkNow(ctx, chunk)
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
