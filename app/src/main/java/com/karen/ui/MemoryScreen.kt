package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Memory & Preferences: on-device memory bank. Style signals come from
 * like/dislike in Chat (StyleMemory); facts are user-added and must be
 * confirmed before Karen references them (MemoryFacts). The preferences
 * here control whether memory is injected into prompts at all.
 */
@Composable
fun MemoryScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var statsTick by remember { mutableStateOf(0) }
    val stats = remember(statsTick) { StyleMemory.stats(context) }
    var pending by remember { mutableStateOf(MemoryFacts.pending(context)) }
    var verified by remember { mutableStateOf(MemoryFacts.verified(context)) }
    var memoryOn by remember { mutableStateOf(UserPrefs.memoryEnabled(context)) }
    var instructions by remember { mutableStateOf(UserPrefs.customInstructions(context)) }
    var savedInstructions by remember { mutableStateOf(UserPrefs.customInstructions(context)) }
    var showAddFact by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    val totalMessages = remember { UserPrefs.totalMessages(context) }

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun refreshFacts() {
        pending = MemoryFacts.pending(context)
        verified = MemoryFacts.verified(context)
    }

    fun exportJson() {
        val root = JSONObject()
        root.put(
            "profile",
            JSONObject()
                .put("name", UserPrefs.name(context))
                .put("age", UserPrefs.age(context))
                .put("type", UserPrefs.type(context))
        )
        root.put(
            "style_memory",
            JSONObject()
                .put("likes", stats.likes)
                .put("dislikes", stats.dislikes)
                .put("summary", JSONArray(stats.summary))
                .put("feedback", JSONArray(stats.feedback))
        )
        val facts = JSONArray()
        (verified + pending).forEach { f ->
            facts.put(
                JSONObject()
                    .put("category", f.category)
                    .put("text", f.text)
                    .put("confirmed", f.verified)
            )
        }
        root.put("facts", facts)
        root.put("exported_at", System.currentTimeMillis())
        clipboard.setText(AnnotatedString(root.toString(2)))
        toast("Memory JSON copied to clipboard")
    }

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
        when {
            showAddFact -> {
                showAddFact = false
                true
            }
            showReset -> {
                showReset = false
                true
            }
            else -> false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Memory & Preferences",
            onMenuClick = onOpenDrawer,
            onBackClick = onNavigateToHome,
            moreActions = listOf(
                Triple("Export JSON", Icons.Default.Share) { exportJson() },
                Triple("Reset memory", Icons.Default.Refresh) { showReset = true }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                MemoryHeroCard(
                    signals = stats.totalSignals,
                    facts = pending.size + verified.size,
                    messages = totalMessages,
                    windowUsed = stats.windowUsed,
                    windowDaysLeft = stats.windowDaysLeft,
                    memoryOn = memoryOn,
                    onExport = { exportJson() },
                    onReset = { showReset = true }
                )
            }
            item {
                StyleSignalsCard(
                    likes = stats.likes,
                    dislikes = stats.dislikes,
                    summary = stats.summary,
                    feedback = stats.feedback,
                    onForget = {
                        StyleMemory.clear(context)
                        statsTick++
                        toast("Style signals cleared")
                    }
                )
            }
            item {
                FactsSection(
                    pending = pending,
                    verified = verified,
                    onAdd = { showAddFact = true },
                    onConfirm = { f ->
                        MemoryFacts.confirm(context, f.id)
                        refreshFacts()
                        toast("Fact confirmed")
                    },
                    onDiscard = { f ->
                        MemoryFacts.discard(context, f.id)
                        refreshFacts()
                    },
                    onDelete = { f ->
                        MemoryFacts.remove(context, f.id)
                        refreshFacts()
                    }
                )
            }
            item {
                MemorySectionLabel(
                    kicker = "Preferences",
                    icon = Icons.Default.Tune,
                    tint = colors.accentBlue,
                    trailing = if (memoryOn) "memory on" else "memory off"
                )
                Spacer(Modifier.height(6.dp))
                MemoryPreferenceCard(
                    memoryOn = memoryOn,
                    onToggle = { on ->
                        memoryOn = on
                        UserPrefs.setMemoryEnabled(context, on)
                        toast(if (on) "Memory is used in replies" else "Memory ignored in replies")
                    }
                )
            }
            item {
                InstructionsCard(
                    value = instructions,
                    dirty = instructions.trim() != savedInstructions,
                    onValueChange = { instructions = it },
                    onSave = {
                        UserPrefs.setCustomInstructions(context, instructions)
                        savedInstructions = UserPrefs.customInstructions(context)
                        instructions = savedInstructions
                        toast("Instructions saved")
                    }
                )
            }
            item { MemoryFooterNote() }
        }
    }

    AddFactDialog(
        show = showAddFact,
        onDismiss = { showAddFact = false },
        onAdd = { category, text ->
            MemoryFacts.add(context, category, text)
            refreshFacts()
            showAddFact = false
            toast("Added — confirm it to remember")
        }
    )
    ResetMemoryDialog(
        show = showReset,
        onDismiss = { showReset = false },
        onConfirm = {
            StyleMemory.clear(context)
            MemoryFacts.clear(context)
            PromptMemory.clear(context)
            statsTick++
            refreshFacts()
            showReset = false
            toast("Memory reset")
        }
    )
}

