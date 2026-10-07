package com.karen.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.formatSize

// Legacy colors kept for backward compatibility, mapped to active theme
val KBg get() = KarenThemeState.colors.background
val KCard get() = KarenThemeState.colors.surface
val KDeep get() = KarenThemeState.colors.background
val KLine get() = KarenThemeState.colors.border
val KText get() = KarenThemeState.colors.textPrimary
val KMuted get() = KarenThemeState.colors.textMuted
val KBody get() = KarenThemeState.colors.textSecondary
val KAccent get() = KarenThemeState.colors.accentGreen
val KCyan get() = KarenThemeState.colors.accentBlue
val KErr get() = KarenThemeState.colors.accentRed

/**
 * Single themed palette for every text input. Material3 defaults follow the
 * (unset) MaterialTheme light scheme — near-black text that vanishes on dark
 * surfaces — so all OutlinedTextFields must use this.
 */
@Composable
fun karenFieldColors(colors: KarenColors): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        disabledTextColor = colors.textMuted,
        errorTextColor = colors.accentRed,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        errorContainerColor = Color.Transparent,
        cursorColor = colors.accentGreen,
        errorCursorColor = colors.accentRed,
        focusedBorderColor = colors.accentGreen,
        unfocusedBorderColor = colors.border,
        disabledBorderColor = colors.border.copy(alpha = 0.5f),
        errorBorderColor = colors.accentRed,
        focusedLabelColor = colors.accentGreen,
        unfocusedLabelColor = colors.textMuted,
        disabledLabelColor = colors.textMuted,
        errorLabelColor = colors.accentRed,
        focusedPlaceholderColor = colors.textMuted,
        unfocusedPlaceholderColor = colors.textMuted,
        disabledPlaceholderColor = colors.textMuted,
        errorPlaceholderColor = colors.accentRed,
        focusedLeadingIconColor = colors.textSecondary,
        unfocusedLeadingIconColor = colors.textMuted,
        focusedTrailingIconColor = colors.textSecondary,
        unfocusedTrailingIconColor = colors.textMuted,
        errorTrailingIconColor = colors.accentRed,
        focusedSupportingTextColor = colors.textMuted,
        unfocusedSupportingTextColor = colors.textMuted
    )

/**
 * Per-effort accent color. Theme-aware: each level has its own hue that
 * stays readable in both dark and light themes.
 */
fun effortColor(name: String, colors: KarenColors): Color {
    return when (name) {
        "Low" -> colors.accentGreen
        "Medium" -> colors.accentBlue
        "High" -> colors.accentAmber
        "Max" -> Color(0xFFF97316)
        "Extreme" -> colors.accentRed
        "Theme" -> if (colors.isDark) Color(0xFFC084FC) else Color(0xFF7E22CE)
        else -> colors.textMuted
    }
}

/**
 * Standing convention: every screen stays in-app on system back — it never
 * exits to the phone home. Screens must call this with their Home route and
 * consume the press first when a sheet/dialog is open or an inner level
 * (e.g. open project, selected tab) can be popped.
 *
 * Pair with `onBackClick = onNavigateToHome` on [ChatGPTTopAppBar] and wire
 * `onNavigateToHome = { currentScreen = "Home" }` in KarenApp.
 *
 * Example:
 * ```
 * KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
 *     if (showSheet) { showSheet = false; true } else false
 * }
 * ```
 */
@Composable
fun KarenHomeBackHandler(
    onNavigateToHome: () -> Unit,
    onBackPressed: () -> Boolean = { false }
) {
    androidx.activity.compose.BackHandler {
        if (!onBackPressed()) onNavigateToHome()
    }
}

/**
 * Shared header for chat and the tool screens.
 *
 * Back/menu pinned left, model + effort pills in the true center, chat
 * actions pinned right. `selectedModel` doubles as the header's status pill
 * label. The overflow menu is a Popup anchored to the more button, so it
 * never disturbs header layout.
 */
