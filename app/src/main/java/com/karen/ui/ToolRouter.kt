package com.karen.ui

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tool routing + validation for the workspace pipeline.
 *
 * Routing picks the right tool per prompt (build vs lookup vs checks vs
 * plain chat) so the model doesn't have to guess from free text. Validation
 * verifies each tool's output actually happened — files on disk, exit
 * status captured, web context non-empty — so progress can never be faked.
 */
enum class WorkspaceTool { CHAT, WEB, SCAFFOLD, CHECKS }

/** Route a prompt to the right tool. Order: explicit checks → build → web → chat. */
fun routeTool(prompt: String): WorkspaceTool {
    val t = " $prompt ".lowercase()
    if (listOf("compile", "run tests", "run the tests", "lint", "syntax check", "verify build", "typecheck").any { t.contains(it) }) {
        return WorkspaceTool.CHECKS
    }
    if (projectNeedsScaffold(prompt)) return WorkspaceTool.SCAFFOLD
    if (WebSearch.needsSearch(prompt)) return WorkspaceTool.WEB
    return WorkspaceTool.CHAT
}

data class ToolVerdict(val ok: Boolean, val detail: String)

/** Verify scaffold output actually landed on disk, non-empty. */
suspend fun validateScaffoldFiles(project: Project, expected: List<String>): ToolVerdict =
    withContext(Dispatchers.IO) {
        if (expected.isEmpty()) return@withContext ToolVerdict(false, "no files produced")
        val missing = expected.filter { rel ->
            val f = safeScaffoldPath(project, rel) ?: return@filter true
            !f.isFile || f.length() == 0L
        }
        if (missing.isEmpty()) ToolVerdict(true, "${expected.size}/${expected.size} files verified on disk")
        else ToolVerdict(false, "missing or empty: ${missing.take(5).joinToString(", ")}")
    }

/** Verify a captured shell transcript ended cleanly (exit 0). */
fun validateShellOutput(out: String): ToolVerdict {
    val code = Regex("\\(exit (\\d+)\\)").findAll(out).lastOrNull()
        ?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when {
        code == null -> ToolVerdict(false, "no exit status captured")
        code == 0 -> ToolVerdict(true, "exit 0")
        else -> ToolVerdict(false, "exit $code")
    }
}

/** Verify a web lookup returned usable context. */
fun validateWebResult(text: String?): ToolVerdict =
    if (text.isNullOrBlank()) ToolVerdict(false, "empty web result")
    else ToolVerdict(true, "${text.length} chars of fresh context")

/**
 * Router for phone tools. One place decides which tools the model sees and
 * which tool a (possibly misspelled) name refers to.
 *
 * The old router only consulted a hand-written trigger list, so a prompt like
 * "what's the weather" or "navigate to the clinic" offered nothing useful and
 * a model that wrote `send_sms` instead of `compose_message` had its call
 * dropped. Scoring below combines triggers, tool-name tokens, description
 * words and aliases; [resolve] maps the names models actually emit onto the
 * real spec. Agent steps use [catalog] because a step can need any safe tool.
 */
object ToolRouting {

    /** Names models invent mapped onto the real spec names. */
    val aliases: Map<String, String> = mapOf(
        "send_sms" to "compose_message",
        "send_message" to "compose_message",
        "sms" to "compose_message",
        "text_message" to "compose_message",
        "whatsapp" to "compose_message",
        "launch_app" to "open_app",
        "start_app" to "open_app",
        "app_open" to "open_app",
        "run_app" to "open_app",
        "open_link" to "open_url",
        "browse" to "open_url",
        "website" to "open_url",
        "web" to "web_search",
        "search_web" to "web_search",
        "google" to "web_search",
        "internet_search" to "web_search",
        "find_file" to "search_local_files",
        "search_files" to "search_local_files",
        "find_document" to "search_local_files",
        "read_file" to "read_document",
        "open_file" to "open_document",
        "call" to "place_call",
        "dial" to "place_call",
        "phone_call" to "place_call",
        "wifi_settings" to "open_settings_page",
        "bluetooth_settings" to "open_settings_page",
        "settings" to "open_settings_page",
        "remind" to "create_reminder",
        "reminder" to "create_reminder",
        "timer" to "set_timer",
        "alarm" to "set_alarm",
        "play_music" to "media_control",
        "pause_music" to "media_control",
        "music" to "media_control",
        "copy" to "clipboard_write",
        "paste" to "clipboard_read",
        "device_info" to "get_device_info",
        "battery" to "get_device_info",
        "current_time" to "get_time",
        "date" to "get_time",
        "map" to "open_map",
        "maps" to "open_map",
        "navigate" to "open_map",
        "directions" to "open_map",
        "nearby" to "open_map",
        "share" to "share_text",
        "send_text" to "share_text",
        "flashlight" to "toggle_flashlight",
        "torch" to "toggle_flashlight",
        "calc" to "calculator"
    )

