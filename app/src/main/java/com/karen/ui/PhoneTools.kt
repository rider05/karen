package com.karen.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.FileProvider
import com.karen.formatSize
import com.karen.readDeviceTelemetry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Runtime for tools_spec/tools.json (bundled at app/src/main/assets).
 *
 * The model proposes tool calls as text blocks:
 *   ///TOOL {"name":"get_time","arguments":{}}
 * This module loads the specs, validates calls, executes them for real,
 * and reports honest results. Nothing is pretended: an executor that
 * cannot confirm success says so in its result text.
 */

/** One argument from the spec. */
data class ToolArgSpec(
    val name: String,
    val type: String,
    val required: Boolean,
    val desc: String,
    val enum: List<String> = emptyList(),
    val min: Long? = null,
    val max: Long? = null,
    val itemsEnum: List<String> = emptyList()
)

/** One tool from tools.json. */
data class ToolSpec(
    val name: String,
    val desc: String,
    val tier: String,
    val args: List<ToolArgSpec>
)

/** A parsed ///TOOL block plus its span (for stripping from chat text). */
data class ParsedTool(val name: String, val args: JSONObject, val start: Int, val end: Int)

/** A T2 call awaiting user confirmation in the chat bubble. */
data class PendingTool(val name: String, val argsJson: String, val summary: String)

/** Honest executor outcome. */
data class ExecResult(val ok: Boolean, val text: String)

object ToolSpecs {
    @Volatile
    private var cached: List<ToolSpec>? = null

    /** Loads tools.json from assets (cached for the process). */
    fun load(ctx: Context): List<ToolSpec> {
        cached?.let { return it }
        val parsed = try {
            val text = ctx.assets.open("tools.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            root.keys().asSequence().mapNotNull { name ->
                val o = root.optJSONObject(name) ?: return@mapNotNull null
                val argObj = o.optJSONObject("arguments") ?: JSONObject()
                val args = argObj.keys().asSequence().map { an ->
                    val a = argObj.optJSONObject(an) ?: JSONObject()
                    ToolArgSpec(
                        name = an,
                        type = a.optString("type", "string"),
                        required = a.optBoolean("required", false),
                        desc = a.optString("description", ""),
                        enum = a.optJSONArray("enum")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList(),
                        min = if (a.has("min")) a.optLong("min") else null,
                        max = if (a.has("max")) a.optLong("max") else null,
                        itemsEnum = a.optJSONArray("items_enum")?.let { arr ->
                            (0 until arr.length()).map { arr.optString(it) }
                        } ?: emptyList()
                    )
                }.toList()
                ToolSpec(
                    name = name,
                    desc = o.optString("description", ""),
                    tier = o.optString("risk_tier", "T1").uppercase(),
                    args = args
                )
            }.toList()
        } catch (_: Exception) {
            emptyList()
        }
        cached = parsed
        return parsed
    }

    fun byName(ctx: Context, name: String): ToolSpec? =
        load(ctx).find { it.name == name }

    // Keyword routing: only relevant schemas reach the prompt (plan §12).
    private val triggers: Map<String, List<String>> = mapOf(
        "open_app" to listOf("open", "launch", "start", "app"),
        "open_url" to listOf("url", "website", "site", "link", "browse", "http"),
        "open_settings_page" to listOf("settings", "wifi", "wi-fi", "bluetooth"),
        "find_contact" to listOf("contact", "phone number", "number for", "whose number"),
        "place_call" to listOf("call", "dial", "ring ", "phone me", "phone "),
        "compose_message" to listOf("message", "text ", "sms", "whatsapp", "send", "tell "),
        "create_reminder" to listOf("remind", "reminder"),
        "create_calendar_event" to listOf("calendar", "event", "schedul", "meeting", "appointment"),
        "get_time" to listOf("time", "date", "today", "clock", "day is"),
        "get_device_info" to listOf("battery", "storage", "memory", "ram", "network"),
        "search_local_files" to listOf("file", "document", "pdf", "find "),
        "open_document" to listOf("open", "document", "pdf"),
        "read_document" to listOf("read", "document", "contents", "summar"),
        "set_timer" to listOf("timer", "countdown"),
        "set_alarm" to listOf("alarm", "wake"),
        "media_control" to listOf("music", "play", "pause", "volume", "mute", "song"),
        "calculator" to listOf("calculat", "math", "plus", "minus", "times", "divided", "percent"),
        "clipboard_read" to listOf("clipboard", "copied"),
        "clipboard_write" to listOf("clipboard", "copy"),
        "remember_fact" to listOf("remember", "my name", "birthday", "prefer", "i like", "i live"),
        "forget_fact" to listOf("forget")
    )