// ---- Hero: status, counts, window, actions ----

@Composable
private fun MemoryHeroCard(
    signals: Int,
    facts: Int,
    messages: Int,
    windowUsed: Int,
    windowDaysLeft: Int,
    memoryOn: Boolean,
    onExport: () -> Unit,
    onReset: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
                    listOf(colors.surface, colors.cardBackground)
                )
            )
            .border(1.dp, colors.accentGreen.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(colors.accentGreen.copy(alpha = 0.13f))
                    .border(1.dp, colors.accentGreen.copy(alpha = 0.28f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "ON-DEVICE MEMORY // PRIVATE",
                    color = colors.accentGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.05.sp
                )
                Spacer(Modifier.height(2.dp))
                Text("Memory & Preferences", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Learned style, confirmed facts, local only", color = colors.textMuted, fontSize = 11.sp, maxLines = 1)
            }
            MemoryPill(
                text = if (memoryOn) "ACTIVE" else "PAUSED",
                tint = if (memoryOn) colors.accentGreen else colors.accentAmber
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Karen personalizes replies from the signals you give in Chat and the facts you confirm here. Nothing is uploaded — memory lives only in this app.",
            color = colors.textMuted,
            fontSize = 11.5.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(signals.toString(), "Style signals", Icons.Default.AutoAwesome, colors.accentGreen, Modifier.weight(1f))
            StatTile(facts.toString(), "Saved facts", Icons.Default.Bookmark, colors.accentBlue, Modifier.weight(1f))
            StatTile(messages.toString(), "Messages", Icons.Default.Chat, colors.accentAmber, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surfaceHover.copy(alpha = 0.4f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Timer, contentDescription = null, tint = colors.accentBlue, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                if (signals == 0) {
                    "Signals expire 30 days or 300 messages after the first one."
                } else {
                    "Style window · $windowUsed/300 messages · resets in ${
                        if (windowDaysLeft == 1) "1 day" else "$windowDaysLeft days"
                    }"
                },
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onExport,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Export JSON", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onReset,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentRed),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text("Reset memory", fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceHover.copy(alpha = 0.45f))
            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(vertical = 9.dp, horizontal = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(tint.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(
            value,
            color = tint,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(1.dp))
        Text(label, color = colors.textMuted, fontSize = 9.5.sp, maxLines = 1)
    }
}

// ---- Style signals (learned from Chat likes / feedback) ----

@Composable
private fun StyleSignalsCard(
    likes: Int,
    dislikes: Int,
    summary: List<String>,
    feedback: List<String>,
    onForget: () -> Unit
) {
    val colors = LocalKarenColors.current
    val hasSignals = summary.isNotEmpty() || feedback.isNotEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemoryIconTile(Icons.Default.AutoAwesome, colors.accentGreen)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "LEARNED STYLE",
                    color = colors.accentGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text("How Karen answers", color = colors.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            }
            if (hasSignals) {
                MemoryPill("$likes liked · $dislikes notes", colors.accentGreen)
            } else {
                MemoryPill("NO SIGNALS", colors.textMuted)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!hasSignals) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceHover.copy(alpha = 0.35f))
                    .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = colors.accentGreen.copy(alpha = 0.85f),
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "No signals yet. Like or dislike a reply in Chat and Karen learns its shape — length, structure, and what to avoid.",
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceHover.copy(alpha = 0.25f))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                summary.forEach { line ->
                    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colors.accentGreen)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(line, color = colors.textPrimary, fontSize = 12.5.sp, lineHeight = 17.sp)
                    }
                }
                feedback.takeLast(3).forEach { note ->
                    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            modifier = Modifier
                                .padding(top = 5.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colors.accentAmber)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "“${note.trim()}”",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = onForget,
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentRed),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Forget style signals", color = colors.accentRed, fontSize = 11.sp)
                }
            }
        }
    }
}

