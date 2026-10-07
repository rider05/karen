package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.rememberDeviceTelemetry
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

data class GgufModel(
    val name: String,
    val size: String,
    val ramRequired: String,
    val throughput: String,
    val quant: String
)

/** Cloud provider catalogue lives in CloudChatApi.kt (shared with the chat). */

@Composable
private fun ModelSourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceHover.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun ModelManagerScreen(
    onOpenDrawer: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val models = remember {
        mutableStateListOf<GgufModel>().apply {
            UserPrefs.models(ctx).forEach { n -> add(GgufModel(n, "-", "-", "-", "GGUF")) }
        }
    }

    var activeModel by remember { mutableStateOf<GgufModel?>(null) }

    /** Re-reads installed weights from prefs (after downloads/imports). */
    fun reloadModels() {
        models.clear()
        UserPrefs.models(ctx).forEach { n -> models.add(GgufModel(n, "-", "-", "-", "GGUF")) }
        if (activeModel != null && models.none { it.name == activeModel?.name }) activeModel = null
    }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showApiDialog by remember { mutableStateOf(false) }
    var apiError by remember { mutableStateOf<String?>(null) }
    val connectedApis = remember { mutableStateListOf<String>() }
    // API-key entry flow: step 0 = provider list, step 1 = key entry.
    var apiStep by remember { mutableStateOf(0) }
    var selectedProvider by remember { mutableStateOf<CloudProvider?>(null) }
    var apiKeyInput by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    // Poll the downloader so progress/completion lands in this screen.
    LaunchedEffect(Unit) {
        while (true) {
            when (val ev = ModelDownloader.refresh(ctx)) {
                is DownloadEvent.Completed -> {
                    reloadModels()
                    android.widget.Toast.makeText(ctx, "${ev.name} installed", android.widget.Toast.LENGTH_SHORT).show()
                }
                is DownloadEvent.Failed -> {
                    android.widget.Toast.makeText(ctx, "Download failed: ${ev.reason}", android.widget.Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    val importModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.gguf"
            models.add(GgufModel(name, "Local file", "—", "—", "GGUF"))
            UserPrefs.saveModels(ctx, models.map { it.name })
            android.widget.Toast.makeText(ctx, "Imported $name", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Local Models (GGUF)",
            onMenuClick = onOpenDrawer,
            moreActions = listOf(
                Triple("Load model", Icons.Default.Upload) { android.widget.Toast.makeText(ctx, "Load model", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Download model", Icons.Default.Download) { android.widget.Toast.makeText(ctx, "Download model", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Delete model", Icons.Default.Delete) { android.widget.Toast.makeText(ctx, "Delete model", android.widget.Toast.LENGTH_SHORT).show() }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 0.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Model Sources: import / download / API
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(colors.surface, colors.cardBackground)
                            )
                        )
                        .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.accentGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Add a Model", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text("Local import · URL pull · Cloud API", color = colors.textMuted, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    ModelSourceRow(
                        icon = Icons.Default.FolderOpen,
                        tint = colors.accentGreen,
                        title = "Import from Storage",
                        subtitle = "Pick a .gguf from your device",
                        onClick = { importModelLauncher.launch(arrayOf("*/*")) }
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelSourceRow(
                        icon = Icons.Default.Download,
                        tint = colors.accentBlue,
                        title = "Download via URL",
                        subtitle = "Pull weights from the open web",
                        onClick = { showUrlDialog = true }
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelSourceRow(
                        icon = Icons.Default.Cloud,
                        tint = colors.accentAmber,
                        title = "Connect Cloud API",
                        subtitle = "OpenAI, Claude, Gemini & more · internet required",
                        onClick = {
                            if (device.networkUp) {
                                apiError = null
                                apiStep = 0
                                selectedProvider = null
                                showApiDialog = true
                            } else {
                                apiError = "No internet connection. Cloud API models require connectivity."
                            }
                        }
                    )
                    if (apiError != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.accentRed.copy(alpha = 0.1f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = colors.accentRed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(apiError!!, color = colors.accentRed, fontSize = 12.sp)
                        }
                    }
                    if (connectedApis.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Connected APIs", color = colors.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            connectedApis.forEach { api ->
                                val providerId = cloudProviders.find { it.name == api }?.id
                                val hasKey = providerId != null && UserPrefs.apiKey(ctx, providerId).isNotBlank()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.accentBlue.copy(alpha = 0.12f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    if (hasKey) {
                                        Icon(Icons.Default.Lock, contentDescription = "Key stored", tint = colors.accentGreen, modifier = Modifier.size(11.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(api, color = colors.accentBlue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            // Active Model Card
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.cardBackground)
                        .border(1.dp, colors.accentGreen, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    if (activeModel == null) {
                        Text("No data found", color = colors.textMuted, fontSize = 13.sp)
                    } else {
                        val active = activeModel!!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Memory, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(active.name, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.accentGreen.copy(alpha = 0.2f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("ACTIVE IN RAM", color = colors.accentGreen, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "File-backed GGUF runtime (${active.quant}) · RAM ${active.ramRequired} resident · ${device.cpuCores} threads · Vulkan / ARM NEON acceleration.",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Weight: ${active.size}", color = colors.textMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                            Text("Speed: ${active.throughput}", color = colors.accentGreen, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Download-in-progress card (live DownloadManager state)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text("Active Download", color = colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    val activeName = ModelDownloader.activeName
                    if (activeName == null) {
                        Text("No data found", color = colors.textMuted, fontSize = 12.sp)
                    } else {
                        Text(activeName, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { ModelDownloader.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accentGreen,
                            trackColor = colors.surfaceHover
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                ModelDownloader.status,
                                color = colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            OutlinedButton(onClick = { ModelDownloader.cancel(ctx) }) {
                                Text("Cancel", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Installed GGUF Models
            item {
                Text("Installed GGUF Weights (${models.size})", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (models.isEmpty()) Text("No data found", color = colors.textMuted, fontSize = 12.sp)
            }

            items(models) { m ->
                val isActive = m.name == activeModel?.name
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, if (isActive) colors.accentGreen else colors.border, RoundedCornerShape(12.dp))
                        .clickable { activeModel = m }
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(m.name, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (isActive) "Active" else "Tap to Hot-Load", color = if (isActive) colors.accentGreen else colors.textMuted, fontSize = 11.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Weight ${m.size} · RAM Floor ${m.ramRequired} · ${m.throughput} · ${m.quant}", color = colors.textMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Available to download — tap to fetch real GGUF weights.
            item {
                Spacer(Modifier.height(6.dp))
                Text("Available to Download", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (models.isEmpty()) {
                    Text(
                        "Nothing installed yet — pick a model below and it appears in selection once downloaded.",
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            items(modelCatalog) { entry ->
                val alreadyInstalled = models.any { it.name == entry.name }
                val isActive = ModelDownloader.activeName == entry.name
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
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
                                OutlinedButton(onClick = { ModelDownloader.cancel(ctx) }) {
                                    Text("Cancel", fontSize = 12.sp)
                                }
                            }
                            else -> {
                                Button(
                                    onClick = {
                                        if (ModelDownloader.activeName != null) {
                                            android.widget.Toast.makeText(ctx, "One download at a time", android.widget.Toast.LENGTH_SHORT).show()
                                        } else if (ModelDownloader.startEntry(ctx, entry)) {
                                            android.widget.Toast.makeText(ctx, "Downloading ${entry.name}", android.widget.Toast.LENGTH_SHORT).show()
                                        } else {
                                            android.widget.Toast.makeText(ctx, "Could not start download", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                                ) { Text("Download", fontSize = 12.5.sp) }
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
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // External URL download dialog
        if (showUrlDialog) {
            var url by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                title = { Text("Download Model") },
                text = {
                    Column {
                        Text("Paste a direct GGUF download URL:", fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            placeholder = { Text("https://example.com/model.gguf") },
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (url.isNotBlank()) {
                            val fileName = url.substringAfterLast('/').substringBefore('?').ifBlank { "remote.gguf" }
                            val displayName = fileName.removeSuffix(".gguf").ifBlank { fileName }
                            if (ModelDownloader.activeName != null) {
                                android.widget.Toast.makeText(ctx, "One download at a time", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (ModelDownloader.start(ctx, displayName, fileName, url.trim())) {
                                android.widget.Toast.makeText(ctx, "Downloading $displayName", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(ctx, "Could not start download", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        showUrlDialog = false
                    }) { Text("Download") }
                },
                dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") } }
            )
        }

        // Cloud API connect dialog: step 0 = pick provider, step 1 = enter key.
        // Keys persist in on-device SharedPreferences via UserPrefs.
        if (showApiDialog) {
            val provider = selectedProvider
            AlertDialog(
                onDismissRequest = { showApiDialog = false; apiStep = 0 },
                title = { Text(if (apiStep == 0) "Connect Cloud API" else (provider?.name ?: "API key")) },
                text = {
                    Column {
                        if (apiStep == 0) {
                            Text("Select a provider, then paste its API key (stored only on this device):", fontSize = 13.sp)
                            Spacer(Modifier.height(10.dp))
                            cloudProviders.forEach { p ->
                                val keySet = UserPrefs.apiKey(ctx, p.id).isNotBlank()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedProvider = p
                                            apiKeyInput = UserPrefs.apiKey(ctx, p.id)
                                            keyVisible = false
                                            apiStep = 1
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (keySet) Icons.Default.CheckCircle else Icons.Default.Cloud,
                                        contentDescription = null,
                                        tint = if (keySet) colors.accentGreen else colors.accentBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, color = colors.textPrimary, fontSize = 13.5.sp)
                                        Text(
                                            if (keySet) "key stored on device" else p.note,
                                            color = if (keySet) colors.accentGreen else colors.textMuted,
                                            fontSize = 11.sp,
                                            fontFamily = if (keySet) FontFamily.Monospace else null
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                        } else if (provider != null) {
                            val stored = UserPrefs.apiKey(ctx, provider.id)
                            Text(
                                "Paste your ${provider.name} key. It stays in on-device storage — only sent to ${provider.name} when you invoke it.",
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Get a key: ${provider.keyUrl}",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.accentBlue
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Calls: ${provider.defaultModel} (live)",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.accentGreen
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("API key") },
                                placeholder = { Text("sk-…") },
                                singleLine = true,
                                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(
                                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (keyVisible) "Hide key" else "Show key"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (stored.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Current: ••••${stored.takeLast(4)}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textMuted
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (apiStep == 0) {
                        TextButton(onClick = { showApiDialog = false }) { Text("Done") }
                    } else {
                        TextButton(onClick = { apiStep = 0 }) { Text("Back") }
                    }
                },
                dismissButton = {
                    if (apiStep == 1 && provider != null) {
                        Row {
                            val stored = UserPrefs.apiKey(ctx, provider.id)
                            if (stored.isNotBlank() || provider.name in connectedApis) {
                                TextButton(onClick = {
                                    UserPrefs.clearApiKey(ctx, provider.id)
                                    connectedApis.remove(provider.name)
                                    apiKeyInput = ""
                                    showApiDialog = false
                                    apiStep = 0
                                    android.widget.Toast.makeText(ctx, "${provider.name} key removed", android.widget.Toast.LENGTH_SHORT).show()
                                }) { Text("Remove", color = colors.accentRed) }
                            }
                            TextButton(onClick = {
                                val key = apiKeyInput.trim()
                                if (key.isNotBlank()) {
                                    UserPrefs.saveApiKey(ctx, provider.id, key)
                                    if (provider.name !in connectedApis) {
                                        connectedApis.add(provider.name)
                                        UserPrefs.saveModels(ctx, models.map { it.name } + provider.name)
                                    }
                                    showApiDialog = false
                                    apiStep = 0
                                    android.widget.Toast.makeText(ctx, "${provider.name} connected", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(ctx, "Paste a key first", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Save & Connect") }
                        }
                    }
                }
            )
        }
    }
}
