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

    // System back button: dismiss open sheets first, otherwise route to Home
    androidx.activity.compose.BackHandler {
        when {
            showModelSheet -> showModelSheet = false
            showAttachmentSheet -> showAttachmentSheet = false
            else -> onNavigateToHome()
        }
    }
    var resettingNewChat by remember { mutableStateOf(false) }
    var temporaryChat by remember { mutableStateOf(false) }
    val karenCtx = androidx.compose.ui.platform.LocalContext.current
    var effort by remember { mutableStateOf(UserPrefs.defaultEffort(karenCtx)) }
    var streamingText by remember { mutableStateOf<String?>(null) }
    var cancelGeneration by remember { mutableStateOf(false) }
    var streamStartMs by remember { mutableStateOf(0L) }

    val voiceStt = rememberVoiceStt(
        onResult = { speechText ->
            input = if (input.isBlank()) speechText else "$input $speechText"
        }
    )

    // ---------- File upload / attachments ----------
    val context = LocalContext.current
    // Default to the first downloaded weight; selection lists installed-only.
    var selectedModel by remember { mutableStateOf(UserPrefs.models(context).firstOrNull { !isCloudProviderName(it) } ?: "Karen 4B") }
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
        ChatHistoryStore.saveConversation(context, conversationId, messages.toList())
        onHistoryChanged()
    }

    /** Typing-effect playback shared by mock and live-cloud replies. */
    suspend fun playOut(text: String) {
        cancelGeneration = false
        streamStartMs = System.currentTimeMillis()
        streamingText = ""
        for (idx in text.indices) {
            if (cancelGeneration) break
            streamingText = text.substring(0, idx + 1)
            kotlinx.coroutines.delay(effortDelayMs(effort))
        }
        streamingText = null
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ChatGPT Top App Bar
            ChatGPTTopAppBar(
                selectedModel = selectedModel,
                onMenuClick = onOpenDrawer,
                onModelClick = { showModelSheet = true },
                onBackClick = onNavigateToHome,
                effort = effort,
                efforts = listOf("Low", "Medium", "High", "Max", "Extreme", "Theme"),
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
                            UserMessageBubble(text = item.text, attachments = item.attachments)
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

                                // Collapsible Reasoning / Thought Box (o1/o3 style)
                                if (item.thought != null) {
                                    ThoughtAccordion(
                                        thoughtDuration = "Thought for 3 seconds",
                                        content = item.thought,
                                        initialExpanded = false
                                    )
                                    Spacer(Modifier.height(8.dp))
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

                                // Assistant Body Typography
                                Text(
                                    text = item.text,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    lineHeight = 22.sp
                                )

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

        // Live streaming response bubble (while generating)
        if (streamingText != null) {
            val tokens = streamingText!!.length / 4
            val elapsedSec = ((System.currentTimeMillis() - streamStartMs) / 1000).coerceAtLeast(1)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(bottom = 96.dp, start = 14.dp, end = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Text(streamingText!!, color = colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(4.dp))
                Text("~${elapsedSec}s to respond · ~$tokens tokens", color = colors.textMuted, fontSize = 10.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { cancelGeneration = true }) {
                        Text("Stop generating", color = colors.accentRed, fontSize = 12.sp)
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
                    val newId = System.currentTimeMillis().toString()
                    messages.add(ChatItem.User(id = "user_$newId", text = userText.ifBlank { sentAttachments.joinToString(", ") { it.name } }, attachments = sentAttachments))

                    // Live cloud model when one is selected (key stored in Model Manager),
                    // otherwise the local mock response.
                    val cloud = findCloudProviderByName(selectedModel)
                    val cloudKey = cloud?.let { UserPrefs.apiKey(context, it.id) }.orEmpty()

                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                        if (cloud != null && cloudKey.isNotBlank()) {
                            cancelGeneration = false
                            streamStartMs = System.currentTimeMillis()
                            streamingText = "Contacting ${cloud.name}…"
                            try {
                                val history = messages.mapNotNull { item ->
                                    when (item) {
                                        is ChatItem.User -> "user" to item.text
                                        is ChatItem.Assistant -> "assistant" to item.text
                                    }
                                }.takeLast(20)
                                val reply = cloud.complete(cloudKey, history, maxTokensFor(effort))
                                if (!cancelGeneration) {
                                    playOut(reply)
                                    if (!cancelGeneration) {
                                        messages.add(
                                            ChatItem.Assistant(
                                                id = "asst_$newId",
                                                text = reply
                                            )
                                        )
                                        persist()
                                    }
                                }
                            } catch (e: CloudApiException) {
                                messages.add(
                                    ChatItem.Assistant(
                                        id = "asst_${newId}_err",
                                        text = "⚠ ${cloud.name} error (HTTP ${e.status}): ${e.message}"
                                    )
                                )
                                persist()
                            } catch (e: Exception) {
                                messages.add(
                                    ChatItem.Assistant(
                                        id = "asst_${newId}_err",
                                        text = "⚠ Could not reach ${cloud.name} — check internet and your API key. (${e.message})"
                                    )
                                )
                                persist()
                            }
                            streamingText = null
                            listState.animateScrollToItem(messages.size - 1)
                        } else {
                            // Simulate Karen on-device response (token streaming)
                            kotlinx.coroutines.delay(400)
                            val fileNote = if (sentAttachments.isNotEmpty()) {
                                val names = sentAttachments.joinToString(", ") { it.name }
                                " I received ${sentAttachments.size} file(s): $names. Files are stored locally in the vault — 0 bytes sent externally."
                            } else ""
                            val fullText = "No data found — connect a local model in Model Manager.$fileNote"
                            playOut(fullText)
                            if (!cancelGeneration) {
                                messages.add(
                                    ChatItem.Assistant(
                                        id = "asst_$newId",
                                        text = fullText
                                    )
                                )
                                persist()
                            }
                            streamingText = null
                            listState.animateScrollToItem(messages.size - 1)
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
        )

        // Model Selector Bottom Sheet
        if (showModelSheet) {
            ModelSelectorSheet(
                selectedModel = selectedModel,
                onSelectModel = { selectedModel = it },
                onDismiss = { showModelSheet = false },
                onOpenModelManager = onNavigateToModelManager
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

        // New-chat transition animation overlay
        androidx.compose.animation.AnimatedVisibility(visible = resettingNewChat) {
            val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "new_chat_pulse")
                .animateFloat(
                    initialValue = 0.92f,
                    targetValue = 1.08f,
                    animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                        animation = androidx.compose.animation.core.tween(720, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
                    ),
                    label = "new_chat_pulse_anim"
                )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(colors.accentGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = "New chat", tint = Color.White, modifier = Modifier.size(34.dp).graphicsLayer(scaleX = pulse, scaleY = pulse))
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Starting a fresh chat…", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(color = colors.accentGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