    /** Specs relevant to [prompt], capped so the prompt stays short. */
    fun relevant(ctx: Context, prompt: String): List<ToolSpec> {
        val all = load(ctx)
        if (all.isEmpty()) return emptyList()
        val t = " $prompt ".lowercase()
        val hit = all.filter { spec ->
            triggers[spec.name].orEmpty().any { t.contains(it) }
        }
        if (hit.isNotEmpty()) return hit.take(6)
        // No signal: offer the four cheapest read-only tools only.
        return all.filter { it.name in setOf("get_time", "calculator", "search_local_files", "get_device_info") }
    }

    /** Compact catalog + protocol block appended to the prompt. */
    fun promptBlock(ctx: Context, prompt: String): String {
        val specs = relevant(ctx, prompt)
        if (specs.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("You can act on the phone with tools. To use one, emit one line per call:\n")
        sb.append("///TOOL {\"name\":\"<tool>\",\"arguments\":{...}}\n")
        sb.append("Rules: only tools listed below; include every required argument with the right type; ")
        sb.append("at most 2 calls per reply; put ///TOOL lines at the very end and stop — the app runs them and reports back. ")
        sb.append("Never claim an action succeeded; the result comes from the app. T2 tools ask the user to confirm first.\n")
        sb.append("Available tools:\n")
        specs.forEach { s ->
            val argText = s.args.joinToString(", ") { a ->
                buildString {
                    append(a.name)
                    if (!a.required) append("?")
                    if (a.enum.isNotEmpty()) append(" in {${a.enum.joinToString("|")}}")
                }
            }
            sb.append("- ${s.name}(${if (argText.isBlank()) "no args" else argText}): ${s.desc}\n")
        }
        return sb.toString().trim()
    }
}

/** Matches a leading "/tool rest" command. Null when not a slash call. */
fun matchSlash(ctx: Context, text: String): Pair<ToolSpec, String>? {
    val m = Regex("^/([A-Za-z_][A-Za-z0-9_]*)\\s*([\\s\\S]*)$")
        .matchEntire(text.trim()) ?: return null
    val spec = ToolSpecs.byName(ctx, m.groupValues[1]) ?: return null
    return spec to m.groupValues[2].trim()
}

/** Expands "/tool args" into an explicit model instruction (AI path). */
fun expandSlash(ctx: Context, raw: String): String {
    val (spec, rest) = matchSlash(ctx, raw) ?: return raw
    val req = spec.args.filter { it.required }.joinToString(", ") { it.name }
    return "Use the \"${spec.name}\" tool now (${spec.desc}" +
        (if (req.isNotBlank()) ". Required arguments: $req" else "") + ")." +
        if (rest.isNotBlank()) " Request: $rest"
        else " Proceed with sensible defaults, or ask for the missing detail."
}

/**
 * Deterministic arg mapping for slash fast-path routing.
 * Returns args when the tool can run with no model round-trip,
 * null when AI is needed (multi-arg or ambiguous).
 */
fun directArgs(spec: ToolSpec, rest: String): JSONObject? {
    val r = rest.trim()
    fun obj(vararg ps: Pair<String, Any>): JSONObject =
        JSONObject().apply { ps.forEach { (k, v) -> put(k, v) } }
    return when (spec.name) {
        "get_time" -> obj().apply { if (r.isNotBlank()) put("timezone", r) }
        "clipboard_read" -> obj()
        "get_device_info" -> obj().apply {
            val f = r.lowercase()
            if (f in listOf("battery", "storage", "memory", "network")) put("field", f)
        }
        "calculator" -> if (r.isBlank()) null else obj("expression" to r)
        "search_local_files" -> if (r.isBlank()) null else obj("query" to r)
        "open_document" -> if (r.isBlank()) null else obj("query" to r)
        "read_document" -> if (r.isBlank()) null else obj("path" to r)
        "find_contact" -> if (r.isBlank()) null else obj("name" to r)
        "clipboard_write" -> if (r.isBlank()) null else obj("text" to r)
        "remember_fact" -> if (r.isBlank()) null else obj("fact" to r)
        "forget_fact" -> if (r.isBlank()) null else obj("fact" to r)
        "open_app" -> if (r.isBlank()) null else obj("app_name" to r)
        "open_url" -> {
            if (r.isBlank() || ' ' in r) null
            else obj("url" to (if (r.startsWith("http://") || r.startsWith("https://")) r else "https://$r"))
        }
        "open_settings_page" -> {
            val p = r.lowercase().replace(" ", "_")
            if (p in listOf("wifi", "bluetooth", "display", "sound", "battery", "storage", "apps", "location", "notifications", "airplane_mode")) {
                obj("page" to p)
            } else null
        }
        "media_control" -> {
            val a = r.lowercase().replace(" ", "_")
            if (a in listOf("play", "pause", "next", "previous", "volume_up", "volume_down", "mute")) {
                obj("action" to a)
            } else null
        }
        "set_timer" -> parseTimerArg(r)
        "set_alarm" -> parseAlarmArg(r)
        else -> null
    }
}

private fun parseTimerArg(r: String): JSONObject? {
    val m = Regex("(\\d+)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)?")
        .find(r.lowercase()) ?: return null
    val n = m.groupValues[1].toLongOrNull() ?: return null
    if (n <= 0) return null
    val mult = when (m.groupValues[2].firstOrNull()) {
        'h' -> 3600
        'm' -> 60
        else -> 1
    }
    val secs = n * mult
    if (secs > 86400) return null
    val label = r.replace(m.value, "").trim().take(60)
    return JSONObject().put("seconds", secs).apply {
        if (label.isNotBlank()) put("label", label)
    }
}

private fun parseAlarmArg(r: String): JSONObject? {
    val m = Regex("^(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$")
        .matchEntire(r.lowercase().trim()) ?: return null
    var h = m.groupValues[1].toIntOrNull() ?: return null
    val min = m.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
    when (m.groupValues[3]) {
        "am" -> if (h == 12) h = 0
        "pm" -> if (h < 12) h += 12
    }
    if (h !in 0..23 || min !in 0..59) return null
    return JSONObject().put("hour", h).put("minute", min)
}
/** Pulls ///TOOL JSON blocks out of model text (brace-matched, max 3). */
fun extractToolCalls(text: String): List<ParsedTool> {
    val out = mutableListOf<ParsedTool>()
    var i = 0
    while (out.size < 3) {
        val k = text.indexOf("///TOOL", i)
        if (k < 0) break
        val s = text.indexOf('{', k)
        if (s < 0) break
        var depth = 0
        var e = -1
        var inStr = false
        var esc = false
        for (j in s until text.length) {
            val c = text[j]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        e = j
                        break
                    }
                }
            }
        }
        if (e < 0) break
        try {
            val o = JSONObject(text.substring(s, e + 1))
            val name = o.optString("name", "").trim()
            if (name.isNotEmpty()) {
                out.add(ParsedTool(name, o.optJSONObject("arguments") ?: JSONObject(), k, e + 1))
            }
        } catch (_: Exception) {
        }
        i = e + 1
    }
    return out
}

