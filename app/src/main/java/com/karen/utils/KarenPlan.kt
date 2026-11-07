package com.karen.utils

import com.karen.ui.LocalKarenColors

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    append("""///PLAN {"steps":[{"t":"<real step 1>","s":"doing"},{"t":"<real step 2>","s":"todo"}]}""")
    append("\nReplace the angle-bracket text with the request's real actions; never output it literally. ")
    append("\nAfter each step finishes, emit the same line again with every step's current status ")
    append("(doing/done/todo) — the last line wins and the app redraws the checklist. ")
    append("When the current step needs the phone, pair it with the matching ///TOOL call and stop; ")
    append("the app runs the tool and advances the checklist for you. Never claim an action succeeded. ")
    append("One action per step, at most 8 steps, 60 characters each. ")
    append("The line is machine syntax and is never shown; keep commentary out of it. ")
    append("Skip the plan for simple questions, single lookups, and chit-chat. ")
    // OBSERVATION_STEPS clone notes: narrate long work + clarify ambiguity.
    append("\nFor long builds (PDF/magazine/document/code >10 steps): first emit one status line ")
    append("(\"Looking up X before starting.\"), then a fixed task skeleton as ///PLAN ")
    append("(Research → Plan → Build → Check & deliver). ")
    append("After each plan revision add ///TRACE rows mirroring Claude: ")
    append("Added/Started/Completed task, Searched the web, Loaded skill, Checked tools/fonts, ")
    append("Created lib.py/pages_a.py/build.py, Built pages, Rendered, Read sheet, Flagging issues. ")
    append("When the subject is ambiguous, ask before building: emit ///CLARIFY lines (2 max), ")
    append("one per line, e.g. ///CLARIFY {\"q\":\"Which X?\",\"opts\":[\"A\",\"B (Recommended)\"]}. ")
    append("The app shows them as a tap carousel; wait for the user's answer. ")
    append("When you deliver a file, emit ///ARTIFACT {\"name\":\"file.pdf\",\"pages\":15,\"kind\":\"pdf\"} ")
    append("and optionally ///SOURCES {\"items\":[{\"title\":\"…\",\"url\":\"…\",\"source\":\"…\"}]}.")
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
    // A model that echoes the schema sample is not a plan. Drop placeholder
    // rows; if nothing real is left there is no checklist to show or run.
    val real = out.take(12).filterNot { it.text.lowercase().trim() in PLAN_PLACEHOLDERS }
    return real.ifEmpty { null }
}