// ---- Saved facts: pending confirmation + verified ----

@Composable
private fun FactsSection(
    pending: List<MemoryFacts.Fact>,
    verified: List<MemoryFacts.Fact>,
    onAdd: () -> Unit,
    onConfirm: (MemoryFacts.Fact) -> Unit,
    onDiscard: (MemoryFacts.Fact) -> Unit,
    onDelete: (MemoryFacts.Fact) -> Unit
) {
    val colors = LocalKarenColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MemoryIconTile(Icons.Default.Bookmark, colors.accentGreen, 30)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "SAVED FACTS",
                    color = colors.accentGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    "${pending.size + verified.size} remembered · ${pending.size} awaiting",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Button(
                onClick = onAdd,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(3.dp))
                Text("Add fact", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (pending.isEmpty() && verified.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.accentAmber.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lightbulb, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column {
                    Text("Nothing saved yet", color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Tap Add fact to remember something — Karen only uses facts you confirm.",
                        color = colors.textMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
            return@Column
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pending.forEach { fact ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(colors.accentAmber.copy(alpha = 0.08f), colors.cardBackground)
                            )
                        )
                        .border(1.dp, colors.accentAmber.copy(alpha = 0.42f), RoundedCornerShape(15.dp))
                        .padding(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(colors.accentAmber.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.HourglassTop, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        MemoryFactCategory(fact.category, colors.accentAmber)
                        Spacer(Modifier.weight(1f))
                        MemoryPill("PENDING", colors.accentAmber)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(fact.text, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onConfirm(fact) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("Confirm & remember", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { onDiscard(fact) },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text("Discard", fontSize = 10.5.sp)
                        }
                    }
                }
            }
            verified.forEach { fact ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(15.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(15.dp))
                        .padding(horizontal = 13.dp, vertical = 11.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(colors.accentGreen.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        MemoryFactCategory(fact.category, colors.accentGreen)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onDelete(fact) }, modifier = Modifier.size(26.dp)) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove fact",
                                tint = colors.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(fact.text, color = colors.textPrimary, fontSize = 13.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Confirmed · used when memory is on",
                        color = colors.textMuted,
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryFactCategory(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.14f))
            .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text.uppercase(),
            color = tint,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.4.sp
        )
    }
}

// ---- Preferences ----

@Composable
private fun MemoryPreferenceCard(memoryOn: Boolean, onToggle: (Boolean) -> Unit) {
    val colors = LocalKarenColors.current
    val tint = if (memoryOn) colors.accentGreen else colors.textMuted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, if (memoryOn) colors.accentGreen.copy(alpha = 0.3f) else colors.border, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MemoryIconTile(Icons.Default.Psychology, tint, 34)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Use memory in replies",
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                MemoryPill(if (memoryOn) "ON" else "OFF", tint)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                if (memoryOn) "Learned style + confirmed facts are added to prompts"
                else "Stored on device but ignored by the model",
                color = colors.textMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = memoryOn,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.accentGreen,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.surfaceHover
            )
        )
    }
}