/** Removes ///TOOL lines so chat shows the human reply only. */
fun stripToolCalls(text: String, calls: List<ParsedTool>): String {
    if (calls.isEmpty()) return text
    val sb = StringBuilder()
    var pos = 0
    calls.sortedBy { it.start }.forEach { c ->
        if (c.start > pos) sb.append(text.substring(pos, c.start))
        pos = maxOf(pos, c.end)
    }
    if (pos < text.length) sb.append(text.substring(pos))
    return sb.toString().replace(Regex("[ \\t]+\\n"), "\n").trim()
}

/** Validates a call against its spec. Null = valid, else the error. */
fun validateToolCall(spec: ToolSpec, args: JSONObject): String? {
    for (a in spec.args) {
        if (a.required && !args.has(a.name)) return "missing required argument \"${a.name}\""
    }
    for (a in spec.args) {
        if (!args.has(a.name) || args.isNull(a.name)) continue
        val v = args.get(a.name)
        when (a.type) {
            "string" -> if (v !is String) return "\"${a.name}\" must be text"
            "integer" -> {
                val ok = when (v) {
                    is Int, is Long -> true
                    is Number -> v.toDouble() % 1.0 == 0.0
                    is String -> v.toLongOrNull() != null
                    else -> false
                }
                if (!ok) return "\"${a.name}\" must be a whole number"
                val num = when (v) {
                    is Number -> v.toLong()
                    else -> (v as String).toLong()
                }
                if (a.min != null && num < a.min) return "\"${a.name}\" must be >= ${a.min}"
                if (a.max != null && num > a.max) return "\"${a.name}\" must be <= ${a.max}"
            }
            "array" -> {
                if (v !is org.json.JSONArray) return "\"${a.name}\" must be a list"
                if (a.itemsEnum.isNotEmpty()) {
                    for (idx in 0 until v.length()) {
                        if (v.optString(idx) !in a.itemsEnum) {
                            return "\"${a.name}[$idx]\" must be one of ${a.itemsEnum.joinToString(", ")}"
                        }
                    }
                }
            }
        }
        if (a.enum.isNotEmpty() && v is String && v !in a.enum) {
            return "\"${a.name}\" must be one of ${a.enum.joinToString(", ")}"
        }
    }
    return null
}

