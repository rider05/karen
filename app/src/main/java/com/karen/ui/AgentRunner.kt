package com.karen.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Plan execution agent.
 *
 * The chat model writes a `///PLAN` checklist, but until now nothing executed
 * it: the checklist was decoration and the turn ended after at most two tool
 * calls. [executePlan] turns that checklist into a real loop:
 *
 *   pick the first unfinished step → ask the model for one tool call
 *   → validate and run it on the device → feed the honest result back
 *   → mark the step done → next step
 *
 * Every action goes through the same [executePhoneTool] executor as a direct
 * `///TOOL` call, so results are real and T2/T3 tools still require the user.
 * The plan line in the message is rewritten after each step, which is what
 * makes the PlanCard advance live while the agent works.
 */
data class AgentObservation(val tool: String, val ok: Boolean, val text: String)

data class AgentOutcome(
    val visible: String,
    val steps: List<PlanStep>,
    val observations: List<AgentObservation>,
    val toolsRun: Int,
    val stopped: String? = null
)

object AgentRunner {

    private const val MAX_ROUNDS = 8
    private const val MAX_CALLS_PER_STEP = 2

    /** True when the ask clearly wants the plan carried out, not just drafted. */
    fun wantsExecution(prompt: String): Boolean {
        val t = " ${prompt.lowercase()} "
        return listOf(
            "do it", "go ahead", "execute", "carry out", "carry it out", "run it",
            "do the steps", "do all", "and report", "then report", "actually do",
            "perform", "go for it", "make it happen", "start working"
        ).any { it in t }
    }

    /** Explicit "draft only" asks: never start executing their steps. */
    fun planOnly(prompt: String): Boolean {
        val t = " ${prompt.lowercase()} "
        return listOf(
            "just the plan", "only the plan", "plan only", "just plan",
            "don't do", "do not do", "no actions", "without doing",
            "don't execute", "do not execute", "outline the steps"
        ).any { it in t }
    }

    fun hasOpenSteps(steps: List<PlanStep>): Boolean =
        steps.any { it.status != PlanStatus.Done }

    /**
     * Runs the open steps of [stepsIn]. [visible] is the message body that
     * already carries the plan line; it is rewritten through [onProgress] so
     * the UI can redraw mid-flight. Returns the final body (with a fresh plan
     * line and one result note per action).
     */
    suspend fun executePlan(
        ctx: Context,
        goal: String,
        visible: String,
        stepsIn: List<PlanStep>,
        seed: List<AgentObservation> = emptyList(),
        callModel: suspend (String) -> String?,
        isCancelled: () -> Boolean = { false },
        onProgress: (String, List<PlanStep>) -> Unit = { _, _ -> }
    ): AgentOutcome {
        var text = visible
        val steps = stepsIn.toMutableList()
        val observations = seed.toMutableList()
        var toolsRun = 0
        var stopped: String? = null
        if (steps.isEmpty()) return AgentOutcome(text, steps, observations, 0, "no plan")

        var round = 0
        while (round < MAX_ROUNDS) {
            if (isCancelled()) {
                stopped = "cancelled"
                break
            }
            val idx = steps.indexOfFirst { it.status != PlanStatus.Done }
            if (idx < 0) break

            // Show the step as running before the model call, so the card
            // pulses on the step the agent is actually working on.
            if (steps[idx].status != PlanStatus.Doing) {
                steps[idx] = steps[idx].copy(status = PlanStatus.Doing)
                text = upsertPlan(text, steps)
                onProgress(text, steps)
            }

            val raw = try {
                callModel(stepPrompt(ctx, goal, steps, observations, idx))
            } catch (_: Exception) {
                null
            }
            if (isCancelled()) {
                stopped = "cancelled"
                break
            }
            if (raw.isNullOrBlank()) {
                stopped = "model unavailable"
                break
            }

            val calls = extractToolCalls(raw).take(MAX_CALLS_PER_STEP)
            if (calls.isEmpty()) {
                val verdict = raw.trim()
                when {
                    verdict.contains("BLOCKED", ignoreCase = true) -> {
                        stopped = verdict.substringAfter("BLOCKED", "")
                            .trim().trimStart(':', '-', ' ')
                            .ifBlank { "blocked" }
                        text = appendNote(text, "⚠ ${steps[idx].text}: $stopped")
                        steps[idx] = steps[idx].copy(status = PlanStatus.Todo)
                        text = upsertPlan(text, steps)
                        onProgress(text, steps)
                        break
                    }
                    verdict.contains("STEP_DONE", ignoreCase = true) -> {
                        steps[idx] = steps[idx].copy(status = PlanStatus.Done)
                        text = upsertPlan(text, steps)
                        onProgress(text, steps)
                        round++
                        continue
                    }
                    else -> {
                        // A short direct answer also completes the step: the
                        // model used what it already has instead of a tool.
                        val note = verdict.take(240)
                        observations.add(AgentObservation("agent", true, note))
                        steps[idx] = steps[idx].copy(status = PlanStatus.Done)
                        text = appendNote(text, "• ${steps[idx].text}: $note")
                        text = upsertPlan(text, steps)
                        onProgress(text, steps)
                        round++
                        continue
                    }
                }
            }

            var batchOk = true
            for (call in calls) {
                if (isCancelled()) {
                    stopped = "cancelled"
                    batchOk = false
                    break
                }
                val spec = ToolRouting.resolve(ctx, call.name)
                if (spec == null) {
                    stopped = "unknown tool \"${call.name}\""
                    text = appendNote(text, "⚠ $stopped")
                    batchOk = false
                    break
                }
                if (spec.tier != "T0" && spec.tier != "T1") {
                    stopped = "${spec.name} needs your confirmation"
                    text = appendNote(text, "⚠ $stopped - tap the confirm card to run it")
                    batchOk = false
                    break
                }
                val err = validateToolCall(spec, call.args)
                if (err != null) {
                    stopped = "${spec.name}: $err"
                    text = appendNote(text, "⚠ $stopped")
                    batchOk = false
                    break
                }
                val res = try {
                    executePhoneTool(ctx, spec, call.args)
                } catch (e: Exception) {
                    ExecResult(false, e.message ?: "failed")
                }
                toolsRun++
                observations.add(AgentObservation(spec.name, res.ok, res.text.take(600)))
                text = appendNote(
                    text,
                    (if (res.ok) "✓" else "⚠") + " ${spec.name}: " + res.text.take(240)
                )
                onProgress(text, steps)
                if (!res.ok) {
                    stopped = "${spec.name}: ${res.text.take(200)}"
                    batchOk = false
                    break
                }
            }
            if (!batchOk) {
                // Leave the step open (not a fake success) so a retry can pick
                // it up, and stop spending tokens on a failing path.
                steps[idx] = steps[idx].copy(status = PlanStatus.Todo)
                text = upsertPlan(text, steps)
                onProgress(text, steps)
                break
            }
            steps[idx] = steps[idx].copy(status = PlanStatus.Done)
            text = upsertPlan(text, steps)
            onProgress(text, steps)
            round++
        }

        if (stopped == null && steps.any { it.status != PlanStatus.Done }) {
            stopped = "step limit reached"
        }
        return AgentOutcome(text, steps, observations, toolsRun, stopped)
    }

