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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val currentMode = LocalKarenThemeMode.current

    androidx.activity.compose.BackHandler(onBack = onBack)

    var editing by remember { mutableStateOf(false) }
    var editName by remember(editing) { mutableStateOf(UserPrefs.name(context)) }
    var editAge by remember(editing) { mutableStateOf(UserPrefs.age(context).takeIf { it != "-" } ?: "") }
    var editType by remember(editing) { mutableStateOf(UserPrefs.type(context).takeIf { it != "-" } ?: "Personal") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
            }
            Text("Settings", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("Profile", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(colors.accentGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                UserPrefs.name(context).firstOrNull()?.uppercase() ?: "-",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(UserPrefs.name(context), color = colors.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Text("${UserPrefs.type(context)} · Age ${UserPrefs.age(context)}", color = colors.textMuted, fontSize = 12.sp)
                        }
                        TextButton(onClick = { editing = true }) {
                            Text("Edit", color = colors.accentGreen)
                        }
                    }
                }
            }

            item {
                Text("Appearance", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (currentMode == KarenThemeMode.LIGHT) "Light Mode" else "Dark Mode", color = colors.textPrimary, fontSize = 13.5.sp)
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

            item {
                Text("Performance", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var gpuAcceleration by remember { mutableStateOf(true) }
                var cpuThreads by remember { mutableStateOf(Runtime.getRuntime().availableProcessors().toFloat()) }
                var thermalGuard by remember { mutableStateOf(UserPrefs.thermalGuard(context)) }
                var thermalLimit by remember { mutableStateOf(UserPrefs.thermalLimitC(context)) }
                var backend by remember { mutableStateOf("Auto") }
                var defaultEffort by remember { mutableStateOf(UserPrefs.defaultEffort(context)) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("GPU Acceleration", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Use Vulkan/GPU for faster inference", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = gpuAcceleration,
                            onCheckedChange = { gpuAcceleration = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentGreen,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Auto", "GPU", "CPU").forEach { mode ->
                            val selected = backend == mode
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) colors.accentGreen.copy(alpha = 0.2f) else colors.surfaceHover)
                                    .border(1.dp, if (selected) colors.accentGreen else colors.border, RoundedCornerShape(10.dp))
                                    .clickable { backend = mode }
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                                Text(mode, color = if (selected) colors.accentGreen else colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Text("Compute backend", color = colors.textMuted, fontSize = 11.5.sp, modifier = Modifier.align(Alignment.CenterVertically).padding(start = 4.dp))
                    }
                    // Content window shared by Chat + Workspace (up to 16k).
                    var contentWindow by remember { mutableStateOf(UserPrefs.contextTokens(context)) }
                    Text("Content Window", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        UserPrefs.contextWindowOptions.forEach { tokens ->
                            val selected = contentWindow == tokens
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentGreen.copy(alpha = 0.18f) else colors.surfaceHover)
                                    .border(1.dp, if (selected) colors.accentGreen else colors.border, RoundedCornerShape(8.dp))
                                    .clickable {
                                        contentWindow = tokens
                                        UserPrefs.setContextTokens(context, tokens)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(windowLabel(tokens), color = if (selected) colors.accentGreen else colors.textPrimary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Text(
                        "History + project context kept per reply. On-device models reload with the new size on next run.",
                        color = colors.textMuted,
                        fontSize = 11.5.sp
                    )
                    // Auto model routing (per-task best model).
                    var autoRoutePref by remember { mutableStateOf(UserPrefs.autoRoute(context)) }
                    var autoCloudPref by remember { mutableStateOf(UserPrefs.autoCloud(context)) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto Model Routing", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Code → strongest · reasoning → thinker · quick chats → fastest", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = autoRoutePref,
                            onCheckedChange = {
                                autoRoutePref = it
                                UserPrefs.setAutoRoute(context, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentGreen,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Include Cloud APIs", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Let Auto use keyed cloud models for hard tasks (data leaves device)", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = autoCloudPref,
                            onCheckedChange = {
                                autoCloudPref = it
                                UserPrefs.setAutoCloud(context, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentGreen,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    var explainerPref by remember { mutableStateOf(UserPrefs.explainerMode(context)) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Premium Explainer Style", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Off = normal replies · On = always structured · auto for study asks", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = explainerPref,
                            onCheckedChange = {
                                explainerPref = it
                                UserPrefs.setExplainerMode(context, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentGreen,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    // Default chat effort (per-level colors, theme-aware)
                    Text("Default Chat Effort", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Low", "Medium", "High", "Max", "Extreme", "XHigh").forEach { e ->
                            val selected = defaultEffort == e
                            val tint = effortColor(e, colors)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) tint.copy(alpha = 0.18f) else colors.surfaceHover)
                                    .border(1.dp, if (selected) tint else colors.border, RoundedCornerShape(8.dp))
                                    .clickable {
                                        defaultEffort = e
                                        UserPrefs.setDefaultEffort(context, e)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(e, color = if (selected) tint else colors.textPrimary, fontSize = 11.5.sp)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("CPU Threads", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("${cpuThreads.toInt()} of ${Runtime.getRuntime().availableProcessors()} threads · local compute", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                    }
                    Slider(
                        value = cpuThreads,
                        onValueChange = { cpuThreads = it },
                        valueRange = 1f..Runtime.getRuntime().availableProcessors().toFloat(),
                        steps = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(0),
                        colors = SliderDefaults.colors(thumbColor = colors.accentGreen, activeTrackColor = colors.accentGreen)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Thermal Guard", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (thermalGuard) "Stop work above ${thermalLimit.toInt()}°C" else "Guard off — work runs unthrottled",
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = thermalGuard,
                            onCheckedChange = {
                                thermalGuard = it
                                UserPrefs.setThermalGuard(context, it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentGreen,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Trip point", color = colors.textSecondary, fontSize = 12.5.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = {
                                    thermalLimit = (thermalLimit - 1f).coerceAtLeast(40f)
                                    UserPrefs.setThermalLimitC(context, thermalLimit)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                            ) { Text("−", fontSize = 14.sp) }
                            Text(
                                "${thermalLimit.toInt()}°C",
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            )
                            OutlinedButton(
                                onClick = {
                                    thermalLimit = (thermalLimit + 1f).coerceAtMost(60f)
                                    UserPrefs.setThermalLimitC(context, thermalLimit)
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                            ) { Text("+", fontSize = 14.sp) }
                        }
                    }
                }
            }

            item {
                Text("General", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var saveHistory by remember { mutableStateOf(true) }
                var haptics by remember { mutableStateOf(true) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Save Chat History", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Keep conversations on-device", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = saveHistory,
                            onCheckedChange = { saveHistory = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentGreen,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Haptic Feedback", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Vibrate on key actions", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = haptics,
                            onCheckedChange = { haptics = it },
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

            item {
                Text("Privacy", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var incognito by remember { mutableStateOf(false) }
                var wipeOnExit by remember { mutableStateOf(false) }
                var webSearch by remember { mutableStateOf(UserPrefs.webSearchEnabled(context)) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Incognito Mode", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Don't persist new conversations", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(checked = incognito, onCheckedChange = { incognito = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accentGreen, uncheckedThumbColor = colors.textMuted, uncheckedTrackColor = colors.surfaceHover))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Wipe Data on Exit", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Clear cache when app closes", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(checked = wipeOnExit, onCheckedChange = { wipeOnExit = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accentGreen, uncheckedThumbColor = colors.textMuted, uncheckedTrackColor = colors.surfaceHover))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Web Search", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (UserPrefs.webSearchAsked(context)) "Look up fresh info online when needed"
                                else "Asks permission on first use",
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = webSearch,
                            onCheckedChange = {
                                webSearch = it
                                UserPrefs.setWebSearchEnabled(context, it)
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accentGreen, uncheckedThumbColor = colors.textMuted, uncheckedTrackColor = colors.surfaceHover)
                        )
                    }
                }
            }

            item {
                Text("Voice & Speech", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var speakResponses by remember { mutableStateOf(false) }
                var continuousListening by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Speak Responses", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Read Karen's replies aloud", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(checked = speakResponses, onCheckedChange = { speakResponses = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accentGreen, uncheckedThumbColor = colors.textMuted, uncheckedTrackColor = colors.surfaceHover))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Continuous Listening", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Keep mic active after reply", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(checked = continuousListening, onCheckedChange = { continuousListening = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accentGreen, uncheckedThumbColor = colors.textMuted, uncheckedTrackColor = colors.surfaceHover))
                    }
                }
            }

            item {
                Text("Memory", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val device = com.karen.rememberDeviceTelemetry()
                var maxRamMb by remember { mutableStateOf((device.totalRamGb * 1024.0 * 0.5).coerceAtLeast(512.0).toFloat()) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Total RAM", color = colors.textSecondary, fontSize = 12.5.sp)
                        Text(String.format("%.1f GB", device.totalRamGb), color = colors.textPrimary, fontSize = 12.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Free RAM", color = colors.textSecondary, fontSize = 12.5.sp)
                        Text(String.format("%.1f GB", device.freeRamGb), color = colors.accentGreen, fontSize = 12.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Used", color = colors.textSecondary, fontSize = 12.5.sp)
                        Text("${device.usedRamPercent}%", color = colors.textPrimary, fontSize = 12.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    LinearProgressIndicator(
                        progress = { (device.usedRamPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = colors.accentGreen,
                        trackColor = colors.surfaceHover
                    )
                    Text("Max RAM for models: ${maxRamMb.toInt()} MB", color = colors.textMuted, fontSize = 11.5.sp)
                    Slider(
                        value = maxRamMb,
                        onValueChange = { maxRamMb = it },
                        valueRange = 512f..(device.totalRamGb * 1024.0).coerceAtLeast(1024.0).toFloat(),
                        steps = 15,
                        colors = SliderDefaults.colors(thumbColor = colors.accentGreen, activeTrackColor = colors.accentGreen)
                    )
                    OutlinedButton(onClick = { /* flush cache */ }, shape = RoundedCornerShape(10.dp)) {
                        Text("Free Up Memory")
                    }
                }
            }

            item {
                Text("Storage", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var autoIndex by remember { mutableStateOf(true) }
                var workspaceUri by remember { mutableStateOf(UserPrefs.workspaceUri(context)) }
                val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    if (uri != null) {
                        try {
                            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        } catch (_: Exception) {}
                        UserPrefs.setWorkspaceUri(context, uri.toString())
                        workspaceUri = uri.toString()
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-Index Vault", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Embed new files automatically", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(checked = autoIndex, onCheckedChange = { autoIndex = it }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accentGreen, uncheckedThumbColor = colors.textMuted, uncheckedTrackColor = colors.surfaceHover))
                    }
                    OutlinedButton(onClick = { /* clear cache */ }, shape = RoundedCornerShape(10.dp)) {
                        Text("Clear Model Cache")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Working Area", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                workspaceUri ?: "Default: ${context.filesDir.absolutePath}",
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                        TextButton(onClick = { folderPicker.launch(null) }) {
                            Text(if (workspaceUri == null) "Set" else "Change", color = colors.accentGreen)
                        }
                    }
                }
            }

            item {
                Text("Project Manager", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var storedProjects by remember { mutableStateOf(UserPrefs.projects(context)) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (storedProjects.isEmpty()) {
                        Text("No data found", color = colors.textMuted, fontSize = 12.sp)
                    } else {
                        storedProjects.forEach { (name, dir) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text(dir, color = colors.textMuted, fontSize = 10.5.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
                                }
                                IconButton(onClick = {
                                    val updated = storedProjects.filter { it.first != name }
                                    UserPrefs.saveProjects(context, updated)
                                    storedProjects = UserPrefs.projects(context)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = colors.accentRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text("About", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Karen · Sovereign on-device AI", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            UserPrefs.age(context) == "-" && UserPrefs.type(context) == "-" -> "No data found"
                            else -> "Profile: ${UserPrefs.type(context)}, age ${UserPrefs.age(context)}"
                        },
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (editing) {
            AlertDialog(
                onDismissRequest = { editing = false },
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                confirmButton = {
                    TextButton(onClick = {
                        if (editName.isNotBlank()) {
                            UserPrefs.save(context, editName.trim(), editAge, editType)
                        }
                        editing = false
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
                title = { Text("Edit Profile") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Name", color = colors.textMuted) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                            colors = karenFieldColors(colors)
                        )
                        OutlinedTextField(
                            value = editAge,
                            onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) editAge = it },
                            label = { Text("Age", color = colors.textMuted) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                            colors = karenFieldColors(colors)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Student", "Personal", "Developer", "Researcher", "Professional").forEach { t ->
                                val selected = editType == t
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) colors.accentGreen.copy(alpha = 0.2f) else colors.surfaceHover)
                                        .border(1.dp, if (selected) colors.accentGreen else colors.border, RoundedCornerShape(8.dp))
                                        .clickable { editType = t }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(t, color = if (selected) colors.accentGreen else colors.textPrimary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}