@Composable
fun ChatGPTTopAppBar(
    selectedModel: String = "Karen 4B",
    onMenuClick: () -> Unit = {},
    onModelClick: () -> Unit = {},
    onNewChatClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    onBackClick: (() -> Unit)? = null,
    onMoreOption: (String) -> Unit = {},
    moreActions: List<Triple<String, ImageVector, () -> Unit>> = emptyList(),
    effort: String? = null,
    efforts: List<String> = emptyList(),
    onSelectEffort: (String) -> Unit = {},
    eyebrow: String = "Dialogue"
) {
    val colors = LocalKarenColors.current
    var showMoreMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .statusBarsPadding()
                .height(56.dp)
                .padding(start = 6.dp, end = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            // Left: navigation affordances
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                if (onBackClick != null) {
                    KIconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                KIconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open navigation",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Center: model + effort selectors
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.Center)
            ) {
                // Model / surface status pill
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.surface)
                        .border(1.dp, colors.accentGreen.copy(alpha = 0.32f), CircleShape)
                        .clickable { onModelClick() }
                        .padding(start = 9.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(colors.accentSuccess)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = selectedModel,
                        color = colors.textPrimary,
                        fontFamily = LocalKarenType.current.mono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Select model",
                        tint = colors.textMuted,
                        modifier = Modifier.size(15.dp)
                    )
                }

                if (effort != null && efforts.isNotEmpty()) {
                    val effortTint = effortColor(effort, colors)
                    Spacer(Modifier.width(4.dp))
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(effortTint.copy(alpha = 0.12f))
                            .border(1.dp, effortTint.copy(alpha = 0.45f), CircleShape)
                            .clickable { effortMenu = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(effortTint)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(effort, color = effortTint, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Select effort",
                            tint = effortTint,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = effortMenu,
                        onDismissRequest = { effortMenu = false },
                        modifier = Modifier.background(colors.cardBackground)
                    ) {
                        efforts.forEach { e ->
                            val tint = effortColor(e, colors)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(tint)
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(e, color = if (e == effort) tint else colors.textPrimary)
                                    }
                                },
                                onClick = {
                                    onSelectEffort(e)
                                    effortMenu = false
                                }
                            )
                        }
                    }
                }
            }

            // Right: chat actions — the overflow menu is anchored here so its
            // popup never participates in header layout (a fillMaxSize overlay
            // inside the header Column would expand the header and blank content).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                KIconButton(onClick = onNewChatClick) {
                    Icon(
                        imageVector = Icons.Default.AddCircle,
                        contentDescription = "New Chat",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Box {
                    KIconButton(onClick = {
                        if (moreActions.isNotEmpty()) showMoreMenu = true else onMoreClick()
                    }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMoreMenu && moreActions.isNotEmpty(),
                        onDismissRequest = { showMoreMenu = false },
                        modifier = Modifier
                            .width(260.dp)
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                    ) {
                        moreActions.forEachIndexed { idx, action ->
                            DropdownMenuItem(
                                text = {
                                    Text(action.first, color = colors.textPrimary, fontSize = 14.sp)
                                },
                                leadingIcon = {
                                    Icon(
                                        action.second,
                                        contentDescription = null,
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    action.third.invoke()
                                }
                            )
                            if (idx != moreActions.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .align(Alignment.CenterHorizontally),
                                    thickness = 0.75.dp,
                                    color = colors.border.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            colors.accentGreen.copy(alpha = 0.35f),
                            colors.accentGreen.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

/**
 * Collapsible Reasoning / Thought Box (like ChatGPT o1/o3)
 */
@Composable
fun ThoughtAccordion(
    thoughtDuration: String = "Thought for 3 seconds",
    content: String,
    initialExpanded: Boolean = false
) {
    val colors = LocalKarenColors.current
    var expanded by remember { mutableStateOf(initialExpanded) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(colors.accentBlue.copy(alpha = pulseAlpha))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = thoughtDuration,
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = "Toggle thoughts",
                tint = colors.textMuted,
                modifier = Modifier.size(16.dp)
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = content,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Tool Call Status Chip
 */
@Composable
fun ToolExecutionPill(
    toolName: String,
    statusText: String = "0.12s"
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .clip(KR.chip)
            .background(colors.surface)
            .border(1.dp, colors.accentSuccess.copy(alpha = 0.30f), KR.chip)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.accentSuccess)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$toolName · $statusText",
            color = colors.textSecondary,
            fontSize = 11.5.sp,
            fontFamily = LocalKarenType.current.mono
        )
    }
}

/**
 * Action Confirmation Card (e.g. Schedule Reminder / Run command)
 */
@Composable
fun ActionConfirmationCard(
    title: String,
    subtitle: String,
    confirmLabel: String = "Confirm & Add",
    dismissLabel: String = "Dismiss",
    onConfirm: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    var isConfirmed by remember { mutableStateOf(false) }
    var isDismissed by remember { mutableStateOf(false) }

    if (isDismissed) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceHover)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Close, contentDescription = "Dismissed", tint = colors.textMuted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("Action dismissed", color = colors.textMuted, fontSize = 12.sp)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(KR.card)
            .background(colors.accentAmber.copy(alpha = 0.06f))
            .border(1.dp, colors.accentAmber.copy(alpha = 0.32f), KR.card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Event,
                contentDescription = "Event",
                tint = colors.accentAmber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            color = colors.textSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(12.dp))

        if (isConfirmed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Confirmed",
                    tint = colors.accentSuccess,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Added to local system calendar (Zero egress)",
                    color = colors.accentSuccess,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        isConfirmed = true
                        onConfirm()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentGreen,
                        contentColor = Color(0xFFF8FAFC)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(confirmLabel, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = {
                        isDismissed = true
                        onDismiss()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = colors.textSecondary
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(colors.border)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(dismissLabel, fontSize = 12.5.sp)
                }
            }
        }
    }
}

/**
 * Code Snippet Container with Header and Copy Action
 */
@Composable
fun CodeBlockView(
    language: String = "json",
    code: String,
    onCopy: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (colors.isDark) Color(0xFF0F0F11) else Color(0xFFF1F1F4))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (colors.isDark) Color(0xFF18181B) else Color(0xFFE4E4E8))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language,
                color = colors.textMuted,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        copied = true
                        clipboardManager.setText(AnnotatedString(code))
                        onCopy()
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (copied) colors.accentGreen else colors.textMuted,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (copied) "Copied" else "Copy code",
                    color = if (copied) colors.accentGreen else colors.textMuted,
                    fontSize = 11.5.sp
                )
            }
        }
        // Code content
        Text(
            text = code,
            color = colors.textPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Assistant Action Bar (Copy, Like, Dislike, Speaker, Share)
 */
@Composable
fun MessageActionBar(
    messageText: String = "",
    onCopy: () -> Unit = {},
    onRegenerate: () -> Unit = {},
    onSpeak: () -> Unit = {},
    onShare: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val clipboardManager = LocalClipboardManager.current
    var thumbsState by remember { mutableStateOf(0) } // 0: none, 1: up, 2: down
    var isCopied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                if (messageText.isNotEmpty()) {
                    clipboardManager.setText(AnnotatedString(messageText))
                }
                isCopied = true
                onCopy()
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                contentDescription = "Copy",
                tint = if (isCopied) colors.accentGreen else colors.textMuted,
                modifier = Modifier.size(15.dp)
            )
        }
        IconButton(
            onClick = { thumbsState = if (thumbsState == 1) 0 else 1 },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.ThumbUp,
                contentDescription = "Good",
                tint = if (thumbsState == 1) colors.accentGreen else colors.textMuted,
                modifier = Modifier.size(15.dp)
            )
        }
        IconButton(
            onClick = { thumbsState = if (thumbsState == 2) 0 else 2 },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.ThumbDown,
                contentDescription = "Bad",
                tint = if (thumbsState == 2) colors.accentRed else colors.textMuted,
                modifier = Modifier.size(15.dp)
            )
        }
        IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Read aloud", tint = colors.textMuted, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Regenerate", tint = colors.textMuted, modifier = Modifier.size(16.dp))
        }
        IconButton(onClick = onShare, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Share, contentDescription = "Share", tint = colors.textMuted, modifier = Modifier.size(15.dp))
        }
    }
}

