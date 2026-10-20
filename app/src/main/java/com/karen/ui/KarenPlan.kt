package com.karen.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/**
 * Live plan checklist — Karen's take on Claude's task list.
 *
 * The model writes its plan into the reply as one line per revision:
 *
 *     ///PLAN {"steps":[{"t":"Read the PDF","s":"doing"},{"t":"Summarise","s":"todo"}]}
 *
 * Re-emitting the line with new statuses is how a step advances, so the list
 * is always the model's own state, not a client guess. The line never reaches
 * the screen: [splitPlan] returns the steps plus the text with every plan line
 * removed. Because it lives inside the message text, the plan survives
 * persistence, history replay and export with no schema changes.
 */

enum class PlanStatus { Todo, Doing, Done }

data class PlanStep(val text: String, val status: PlanStatus)

/** Steps from the most recent plan line, plus the body text with plan lines removed. */
data class ParsedPlan(val steps: List<PlanStep>, val body: String) {
    val done: Int get() = steps.count { it.status == PlanStatus.Done }
    val isEmpty: Boolean get() = steps.isEmpty()
}

private const val PLAN_MARK = "///PLAN"

/** Prompt contract, injected only for asks that look multi-step. */
fun planGuide(): String = buildString {
    append("For multi-step work (3 or more distinct actions), write the plan before acting. ")
    append("Start the reply with exactly one line:\n")
    append("""///PLAN {"steps":[{"t":"short step","s":"doing"},{"t":"next step","s":"todo"}]}""")
    append("\nAfter each step finishes, emit the same line again with every step's current status ")
    append("(doing/done/todo) — the last line wins and the app redraws the checklist. ")
    append("One action per step, at most 8 steps, 60 characters each. ")
    append("The line is machine syntax and is never shown; keep commentary out of it. ")
    append("Skip the plan for simple questions, single lookups, and chit-chat.")
}

/**
 * Cheap gate so ordinary chat pays no prompt tokens. The guide itself tells the
 * model to skip non-multi-step asks, so a false positive here costs clarity,
 * never correctness.
 */
fun wantsPlan(prompt: String): Boolean {
    val t = " ${prompt.lowercase()} "
    if (t.length > 220) return true
    val cues = listOf(
        " step by step", " steps ", " plan", " roadmap", " workflow", " checklist",
        " first ", " then ", " after that", " lastly", " finally ",
        " build ", " implement", " refactor", " migrate", " research", " analyze", " analyse",
        " compare", " review", " debug", " fix ", " set up", " setup", " walk me through",
        " organize", " organise", " draft ", " write a ", " create a "
    )
    return cues.any { it in t }
}

/** A `///PLAN` line → steps. Null when the line is not (yet) valid JSON. */
private fun parsePlanJson(line: String): List<PlanStep>? {
    val start = line.indexOf('{')
    val end = line.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val obj = try {
        JSONObject(line.substring(start, end + 1))
    } catch (_: Exception) {
        null
    } ?: return null
    val arr = obj.optJSONArray("steps") ?: return null
    val out = ArrayList<PlanStep>(arr.length())
    for (i in 0 until arr.length()) {
        val item = arr.optJSONObject(i) ?: continue
        val text = item.optString("t").ifBlank { item.optString("text") }.trim()
        if (text.isBlank()) continue
        out.add(PlanStep(text.take(120), statusOf(item.optString("s").ifBlank { item.optString("status") })))
    }
    return out.take(12).ifEmpty { null }
}

private fun statusOf(raw: String): PlanStatus = when (raw.trim().lowercase()) {
    "done", "complete", "completed", "finished", "ok" -> PlanStatus.Done
    "doing", "active", "current", "in_progress", "in-progress", "running", "now" -> PlanStatus.Doing
    else -> PlanStatus.Todo
}

private fun isPlanLine(line: String): Boolean =
    line.trimStart().startsWith(PLAN_MARK, ignoreCase = true)

/**
 * Splits a reply into its latest plan and the readable body. Every plan line is
 * dropped, including a half-streamed one — so nothing flashes on screen while
 * the JSON is still arriving.
 */
fun splitPlan(text: String): ParsedPlan {
    if (!text.contains(PLAN_MARK, ignoreCase = true)) return ParsedPlan(emptyList(), text)
    var latest: List<PlanStep>? = null
    val kept = StringBuilder(text.length)
    text.lineSequence().forEach { line ->
        if (isPlanLine(line)) {
            parsePlanJson(line)?.let { latest = it }
        } else {
            if (kept.isNotEmpty()) kept.append('\n')
            kept.append(line)
        }
    }
    val body = kept.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    return ParsedPlan(latest ?: emptyList(), body)
}

/** Compact checklist card: one row per step, status glyph left, progress right. */
@Composable
fun PlanCard(plan: ParsedPlan, modifier: Modifier = Modifier) {
    if (plan.isEmpty) return
    val colors = LocalKarenColors.current
    // Only animate while something is in flight; a finished plan keeps the
    // card in history and must not tick forever.
    val pulse = if (plan.steps.any { it.status == PlanStatus.Doing }) {
        rememberInfiniteTransition(label = "planPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "planPulseAlpha"
        ).value
    } else {
        1f
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "PLAN",
                color = colors.textMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${plan.done}/${plan.steps.size}",
                color = colors.textMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))
        plan.steps.forEachIndexed { i, step ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(15.dp)
                        .padding(top = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (step.status) {
                        PlanStatus.Done -> Text(
                            text = "✓",
                            color = colors.accentGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        PlanStatus.Doing -> Text(
                            text = "●",
                            modifier = Modifier.alpha(pulse),
                            color = colors.accentBlue,
                            fontSize = 9.sp
                        )
                        PlanStatus.Todo -> Text(
                            text = "○",
                            color = colors.textMuted,
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.width(7.dp))
                Text(
                    text = step.text,
                    color = if (step.status == PlanStatus.Done) colors.textMuted else colors.textPrimary,
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    textDecoration = if (step.status == PlanStatus.Done) TextDecoration.LineThrough else null
                )
            }
        }
    }
}
