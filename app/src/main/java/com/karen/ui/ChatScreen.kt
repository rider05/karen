package com.karen.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.rememberVoiceStt
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
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
        /** Approx. reply tokens (chars/4). 0 = unknown/not finished. */
        val tokens: Int = 0,
        /** True when this reply was cut short by Stop. */
        val interrupted: Boolean = false,
        /** Model that wrote this reply (manual pick or Auto route). */
        val model: String = ""
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
private val ATTACH_TEXT_EXTS = setOf(
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
    else -> 12L
}

/** Reply budget per effort level for cloud calls. */
fun maxTokensFor(effort: String): Int = when (effort) {
    "Low" -> 256
    "High" -> 1024
    "Max" -> 2048
    "Extreme" -> 4096
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

    var input by remember { mutableStateOf("") }
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

    val voiceStt = rememberVoiceStt(
        onResult = { speechText ->
            input = if (input.isBlank()) speechText else "$input $speechText"
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
        val decision = routeModel(prompt, locals, keyed, UserPrefs.autoCloud(context))
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

    /**
     * Immediate stop feedback: swaps a bare status bubble to "Cancelling…"
     * while the native call winds down. Real reply text is never clobbered.
     */
    fun markCancelling() {
        streamingId?.let { id ->
            val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
            if (i >= 0) {
                (messages[i] as? ChatItem.Assistant)?.let {
                    if (it.text.isBlank() || it.text.trimEnd().endsWith("…")) {
                        messages[i] = it.copy(text = "Cancelling…")
                    }
                }
            }
        }
    }

    // System back button: dismiss open sheets first, stop generation, else Home
    androidx.activity.compose.BackHandler {
        when {
            showModelSheet -> showModelSheet = false
            showAttachmentSheet -> showAttachmentSheet = false
            webConsentGate != null -> {
                webConsentGate?.complete(false)
                webConsentGate = null
                webConsentPreview = null
            }
            isGenerating -> { cancelGeneration = true; KarenLlama.cancel(); markCancelling() }
            else -> onNavigateToHome()
        }
    }

    /** Parks the current composer text to run after the in-flight reply. */
    fun enqueueCurrent() {
        val userText = input.trim()
        if (userText.isBlank() && attachments.isEmpty()) return
        val sentAttachments = attachments
        input = ""
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
        val i = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == id }
        if (i >= 0) {
            val cur = messages[i] as ChatItem.Assistant
            if (cur.text != text) messages[i] = cur.copy(text = text)
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
            if (beyondPx <= 200) listState.scrollToItem(total - 1)
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
                messages[i] = it.copy(tookMs = elapsed, tokens = tokens ?: it.text.length / 4)
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
            if (spec.tier == "T2" || spec.tier == "T3") {
                if (spec.tier == "T3") {
                    notes.add("⚠ ${spec.name} is disabled (T3) — not run")
                    continue
                }
                // place_call: resolve the name against contacts first so the
                // card confirms a real number; several matches → picker.
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
                executePhoneTool(context, spec, call.args)
            } catch (e: Exception) {
                ExecResult(false, "failed (${e.message})")
            }
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
        return ToolSettle(visible, docText, docName)
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

    /** End-of-generation after Stop: keep partial text, drop bare placeholders. */
    suspend fun cancelSettle(id: String) {
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
        genLive = null
        scrollToBottomIfNear()
        drainQueue()
    }

    /** End-of-generation on success: stamp stats, archive, then run queued prompts. */
    suspend fun completeSettle() {
        val id = streamingId
        // Length from the played text so stats match exactly what is shown.
        val len = (messages.firstOrNull { (it as? ChatItem.Assistant)?.id == id } as? ChatItem.Assistant)?.text?.length ?: 0
        id?.let { stampTook(it, len / 4) }
        persist()
        isGenerating = false
        streamingId = null
        genLive = null
        scrollToBottomIfNear()
        // Queued prompts (typed while busy) run now, one following the other.
        drainQueue()
    }

    /** Failed turn: show the error inline in the provisional bubble. */
    suspend fun errorSettle(id: String, text: String) {
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
        val parts = listOf(
            if (wantsExplainer(prompt, context)) EXPLAINER_STYLE_GUIDE else "",
            StyleMemory.currentHint(context),
            ToolSpecs.promptBlock(context, prompt),
            if (custom.isNotBlank()) "User instructions: $custom" else ""
        ).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "" else " " + parts.joinToString("\n\n")
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
        regenerateHint: String?
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
                (base + ("user" to "Use these fresh web results if relevant:\n$web")).takeLast(limit)
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
            val fullReply = StringBuilder()
            var roundHistory = styled
            var rounds = 0
            var cut = true
            while (cut && rounds < 4 && !cancelGeneration) {
                if (rounds > 0) {
                    setStreamingText(aid, "Continuing… (part ${rounds + 1})")
                }
                val res = provider.completeResult(
                    key,
                    roundHistory,
                    maxTokensFor(effort),
                    thinkingBudget = if (provider.reasoning) thinkingBudgetFor(effort) else null,
                    historyLimit = limit,
                    model = UserPrefs.apiModel(context, provider.id).ifBlank { null }
                )
                if (cancelGeneration) break
                fullReply.append(res.text)
                cut = res.truncated && res.text.isNotBlank()
                rounds++
                if (cut && rounds < 4) {
                    roundHistory = (roundHistory + ("assistant" to res.text) + ("user" to "Continue exactly where you stopped. Output only the continuation — no recap, no repetition.")).takeLast(limit)
                }
            }
            val reply = cleanReply(fullReply.toString())
            if (cancelGeneration) {
                cancelSettle(aid)
                return
            }
            // Realtime tools: validate + run ///TOOL calls before showing.
            val settled = settleTools(aid, reply)
            if (cancelGeneration) {
                cancelSettle(aid)
                return
            }
            var withTools = settled.visible
            // Document reads: answer from the extracted text in the same turn.
            if (settled.docText != null && !cancelGeneration) {
                setStreamingText(aid, (withTools.trimEnd() + "\n\nReading ${settled.docName}…").trim())
                try {
                    val followHistory = (styled + ("user" to
                        "Use this document text to answer the request. Do not emit ///TOOL lines:\n${settled.docText}")
                        ).takeLast(limit)
                    val ans = provider.completeResult(
                        key,
                        followHistory,
                        maxTokensFor(effort),
                        thinkingBudget = if (provider.reasoning) thinkingBudgetFor(effort) else null,
                        historyLimit = limit,
                        model = UserPrefs.apiModel(context, provider.id).ifBlank { null }
                    ).text.trim()
                    if (ans.isNotBlank() && !cancelGeneration) {
                        val clean = stripToolCalls(ans, extractToolCalls(ans)).trim()
                        if (clean.isNotBlank()) {
                            withTools = (withTools.trimEnd() + "\n\n" + clean + "\n(Source: ${settled.docName})").trim()
                        }
                    }
                } catch (_: Exception) {
                }
            }
            if (hasVisibleContent(withTools)) {
                playOut(aid, withTools)
                if (cancelGeneration) cancelSettle(aid) else completeSettle()
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
            // Reroute: next keyed cloud takes over the same turn.
            val next = pickCloud(userText, tried)
            if (next == null) {
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
    fun runAssistantTurn(
        userText: String,
        sentAttachments: List<Attachment>,
        regenerateHint: String? = null
    ) {
        if (isGenerating) return
        // Live cloud model when one is selected (key stored in Model Manager),
        // real on-device inference for downloaded weights, else the mock.
        // Auto routing swaps in the best model for this prompt.
        val sendModel = effectiveModel(userText.ifBlank { "chat" })
        val cloud = findCloudProviderByName(sendModel, UserPrefs.customProviders(context))
        val cloudKey = cloud?.let { UserPrefs.apiKey(context, it.id) }.orEmpty()
        val weightFile = if (cloud == null) ModelDownloader.weightFileFor(context, sendModel) else null

        coroutineScope.launch {
            cancelGeneration = false
            streamStartMs = System.currentTimeMillis()
            streamingContent = false
            genLive = null
            val aid = "asst_${System.currentTimeMillis()}"
            messages.add(ChatItem.Assistant(id = aid, text = "", model = sendModel))
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
                        WebSearch.search(WebSearch.cleanQuery(userText))
                            .takeIf { it.isNotEmpty() }
                            ?.let(WebSearch::formatForModel)
                    } catch (_: Exception) { null }
                    if (cancelGeneration) {
                        cancelSettle(aid)
                        return@launch
                    }
                }
            }
            if (cloud != null && cloudKey.isNotBlank()) {
                cloudTurn(cloud, cloudKey, aid, userText, web, regenerateHint)
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
                        val localLimit = localTurnsFor(window)
                        val basePairs = messages.mapNotNull { item ->
                            when (item) {
                                is ChatItem.User -> "user" to (item.text + attachedContext(item))
                                is ChatItem.Assistant -> if (item.id == aid) null else "assistant" to item.text
                            }
                        }
                        val trimmedPairs = if (web != null) {
                            (basePairs + ("user" to "Use these fresh web results if relevant:\n$web")).takeLast(localLimit)
                        } else basePairs.takeLast(localLimit)
                        val instructedPairs = if (regenerateHint != null) {
                            (trimmedPairs + ("user" to regenerateHint)).takeLast(localLimit)
                        } else trimmedPairs
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
                        val out = awaitAbandoned {
                            val l = KarenLlama.ensureLoaded(weightFile, sendModel, window, threads)
                            var r = ""
                            if (l) {
                                // True token streaming: each predicted piece
                                // lands in the bubble as it is generated.
                                r = KarenLlama.completeStream(
                                    "You are Karen, a concise on-device assistant." +
                                        if (isReasoningModel(sendModel)) reasoningEffortHint(effort) else "" +
                                        shaping,
                                    instructedPairs.map { it.first }.toTypedArray(),
                                    instructedPairs.map { it.second }.toTypedArray(),
                                    maxTokensFor(effort)
                                ) { piece ->
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
                        if (out == null) {
                            // Local took too long (abandoned above): switch to
                            // API without breaking flow.
                            if (cancelGeneration) {
                                cancelSettle(aid)
                            } else {
                                val fb = pickFallbackCloud(userText)
                                if (fb == null) {
                                    errorSettle(aid, "$sendModel is taking too long and no keyed cloud model is reachable — check internet and Model Manager keys.")
                                } else {
                                    android.widget.Toast.makeText(context, "Local slow — continuing on ${fb.name}", android.widget.Toast.LENGTH_SHORT).show()
                                    cloudTurn(fb, UserPrefs.apiKey(context, fb.id), aid, userText, web, regenerateHint)
                                }
                            }
                        } else if (!out.loaded) {
                            if (cancelGeneration) cancelSettle(aid)
                            else errorSettle(aid, "Could not load $sendModel into RAM.")
                        } else if (cancelGeneration) {
                            cancelSettle(aid)
                        } else {
                            val finalReply = cleanReply(out.reply)
                            val settled = settleTools(aid, finalReply)
                            var withTools = settled.visible
                            if (settled.docText != null && !cancelGeneration) {
                                setStreamingText(aid, (withTools.trimEnd() + "\n\nReading ${settled.docName}…").trim())
                                val docPairs = (instructedPairs + ("user" to
                                    "Use this document text to answer the request. Do not emit ///TOOL lines:\n${settled.docText}")
                                    ).takeLast(localLimit)
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
                                if (cancelGeneration) cancelSettle(aid) else completeSettle()
                            } else {
                                errorSettle(aid, "The model returned an empty reply.")
                            }
                        }
                    } catch (e: Exception) {
                        errorSettle(aid, "Local model error: ${e.message}")
                    }
                }
            } else {
                // Simulate Karen on-device response (token streaming)
                val fileNote = if (sentAttachments.isNotEmpty()) {
                    val names = sentAttachments.joinToString(", ") { it.name }
                    " I received ${sentAttachments.size} file(s): $names. Files are stored locally in the vault — 0 bytes sent externally."
                } else ""
                val regenNote = if (regenerateHint != null) "\n\n(Regenerated with a fresh take.)" else ""
                val fullText = if (web != null) {
                    "Fresh web results:\n$web"
                } else {
                    "No data found — connect a local model in Model Manager.$fileNote$regenNote"
                }
                kotlinx.coroutines.delay(400)
                if (cancelGeneration) {
                    cancelSettle(aid)
                } else {
                    playOut(aid, fullText)
                    if (cancelGeneration) cancelSettle(aid) else completeSettle()
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
        val (spec, rest) = matchSlash(context, rawText) ?: return false
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
        if (spec.tier == "T2") {
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
            val res = try {
                executePhoneTool(context, spec, dargs)
            } catch (e: Exception) {
                ExecResult(false, "failed (${e.message})")
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
    }

    // Fresh chat requested from the drawer while already on this screen.
    LaunchedEffect(newChatSignal) {
        if (newChatSignal == 0) return@LaunchedEffect
        persist()
        messages.clear()
                        queue.clear()
        conversationId = System.currentTimeMillis().toString()
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
                    Triple(if (explainerPref) "Study mode: On" else "Study mode: Off", Icons.Default.School) {
                        explainerPref = !explainerPref
                        UserPrefs.setExplainerMode(context, explainerPref)
                        android.widget.Toast.makeText(
                            context,
                            if (explainerPref) "Study mode on — structured answers" else "Study mode off — normal replies",
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

            // Chat Messages Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(top = 0.dp, bottom = 200.dp),
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
                                if (isLiveThinking) {
                                    ThinkingIndicator(
                                        label = item.text.ifBlank { "Thinking" },
                                        elapsed = "%.1fs".format((System.currentTimeMillis() - streamStartMs) / 1000.0)
                                    )
                                } else {
                                    val thinkSplit = remember(item.text) { splitThinkBlock(item.text) }
                                    val thoughtText = item.thought ?: thinkSplit.first
                                    val bodyText = if (item.thought != null) item.text else thinkSplit.second
                                    if (thoughtText != null) {
                                        ThoughtAccordion(
                                            thoughtDuration = "Reasoning trace",
                                            content = thoughtText,
                                            initialExpanded = false
                                        )
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    if (bodyText.isNotBlank()) {
                                        MarkdownText(
                                            text = bodyText,
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

        // Floating Bottom Composer Dock
        // ChatGPT-style prompt suggestion chips: shown on a fresh chat,
        // hidden as soon as the user sends their first prompt.
        if (messages.size <= 1) {
            val suggestions = listOf("Inspect my timetable", "Summarize a document", "Set a reminder", "Open Workspace")
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 104.dp, start = 20.dp, end = 20.dp)
            ) {
                suggestions.forEach { suggestion ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                            .clickable { input = suggestion }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = colors.accentGreen,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            suggestion,
                            color = colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

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
            // Slash tool menu (ChatGPT/OpenCode style): "/…" lists phone
            // tools above the composer; picking one inserts "/name ".
            val slashQuery = if (input.startsWith("/") && ' ' !in input) {
                input.removePrefix("/").trimStart()
            } else null
            if (slashQuery != null) {
                val slashSpecs = remember(slashQuery) {
                    val all = ToolSpecs.load(context)
                    val q = slashQuery.lowercase()
                    all.filter { q.isBlank() || it.name.contains(q) || it.desc.lowercase().contains(q) }
                        .sortedWith(compareBy({ !it.name.startsWith(q) }, { it.name }))
                        .take(8)
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
                            text = "TOOLS · tap to insert",
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        slashSpecs.forEach { spec ->
                            val tierTint = when (spec.tier) {
                                "T0" -> colors.accentGreen
                                "T2", "T3" -> colors.accentAmber
                                else -> colors.accentBlue
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { input = "/${spec.name} " }
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
            // Inserted "/tool …" reminder chip (args are plain text after the name).
            val slashActive = Regex("^/([A-Za-z_][A-Za-z0-9_]*)\\s")
                .find(input)?.groupValues?.getOrNull(1)
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
            ChatGPTFloatingComposer(
                value = input,
                onValueChange = { input = it },
                onSend = {
                val rawText = input.trim()
                if (rawText.isNotBlank() || attachments.isNotEmpty()) {
                    input = ""
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
            onStop = {
                cancelGeneration = true
                KarenLlama.cancel()
                markCancelling()
                webConsentGate?.complete(false)
                webConsentGate = null
                webConsentPreview = null
            },
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
            val tokens = replies.sumOf { it.tokens }
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
                        KV("Tokens used", "≈$tokens tok")
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

        // New-chat transition animation overlay — ripple rings + dots.
        androidx.compose.animation.AnimatedVisibility(
            visible = resettingNewChat,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(250)
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(250)
            )
        ) {
            val ripple = androidx.compose.animation.core.rememberInfiniteTransition(label = "new_chat_ripple")
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(128.dp)
                    ) {
                        repeat(3) { i ->
                            val progress by ripple.animateFloat(
                                initialValue = 0f,
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                    animation = androidx.compose.animation.core.tween(1800, delayMillis = i * 600),
                                    repeatMode = androidx.compose.animation.core.RepeatMode.Restart
                                ),
                                label = "new_chat_ring_$i"
                            )
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .graphicsLayer(
                                        scaleX = 0.75f + 0.9f * progress,
                                        scaleY = 0.75f + 0.9f * progress,
                                        alpha = (1f - progress) * 0.55f
                                    )
                                    .border(2.dp, colors.accentGreen, androidx.compose.foundation.shape.CircleShape)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(colors.accentGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "New chat", tint = Color.White, modifier = Modifier.size(34.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    ThinkingIndicator(label = "Starting a fresh chat")
                }
            }
        }
    }
}
