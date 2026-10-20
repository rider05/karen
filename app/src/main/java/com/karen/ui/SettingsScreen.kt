package com.karen.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.karen.WakeWord
import com.karen.WakeWordService

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(onBack: () -> Unit = {}) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val currentMode = LocalKarenThemeMode.current

    var editing by remember { mutableStateOf(false) }
    var showClearCache by remember { mutableStateOf(false) }

    // Fish Audio voice selection lives at screen level so the picker dialog
    // (rendered below the list) and the settings card stay in sync.
    var fishVoice by remember { mutableStateOf(UserPrefs.fishVoiceId(context)) }
    var fishVoiceName by remember { mutableStateOf(UserPrefs.fishVoiceName(context)) }
    var showVoicePicker by remember { mutableStateOf(false) }

    KarenHomeBackHandler(onNavigateToHome = onBack) {
        when {
            showVoicePicker -> { showVoicePicker = false; true }
            editing -> { editing = false; true }
            showClearCache -> { showClearCache = false; true }
            else -> false
        }
    }
    var editName by remember(editing) { mutableStateOf(UserPrefs.name(context)) }
    var editAge by remember(editing) { mutableStateOf(UserPrefs.age(context).takeIf { it != "-" } ?: "") }
    var editType by remember(editing) { mutableStateOf(UserPrefs.type(context).takeIf { it != "-" } ?: "Personal") }

    var dobMillis by remember { mutableStateOf(UserPrefs.dob(context)) }
    var showDobPicker by remember { mutableStateOf(false) }

    // Update card state — hoisted to screen level so the clear-cache dialog
    // can be rendered with the other dialogs below the list.
    var updateStatus by remember { mutableStateOf("Installed: ${AppUpdate.currentVersion(context)}") }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var pendingSha by remember { mutableStateOf<String?>(null) }
    var pendingDate by remember { mutableStateOf("") }
    var releaseNotes by remember { mutableStateOf("") }
    var downloadedFile by remember { mutableStateOf(AppUpdate.pendingApk(context)) }
    var justInstalled by remember { mutableStateOf(AppUpdate.consumeJustInstalled(context)) }
    var cacheBytes by remember { mutableStateOf(AppUpdate.cacheSizeBytes(context)) }
    val updateScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Scaffold already applies the status-bar inset — keep only a
                // small margin so the header sits at the same height as the
                // shared ChatGPTTopAppBar used elsewhere.
                .padding(top = 6.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp),
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
            contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
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
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Date of birth", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (dobMillis > 0L) formatDob(dobMillis) else "Not set — Karen can wish you a happy birthday",
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        TextButton(onClick = { showDobPicker = true }) {
                            Text(if (dobMillis > 0L) "Change" else "Set", color = colors.accentGreen)
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
                    // Model routing mode: auto-switching vs the selected model.
                    var autoRoutePref by remember { mutableStateOf(UserPrefs.autoRoute(context)) }
                    var autoCloudPref by remember { mutableStateOf(UserPrefs.autoCloud(context)) }
                    var apiFailoverPref by remember { mutableStateOf(UserPrefs.apiFailover(context)) }
                    var modelFallbackPref by remember { mutableStateOf(UserPrefs.modelFallback(context)) }
                    var localFallbackPref by remember { mutableStateOf(UserPrefs.cloudLocalFallback(context)) }
                    Text("Model Routing", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(true to "Auto-switching", false to "Selected model").forEach { (value, label) ->
                            val selected = autoRoutePref == value
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentGreen.copy(alpha = 0.18f) else colors.surfaceHover)
                                    .border(1.dp, if (selected) colors.accentGreen else colors.border, RoundedCornerShape(8.dp))
                                    .clickable {
                                        autoRoutePref = value
                                        UserPrefs.setAutoRoute(context, value)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(label, color = if (selected) colors.accentGreen else colors.textPrimary, fontSize = 11.5.sp)
                            }
                        }
                    }
                    Text(
                        if (autoRoutePref) "Code → strongest · reasoning → thinker · quick chats → fastest"
                        else "Pinned: ${UserPrefs.selectedModel(context).ifBlank { "(pick one in the model sheet)" }} — every surface uses it",
                        color = colors.textMuted,
                        fontSize = 11.5.sp
                    )
                    // Preferred models: favored defaults for local and API calls.
                    var preferredLocal by remember { mutableStateOf(UserPrefs.preferredLocalModel(context)) }
                    var preferredApi by remember { mutableStateOf(UserPrefs.preferredApiModel(context)) }
                    var localMenu by remember { mutableStateOf(false) }
                    var apiMenu by remember { mutableStateOf(false) }
                    val localOptions = remember {
                        listOf("") + UserPrefs.models(context).filter { !isCloudProviderName(it) }
                    }
                    val apiOptions = remember {
                        listOf("") + allCloudProviders(context)
                            .filter { UserPrefs.apiKey(context, it.id).isNotBlank() }
                            .map { it.name }
                    }
                    fun preferredLabel(value: String, kind: String): String =
                        if (value.isBlank()) "None (auto)" else value
                    listOf(
                        Triple(
                            "Preferred on-device model",
                            "Local fallback, server default, and routing favorite",
                            0
                        ),
                        Triple("Preferred cloud API", "Routing favorite when a cloud model fits", 1)
                    ).forEach { (title, sub, which) ->
                        val current = if (which == 0) preferredLocal else preferredApi
                        val expanded = if (which == 0) localMenu else apiMenu
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.surfaceHover.copy(alpha = 0.55f))
                                    .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                    .clickable { if (which == 0) localMenu = true else apiMenu = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        preferredLabel(current, title) + (if (which == 0) "" else " · keyed only"),
                                        color = if (current.isBlank()) colors.textMuted else colors.accentGreen,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Choose",
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            KDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { if (which == 0) localMenu = false else apiMenu = false }
                            ) {
                                (if (which == 0) localOptions else apiOptions).forEachIndexed { idx, option ->
                                    // Divider between "None (auto)" and the concrete list.
                                    if (idx == 1) KMenuDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (option.isBlank()) "None (auto)" else option,
                                                color = if (option == current) colors.accentGreen else colors.textPrimary,
                                                fontSize = 13.sp
                                            )
                                        },
                                        onClick = {
                                            if (which == 0) {
                                                preferredLocal = option
                                                UserPrefs.setPreferredLocalModel(context, option)
                                                localMenu = false
                                            } else {
                                                preferredApi = option
                                                UserPrefs.setPreferredApiModel(context, option)
                                                apiMenu = false
                                            }
                                        }
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                sub,
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
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
                            Text("Auto-switch API on failure", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Quota/network error tries the next keyed API in the same turn", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = apiFailoverPref,
                            onCheckedChange = {
                                apiFailoverPref = it
                                UserPrefs.setApiFailover(context, it)
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
                            Text("Model fallback (same provider)", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("Token/context limit hit → retry on another model from the same API, largest window first", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = modelFallbackPref,
                            onCheckedChange = {
                                modelFallbackPref = it
                                UserPrefs.setModelFallback(context, it)
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
                            Text("On-device fallback", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text("All APIs failed → continue on the first runnable local weight", color = colors.textMuted, fontSize = 11.5.sp)
                        }
                        Switch(
                            checked = localFallbackPref,
                            onCheckedChange = {
                                localFallbackPref = it
                                UserPrefs.setCloudLocalFallback(context, it)
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
                            Text("SmartUI", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
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
                    var exaKeyInput by remember { mutableStateOf("") }
                    var exaSaved by remember { mutableStateOf(UserPrefs.apiKey(context, "exa").isNotBlank()) }
                    var exaVisible by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column {
                            Text("Exa search key (optional)", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (exaSaved) "Exa answers first · key stored on-device" else "Neural search with live snippets (exa.ai) · falls back to keyless",
                                color = if (exaSaved) colors.accentGreen else colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = if (exaSaved) FontFamily.Monospace else null
                            )
                        }
                        if (!exaSaved) {
                            OutlinedTextField(
                                value = exaKeyInput,
                                onValueChange = { exaKeyInput = it.trim().take(200) },
                                label = { Text("Paste Exa API key", color = colors.textMuted) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = colors.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                colors = karenFieldColors(colors),
                                visualTransformation = if (exaVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { exaVisible = !exaVisible }, modifier = Modifier.size(28.dp)) {
                                        Icon(
                                            if (exaVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (exaVisible) "Hide key" else "Show key",
                                            tint = colors.textMuted,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    if (exaKeyInput.isNotBlank()) {
                                        UserPrefs.saveApiKey(context, "exa", exaKeyInput)
                                        exaKeyInput = ""
                                        exaSaved = true
                                    }
                                }) { Text("Save", color = colors.accentGreen, fontSize = 12.sp) }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "••••" + UserPrefs.apiKey(context, "exa").takeLast(4),
                                    color = colors.textMuted,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                TextButton(onClick = {
                                    UserPrefs.clearApiKey(context, "exa")
                                    exaSaved = false
                                }) { Text("Remove", color = colors.accentRed, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }

            item {
                Text("Voice & Speech", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var speakResponses by remember { mutableStateOf(false) }
                var continuousListening by remember { mutableStateOf(false) }
                var wakeEnabled by remember { mutableStateOf(UserPrefs.wakeEnabled(context)) }
                var wakePhrase by remember { mutableStateOf(UserPrefs.wakePhrase(context)) }
                var wakeDenied by remember { mutableStateOf(false) }
                var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
                val overlayLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) {
                    overlayOk = try {
                        Settings.canDrawOverlays(context)
                    } catch (_: Exception) {
                        false
                    }
                }
                androidx.compose.runtime.LaunchedEffect(wakeEnabled) {
                    overlayOk = try {
                        Settings.canDrawOverlays(context)
                    } catch (_: Exception) {
                        false
                    }
                }
                val micPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    if (granted) {
                        wakeEnabled = true
                        UserPrefs.setWakeEnabled(context, true)
                        UserPrefs.setWakePhrase(context, wakePhrase.ifBlank { WakeWord.DEFAULT_PHRASE })
                        wakeDenied = false
                        try {
                            WakeWordService.start(context)
                        } catch (_: Exception) {
                        }
                    } else {
                        wakeEnabled = false
                        UserPrefs.setWakeEnabled(context, false)
                        wakeDenied = true
                    }
                }
                fun applyWake(enabled: Boolean) {
                    if (enabled) {
                        val micOk = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!micOk) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return
                        }
                        wakeEnabled = true
                        UserPrefs.setWakeEnabled(context, true)
                        UserPrefs.setWakePhrase(context, wakePhrase.ifBlank { WakeWord.DEFAULT_PHRASE })
                        wakeDenied = false
                        try {
                            WakeWordService.start(context)
                        } catch (_: Exception) {
                        }
                    } else {
                        wakeEnabled = false
                        UserPrefs.setWakeEnabled(context, false)
                        try {
                            WakeWordService.stop(context)
                        } catch (_: Exception) {
                        }
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
                    var fishKeySaved by remember { mutableStateOf(UserPrefs.fishAudioKey(context).isNotBlank()) }
                    val openRouterReady = UserPrefs.apiKey(context, "openrouter").isNotBlank()
                    val anyVoiceKey = fishKeySaved || openRouterReady
                    var fishTtsPref by remember { mutableStateOf(UserPrefs.fishTts(context)) }
                    var fishModel by remember { mutableStateOf(UserPrefs.fishModel(context)) }
                    var modelMenu by remember { mutableStateOf(false) }
                    var modelCustom by remember {
                        mutableStateOf(CloudTts.KNOWN_MODELS.none { it.id == fishModel })
                    }
                    val modelInfo = CloudTts.KNOWN_MODELS.firstOrNull { it.id == fishModel }
                    val modelLabel = modelInfo?.label ?: fishModel.ifBlank { CloudTts.FISH_MODEL }
                    val voiceLabel = when {
                        fishVoice.isBlank() -> "Energetic Male (default)"
                        fishVoiceName.isNotBlank() -> fishVoiceName
                        else -> CloudTts.KNOWN_VOICES.firstOrNull { it.id == fishVoice }?.title
                            ?: "Custom voice"
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
                            Text("Fish Audio TTS", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    fishKeySaved -> "Direct Fish Audio · $modelLabel · $voiceLabel"
                                    openRouterReady -> "Via OpenRouter · ${CloudTts.openRouterModelFor(fishModel)}"
                                    else -> "Add a Fish Audio or OpenRouter key to enable the free cloud voice"
                                },
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = fishTtsPref && anyVoiceKey,
                            onCheckedChange = {
                                fishTtsPref = it
                                UserPrefs.setFishTts(context, it)
                            },
                            enabled = anyVoiceKey,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentGreen,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    if (anyVoiceKey) {
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(colors.surfaceHover.copy(alpha = 0.55f))
                                    .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                    .clickable { modelMenu = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Fish Audio model", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        modelInfo?.note ?: "Custom model header",
                                        color = colors.textMuted,
                                        fontSize = 11.5.sp
                                    )
                                }
                                Text(
                                    modelLabel,
                                    color = colors.accentBlue,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Choose model",
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            KDropdownMenu(
                                expanded = modelMenu,
                                onDismissRequest = { modelMenu = false }
                            ) {
                                CloudTts.KNOWN_MODELS.forEach { m ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    m.id,
                                                    color = if (m.id == fishModel) colors.accentGreen else colors.textPrimary,
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(
                                                            if (m.free) colors.accentGreen.copy(alpha = 0.18f)
                                                            else colors.accentAmber.copy(alpha = 0.16f)
                                                        )
                                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        if (m.free) "FREE" else "PAID",
                                                        color = if (m.free) colors.accentGreen else colors.accentAmber,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            fishModel = m.id
                                            UserPrefs.setFishModel(context, m.id)
                                            modelCustom = false
                                            modelMenu = false
                                        },
                                        modifier = Modifier
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                }
                                KMenuDivider()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Custom model id…",
                                            color = if (modelCustom) colors.accentGreen else colors.textPrimary,
                                            fontSize = 13.sp
                                        )
                                    },
                                    onClick = {
                                        modelCustom = true
                                        modelMenu = false
                                    },
                                    modifier = Modifier
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            }
                        }
                        if (modelCustom) {
                            OutlinedTextField(
                                value = fishModel,
                                onValueChange = {
                                    fishModel = it.trim().take(60)
                                    UserPrefs.setFishModel(context, fishModel)
                                },
                                label = { Text("Fish Audio model id", color = colors.textMuted) },
                                placeholder = { Text(CloudTts.FISH_MODEL, color = colors.textMuted) },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = colors.textPrimary,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                ),
                                colors = karenFieldColors(colors),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Sent as the `model` header to api.fish.audio. Blank falls back to ${CloudTts.FISH_MODEL}.",
                                color = colors.textMuted,
                                fontSize = 11.sp
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceHover.copy(alpha = 0.55f))
                                .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .clickable { showVoicePicker = true }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Voice", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    if (fishVoice.isBlank()) "Energetic Male · default"
                                    else "$voiceLabel · ${fishVoice.take(8)}…",
                                    color = colors.textMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                            Text("Choose", color = colors.accentBlue, fontSize = 12.sp)
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = "Choose voice",
                                tint = colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            "Search Fish Audio's voice library or paste any reference ID. Direct TTS uses this voice; OpenRouter uses it when set.",
                            color = colors.textMuted,
                            fontSize = 11.sp
                        )
                    }
                    var fishKeyInput by remember { mutableStateOf("") }
                    var fishKeyVisible by remember { mutableStateOf(false) }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.55f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Fish Audio API key", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        if (fishKeySaved) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "••••" + UserPrefs.fishAudioKey(context).takeLast(4),
                                    color = colors.textMuted,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                TextButton(onClick = {
                                    UserPrefs.clearFishAudioKey(context)
                                    fishKeySaved = false
                                    fishKeyInput = ""
                                }) { Text("Remove", color = colors.accentRed, fontSize = 12.sp) }
                            }
                        }
                        OutlinedTextField(
                            value = fishKeyInput,
                            onValueChange = { fishKeyInput = it.trim() },
                            label = {
                                Text(
                                    if (fishKeySaved) "Replace key" else "Paste sk-fish-… key",
                                    color = colors.textMuted
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (fishKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { fishKeyVisible = !fishKeyVisible }) {
                                    Text(
                                        if (fishKeyVisible) "Hide" else "Show",
                                        color = colors.accentBlue,
                                        fontSize = 11.sp
                                    )
                                }
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = colors.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            colors = karenFieldColors(colors),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "fish.audio/app/api-keys · stored only on this device",
                                color = colors.textMuted,
                                fontSize = 11.sp
                            )
                            TextButton(
                                enabled = fishKeyInput.isNotBlank(),
                                onClick = {
                                    UserPrefs.saveFishAudioKey(context, fishKeyInput)
                                    fishKeyInput = ""
                                    fishKeyVisible = false
                                    fishKeySaved = true
                                }
                            ) {
                                Text(
                                    "Save",
                                    color = if (fishKeyInput.isNotBlank()) colors.accentGreen else colors.textMuted,
                                    fontSize = 12.sp
                                )
                            }
                        }
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
                            Text("Wake Word — “Hey Jarvis”", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (WakeWordService.running) "Listening in background · say “$wakePhrase” to open Live mode"
                                else "Opens Live mode even when the app is closed",
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = wakeEnabled,
                            onCheckedChange = { applyWake(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.accentGreen, uncheckedThumbColor = colors.textMuted, uncheckedTrackColor = colors.surfaceHover)
                        )
                    }
                    OutlinedTextField(
                        value = wakePhrase,
                        onValueChange = {
                            wakePhrase = it.take(40)
                            UserPrefs.setWakePhrase(context, wakePhrase.ifBlank { WakeWord.DEFAULT_PHRASE })
                        },
                        label = { Text("Wake phrase (custom word later)", color = colors.textMuted) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                        colors = karenFieldColors(colors),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (wakeDenied) "Microphone permission denied — wake word stays off until granted."
                        else "Battery note: the listener holds the mic open. Off by default; survives reboot when on.",
                        color = if (wakeDenied) colors.accentRed else colors.textMuted,
                        fontSize = 11.5.sp
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
                            Text("Direct open", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (overlayOk) "On — the app opens immediately on the wake phrase"
                                else "Needs “Display over other apps”, or wake falls back to tap-to-open",
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        if (!overlayOk) {
                            TextButton(onClick = {
                                try {
                                    overlayLauncher.launch(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:" + context.packageName)
                                        )
                                    )
                                } catch (_: Exception) {
                                }
                            }) {
                                Text("Allow", color = colors.accentGreen)
                            }
                        }
                    }
                }
            }

            item {
                Text("Calls", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var callMode by remember { mutableStateOf(UserPrefs.callBehavior(context)) }
                var callPermOk by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val callPermLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    callPermOk = granted
                    if (!granted) {
                        android.widget.Toast.makeText(context, "Without the phone permission, calls open the dialer", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("How /place_call runs", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            UserPrefs.CALL_ASK to "Ask every time",
                            UserPrefs.CALL_DIRECT to "Call directly",
                            UserPrefs.CALL_DIALER to "Dialer only"
                        ).forEach { (value, label) ->
                            val selected = callMode == value
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.accentGreen.copy(alpha = 0.18f) else colors.surfaceHover)
                                    .border(1.dp, if (selected) colors.accentGreen else colors.border, RoundedCornerShape(8.dp))
                                    .clickable {
                                        callMode = value
                                        UserPrefs.setCallBehavior(context, value)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(label, color = if (selected) colors.accentGreen else colors.textPrimary, fontSize = 11.5.sp)
                            }
                        }
                    }
                    Text(
                        when (callMode) {
                            UserPrefs.CALL_DIRECT -> "Runs immediately after matching the contact. Needs the phone permission, or it opens the dialer."
                            UserPrefs.CALL_DIALER -> "Always opens the dialer with the number filled — never places a call by itself."
                            else -> "Matches the contact, then asks Run/Cancel every time. Default."
                        },
                        color = colors.textMuted,
                        fontSize = 11.5.sp
                    )
                    if (callMode == UserPrefs.CALL_DIRECT && !callPermOk) {
                        OutlinedButton(
                            onClick = { callPermLauncher.launch(Manifest.permission.CALL_PHONE) },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Grant phone permission", fontSize = 12.sp)
                        }
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
                        Text("No projects yet — add one to map a folder to a named workspace.", color = colors.textMuted, fontSize = 12.sp)
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
                Text("Local Server", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                var serverOn by remember { mutableStateOf(LocalApiServer.running) }
                var serverPortText by remember { mutableStateOf(UserPrefs.serverPort(context).toString()) }
                var serverLanOn by remember { mutableStateOf(UserPrefs.serverLan(context)) }
                var serverTokenShown by remember { mutableStateOf(false) }
                var serverTokenTick by remember { mutableStateOf(0) }
                val serverTokenValue = remember(serverTokenTick) { LocalApiServer.token(context) }
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                // Keep the toggle honest if the server died with the process.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    serverOn = LocalApiServer.running
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (serverOn) "Running · ${LocalApiServer.status}" else "Stopped",
                                color = if (serverOn) colors.accentGreen else colors.textPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "OpenAI-compatible API for your own apps (chat + models) · dashboard at /dashboard, worker page at / on your Wi-Fi",
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = serverOn,
                            onCheckedChange = { want ->
                                serverOn = if (want) {
                                    LocalApiServer.start(context)
                                } else {
                                    LocalApiServer.stop()
                                    false
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentGreen,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    OutlinedTextField(
                        value = serverPortText,
                        onValueChange = { v ->
                            if (v.all { it.isDigit() } && v.length <= 5) {
                                serverPortText = v
                                v.toIntOrNull()?.let { UserPrefs.setServerPort(context, it) }
                            }
                        },
                        label = { Text("Port (11434 = Ollama)", color = colors.textMuted) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = karenFieldColors(colors),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Expose on local network", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (serverLanOn) "Anyone on your Wi-Fi with the token can call it" else "Localhost only",
                                color = if (serverLanOn) colors.accentAmber else colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        Switch(
                            checked = serverLanOn,
                            onCheckedChange = {
                                serverLanOn = it
                                UserPrefs.setServerLan(context, it)
                                if (serverOn) {
                                    LocalApiServer.stop()
                                    serverOn = LocalApiServer.start(context)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = colors.accentAmber,
                                uncheckedThumbColor = colors.textMuted,
                                uncheckedTrackColor = colors.surfaceHover
                            )
                        )
                    }
                    // Always visible: tells you exactly what other devices
                    // should open, and what is still missing when they can't.
                    val pageUrl = remember(serverOn, serverLanOn, serverPortText) { LocalApiServer.lanUrl(context) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dashboard (other devices)", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                pageUrl,
                                color = if (serverOn && serverLanOn) colors.accentGreen else colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (!serverLanOn) {
                                Text(
                                    "Enable \"Expose on local network\" to open it from other devices",
                                    color = colors.accentAmber,
                                    fontSize = 11.sp
                                )
                            } else if (pageUrl.contains("127.0.0.1")) {
                                Text(
                                    "No Wi-Fi LAN address — connect the phone to Wi-Fi first",
                                    color = colors.accentAmber,
                                    fontSize = 11.sp
                                )
                            } else if (!serverOn) {
                                Text("Start the server above to open it", color = colors.textMuted, fontSize = 11.sp)
                            } else {
                                Text("No token to open the page — chat needs the token from below", color = colors.textMuted, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(pageUrl))
                            android.widget.Toast.makeText(context, "Address copied — open it in any browser", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Copy", color = colors.accentBlue, fontSize = 12.sp)
                        }
                    }
                    // Same page from this phone itself — reachable even with LAN off.
                    val localPageUrl = "http://127.0.0.1:${UserPrefs.serverPort(context)}"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dashboard (this phone)", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                localPageUrl,
                                color = if (serverOn) colors.accentGreen else colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            if (!serverOn) {
                                Text("Start the server above to open it", color = colors.textMuted, fontSize = 11.sp)
                            }
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(localPageUrl))
                            android.widget.Toast.makeText(context, "Local address copied", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Copy", color = colors.accentBlue, fontSize = 12.sp)
                        }
                    }
                    // Diagnostics: proves the server answers and shows whether
                    // another device's request ever arrived. The counters move
                    // on every HTTP hit (each worker poll included), so they
                    // are sampled on a timer — this card keeps a fixed height
                    // and the cards below it never re-layout per request.
                    var diagCount by remember { mutableStateOf(0) }
                    var diagLast by remember { mutableStateOf("") }
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        while (true) {
                            diagCount = LocalApiServer.requestCount
                            diagLast = LocalApiServer.lastRequest
                            kotlinx.coroutines.delay(2000)
                        }
                    }
                    var selfTestText by remember { mutableStateOf("") }
                    var selfTesting by remember { mutableStateOf(false) }
                    val diagScope = rememberCoroutineScope()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Diagnostics", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (diagCount == 0) "No requests received yet"
                                else "$diagCount request(s) received",
                                color = colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                if (diagLast.isBlank()) "Last: —" else "Last: $diagLast",
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                minLines = 2,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            if (selfTestText.isNotBlank()) {
                                Text(
                                    selfTestText,
                                    color = if (selfTestText.contains("OK")) colors.accentGreen else colors.accentAmber,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        TextButton(onClick = {
                            if (!selfTesting) {
                                selfTesting = true
                                selfTestText = ""
                                diagScope.launch {
                                    selfTestText = LocalApiServer.selfTest(context)
                                    selfTesting = false
                                }
                            }
                        }) {
                            Text(if (selfTesting) "Testing…" else "Self-test", color = colors.accentBlue, fontSize = 12.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Bearer token", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (serverTokenShown) serverTokenValue else "••••" + serverTokenValue.takeLast(4),
                                color = colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        TextButton(onClick = { serverTokenShown = !serverTokenShown }) {
                            Text(if (serverTokenShown) "Hide" else "Show", color = colors.accentBlue, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(serverTokenValue))
                            android.widget.Toast.makeText(context, "Token copied", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Text("Copy", color = colors.accentBlue, fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            UserPrefs.setServerToken(context, "")
                            serverTokenTick++
                            if (serverOn) {
                                LocalApiServer.stop()
                                serverOn = LocalApiServer.start(context)
                            }
                        }) {
                            Text("Regenerate", color = colors.accentAmber, fontSize = 12.sp)
                        }
                    }
                    Text(
                        "Runs while Karen is open. Example: curl -H \"Authorization: Bearer <token>\" http://127.0.0.1:${UserPrefs.serverPort(context)}/v1/models",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            item {
                Text("Updates", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(updateStatus, color = colors.textPrimary, fontSize = 13.sp)
                    if (justInstalled) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.accentGreen.copy(alpha = 0.12f))
                                .border(1.dp, colors.accentGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Update installed successfully", color = colors.accentGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (releaseNotes.isNotBlank() && pendingSha != null) {
                        Spacer(Modifier.height(6.dp))
                        // Release notes are the full commit message — cap the box and
                        // let them scroll inside it instead of stretching the page.
                        val notesOverflow = releaseNotes.length > 380 || releaseNotes.lines().size > 8
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceHover.copy(alpha = 0.4f))
                                .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Release notes", color = colors.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                if (notesOverflow) {
                                    Spacer(Modifier.weight(1f))
                                    Text("scroll ↕", color = colors.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 140.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    releaseNotes,
                                    color = colors.textSecondary,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                    if (downloading) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = colors.accentGreen,
                            trackColor = colors.surfaceHover
                        )
                        Text(
                            "${(progress.coerceIn(0f, 1f) * 100).toInt()}%${if (paused) " · paused" else ""}",
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (checking || downloading) return@OutlinedButton
                                checking = true
                                updateStatus = "Checking ${AppUpdate.REPO}…"
                                updateScope.launch {
                                    try {
                                        when (val res = AppUpdate.check(context)) {
                                            is AppUpdate.Check.UpToDate -> {
                                                pendingSha = null
                                                releaseNotes = ""
                                                updateStatus = "Up to date — installed matches ${AppUpdate.APK_PATH}"
                                            }
                                            is AppUpdate.Check.UpdateAvailable -> {
                                                pendingSha = res.sha
                                                pendingDate = res.date
                                                releaseNotes = res.releaseNotes
                                                updateStatus = if (AppUpdate.storedSha(context).isBlank()) {
                                                    "Installed version unknown — latest is ${res.sha.take(7)} (${
                                                        res.date.ifBlank { "unknown date" }
                                                    }). Download to sync."
                                                } else {
                                                    "Update available: ${res.sha.take(7)} (${
                                                        res.date.ifBlank { "unknown date" }
                                                    })"
                                                }
                                            }
                                            is AppUpdate.Check.Failed -> {
                                                pendingSha = null
                                                updateStatus = "Check failed: ${res.reason}"
                                            }
                                        }
                                    } finally {
                                        checking = false
                                    }
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            enabled = !checking && !downloading
                        ) {
                            Text(if (checking) "Checking…" else "Check for update", fontSize = 12.sp)
                        }
                        val sha = pendingSha
                        val downloaded = downloadedFile
                        if (sha != null && (downloaded == null || !downloaded.exists())) {
                            Button(
                                onClick = {
                                    if (checking || downloading) return@Button
                                    downloading = true
                                    paused = false
                                    AppUpdate.setDownloadPaused(false)
                                    val partFile = java.io.File(context.getExternalFilesDir(null), "updates/karen.apk.part")
                                    val hasPartial = partFile.exists() && partFile.length() > 0
                                    if (hasPartial) {
                                        // Show resume progress immediately.
                                        val total = AppUpdate.probeLength()
                                        progress = if (total != null && total > 0) (partFile.length().toFloat() / total).coerceIn(0f, 1f) else 0f
                                    } else {
                                        progress = 0f
                                    }
                                    updateStatus = if (hasPartial) "Resuming download…" else "Downloading ${AppUpdate.APK_PATH}…"
                                    updateScope.launch {
                                        try {
                                            val errors = mutableListOf<String>()
                                            val partFile = java.io.File(context.getExternalFilesDir(null), "updates/karen.apk.part")
                                            val hasPartial = partFile.exists() && partFile.length() > 0
                                            val file = AppUpdate.download(context, sha, { p -> progress = p }, errors, resume = hasPartial)
                                            if (file != null) {
                                                pendingSha = null
                                                downloadedFile = file
                                                updateStatus = "Download complete — tap Install to update."
                                            } else {
                                                updateStatus = "Download failed: ${errors.firstOrNull() ?: "unknown error"}"
                                            }
                                        } finally {
                                            downloading = false
                                            paused = false
                                            AppUpdate.setDownloadPaused(false)
                                            cacheBytes = AppUpdate.cacheSizeBytes(context)
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentGreen,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !checking && !downloading
                            ) {
                                Text("Download", fontSize = 12.sp)
                            }
                        }
                        if (downloading) {
                            OutlinedButton(
                                onClick = {
                                    val next = !paused
                                    paused = next
                                    AppUpdate.setDownloadPaused(next)
                                    updateStatus = if (next) {
                                        "Paused at ${(progress.coerceIn(0f, 1f) * 100).toInt()}% — tap Resume to continue."
                                    } else {
                                        "Downloading ${AppUpdate.APK_PATH}…"
                                    }
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(if (paused) "Resume" else "Pause", fontSize = 12.sp)
                            }
                        }
                        if (downloaded != null && downloaded.exists() && !downloading) {
                            Button(
                                onClick = {
                                    updateScope.launch {
                                        updateStatus = AppUpdate.installAndReopen(context, downloaded)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentGreen,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                enabled = !checking
                            ) {
                                Text("Install", fontSize = 12.sp)
                            }
                        }
                    }
                    if (cacheBytes > 0L && !downloading) {
                        OutlinedButton(
                            onClick = { showClearCache = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Clear download cache (${fmtBytes(cacheBytes)})", fontSize = 12.sp)
                        }
                    }
                    Text(
                        "Source: github.com/${AppUpdate.REPO}/blob/main/${AppUpdate.APK_PATH}",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
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
                            UserPrefs.age(context) == "-" && UserPrefs.type(context) == "-" -> "No profile set yet — add your name and age above"
                            else -> "Profile: ${UserPrefs.type(context)}, age ${UserPrefs.age(context)}"
                        },
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (editing) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { editing = false }) {
                val canSave = editName.isNotBlank()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header: live avatar preview + title.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(colors.accentGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                editName.firstOrNull()?.uppercase() ?: "-",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Edit Profile", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Text("Personalize your Karen", color = colors.textMuted, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = colors.border, thickness = 0.5.dp)
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Name", color = colors.textMuted) },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = colors.textMuted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                        colors = karenFieldColors(colors)
                    )
                    OutlinedTextField(
                        value = editAge,
                        onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) editAge = it },
                        label = { Text("Age", color = colors.textMuted) },
                        leadingIcon = { Icon(Icons.Default.Cake, contentDescription = null, tint = colors.textMuted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                        colors = karenFieldColors(colors)
                    )
                    Text("Profile type", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Student", "Personal", "Developer", "Researcher", "Professional").forEach { t ->
                            val selected = editType == t
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) colors.accentGreen.copy(alpha = 0.2f) else colors.surfaceHover)
                                    .border(1.dp, if (selected) colors.accentGreen else colors.border, RoundedCornerShape(10.dp))
                                    .clickable { editType = t }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text(
                                    t,
                                    color = if (selected) colors.accentGreen else colors.textPrimary,
                                    fontSize = 12.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { editing = false }) {
                            Text("Cancel", color = colors.textMuted)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                UserPrefs.save(context, editName.trim(), editAge, editType)
                                editing = false
                            },
                            enabled = canSave,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentGreen,
                                contentColor = Color.White,
                                disabledContainerColor = colors.surfaceHover,
                                disabledContentColor = colors.textMuted
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
        if (showVoicePicker) {
            FishVoicePickerDialog(
                colors = colors,
                hasKey = UserPrefs.fishAudioKey(context).isNotBlank(),
                selectedId = fishVoice,
                selectedName = fishVoiceName,
                onDismiss = { showVoicePicker = false },
                onPick = { id, name ->
                    fishVoice = id
                    fishVoiceName = name
                    UserPrefs.setFishVoiceId(context, id)
                    UserPrefs.setFishVoiceName(context, name)
                    showVoicePicker = false
                }
            )
        }
        if (showDobPicker) {
            DobPickerDialog(
                initialMillis = dobMillis,
                onDismiss = { showDobPicker = false },
                onSave = { millis ->
                    UserPrefs.setDob(context, millis)
                    dobMillis = millis
                    com.karen.BirthdayNotifier.schedule(context)
                }
            )
        }
        if (showClearCache) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showClearCache = false }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Clear download cache?", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Deletes ${fmtBytes(cacheBytes)} of downloaded update files from the app's private storage. You can download the update again any time.",
                        color = colors.textMuted,
                        fontSize = 12.5.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showClearCache = false }) {
                            Text("Cancel", color = colors.textMuted)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                AppUpdate.clearDownloadCache(context)
                                downloadedFile = null
                                cacheBytes = AppUpdate.cacheSizeBytes(context)
                                updateStatus = "Download cache cleared — you can download again any time."
                                showClearCache = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentRed,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DobPickerDialog(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    val colors = LocalKarenColors.current
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis.takeIf { it > 0L }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onSave(it) }
                onDismiss()
            }) {
                Text("Save", color = colors.accentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textMuted)
            }
        }
    ) {
        DatePicker(state = pickerState)
    }
}

private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

private fun formatDob(millis: Long): String {
    return try {
        val sdf = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
        sdf.format(java.util.Date(millis))
    } catch (_: Exception) {
        ""
    }
}