/** One-line summary of validated args for confirm cards and pills. */
fun summarizeArgs(spec: ToolSpec, args: JSONObject): String =
    spec.args.filter { args.has(it.name) && !args.isNull(it.name) }
        .joinToString(", ") { a ->
            val v = args.get(a.name)
            val s = if (v is org.json.JSONArray) (0 until v.length()).joinToString("/") { v.optString(it) } else v.toString()
            "${a.name}=$s"
        }.ifBlank { "no arguments" }

/** Explicit user facts (remember_fact / forget_fact). */
object FactStore {
    private const val PREFS = "karen_phone_facts"
    private const val KEY = "facts"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(ctx: Context): List<String> =
        (prefs(ctx).getString(KEY, "") ?: "").split(";;").filter { it.isNotBlank() }

    fun save(ctx: Context, fact: String): Int {
        val cur = all(ctx).toMutableList()
        cur.add("${System.currentTimeMillis()}||${fact.trim().take(240)}")
        prefs(ctx).edit().putString(KEY, cur.takeLast(100).joinToString(";;")).apply()
        return cur.size
    }

    /** Removes facts containing [text]; returns how many were removed. */
    fun forget(ctx: Context, text: String): Int {
        val q = text.trim().lowercase()
        val kept = all(ctx).filter { !it.substringAfter("||").lowercase().contains(q) }
        val removed = all(ctx).size - kept.size
        prefs(ctx).edit().putString(KEY, kept.joinToString(";;")).apply()
        return removed
    }
}