    /** Prompt cues per tool, the strongest routing signal. */
    private val triggers: Map<String, List<String>> = mapOf(
        "open_app" to listOf("open", "launch", "start", "app"),
        "open_url" to listOf("url", "website", "site", "link", "browse", "http"),
        "open_settings_page" to listOf("settings", "wifi", "wi-fi", "bluetooth", "airplane"),
        "find_contact" to listOf("contact", "phone number", "number for", "whose number"),
        "place_call" to listOf("call", "dial", "ring ", "phone me", "phone "),
        "compose_message" to listOf("message", "text ", "sms", "whatsapp", "send", "tell "),
        "create_reminder" to listOf("remind", "reminder"),
        "create_calendar_event" to listOf("calendar", "event", "schedul", "meeting", "appointment"),
        "get_time" to listOf("time", "date", "today", "clock", "day is"),
        "get_device_info" to listOf("battery", "storage", "memory", "ram", "network", "device"),
        "search_local_files" to listOf("file", "document", "pdf", "find ", "download"),
        "open_document" to listOf("open", "document", "pdf"),
        "read_document" to listOf("read", "document", "contents", "summar"),
        "set_timer" to listOf("timer", "countdown"),
        "set_alarm" to listOf("alarm", "wake"),
        "media_control" to listOf("music", "play", "pause", "volume", "mute", "song"),
        "calculator" to listOf("calculat", "math", "plus", "minus", "times", "divided", "percent"),
        "clipboard_read" to listOf("clipboard", "copied"),
        "clipboard_write" to listOf("clipboard", "copy"),
        "remember_fact" to listOf("remember", "my name", "birthday", "prefer", "i like", "i live"),
        "forget_fact" to listOf("forget"),
        "web_search" to listOf("search", "google", "look up", "latest", "news", "weather", "who is", "what is"),
        "open_map" to listOf("map", "directions", "navigate", "nearby", "route", "how far"),
        "share_text" to listOf("share", "forward", "send this"),
        "toggle_flashlight" to listOf("flashlight", "torch", "light on", "light off")
    )

    /** Words that appear in almost every description; matching them means nothing. */
    private val generic = setOf(
        "the", "and", "for", "with", "your", "that", "this", "from", "into", "then",
        "when", "what", "how", "does", "can", "you", "please", "using", "use", "one",
        "tool", "phone", "android", "value", "string", "name", "number", "page"
    )

    fun normalize(name: String): String =
        name.trim().lowercase().replace('-', '_').replace(' ', '_')

    /** Read-only, always-useful tools offered when a prompt has no signal. */
    private val fallback = listOf(
        "get_time", "calculator", "search_local_files", "get_device_info", "web_search"
    )

    /**
     * Tools that best fit [prompt], capped so the prompt block stays short.
     * Score = explicit name (8) + trigger (3) + alias (2) + name token (2)
     * + description word (1). Ties keep catalog order.
     */
    fun route(ctx: Context, prompt: String, limit: Int = 6): List<ToolSpec> {
        val all = ToolSpecs.load(ctx)
        if (all.isEmpty()) return emptyList()
        val t = " " + prompt.lowercase().replace(Regex("\\s+"), " ") + " "
        val scored = all.mapNotNull { spec ->
            var score = 0
            if (t.contains(" ${spec.name} ")) score += 8
            triggers[spec.name].orEmpty().forEach { cue -> if (t.contains(cue)) score += 3 }
            aliases.forEach { (alias, target) ->
                if (target == spec.name && t.contains(" ${alias.replace('_', ' ')} ")) score += 2
            }
            spec.name.split('_').filter { it.length >= 3 && it !in generic }.forEach { tok ->
                if (t.contains(tok)) score += 2
            }
            val words = spec.desc.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 4 && it !in generic }.toSet()
            var descHits = 0
            words.forEach { w -> if (t.contains(w)) descHits++ }
            score += descHits.coerceAtMost(3)
            if (score > 0) spec to score else null
        }.sortedByDescending { it.second }
        if (scored.isEmpty()) return all.filter { it.name in fallback }
        return scored.take(limit.coerceIn(1, 12)).map { it.first }
    }

    /**
     * Every safe tool (T0/T1), compact. Agent steps see all of them because a
     * step can legitimately need a tool the first prompt never hinted at.
     */
    fun catalog(ctx: Context): List<ToolSpec> =
        ToolSpecs.load(ctx).filter { it.tier == "T0" || it.tier == "T1" }
            .sortedBy { it.name }

    /** Resolve a model-written name onto a real spec (exact → alias → variants). */
    fun resolve(ctx: Context, raw: String): ToolSpec? {
        val all = ToolSpecs.load(ctx)
        if (all.isEmpty()) return null
        val n = normalize(raw)
        all.firstOrNull { it.name == n }?.let { return it }
        aliases[n]?.let { target -> all.firstOrNull { it.name == target }?.let { return it } }
        // "phone_open_app", "open_app_tool", "compose-message"
        all.firstOrNull { n.endsWith("_" + it.name) || n.startsWith(it.name + "_") }?.let { return it }
        val squashed = n.replace("_", "")
        return all.firstOrNull { it.name.replace("_", "") == squashed }
    }

    /** Compact catalog lines: `- name(arg, arg?): description`. */
    fun compact(specs: List<ToolSpec>): String = specs.joinToString("\n") { s ->
        val args = s.args.joinToString(", ") { a ->
            buildString {
                append(a.name)
                if (!a.required) append('?')
                if (a.enum.isNotEmpty()) append(" in {${a.enum.joinToString("|")}}")
            }
        }
        "- ${s.name}(${if (args.isBlank()) "no args" else args}): ${s.desc}"
    }
}
