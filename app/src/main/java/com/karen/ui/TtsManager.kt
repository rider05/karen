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
                        speaking = false
                        speakingText = null
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onMain {
                        speaking = false
                        speakingText = null
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onMain {
                        speaking = false
                        speakingText = null
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
