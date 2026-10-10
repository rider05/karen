package com.karen.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
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
import kotlinx.coroutines.CompletableDeferred
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
        val showCalendarAction: Boolean = false,
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

/** An attachment picked from the device, pending to be added to the chat. */
data class Attachment(val name: String, val sizeBytes: Long, val mime: String? = null)

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
    var genStats by remember { mutableStateOf<String?>(null) }
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
            .filter { !isCloudProviderName(it) && ModelDownloader.isRunnableWeight(context, it) }
        val keyed = cloudProviders.filter { UserPrefs.apiKey(context, it.id).isNotBlank() }
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
                Attachment(name = name, sizeBytes = size, mime = mime)
            } catch (_: Exception) { null }
        }
        attachments = attachments + picked
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

    fun setStats(finalLen: Int) {
        val elapsed = (System.currentTimeMillis() - streamStartMs) / 1000.0
        val tok = finalLen / 4
        val rate = if (elapsed > 0) tok / elapsed else 0.0
        genStats = "%.1fs · ~%d tok · %.1f tok/s".format(elapsed, tok, rate)
        genLive = null
    }

    /** Typing-effect playback writing straight into the chat-list message. */
    suspend fun playOut(id: String, text: String) {
        streamingContent = text.isNotEmpty()
        var n = 0
        for (idx in text.indices) {
            if (cancelGeneration) break
            setStreamingText(id, text.substring(0, idx + 1))
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
    suspend fun completeSettle(finalLen: Int) {
        streamingId?.let { stampTook(it, finalLen / 4) }
        setStats(finalLen)
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

    /** Prompt shaping: explainer guide and/or liked-style memory. */
    fun shapingSuffix(prompt: String): String {
        val parts = listOf(
            if (wantsExplainer(prompt, context)) EXPLAINER_STYLE_GUIDE else "",
            StyleMemory.currentHint(context)
        ).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "" else " " + parts.joinToString("\n\n")
    }

    /** Best keyed cloud for slow-local fallback, or null when offline/unkeyed. */
    fun pickFallbackCloud(prompt: String): CloudProvider? {
        if (!device.networkUp) return null
        val keyed = cloudProviders.filter { UserPrefs.apiKey(context, it.id).isNotBlank() }
        if (keyed.isEmpty()) return null
        val intent = detectIntent(prompt.ifBlank { "chat" })
        return keyed.maxByOrNull { scoreCloud(it.id, intent) }
    }

    /**
     * One full cloud turn for an already-listed provisional bubble [aid].
     * Shared by direct cloud routes and slow-local fallback so the flow
     * never breaks mid-reply.
     */
    suspend fun cloudTurn(
        cloud: CloudProvider,
        cloudKey: String,
        aid: String,
        userText: String,
        web: String?,
        regenerateHint: String?
    ) {
        setStreamingText(aid, "Contacting ${cloud.name}…")
        // Stamp the answering model (no-op on direct routes, correct on fallback).
        val mi = messages.indexOfFirst { (it as? ChatItem.Assistant)?.id == aid }
        if (mi >= 0) {
            (messages[mi] as? ChatItem.Assistant)?.let { messages[mi] = it.copy(model = cloud.name) }
        }
        try {
            // Cloud history uses the provider window, not the local setting.
            val limit = cloudHistoryTurns()
            val base = messages.mapNotNull { item ->
                when (item) {
                    is ChatItem.User -> "user" to item.text
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
                val res = cloud.completeResult(
                    cloudKey,
                    roundHistory,
                    maxTokensFor(effort),
                    thinkingBudget = if (cloud.reasoning) thinkingBudgetFor(effort) else null,
                    historyLimit = limit,
                    model = UserPrefs.apiModel(context, cloud.id).ifBlank { null }
                )
                if (cancelGeneration) break
                fullReply.append(res.text)
                cut = res.truncated && res.text.isNotBlank()
                rounds++
                if (cut && rounds < 4) {
                    roundHistory = (roundHistory + ("assistant" to res.text) + ("user" to "Continue exactly where you stopped. Output only the continuation — no recap, no repetition.")).takeLast(limit)
                }
            }
            val reply = fullReply.toString()
            if (cancelGeneration) {
                cancelSettle(aid)
            } else {
                playOut(aid, reply)
                if (cancelGeneration) cancelSettle(aid) else completeSettle(reply.length)
            }
        } catch (e: CloudApiException) {
            errorSettle(aid, "⚠ ${cloud.name} error (HTTP ${e.status}): ${e.message}")
        } catch (e: Exception) {
            errorSettle(aid, "⚠ Could not reach ${cloud.name} — check internet and your API key. (${e.message})")
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
        val cloud = findCloudProviderByName(sendModel)
        val cloudKey = cloud?.let { UserPrefs.apiKey(context, it.id) }.orEmpty()
        val weightFile = if (cloud == null) ModelDownloader.weightFileFor(context, sendModel) else null

        coroutineScope.launch {
            cancelGeneration = false
            streamStartMs = System.currentTimeMillis()
            streamingContent = false
            genLive = null
            genStats = null
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
                                is ChatItem.User -> "user" to item.text
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
                        // Slow-local guard: load+generation share one budget, then
                        // the turn continues on the best keyed cloud uninterrupted.
                        data class LocalOut(val loaded: Boolean, val reply: String)
                        val out = withTimeoutOrNull(LOCAL_TURN_TIMEOUT_MS) {
                            val l = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                KarenLlama.ensureLoaded(weightFile, sendModel, window, threads)
                            }
                            var r = ""
                            if (l && !cancelGeneration) {
                                setStreamingText(aid, "Thinking on-device…")
                                r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    KarenLlama.complete(
                                        "You are Karen, a concise on-device assistant." +
                                            if (isReasoningModel(sendModel)) reasoningEffortHint(effort) else "" +
                                            shaping,
                                        instructedPairs.map { it.first }.toTypedArray(),
                                        instructedPairs.map { it.second }.toTypedArray(),
                                        maxTokensFor(effort)
                                    )
                                }
                            }
                            LocalOut(l, r)
                        }
                        if (out == null) {
                            // Local took too long: switch to API without breaking flow.
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
                        } else if (out.reply.isNotBlank()) {
                            playOut(aid, out.reply)
                            if (cancelGeneration) cancelSettle(aid) else completeSettle(out.reply.length)
                        } else {
                            errorSettle(aid, "The model returned an empty reply.")
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
                    if (cancelGeneration) cancelSettle(aid) else completeSettle(fullText.length)
                }
            }
        }
    }

    /** Lists a user message and starts the assistant turn for it. */
    fun sendNow(userText: String, sentAttachments: List<Attachment>) {
        val newId = System.currentTimeMillis().toString()
        val body = userText.ifBlank { sentAttachments.joinToString(", ") { it.name } }
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
                onBackClick = onNavigateToHome,
                effort = if (isReasoningModel(if (autoRoute) (lastRouted ?: selectedModel) else selectedModel)) effort else null,
                efforts = listOf("Low", "Medium", "High", "Max", "Extreme", "XHigh"),
                onSelectEffort = { effort = it },
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
                        android.widget.Toast.makeText(context, "Custom instructions", android.widget.Toast.LENGTH_SHORT).show()
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
                        if (!temporaryChat) {
                            persist()
                            messages.clear()
                        queue.clear()
                            conversationId = System.currentTimeMillis().toString()
                        }
                        temporaryChat = !temporaryChat
                        android.widget.Toast.makeText(context, if (temporaryChat) "Temporary chat on — not saved" else "Temporary chat off", android.widget.Toast.LENGTH_SHORT).show()
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
                contentPadding = PaddingValues(top = 0.dp, bottom = 150.dp),
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
                                    ToolExecutionPill(toolName = item.toolCall)
                                    Spacer(Modifier.height(8.dp))
                                }

                                // Action Confirmation Card
                                if (item.showCalendarAction) {
                                    ActionConfirmationCard(
                                        title = "Schedule Calendar Reminder",
                                        subtitle = "Distributed Systems Lab Exam\nThu, Oct 24 · 02:00 PM – 05:00 PM · Hardware Lab 4B"
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
                .height(120.dp)
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

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
        ) {
            // Last-response stats: small readout pinned top-left above the composer.
            val statsLine = genLive ?: genStats
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
            ChatGPTFloatingComposer(
                value = input,
                onValueChange = { input = it },
                onSend = {
                val userText = input.trim()
                if (userText.isNotBlank() || attachments.isNotEmpty()) {
                    input = ""
                    val sentAttachments = attachments
                    attachments = emptyList()
                    // Model answers (fresh sends, edited resends, regenerates) run here.
                    sendNow(userText, sentAttachments)
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
