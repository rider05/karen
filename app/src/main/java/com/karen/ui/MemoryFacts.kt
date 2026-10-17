package com.karen.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Long-term facts behind Memory & Preferences. The user adds a fact and it
 * waits in the pending list until it is explicitly confirmed — only then can
 * Karen reference it in prompts. Stored on-device as JSON in
 * SharedPreferences; never uploaded or synced.
 */
object MemoryFacts {
    data class Fact(
        val id: String,
        val category: String,
        val text: String,
        val verified: Boolean
    )

    val categories = listOf("Identity", "Preferences", "Projects", "Study", "Health", "Other")

    private const val PREFS = "karen_memory_facts"
    private const val K_PENDING = "pending"
    private const val K_VERIFIED = "verified"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(ctx: Context, key: String): MutableList<Fact> {
        val raw = prefs(ctx).getString(key, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Fact(
                    id = o.optString("id"),
                    category = o.optString("category", "Other").ifBlank { "Other" },
                    text = o.optString("text"),
                    verified = key == K_VERIFIED
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun write(ctx: Context, key: String, list: List<Fact>) {
        val arr = JSONArray()
        list.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("category", f.category)
                    .put("text", f.text)
            )
        }
        prefs(ctx).edit().putString(key, arr.toString()).apply()
    }

    fun pending(ctx: Context): List<Fact> = read(ctx, K_PENDING)

    fun verified(ctx: Context): List<Fact> = read(ctx, K_VERIFIED)

    fun count(ctx: Context): Int = pending(ctx).size + verified(ctx).size

    /** New fact — waits in the pending list until the user confirms it. */
    fun add(ctx: Context, category: String, text: String): Fact {
        val fact = Fact(
            id = System.currentTimeMillis().toString(),
            category = category.takeIf { it in categories } ?: "Other",
            text = text.trim().take(240),
            verified = false
        )
        val list = read(ctx, K_PENDING)
        list.add(fact)
        write(ctx, K_PENDING, list)
        return fact
    }

    fun confirm(ctx: Context, id: String) {
        val from = read(ctx, K_PENDING)
        val fact = from.firstOrNull { it.id == id } ?: return
        from.removeAll { it.id == id }
        write(ctx, K_PENDING, from)
        val to = read(ctx, K_VERIFIED)
        to.add(0, fact.copy(verified = true))
        write(ctx, K_VERIFIED, to)
    }

    fun discard(ctx: Context, id: String) {
        write(ctx, K_PENDING, read(ctx, K_PENDING).filter { it.id != id })
    }

    fun remove(ctx: Context, id: String) {
        write(ctx, K_VERIFIED, read(ctx, K_VERIFIED).filter { it.id != id })
        write(ctx, K_PENDING, read(ctx, K_PENDING).filter { it.id != id })
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    /** Prompt fragment: confirmed facts only, capped; "" when none. */
    fun promptBlock(ctx: Context): String {
        val facts = verified(ctx).take(12)
        if (facts.isEmpty()) return ""
        return ("Background facts about the user (confirmed by them; reference silently — " +
            "never mention, acknowledge, or list them): " +
            facts.joinToString("; ") { it.text.take(120) }).take(900)
    }
}
