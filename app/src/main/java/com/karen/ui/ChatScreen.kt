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
    data class User(val id: String, val text: String, val attachments: List<Attachment> = emptyList()) : ChatItem()
    data class Assistant(
        val id: String,
        val thought: String? = null,
        val toolCall: String? = null,
        val text: String,
        val showCalendarAction: Boolean = false,
        val codeJson: String? = null
    ) : ChatItem()
}

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

/**
 * Content-window scaling (Settings → Content Window, up to 16k). Larger
 * windows keep more turns and more project text in front of the model.
 */
fun historyTurnsFor(windowTokens: Int): Int = when {
    windowTokens >= 16384 -> 60
    windowTokens >= 8192 -> 40
    else -> 20
}

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
            isGenerating -> { cancelGeneration = true; KarenLlama.cancel() }
            else -> onNavigateToHome()
        }
    }
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
    // Default to the first downloaded weight; selection lists installed-only.
    var selectedModel by remember { mutableStateOf(UserPrefs.models(context).firstOrNull { !isCloudProviderName(it) } ?: modelCatalog.first().name) }
    // Auto routing: pick the best installed/connected model per message.
    var autoRoute by remember { mutableStateOf(UserPrefs.autoRoute(context)) }
    var lastRouted by remember { mutableStateOf<String?>(null) }

    /** Manual model, or the routed pick when Auto is on. */
    fun effectiveModel(prompt: String): String {
        if (!autoRoute) return selectedModel
        val locals = UserPrefs.models(context).filter { !isCloudProviderName(it) }
        val keyed = cloudProviders.filter { UserPrefs.apiKey(context, it.id).isNotBlank() }
        val decision = routeModel(prompt, locals, keyed, UserPrefs.autoCloud(context))
        if (decision != null) {
            if (decision.modelName != lastRouted) {
                lastRouted = decision.modelName
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
                try { listState.scrollToItem(messages.size - 1) } catch (_: Exception) {}
            }
            kotlinx.coroutines.delay(effortDelayMs(effort))
        }
    }

    /** End-of-generation after Stop: keep partial text, drop bare placeholders. */
    suspend fun cancelSettle(id: String) {
        if (!streamingContent) {
            messages.removeAll { (it as? ChatItem.Assistant)?.id == id }
        } else {
            persist()
        }
        isGenerating = false
        streamingId = null
        genLive = null
        listState.animateScrollToItem(messages.size - 1)
    }

    /** End-of-generation on success: stamp stats and archive. */
    suspend fun completeSettle(finalLen: Int) {
        setStats(finalLen)
        persist()
        isGenerating = false
        streamingId = null
        genLive = null
        listState.animateScrollToItem(messages.size - 1)
    }

    /** Failed turn: show the error inline in the provisional bubble. */
    suspend fun errorSettle(id: String, text: String) {
        streamingContent = true
        setStreamingText(id, text)
        persist()
        isGenerating = false
        streamingId = null
        genLive = null
        listState.animateScrollToItem(messages.size - 1)
    }

    // Open a saved conversation from the history list.
    LaunchedEffect(openConversationId) {
        val id = openConversationId ?: return@LaunchedEffect
        persist()
        val loaded = ChatHistoryStore.loadMessages(context, id)
        messages.clear()
        messages.addAll(loaded)
        conversationId = id
        onConversationOpened()
    }

    // Fresh chat requested from the drawer while already on this screen.
    LaunchedEffect(newChatSignal) {
        if (newChatSignal == 0) return@LaunchedEffect
        persist()
        messages.clear()
        conversationId = System.currentTimeMillis().toString()
    }

    // Live elapsed/token readout above the composer while a reply streams in.
    LaunchedEffect(isGenerating) {
        if (!isGenerating) return@LaunchedEffect
        while (true) {
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
                        conversationId = System.currentTimeMillis().toString()
                        kotlinx.coroutines.delay(100)
                        resettingNewChat = false
                    }
                },
                moreActions = listOf(
                    Triple("Search in chat", Icons.Default.Search) {
                        android.widget.Toast.makeText(context, "Search in chat", android.widget.Toast.LENGTH_SHORT).show()
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
                        conversationId = System.currentTimeMillis().toString()
                        android.widget.Toast.makeText(context, "Chat archived to history", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    Triple("Temporary chat", Icons.Default.AutoAwesome) {
                        if (!temporaryChat) {
                            persist()
                            messages.clear()
                            conversationId = System.currentTimeMillis().toString()
                        }
                        temporaryChat = !temporaryChat
                        android.widget.Toast.makeText(context, if (temporaryChat) "Temporary chat on — not saved" else "Temporary chat off", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    Triple("Clear conversation", Icons.Default.Delete) {
                        ChatHistoryStore.deleteConversation(context, conversationId)
                        messages.clear()
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
                                onEdit = { edited ->
                                    val idx = messages.indexOfFirst { (it as? ChatItem.User)?.id == item.id }
                                    if (idx >= 0 && !isGenerating) {
                                        messages[idx] = item.copy(text = edited)
                                        persist()
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
                                MessageActionBar(messageText = item.text)
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

        // Slim stop pill while generating (the reply itself streams inline above).
        if (isGenerating) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                    .clickable {
                        cancelGeneration = true
                        KarenLlama.cancel()
                        webConsentGate?.complete(false)
                        webConsentGate = null
                        webConsentPreview = null
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(color = colors.accentGreen, strokeWidth = 2.dp, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stop", color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
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
            ChatGPTFloatingComposer(
                value = input,
                onValueChange = { input = it },
                onSend = {
                val userText = input.trim()
                if (userText.isNotBlank() || attachments.isNotEmpty()) {
                    input = ""
                    val sentAttachments = attachments
                    attachments = emptyList()
                    val newId = System.currentTimeMillis().toString()
                    messages.add(ChatItem.User(id = "user_$newId", text = userText.ifBlank { sentAttachments.joinToString(", ") { it.name } }, attachments = sentAttachments))

                    // Live cloud model when one is selected (key stored in Model Manager),
                    // real on-device inference for downloaded weights, else the mock.
                    // Auto routing swaps in the best model for this prompt.
                    val sendModel = effectiveModel(userText)
                    val cloud = findCloudProviderByName(sendModel)
                    val cloudKey = cloud?.let { UserPrefs.apiKey(context, it.id) }.orEmpty()
                    val weightFile = if (cloud == null) ModelDownloader.weightFileFor(context, sendModel) else null

                    coroutineScope.launch {
                        cancelGeneration = false
                        streamStartMs = System.currentTimeMillis()
                        streamingContent = false
                        genLive = null
                        val aid = "asst_$newId"
                        messages.add(ChatItem.Assistant(id = aid, text = ""))
                        streamingId = aid
                        isGenerating = true
                        listState.animateScrollToItem(messages.size - 1)
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
                            setStreamingText(aid, "Contacting ${cloud.name}…")
                            try {
                                val window = UserPrefs.contextTokens(context)
                                val limit = historyTurnsFor(window)
                                val base = messages.mapNotNull { item ->
                                    when (item) {
                                        is ChatItem.User -> "user" to item.text
                                        is ChatItem.Assistant -> if (item.id == aid) null else "assistant" to item.text
                                    }
                                }.takeLast(limit)
                                val history = if (web != null) {
                                    (base + ("user" to "Use these fresh web results if relevant:\n$web")).takeLast(limit)
                                } else base
                                // Recursively continue cut-off replies: when the model hits
                                // its output cap it stops mid-answer, so keep asking for
                                // the rest instead of making the user type "continue".
                                val fullReply = StringBuilder()
                                var roundHistory = history
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
                        } else if (weightFile != null) {
                            // Real on-device inference through the bundled llama.cpp core.
                            if (!KarenLlama.ready) {
                                errorSettle(aid, "Local runtime failed to load on this device.")
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
                                    val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        KarenLlama.ensureLoaded(weightFile, sendModel, window, threads)
                                    }
                                    if (!loaded) {
                                        if (cancelGeneration) cancelSettle(aid)
                                        else errorSettle(aid, "Could not load $sendModel into RAM.")
                                    } else if (cancelGeneration) {
                                        cancelSettle(aid)
                                    } else {
                                        setStreamingText(aid, "Thinking on-device…")
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
                                        val reply = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            KarenLlama.complete(
                                                "You are Karen, a concise on-device assistant." +
                                                    if (isReasoningModel(sendModel)) reasoningEffortHint(effort) else "",
                                                trimmedPairs.map { it.first }.toTypedArray(),
                                                trimmedPairs.map { it.second }.toTypedArray(),
                                                maxTokensFor(effort)
                                            )
                                        }
                                        if (cancelGeneration) {
                                            cancelSettle(aid)
                                        } else if (reply.isNotBlank()) {
                                            playOut(aid, reply)
                                            if (cancelGeneration) cancelSettle(aid) else completeSettle(reply.length)
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
                            val fullText = if (web != null) {
                                "Fresh web results:\n$web"
                            } else {
                                "No data found — connect a local model in Model Manager.$fileNote"
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
            },
            onAttachClick = { showAttachmentSheet = true },
            attachmentsPreview = attachments,
            onRemoveAttachment = { index -> attachments = attachments.filterIndexed { i, _ -> i != index } },
            onMicClick = { voiceStt.toggle() },
            onVoiceModeClick = onNavigateToVoice,
            isListening = voiceStt.state.isListening,
            listeningText = voiceStt.state.partialText,
            modifier = Modifier.fillMaxWidth()
        )
        }

        // Model Selector Bottom Sheet
        if (showModelSheet) {
            ModelSelectorSheet(
                selectedModel = selectedModel,
                onSelectModel = {
                    selectedModel = it
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
