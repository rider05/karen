package com.karen.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * "Recently opened" feed for the Home card: chats, canvases (workspaces) and
 * app screens, newest first. Tiny and local — one SharedPreferences string,
 * no new deps, no network. Entries are deduplicated by type+key so opening
 * the same chat again just refreshes its timestamp.
 */
object RecentActivity {
    const val TYPE_CHAT = "chat"
    const val TYPE_WORKSPACE = "workspace"
    const val TYPE_SCREEN = "screen"

    private const val PREFS = "karen_recent_activity"
    private const val KEY = "items"
    private const val MAX = 12
    private const val SEP = "\u001F"

    data class Item(val type: String, val key: String, val title: String, val at: Long)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Records (or refreshes) one opened chat / workspace / screen. */
    fun record(ctx: Context, type: String, key: String, title: String) {
        if (type.isBlank() || key.isBlank()) return
        val clean = title.replace('\n', ' ').replace(SEP, " ").trim().take(60).ifBlank { key }
        val next = (
            listOf(Item(type, key, clean, System.currentTimeMillis())) +
                list(ctx).filterNot { it.type == type && it.key == key }
            ).take(MAX)
        prefs(ctx).edit().putString(
            KEY,
            next.joinToString("\n") { it.type + SEP + it.key + SEP + it.title + SEP + it.at }
        ).apply()
    }

    /** Newest first. Corrupt/partial lines are dropped, never crash the card. */
    fun list(ctx: Context): List<Item> =
        prefs(ctx).getString(KEY, "").orEmpty().lineSequence().mapNotNull { line ->
            val p = line.split(SEP)
            if (p.size < 4) return@mapNotNull null
            // Older builds called this screen "Voice Chat"; show the new name
            // even before the entry is refreshed by another visit.
            val title = if (p[0] == TYPE_SCREEN && p[1] == "Voice") "Live Mode" else p[2]
            Item(p[0], p[1], title, p[3].toLongOrNull() ?: 0L)
        }.toList()
}
