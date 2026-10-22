package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.rememberDeviceTelemetry

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenConversation: (String) -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when {
        hourOfDay < 12 -> "Good morning"
        hourOfDay < 18 -> "Good afternoon"
        else -> "Good evening"
    }

    // Real recent items: last conversations + newest vault documents.
    // Three show at a glance; "See More" reveals up to ten.
    val recentChats = remember { ChatHistoryStore.loadConversations(ctx).take(10) }
    val recentFiles = remember {
        vaultFiles(ctx).sortedByDescending { it.lastModified() }.take(10)
    }
    var showAllRecents by remember { mutableStateOf(false) }

    // Live task surface: an in-flight download, else the pinned model.
    val activeDownload = ModelDownloader.activeName
    val pinnedModel = UserPrefs.selectedModel(ctx).takeIf { isSelectableModel(ctx, it) }
    val downloadProgress = ModelDownloader.progress
    // Keep the gesture callback fresh without restarting the pointer detector
    // every time Home recomposes for telemetry.
    val openLiveMode = rememberUpdatedState { onNavigate("Voice") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .twoFingerSwipeDown { openLiveMode.value() }
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Greeting hero
        item {
            KHero(
                kicker = "Home // dashboard",
                title = "$greeting, ${UserPrefs.name(androidx.compose.ui.platform.LocalContext.current)}",
                subtitle = "What would you like to build or synthesize today?",
                icon = Icons.Default.AutoAwesome,
                tone = colors.accentGreen,
                status = if (device.networkUp) "NETWORK UP" else "OFFLINE",
                statusTone = if (device.networkUp) KTone.Amber else KTone.Success
            ) {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KHeroStat(
                        label = "RAM",
                        value = "${device.usedRamPercent}%",
                        unit = "in use",
                        tint = colors.accentGreen,
                        modifier = Modifier.weight(1f)
                    )
                    KHeroStat(
                        label = "Battery",
                        value = "${device.batteryPercent}%",
                        unit = String.format("%.1f°C", device.batteryTempC),
                        tint = colors.accentBlue,
                        modifier = Modifier.weight(1f)
                    )
                    KHeroStat(
                        label = "Model",
                        value = if (pinnedModel != null) "READY" else "—",
                        unit = if (pinnedModel != null) "pinned" else "none pinned",
                        tint = colors.accentAmber,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Air-Gapped",
                        tint = colors.accentGreen,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "Air-Gapped Sovereign",
                        color = colors.accentGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (device.networkUp) "Network up" else "Offline · 0 egress",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceHover)
                            .border(1.dp, colors.border, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }

        // Quick Prompt Shortcut Grid (2x2)
        item {
            KSectionHeader(
                kicker = "Quick actions",
                icon = Icons.Default.Bolt,
                tone = colors.accentBlue,
                trailing = "4 shortcuts"
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PromptShortcutCard(
                        title = "Ask Karen",
                        subtitle = "Type or paste anything",
                        icon = Icons.Default.Assistant,
                        iconTint = colors.accentBlue,
                        modifier = Modifier.weight(1f)
                    ) { onNavigate("Chat") }

                    PromptShortcutCard(
                        title = "Live Mode",
                        subtitle = "Hands-free AI",
                        icon = Icons.Default.GraphicEq,
                        iconTint = colors.accentGreen,
                        modifier = Modifier.weight(1f)
                    ) { onNavigate("Voice") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PromptShortcutCard(
                        title = "Knowledge Vault",
                        subtitle = "Your files & notes",
                        icon = Icons.Default.SnippetFolder,
                        iconTint = colors.accentAmber,
                        modifier = Modifier.weight(1f)
                    ) { onNavigate("Files") }

                    PromptShortcutCard(
                        title = "Canvas",
                        subtitle = "Write & plan",
                        icon = Icons.Default.Workspaces,
                        iconTint = Color(0xFFEC4899),
                        modifier = Modifier.weight(1f)
                    ) { onNavigate("Workspace") }
                }
            }
        }

        // Live Execution Task Card
        item {
            KSectionHeader(
                kicker = "Live task",
                icon = Icons.Default.Bolt,
                tone = colors.accentGreen,
                trailing = when {
                    activeDownload != null -> "DOWNLOADING"
                    pinnedModel != null -> "PINNED"
                    else -> "IDLE"
                }
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(colors.surface, colors.cardBackground)
                        )
                    )
                    .border(1.dp, colors.accentGreen.copy(alpha = 0.28f), RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    KIconTile(Icons.Default.Bolt, colors.accentGreen, 30.dp)
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "EXECUTION // LIVE",
                            color = colors.accentGreen,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            "Memory Pressure",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text("${device.usedRamPercent}% RAM", color = colors.accentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        activeDownload != null -> "Downloading $activeDownload"
                        pinnedModel != null -> "Pinned model: $pinnedModel"
                        else -> "No active task"
                    },
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        activeDownload != null -> ModelDownloader.status.ifBlank { "Starting…" }
                        pinnedModel != null -> "Every surface answers with this model — change it in the model sheet."
                        else -> "Start a chat or download a model — live progress shows up here."
                    },
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(Modifier.height(10.dp))
                // Live progress: download when one runs, otherwise RAM pressure.
                LinearProgressIndicator(
                    progress = { if (activeDownload != null) downloadProgress else device.usedRamPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )

                Spacer(Modifier.height(12.dp))
                // Recently opened: chats, canvases and screens — tap to jump back.
                val recents = remember { RecentActivity.list(ctx).take(3) }
                if (recents.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    HorizontalDivider(color = colors.border)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "RECENTLY OPENED",
                        color = colors.textMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    recents.forEach { r ->
                        val icon = when (r.type) {
                            RecentActivity.TYPE_CHAT -> Icons.AutoMirrored.Filled.Chat
                            RecentActivity.TYPE_WORKSPACE -> Icons.Default.Workspaces
                            else -> Icons.Default.Layers
                        }
                        val typeLabel = when (r.type) {
                            RecentActivity.TYPE_CHAT -> "Chat"
                            RecentActivity.TYPE_WORKSPACE -> "Canvas"
                            else -> "Screen"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    when (r.type) {
                                        RecentActivity.TYPE_CHAT -> onOpenConversation(r.key)
                                        RecentActivity.TYPE_WORKSPACE -> onNavigate("Workspace")
                                        else -> onNavigate(r.key)
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp)
                        ) {
                            Icon(icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                r.title,
                                color = colors.textPrimary,
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "$typeLabel · ${relTimeAgo(r.at)}",
                                color = colors.textMuted,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (label, target) = when {
                        activeDownload != null -> "Open Model Manager" to "ModelManager"
                        pinnedModel != null -> "Open Chat" to "Chat"
                        else -> "Resume in Canvas" to "Workspace"
                    }
                    Button(
                        onClick = { onNavigate(target) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentGreen,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        // Recent Knowledge & Vault — newest chats and vault docs in one list:
        // three at a glance, up to ten with See More.
        item {
            val recentItems = buildList {
                recentChats.forEach { c ->
                    add(
                        HomeRecentItem(
                            ts = c.updatedAt,
                            title = c.title,
                            sub = "Chat · ${relTimeAgo(c.updatedAt)}",
                            icon = Icons.AutoMirrored.Filled.Chat,
                            onOpen = { onOpenConversation(c.id) }
                        )
                    )
                }
                recentFiles.forEach { f ->
                    add(
                        HomeRecentItem(
                            ts = f.lastModified(),
                            title = f.name,
                            sub = "Vault · ${sizeLabel(f.length())}",
                            icon = Icons.Default.Description,
                            onOpen = { onNavigate("Files") }
                        )
                    )
                }
            }.sortedByDescending { it.ts }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.accentAmber.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "RECENT KNOWLEDGE & VAULT",
                    color = colors.textSecondary,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.9.sp,
                    modifier = Modifier.weight(1f)
                )
                if (recentItems.size > 3) {
                    Text(
                        if (showAllRecents) "SHOW LESS" else "SEE MORE",
                        color = colors.accentGreen,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.accentGreen.copy(alpha = 0.10f))
                            .border(1.dp, colors.accentGreen.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                            .clickable { showAllRecents = !showAllRecents }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recentItems.isEmpty()) {
                    Text(
                        "Nothing yet — ask Karen something, or add a document in the Knowledge Vault.",
                        color = colors.textMuted,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp
                    )
                }
                recentItems.take(if (showAllRecents) 10 else 3).forEach { r ->
                    RecentDocRow(
                        title = r.title,
                        sub = r.sub,
                        icon = r.icon,
                        onClick = r.onOpen
                    )
                }
            }
        }

        // System Telemetry Snapshot
        item {
            KSectionHeader(
                kicker = "System diagnostics",
                icon = Icons.Default.MonitorHeart,
                tone = colors.accentBlue,
                trailing = if (device.networkUp) "NETWORK UP" else "OFFLINE"
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryCol(label = "RAM Free", value = String.format("%.1f / %.0f GB", device.freeRamGb, device.totalRamGb))
                TelemetryCol(label = "Battery", value = "${device.batteryPercent}% · ${String.format("%.1f", device.batteryTempC)}°C")
                TelemetryCol(label = "Storage Free", value = String.format("%.1f GB", device.storageFreeGb))
            }
        }
    }
}

@Composable
private fun PromptShortcutCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, iconTint.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KIconTile(icon, iconTint, 32.dp)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = colors.textMuted, fontSize = 11.5.sp)
    }
}

/** One row in the merged Recent Knowledge & Vault feed. */
private data class HomeRecentItem(
    val ts: Long,
    val title: String,
    val sub: String,
    val icon: ImageVector,
    val onOpen: () -> Unit
)

@Composable
private fun RecentDocRow(
    title: String,
    sub: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KIconTile(icon, colors.accentBlue, 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = colors.textMuted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "Open", tint = colors.textMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TelemetryCol(label: String, value: String) {
    val colors = LocalKarenColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(),
            color = colors.textMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(4.dp))
        Text(value, color = colors.textPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

/**
 * Opens Live mode on a deliberate two-finger swipe down. Single-finger
 * scrolling and taps are untouched; the gesture is consumed only once it
 * clearly reads as a downward swipe so the list cannot keep scrolling
 * underneath the navigation.
 */
private fun Modifier.twoFingerSwipeDown(onSwipeDown: () -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var startX = 0f
            var startY = 0f
            var tracking = false
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val x = pressed.map { it.position.x }.average().toFloat()
                    val y = pressed.map { it.position.y }.average().toFloat()
                    if (!tracking) {
                        startX = x
                        startY = y
                        tracking = true
                    } else {
                        val dy = y - startY
                        val dx = kotlin.math.abs(x - startX)
                        if (dy >= 96.dp.toPx() && dy >= dx * 1.5f) {
                            event.changes.forEach { it.consume() }
                            fired = true
                            break
                        }
                    }
                } else {
                    tracking = false
                }
                if (event.changes.none { it.pressed }) break
            }
            if (fired) onSwipeDown()
        }
    }

/** Compact "2m ago" style stamp for the recent lists. */
private fun relTimeAgo(ts: Long): String {
    val delta = System.currentTimeMillis() - ts
    return when {
        delta < 60_000 -> "just now"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        delta < 86_400_000 -> "${delta / 3_600_000}h ago"
        else -> "${delta / 86_400_000}d ago"
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1073741824L -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