private fun argStr(args: JSONObject, name: String): String =
    args.optString(name, "").trim()

private fun argLong(args: JSONObject, name: String, def: Long = 0L): Long {
    args.optLong(name, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { return it }
    return args.optString(name, "").toLongOrNull() ?: def
}

/** Exact arithmetic without an engine (shunting-yard over BigDecimal doubles). */
private fun evalExpr(raw: String): Double {
    val s = raw.replace("\\s".toRegex(), "")
    if (s.isEmpty() || s.length > 200) throw IllegalArgumentException("bad expression")
    if (!s.matches(Regex("[0-9+\\-*/%^().]+"))) throw IllegalArgumentException("only numbers and + - * / % ^ ( ) allowed")
    var pos = 0
    fun peek(): Char? = if (pos < s.length) s[pos] else null
    fun eat(): Char = s[pos++]
    lateinit var expr: () -> Double
    lateinit var term: () -> Double
    lateinit var unary: () -> Double
    lateinit var power: () -> Double
    lateinit var primary: () -> Double
    expr = {
        var v = term()
        while (peek() == '+' || peek() == '-') {
            val op = eat()
            val r = term()
            v = if (op == '+') v + r else v - r
        }
        v
    }
    term = {
        var v = unary()
        while (peek() == '*' || peek() == '/' || peek() == '%') {
            val op = eat()
            val r = unary()
            v = when (op) {
                '*' -> v * r
                '/' -> v / r
                else -> v % r
            }
        }
        v
    }
    unary = {
        if (peek() == '+') {
            eat()
            power()
        } else if (peek() == '-') {
            eat()
            -power()
        } else power()
    }
    power = {
        val v = primary()
        if (peek() == '^') {
            eat()
            Math.pow(v, unary())
        } else v
    }
    primary = {
        val c = peek() ?: throw IllegalArgumentException("bad expression")
        if (c == '(') {
            eat()
            val v = expr()
            if (peek() != ')') throw IllegalArgumentException("missing )")
            eat()
            v
        } else {
            val m = Regex("^\\d+(\\.\\d+)?").find(s.substring(pos))
                ?: throw IllegalArgumentException("bad expression")
            pos += m.value.length
            m.value.toDouble()
        }
    }
    val result = expr()
    if (pos != s.length) throw IllegalArgumentException("bad expression")
    return result
}

private fun fmtNum(d: Double): String = when {
    d.isNaN() || d.isInfinite() -> "undefined (division by zero?)"
    d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15 -> d.toLong().toString()
    else -> {
        var t = "%.6f".format(d).trimEnd('0').trimEnd('.')
        if (t == "-0") t = "0"
        t
    }
}

private fun queryContacts(ctx: Context, q: String, limit: Int = 5): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    try {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$q%"),
            null
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (out.none { it.second == num }) out.add(n to num)
            }
        }
    } catch (_: Exception) {
    }
    return out
}

private fun digitsOf(s: String): String = s.filter { it.isDigit() }

