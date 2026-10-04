package com.karen.ui

import androidx.compose.animation.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.rememberVoiceStt
import kotlinx.coroutines.launch

sealed class ChatItem {
    data class User(val id: String, val text: String) : ChatItem()
    data class Assistant(
        val id: String,
        val thought: String? = null,
        val toolCall: String? = null,
        val text: String,
        val showCalendarAction: Boolean = false,
        val codeJson: String? = null
    ) : ChatItem()
}

@Composable
fun ChatScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToVoice: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf("Karen 4B") }
    var showModelSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }

    val voiceStt = rememberVoiceStt(
        onResult = { speechText ->
            input = if (input.isBlank()) speechText else "$input $speechText"
        }
    )

    val messages = remember {
        mutableStateListOf<ChatItem>(
            ChatItem.User(
                id = "1",
                text = "Can you inspect my Timetable_Sem5.pdf and check when my next Lab exam is scheduled, then add a reminder?"
            ),
            ChatItem.Assistant(
                id = "2",
                thought = "• Queried SQLite vector table for \"Lab exam semester timetable\"\n• Extracted page 3, section 4.2 of Timetable_Sem5.pdf (Cosine sim: 0.941)\n• Detected: \"Distributed Systems Lab Exam — Thu, Oct 24 — 02:00 PM\"\n• Formulated on-device Intent for Calendar Provider with 0 network egress.",
                toolCall = "rag_search(doc=\"Timetable_Sem5.pdf\") · 0.12s",
                text = "According to page 3 of your semester timetable, your next lab exam is **Distributed Systems Lab** on **Thursday, October 24th at 2:00 PM – 5:00 PM** in **Computing Complex Lab 4B**.\n\nI've prepared the on-device calendar event above. Confirm to commit it directly to your system database without any cloud sync.",
                showCalendarAction = true,
                codeJson = "{\n  \"event\": \"Distributed Systems Lab Exam\",\n  \"start\": \"2026-10-24T14:00:00\",\n  \"end\": \"2026-10-24T17:00:00\",\n  \"location\": \"Hardware Lab 4B\",\n  \"source_doc\": \"Timetable_Sem5.pdf\",\n  \"privacy\": \"air-gapped-local\"\n}"
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
                onNewChatClick = {
                    messages.clear()
                    messages.add(
                        ChatItem.Assistant(
                            id = "welcome",
                            text = "Hello Alex! I am Karen, your sovereign on-device assistant running locally on your hardware. How can I help you today?"
                        )
                    )
                },
                onMoreClick = { showAttachmentSheet = true }
            )

            // Chat Messages Stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(messages, key = { when (it) { is ChatItem.User -> it.id; is ChatItem.Assistant -> it.id } }) { item ->
                    when (item) {
                        is ChatItem.User -> {
                            UserMessageBubble(text = item.text)
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

        // Floating Bottom Composer Dock
        ChatGPTFloatingComposer(
            value = input,
            onValueChange = { input = it },
            onSend = {
                if (input.isNotBlank()) {
                    val userText = input.trim()
                    input = ""
                    val newId = System.currentTimeMillis().toString()
                    messages.add(ChatItem.User(id = "user_$newId", text = userText))

                    // Simulate Karen on-device response
                    coroutineScope.launch {
                        listState.animateScrollToItem(messages.size - 1)
                        kotlinx.coroutines.delay(400)
                        messages.add(
                            ChatItem.Assistant(
                                id = "asst_$newId",
                                thought = "• Evaluated user query via on-device 4B model\n• 0 bytes transmitted externally\n• Executed local inference in 184ms",
                                text = "I received your request: \"$userText\". Operating in sovereign air-gapped mode on your local hardware.",
                                toolCall = "local_executor() · 0.05s"
                            )
                        )
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
            },
            onAttachClick = { showAttachmentSheet = true },
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
                onSelectOption = { _ -> }
            )
        }
    }
}