/**
 * User message card — right-aligned, Level 2 surface, 16dp radius with the
 * lower-right corner notched to 4dp so the flow reads left-to-right.
 */
@Composable
fun UserMessageBubble(
    text: String,
    attachments: List<Attachment> = emptyList(),
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Column(horizontalAlignment = Alignment.End) {
            if (attachments.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    attachments.forEach { a ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(a.name, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                if (a.sizeBytes > 0) {
                                    Text(
                                        formatSize(a.sizeBytes),
                                        color = colors.textMuted,
                                        fontFamily = LocalKarenType.current.mono,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (text.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 310.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                        .background(colors.userBubble)
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = text,
                        color = colors.userBubbleText,
                        fontSize = 14.5.sp,
                        lineHeight = 21.sp
                    )
                }
            }
        }
    }
}

/**
 * Minimalist ChatGPT Floating Composer Pill Dock
 */
@Composable
fun ChatGPTFloatingComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttachClick: () -> Unit,
    onMicClick: () -> Unit,
    onVoiceModeClick: () -> Unit,
    isListening: Boolean = false,
    listeningText: String = "",
    attachmentsPreview: List<Attachment> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    val hasText = value.isNotBlank()
    val canSend = hasText || attachmentsPreview.isNotEmpty()

    val infiniteTransition = rememberInfiniteTransition(label = "composer_mic_pulse")
    val micPulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.22f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micPulseScale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (attachmentsPreview.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    items(attachmentsPreview.size) { i ->
                        val a = attachmentsPreview[i]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.surfaceHover)
                                .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(a.name, color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                if (a.sizeBytes > 0) Text(formatSize(a.sizeBytes), color = colors.textMuted, fontSize = 10.5.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = colors.textMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onRemoveAttachment(i) }
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.composerBackground)
                    .border(
                        1.dp,
                        if (isListening) colors.accentGreen else colors.composerBorder,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
            // Plus attachment button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceHover)
                    .clickable { onAttachClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Attach",
                    tint = colors.textPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Text input field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = if (isListening) {
                            if (listeningText.isNotEmpty()) listeningText else "Listening... speak now"
                        } else {
                            "Message Karen..."
                        },
                        color = if (isListening) {
                            if (listeningText.isNotEmpty()) colors.accentGreen else colors.accentBlue
                        } else {
                            colors.textMuted
                        },
                        fontSize = 15.sp,
                        fontWeight = if (isListening && listeningText.isNotEmpty()) FontWeight.Medium else FontWeight.Normal
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = TextStyle(
                        color = colors.textPrimary,
                        fontSize = 15.sp
                    ),
                    cursorBrush = SolidColor(colors.accentGreen),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Send,
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (canSend) onSend()
                        }
                    ),
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.width(6.dp))

            // Actions row: Dictate, Advanced Voice, Send
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!canSend) {
                    // Mic button for dictation with native STT pulse
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .then(if (isListening) Modifier.scale(micPulseScale) else Modifier)
                            .clip(CircleShape)
                            .background(if (isListening) colors.accentGreen.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onMicClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Default.Mic else Icons.Default.Mic,
                            contentDescription = if (isListening) "Listening (Tap to stop)" else "Dictate with voice",
                            tint = if (isListening) colors.accentGreen else colors.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(2.dp))

                    // ChatGPT Advanced Voice Mode button
                    IconButton(
                        onClick = onVoiceModeClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Advanced Voice Mode",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    // Send button — solid primary, no shadow
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.accentGreen)
                            .clickable { onSend() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Send",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * Model Selector Bottom Sheet Modal
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpenModelManager: () -> Unit = {}
) {
    val colors = LocalKarenColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.border) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Model Architecture",
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "On-device weights and live cloud APIs",
                color = colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
            )

            val sheetCtx = androidx.compose.ui.platform.LocalContext.current
            // Poll the downloader while the sheet is open so tap-to-download
            // finalizes (registers) even if Model Manager was never visited.
            LaunchedEffect(Unit) {
                while (true) {
                    when (val ev = ModelDownloader.refresh(sheetCtx)) {
                        is DownloadEvent.Completed -> android.widget.Toast.makeText(
                            sheetCtx, "${ev.name} installed — tap it to load", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        is DownloadEvent.Failed -> android.widget.Toast.makeText(
                            sheetCtx, "Download failed: ${ev.reason}", android.widget.Toast.LENGTH_LONG
                        ).show()
                        else -> {}
                    }
                    kotlinx.coroutines.delay(1500)
                }
            }

            // My Models — only weights actually downloaded on this device.
            Text(
                text = "My Models · downloaded",
                color = colors.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            val installed = UserPrefs.models(sheetCtx).filter { !isCloudProviderName(it) }
            if (installed.isEmpty()) {
                Text(
                    text = "No local models on this device yet — download one below or add a cloud API key.",
                    color = colors.textMuted,
                    fontSize = 12.5.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            installed.forEach { name ->
                val isSelected = selectedModel == name
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) colors.surfaceHover else Color.Transparent)
                        .border(
                            1.dp,
                            if (isSelected) colors.accentGreen else colors.border,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable {
                            onSelectModel(name)
                            onDismiss()
                        }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = name,
                                color = colors.textPrimary,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) colors.accentGreen.copy(alpha = 0.2f)
                                        else colors.border.copy(alpha = 0.4f)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isSelected) "Active" else "GGUF",
                                    color = if (isSelected) colors.accentGreen else colors.textMuted,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "On-device · tap to load",
                            color = colors.textMuted,
                            fontSize = 11.5.sp
                        )
                    }

                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            onSelectModel(name)
                            onDismiss()
                        },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = colors.accentGreen,
                            unselectedColor = colors.textMuted
                        )
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Live third-party APIs — only providers with a stored key appear here.
            val keyedProviders = remember {
                cloudProviders.filter { UserPrefs.apiKey(sheetCtx, it.id).isNotBlank() }
            }
            if (keyedProviders.isNotEmpty()) {
                Text(
                    text = "Cloud APIs · live via your keys",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                keyedProviders.forEach { provider ->
                    val isSelected = selectedModel == provider.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) colors.surfaceHover else Color.Transparent)
                            .border(
                                1.dp,
                                if (isSelected) colors.accentBlue else colors.border,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                onSelectModel(provider.name)
                                onDismiss()
                            }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = provider.name,
                                    color = colors.textPrimary,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.accentBlue.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Live",
                                        color = colors.accentBlue,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = provider.defaultModel,
                                color = colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onSelectModel(provider.name)
                                onDismiss()
                            },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accentBlue,
                                unselectedColor = colors.textMuted
                            )
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Available to download — tap a model to fetch its GGUF weights.
            Text(
                text = "Available to download",
                color = colors.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            modelCatalog.forEach { entry ->
                val alreadyInstalled = entry.name in installed
                val isActive = ModelDownloader.activeName == entry.name
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                color = colors.textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${entry.sizeLabel} · ${entry.quant} · RAM ${entry.ram}",
                                color = colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        when {
                            alreadyInstalled -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Installed", color = colors.accentGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                            isActive -> {
                                IconButton(
                                    onClick = { ModelDownloader.cancel(sheetCtx) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel download", tint = colors.accentRed, modifier = Modifier.size(16.dp))
                                }
                            }
                            else -> {
                                OutlinedButton(
                                    onClick = {
                                        if (ModelDownloader.activeName != null) {
                                            android.widget.Toast.makeText(sheetCtx, "One download at a time", android.widget.Toast.LENGTH_SHORT).show()
                                        } else if (ModelDownloader.startEntry(sheetCtx, entry)) {
                                            android.widget.Toast.makeText(sheetCtx, "Downloading ${entry.name}", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(sheetCtx, "Could not start download", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Get", color = colors.accentGreen, fontSize = 12.5.sp)
                                }
                            }
                        }
                    }
                    if (isActive) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { ModelDownloader.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accentGreen,
                            trackColor = colors.surfaceHover
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            ModelDownloader.status,
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // Shortcut into the full Model Manager (import / export / GGUF weights)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentGreen.copy(alpha = 0.10f))
                    .border(1.dp, colors.accentGreen.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .clickable {
                        onDismiss()
                        onOpenModelManager()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = colors.accentGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Open Model Manager",
                        color = colors.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Import · export · installed GGUF weights",
                        color = colors.textMuted,
                        fontSize = 11.5.sp
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.accentGreen,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Attachment Bottom Sheet (Photos, Files, Camera, Tools)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    onDismiss: () -> Unit,
    onSelectOption: (String) -> Unit
) {
    val colors = LocalKarenColors.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.border) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Add to Chat",
                color = colors.textPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val options = listOf(
                Triple("Document / PDF", Icons.AutoMirrored.Filled.InsertDriveFile, "RAG Search"),
                Triple("Take Photo", Icons.Default.PhotoCamera, "Vision OCR"),
                Triple("Photos Library", Icons.Default.Image, "Attach asset"),
                Triple("Voice Memo", Icons.Default.Mic, "Whisper local")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                options.forEach { (label, icon, _) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectOption(label)
                                onDismiss()
                            }
                            .padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(colors.surfaceHover),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = label, tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(label, color = colors.textSecondary, fontSize = 11.5.sp)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Slide-out Navigation Drawer matching ChatGPT Mobile
 */
@Composable
fun ChatGPTDrawerContent(
    currentScreen: String = "Chat",
    onNavigate: (String) -> Unit = {},
    onCloseDrawer: () -> Unit = {},
    historyVersion: Int = 0,
    onOpenConversation: (String) -> Unit = {},
    onDeleteConversation: (String) -> Unit = {},
    onNewChat: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val currentMode = LocalKarenThemeMode.current
    var searchQuery by remember { mutableStateOf("") }
    val drawerCtx = androidx.compose.ui.platform.LocalContext.current
    val conversations = remember(historyVersion) { ChatHistoryStore.loadConversations(drawerCtx) }
    val visibleConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(colors.drawerBackground)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Psychology,
                    contentDescription = "Karen Logo",
                    tint = colors.accentGreen,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Karen Intelligence",
                    color = colors.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = onCloseDrawer) {
                Icon(Icons.Default.Close, contentDescription = "Close drawer", tint = colors.textMuted)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Search Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, contentDescription = "Search", tint = colors.textMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.5.sp),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    if (searchQuery.isEmpty()) {
                        Text("Search conversations...", color = colors.textMuted, fontSize = 13.5.sp)
                    }
                    innerTextField()
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        // New Chat Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceHover)
                .clickable {
                    onNewChat()
                    onCloseDrawer()
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Add, contentDescription = "New chat", tint = colors.textPrimary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("New chat", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(14.dp))

        // Scrollable Navigation Items
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Section Capabilities & Tools (system screens first)
            item {
                Text(
                    "Capabilities & Tools",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                DrawerNavItem("Home Dashboard", Icons.Default.Home, currentScreen == "Home") {
                    onNavigate("Home"); onCloseDrawer()
                }
                DrawerNavItem("Advanced Voice Mode", Icons.Default.GraphicEq, currentScreen == "Voice") {
                    onNavigate("Voice"); onCloseDrawer()
                }
                DrawerNavItem("Dev Workspace / Canvas", Icons.Default.Terminal, currentScreen == "Workspace") {
                    onNavigate("Workspace"); onCloseDrawer()
                }
                DrawerNavItem("Knowledge Vault & Files", Icons.Default.Folder, currentScreen == "Files") {
                    onNavigate("Files"); onCloseDrawer()
                }
                DrawerNavItem("Memory & Preferences", Icons.Default.Psychology, currentScreen == "Memory") {
                    onNavigate("Memory"); onCloseDrawer()
                }
                DrawerNavItem("Local Models (GGUF)", Icons.Default.Memory, currentScreen == "ModelManager") {
                    onNavigate("ModelManager"); onCloseDrawer()
                }
                DrawerNavItem("Performance & TTFT", Icons.Default.Speed, currentScreen == "Performance") {
                    onNavigate("Performance"); onCloseDrawer()
                }
                DrawerNavItem("Hardware & Governance", Icons.Default.Shield, currentScreen == "Hardware") {
                    onNavigate("Hardware"); onCloseDrawer()
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Managers · v2.2 preview",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                DrawerNavItem("Model Import", Icons.Default.FolderOpen, currentScreen == "ModelImport") {
                    onNavigate("ModelImport"); onCloseDrawer()
                }
                DrawerNavItem("Model Export", Icons.Default.Upload, currentScreen == "ModelExport") {
                    onNavigate("ModelExport"); onCloseDrawer()
                }
                DrawerNavItem("Memory Transfer", Icons.Default.Sync, currentScreen == "MemoryTransfer") {
                    onNavigate("MemoryTransfer"); onCloseDrawer()
                }
                DrawerNavItem("Backup Package", Icons.Default.Archive, currentScreen == "BackupPackage") {
                    onNavigate("BackupPackage"); onCloseDrawer()
                }
                DrawerNavItem("Karen Storage", Icons.Default.Storage, currentScreen == "StorageManager") {
                    onNavigate("StorageManager"); onCloseDrawer()
                }
                DrawerNavItem("Device Migration", Icons.Default.SwapHoriz, currentScreen == "Migration") {
                    onNavigate("Migration"); onCloseDrawer()
                }
                Spacer(Modifier.height(12.dp))
            }

            // Chat history from local storage, grouped by recency.
            item {
                if (visibleConversations.isEmpty()) {
                    Text(
                        if (searchQuery.isBlank()) "No saved chats yet — they appear here after your first message."
                        else "No chats match your search.",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    val now = System.currentTimeMillis()
                    val dayMs = 24L * 60 * 60 * 1000
                    val startOfToday = now - (now % dayMs)
                    val groups = listOf(
                        "Today" to visibleConversations.filter { it.updatedAt >= startOfToday },
                        "Previous 7 Days" to visibleConversations.filter { it.updatedAt < startOfToday && it.updatedAt >= startOfToday - 7 * dayMs },
                        "Older" to visibleConversations.filter { it.updatedAt < startOfToday - 7 * dayMs }
                    )
                    val dateFmt = java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                    groups.forEach { (label, items) ->
                        if (items.isNotEmpty()) {
                            Text(
                                label,
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                            items.take(30).forEach { convo ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.Transparent)
                                        .clickable {
                                            onOpenConversation(convo.id)
                                            onCloseDrawer()
                                        }
                                        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            convo.title,
                                            color = colors.textSecondary,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                        Text(
                                            dateFmt.format(java.util.Date(convo.updatedAt)),
                                            color = colors.textMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteConversation(convo.id) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete chat", tint = colors.textMuted, modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // Theme Switcher Section
            item {
                Text(
                    "Theme & Appearance",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .clickable {
                            if (currentMode == KarenThemeMode.OLED) {
                                KarenThemeState.setMode(KarenThemeMode.CHARCOAL)
                            } else if (currentMode == KarenThemeMode.CHARCOAL) {
                                KarenThemeState.setMode(KarenThemeMode.OLED)
                            } else {
                                KarenThemeState.setMode(KarenThemeMode.OLED)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (currentMode == KarenThemeMode.LIGHT) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = "Mode",
                                tint = colors.accentAmber,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (currentMode == KarenThemeMode.LIGHT) "Light Mode" else "Dark Mode",
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Text(
                            text = when (currentMode) {
                                KarenThemeMode.OLED -> "OLED Black (#000000)"
                                KarenThemeMode.CHARCOAL -> "Charcoal (#212121)"
                                KarenThemeMode.LIGHT -> "Clean Light (#FFFFFF)"
                            },
                            color = colors.textMuted,
                            fontSize = 11.sp
                        )
                    }

                    Switch(
                        checked = currentMode != KarenThemeMode.LIGHT,
                        onCheckedChange = { KarenThemeState.toggleTheme() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = colors.accentGreen,
                            uncheckedThumbColor = colors.textMuted,
                            uncheckedTrackColor = colors.surfaceHover
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Drawer Footer: User Profile Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onNavigate("Memory"); onCloseDrawer() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.accentGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Text("A", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(UserPrefs.name(androidx.compose.ui.platform.LocalContext.current), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Karen Sovereign · 4B NPU", color = colors.textMuted, fontSize = 11.sp)
                }
            }

            IconButton(onClick = { KarenThemeState.toggleTheme() }, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (currentMode == KarenThemeMode.LIGHT) Icons.Default.DarkMode else Icons.Default.LightMode,
                    contentDescription = "Toggle Theme",
                    tint = colors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(onClick = { onNavigate("Hardware"); onCloseDrawer() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = colors.textMuted, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun DrawerNavItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) colors.drawerItemHover else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (isSelected) colors.accentGreen else colors.textMuted,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            color = if (isSelected) colors.textPrimary else colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

// Backward-compatible legacy components for existing callers
@Composable
fun KScreen(title: String, subtitle: String = "", content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalKarenColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(title, color = colors.textPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, color = colors.textMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        HorizontalDivider(color = colors.border, thickness = 0.5.dp)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            item { Column(content = content) }
        }
    }
}

@Composable
fun Section(title: String, icon: String = "", content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalKarenColors.current
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(
            if (icon.isEmpty()) title else "$icon  $title",
            color = colors.textMuted,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Column(content = content)
    }
}

@Composable
fun KV(label: String, value: String, hl: Boolean = false) {
    val colors = LocalKarenColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp)
        Text(
            value,
            color = if (hl) colors.accentGreen else colors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
    }
}

@Composable
fun KCard(text: String, sub: String = "") {
    val colors = LocalKarenColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(text, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
        if (sub.isNotEmpty()) {
            Text(
                sub,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 4.dp),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun KButton(label: String, primary: Boolean = false, danger: Boolean = false) {
    val colors = LocalKarenColors.current
    OutlinedButton(
        onClick = {},
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = when {
                danger -> colors.accentRed
                primary -> colors.accentGreen
                else -> colors.textPrimary
            }
        ),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            brush = SolidColor(if (primary) colors.accentGreen else colors.border)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.padding(end = 6.dp, bottom = 6.dp)
    ) {
        Text(
            if (primary) "▶  $label" else label,
            color = when {
                danger -> colors.accentRed
                primary -> colors.accentGreen
                else -> colors.textPrimary
            },
            fontSize = 12.5.sp
        )
    }
}
