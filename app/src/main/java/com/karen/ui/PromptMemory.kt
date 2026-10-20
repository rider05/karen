package com.karen.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * Prompt memory: the last prompts sent from this device, newest first.
 *
 * Backs the chat composer's inline "memory" completions — once the user
 * starts typing, earlier asks that begin with the same words are offered
 * above the composer and a tap fills the field. Purely local: one
 * SharedPreferences string, no network, no new dependencies. Temporary
 * chats never record anything.
 */
object PromptMemory {
    private const val PREFS = "karen_prompt_memory"
    private const val KEY = "prompts"
    /** One-shot guard: history is imported at most once, never after a reset. */
    private const val K_SEEDED = "seeded"
    private const val SEP = "\u001F"
    private const val MAX = 40
    private const val MAX_LEN = 160

    /** Shorter entries ("ok", "hi") are noise, not useful completions. */
    private const val MIN_LEN = 3

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun clean(prompt: String): String =
        prompt.replace('\n', ' ').replace(Regex("\\s+"), " ").trim().take(MAX_LEN)

    /** Newest first; corrupt/blank entries are dropped, never worth a crash. */
    fun list(ctx: Context): List<String> =
        prefs(ctx).getString(KEY, "").orEmpty()
            .split(SEP)
            .map { clean(it) }
            .filter { it.length >= MIN_LEN }

    /** Records one sent prompt at the front (case-insensitive dedupe). */
    fun record(ctx: Context, prompt: String) {
        val text = clean(prompt)
        if (text.length < MIN_LEN || text.startsWith("/")) return
        val next = (
            listOf(text) + list(ctx).filterNot { it.equals(text, ignoreCase = true) }
            ).take(MAX)
        prefs(ctx).edit().putString(KEY, next.joinToString(SEP)).apply()
    }

    /**
     * Earlier prompts that start with [typed] — prefix matches only, so a
     * suggestion always extends what is on screen instead of replacing it
     * with something unrelated. Queries under two characters return none.
     */
    fun suggestions(ctx: Context, typed: String, limit: Int = 4): List<String> {
        val query = clean(typed)
        if (query.length < 2) return emptyList()
        return list(ctx)
            .filter {
                it.startsWith(query, ignoreCase = true) &&
                    !it.equals(query, ignoreCase = true)
            }
            .take(limit)
    }

    /**
     * Pill-row suggestions: prefix matches first (a completion extends what is
     * already typed), then prompts that merely contain the typed words — a
     * distinctive word still finds its ask. Queries under two characters
     * return none.
     */
    fun matches(ctx: Context, typed: String, limit: Int = 4): List<String> {
        val query = clean(typed)
        if (query.length < 2) return emptyList()
        val all = list(ctx).filterNot { it.equals(query, ignoreCase = true) }
        val prefix = all.filter { it.startsWith(query, ignoreCase = true) }
        val contains = all.filter {
            !it.startsWith(query, ignoreCase = true) &&
                it.contains(query, ignoreCase = true)
        }
        return (prefix + contains).take(limit)
    }

    /**
     * One-time seed from local chat history, newest conversation first, so
     * memory is useful immediately after an update instead of only after the
     * next prompt. Runs at most once — a reset marks it done so history is
     * never re-imported behind the user's back.
     */
    fun seedFromHistory(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean(K_SEEDED, false) || list(ctx).isNotEmpty()) {
            p.edit().putBoolean(K_SEEDED, true).apply()
            return
        }
        val found = mutableListOf<String>()
        ChatHistoryStore.loadConversations(ctx).take(8).forEach { convo ->
            ChatHistoryStore.loadMessages(ctx, convo.id)
                .filterIsInstance<ChatItem.User>()
                .map { clean(it.text) }
                .asReversed()
                .filter { it.length >= MIN_LEN && !it.startsWith("/") }
                .forEach { text ->
                    if (found.none { it.equals(text, ignoreCase = true) }) found.add(text)
                }
        }
        p.edit()
            .putString(KEY, found.take(MAX).joinToString(SEP))
            .putBoolean(K_SEEDED, true)
            .apply()
    }

    /** Forgets every remembered prompt (Settings / reset paths). */
    fun clear(ctx: Context) {
        // Keep the seeded guard so "Reset memory" isn't undone by a re-import
        // the next time Chat opens.
        prefs(ctx).edit().clear().putBoolean(K_SEEDED, true).apply()
    }
}