private fun startIntent(ctx: Context, intent: Intent): Boolean {
    return try {
        if (intent.resolveActivity(ctx.packageManager) == null) return false
        if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Runs one validated tool call for real. Must be called on the main thread
 * (intents); blocking work is moved to Dispatchers.IO inside.
 */
suspend fun executePhoneTool(ctx: Context, spec: ToolSpec, args: JSONObject): ExecResult {
    return when (spec.name) {
        "get_time" -> {
            val tz = argStr(args, "timezone")
            try {
                val zone = if (tz.isBlank()) java.time.ZoneId.systemDefault() else java.time.ZoneId.of(tz)
                val now = java.time.ZonedDateTime.now(zone)
                ExecResult(true, now.format(java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z")))
            } catch (_: Exception) {
                ExecResult(false, "unknown time zone \"$tz\"")
            }
        }
        "calculator" -> {
            try {
                ExecResult(true, fmtNum(evalExpr(argStr(args, "expression"))))
            } catch (e: Exception) {
                ExecResult(false, e.message ?: "bad expression")
            }
        }
        "get_device_info" -> {
            try {
                val d = withContext(Dispatchers.IO) { readDeviceTelemetry(ctx.applicationContext) }
                val text = when (argStr(args, "field")) {
                    "battery" -> "Battery ${d.batteryPercent}%${if (d.batteryCharging) " (charging)" else ""}, ${"%.0f".format(d.batteryTempC)}°C"
                    "storage" -> "Storage ${"%.1f".format(d.storageTotalGb - d.storageFreeGb)} / ${"%.1f".format(d.storageTotalGb)} GB used"
                    "memory" -> "RAM ${"%.1f".format(d.totalRamGb - d.freeRamGb)} / ${"%.1f".format(d.totalRamGb)} GB used (${d.usedRamPercent}%)"
                    "network" -> if (d.networkUp) "Online via ${if (d.wifi) "Wi-Fi" else if (d.cellular) "cellular" else "network"}" else "Offline"
                    else -> ""
                }
                ExecResult(true, text)
            } catch (e: Exception) {
                ExecResult(false, "could not read device info (${e.message})")
            }
        }
        "open_url" -> {
            val url = argStr(args, "url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                ExecResult(false, "URL must start with https://")
            } else if (startIntent(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
                ExecResult(true, "opened $url in the browser")
            } else {
                ExecResult(false, "no browser found")
            }
        }
        "open_app" -> {
            val want = argStr(args, "app_name").lowercase()
            try {
                val pm = ctx.packageManager
                val mains = pm.queryIntentActivities(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                    0
                )
                val hit = mains.firstOrNull { ri ->
                    ri.loadLabel(pm)?.toString()?.lowercase()?.contains(want) == true
                }
                val launch = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
                if (launch != null && startIntent(ctx, launch)) {
                    ExecResult(true, "opened ${hit?.loadLabel(pm)}")
                } else {
                    ExecResult(false, "no installed app matches \"$want\"")
                }
            } catch (e: Exception) {
                ExecResult(false, "could not list apps (${e.message})")
            }
        }
        "open_settings_page" -> {
            val intent = when (argStr(args, "page")) {
                "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                "battery" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "storage" -> Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
                "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                "airplane_mode" -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                "notifications" -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                else -> null
            }
            if (intent != null && startIntent(ctx, intent)) ExecResult(true, "opened ${argStr(args, "page")} settings")
            else ExecResult(false, "settings page unavailable on this device")
        }
        "find_contact" -> {
            val hits = withContext(Dispatchers.IO) { queryContacts(ctx, argStr(args, "name")) }
            if (hits.isEmpty()) ExecResult(true, "no contacts match \"${argStr(args, "name")}\"")
            else ExecResult(true, hits.joinToString("\n") { "${it.first} — ${it.second}" })
        }
        "place_call" -> {
            var number = digitsOf(argStr(args, "number"))
            val display = argStr(args, "display_name")
            if (number.length < 3) {
                val hits = withContext(Dispatchers.IO) { queryContacts(ctx, argStr(args, "number"), 1) }
                number = digitsOf(hits.firstOrNull()?.second ?: "")
            }
            if (number.length < 3) {
                ExecResult(false, "no phone number to dial")
            } else if (startIntent(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))) {
                ExecResult(true, "dialer opened${if (display.isNotBlank()) " for $display" else ""} ($number) — tap call to place it")
            } else {
                ExecResult(false, "could not open the dialer")
            }
        }
        "compose_message" -> {
            val app = argStr(args, "app").lowercase()
            val text = argStr(args, "text")
            var number = digitsOf(argStr(args, "recipient"))
            if (number.length < 3) {
                number = digitsOf(withContext(Dispatchers.IO) { queryContacts(ctx, argStr(args, "recipient"), 1) }.firstOrNull()?.second ?: "")
            }
            if (number.length < 3) {
                ExecResult(false, "no phone number for \"${argStr(args, "recipient")}\"")
            } else if (app == "sms") {
                val ok = startIntent(
                    ctx,
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", text)
                )
                if (ok) ExecResult(true, "message prefilled to $number — you tap Send") else ExecResult(false, "no SMS app found")
            } else {
                val url = "https://wa.me/$number?text=${Uri.encode(text)}"
                if (startIntent(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
                    ExecResult(true, "WhatsApp chat opened with message prefilled — you tap Send")
                } else {
                    ExecResult(false, "could not open WhatsApp")
                }
            }
        }
        "create_reminder", "create_calendar_event" -> {
            try {
                val isEvent = spec.name == "create_calendar_event"
                val title = if (isEvent) argStr(args, "title") else argStr(args, "text")
                val startIso = if (isEvent) argStr(args, "start") else argStr(args, "datetime")
                val begin = java.time.OffsetDateTime.parse(startIso).toInstant().toEpochMilli()
                val end = if (isEvent && args.optString("end", "").isNotBlank()) {
                    java.time.OffsetDateTime.parse(argStr(args, "end")).toInstant().toEpochMilli()
                } else begin + 30 * 60 * 1000L
                val intent = Intent(Intent.ACTION_INSERT)
                    .setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, title)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                if (isEvent && args.optString("location", "").isNotBlank()) {
                    intent.putExtra(CalendarContract.Events.EVENT_LOCATION, argStr(args, "location"))
                }
                if (startIntent(ctx, intent)) ExecResult(true, "calendar opened with \"$title\" prefilled — you tap Save")
                else ExecResult(false, "no calendar app found")
            } catch (_: Exception) {
                ExecResult(false, "bad date-time (need ISO 8601 with offset, e.g. 2026-10-20T14:00:00+05:30)")
            }
        }
        "set_timer" -> {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, argLong(args, "seconds", 60L).toInt())
                .putExtra(AlarmClock.EXTRA_MESSAGE, argStr(args, "label").ifBlank { "Karen timer" })
            if (startIntent(ctx, intent)) ExecResult(true, "timer started for ${argLong(args, "seconds", 60L)}s")
            else ExecResult(false, "no clock app found")
        }
        "set_alarm" -> {
            val days = args.optJSONArray("days")?.let { arr ->
                arrayListOf<Int>().apply {
                    val map = mapOf("mon" to 2, "tue" to 3, "wed" to 4, "thu" to 5, "fri" to 6, "sat" to 7, "sun" to 1)
                    for (i in 0 until arr.length()) map[arr.optString(i)]?.let { add(it) }
                }
            } ?: arrayListOf()
            val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, argLong(args, "hour").toInt())
                .putExtra(AlarmClock.EXTRA_MINUTES, argLong(args, "minute").toInt())
                .putExtra(AlarmClock.EXTRA_MESSAGE, argStr(args, "label").ifBlank { "Karen alarm" })
                .putExtra(AlarmClock.EXTRA_DAYS, days)
            if (startIntent(ctx, intent)) {
                ExecResult(true, "alarm set for %02d:%02d".format(argLong(args, "hour").toInt(), argLong(args, "minute").toInt()))
            } else ExecResult(false, "no clock app found")
        }
        "media_control" -> {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (am == null) return ExecResult(false, "audio service unavailable")
            when (argStr(args, "action")) {
                "volume_up" -> {
                    am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ExecResult(true, "volume up")
                }
                "volume_down" -> {
                    am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    ExecResult(true, "volume down")
                }
                "mute" -> {
                    am.adjustVolume(AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    ExecResult(true, "muted")
                }
                else -> {
                    // Transport keys need a live player session; broadcast and
                    // report honestly since delivery can't be confirmed.
                    val code = when (argStr(args, "action")) {
                        "play", "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                        else -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    }
                    try {
                        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)
                        val up = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code)
                        ctx.sendOrderedBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, down), null)
                        ctx.sendOrderedBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, up), null)
                        ExecResult(true, "media key sent — if nothing happened, open the player first")
                    } catch (e: Exception) {
                        ExecResult(false, "media key failed (${e.message})")
                    }
                }
            }
        }
        "search_local_files" -> {
            val q = argStr(args, "query").lowercase()
            val exts = when (argStr(args, "file_type")) {
                "pdf" -> setOf("pdf")
                "docx" -> setOf("doc", "docx")
                "pptx" -> setOf("ppt", "pptx")
                "xlsx" -> setOf("xls", "xlsx")
                "image" -> setOf("jpg", "jpeg", "png", "webp", "gif")
                else -> emptySet()
            }
            val hits = withContext(Dispatchers.IO) {
                vaultFiles(ctx).filter {
                    it.name.lowercase().contains(q) && (exts.isEmpty() || it.extension.lowercase() in exts)
                }.take(8)
            }
            if (hits.isEmpty()) ExecResult(true, "no vault files match \"$q\"")
            else ExecResult(true, hits.joinToString("\n") { "${it.name} (${formatSize(it.length())})" })
        }
        "open_document" -> {
            val q = argStr(args, "query").lowercase()
            val hit = withContext(Dispatchers.IO) {
                vaultFiles(ctx).maxWithOrNull(compareBy(
                    { if (it.name.lowercase() == q) 2 else 0 },
                    { if (it.name.lowercase().contains(q)) 1 else 0 }
                ))?.takeIf { it.name.lowercase().contains(q) || it.name.lowercase() == q }
            }
            if (hit == null) {
                ExecResult(false, "no vault document matches \"${argStr(args, "query")}\"")
            } else {
                try {
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", hit)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, ctx.contentResolver.getType(uri) ?: mimeFor(hit.name))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    if (startIntent(ctx, intent)) ExecResult(true, "opened ${hit.name}")
                    else ExecResult(false, "no app can open ${hit.name}")
                } catch (e: Exception) {
                    ExecResult(false, "could not open ${hit.name} (${e.message})")
                }
            }
        }
        "read_document" -> {
            val target = argStr(args, "path")
            val file = withContext(Dispatchers.IO) {
                val direct = File(target)
                if (direct.isFile) direct
                else vaultFiles(ctx).firstOrNull {
                    it.name.equals(target, ignoreCase = true) || it.name.lowercase().contains(target.lowercase())
                }
            }
            if (file == null || !file.exists()) {
                ExecResult(false, "no vault document matches \"$target\"")
            } else if (file.extension.lowercase() == "pdf") {
                ExecResult(false, "${file.name} is a PDF — open it instead of reading (no PDF text engine on-device)")
            } else {
                val text = withContext(Dispatchers.IO) { readPreview(file, 4000) }
                if (text.isNullOrBlank()) ExecResult(false, "could not read ${file.name} as text")
                else ExecResult(true, "${file.name}:\n$text")
            }
        }
        "clipboard_read" -> {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                ExecResult(true, if (clip.isBlank()) "clipboard is empty" else clip.take(2000))
            } catch (e: Exception) {
                ExecResult(false, "clipboard unavailable (${e.message})")
            }
        }
        "clipboard_write" -> {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Karen", argStr(args, "text")))
                ExecResult(true, "copied to clipboard")
            } catch (e: Exception) {
                ExecResult(false, "copy failed (${e.message})")
            }
        }
        "remember_fact" -> {
            val n = FactStore.save(ctx, argStr(args, "fact"))
            ExecResult(true, "remembered (${n} stored) — manage in Memory")
        }
        "forget_fact" -> {
            val n = FactStore.forget(ctx, argStr(args, "fact"))
            ExecResult(true, if (n > 0) "forgot $n fact${if (n == 1) "" else "s"}" else "no stored fact matched")
        }
        else -> ExecResult(false, "unknown tool \"${spec.name}\"")
    }
}
