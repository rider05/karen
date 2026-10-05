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

@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToVoice: () -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("Karen 4B") }
    var showModelSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var resettingNewChat by remember { mutableStateOf(false) }

    val voiceStt = rememberVoiceStt(
        onResult = { speechText ->
            input = if (input.isBlank()) speechText else "$input $speechText"
        }
    )

    // ---------- File upload / attachments ----------
    val context = LocalContext.current
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
        mutableStateListOf<ChatItem>(
            ChatItem.Assistant(
                id = "welcome",
                text = "Hello Alex! I am Karen, your sovereign on-device assistant running locally on your hardware. How can I help you today?"
            )
        )
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
                onNewChatClick = {
                    resettingNewChat = true
                    coroutineScope.launch {
                        kotlinx.coroutines.delay(700)
                        messages.clear()
                        messages.add(
                            ChatItem.Assistant(
                                id = "welcome",
                                text = "Hello Alex! I am Karen, your sovereign on-device assistant running locally on your hardware. How can I help you today?"
                            )
                        )
                        kotlinx.coroutines.delay(100)
                        resettingNewChat = false
                    }
                },
                onMoreClick = { showMoreMenu = true }
            )

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
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 104.dp, start = 14.dp, end = 14.dp)
            ) {
                val suggestions = listOf("Inspect my timetable", "Summarize a document", "Set a reminder", "Open Workspace")
                items(suggestions.size) { i ->
                    Text(
                        suggestions[i],
                        color = colors.textPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                            .clickable { input = suggestions[i] }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
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

                    // Simulate Karen on-device response
                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                        kotlinx.coroutines.delay(400)
                        val fileNote = if (sentAttachments.isNotEmpty()) {
                            val names = sentAttachments.joinToString(", ") { it.name }
                            " I received ${sentAttachments.size} file(s): $names. Files are stored locally in the vault — 0 bytes sent externally."
                        } else ""
                        messages.add(
                            ChatItem.Assistant(
                                id = "asst_$newId",
                                thought = "• Evaluated user query via on-device 4B model\n• 0 bytes transmitted externally\n• Executed local inference in 184ms",
                                text = "I received your request: \"$userText\". Operating in sovereign air-gapped mode on your local hardware.$fileNote",
                                toolCall = "local_executor() · 0.05s"
                            )
                        )
                        listState.animateScrollToItem(messages.size - 1)
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
                onDismiss = { showModelSheet = false }
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

        // More options themed dropdown (theme-following, with icons + separators)
        if (showMoreMenu) {
            ThemedMoreMenu(
                onDismiss = { showMoreMenu = false },
                items = listOf(
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
                        android.widget.Toast.makeText(context, "Chat archived", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    Triple("Clear conversation", Icons.Default.Delete) {
                        messages.clear()
                        messages.add(
                            ChatItem.Assistant(
                                id = "welcome",
                                text = "Hello Alex! I am Karen, your sovereign on-device assistant running locally on your hardware. How can I help you today?"
                            )
                        )
                    },
                    Triple("Report a problem", Icons.Default.Flag) {
                        android.widget.Toast.makeText(context, "Report submitted", android.widget.Toast.LENGTH_SHORT).show()
                    }
                )
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
