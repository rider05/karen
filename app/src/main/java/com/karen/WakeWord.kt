package com.karen

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Shared wake-word contract.
 *
 * Default phrase is "Hey Jarvis" (editable later for a custom word).
 * "Hey Karen" is not referenced anywhere in the app today, so both
 * phrases are free to use — nothing to rename or migrate.
 */
object WakeWord {
    const val DEFAULT_PHRASE = "Hey Jarvis"
    const val ACTION_WAKE = "com.karen.action.WAKE_WORD"
    const val EXTRA_OPEN_VOICE = "open_voice"

    /** Cooldown between two wake triggers (STT often repeats the phrase). */
    const val TRIGGER_COOLDOWN_MS = 6_000L

    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * True when [transcript] contains the wake [phrase].
     * Falls back to "all words present" so STT variants
     * ("hey jarvis!", "hey  jarvis") still trigger.
     */
    fun matches(transcript: String, phrase: String): Boolean {
        val t = normalize(transcript)
        val p = normalize(phrase)
        if (t.isEmpty() || p.isEmpty()) return false
        if (t.contains(p)) return true
        val words = p.split(" ").filter { it.isNotBlank() }
        if (words.size >= 2 && words.all { t.contains(it) }) return true
        return false
    }
}

/**
 * Warm-process signal: the foreground service sets this when the app is
 * already alive. Cold start (process dead) is covered by the WAKE intent
 * extra read in MainActivity instead.
 */
object WakeWordNavigator {
    val signal = MutableStateFlow(0L)

    fun requestOpen() {
        signal.value = System.currentTimeMillis()
    }
}