    /** Rewrites the message's plan line to [steps]; the last valid line wins. */
    fun upsertPlan(text: String, steps: List<PlanStep>): String {
        val arr = JSONArray()
        steps.forEach { step ->
            arr.put(
                JSONObject()
                    .put("t", step.text.take(120))
                    .put(
                        "s",
                        when (step.status) {
                            PlanStatus.Done -> "done"
                            PlanStatus.Doing -> "doing"
                            PlanStatus.Todo -> "todo"
                        }
                    )
            )
        }
        val line = "///PLAN " + JSONObject().put("steps", arr).toString()
        val kept = text.lineSequence()
            .filterNot { it.trimStart().startsWith("///PLAN", ignoreCase = true) }
            .joinToString("\n")
            .trimStart('\n')
        return if (kept.isBlank()) line else line + "\n" + kept
    }

    private fun appendNote(text: String, note: String): String =
        if (text.isBlank()) note else text.trimEnd() + "\n" + note

    /**
     * One-step prompt. The catalog is the full T0/T1 set: a later step can
     * need a tool the opening prompt never mentioned, and this call is small.
     */
    private fun stepPrompt(
        ctx: Context,
        goal: String,
        steps: List<PlanStep>,
        observations: List<AgentObservation>,
        current: Int
    ): String = buildString {
        append("You are Karen's execution agent on this Android phone. Do one step, then stop.\n")
        append("GOAL: ").append(goal.take(800)).append("\n\n")
        append("PLAN:\n")
        steps.forEachIndexed { i, s ->
            val mark = when (s.status) {
                PlanStatus.Done -> "done"
                PlanStatus.Doing -> "doing"
                PlanStatus.Todo -> "todo"
            }
            append("${i + 1}. [$mark] ${s.text}\n")
        }
        append("\nDONE SO FAR:\n")
        if (observations.isEmpty()) {
            append("- nothing yet\n")
        } else {
            observations.takeLast(6).forEach { o ->
                append("- ").append(if (o.ok) "OK " else "FAIL ")
                append(o.tool).append(": ").append(o.text.take(300)).append("\n")
            }
        }
        append("\nCURRENT STEP: ").append(steps[current].text).append("\n\n")
        append("Decide the single next action and reply with exactly one of:\n")
        append("- ///TOOL {\"name\":\"<tool>\",\"arguments\":{...}} to use a phone tool\n")
        append("- STEP_DONE if the observations already satisfy this step or no device action is needed\n")
        append("- BLOCKED: <reason> if it needs the user or no safe tool can do it\n")
        append("Do not repeat an action listed under DONE SO FAR. Use only tools in this catalog:\n")
        append(ToolRouting.compact(ToolRouting.catalog(ctx)))
    }.toString()
}
