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
    private const val WINDOW_DAYS = 30
    private const val DAY_MS = 24L * 60 * 60 * 1000
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
            sb.append("prefer ${top ?: "medium"}-length answers")
            if ("code" in flags) sb.append(", with code examples where they fit")
            if ("tables" in flags) sb.append(", with tables where they fit")
            if ("lists" in flags) sb.append(", with lists where they fit")
            sb.append(". ")
        }
        dislikes.takeLast(2).forEach { d ->
            sb.append("User feedback: ${d.substringAfter("|").take(120)}. ")
        }
        // Framed as silent background guidance: past phrasing ("User liked…")
        // made models echo it back ("I've noted your preference…").
        // Length-matching stops "Hello" from getting a full capability pitch.
        return "Background style guidance (apply silently — never mention, acknowledge, or restate it): ${sb.toString().trim()} " +
            "Match reply length to the message: greetings and small talk get one or two short sentences; " +
            "full detail only for substantive questions.".take(420)
    }

    /** Read-only view for the Memory & Preferences screen. */
    data class Stats(
        val likes: Int,
        val dislikes: Int,
        val summary: List<String>,
        val feedback: List<String>,
        val windowUsed: Int,
        val windowDaysLeft: Int
    ) {
        val totalSignals: Int get() = likes + dislikes
    }

    fun stats(ctx: Context): Stats {
        pruneIfExpired(ctx)
        val p = prefs(ctx)
        val likes = p.getString(K_LIKES, "").orEmpty().split(";;").filter { it.isNotBlank() }
        val dislikes = p.getString(K_DISLIKES, "").orEmpty().split(";;").filter { it.isNotBlank() }
        val summary = mutableListOf<String>()
        if (likes.isNotEmpty()) {
            val buckets = likes.mapNotNull { it.split("|").getOrNull(2) }
            buckets.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let { top ->
                summary.add(
                    when (top) {
                        "short" -> "Prefers short replies"
                        "detailed" -> "Prefers detailed answers"
                        else -> "Prefers medium-length replies"
                    }
                )
            }
            val flags = likes.flatMap { (it.split("|").getOrNull(3) ?: "").split(",") }
                .filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
                .filter { it.value * 2 >= likes.size }.keys
            if (flags.isNotEmpty()) {
                summary.add("Likes " + flags.joinToString(", ") {
                    when (it) {
                        "code" -> "code examples"
                        "tables" -> "tables"
                        "lists" -> "lists"
                        "headings" -> "headings"
                        else -> it
                    }
                })
            }
        }
        val hasWindow = p.contains(K_WIN_TS)
        val used = if (hasWindow) (UserPrefs.totalMessages(ctx) - p.getInt(K_WIN_MSG, 0)).coerceAtLeast(0) else 0
        val daysLeft = if (!hasWindow) WINDOW_DAYS else
            ((WINDOW_MS - (System.currentTimeMillis() - p.getLong(K_WIN_TS, 0L))).coerceAtLeast(0L) / DAY_MS)
                .coerceIn(0L, WINDOW_DAYS.toLong()).toInt()
        return Stats(
            likes = likes.size,
            dislikes = dislikes.size,
            summary = summary,
            feedback = dislikes.map { it.substringAfter("|").take(160) },
            windowUsed = used,
            windowDaysLeft = daysLeft
        )
    }

    /** Forget all learned signals from this device. */
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
