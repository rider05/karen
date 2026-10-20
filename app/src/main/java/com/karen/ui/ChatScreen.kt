package com.karen.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import com.karen.formatSize
import com.karen.rememberVoiceStt
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Built-in /help answer: how tools, mentions, and routing work. */
private const val HELP_TEXT = """How Karen tools work

Type / anywhere to list every phone tool, or @ to attach a vault file. Works mid-sentence.

RUNNING A TOOL
• /tool plus plain text — e.g. /calculator 1847*23, /get_time Asia/Kolkata, /open_app Spotify, /set_timer 2 min eggs
• Clear-cut calls run at once, no model involved. Anything ambiguous goes to the AI, which fills the details.
• Just ask normally too — "call Arun", "remind me at 6" routes the same way.

SAFETY TIERS (colored dots)
• Green T0 — reads and math, always auto-run.
• Blue T1 — reversible actions (open app, timer), auto-run with a visible pill.
• Amber T2 — calls, messages, calendar, memory: a Run/Cancel card asks first. Nothing sends or dials without your tap. T3 stays disabled.

FILES — RAG GROUNDING
• @name grounds the turn in that vault file: the document is split into chunks and only the passages closest to your question are retrieved and handed to the model — the whole file is not dumped, so long notes still fit.
• Text files read direct; PDFs transcribe through a vision-capable cloud key (first pages).
• Type @ anywhere to list vault files; follow-ups stay grounded in the file, cited by name.

MODELS
• Header pill shows what's answering: a name, or Auto → routed pick.
• Settings → Model Routing: Auto-switching per task, or pin one model everywhere (Chat, Voice, Workspace).
• Settings → API Failover: failed keys auto-try the next API, optionally falling back on-device.

VOICE
• Voice mode streams replies sentence by sentence; talk over Karen to interrupt. Needs mic + a model (cloud key or local weight). Wake word ("Hey Jarvis") lives in Settings → Voice & Speech."""

sealed class ChatItem {
    data class User(val id: String, val text: String, val attachments: List<Attachment> = emptyList(), val tokens: Int = 0) : ChatItem()
    data class Assistant(
        val id: String,
        val thought: String? = null,
        val toolCall: String? = null,
        val text: String,
        /** T2 tool call awaiting user confirmation (null = none pending). */
        val pendingTool: PendingTool? = null,
        val codeJson: String? = null,
        /** How long this reply took to generate, ms. 0 = unknown/not finished. */
        val tookMs: Long = 0L,
        /** Output tokens: provider-reported when [usageReal], else ≈chars/4. 0 = unknown. */
        val tokens: Int = 0,
        /** Input tokens for the turn: provider-reported or ≈prompt chars/4. 0 = unknown. */
        val inTokens: Int = 0,
        /** Reasoning/thinking tokens: provider-reported or ≈think-block chars/4. */
        val reasonTokens: Int = 0,
        /** True when the token counts came from provider usage, not estimates. */
        val usageReal: Boolean = false,
        /** True when this reply was cut short by Stop. */
        val interrupted: Boolean = false,
        /** Model that wrote this reply (manual pick or Auto route). */
        val model: String = "",
        /** Rendered as the built-in help card instead of a text wall (/help). */
        val help: Boolean = false
    ) : ChatItem()
}

/** A prompt typed while the model was busy, run after the current reply. */
data class QueuedPrompt(val text: String, val attachments: List<Attachment> = emptyList())

/** Local load+generation budget before falling back to a keyed cloud model. */
private const val LOCAL_TURN_TIMEOUT_MS = 150_000L

/**
 * Local inference runs here — never on Dispatchers.IO. A thread blocked in
 * JNI ignores coroutine cancellation, so the timeout abandons it on this
 * pool (cached: the stuck call still finishes and frees its thread) while
 * the turn continues on a cloud model.
 */
private val localInferenceDispatcher =
    java.util.concurrent.Executors.newCachedThreadPool().asCoroutineDispatcher()

/** An attachment picked from the device, pending to be added to the chat. */
data class Attachment(val name: String, val sizeBytes: Long, val mime: String? = null, val path: String? = null)

/** Text extensions whose attachment content is fed to the model. */
internal val ATTACH_TEXT_EXTS = setOf(
    "txt", "md", "markdown", "json", "csv", "tsv", "log", "xml", "yaml", "yml",
    "ini", "cfg", "properties", "toml", "kt", "java", "py", "js", "ts", "tsx",
    "jsx", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "swift",
    "sql", "sh", "gradle", "html", "css"
)

/**
 * Stored text content of an attachment for model context (capped).
 * Binary/oversize/missing files yield null (name-only, as before).
 */
fun attachmentText(a: Attachment, maxChars: Int = 12000): String? {
    val p = a.path ?: return null
    try {
        val f = File(p)
        if (!f.exists() || f.length() <= 0 || f.length() > 200000) return null
        val ext = a.name.substringAfterLast('.', "").lowercase()
        val mime = a.mime ?: ""
        if (ext !in ATTACH_TEXT_EXTS && !mime.startsWith("text/") && mime != "application/json") return null
        return f.readText().take(maxChars).ifBlank { null }
    } catch (_: Exception) {
        return null
    }
}

/**
 * Stored attachment content appended to a user turn so the conversation
 * remembers files (text types, capped; others stay name-only).
 */
fun attachedContext(item: ChatItem.User): String {
    val extra = item.attachments.take(3).mapNotNull { a ->
        attachmentText(a)?.let { "[Attached file ${a.name} content:\n$it]" }
    }.joinToString("\n\n").takeIf { it.isNotBlank() } ?: return ""
    return "\n\n$extra"
}

/** Typing-effect speed per effort level. */
fun effortDelayMs(effort: String): Long = when (effort) {
    "Low" -> 8L
    "High" -> 24L
    "Max" -> 36L
    "Extreme" -> 50L
    "XHigh" -> 60L
    else -> 12L
}

/** Reply budget per effort level for cloud calls. */
fun maxTokensFor(effort: String): Int = when (effort) {
    "Low" -> 256
    "High" -> 1024
    "Max" -> 2048
    "Extreme" -> 4096
    // Same ceiling as Extreme: only reasoning providers can act on XHigh, and
    // they steer depth with the effort string, not this budget.
    "XHigh" -> 4096
    else -> 512
}

/** Human reply timing: seconds, minutes, or hours — always truthful. */
fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        ms < 0 -> "0.0s"
        s < 60 -> "%.1fs".format(ms / 1000.0)
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${(s % 3600) / 60}m"
    }
}

/**
 * Content-window scaling (Settings → Content Window, up to 16k). Larger
 * windows keep more turns and more project text in front of the model.
 */
fun historyTurnsFor(windowTokens: Int): Int = when {
    windowTokens >= 16384 -> 60
    windowTokens >= 8192 -> 40
    else -> 20
}

/**
 * Cloud turns ignore the local content-window setting (it sizes on-device
 * RAM, not API context). Providers serve 32k–1M+ tokens; 60 turns keeps
 * long conversations coherent without unbounded growth.
 */
fun cloudHistoryTurns(): Int = 60

fun localTurnsFor(windowTokens: Int): Int = when {
    windowTokens >= 16384 -> 24
    windowTokens >= 8192 -> 16
    else -> 10
}

/** Approx. chars of project context that fit ~half the window. */
fun contextCharsFor(windowTokens: Int): Int = (windowTokens * 9 / 4).coerceIn(4096, 36864)

fun windowLabel(tokens: Int): String = when {
    tokens >= 16384 -> "16K"
    tokens >= 8192 -> "8K"
    tokens >= 4096 -> "4K"
    else -> "2K"
}

