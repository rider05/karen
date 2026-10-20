package com.karen.ui

/**
 * Automatic "keep going" support for answers that hit the model's output cap.
 *
 * A capped reply stops mid-thought, so Karen re-asks for the remainder and
 * stitches the parts together instead of making the user type "continue".
 * The loop is deliberately bounded ([MAX_CONTINUE_ROUNDS]) so a model that
 * never stops can never spin forever, but high enough that long code, essays,
 * and plans finish in one send.
 */

/** Hard cap on continuation rounds for one answer. */
const val MAX_CONTINUE_ROUNDS = 8

/** The instruction whispered to the model when a reply was cut off. */
const val CONTINUE_PROMPT =
    "Continue exactly where you stopped. Output only the continuation — " +
        "no recap, no repetition, no new introduction, no code fence restart."

/** Characters of the cut reply kept as context for the continuation round. */
const val CONTINUE_CONTEXT_CHARS = 4000

/** Sentence/block terminators: a reply ending on one of these is complete. */
private const val TERMINALS = ".,!?。！？…)]}\"'”’`>"

/** Characters a mid-thought reply tends to stop on. */
private const val OPEN_TAIL = ",([{-=:*/+&|\\"

/**
 * True when [text] was probably cut at the output cap.
 *
 * [hitCap] is the exact signal where the caller has one (on-device token
 * count). Without it, only unmistakable damage is treated as a cut: an
 * unclosed fenced code block, or a near-cap reply that stops on an opening
 * punctuation mark or connective. Complete answers are never re-asked.
 */
fun looksCutOff(text: String, maxTokens: Int, hitCap: Boolean = false): Boolean {
    val t = text.trimEnd()
    if (t.isBlank()) return false
    // Odd number of fence markers: stopped inside a code block.
    if (t.split("```").size % 2 == 0) return true
    val last = t.last()
    if (hitCap) return last !in TERMINALS
    // No provider signal: require both a near-cap length and a broken tail,
    // so ordinary prose (which often ends without punctuation) is left alone.
    val nearCap = maxTokens > 0 && t.length >= (maxTokens * 2.5).toInt()
    if (!nearCap || last in TERMINALS) return false
    if (last in OPEN_TAIL) return true
    val lower = t.lowercase()
    return lower.endsWith(" and") || lower.endsWith(" or") || lower.endsWith(" the") ||
        lower.endsWith(" with") || lower.endsWith(" to")
}

/**
 * Joins a continuation to the text already shown, dropping any overlap the
 * model repeated (common when it re-emits the last sentence or code lines).
 */
fun stitchContinuation(base: String, next: String): String {
    if (base.isEmpty()) return next
    if (next.isEmpty()) return base
    val tail = base.takeLast(minOf(base.length, 400))
    val head = next.take(minOf(next.length, 400))
    var overlap = 0
    for (n in minOf(tail.length, head.length) downTo 24) {
        if (tail.endsWith(head.substring(0, n))) {
            overlap = n
            break
        }
    }
    return base + next.substring(overlap)
}

/**
 * Line appended when even the brute-force loop ran out of rounds, so the
 * user knows the answer is incomplete and how to get the rest.
 */
fun continuationLimitNote(rounds: Int): String =
    "\n\n…(stopped at the output limit after $rounds parts — say \"continue\" for more)"
