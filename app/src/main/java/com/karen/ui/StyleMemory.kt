package com.karen.ui

import android.content.Context

/**
 * Response style memory: likes remember the shape of answers the user
 * preferred, dislikes store free-text feedback. Injected into prompts before
 * generation ("use before giving the request"). Alive 30 days or 300
 * messages, whichever comes first — then it resets.
 */
object StyleMemory {
    private const val PREFS = "karen_style_memory"
    private const val K_LIKES = "likes"
    private const val K_DISLIKES = "dislikes"
    private const val K_WIN_TS = "win_ts"
    private const val K_WIN_MSG = "win_msg"

    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val WINDOW_MSGS = 300
    private const val MAX_LIKES = 10
    private const val MAX_DISLIKES = 5

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Total user messages ever sent (drives the 300-message window). */
    fun bumpMessageCount(ctx: Context) {
        UserPrefs.bumpTotalMessages(ctx)
        pruneIfExpired(ctx)
    }

    private fun windowValid(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.all.isEmpty()) return true
        val ageOk = System.currentTimeMillis() - p.getLong(K_WIN_TS, 0L) < WINDOW_MS
        val msgOk = UserPrefs.totalMessages(ctx) - p.getInt(K_WIN_MSG, 0) < WINDOW_MSGS
        return ageOk && msgOk
    }

    private fun touchWindow(ctx: Context) {
        val p = prefs(ctx)
        if (!p.contains(K_WIN_TS)) {
            p.edit()
                .putLong(K_WIN_TS, System.currentTimeMillis())
                .putInt(K_WIN_MSG, UserPrefs.totalMessages(ctx))
                .apply()
        }
    }

    private fun pruneIfExpired(ctx: Context) {
        if (!windowValid(ctx)) prefs(ctx).edit().clear().apply()
    }

    private fun likeBucket(len: Int): String = when {
        len < 200 -> "short"
        len < 800 -> "medium"
        else -> "detailed"
    }

    /** Records a liked reply: its shape (length/structure), not the text. */
    fun recordLike(ctx: Context, text: String, model: String) {
        pruneIfExpired(ctx)
        touchWindow(ctx)
        val flags = buildList {
            if ("```" in text) add("code")
            if (text.lines().any { it.trim().startsWith("|") }) add("tables")
            if (text.lines().any { it.trim().startsWith("- ") || it.trim().startsWith("* ") }) add("lists")
            if (text.lines().any { it.trim().startsWith("#") }) add("headings")
        }.joinToString(",")
        val rec = "${System.currentTimeMillis()}|${model.take(40)}|${likeBucket(text.length)}|$flags"
        val cur = prefs(ctx).getString(K_LIKES, "").orEmpty()
            .split(";;").filter { it.isNotBlank() }.toMutableList()
        cur.add(rec)
        while (cur.size > MAX_LIKES) cur.removeAt(0)
        prefs(ctx).edit().putString(K_LIKES, cur.joinToString(";;")).apply()
    }

    /** Records free-text dislike feedback ("how would you like responses?"). */
    fun recordDislike(ctx: Context, feedback: String) {
        val clean = feedback.trim().take(240)
        if (clean.isBlank()) return
        pruneIfExpired(ctx)
        touchWindow(ctx)
        val cur = prefs(ctx).getString(K_DISLIKES, "").orEmpty()
            .split(";;").filter { it.isNotBlank() }.toMutableList()
        cur.add("${System.currentTimeMillis()}|$clean")
        while (cur.size > MAX_DISLIKES) cur.removeAt(0)
        prefs(ctx).edit().putString(K_DISLIKES, cur.joinToString(";;")).apply()
    }

    /** Compact style hint for prompts, or "" when memory is empty/expired. */
    fun currentHint(ctx: Context): String {
        pruneIfExpired(ctx)
        val p = prefs(ctx)
        val likes = p.getString(K_LIKES, "").orEmpty().split(";;").filter { it.isNotBlank() }
        val dislikes = p.getString(K_DISLIKES, "").orEmpty().split(";;").filter { it.isNotBlank() }
        if (likes.isEmpty() && dislikes.isEmpty()) return ""
        val sb = StringBuilder()
        if (likes.isNotEmpty()) {
            val buckets = likes.mapNotNull { it.split("|").getOrNull(2) }
            val top = buckets.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            val flags = likes.flatMap { (it.split("|").getOrNull(3) ?: "").split(",") }
                .filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
                .filter { it.value * 2 >= likes.size }.keys
            sb.append("User liked ${top ?: "medium"} answers")
            if ("code" in flags) sb.append(" with code examples")
            if ("tables" in flags) sb.append(" with tables")
            if ("lists" in flags) sb.append(" with lists")
            sb.append(". ")
        }
        dislikes.takeLast(2).forEach { d ->
            sb.append("User feedback: ${d.substringAfter("|").take(120)}. ")
        }
        return "Style memory: ${sb.toString().trim()}".take(300)
    }
}