@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToVoice: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToModelManager: () -> Unit = {},
    openConversationId: String? = null,
    newChatSignal: Int = 0,
    onConversationOpened: () -> Unit = {},
    onHistoryChanged: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf(TextFieldValue("")) }

    /** Sets the composer text with the cursor parked at the end. */
    fun setInputEnd(text: String) {
        input = TextFieldValue(
            text,
            TextRange(text.length)
        )
    }
    // Bumped once prompt memory has been seeded from local history, so the
    // composer's inline completions pick the seeded entries up.
    var promptMemoryRevision by remember { mutableStateOf(0) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var isGenerating by remember { mutableStateOf(false) }
    var streamingId by remember { mutableStateOf<String?>(null) }
    var streamingContent by remember { mutableStateOf(false) }
    var cancelGeneration by remember { mutableStateOf(false) }
    var streamStartMs by remember { mutableStateOf(0L) }
    var genLive by remember { mutableStateOf<String?>(null) }
    // One-time web-search consent: gate suspends the send until the user answers.
    var webConsentGate by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var webConsentPreview by remember { mutableStateOf<String?>(null) }
    // Usage stats dialog + dislike feedback entry.
    var showUsage by remember { mutableStateOf(false) }
    var dislikeFor by remember { mutableStateOf<ChatItem.Assistant?>(null) }
    var dislikeText by remember { mutableStateOf("") }

    var resettingNewChat by remember { mutableStateOf(false) }
    var temporaryChat by remember { mutableStateOf(false) }
    val karenCtx = androidx.compose.ui.platform.LocalContext.current
    val device = com.karen.rememberDeviceTelemetry()
    var effort by remember { mutableStateOf(UserPrefs.defaultEffort(karenCtx)) }
    // Compute route for this chat: "" = this phone, "auto" = fastest joined
    // node, else a worker id (w_xxxxxx). Survives restarts via UserPrefs.
    var workerRoute by remember { mutableStateOf(UserPrefs.workerTarget(karenCtx)) }

    val voiceStt = rememberVoiceStt(
        onResult = { speechText ->
            input = if (input.text.isBlank()) {
                TextFieldValue(
                    speechText,
                    TextRange(speechText.length)
                )
            } else {
                val combined = "${input.text} $speechText"
                TextFieldValue(
                    combined,
                    TextRange(combined.length)
                )
            }
        }
    )

    // ---------- File upload / attachments ----------
    val context = LocalContext.current
    // Content-window switcher dialog.
    var showWindowDialog by remember { mutableStateOf(false) }
    var contentWindow by remember { mutableStateOf(UserPrefs.contextTokens(context)) }
    // Custom instructions dialog (stored to memory, used in every prompt).
    var showCustomDialog by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    // Restore last manual pick when still usable, else first weight.
    var selectedModel by remember {
        mutableStateOf(
            UserPrefs.selectedModel(context).takeIf { isSelectableModel(context, it) }
                ?: (UserPrefs.models(context).firstOrNull { !isCloudProviderName(it) } ?: modelCatalog.first().name)
        )
    }
    // Auto routing: pick the best installed/connected model per message.
    var autoRoute by remember { mutableStateOf(UserPrefs.autoRoute(context)) }
    var lastRouted by remember { mutableStateOf<String?>(null) }
    // Study mode (explainer style) quick toggle, persisted like Settings.
    var explainerPref by remember { mutableStateOf(UserPrefs.explainerMode(context)) }

    /** Manual model, or the routed pick when Auto is on. */
    fun effectiveModel(prompt: String): String {
        if (!autoRoute) return selectedModel
        val locals = UserPrefs.models(context)
            .filter { !isCloudProviderName(it) && !isEmbeddingModel(it) && ModelDownloader.isRunnableWeight(context, it) }
        val keyed = allCloudProviders(context).filter { UserPrefs.apiKey(context, it.id).isNotBlank() }
        val decision = routeModel(
            prompt, locals, keyed, UserPrefs.autoCloud(context),
            UserPrefs.preferredLocalModel(context), UserPrefs.preferredApiModel(context)
        )
        if (decision != null) {
            if (decision.modelName != lastRouted) {
                lastRouted = decision.modelName
                UserPrefs.pushRecentModel(context, decision.modelName)
                android.widget.Toast.makeText(context, "Auto: ${decision.modelName} (${decision.reason})", android.widget.Toast.LENGTH_SHORT).show()
            }
            return decision.modelName
        }
        return selectedModel
    }
    var attachments by remember { mutableStateOf(listOf<Attachment>()) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun addUris(uris: List<Uri>) {
        val picked = uris.mapNotNull { uri ->
            try {
                val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
                } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val size = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                } ?: 0L
                val mime = context.contentResolver.getType(uri)
                uri to Attachment(name = name, sizeBytes = size, mime = mime)
            } catch (_: Exception) { null }
        }
        attachments = attachments + picked.map { it.second }
        // Persist copies in background so the conversation remembers content.
        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            picked.forEach { (uri, att) ->
                try {
                    val dir = File(context.filesDir, "attachments").apply { mkdirs() }
                    val safe = att.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60).ifBlank { "file" }
                    val dest = File(dir, "${System.currentTimeMillis()}_$safe")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                    }
                    if (dest.exists() && dest.length() > 0) {
                        val idx = attachments.indexOfFirst {
                            it.path == null && it.name == att.name && it.sizeBytes == att.sizeBytes
                        }
                        if (idx >= 0) {
                            val updated = attachments.toMutableList()
                            updated[idx] = updated[idx].copy(path = dest.absolutePath)
                            attachments = updated
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addUris(uris)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addUris(uris)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addUris(uris)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && pendingCameraUri != null) {
            addUris(listOfNotNull(pendingCameraUri))
        }
        pendingCameraUri = null
    }

    fun takePhoto() {
        try {
            val dir = File(context.cacheDir, "photos").apply { mkdirs() }
            val file = File(dir, "karen_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            pendingCameraUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            pendingCameraUri = null
            android.widget.Toast.makeText(context, "Camera not available on this device", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val messages = remember {
        mutableStateListOf<ChatItem>()
    }
    var conversationId by remember { mutableStateOf(System.currentTimeMillis().toString()) }
    // Prompts typed while the model was busy: run in order after each reply.
    val queue = remember { mutableStateListOf<QueuedPrompt>() }
    // Id of a queued user message awaiting its turn (set by completeSettle).
    var pendingDrainId by remember { mutableStateOf<String?>(null) }

    /** Parks the current composer text to run after the in-flight reply. */
    fun enqueueCurrent() {
        val userText = input.text.trim()
        if (userText.isBlank() && attachments.isEmpty()) return
        if (userText.isNotBlank() && !temporaryChat) PromptMemory.record(context, userText)
        val sentAttachments = attachments
        input = TextFieldValue("")
        attachments = emptyList()
        queue.add(QueuedPrompt(userText.ifBlank { sentAttachments.joinToString(", ") { it.name } }, sentAttachments))
    }

    /** Writes the current conversation to local history (skipped for temporary chats). */
    fun persist() {
        if (temporaryChat || messages.isEmpty()) return
        val snapshot = messages.toList()
        // Never archive a bare status/blank provisional bubble mid-generation.
        val cleaned = if (isGenerating && streamingId != null && !streamingContent)
            snapshot.filter { (it as? ChatItem.Assistant)?.id != streamingId } else snapshot
        if (cleaned.isEmpty()) return
        ChatHistoryStore.saveConversation(context, conversationId, cleaned)
        // Recently-opened feed for the Home card (title = the opening ask).
        val firstAsk = cleaned.firstOrNull { it is ChatItem.User }
            ?.let { (it as ChatItem.User).text }.orEmpty()
        if (firstAsk.isNotBlank()) {
            RecentActivity.record(context, RecentActivity.TYPE_CHAT, conversationId, firstAsk)
        }
        onHistoryChanged()
    }

    /** Direct temp-chat toggle (header icon + overflow menu share this). */
    fun toggleTemporaryChat() {
        if (!temporaryChat) {
            persist()
            messages.clear()
            queue.clear()
            conversationId = System.currentTimeMillis().toString()
        }
        temporaryChat = !temporaryChat
        android.widget.Toast.makeText(context, if (temporaryChat) "Temporary chat on — not saved" else "Temporary chat off", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Updates the in-list provisional reply (the reply streams in place). */
    fun setStreamingText(id: String, text: String) {
        // Stop settles the bubble and clears streamingId immediately; a chunk
        // still in flight must never repaint the stopped reply.
        if (id != streamingId) return
        val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
        if (i >= 0) {
            val cur = messages[i] as ChatItem.Assistant
            if (cur.text != text) messages[i] = cur.copy(text = text)
        }
    }

    /** Updates the assistant bubble's model footer (node-route fallback honesty). */
    fun setStreamingModel(id: String, model: String) {
        val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
        if (i >= 0) {
            val cur = messages[i] as ChatItem.Assistant
            if (cur.model != model) messages[i] = cur.copy(model = model)
        }
    }

    /**
     * Follows new output only when the user is already at the bottom —
     * scrolled-up readers are never yanked (free to scroll while streaming).
     * Offset-aware: a tall streaming reply still counts as "left behind" once
     * its bottom runs past the viewport.
     */
    suspend fun scrollToBottomIfNear() {
        try {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return
            val last = info.visibleItemsInfo.lastOrNull() ?: return
            if (last.index < total - 1) return
            val beyondPx = (last.offset + last.size) - info.viewportEndOffset
            // Only auto-scroll when the user is near the bottom. Use scrollBy
            // (relative nudge) instead of scrollToItem (which re-anchors to
            // the item top and jumps the user upward during streaming).
            if (beyondPx in 1..500) {
                listState.scrollBy(beyondPx.toFloat())
            }
        } catch (_: Exception) {}
    }

    /** Drops model droppings: leading bare null/none lines or null runs. */
    fun cleanReply(text: String): String =
        text.replaceFirst(Regex("^((?:\\s*null){2,}\\s*|\\s*(?:null|none|nil)\\s*\\n)+", RegexOption.IGNORE_CASE), "")
            .trimStart('\n')

    /** Whitespace plus zero-width/format space separators models emit. */
    fun isInvisibleChar(c: Char): Boolean =
        c.isWhitespace() || c == '\u200B' || c == '\uFEFF' || c == '\u200E' || c == '\u200F' ||
            c == '\u2028' || c == '\u2029' || c == '\u00A0' || c == '\u1680' || c == '\u180E' ||
            c == '\u205F' || c == '\u3000' || (c in '\u2000'..'\u200A')

    /** True when text has anything a human can see (not blank/invisible). */
    fun hasVisibleContent(s: String): Boolean = s.any { !isInvisibleChar(it) }

    /** Typing-effect playback writing straight into the chat-list message. */
    suspend fun playOut(id: String, text: String) {
        val clean = cleanReply(text)
        streamingContent = clean.isNotEmpty()
        var n = 0
        for (idx in clean.indices) {
            if (cancelGeneration) break
            setStreamingText(id, clean.substring(0, idx + 1))
            n++
            if (n % 8 == 0) {
                scrollToBottomIfNear()
            }
            kotlinx.coroutines.delay(effortDelayMs(effort))
        }
    }

    /** Stamps the finished reply with how long it took (even when stopped). */
    fun stampTook(id: String, tokens: Int? = null) {
        val elapsed = System.currentTimeMillis() - streamStartMs
        val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
        if (i >= 0) {
            (messages[i] as? ChatItem.Assistant)?.let {
                // Provider-reported output tokens win over the chars/4 estimate.
                val approx = tokens ?: it.text.length / 4
                val out = if (it.usageReal && it.tokens > 0) it.tokens else approx
                messages[i] = it.copy(tookMs = elapsed, tokens = out)
            }
        }
    }

    /**
     * Realtime tool loop: validates ///TOOL calls from a finished reply,
     * runs T0/T1 immediately, and parks T2 behind a confirmation card.
     * Returns the text to display (tool lines stripped) with executor
     * results appended. Results come from the executors, never model prose.
     */
    suspend fun settleTools(id: String, reply: String): ToolSettle {
        val calls = extractToolCalls(reply)
        if (calls.isEmpty()) return ToolSettle(reply)
        var visible = stripToolCalls(reply, calls)
        val notes = mutableListOf<String>()
        val pills = mutableListOf<String>()
        val observations = mutableListOf<AgentObservation>()
        var ran = 0
        var docText: String? = null
        var docName: String? = null
        for (call in calls.take(2)) {
            val spec = ToolSpecs.byName(context, call.name)
            if (spec == null) {
                notes.add("⚠ unknown tool \"${call.name}\" — not run")
                continue
            }
            val err = validateToolCall(spec, call.args)
            if (err != null) {
                notes.add("⚠ ${spec.name}: $err — not run")
                continue
            }
            if (spec.tier == "T3") {
                notes.add("⚠ ${spec.name} is disabled (T3) — not run")
                continue
            }
            // place_call: resolve the name against contacts first so every
            // mode confirms or dials a real number, never a bare name.
            var resolvedArgs = call.args
            var options = emptyList<String>()
            if (spec.name == "place_call" && digitsOf(call.args.optString("number", "")).length < 3) {
                val q = call.args.optString("number", "")
                val hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    queryContacts(context, q, 5)
                }
                if (hits.isEmpty()) {
                    notes.add("⚠ place_call: no contacts match \"$q\" — not run")
                    continue
                } else if (hits.size == 1) {
                    resolvedArgs = org.json.JSONObject(call.args.toString())
                    resolvedArgs.put("number", digitsOf(hits[0].second))
                    if (resolvedArgs.optString("display_name", "").isBlank()) {
                        resolvedArgs.put("display_name", hits[0].first)
                    }
                } else {
                    options = hits.map { "${it.first} — ${it.second}" }
                }
            }
            // Several matches always need a pick; T2 otherwise parks unless
            // the user chose direct/dialer-only in Settings.
            if (options.isNotEmpty() || (spec.tier == "T2" && !runsDirect(spec, context))) {
                val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
                if (i >= 0) {
                    val cur = messages[i] as ChatItem.Assistant
                    messages[i] = cur.copy(
                        pendingTool = PendingTool(
                            name = spec.name,
                            argsJson = resolvedArgs.toString(),
                            summary = summarizeArgs(spec, resolvedArgs),
                            options = options
                        )
                    )
                }
                notes.add(
                    if (options.isNotEmpty()) "${spec.name}: ${options.size} similar contacts — pick one below"
                    else "${spec.name}: waiting for your confirmation below"
                )
                continue
            }
            val t0 = System.currentTimeMillis()
            val res = try {
                executePhoneTool(context, spec, resolvedArgs)
            } catch (e: Exception) {
                ExecResult(false, "failed (${e.message})")
            }
            ran++
            observations.add(AgentObservation(spec.name, res.ok, res.text.take(600)))
            val ms = System.currentTimeMillis() - t0
            pills.add("${spec.name} · ${ms}ms")
            notes.add((if (res.ok) "✓ " else "⚠ ") + "${spec.name}: ${res.text}")
            // Document reads feed a grounded follow-up answer below.
            if (spec.name == "read_document" && res.ok && res.text.length > 200) {
                docName = res.text.substringBefore(":\n").take(80).ifBlank { spec.name }
                docText = res.text.substringAfter(":\n", res.text).take(3000)
            }
        }
        if (notes.isNotEmpty()) {
            visible = (if (visible.isBlank()) "" else visible.trimEnd() + "\n\n") +
                notes.joinToString("\n")
        }
        if (pills.isNotEmpty()) {
            val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
            if (i >= 0) {
                (messages[i] as? ChatItem.Assistant)?.let { messages[i] = it.copy(toolCall = pills.joinToString(" · ")) }
            }
        }
        persist()
        return ToolSettle(visible, docText, docName, observations, ran)
    }

    /** T2 confirm: runs the parked call and appends its honest result. */
    fun confirmPendingTool(id: String, pt: PendingTool) {
        coroutineScope.launch {
            val spec = ToolSpecs.byName(context, pt.name)
            val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
            if (i < 0 || spec == null) return@launch
            val t0 = System.currentTimeMillis()
            setStreamingText(id, (messages[i] as ChatItem.Assistant).text)
            val args = try {
                org.json.JSONObject(pt.argsJson)
            } catch (_: Exception) {
                org.json.JSONObject()
            }
            val res = try {
                executePhoneTool(context, spec, args)
            } catch (e: Exception) {
                ExecResult(false, "failed (${e.message})")
            }
            val ms = System.currentTimeMillis() - t0
            val cur = messages[i] as ChatItem.Assistant
            val line = (if (res.ok) "✓ " else "⚠ ") + "${spec.name}: ${res.text}"
            messages[i] = cur.copy(
                pendingTool = null,
                toolCall = "${spec.name} · ${ms}ms",
                text = cur.text.trimEnd() + "\n\n" + line
            )
            persist()
        }
    }

    /** T2 dismiss: drops the parked call without running it. */
    fun clearPendingTool(id: String) {
        val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
        if (i >= 0) {
            (messages[i] as? ChatItem.Assistant)?.let { messages[i] = it.copy(pendingTool = null) }
        }
        persist()
        android.widget.Toast.makeText(context, "Tool not run", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Picks one candidate for a parked call (clears the picker, keeps the card). */
    fun selectPendingOption(id: String, option: String) {
        val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
        if (i < 0) return
        val cur = messages[i] as ChatItem.Assistant
        val pt = cur.pendingTool ?: return
        val num = digitsOf(option)
        if (num.length < 3) return
        val args = try {
            org.json.JSONObject(pt.argsJson)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
        args.put("number", num)
        if (args.optString("display_name", "").isBlank()) {
            args.put("display_name", option.substringBefore("—").trim())
        }
        val spec = ToolSpecs.byName(context, pt.name)
        messages[i] = cur.copy(
            pendingTool = pt.copy(
                argsJson = args.toString(),
                summary = if (spec != null) summarizeArgs(spec, args) else option,
                options = emptyList()
            )
        )
        persist()
    }

    /**
     * Lists the next queued prompt (typed while busy) so the drain effect
     * starts its turn. Runs after success AND after interrupt — queued work
     * is independent of how the previous turn ended.
     */
    fun drainQueue() {
        if (queue.isEmpty()) return
        val q = queue.removeAt(0)
        val qid = "user_${System.currentTimeMillis()}"
        messages.add(ChatItem.User(id = qid, text = q.text, attachments = q.attachments, tokens = q.text.length / 4))
        StyleMemory.bumpMessageCount(context)
        pendingDrainId = qid
    }

    /**
     * End-of-generation after Stop: keep partial text, drop bare placeholders.
     * When [stopNow] already finalized the bubble this is the abandoned turn
     * unwinding, so leave the settled UI alone and release the queue.
     */
    suspend fun cancelSettle(id: String) {
        if (streamingId != id) {
            drainQueue()
            return
        }
        if (!streamingContent) {
            messages.removeAll { (it as? ChatItem.Assistant)?.id == id }
        } else {
            stampTook(id)
            val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
            if (i >= 0) {
                (messages[i] as? ChatItem.Assistant)?.let { messages[i] = it.copy(interrupted = true) }
            }
            persist()
        }
        isGenerating = false
        streamingId = null
        streamingContent = false
        genLive = null
        scrollToBottomIfNear()
        drainQueue()
    }

    /**
     * Stop, effective immediately: settle the live bubble and re-enable the
     * composer on the spot instead of waiting for a blocked cloud read or a
     * native decode to notice the flag. The abandoned turn is fenced by
     * streamingId + cancelGeneration, so any late chunk is ignored.
     */
    fun stopNow() {
        cancelGeneration = true
        KarenLlama.cancel()
        webConsentGate?.complete(false)
        webConsentGate = null
        webConsentPreview = null
        val id = streamingId
        if (id != null) {
            val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
            if (i >= 0) {
                val cur = messages[i] as? ChatItem.Assistant
                if (cur != null && streamingContent && hasVisibleContent(cur.text)) {
                    val elapsed = System.currentTimeMillis() - streamStartMs
                    val out = if (cur.usageReal && cur.tokens > 0) cur.tokens else cur.text.length / 4
                    messages[i] = cur.copy(tookMs = elapsed, tokens = out, interrupted = true)
                } else {
                    // Bare "Thinking…"/"Searching…" status is not a reply.
                    messages.removeAt(i)
                }
            }
        }
        isGenerating = false
        streamingId = null
        streamingContent = false
        genLive = null
        persist()
        coroutineScope.launch { scrollToBottomIfNear() }
    }

    // System back button: dismiss open sheets first, stop generation, else Home.
    androidx.activity.compose.BackHandler {
        when {
            showModelSheet -> showModelSheet = false
            showAttachmentSheet -> showAttachmentSheet = false
            webConsentGate != null -> {
                webConsentGate?.complete(false)
                webConsentGate = null
                webConsentPreview = null
            }
            isGenerating -> stopNow()
            else -> onNavigateToHome()
        }
    }

    /** End-of-generation on success: stamp stats, archive, then run queued prompts. */
    suspend fun completeSettle(id: String) {
        // A stopped or superseded turn must never settle the live one.
        if (streamingId != id || cancelGeneration) return
        // Length from the played text so stats match exactly what is shown.
        val len = (messages.firstOrNull { (it as? ChatItem.Assistant)?.id == id } as? ChatItem.Assistant)?.text?.length ?: 0
        stampTook(id, len / 4)
        persist()
        isGenerating = false
        streamingId = null
        streamingContent = false
        genLive = null
        scrollToBottomIfNear()
        // Queued prompts (typed while busy) run now, one following the other.
        drainQueue()
    }

    /** Failed turn: show the error inline in the provisional bubble. */
    suspend fun errorSettle(id: String, text: String) {
        // A stopped turn never gets to overwrite the stopped reply with an error.
        if (streamingId != id || cancelGeneration) return
        streamingContent = true
        setStreamingText(id, text)
        stampTook(id, 0)
        persist()
        isGenerating = false
        streamingId = null
        genLive = null
        scrollToBottomIfNear()
    }

    /** Prompt shaping: explainer guide, liked-style memory, custom instructions, phone tools. */
    fun shapingSuffix(prompt: String): String {
        val custom = UserPrefs.customInstructions(context).trim().take(500)
        val memoryOn = UserPrefs.memoryEnabled(context)
        val parts = listOf(
            if (wantsExplainer(prompt, context)) EXPLAINER_STYLE_GUIDE else "",
            if (wantsPlan(prompt)) planGuide() else "",
            if (memoryOn) StyleMemory.currentHint(context) else "",
            if (memoryOn) MemoryFacts.promptBlock(context) else "",
            ToolSpecs.promptBlock(context, prompt),
            if (custom.isNotBlank()) "User instructions: $custom" else ""
        ).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "" else " " + parts.joinToString("\n\n")
    }

    /**
     * Runs the open steps of a plan with real phone tools. Each step asks the
     * model for one action, executes it, feeds the result back, and advances
     * the checklist. The bubble is rewritten through setStreamingText so the
     * PlanCard moves while the work happens — not after a fake delay.
     */
    suspend fun runAgentLoop(
        aid: String,
        userText: String,
        visible: String,
        steps: List<PlanStep>,
        seed: List<AgentObservation>,
        provider: CloudProvider,
        key: String
    ): String {
        var last = visible
        val outcome = AgentRunner.executePlan(
            ctx = context,
            goal = userText,
            visible = visible,
            stepsIn = steps,
            seed = seed,
            isCancelled = { cancelGeneration },
            callModel = { prompt ->
                try {
                    provider.withModelFallback(
                        context,
                        UserPrefs.apiModel(context, provider.id)
                    ) { useModel ->
                        provider.completeResult(
                            key,
                            listOf("user" to prompt),
                            maxTokens = 700,
                            thinkingBudget = null,
                            historyLimit = 4,
                            model = useModel,
                            effort = "Low"
                        )
                    }.text
                } catch (_: Exception) {
                    null
                }
            },
            onProgress = { text, _ ->
                last = text
                setStreamingText(aid, text)
                coroutineScope.launch { scrollToBottomIfNear() }
            }
        )
        return outcome.visible.ifBlank { last }
    }

    /** Top-scoring keyed cloud outside [exclude], with its key. */
    fun pickCloud(prompt: String, exclude: Set<String> = emptySet()): Pair<CloudProvider, String>? {
        if (!device.networkUp) return null
        val keyed = allCloudProviders(context).filter { it.id !in exclude && UserPrefs.apiKey(context, it.id).isNotBlank() }
        if (keyed.isEmpty()) return null
        val intent = detectIntent(prompt.ifBlank { "chat" })
        val best = keyed.maxByOrNull { scoreCloud(it.id, intent) } ?: return null
        return best to UserPrefs.apiKey(context, best.id)
    }

    /** Best keyed cloud for slow-local fallback, or null when offline/unkeyed. */
    fun pickFallbackCloud(prompt: String): CloudProvider? = pickCloud(prompt)?.first

    /** Assigned below: restarts the turn on-device after total API failure. */
    var fallbackToLocal: (String, List<Attachment>, String?) -> Unit = { _, _, _ -> }

    /**
     * One full cloud turn for an already-listed provisional bubble [aid].
     * Shared by direct cloud routes and slow-local fallback so the flow
     * never breaks mid-reply. A failing model reroutes to the next keyed
     * cloud and runs again (each tried once); the queue drains on success.
     */
    suspend fun cloudTurn(
        cloud: CloudProvider,
        cloudKey: String,
        aid: String,
        userText: String,
        web: String?,
        regenerateHint: String?,
        sentAttachments: List<Attachment> = emptyList(),
        allowLocalFallback: Boolean = true
    ) {
        var provider = cloud
        var key = cloudKey
        val tried = mutableSetOf<String>()
        var lastError = ""
        while (true) {
            tried.add(provider.id)
            setStreamingText(aid, "Contacting ${provider.name}…")
            // Stamp the answering model (correct on fallback and reroutes).
            val mi = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == aid }
            if (mi >= 0) {
                (messages[mi] as? ChatItem.Assistant)?.let { messages[mi] = it.copy(model = provider.name) }
            }
            try {
            // Cloud history uses the provider window, not the local setting.
            val limit = cloudHistoryTurns()
            val base = messages.mapNotNull { item ->
                when (item) {
                    is ChatItem.User -> "user" to (item.text + attachedContext(item))
                    is ChatItem.Assistant -> if (item.id == aid) null else "assistant" to item.text
                }
            }.takeLast(limit)
            val history = if (web != null) {
                (base + ("user" to "Use these fresh web results where they help. ${WebSearch.GROUNDING_GUIDE}\n$web")).takeLast(limit)
            } else base
            val instructed = if (regenerateHint != null) {
                (history + ("user" to regenerateHint)).takeLast(limit)
            } else history
            // Prompt shaping (explainer/style memory) without touching history.
            val shaping = shapingSuffix(userText)
            val styled = if (shaping.isNotBlank() && instructed.isNotEmpty() && instructed.last().first == "user") {
                instructed.dropLast(1) + ("user" to instructed.last().second + "\n\n" + shaping.trim())
            } else instructed
            // Recursively continue cut-off replies: when the model hits
            // its output cap it stops mid-answer, so keep asking for
            // the rest instead of making the user type "continue".
            // SSE deltas land in the bubble live, so the answer is visible
            // while it generates instead of after the whole wait.
            val fullReply = StringBuilder()
            // Provider token usage for this turn (0 = not reported).
            var turnIn = 0
            var turnOut = 0
            var turnReason = 0
            var turnReal = false
            val live = StringBuilder()
            var lastUiMs = 0L
            fun liveView(): String {
                val s = live.toString()
                val toolAt = s.indexOf("///TOOL")
                return (if (toolAt >= 0) s.substring(0, toolAt) else s).trimEnd()
            }
            var roundHistory = styled
            var rounds = 0
            var cut = true
            // Brute-force continuation: keep asking for the remainder until
            // the model stops by itself or the round cap is spent.
            while (cut && rounds < MAX_CONTINUE_ROUNDS && !cancelGeneration) {
                if (rounds > 0) {
                    setStreamingText(aid, (liveView() + "\n\nContinuing… (part ${rounds + 1})").trim())
                }
                val res = try {
                    provider.withModelFallback(context, UserPrefs.apiModel(context, provider.id)) { useModel ->
                        provider.completeStreamingResult(
                            key,
                            roundHistory,
                            maxTokensFor(effort),
                            thinkingBudget = if (provider.reasoning) thinkingBudgetFor(effort) else null,
                            historyLimit = limit,
                            model = useModel,
                            onToken = { piece ->
                                live.append(piece)
                                val now = System.currentTimeMillis()
                                if (now - lastUiMs > 60) {
                                    lastUiMs = now
                                    val snap = liveView()
                                    coroutineScope.launch {
                                        streamingContent = true
                                        setStreamingText(aid, snap)
                                        scrollToBottomIfNear()
                                    }
                                }
                            },
                            isCancelled = { cancelGeneration },
                            effort = effort
                        )
                    }
                } catch (e: Exception) {
                    if (cancelGeneration) break
                    // Endpoints that reject SSE: retry this round in one shot.
                    provider.withModelFallback(context, UserPrefs.apiModel(context, provider.id)) { useModel ->
                        provider.completeResult(
                            key,
                            roundHistory,
                            maxTokensFor(effort),
                            thinkingBudget = if (provider.reasoning) thinkingBudgetFor(effort) else null,
                            historyLimit = limit,
                            model = useModel,
                            effort = effort
                        )
                    }
                }
                if (cancelGeneration) break
                res.usage?.let { u ->
                    if (u.any) {
                        turnReal = true
                        turnIn += u.input
                        turnOut += u.output
                        turnReason += u.reasoning
                    }
                }
                val beforeJoin = fullReply.length
                fullReply.append(
                    if (rounds == 0) res.text else stitchContinuation(fullReply.toString(), res.text)
                )
                val added = fullReply.length - beforeJoin
                // A round that only repeated itself makes no progress: stop
                // instead of burning the rest of the budget on duplicates.
                val capUsed = maxTokensFor(effort)
                val outTokens = res.usage?.output ?: 0
                cut = added > 0 && res.text.isNotBlank() &&
                    (res.truncated || looksCutOff(res.text, capUsed, hitCap = outTokens >= capUsed - 1))
                rounds++
                if (cut && rounds < MAX_CONTINUE_ROUNDS) {
                    roundHistory = (roundHistory + ("assistant" to res.text.takeLast(CONTINUE_CONTEXT_CHARS)) + ("user" to CONTINUE_PROMPT)).takeLast(limit)
                }
            }
            val cutNote = if (cut) continuationLimitNote(rounds) else ""
            val reply = cleanReply(fullReply.toString()) + cutNote
            if (cancelGeneration) {
                cancelSettle(aid)
                return
            }
            // Token usage for the Usage-stats dialog: provider-reported when
            // available, otherwise estimates from prompt/reply text length.
            run {
                val ui = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == aid }
                if (ui >= 0) (messages[ui] as? ChatItem.Assistant)?.let {
                    messages[ui] = it.copy(
                        inTokens = if (turnIn > 0) turnIn else styled.sumOf { m -> m.second.length } / 4,
                        reasonTokens = if (turnReason > 0) turnReason else (splitThinkBlock(reply).first?.length ?: 0) / 4,
                        usageReal = turnReal,
                        tokens = if (turnReal && turnOut > 0) turnOut else it.tokens
                    )
                }
            }
            // Realtime tools: validate + run ///TOOL calls before showing.
            var settled = settleTools(aid, reply)
            if (cancelGeneration) {
                cancelSettle(aid)
                return
            }
            var withTools = settled.visible
            // A model can echo the plan schema sample ("real step 1") instead
            // of planning. That leaves an empty body and no checklist; ask
            // once for the actual plan + first action instead of showing it.
            if (!cancelGeneration && settled.ran == 0 &&
                withTools.contains("///PLAN") && splitPlan(withTools).steps.isEmpty()
            ) {
                setStreamingText(aid, "Planning the real steps…")
                try {
                    val repair = provider.withModelFallback(
                        context,
                        UserPrefs.apiModel(context, provider.id)
                    ) { useModel ->
                        provider.completeResult(
                            key,
                            (styled + ("user" to
                                "Your previous reply only repeated the plan sample, so it was discarded. " +
                                "Redo the request for real: start with one ///PLAN line using the actual " +
                                "steps, then emit the ///TOOL call for the first step and stop. " +
                                "Never reuse the sample text.")).takeLast(limit),
                            maxTokens = 800,
                            thinkingBudget = null,
                            historyLimit = limit,
                            model = useModel,
                            effort = effort
                        )
                    }.text
                    if (repair.isNotBlank() && !cancelGeneration) {
                        settled = settleTools(aid, repair)
                        withTools = settled.visible
                    }
                } catch (_: Exception) {
                }
            }
            // Document reads: answer from the extracted text in the same turn.
            if (settled.docText != null && !cancelGeneration) {
                setStreamingText(aid, (withTools.trimEnd() + "\n\nReading ${settled.docName}…").trim())
                try {
                    val followHistory = (styled + ("user" to
                        "Use this document text to answer the request. ${WebSearch.GROUNDING_GUIDE} Do not emit ///TOOL lines:\n${settled.docText}")
                        ).takeLast(limit)
                    val ans = provider.withModelFallback(context, UserPrefs.apiModel(context, provider.id)) { useModel ->
                        provider.completeResult(
                            key,
                            followHistory,
                            maxTokensFor(effort),
                            thinkingBudget = if (provider.reasoning) thinkingBudgetFor(effort) else null,
                            historyLimit = limit,
                            model = useModel,
                            effort = effort
                        )
                    }.text.trim()
                    if (ans.isNotBlank() && !cancelGeneration) {
                        val clean = stripToolCalls(ans, extractToolCalls(ans)).trim()
                        if (clean.isNotBlank()) {
                            withTools = (withTools.trimEnd() + "\n\n" + clean + "\n(Source: ${settled.docName})").trim()
                        }
                    }
                } catch (_: Exception) {
                }
            }
            // Plan execution: finish the checklist with real device tools.
            // A plan is a promise to act (the guide says "plan before acting"),
            // so open steps run unless the user asked for a draft only.
            val planNow = splitPlan(withTools)
            if (!cancelGeneration && AgentRunner.hasOpenSteps(planNow.steps) &&
                !AgentRunner.planOnly(userText)
            ) {
                withTools = runAgentLoop(
                    aid = aid,
                    userText = userText,
                    visible = withTools,
                    steps = planNow.steps,
                    seed = settled.observations,
                    provider = provider,
                    key = key
                )
            }
            if (hasVisibleContent(withTools)) {
                // Already streamed live: sync the final text (tool lines
                // stripped, notes appended) instead of replaying it with
                // the typing effect.
                setStreamingText(aid, withTools)
                scrollToBottomIfNear()
                if (cancelGeneration) cancelSettle(aid) else completeSettle(aid)
                return
            }
            lastError = "The model returned an empty reply"
            } catch (e: CloudApiException) {
                if (cancelGeneration) {
                    cancelSettle(aid)
                    return
                }
                lastError = "${provider.name} error (HTTP ${e.status}): ${e.message}"
            } catch (e: Exception) {
                if (cancelGeneration) {
                    cancelSettle(aid)
                    return
                }
                lastError = "Could not reach ${provider.name} — check internet and your API key. (${e.message})"
            }
            // Reroute: next keyed cloud takes over the same turn (when set).
            if (!UserPrefs.apiFailover(context)) {
                errorSettle(aid, "⚠ $lastError")
                return
            }
            val next = pickCloud(userText, tried)
            if (next == null) {
                if (allowLocalFallback && UserPrefs.cloudLocalFallback(context)) {
                    messages.removeAll { (it as? ChatItem.Assistant)?.id == aid }
                    isGenerating = false
                    streamingId = null
                    android.widget.Toast.makeText(context, "APIs failed — continuing on-device", android.widget.Toast.LENGTH_SHORT).show()
                    fallbackToLocal(userText, sentAttachments, regenerateHint)
                    return
                }
                errorSettle(aid, "⚠ $lastError")
                return
            }
            provider = next.first
            key = next.second
        }
    }

    /**
     * Runs one full assistant turn for an already-listed user message.
     * Shared by fresh sends, edited resends, and regenerates. When
     * [regenerateHint] is set it is whispered to the model only (never shown
     * in chat) so the retry thinks and answers quite differently.
     */
    /**
     * Runs one chat turn on a joined GPU node (the "Run on" chip above the
     * composer). Streams status + deltas into the provisional bubble. Returns
     * true when the turn settled here (done, cancelled, or failed on the
     * node); false when no node could take it — the caller then runs the turn
     * on this phone instead.
     */
    suspend fun nodeTurn(
        aid: String,
        sel: String,
        label: String,
        system: String,
        pairs: List<Pair<String, String>>,
        pairLimit: Int,
        maxTok: Int,
        modelOverride: String
    ): Boolean {
        setStreamingText(aid, "Assigning to $label…")
        val buf = StringBuilder()

        suspend fun runNode(roundPairs: List<Pair<String, String>>): LocalApiServer.WorkerRun =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LocalApiServer.runOnWorker(
                    sel = sel,
                    system = system,
                    pairs = roundPairs,
                    temperature = 0.7,
                    maxTokens = maxTok,
                    model = modelOverride,
                    onStatus = { s ->
                        if (buf.isEmpty()) coroutineScope.launch { setStreamingText(aid, s) }
                    },
                    onDelta = { d ->
                        buf.append(d)
                        val snap = buf.toString()
                        coroutineScope.launch {
                            streamingContent = true
                            setStreamingText(aid, snap)
                            scrollToBottomIfNear()
                        }
                    },
                    cancelled = { cancelGeneration }
                )
            }

        val res = runNode(pairs)
        if (res.noWorker) return false
        if (res.error == "cancelled" || cancelGeneration) {
            cancelSettle(aid)
            return true
        }
        var raw = res.text.trim()
        if (!res.ok || raw.isBlank()) {
            val why = res.error.ifBlank { "the node returned an empty reply" }
            if (raw.isNotBlank()) {
                // Partial output before the failure still lands in the bubble.
                errorSettle(aid, "$raw\n\n⚠ $why ($label)")
            } else {
                errorSettle(aid, "$why (node route: $label)")
            }
            return true
        }
        // Brute-force continuation: the node has no finish_reason, so an
        // unclosed code block or a near-cap broken tail re-asks for the rest.
        var nodeRounds = 1
        while (nodeRounds < MAX_CONTINUE_ROUNDS && !cancelGeneration && looksCutOff(raw, maxTok)) {
            setStreamingText(
                aid,
                (raw.trimEnd() + "\n\nContinuing… (part ${nodeRounds + 1})").trim()
            )
            buf.setLength(0)
            buf.append(raw.trimEnd()).append("\n\n")
            val contPairs = (pairs +
                ("assistant" to raw.takeLast(CONTINUE_CONTEXT_CHARS)) +
                ("user" to CONTINUE_PROMPT)).takeLast(pairLimit)
            val more = runNode(contPairs)
            val joined = if (more.text.isBlank()) raw else stitchContinuation(raw, more.text.trim())
            val grew = joined.length > raw.length
            raw = joined
            if (!more.ok || more.text.isBlank() || !grew) break
            nodeRounds++
        }
        if (looksCutOff(raw, maxTok) && nodeRounds >= MAX_CONTINUE_ROUNDS) {
            raw += continuationLimitNote(nodeRounds)
        }
        val settled = settleTools(aid, cleanReply(raw))
        var withTools = settled.visible
        if (settled.docText != null && !cancelGeneration) {
            setStreamingText(aid, (withTools.trimEnd() + "\n\nReading ${settled.docName} on the node…").trim())
            val docPair = "user" to
                "Use this document text to answer the request. ${WebSearch.GROUNDING_GUIDE} Do not emit ///TOOL lines:\n${settled.docText}"
            val second = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                LocalApiServer.runOnWorker(
                    sel = sel,
                    system = system,
                    pairs = (pairs + docPair).takeLast(pairLimit),
                    temperature = 0.7,
                    maxTokens = maxTok,
                    model = modelOverride,
                    cancelled = { cancelGeneration }
                )
            }
            val ans = second.text.trim()
            if (ans.isNotBlank() && !cancelGeneration) {
                val clean = stripToolCalls(ans, extractToolCalls(ans)).trim()
                if (clean.isNotBlank()) {
                    withTools = (withTools.trimEnd() + "\n\n" + clean + "\n(Source: ${settled.docName})").trim()
                }
            }
        }
        if (hasVisibleContent(withTools)) {
            setStreamingText(aid, withTools)
            scrollToBottomIfNear()
            if (cancelGeneration) cancelSettle(aid) else completeSettle(aid)
        } else {
            errorSettle(aid, "The node returned an empty reply.")
        }
        return true
    }

    fun runAssistantTurn(
        userText: String,
        sentAttachments: List<Attachment>,
        regenerateHint: String? = null,
        /** All APIs failed with on-device fallback on: skip clouds entirely. */
        forceLocal: Boolean = false
    ) {
        if (isGenerating) return
        fallbackToLocal = { u, a, r -> runAssistantTurn(u, a, r, forceLocal = true) }
        // Live cloud model when one is selected (key stored in Model Manager),
        // real on-device inference for downloaded weights, else the mock.
        // Auto routing swaps in the best model for this prompt.
        val routed = effectiveModel(userText.ifBlank { "chat" })
        val runnableLocals = UserPrefs.models(context).filter {
            !isCloudProviderName(it) && !isEmbeddingModel(it) && ModelDownloader.isRunnableWeight(context, it)
        }
        val sendModel = if (forceLocal) {
            val preferred = UserPrefs.preferredLocalModel(context).takeIf {
                it in runnableLocals
            }
            preferred ?: runnableLocals.firstOrNull() ?: routed
        } else routed
        val cloud = if (forceLocal) null else findCloudProviderByName(sendModel, UserPrefs.customProviders(context))
        val cloudKey = cloud?.let { UserPrefs.apiKey(context, it.id) }.orEmpty()
        val weightFile = if (cloud == null) ModelDownloader.weightFileFor(context, sendModel) else null

        coroutineScope.launch {
            cancelGeneration = false
            // "@file" vault mentions: fetch content so this turn answers from it.
            val userText = resolveMentions(context, userText)
            streamStartMs = System.currentTimeMillis()
            streamingContent = false
            genLive = null
            // Node route (the "Run on" chip): resolve the pick up front so the
            // bubble footer names the node honestly. Offline/paused ⇒ this phone.
            val nodeRoute = workerRoute
            val nodePick = if (nodeRoute.isNotBlank()) {
                val picks = LocalApiServer.workerPicks().filter { it.online && it.state != "paused" }
                if (nodeRoute == "auto") picks.maxByOrNull { it.gflops } else picks.firstOrNull { it.id == nodeRoute }
            } else null
            val nodeLabel = when {
                nodeRoute.isBlank() -> ""
                nodeRoute == "auto" -> "node auto" + (nodePick?.let { " · " + it.name } ?: "")
                else -> "node " + (nodePick?.name ?: nodeRoute)
            }
            val aid = "asst_${System.currentTimeMillis()}"
            messages.add(
                ChatItem.Assistant(
                    id = aid,
                    text = "",
                    model = if (nodePick != null) nodeLabel else sendModel
                )
            )
            streamingId = aid
            isGenerating = true
            scrollToBottomIfNear()
            // Web search tool: runs when the message needs fresh info,
            // gated by the one-time permission dialog on first use.
            var web: String? = null
            val wantSearch = userText.isNotBlank() && WebSearch.needsSearch(userText) && UserPrefs.webSearchEnabled(context)
            if (wantSearch) {
                var allowed = true
                if (!UserPrefs.webSearchAsked(context)) {
                    val gate = CompletableDeferred<Boolean>()
                    webConsentPreview = userText
                    webConsentGate = gate
                    allowed = try { gate.await() } catch (_: Exception) { false }
                    webConsentPreview = null
                    webConsentGate = null
                    if (cancelGeneration) {
                        cancelSettle(aid)
                        return@launch
                    }
                }
                if (allowed) {
                    setStreamingText(aid, "Searching the web…")
                    web = try {
                        WebSearch.searchWithKey(context, WebSearch.cleanQuery(userText))
                            .takeIf { it.isNotEmpty() }
                            ?.let(WebSearch::formatForModel)
                    } catch (_: Exception) { null }
                    if (cancelGeneration) {
                        cancelSettle(aid)
                        return@launch
                    }
                }
            }
            // History as model pairs — shared by the on-device and node routes.
            val pairLimit = localTurnsFor(UserPrefs.contextTokens(context))
            val basePairs = messages.mapNotNull { item ->
                when (item) {
                    is ChatItem.User -> "user" to (item.text + attachedContext(item))
                    is ChatItem.Assistant -> if (item.id == aid) null else "assistant" to item.text
                }
            }
            val trimmedPairs = if (web != null) {
                (basePairs + ("user" to "Use these fresh web results where they help. ${WebSearch.GROUNDING_GUIDE}\n$web")).takeLast(pairLimit)
            } else basePairs.takeLast(pairLimit)
            val instructedPairs = if (regenerateHint != null) {
                (trimmedPairs + ("user" to regenerateHint)).takeLast(pairLimit)
            } else trimmedPairs

            // Node route: the whole turn runs on the joined GPU node.
            if (nodePick != null) {
                val nodeSystem = "You are Karen, a concise on-device assistant." + shapingSuffix(userText)
                val handled = nodeTurn(
                    aid = aid,
                    sel = nodeRoute,
                    label = nodeLabel,
                    system = nodeSystem,
                    pairs = instructedPairs,
                    pairLimit = pairLimit,
                    maxTok = maxTokensFor(effort),
                    // Per-turn pick from the model chip ("" = node's assignment).
                    // Auto always keeps the node's assignment: the fastest node
                    // at send time may be another device with other models.
                    modelOverride = if (nodeRoute == "auto") "" else UserPrefs.nodeModel(context, nodeRoute)
                )
                if (handled) return@launch
                // The node vanished between the chip and the send: run here.
                setStreamingModel(aid, sendModel)
                android.widget.Toast.makeText(
                    context,
                    "Node unavailable — ran on this phone",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }

            if (cloud != null && cloudKey.isNotBlank()) {
                cloudTurn(cloud, cloudKey, aid, userText, web, regenerateHint, sentAttachments)
            } else if (weightFile != null) {
                // Real on-device inference through the bundled llama.cpp core.
                if (!KarenLlama.ready) {
                    errorSettle(aid, "Local runtime failed to load on this device.")
                } else if (!ModelDownloader.isRunnableWeight(context, sendModel)) {
                    errorSettle(aid, "$sendModel is ${ModelDownloader.formatOf(sendModel)} — on-device runs GGUF weights only. Convert it to GGUF or pick a cloud model.")
                } else if (isEmbeddingModel(sendModel)) {
                    errorSettle(aid, "$sendModel is an embedding weight for vault search — pick a chat model to talk.")
                } else if (UserPrefs.thermalGuard(context) && device.batteryTempC >= UserPrefs.thermalLimitC(context)) {
                    errorSettle(
                        aid,
                        "Paused by Thermal Guard — battery ${"%.0f".format(device.batteryTempC)}°C " +
                            "is at/above your ${UserPrefs.thermalLimitC(context).toInt()}°C limit. Let the phone cool down."
                    )
                } else {
                    setStreamingText(aid, if (KarenLlama.isLoaded(sendModel)) "Thinking on-device…" else "Loading $sendModel…")
                    try {
                        val threads = maxOf(2, minOf(6, Runtime.getRuntime().availableProcessors()))
                        val window = UserPrefs.contextTokens(context)
                        val shaping = shapingSuffix(userText)
                        // Slow-local guard: blocking native calls run as orphans
                        // on the pool — the timeout abandons the await (a plain
                        // withContext would join the stuck thread instead).
                        // Abort flag frees the orphaned thread right after.
                        val deadline = System.currentTimeMillis() + LOCAL_TURN_TIMEOUT_MS
                        fun remainingMs(): Long = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
                        data class LocalOut(val loaded: Boolean, val reply: String)
                        suspend fun awaitAbandoned(block: () -> LocalOut): LocalOut? {
                            // Child of the turn scope: timeout abandons the await
                            // while the pooled thread winds down via the abort flag.
                            val job = async(localInferenceDispatcher) { block() }
                            return try {
                                withTimeoutOrNull(remainingMs()) { job.await() }
                            } finally {
                                if (!job.isCompleted) KarenLlama.cancel()
                            }
                        }
                        val streamBuf = StringBuilder()
                        var lastPostMs = 0L
                        val localMax = maxTokensFor(effort)
                        val localSystem = "You are Karen, a concise on-device assistant." +
                            (if (isReasoningModel(sendModel)) reasoningEffortHint(effort) else "") +
                            shaping
                        var localHistory = instructedPairs
                        var localTokens = 0
                        var localRounds = 0
                        var localCut = true
                        var localStillCut = false
                        var localFail: String? = null
                        var localTimedOut = false
                        // Brute-force continuation: on-device replies stopped at
                        // the token cap are re-asked for the remainder.
                        while (localCut && localRounds < MAX_CONTINUE_ROUNDS && !cancelGeneration) {
                            if (localRounds > 0) {
                                setStreamingText(
                                    aid,
                                    (streamBuf.toString().trimEnd() + "\n\nContinuing… (part ${localRounds + 1})").trim()
                                )
                            }
                            val tokensBefore = localTokens
                            var budget = localMax
                            var round: LocalOut? = null
                            var trimmed = localHistory
                            while (round == null && !cancelGeneration) {
                                val attempt = try {
                                    awaitAbandoned {
                                        val l = KarenLlama.ensureLoaded(weightFile, sendModel, window, threads)
                                        var r = ""
                                        if (l) {
                                            // One piece per predicted token: the
                                            // count is the exact cap signal.
                                            r = KarenLlama.completeStream(
                                                localSystem,
                                                trimmed.map { it.first }.toTypedArray(),
                                                trimmed.map { it.second }.toTypedArray(),
                                                budget
                                            ) { piece ->
                                                localTokens++
                                                streamBuf.append(piece)
                                                val now = System.currentTimeMillis()
                                                if (now - lastPostMs > 60) {
                                                    lastPostMs = now
                                                    val snap = streamBuf.toString()
                                                    coroutineScope.launch {
                                                        streamingContent = true
                                                        setStreamingText(aid, snap)
                                                        scrollToBottomIfNear()
                                                    }
                                                }
                                            }
                                        }
                                        LocalOut(l, r)
                                    }
                                } catch (e: Exception) {
                                    // Brute-force fit: when the prompt overflows the
                                    // context, drop half the history (then shrink the
                                    // reply budget) and retry instead of failing.
                                    if (e.message?.contains("prompt too long", true) == true) {
                                        if (trimmed.size > 1) {
                                            trimmed = trimmed.drop(maxOf(1, trimmed.size / 2))
                                            continue
                                        }
                                        val est = (localSystem.length + trimmed.sumOf { it.second.length }) / 4 + 16
                                        val fit = (window - est).coerceAtLeast(256)
                                        if (fit < budget) {
                                            budget = fit
                                            continue
                                        }
                                    }
                                    localFail = e.message ?: "local model error"
                                    null
                                }
                                if (attempt == null) {
                                    if (localFail == null) localTimedOut = true
                                    break
                                }
                                round = attempt
                            }
                            val r = round ?: break
                            if (!r.loaded || cancelGeneration) {
                                if (!r.loaded && streamBuf.isBlank()) localFail = "load"
                                break
                            }
                            val made = localTokens - tokensBefore
                            val capUsed = budget
                            val cutHere = r.reply.isNotBlank() &&
                                looksCutOff(r.reply, capUsed, hitCap = made >= capUsed - 1)
                            localRounds++
                            localStillCut = cutHere
                            localCut = cutHere && localRounds < MAX_CONTINUE_ROUNDS
                            if (cutHere) {
                                localHistory = (trimmed +
                                    ("assistant" to r.reply.takeLast(CONTINUE_CONTEXT_CHARS)) +
                                    ("user" to CONTINUE_PROMPT)).takeLast(pairLimit)
                            }
                        }
                        val produced = streamBuf.toString().trim()
                        if (localTimedOut && produced.isBlank()) {
                            // Local took too long (abandoned above): switch to
                            // API without breaking flow.
                            if (cancelGeneration) {
                                cancelSettle(aid)
                            } else {
                                val fb = pickFallbackCloud(userText)
                                if (fb == null || forceLocal) {
                                    errorSettle(aid, "$sendModel is taking too long and no keyed cloud model is reachable — check internet and Model Manager keys.")
                                } else {
                                    android.widget.Toast.makeText(context, "Local slow — continuing on ${fb.name}", android.widget.Toast.LENGTH_SHORT).show()
                                    cloudTurn(fb, UserPrefs.apiKey(context, fb.id), aid, userText, web, regenerateHint, sentAttachments, allowLocalFallback = false)
                                }
                            }
                        } else if (cancelGeneration) {
                            cancelSettle(aid)
                        } else if (produced.isBlank()) {
                            when {
                                localFail == "load" -> errorSettle(aid, "Could not load $sendModel into RAM.")
                                localFail != null -> errorSettle(aid, "Local model error: $localFail")
                                else -> errorSettle(aid, "The model returned an empty reply.")
                            }
                        } else {
                            var finalReply = cleanReply(produced)
                            if (localStillCut && localRounds >= MAX_CONTINUE_ROUNDS) {
                                finalReply += continuationLimitNote(localRounds)
                            } else if (localFail != null) {
                                finalReply += "\n\n⚠ $localFail"
                            }
                            val settled = settleTools(aid, finalReply)
                            var withTools = settled.visible
                            if (settled.docText != null && !cancelGeneration) {
                                setStreamingText(aid, (withTools.trimEnd() + "\n\nReading ${settled.docName}…").trim())
                                val docPairs = (instructedPairs + ("user" to
                                    "Use this document text to answer the request. ${WebSearch.GROUNDING_GUIDE} Do not emit ///TOOL lines:\n${settled.docText}")
                                    ).takeLast(pairLimit)
                                val ans = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    try {
                                        KarenLlama.complete(
                                            "You are Karen, a concise on-device assistant.",
                                            docPairs.map { it.first }.toTypedArray(),
                                            docPairs.map { it.second }.toTypedArray(),
                                            maxTokensFor(effort)
                                        )
                                    } catch (_: Exception) {
                                        ""
                                    }
                                }?.trim().orEmpty()
                                if (ans.isNotBlank() && !cancelGeneration) {
                                    val clean = stripToolCalls(ans, extractToolCalls(ans)).trim()
                                    if (clean.isNotBlank()) {
                                        withTools = (withTools.trimEnd() + "\n\n" + clean + "\n(Source: ${settled.docName})").trim()
                                    }
                                }
                            }
                            if (hasVisibleContent(withTools)) {
                                // Already streamed live above: sync the final
                                // text (cleaned + tool notes) instead of
                                // replaying it with the typing effect.
                                setStreamingText(aid, withTools)
                                scrollToBottomIfNear()
                                if (cancelGeneration) cancelSettle(aid) else completeSettle(aid)
                            } else {
                                errorSettle(aid, "The model returned an empty reply.")
                            }
                        }
                    } catch (e: Exception) {
                        errorSettle(aid, "Local model error: ${e.message}")
                    }
                }
            } else {
                // No provider available — explain how to connect one.
                val fileNote = if (sentAttachments.isNotEmpty()) {
                    val names = sentAttachments.joinToString(", ") { it.name }
                    " I received ${sentAttachments.size} file(s): $names. Files are stored locally in the vault — 0 bytes sent externally."
                } else ""
                val regenNote = if (regenerateHint != null) "\n\n(Regenerated with a fresh take.)" else ""
                val fullText = if (web != null) {
                    "Fresh web results:\n$web"
                } else {
                    "No model is connected yet — pick a cloud provider in the model sheet, or download a local GGUF in Model Manager → Catalog.$fileNote$regenNote"
                }
                kotlinx.coroutines.delay(400)
                if (cancelGeneration) {
                    cancelSettle(aid)
                } else {
                    playOut(aid, fullText)
                    if (cancelGeneration) cancelSettle(aid) else completeSettle(aid)
                }
            }
        }
    }

    /**
     * Slash fast path: deterministic tool routing with no model round-trip.
     * Unambiguous calls (zero args, one text arg, parseable timer/alarm)
     * run straight through validation + policy; anything needing judgment
     * returns false so the AI route (and API if needed) handles it.
     */
    fun tryDirectToolTurn(rawText: String): Boolean {
        // Built-in help: answered locally, no model or network involved.
        if (rawText.trim().equals("/help", ignoreCase = true)) {
            val newId = System.currentTimeMillis().toString()
            messages.add(ChatItem.User(id = "user_$newId", text = rawText, tokens = rawText.length / 4))
            StyleMemory.bumpMessageCount(context)
            val aid = "asst_$newId"
            streamStartMs = System.currentTimeMillis()
            messages.add(ChatItem.Assistant(id = aid, text = HELP_TEXT, model = "tools", help = true))
            stampTook(aid)
            persist()
            // The card is a full reference now: anchor its top so /help opens
            // at "How Karen works" instead of the last tool in the catalog.
            // One frame for the new item to enter the lazy layout first.
            val helpIndex = messages.lastIndex
            coroutineScope.launch {
                try {
                    kotlinx.coroutines.delay(80)
                    listState.animateScrollToItem(helpIndex.coerceAtLeast(0))
                } catch (_: Exception) {
                    scrollToBottomIfNear()
                }
            }
            return true
        }
        val (spec, rest) = matchSlash(context, rawText) ?: return false
        // place_call with a name skips the model too: match contacts now,
        // then confirm (or direct-run per Settings) — no API needed.
        if (spec.name == "place_call" && rest.any { it.isLetter() }) {
            val newId = System.currentTimeMillis().toString()
            messages.add(ChatItem.User(id = "user_$newId", text = rawText, tokens = rawText.length / 4))
            StyleMemory.bumpMessageCount(context)
            val aid = "asst_$newId"
            streamStartMs = System.currentTimeMillis()
            messages.add(ChatItem.Assistant(id = aid, text = "Looking up “$rest”…", model = "tools"))
            isGenerating = true
            coroutineScope.launch {
                val hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    queryContacts(context, rest, 5)
                }
                val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == aid }
                when {
                    hits.isEmpty() -> {
                        if (i >= 0) messages[i] = (messages[i] as ChatItem.Assistant).copy(
                            text = "⚠ place_call: no contacts match “$rest” — not run"
                        )
                    }
                    hits.size == 1 -> {
                        val args = org.json.JSONObject()
                            .put("number", digitsOf(hits[0].second))
                            .put("display_name", hits[0].first)
                        if (runsDirect(spec, context)) {
                            val res = kotlinx.coroutines.withTimeoutOrNull(150_000L) {
                                try {
                                    executePhoneTool(context, spec, args)
                                } catch (e: Exception) {
                                    ExecResult(false, "failed (${e.message})")
                                }
                            } ?: ExecResult(false, "timed out after 150s — check connection and retry")
                            if (cancelGeneration) {
                                isGenerating = false
                                stampTook(aid)
                                persist()
                                return@launch
                            }
                            if (i >= 0) messages[i] = (messages[i] as ChatItem.Assistant).copy(
                                text = (if (res.ok) "✓ " else "⚠ ") + "place_call: ${res.text}",
                                toolCall = "place_call"
                            )
                        } else if (i >= 0) {
                            val summary = summarizeArgs(spec, args)
                            messages[i] = (messages[i] as ChatItem.Assistant).copy(
                                text = "place_call: $summary",
                                pendingTool = PendingTool(spec.name, args.toString(), summary)
                            )
                        }
                    }
                    else -> {
                        val options = hits.map { "${it.first} — ${it.second}" }
                        val args = org.json.JSONObject().put("number", rest)
                        if (i >= 0) messages[i] = (messages[i] as ChatItem.Assistant).copy(
                            text = "place_call: ${options.size} similar contacts — pick one below",
                            pendingTool = PendingTool(
                                spec.name,
                                args.toString(),
                                "matches: ${options.joinToString("; ")}",
                                options
                            )
                        )
                    }
                }
                isGenerating = false
                stampTook(aid)
                persist()
                scrollToBottomIfNear()
            }
            return true
        }
        val dargs = directArgs(spec, rest) ?: return false
        val newId = System.currentTimeMillis().toString()
        messages.add(ChatItem.User(id = "user_$newId", text = rawText, tokens = rawText.length / 4))
        StyleMemory.bumpMessageCount(context)
        val aid = "asst_$newId"
        streamStartMs = System.currentTimeMillis()
        val err = validateToolCall(spec, dargs)
        if (err != null) {
            messages.add(ChatItem.Assistant(id = aid, text = "⚠ ${spec.name}: $err — not run", model = "tools"))
            stampTook(aid)
            persist()
            coroutineScope.launch { scrollToBottomIfNear() }
            return true
        }
        if (spec.tier == "T3") {
            messages.add(ChatItem.Assistant(id = aid, text = "⚠ ${spec.name} is disabled (T3) — not run", model = "tools"))
            stampTook(aid)
            persist()
            coroutineScope.launch { scrollToBottomIfNear() }
            return true
        }
        if (spec.tier == "T2" && !runsDirect(spec, context)) {
            val summary = summarizeArgs(spec, dargs)
            messages.add(
                ChatItem.Assistant(
                    id = aid,
                    text = "${spec.name}: $summary",
                    model = "tools",
                    pendingTool = PendingTool(spec.name, dargs.toString(), summary)
                )
            )
            stampTook(aid)
            persist()
            coroutineScope.launch { scrollToBottomIfNear() }
            return true
        }
        messages.add(ChatItem.Assistant(id = aid, text = "Running ${spec.name}…", model = "tools"))
        isGenerating = true
        coroutineScope.launch {
            val t0 = System.currentTimeMillis()
            // Bounded: a stalled API must fail the turn, never hang it.
            val res = kotlinx.coroutines.withTimeoutOrNull(150_000L) {
                try {
                    executePhoneTool(context, spec, dargs)
                } catch (e: Exception) {
                    ExecResult(false, "failed (${e.message})")
                }
            } ?: ExecResult(false, "timed out after 150s — check connection and retry")
            if (cancelGeneration) {
                isGenerating = false
                stampTook(aid)
                persist()
                return@launch
            }
            val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == aid }
            if (i >= 0) {
                val line = (if (res.ok) "✓ " else "⚠ ") + "${spec.name}: ${res.text}"
                messages[i] = (messages[i] as ChatItem.Assistant).copy(
                    text = line,
                    toolCall = "${spec.name} · ${System.currentTimeMillis() - t0}ms"
                )
            }
            isGenerating = false
            stampTook(aid)
            persist()
            scrollToBottomIfNear()
        }
        return true
    }

    /** Lists a user message and starts the assistant turn for it. */
    fun sendNow(userText: String, sentAttachments: List<Attachment>, displayText: String = userText) {
        val newId = System.currentTimeMillis().toString()
        val body = displayText.ifBlank { sentAttachments.joinToString(", ") { it.name } }
        messages.add(ChatItem.User(id = "user_$newId", text = body, attachments = sentAttachments, tokens = body.length / 4))
        StyleMemory.bumpMessageCount(context)
        // Persist the prompt immediately: a recreation mid-turn must never
        // lose what the user asked.
        persist()
        // Model answers (fresh sends, edited resends, regenerates) run here.
        runAssistantTurn(userText, sentAttachments)
    }

    // Starts the turn for a queued prompt listed by completeSettle.
    LaunchedEffect(pendingDrainId) {
        val id = pendingDrainId ?: return@LaunchedEffect
        pendingDrainId = null
        if (isGenerating) return@LaunchedEffect
        val u = messages.filterIsInstance<ChatItem.User>().find { it.id == id }
            ?: return@LaunchedEffect
        runAssistantTurn(u.text, u.attachments)
    }

    // Open a saved conversation from the history list.
    LaunchedEffect(openConversationId) {
        val id = openConversationId ?: return@LaunchedEffect
        persist()
        val loaded = ChatHistoryStore.loadMessages(context, id)
        messages.clear()
                        queue.clear()
        messages.addAll(loaded)
        conversationId = id
        onConversationOpened()
        // Recently-opened feed for the Home card.
        val firstAsk = loaded.firstOrNull { it is ChatItem.User }
            ?.let { (it as ChatItem.User).text }.orEmpty()
        if (firstAsk.isNotBlank()) {
            RecentActivity.record(context, RecentActivity.TYPE_CHAT, id, firstAsk)
        }
    }

    // Fresh chat requested from the drawer while already on this screen.
    LaunchedEffect(newChatSignal) {
        if (newChatSignal == 0) return@LaunchedEffect
        persist()
        messages.clear()
                        queue.clear()
        conversationId = System.currentTimeMillis().toString()
    }

    // Prompt memory: seed from local chat history once per mount so the
    // composer's completions work before the first new prompt is sent.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { PromptMemory.seedFromHistory(context) }
        promptMemoryRevision++
    }

    // Live elapsed/token readout above the composer while a reply streams in.
    LaunchedEffect(isGenerating) {
        if (!isGenerating) return@LaunchedEffect
        while (true) {
            // Settle (stop/done/error) nulls the readout; quit instead of
            // repainting one stale tick after it.
            if (!isGenerating) {
                genLive = null
                return@LaunchedEffect
            }
            val id = streamingId
            val len = (messages.firstOrNull { (it as? ChatItem.Assistant)?.id == id } as? ChatItem.Assistant)?.text?.length ?: 0
            val elapsed = (System.currentTimeMillis() - streamStartMs) / 1000.0
            genLive = "%.1fs · ~%d tok…".format(elapsed, len / 4)
            kotlinx.coroutines.delay(250)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ChatGPT Top App Bar
            // Effort pill only for thinking models; otherwise it stays hidden.
            ChatGPTTopAppBar(
                selectedModel = if (autoRoute) "Auto → " + (lastRouted ?: selectedModel) else selectedModel,
                onMenuClick = onOpenDrawer,
                onModelClick = { showModelSheet = true },
                effort = if (isReasoningModel(if (autoRoute) (lastRouted ?: selectedModel) else selectedModel)) effort else null,
                efforts = listOf("Low", "Medium", "High", "Max", "Extreme", "XHigh"),
                onSelectEffort = { effort = it },
                temporaryChat = temporaryChat,
                onToggleTemporaryChat = { toggleTemporaryChat() },
                showTemporaryChat = true,
                onBackClick = null,
                onNewChatClick = {
                    resettingNewChat = true
                    coroutineScope.launch {
                        kotlinx.coroutines.delay(700)
                        persist()
                        messages.clear()
                        queue.clear()
                        conversationId = System.currentTimeMillis().toString()
                        kotlinx.coroutines.delay(100)
                        resettingNewChat = false
                    }
                },
                moreActions = listOf(
                    Triple("Search in chat", Icons.Default.Search) {
                        android.widget.Toast.makeText(context, "Search in chat", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    Triple("Usage stats", Icons.Default.BarChart) {
                        showUsage = true
                    },
                    Triple("Content window: ${windowLabel(contentWindow)}", Icons.Default.DataArray) {
                        contentWindow = UserPrefs.contextTokens(context)
                        showWindowDialog = true
                    },
                    Triple(if (explainerPref) "SmartUI: On" else "SmartUI: Off", Icons.Default.School) {
                        explainerPref = !explainerPref
                        UserPrefs.setExplainerMode(context, explainerPref)
                        android.widget.Toast.makeText(
                            context,
                            if (explainerPref) "SmartUI on — structured answers" else "SmartUI off — normal replies",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    Triple("Customize instructions", Icons.Default.Settings) {
                        customText = UserPrefs.customInstructions(context)
                        showCustomDialog = true
                    },
                    Triple("Share chat link", Icons.Default.Share) {
                        android.widget.Toast.makeText(context, "Chat link copied", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    Triple("Archive this chat", Icons.Default.Archive) {
                        persist()
                        messages.clear()
                        queue.clear()
                        conversationId = System.currentTimeMillis().toString()
                        android.widget.Toast.makeText(context, "Chat archived to history", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    Triple("Temporary chat", Icons.Default.AutoAwesome) {
                        toggleTemporaryChat()
                    },
                    Triple("Clear conversation", Icons.Default.Delete) {
                        ChatHistoryStore.deleteConversation(context, conversationId)
                        messages.clear()
                        queue.clear()
                        conversationId = System.currentTimeMillis().toString()
                        onHistoryChanged()
                    },
                    Triple("Report a problem", Icons.Default.Flag) {
                        android.widget.Toast.makeText(context, "Report submitted", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
            )

            // Temporary chat indicator banner
            if (temporaryChat) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.accentAmber.copy(alpha = 0.12f))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Temporary chat · this conversation won't be saved", color = colors.accentAmber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Compute routing: the node selector lives here, pinned under the
            // header where it stays visible for the whole conversation and
            // out of the composer's typing path. The row collapses to nothing
            // when routing is local-only (no nodes, no stored route).
            ChatRoutingStatus(
                route = workerRoute,
                onSelect = {
                    workerRoute = it
                    UserPrefs.setWorkerTarget(context, it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = 2.dp, bottom = 2.dp)
            )

            // Chat Messages Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(messages, key = { when (it) { is ChatItem.User -> it.id; is ChatItem.Assistant -> it.id } }) { item ->
                    when (item) {
                        is ChatItem.User -> {
                            UserMessageBubble(
                                text = item.text,
                                attachments = item.attachments,
                                tokens = item.tokens,
                                onEdit = { edited ->
                                    val idx = messages.indexOfFirst { (it as? ChatItem.User)?.id == item.id }
                                    if (idx >= 0 && !isGenerating) {
                                        val changed = edited != item.text
                                        messages[idx] = item.copy(text = edited, tokens = edited.length / 4)
                                        if (changed) {
                                            // Drop everything after the edited message: the old
                                            // reply no longer matches, so ask the AI again.
                                            while (messages.size > idx + 1) {
                                                messages.removeAt(messages.size - 1)
                                            }
                                        }
                                        persist()
                                        if (changed) runAssistantTurn(edited, item.attachments)
                                    }
                                }
                            )
                        }
                        is ChatItem.Assistant -> {
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Assistant Header Pill
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(colors.accentGreen),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Psychology,
                                            contentDescription = "Karen",
                                            tint = Color.White,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Karen",
                                        color = colors.textPrimary,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                // Assistant Body: reasoning trace folds into the accordion,
                                // the rest renders as Markdown with ChatGPT-style code blocks.
                                val isLiveThinking = isGenerating && item.id == streamingId && !streamingContent
                                if (item.help || item.text == HELP_TEXT) {
                                    // /help: structured card instead of the text wall.
                                    KarenHelpCard(onInsert = { setInputEnd(it) })
                                } else if (isLiveThinking) {
                                    ThinkingIndicator(
                                        label = item.text.ifBlank { "Thinking" },
                                        elapsed = "%.1fs".format((System.currentTimeMillis() - streamStartMs) / 1000.0)
                                    )
                                } else {
                                    val thinkSplit = remember(item.text) { splitThinkBlock(item.text) }
                                    val thoughtText = item.thought ?: thinkSplit.first
                                    val bodyText = if (item.thought != null) item.text else thinkSplit.second
                                    // Plan lines live inside the reply text; lift them into a card.
                                    val plan = remember(bodyText) { splitPlan(bodyText) }
                                    if (thoughtText != null) {
                                        ThoughtAccordion(
                                            thoughtDuration = "Reasoning trace",
                                            content = thoughtText,
                                            initialExpanded = false
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    if (!plan.isEmpty) {
                                        PlanCard(plan)
                                        Spacer(Modifier.height(10.dp))
                                    }
                                    if (plan.body.isNotBlank()) {
                                        MarkdownText(
                                            text = plan.body,
                                            color = colors.textPrimary,
                                            fontSize = 15.sp,
                                            lineHeight = 22.sp
                                        )
                                    }
                                }

                                // Tool Call Chip
                                if (item.toolCall != null) {
                                    Spacer(Modifier.height(10.dp))
                                    ToolExecutionPill(toolName = item.toolCall)
                                    Spacer(Modifier.height(8.dp))
                                }

                                // Action Confirmation Card (T2 tool awaiting approval)
                                item.pendingTool?.let { pt ->
                                    if (pt.options.isNotEmpty()) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(colors.surface)
                                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                                .padding(vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = "Which ${pt.name.replace('_', ' ')}?",
                                                color = colors.textMuted,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                            )
                                            pt.options.forEach { opt ->
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { selectPendingOption(item.id, opt) }
                                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Person,
                                                        contentDescription = null,
                                                        tint = colors.accentGreen,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        text = opt,
                                                        color = colors.textPrimary,
                                                        fontSize = 13.sp,
                                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    ActionConfirmationCard(
                                        title = pt.name,
                                        subtitle = pt.summary,
                                        confirmLabel = "Run",
                                        dismissLabel = "Cancel",
                                        confirmedNote = "Running…",
                                        onConfirm = { confirmPendingTool(item.id, pt) },
                                        onDismiss = { clearPendingTool(item.id) }
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }

                                // Code Block Container
                                if (item.codeJson != null) {
                                    Spacer(Modifier.height(10.dp))
                                    CodeBlockView(
                                        language = "json",
                                        code = item.codeJson
                                    )
                                }

                                // Message Action Bar
                                MessageActionBar(
                                    messageText = item.text,
                                    onLike = {
                                        StyleMemory.recordLike(context, item.text, item.model)
                                        android.widget.Toast.makeText(context, "Liked — I'll answer more like this", android.widget.Toast.LENGTH_SHORT).show()
                                    },
                                    onDislike = {
                                        dislikeFor = item
                                        dislikeText = ""
                                    },
                                    onSpeak = {
                                        TtsManager.toggle(context, item.text, UserPrefs.voicePersona(context))
                                    },
                                    isSpeaking = TtsManager.speaking && TtsManager.speakingText == item.text,
                                    onRegenerate = {
                                        if (isGenerating) return@MessageActionBar
                                        val lastUserIdx = messages.indexOfLast { it is ChatItem.User }
                                        if (lastUserIdx < 0) return@MessageActionBar
                                        // Drop the old reply (and anything after it), then ask
                                        // the AI to think and answer quite differently.
                                        while (messages.size > lastUserIdx + 1) {
                                            messages.removeAt(messages.size - 1)
                                        }
                                        val u = messages[lastUserIdx] as ChatItem.User
                                        persist()
                                        runAssistantTurn(
                                            u.text,
                                            u.attachments,
                                            regenerateHint = "Think carefully and respond with a quite different take from your previous answer. Do not repeat it."
                                        )
                                    }
                                )
                                // Reply timing + token + model footer, per response.
                                if (item.tookMs > 0) {
                                    val footer = buildString {
                                        append("took ${formatDuration(item.tookMs)}")
                                        if (item.tokens > 0) append(" · ~${item.tokens} tok")
                                        if (item.model.isNotBlank()) append(" · ${item.model}")
                                    }
                                    Text(
                                        text = footer,
                                        color = colors.textMuted,
                                        fontSize = 10.5.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                // Interrupted marker after a stopped reply.
                                if (item.interrupted) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(0.75f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            HorizontalDivider(
                                                modifier = Modifier.weight(1f),
                                                color = colors.border
                                            )
                                            Text(
                                                text = "Interrupted",
                                                color = colors.textMuted,
                                                fontSize = 11.sp,
                                                modifier = Modifier.padding(horizontal = 8.dp)
                                            )
                                            HorizontalDivider(
                                                modifier = Modifier.weight(1f),
                                                color = colors.border
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Fading edge effect on the message scroll area (top & bottom)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(colors.background, Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .align(Alignment.BottomCenter)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(Color.Transparent, colors.background)
                    )
                )
        )

        // Starter prompts and memory completions live in the composer stack
        // (see ChatSuggestionPills) so they align above the Run-on chip
        // instead of floating over the message list.

        // Jump-to-latest FAB (ChatGPT-style): visible only when scrolled
        // up away from the end; hides at the bottom of the chat.
        val showJumpBottom by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                if (total == 0) {
                    false
                } else {
                    val last = info.visibleItemsInfo.lastOrNull()
                    if (last == null) {
                        true
                    } else {
                        val beyond = (last.offset + last.size) - info.viewportEndOffset
                        last.index < total - 1 || beyond > 8
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = showJumpBottom,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 116.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, colors.border, CircleShape)
                    .clickable {
                        coroutineScope.launch {
                            try {
                                val last = messages.size - 1
                                if (last < 0) return@launch
                                listState.animateScrollToItem(last)
                                // Pin to the absolute end (item-top alignment
                                // alone can leave the footer below the fold).
                                val info = listState.layoutInfo
                                val tail = info.visibleItemsInfo.lastOrNull()
                                if (tail != null) {
                                    val beyond = (tail.offset + tail.size) - info.viewportEndOffset
                                    if (beyond > 0) {
                                        listState.animateScrollBy(beyond.toFloat())
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.ArrowDownward,
                    contentDescription = "Jump to latest",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Boundary above the composer while generating.
            if (isGenerating) {
                HorizontalDivider(
                    color = colors.accentGreen.copy(alpha = 0.35f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            // Live generating readout above the composer (hidden when idle —
            // finished replies keep their own took/tokens/model footer).
            val statsLine = genLive
            if (statsLine != null) {
                Row(
                    modifier = Modifier.padding(start = 22.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(statsLine, color = colors.textMuted, fontSize = 10.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
            // Queued prompts: parked while the model was busy, run in order.
            if (queue.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 22.dp, end = 14.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    queue.forEachIndexed { i, q ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.PlaylistAdd,
                                contentDescription = null,
                                tint = colors.accentGreen,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${i + 1}. ${q.text.take(60)}",
                                color = colors.textPrimary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove queued prompt",
                                tint = colors.textMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { queue.removeAt(i) }
                            )
                        }
                    }
                }
            }
            // "@file" vault menu (OpenCode style): "@…" lists vault files
            // above the composer; picking one inserts "@filename ".
            val mentionMatch = Regex("@([\\w.\\-]*)$").find(input.text)
            if (mentionMatch != null) {
                val mq = mentionMatch.groupValues[1].lowercase()
                val mentionFiles = remember(mq) {
                    vaultFiles(context)
                        .filter { mq.isBlank() || it.name.lowercase().contains(mq) }
                        .take(8)
                }
                if (mentionFiles.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 14.dp, bottom = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "VAULT · tap to attach",
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        mentionFiles.forEach { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val base = input.text.removeSuffix("@" + mentionMatch.groupValues[1])
                                        setInputEnd(base + "@${f.name} ")
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Icon(
                                    when (f.extension.lowercase()) {
                                        "pdf", "md", "txt" -> Icons.Default.Description
                                        "mp3", "wav" -> Icons.Default.Mic
                                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                                    },
                                    contentDescription = null,
                                    tint = colors.accentBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "@${f.name}",
                                    color = colors.accentGreen,
                                    fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = formatSize(f.length()),
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
            // Slash tool menu (ChatGPT/OpenCode style): a trailing "/…"
            // token anywhere in the text lists phone tools above the
            // composer; picking one replaces the token with "/name ".
            // A bare trailing "/" lists everything.
            val slashToken = Regex("(?:^|\\s)/([A-Za-z_][A-Za-z0-9_]*)?$").find(input.text)
            val slashQuery = slashToken?.groupValues?.getOrNull(1) ?: ""
            if (slashToken != null) {
                val slashSpecs = remember(slashQuery, slashToken?.value) {
                    val all = ToolSpecs.load(context)
                    val q = slashQuery.lowercase()
                    all.filter { q.isBlank() || it.name.contains(q) || it.desc.lowercase().contains(q) }
                        .sortedWith(compareBy({ !it.name.startsWith(q) }, { it.name }))
                }
                if (slashSpecs.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 14.dp, bottom = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = "TOOLS (${slashSpecs.size}) · tap to insert",
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        if ("help".contains(slashQuery)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { setInputEnd("/help") }
                                    .padding(horizontal = 12.dp, vertical = 7.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(colors.accentGreen)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "/help",
                                    color = colors.accentGreen,
                                    fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "How Karen tools work",
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                        ) {
                            items(slashSpecs, key = { it.name }) { spec ->
                                val tierTint = when (spec.tier) {
                                    "T0" -> colors.accentGreen
                                    "T2", "T3" -> colors.accentAmber
                                    else -> colors.accentBlue
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val tok = slashToken?.value.orEmpty()
                                            val at = input.text.lastIndexOf(tok)
                                            // Keep a preceding space; drop only the token.
                                            val cut = if (at >= 0) at + (if (tok.startsWith("/")) 0 else 1) else -1
                                            val base = if (cut >= 0) input.text.substring(0, cut) else input.text
                                            setInputEnd(base + "/${spec.name} ")
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(tierTint)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "/${spec.name}",
                                        color = colors.accentGreen,
                                        fontSize = 13.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = spec.desc,
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Inserted "/tool …" reminder chip (args are plain text after the name).
            val slashActive = Regex("(?:^|\\s)/([A-Za-z_][A-Za-z0-9_]*)\\s")
                .find(input.text)?.groupValues?.getOrNull(1)
                ?.let { ToolSpecs.byName(context, it) }
            if (slashActive != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 22.dp, bottom = 6.dp)
                ) {
                    Icon(Icons.Default.Build, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "/${slashActive.name} · ${slashActive.desc}",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            // Suggestion pills above the composer, matching the design's
            // staggered stack: starter prompts on a fresh chat, memory
            // completions (past asks remembered on this device) once typing
            // starts. Picking one fills the field and parks the cursor after a
            // trailing space.
            val memoryQuery = input.text.trim()
            val memoryMatches = remember(memoryQuery, promptMemoryRevision) {
                if (memoryQuery.length < 2 || mentionMatch != null || slashToken != null) {
                    emptyList<String>()
                } else {
                    PromptMemory.matches(context, memoryQuery)
                }
            }
            val starterSuggestions = remember(messages.size) {
                if (messages.size <= 1) {
                    listOf("Inspect my timetable", "Summarize a document", "Set a reminder", "Open Workspace")
                } else {
                    emptyList()
                }
            }
            if (memoryMatches.isNotEmpty() ||
                (memoryQuery.isBlank() && starterSuggestions.isNotEmpty())
            ) {
                ChatSuggestionPills(
                    starter = starterSuggestions,
                    memory = memoryMatches,
                    colors = colors,
                    onPick = { pick -> setInputEnd("$pick ") }
                )
            }
            ChatGPTFloatingComposer(
                value = input,
                onValueChange = { input = it },
                onSend = {
                val rawText = input.text.trim()
                if (rawText.isNotBlank() || attachments.isNotEmpty()) {
                    if (rawText.isNotBlank() && !temporaryChat) PromptMemory.record(context, rawText)
                    input = TextFieldValue("")
                    val sentAttachments = attachments
                    attachments = emptyList()
                    // Deterministic tool routing first (no model, no API);
                    // anything needing judgment falls through to the AI route.
                    if (!(sentAttachments.isEmpty() && tryDirectToolTurn(rawText))) {
                        sendNow(expandSlash(context, rawText), sentAttachments, displayText = rawText)
                    }
                }
            },
            onAttachClick = { showAttachmentSheet = true },
            attachmentsPreview = attachments,
            onRemoveAttachment = { index -> attachments = attachments.filterIndexed { i, _ -> i != index } },
            onMicClick = { voiceStt.toggle() },
            onVoiceModeClick = onNavigateToVoice,
            isListening = voiceStt.state.isListening,
            listeningText = voiceStt.state.partialText,
            isGenerating = isGenerating,
            onStop = { stopNow() },
            onQueue = { enqueueCurrent() },
            modifier = Modifier.fillMaxWidth()
        )
        }

        // Model Selector Bottom Sheet
        if (showModelSheet) {
            ModelSelectorSheet(
                selectedModel = selectedModel,
                onSelectModel = {
                    selectedModel = it
                    UserPrefs.setSelectedModel(context, it)
                    UserPrefs.pushRecentModel(context, it)
                    if (autoRoute) {
                        autoRoute = false
                        UserPrefs.setAutoRoute(context, false)
                    }
                },
                onDismiss = { showModelSheet = false },
                onOpenModelManager = onNavigateToModelManager,
                autoRoute = autoRoute,
                onSelectAuto = {
                    autoRoute = true
                    UserPrefs.setAutoRoute(context, true)
                }
            )
        }

        // One-time web search permission (asked at most once ever).
        val consentQuery = webConsentPreview
        if (webConsentGate != null && consentQuery != null) {
            AlertDialog(
                onDismissRequest = {
                    // Dismissed without answering: ask again next time.
                    webConsentGate?.complete(false)
                    webConsentGate = null
                    webConsentPreview = null
                },
                title = { Text("Allow web search?") },
                text = {
                    Text(
                        "Karen wants to search the web for:\n\"${consentQuery.take(100)}\"\n\nOnly the query leaves the device. You will only be asked this once — control it later in Settings → Privacy.",
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        UserPrefs.setWebSearchAsked(context, true)
                        UserPrefs.setWebSearchEnabled(context, true)
                        webConsentGate?.complete(true)
                        webConsentGate = null
                        webConsentPreview = null
                    }) { Text("Allow") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        UserPrefs.setWebSearchAsked(context, true)
                        UserPrefs.setWebSearchEnabled(context, false)
                        webConsentGate?.complete(false)
                        webConsentGate = null
                        webConsentPreview = null
                    }) { Text("Don't allow") }
                }
            )
        }

        // Content-window switcher (local RAM size; cloud uses provider window).
        if (showWindowDialog) {
            AlertDialog(
                onDismissRequest = { showWindowDialog = false },
                title = { Text("Content window") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        UserPrefs.contextWindowOptions.forEach { tokens ->
                            val selected = contentWindow == tokens
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        contentWindow = tokens
                                        UserPrefs.setContextTokens(context, tokens)
                                        showWindowDialog = false
                                    }
                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        contentWindow = tokens
                                        UserPrefs.setContextTokens(context, tokens)
                                        showWindowDialog = false
                                    },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = colors.accentGreen,
                                        unselectedColor = colors.textMuted
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = windowLabel(tokens),
                                        color = if (selected) colors.accentGreen else colors.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                    Text(
                                        text = "$tokens tokens history + project context",
                                        color = colors.textMuted,
                                        fontSize = 11.5.sp
                                    )
                                }
                            }
                        }
                        Text(
                            "On-device models reload with the new size on next run.",
                            color = colors.textMuted,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showWindowDialog = false }) { Text("Close") }
                }
            )
        }

        // Custom instructions editor (memory for every prompt).
        if (showCustomDialog) {
            AlertDialog(
                onDismissRequest = { showCustomDialog = false },
                title = { Text("Custom instructions") },
                text = {
                    Column {
                        Text(
                            "Remembered on this device and added to every model request.",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it },
                            placeholder = { Text("e.g. Always answer with examples") },
                            colors = karenFieldColors(colors),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        UserPrefs.setCustomInstructions(context, customText)
                        showCustomDialog = false
                        android.widget.Toast.makeText(context, "Instructions saved to memory", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        UserPrefs.setCustomInstructions(context, "")
                        customText = ""
                        showCustomDialog = false
                    }) { Text("Clear") }
                }
            )
        }

        // Usage stats for this conversation.
        if (showUsage) {
            val users = messages.filterIsInstance<ChatItem.User>()
            val replies = messages.filterIsInstance<ChatItem.Assistant>().filter { it.text.isNotBlank() }
            val inTokens = replies.sumOf { it.inTokens }
            val outTokens = replies.sumOf { it.tokens }
            val reasonTokens = replies.sumOf { it.reasonTokens }
            val approx = replies.any { it.tokens > 0 && !it.usageReal }
            val pre = if (approx) "≈" else ""
            val modelCounts = replies.filter { it.model.isNotBlank() }
                .groupingBy { it.model }.eachCount()
            var switches = 0
            var prev: String? = null
            replies.forEach {
                if (it.model.isNotBlank()) {
                    if (prev != null && prev != it.model) switches++
                    prev = it.model
                }
            }
            val started = conversationId.toLongOrNull()?.let {
                java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))
            } ?: "-"
            AlertDialog(
                onDismissRequest = { showUsage = false },
                title = { Text("Usage stats") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        KV("Started", started)
                        KV("Requests", users.size.toString())
                        KV("Responses", replies.size.toString())
                        KV("Input tokens", "$pre$inTokens tok")
                        KV("Output tokens", "$pre$outTokens tok")
                        KV("Reasoning tokens", "$pre$reasonTokens tok")
                        if (approx) {
                            Text(
                                "≈ estimated from text length where the provider didn't report usage",
                                color = colors.textMuted,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp
                            )
                        }
                        KV("Model switches", switches.toString())
                        KV("Queued", queue.size.toString())
                        if (modelCounts.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text("Models used", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            modelCounts.entries.sortedByDescending { it.value }.forEach { (m, n) ->
                                KV(m, "×$n")
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showUsage = false }) { Text("Close") }
                }
            )
        }

        // Dislike feedback: how should Karen answer instead?
        val badItem = dislikeFor
        if (badItem != null) {
            AlertDialog(
                onDismissRequest = { dislikeFor = null },
                title = { Text("What went wrong?") },
                text = {
                    Column {
                        Text(
                            "Tell Karen how you'd like answers instead — saved to style memory for 30 days / 300 messages.",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = dislikeText,
                            onValueChange = { dislikeText = it },
                            placeholder = { Text("e.g. shorter answers with examples") },
                            colors = karenFieldColors(colors),
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        StyleMemory.recordDislike(context, dislikeText)
                        dislikeFor = null
                        android.widget.Toast.makeText(context, "Saved — I'll adjust my style", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { dislikeFor = null }) { Text("Cancel") }
                }
            )
        }

        // Attachment Sheet
        if (showAttachmentSheet) {
            AttachmentSheet(
                onDismiss = { showAttachmentSheet = false },
                onSelectOption = { option ->
                    when (option) {
                        "Document / PDF" -> docPicker.launch(arrayOf("*/*"))
                        "Photos Library" -> imagePicker.launch(arrayOf("image/*", "video/*"))
                        "Voice Memo" -> audioPicker.launch(arrayOf("audio/*"))
                        "Take Photo" -> takePhoto()
                    }
                }
            )
        }

        // New-chat transition overlay — a quiet bloom, not a spinner.
        androidx.compose.animation.AnimatedVisibility(
            visible = resettingNewChat,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(220)
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(220)
            )
        ) {
            NewChatBloomOverlay(colors)
        }
    }
}

/**
 * The /help answer as a structured card instead of a text wall. The quick-start
 * rows are hand-picked examples; the catalog under them is generated from
 * tools.json (name, required args, tier, description), so every command and
 * tool is documented and tapping any row drops it into the composer ("@"
 * opens the vault file menu). Colors follow the existing chat surfaces.
 */
@Composable
private fun KarenHelpCard(onInsert: (String) -> Unit) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    // Generated from tools.json so the guide can never drift from the tool
    // schemas the router actually exposes.
    val toolSpecs = remember { ToolSpecs.load(context) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = colors.accentGreen,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "How Karen works",
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "tap a row to insert",
                color = colors.textMuted,
                fontSize = 10.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }

        Text(
            "Every phone tool is a slash command. Type / to open the tool menu, or tap any row " +
                "below to put it in the composer. Plain language works too — “call Arun”, " +
                "“remind me at 6” route the same way.",
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        HelpSectionLabel("START HERE · TAP TO INSERT", colors)
        HelpRow("/calculator", "1847*23", "solved on the phone itself", "/calculator 1847*23", colors, onInsert)
        HelpRow("/get_time", "Asia/Kolkata", "real device clock, any timezone", "/get_time Asia/Kolkata", colors, onInsert)
        HelpRow("/open_app", "Spotify", "launches any installed app", "/open_app Spotify", colors, onInsert)
        HelpRow("/set_timer", "2 min eggs", "real system timer", "/set_timer 2 min eggs", colors, onInsert)
        HelpRow("/place_call", "Arun", "T2 — matches the contact, asks before dialing", "/place_call Arun", colors, onInsert)
        HelpRow("/search", "today's AI news", "keyless DuckDuckGo + Wikipedia; also “search: …”", "/search today's AI news", colors, onInsert)
        HelpRow("/help", "", "this card", "/help", colors, onInsert)

        HelpSectionLabel("ALL TOOLS · /name <required args>", colors)
        Text(
            "T0 reads and math · T1 reversible · T2 asks first · T3 blocked. " +
                "Tap a tool to insert it, then add the arguments.",
            color = colors.textMuted,
            fontSize = 10.5.sp,
            lineHeight = 14.sp
        )
        if (toolSpecs.isEmpty()) {
            Text(
                "Tool catalog could not be loaded.",
                color = colors.textMuted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            listOf(
                "T0" to "T0 · READS + MATH — always auto-run",
                "T1" to "T1 · REVERSIBLE — auto-run, visible result pill",
                "T2" to "T2 · ASKS FIRST — Run/Cancel card",
                "T3" to "T3 · BLOCKED — disabled"
            ).forEach { (tier, title) ->
                val tools = toolSpecs.filter { it.tier == tier }.sortedBy { it.name }
                if (tools.isNotEmpty()) {
                    HelpSectionLabel("$title (${tools.size})", colors)
                    tools.forEach { HelpToolRow(it, colors, onInsert) }
                }
            }
        }

        HelpSectionLabel("MENTIONS · VAULT GROUNDING (RAG)", colors)
        HelpRow(
            "@file", "notes.md",
            "splits that vault file into chunks and feeds only the passages nearest your question — long documents still fit, follow-ups stay cited",
            "@", colors, onInsert
        )

        HelpSectionLabel("WHAT ANSWERS · WHERE TO CHANGE IT", colors)
        HelpRow("header pill", "", "shows the active model; Auto routes per task — Settings → Model Routing", "", colors, onInsert)
        HelpRow("voice", "", "streams sentence by sentence; wake word lives in Settings → Voice & Speech", "", colors, onInsert)

        Text(
            "Karen only reports a tool as done after the phone returns the result. T2 actions " +
                "wait for your tap — nothing calls, messages, or writes memory on its own.",
            color = colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun HelpSectionLabel(text: String, colors: KarenColors) {
    Text(
        text = text,
        color = colors.textMuted,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

/** One guide row: a mono chip plus its one-line explanation. */
@Composable
private fun HelpRow(
    chip: String,
    tail: String,
    desc: String,
    insert: String,
    colors: KarenColors,
    onInsert: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (insert.isNotBlank()) Modifier.clickable { onInsert(insert) } else Modifier)
            .padding(vertical = 3.dp)
    ) {
        Text(
            text = if (tail.isBlank()) chip else "$chip $tail",
            color = colors.accentBlue,
            fontSize = 11.5.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.background)
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp)
        )
        if (desc.isNotBlank()) {
            Spacer(Modifier.width(9.dp))
            Text(
                text = desc,
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * One row in the generated tool catalog: the slash form plus required-argument
 * hints on the first line, the tool's own description underneath. Tapping
 * inserts "/name " so the composer is ready for arguments.
 */
@Composable
private fun HelpToolRow(
    spec: ToolSpec,
    colors: KarenColors,
    onInsert: (String) -> Unit
) {
    val hint = spec.args
        .filter { it.required }
        .joinToString(" ") { "<${it.name}>" }
    // Enum values are the part a bare argument name cannot convey (which
    // settings page, which media action, which flashlight state).
    val values = spec.args
        .filter { it.required && it.enum.isNotEmpty() }
        .joinToString(" · ") { "${it.name}: ${it.enum.joinToString("|")}" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onInsert("/${spec.name} ") }
            .padding(vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "/${spec.name}",
                color = colors.accentBlue,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.background)
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (hint.isNotBlank()) {
                Spacer(Modifier.width(7.dp))
                Text(
                    text = hint,
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = spec.desc,
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        if (values.isNotBlank()) {
            Text(
                text = values,
                color = colors.textMuted,
                fontSize = 9.5.sp,
                lineHeight = 13.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * Pinned compute-routing row under the chat header. The node selector and the
 * per-node model chip sit together here — visible for the whole conversation,
 * never inside the composer — and both collapse away for phone-only chats.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatRoutingStatus(
    route: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        WorkerRouteChip(current = route, onSelect = onSelect)
        WorkerModelChip(route = route)
    }
}

/**
 * Suggestion pills above the composer (design ref: the staggered stack —
 * "Inspect my timetable", then "Summarize a document" + "Set a reminder",
 * then "Open Workspace"). A fresh chat keeps that 1-2-1 rhythm; while
 * typing, memory completions flow in the same pill style.
 *
 * The stack assembles: each pill rises out of a faint green halo, unwinding
 * its sparkle and passing one light sweep across the surface. The halo
 * breathes slowly behind the block so an empty chat never feels frozen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChatSuggestionPills(
    starter: List<String>,
    memory: List<String>,
    colors: KarenColors,
    onPick: (String) -> Unit
) {
    val typing = memory.isNotEmpty()
    val halo = rememberInfiniteTransition(label = "pill_halo")
    val breathe by halo.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pill_halo_alpha"
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            colors.accentGreen.copy(alpha = 0.075f * breathe),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(
                            size.width / 2f,
                            size.height * 0.55f
                        ),
                        radius = size.width * 0.58f
                    )
                )
            }
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            if (typing) {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    memory.forEachIndexed { i, text ->
                        SuggestionPill(text, colors, 300.dp, i) { onPick(text) }
                    }
                }
            } else if (starter.size >= 4) {
                SuggestionPill(starter[0], colors, 300.dp, 0) { onPick(starter[0]) }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SuggestionPill(starter[1], colors, 240.dp, 1) { onPick(starter[1]) }
                    SuggestionPill(starter[2], colors, 240.dp, 2) { onPick(starter[2]) }
                }
                SuggestionPill(starter[3], colors, 300.dp, 3) { onPick(starter[3]) }
            }
        }
    }
}

/**
 * One suggestion pill: green sparkle + label, full radius, no border.
 * [index] staggers the entrance so the stack assembles top-to-bottom.
 */
@Composable
private fun SuggestionPill(
    text: String,
    colors: KarenColors,
    maxWidth: Dp,
    index: Int,
    onClick: () -> Unit
) {
    var landed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { landed = true }
    // One driver: rise + scale + sparkle unwind + a single light sweep.
    val enter by animateFloatAsState(
        targetValue = if (landed) 1f else 0f,
        animationSpec = tween(
            durationMillis = 440,
            delayMillis = 30 + index * 55,
            easing = CubicBezierEasing(0.16f, 0.84f, 0.24f, 1f)
        ),
        label = "pill_enter_$index"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(max = maxWidth)
            .graphicsLayer {
                alpha = enter
                translationY = (1f - enter) * 22.dp.toPx()
                scaleX = 0.90f + 0.10f * enter
                scaleY = 0.90f + 0.10f * enter
            }
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceHover)
            .drawWithContent {
                drawContent()
                val sweep = ((enter - 0.30f) / 0.70f).coerceIn(0f, 1f)
                if (sweep > 0f && sweep < 1f) {
                    val x = -size.width * 0.7f + size.width * 2.1f * sweep
                    drawRect(
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.06f),
                                Color.Transparent
                            ),
                            start = androidx.compose.ui.geometry.Offset(x, 0f),
                            end = androidx.compose.ui.geometry.Offset(
                                x + size.width * 0.55f,
                                size.height
                            )
                        )
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Icon(
            Icons.Default.AutoAwesome,
            contentDescription = null,
            tint = colors.accentGreen,
            modifier = Modifier
                .size(14.dp)
                .graphicsLayer {
                    scaleX = 0.55f + 0.45f * enter
                    scaleY = 0.55f + 0.45f * enter
                    rotationZ = (1f - enter) * -70f
                }
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = colors.textPrimary,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

/**
 * New-chat moment ("quiet bloom"): a dark veil with a faint green core, a
 * sparkle tile that lands with a soft overshoot, one expanding hairline ring,
 * and a thin green→blue rule that fills for the reset. Replaces the old
 * ripple + spinner, which read as a generic loader — this reads as the page
 * turning. Runs ~760ms, matching the caller's hold before the transcript
 * clears, so the exit fade lands exactly on the empty chat.
 */
@Composable
private fun NewChatBloomOverlay(colors: KarenColors) {
    var start by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { start = true }

    val land by animateFloatAsState(
        targetValue = if (start) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.58f, stiffness = 320f),
        label = "new_chat_land"
    )
    val ring by animateFloatAsState(
        targetValue = if (start) 1f else 0f,
        animationSpec = tween(durationMillis = 720, easing = FastOutSlowInEasing),
        label = "new_chat_ring"
    )
    val fill by animateFloatAsState(
        targetValue = if (start) 1f else 0f,
        animationSpec = tween(
            durationMillis = 560,
            delayMillis = 90,
            easing = FastOutSlowInEasing
        ),
        label = "new_chat_rule"
    )
    val copy by animateFloatAsState(
        targetValue = if (start) 1f else 0f,
        animationSpec = tween(
            durationMillis = 320,
            delayMillis = 110,
            easing = FastOutSlowInEasing
        ),
        label = "new_chat_copy"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(
                        colors.accentGreen.copy(alpha = 0.10f),
                        Color.Black.copy(alpha = 0.88f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
                // One hairline ring: born with the tile, gone by the reset.
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .graphicsLayer {
                            val s = 0.72f + 1.55f * ring
                            scaleX = s
                            scaleY = s
                            alpha = (1f - ring) * 0.45f
                        }
                        .border(1.dp, colors.accentGreen, CircleShape)
                )
                // Sparkle tile: pops with a small overshoot, unrotates as it lands.
                Box(
                    modifier = Modifier.graphicsLayer {
                        scaleX = 0.70f + 0.30f * land
                        scaleY = 0.70f + 0.30f * land
                        rotationZ = (1f - land) * -22f
                    }
                ) {
                    KLogoMark(size = 58.dp) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "New chat",
                            tint = colors.accentGreen,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = "New chat",
                color = colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                modifier = Modifier.graphicsLayer {
                    alpha = copy
                    translationY = (1f - copy) * 8.dp.toPx()
                }
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "A CLEAN SLATE",
                color = colors.textMuted,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.18.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.graphicsLayer { alpha = copy * 0.9f }
            )
            Spacer(Modifier.height(18.dp))
            // The wait has a shape: a 2dp rule filling once, left to right.
            Box(
                modifier = Modifier
                    .width(108.dp)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(colors.border)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fill.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(colors.accentGreen, colors.accentBlue)
                            )
                        )
                )
            }
        }
    }
}