/** Schema sample text (old and new wording) that must never become a step. */
private val PLAN_PLACEHOLDERS = setOf(
    "short step", "next step", "<real step 1>", "<real step 2>",
    "real step 1", "real step 2", "<action>", "your step", "step 1", "step 2"
)

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
    val active = plan.steps.firstOrNull { it.status == PlanStatus.Doing }
    // While a step is running the card opens collapsed onto that step alone.
    // A tap on the header (or its chevron) expands to the full list; the choice
    // sticks for this card. When nothing is running, everything is shown.
    var expandedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = expandedOverride ?: (active == null)
    val visible = if (active == null || expanded) plan.steps else listOf(active)
    // Only animate while something is in flight; a finished plan keeps the
    // card in history and must not tick forever.
    val pulse = if (active != null) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .then(
                    if (active != null) Modifier.clickable(
                        onClickLabel = if (expanded) "Collapse plan" else "Expand plan"
                    ) { expandedOverride = !expanded } else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            if (active != null) {
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (expanded) "▴" else "▾",
                    color = colors.textMuted,
                    fontSize = 12.sp
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        visible.forEachIndexed { i, step ->
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

// ---------------------------------------------------------------------------
// OBSERVATION_STEPS — Claude-style step trace, clarify, artifacts, sources.
// All live inside the reply text as ///TRACE / ///CLARIFY / ///ARTIFACT /
// SOURCES lines (like ///PLAN), so they survive persistence with no schema
// change. splitTrace returns the append-only trace plus the body with all
// machine lines stripped.
// ---------------------------------------------------------------------------

/** Trace row kind — skill/env are first-class (clone notes). */
enum class TraceKind { Task, Skill, EnvCheck, Build, Search, Create, Render, Read, Flag, Note }

enum class TraceState { Added, Started, Completed, Info }

data class TraceRow(
    val label: String,
    val kind: TraceKind,
    val state: TraceState
)

data class ParsedTrace(val rows: List<TraceRow>, val body: String) {
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** One clarifying question with tappable options. */
data class ClarifyQuestion(val question: String, val options: List<String>)

data class ParsedClarify(val questions: List<ClarifyQuestion>, val body: String)

/** A delivered file artifact (PDF etc.) built on-device. */
data class ArtifactMeta(val name: String, val pages: Int, val kind: String)

data class ParsedArtifact(val artifacts: List<ArtifactMeta>, val body: String)

/** One web source behind the "N sources" chip. */
data class SourceEntry(val title: String, val url: String, val source: String)

data class ParsedSources(val items: List<SourceEntry>, val body: String)

private const val TRACE_MARK = "///TRACE"
private const val CLARIFY_MARK = "///CLARIFY"
private const val ARTIFACT_MARK = "///ARTIFACT"
private const val SOURCES_MARK = "///SOURCES"

/** True for long-build prompts ("15 page pdf", "magazine", "step by step" builds). */
fun wantsLongBuild(prompt: String): Boolean {
    val t = " ${prompt.lowercase()} "
    return listOf("pdf", "magazine", "document", "report", "ebook", "book", "page", " build ", " create a ").any { it in t } &&
        (wantsPlan(prompt) || "step by step" in t)
}

private fun kindOf(raw: String): TraceKind = when (raw.trim().lowercase()) {
    "skill" -> TraceKind.Skill
    "env", "check", "tools" -> TraceKind.EnvCheck
    "build" -> TraceKind.Build
    "search", "web" -> TraceKind.Search
    "create", "file" -> TraceKind.Create
    "render" -> TraceKind.Render
    "read" -> TraceKind.Read
    "flag" -> TraceKind.Flag
    else -> TraceKind.Task
}

private fun stateOf(raw: String): TraceState = when (raw.trim().lowercase()) {
    "started", "doing", "active", "running" -> TraceState.Started
    "completed", "done", "finished", "ok" -> TraceState.Completed
    "added" -> TraceState.Added
    else -> TraceState.Info
}

private fun parseTraceLine(line: String): TraceRow? {
    val start = line.indexOf('{')
    val end = line.lastIndexOf('}')
    val label = if (start < 0 || end <= start) {
        // Bare ///TRACE text line: label with no kind/state.
        line.removePrefix(TRACE_MARK).trim().trimStart(':', ' ').take(120).ifBlank { return null }
    } else {
        try {
            val o = JSONObject(line.substring(start, end + 1))
            val t = o.optString("t").ifBlank { o.optString("label") }.trim().take(120)
            if (t.isBlank()) return null
            return TraceRow(t, kindOf(o.optString("k").ifBlank { o.optString("kind") }), stateOf(o.optString("s").ifBlank { o.optString("state") }))
        } catch (_: Exception) {
            return null
        }
    }
    return TraceRow(label, TraceKind.Task, TraceState.Info)
}

private fun stripMachine(text: String, marks: List<String>): String {
    val kept = StringBuilder(text.length)
    text.lineSequence().forEach { line ->
        if (marks.any { line.trimStart().startsWith(it, ignoreCase = true) }) return@forEach
        if (kept.isNotEmpty()) kept.append('\n')
        kept.append(line)
    }
    return kept.toString().replace(Regex("\n{3,}"), "\n\n").trim()
}

/**
 * Appends one trace row to a reply. Trace is append-only (unlike PLAN, where
 * the last line wins) — every Added/Started/Completed flip is a new row.
 */
fun appendTrace(text: String, row: TraceRow): String {
    val line = "///TRACE " + JSONObject()
        .put("t", row.label.take(120))
        .put(
            "k", when (row.kind) {
                TraceKind.Task -> "task"
                TraceKind.Skill -> "skill"
                TraceKind.EnvCheck -> "env"
                TraceKind.Build -> "build"
                TraceKind.Search -> "search"
                TraceKind.Create -> "create"
                TraceKind.Render -> "render"
                TraceKind.Read -> "read"
                TraceKind.Flag -> "flag"
                TraceKind.Note -> "note"
            }
        )
        .put(
            "s", when (row.state) {
                TraceState.Added -> "added"
                TraceState.Started -> "started"
                TraceState.Completed -> "completed"
                TraceState.Info -> "info"
            }
        )
        .toString()
    return if (text.isBlank()) line else text.trimEnd() + "\n" + line
}

/** All ///TRACE rows (append-only, in order) plus the body without trace lines. */
fun splitTrace(text: String): ParsedTrace {
    if (!text.contains(TRACE_MARK, ignoreCase = true)) return ParsedTrace(emptyList(), text)
    val rows = ArrayList<TraceRow>()
    text.lineSequence().forEach { line ->
        if (line.trimStart().startsWith(TRACE_MARK, ignoreCase = true)) {
            parseTraceLine(line)?.let { rows.add(it) }
        }
    }
    return ParsedTrace(rows.take(40), stripMachine(text, listOf(TRACE_MARK)))
}

/** ///CLARIFY lines → questions (max 2), body stripped. */
fun splitClarify(text: String): ParsedClarify {
    if (!text.contains(CLARIFY_MARK, ignoreCase = true)) return ParsedClarify(emptyList(), text)
    val out = ArrayList<ClarifyQuestion>()
    text.lineSequence().forEach { line ->
        if (!line.trimStart().startsWith(CLARIFY_MARK, ignoreCase = true) || out.size >= 2) return@forEach
        val start = line.indexOf('{')
        val end = line.lastIndexOf('}')
        val obj = try {
            if (start < 0 || end <= start) null else JSONObject(line.substring(start, end + 1))
        } catch (_: Exception) {
            null
        } ?: return@forEach
        val q = obj.optString("q").ifBlank { obj.optString("question") }.trim().take(160)
        val arr = obj.optJSONArray("opts") ?: obj.optJSONArray("options")
        val opts = ArrayList<String>()
        if (arr != null) for (i in 0 until arr.length()) {
            arr.optString(i).trim().take(80).takeIf { it.isNotBlank() }?.let { opts.add(it) }
        }
        if (q.isNotBlank() && opts.isNotEmpty()) out.add(ClarifyQuestion(q, opts.take(4)))
    }
    return ParsedClarify(out, stripMachine(text, listOf(CLARIFY_MARK)))
}

/** ///ARTIFACT {"name":"x.pdf","pages":15,"kind":"pdf"} → artifacts, body stripped. */
fun splitArtifact(text: String): ParsedArtifact {
    if (!text.contains(ARTIFACT_MARK, ignoreCase = true)) return ParsedArtifact(emptyList(), text)
    val out = ArrayList<ArtifactMeta>()
    text.lineSequence().forEach { line ->
        if (!line.trimStart().startsWith(ARTIFACT_MARK, ignoreCase = true)) return@forEach
        val start = line.indexOf('{')
        val end = line.lastIndexOf('}')
        val obj = try {
            if (start < 0 || end <= start) null else JSONObject(line.substring(start, end + 1))
        } catch (_: Exception) {
            null
        } ?: return@forEach
        val name = obj.optString("name").ifBlank { obj.optString("file") }.trim().take(120)
        if (name.isNotBlank()) {
            out.add(
                ArtifactMeta(
                    name,
                    obj.optInt("pages", 0).coerceIn(0, 999),
                    obj.optString("kind", "pdf").ifBlank { "pdf" }.trim().lowercase().take(12)
                )
            )
        }
    }
    return ParsedArtifact(out, stripMachine(text, listOf(ARTIFACT_MARK)))
}

/** ///SOURCES {"items":[{title,url,source}…]} → sources, body stripped. */
fun splitSources(text: String): ParsedSources {
    if (!text.contains(SOURCES_MARK, ignoreCase = true)) return ParsedSources(emptyList(), text)
    var items: List<SourceEntry> = emptyList()
    text.lineSequence().forEach { line ->
        if (!line.trimStart().startsWith(SOURCES_MARK, ignoreCase = true)) return@forEach
        val start = line.indexOf('{')
        val end = line.lastIndexOf('}')
        val obj = try {
            if (start < 0 || end <= start) null else JSONObject(line.substring(start, end + 1))
        } catch (_: Exception) {
            null
        } ?: return@forEach
        val arr = obj.optJSONArray("items")
        if (arr != null) {
            val list = ArrayList<SourceEntry>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val title = it.optString("title").ifBlank { "Source" }.trim().take(120)
                val url = it.optString("url", "").trim().take(240)
                val src = it.optString("source").ifBlank { url.substringAfter("://", "").substringBefore('/').ifBlank { "source" } }.trim().take(60)
                if (title.isNotBlank()) list.add(SourceEntry(title, url, src))
            }
            if (list.isNotEmpty()) items = list.take(12)
        }
    }
    return ParsedSources(items, stripMachine(text, listOf(SOURCES_MARK)))
}

/** Appends a ///SOURCES line for already-retrieved web hits. */
fun appendSources(text: String, items: List<SourceEntry>): String {
    if (items.isEmpty()) return text
    val arr = org.json.JSONArray()
    items.take(12).forEach {
        arr.put(JSONObject().put("title", it.title.take(120)).put("url", it.url.take(240)).put("source", it.source.take(60)))
    }
    val line = "///SOURCES " + JSONObject().put("items", arr).toString()
    return if (text.isBlank()) line else text.trimEnd() + "\n" + line
}

/** All machine lines stripped — the visible body. */
fun stripMachineLines(text: String): String =
    stripMachine(text, listOf(PLAN_MARK, TRACE_MARK, CLARIFY_MARK, ARTIFACT_MARK, SOURCES_MARK))

/** Grouped summary: "Created 3 files, searched the web, and 12 more steps". */
fun traceSummary(rows: List<TraceRow>): String {
    if (rows.isEmpty()) return ""
    val creates = rows.count { it.kind == TraceKind.Create }
    val searched = rows.any { it.kind == TraceKind.Search }
    val parts = ArrayList<String>()
    if (creates > 0) parts.add("Created $creates file${if (creates == 1) "" else "s"}")
    if (searched) parts.add("searched the web")
    val more = rows.size - creates - (if (searched) 1 else 0)
    if (more > 0) parts.add("$more more step${if (more == 1) "" else "s"}")
    if (parts.isEmpty()) return "${rows.size} step${if (rows.size == 1) "" else "s"}"
    return parts.joinToString(", ")
}

/** Row label as shown in Claude's trace: "Added task: X" / "Started task: X". */
fun traceRowLabel(row: TraceRow): String = when (row.state) {
    TraceState.Added -> "Added task: ${row.label}"
    TraceState.Started -> "Started task: ${row.label}"
    TraceState.Completed -> "Completed task: ${row.label}"
    TraceState.Info -> when (row.kind) {
        TraceKind.Skill -> "Loaded skill — ${row.label}"
        TraceKind.EnvCheck -> "Checked ${row.label}"
        TraceKind.Search -> "Searched the web — ${row.label}"
        TraceKind.Create -> "Created ${row.label}"
        TraceKind.Render -> "Rendered ${row.label}"
        TraceKind.Read -> "Read ${row.label}"
        TraceKind.Flag -> "Flagging ${row.label}"
        TraceKind.Build -> row.label
        TraceKind.Note -> row.label
        TraceKind.Task -> row.label
    }
}

/** Status-line narration for a working row ("Looking up X before starting."). */
fun narrationFor(row: TraceRow): String = when (row.kind) {
    TraceKind.Search -> "Looking up ${row.label} before starting."
    TraceKind.Skill -> "Loading skill ${row.label}…"
    TraceKind.EnvCheck -> "Checking tools and fonts…"
    TraceKind.Flag -> "Flagging layout problems…"
    TraceKind.Read -> "Reading back the render…"
    else -> if (row.state == TraceState.Started || row.state == TraceState.Info) row.label else ""
}

/** Grouped accordion trace — task group + work group, Claude-style. */
@Composable
fun TraceAccordion(trace: ParsedTrace, modifier: Modifier = Modifier) {
    if (trace.isEmpty) return
    val colors = LocalKarenColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val tasks = trace.rows.filter { it.kind == TraceKind.Task }
    val work = trace.rows.filter { it.kind != TraceKind.Task }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Collapse steps" else "Expand steps") { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = traceSummary(trace.rows),
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "▴" else "▾",
                color = colors.textMuted,
                fontSize = 12.sp
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            if (tasks.isNotEmpty()) {
                Text(
                    text = "${tasks.size} step${if (tasks.size == 1) "" else "s"}",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                tasks.forEach { TraceRowView(it) }
            }
            if (tasks.isNotEmpty() && work.isNotEmpty()) Spacer(Modifier.height(6.dp))
            if (work.isNotEmpty()) {
                if (tasks.isNotEmpty()) {
                    Text(
                        text = traceSummary(work),
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                }
                work.forEach { TraceRowView(it) }
            }
        }
    }
}

@Composable
private fun TraceRowView(row: TraceRow) {
    val colors = LocalKarenColors.current
    val glyph = when (row.kind) {
        TraceKind.Skill -> "⬡"
        TraceKind.EnvCheck -> "⚙"
        TraceKind.Search -> "🔎"
        TraceKind.Create -> "📄"
        TraceKind.Render -> "▦"
        TraceKind.Read -> "👁"
        TraceKind.Flag -> "⚑"
        else -> if (row.state == TraceState.Completed) "✓" else if (row.state == TraceState.Started) "●" else "○"
    }
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(15.dp).padding(top = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = glyph,
                color = when (row.kind) {
                    TraceKind.Flag -> colors.accentAmber
                    TraceKind.Skill -> colors.accentBlue
                    else -> colors.textMuted
                },
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            text = traceRowLabel(row),
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            lineHeight = 17.sp
        )
    }
}

/** Clarifying-question carousel ("Q n of m") — tappable options. */
@Composable
fun ClarifyCard(
    questions: List<ClarifyQuestion>,
    index: Int,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit = {}
) {
    if (questions.isEmpty()) return
    val colors = LocalKarenColors.current
    val q = questions[index.coerceIn(0, questions.lastIndex)]
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.accentBlue.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Q ${index + 1} of ${questions.size}",
                color = colors.accentBlue,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = q.question,
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        q.options.forEach { opt ->
            val recommended = opt.contains("Recommended", ignoreCase = true)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceHover.copy(alpha = 0.5f))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .clickable { onPick(opt) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = opt.removeSuffix(" (Recommended)").trim(),
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                if (recommended) {
                    Text(
                        text = "Recommended",
                        color = colors.accentGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Artifact card ("Document · PDF" chip) — tap opens the viewer. */
@Composable
fun ArtifactCard(
    artifact: ArtifactMeta,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accentRed),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = artifact.kind.uppercase().take(3),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = artifact.name,
                color = colors.textPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accentRed.copy(alpha = 0.1f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Document · ${artifact.kind.uppercase()}",
                        color = colors.accentRed,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (artifact.pages > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${artifact.pages} page${if (artifact.pages == 1) "" else "s"}",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.accentGreen)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "View file",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