@Composable
private fun InstructionsCard(
    value: String,
    dirty: Boolean,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemoryIconTile(Icons.Default.EditNote, colors.accentBlue, 34)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "CUSTOM INSTRUCTIONS",
                    color = colors.accentBlue,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text("Custom instructions", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Prepended to every prompt for Karen — max 500 characters.",
                    color = colors.textMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }
            MemoryPill("${value.length}/500", colors.accentBlue)
        }
        Spacer(Modifier.height(9.dp))
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.take(500)) },
            placeholder = { Text("e.g. Answer in British English; keep code comments short", color = colors.textMuted, fontSize = 12.5.sp) },
            minLines = 3,
            maxLines = 5,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 13.sp),
            colors = karenFieldColors(colors)
        )
        Spacer(Modifier.height(5.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onSave, enabled = dirty) {
                Text(
                    if (dirty) "Save" else "Saved",
                    color = if (dirty) colors.accentGreen else colors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun MemoryFooterNote() {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(colors.accentGreen.copy(alpha = 0.05f))
            .border(1.dp, colors.accentGreen.copy(alpha = 0.16f), RoundedCornerShape(11.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Karen never uploads or syncs memory. Reset erases it from this device only.",
            color = colors.textMuted,
            fontSize = 10.5.sp,
            lineHeight = 14.sp
        )
    }
}

// ---- Memory visual primitives ----

@Composable
private fun MemoryIconTile(icon: ImageVector, tint: Color, size: Int = 34) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.24f), RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.48f).dp))
    }
}

@Composable
private fun MemoryPill(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            color = tint,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun MemorySectionLabel(
    kicker: String,
    icon: ImageVector,
    tint: Color,
    trailing: String? = null
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            kicker.uppercase(),
            color = colors.textSecondary,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp,
            modifier = Modifier.weight(1f)
        )
        if (!trailing.isNullOrBlank()) {
            Text(trailing, color = colors.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

// ---- Dialogs ----

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddFactDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    if (!show) return
    val colors = LocalKarenColors.current
    var category by remember { mutableStateOf(MemoryFacts.categories.first()) }
    var text by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.accentGreen.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Add a fact", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("Karen remembers it once you confirm", color = colors.textMuted, fontSize = 12.sp)
                }
            }
            HorizontalDivider(color = colors.border, thickness = 0.5.dp)
            Text("Category", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MemoryFacts.categories.forEach { c ->
                    val selected = category == c
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) colors.accentGreen.copy(alpha = 0.2f) else colors.surfaceHover)
                            .border(1.dp, if (selected) colors.accentGreen else colors.border, RoundedCornerShape(10.dp))
                            .clickable { category = c }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selected) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            c,
                            color = if (selected) colors.accentGreen else colors.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(240) },
                label = { Text("What should Karen remember?", color = colors.textMuted) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                colors = karenFieldColors(colors)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = colors.textMuted)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onAdd(category, text.trim()) },
                    enabled = text.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accentGreen,
                        contentColor = Color.White,
                        disabledContainerColor = colors.surfaceHover,
                        disabledContentColor = colors.textMuted
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Add")
                }
            }
        }
    }
}

@Composable
private fun ResetMemoryDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (!show) return
    val colors = LocalKarenColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text("Reset memory?", color = colors.textPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                "Deletes saved facts, learned style signals, and remembered prompts from this device. This can't be undone.",
                color = colors.textSecondary,
                fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Reset", color = colors.accentRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    )
}
