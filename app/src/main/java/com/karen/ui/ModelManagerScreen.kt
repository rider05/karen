package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
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
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.Intent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.karen.rememberDeviceTelemetry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val models = remember {
        mutableStateListOf<GgufModel>().apply {
            // Heal prefs: cloud names must never live in installed weights.
            val stored = UserPrefs.models(ctx)
            val cleaned = stored.filter { !isCloudProviderName(it) }
            if (cleaned.size != stored.size) UserPrefs.saveModels(ctx, cleaned)
            cleaned.forEach { n -> add(GgufModel(n, "-", "-", "-", ModelDownloader.formatOf(n))) }
        }
    }

    var activeModel by remember { mutableStateOf<GgufModel?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showApiDialog by remember { mutableStateOf(false) }
    var apiError by remember { mutableStateOf<String?>(null) }
    // Restored from persisted keys so connections survive screen revisits.
    val connectedApis = remember {
        mutableStateListOf<String>().apply {
            cloudProviders
                .filter { UserPrefs.apiKey(ctx, it.id).isNotBlank() }
                .forEach { add(it.name) }
        }
    }

    /** Re-reads installed weights from prefs (after downloads/imports). */
    fun reloadModels() {
        models.clear()
        UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
            .forEach { n -> models.add(GgufModel(n, "-", "-", "-", ModelDownloader.formatOf(n))) }
        if (activeModel != null && models.none { it.name == activeModel?.name }) activeModel = null
    }

    /** Installed voice weights (whistle.cact, sherpa-onnx files). */
    val voiceModels = remember {
        mutableStateListOf<String>().apply { addAll(UserPrefs.voiceModels(ctx)) }
    }

    fun reloadVoices() {
        voiceModels.clear()
        voiceModels.addAll(UserPrefs.voiceModels(ctx))
    }

    fun deleteVoice(name: String, fileName: String) {
        try {
            ModelDownloader.voiceFile(ctx, fileName).delete()
        } catch (_: Exception) {
        }
        UserPrefs.saveVoiceModels(ctx, UserPrefs.voiceModels(ctx).filter { it != name })
        reloadVoices()
    }

    /** Manual refresh: weights, connections, and one downloader poll. */
    fun refreshAll() {
        reloadModels()
        connectedApis.clear()
        cloudProviders
            .filter { UserPrefs.apiKey(ctx, it.id).isNotBlank() }
            .forEach { connectedApis.add(it.name) }
        when (val ev = ModelDownloader.refresh(ctx)) {
            is DownloadEvent.Completed -> {
                reloadModels()
                android.widget.Toast.makeText(ctx, "${ev.name} installed", android.widget.Toast.LENGTH_SHORT).show()
            }
            is DownloadEvent.Failed -> {
                android.widget.Toast.makeText(ctx, "Download failed: ${ev.reason}", android.widget.Toast.LENGTH_LONG).show()
            }
            else -> {
                val downloading = ModelDownloader.activeName
                android.widget.Toast.makeText(
                    ctx,
                    if (downloading != null) "Refreshing… $downloading ${ModelDownloader.status}" else "Lists refreshed",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    // API-key entry flow: step 0 = provider list, step 1 = key entry.
    var apiStep by remember { mutableStateOf(0) }
    var selectedProvider by remember { mutableStateOf<CloudProvider?>(null) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiModelInput by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var modelCustom by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    // Download catalog collapse: 3 cards by default.
    var catalogExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GgufModel?>(null) }

    /** Removes a model: deletes its weight file(s) and unregisters it. */
    fun deleteModel(m: GgufModel) {
        try {
            ModelDownloader.modelsDir(ctx).listFiles()?.forEach { f ->
                if (f.name == m.name || f.nameWithoutExtension == m.name) f.delete()
            }
        } catch (_: Exception) {
        }
        UserPrefs.saveModels(ctx, UserPrefs.models(ctx).filter { it != m.name })
        if (activeModel?.name == m.name) activeModel = null
        reloadModels()
    }

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
            when (val vev = ModelDownloader.refreshVoice(ctx)) {
                is DownloadEvent.Completed -> {
                    reloadVoices()
                    android.widget.Toast.makeText(ctx, "${vev.name} installed", android.widget.Toast.LENGTH_SHORT).show()
                }
                is DownloadEvent.Failed -> {
                    android.widget.Toast.makeText(ctx, "Voice download failed: ${vev.reason}", android.widget.Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    // Real local import: copy + validate on IO with progress on the card below.
    var importingName by remember { mutableStateOf<String?>(null) }
    var importProgress by remember { mutableStateOf(0f) }
    var importStatus by remember { mutableStateOf("") }
    var importCancelled by remember { mutableStateOf(false) }
    val managerScope = rememberCoroutineScope()

    fun fileNameOf(uri: Uri): String =
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "imported.gguf"

    fun fileSizeOf(uri: Uri): Long =
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) c.getLong(i) else -1L
        } ?: -1L

    /** Copies the picked file into app storage. GGUF is header-checked; ONNX /
        PyTorch are stored as-is (listed, but on-device runs GGUF only). Null = ok. */
    fun copyImport(uri: Uri, dest: File, total: Long): String? {
        val runnable = dest.extension.lowercase() == "gguf"
        try {
            if (runnable) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val magic = ByteArray(4)
                    var read = 0
                    while (read < 4) {
                        val n = input.read(magic, read, 4 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                        return "not a GGUF file (bad header)"
                    }
                } ?: return "cannot open file"
            }
            var copied = 0L
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (!importCancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) importProgress = (copied.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        importStatus = "Copying %.1f MB".format(copied / 1048576.0)
                    }
                }
            } ?: return "cannot open file"
            if (importCancelled) return "CANCELLED"
            if (dest.length() <= 0) return "empty file"
            return null
        } catch (e: Exception) {
            return e.message ?: "copy failed"
        }
    }

    val importModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        managerScope.launch {
            val name = fileNameOf(uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("gguf", "onnx", "pth", "pt")) {
                Toast.makeText(ctx, "Only .gguf, .onnx, .pth files can be imported", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (name in UserPrefs.models(ctx)) {
                Toast.makeText(ctx, "$name is already imported", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dest = File(ModelDownloader.modelsDir(ctx), name)
            if (dest.exists()) {
                Toast.makeText(ctx, "$name is already imported", Toast.LENGTH_SHORT).show()
                return@launch
            }
            importingName = name
            importProgress = 0f
            importStatus = "Starting…"
            importCancelled = false
            val err = withContext(Dispatchers.IO) { copyImport(uri, dest, fileSizeOf(uri)) }
            importingName = null
            when {
                err == null -> {
                    reloadModels()
                    val note = if (ModelDownloader.formatOf(name) == "GGUF") "select it in the model picker"
                    else "listed (${ModelDownloader.formatOf(name)} — on-device runs GGUF only)"
                    Toast.makeText(ctx, "Imported $name — $note", Toast.LENGTH_LONG).show()
                }
                err == "CANCELLED" -> {
                    dest.delete()
                    Toast.makeText(ctx, "Import cancelled", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    dest.delete()
                    Toast.makeText(ctx, "Import failed: $err", Toast.LENGTH_LONG).show()
                }
            }
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
                Triple("Refresh lists", Icons.Default.Refresh) { refreshAll() },
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
                        subtitle = "Pick .gguf, .onnx or .pth from your device",
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
                    val importName = importingName
                    if (activeName == null && importName == null) {
                        Text("No data found", color = colors.textMuted, fontSize = 12.sp)
                    } else if (activeName != null) {
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
                    } else {
                        Text(importName ?: "", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { importProgress },
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
                                "Import · $importStatus",
                                color = colors.textMuted,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            OutlinedButton(onClick = { importCancelled = true }) {
                                Text("Cancel", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Installed GGUF Models
            item {
                Text("Installed Weights (${models.size})", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
                        Text(
                            m.name,
                            color = colors.textPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (isActive) "Active" else "Tap to Hot-Load",
                            color = if (isActive) colors.accentGreen else colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                        )
                        IconButton(
                            onClick = { pendingDelete = m },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${m.name}", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Weight ${m.size} · RAM Floor ${m.ramRequired} · ${m.throughput} · ${m.quant}", color = colors.textMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Voice Models — zero-MB system engines (no downloads needed).
            item {
                Spacer(Modifier.height(6.dp))
                Text("Voice Models", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                val sttOk = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }
                var ttsStatus by remember { mutableStateOf("Checking…") }
                LaunchedEffect(Unit) {
                    try {
                        var tts: TextToSpeech? = null
                        tts = TextToSpeech(ctx) { status ->
                            ttsStatus = if (status == TextToSpeech.SUCCESS) {
                                val n = try { tts?.voices?.size ?: 0 } catch (_: Exception) { 0 }
                                if (n > 0) "$n voices · 0 MB" else "Engine ready · 0 MB"
                            } else "Unavailable"
                            try { tts?.shutdown() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {
                        ttsStatus = "Unavailable"
                    }
                }
                fun openSystem(intent: Intent, fallback: String) {
                    try {
                        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        Toast.makeText(ctx, fallback, Toast.LENGTH_SHORT).show()
                    }
                }
                ModelSourceRow(
                    icon = Icons.Default.Mic,
                    tint = colors.accentGreen,
                    title = "On-device Speech Recognition",
                    subtitle = if (sttOk) "Available · system STT · 0 MB — tap for voice input settings" else "Not available on this device",
                    onClick = {
                        openSystem(
                            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                            "Voice input settings not found"
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                ModelSourceRow(
                    icon = Icons.Default.RecordVoiceOver,
                    tint = colors.accentBlue,
                    title = "System TTS Voices ($ttsStatus)",
                    subtitle = "Reads replies aloud · 0 MB — tap for TTS settings & voice data",
                    onClick = {
                        openSystem(
                            Intent("com.android.settings.TTS_SETTINGS"),
                            "TTS settings not found"
                        )
                    }
                )
                Spacer(Modifier.height(10.dp))
                Text("Downloadable voice weights", color = colors.textMuted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                ModelDownloader.voiceCatalog.forEach { v ->
                    val installed = v.name in voiceModels
                    val downloading = ModelDownloader.activeVoiceName == v.name
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceHover.copy(alpha = 0.6f))
                            .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.name, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${v.sizeLabel} · ${v.engine}",
                                    color = colors.textMuted,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (installed) {
                                IconButton(
                                    onClick = { deleteVoice(v.name, v.fileName) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${v.name}", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                                }
                            } else if (!downloading) {
                                TextButton(onClick = {
                                    if (!ModelDownloader.startVoice(ctx, v)) {
                                        Toast.makeText(ctx, "Another download is running", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("Download", color = colors.accentGreen) }
                            }
                        }
                        Text(v.note, color = colors.textSecondary, fontSize = 11.5.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            VoiceEngine.status(ctx, v),
                            color = if (installed) colors.accentGreen else colors.textMuted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (downloading) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { ModelDownloader.voiceProgress },
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
                                    ModelDownloader.voiceStatus,
                                    color = colors.textMuted,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                OutlinedButton(onClick = { ModelDownloader.cancelVoice(ctx) }) {
                                    Text("Cancel", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
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

            items(
                if (catalogExpanded) modelCatalog else modelCatalog.take(3),
                key = { it.name }
            ) { entry ->
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
                                "${entry.sizeLabel} · ${entry.quant} · RAM ${entry.ram}${if (entry.reasoning) " · Reasons" else ""}",
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

            // Collapse: 3 cards by default, expandable to the full catalog.
            if (modelCatalog.size > 3) {
                item {
                    TextButton(
                        onClick = { catalogExpanded = !catalogExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            if (catalogExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = colors.accentGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (catalogExpanded) "Show less" else "Show all ${modelCatalog.size} models",
                            color = colors.accentGreen,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Delete confirmation — explicit per §96 ownership rules.
        val doomed = pendingDelete
        if (doomed != null) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete model?") },
                text = {
                    Text(
                        "“${doomed.name}” will be removed from this device, including its weight file. This cannot be undone.",
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteModel(doomed)
                        pendingDelete = null
                        android.widget.Toast.makeText(ctx, "${doomed.name} deleted", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Delete", color = colors.accentRed) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
            )
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
                            singleLine = true,
                            colors = karenFieldColors(colors)
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
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
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
                                            apiModelInput = UserPrefs.apiModel(ctx, p.id).ifBlank { p.defaultModel }
                                            modelCustom = false
                                            modelMenu = false
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
                                            if (keySet) "key stored · ${UserPrefs.apiModel(ctx, p.id).ifBlank { p.defaultModel }}" else p.note,
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
                            val keyPage = remember(provider.keyUrl) {
                                val url = if (provider.keyUrl.startsWith("http")) provider.keyUrl
                                else "https://${provider.keyUrl}"
                                androidx.compose.ui.text.AnnotatedString.Builder("Get a key: ${provider.keyUrl}").apply {
                                    val start = "Get a key: ".length
                                    addStyle(
                                        androidx.compose.ui.text.SpanStyle(
                                            color = colors.accentBlue,
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        start,
                                        length
                                    )
                                    addStringAnnotation("url", url, start, length)
                                }.toAnnotatedString()
                            }
                            ClickableText(
                                text = keyPage,
                                style = androidx.compose.ui.text.TextStyle(
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                ),
                                onClick = { offset ->
                                    keyPage.getStringAnnotations("url", offset, offset)
                                        .firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } }
                                }
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Calls: ${apiModelInput.ifBlank { provider.defaultModel }} (live)",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.accentGreen
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("Model", color = colors.textMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.surfaceHover.copy(alpha = 0.6f))
                                        .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                        .clickable { modelMenu = true }
                                        .padding(horizontal = 12.dp, vertical = 11.dp)
                                ) {
                                    Text(
                                        text = apiModelInput.ifBlank { provider.defaultModel },
                                        color = colors.textPrimary,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose model", tint = colors.textMuted, modifier = Modifier.size(18.dp))
                                }
                                DropdownMenu(
                                    expanded = modelMenu,
                                    onDismissRequest = { modelMenu = false },
                                    modifier = Modifier
                                        .background(colors.cardBackground, RoundedCornerShape(12.dp))
                                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                        .clip(RoundedCornerShape(12.dp))
                                ) {
                                    providerModelChoices(provider, apiModelInput).forEach { choice ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = choice,
                                                    color = if (choice == apiModelInput.ifBlank { provider.defaultModel }) colors.accentGreen else colors.textPrimary,
                                                    fontSize = 13.sp,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            },
                                            onClick = {
                                                if (choice == CUSTOM_MODEL) {
                                                    modelCustom = true
                                                    apiModelInput = ""
                                                } else {
                                                    modelCustom = false
                                                    apiModelInput = choice
                                                }
                                                modelMenu = false
                                            },
                                            modifier = Modifier
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    }
                                }
                            }
                            if (modelCustom) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = apiModelInput,
                                    onValueChange = { apiModelInput = it },
                                    label = { Text("Custom model id") },
                                    placeholder = { Text(provider.defaultModel) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = colors.textPrimary,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    colors = karenFieldColors(colors),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("API key") },
                                placeholder = { Text("sk-…") },
                                singleLine = true,
                                colors = karenFieldColors(colors),
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
                                    UserPrefs.setApiModel(ctx, provider.id, "")
                                    connectedApis.remove(provider.name)
                                    apiKeyInput = ""
                                    apiModelInput = ""
                                    showApiDialog = false
                                    apiStep = 0
                                    android.widget.Toast.makeText(ctx, "${provider.name} key removed", android.widget.Toast.LENGTH_SHORT).show()
                                }) { Text("Remove", color = colors.accentRed) }
                            }
                            TextButton(onClick = {
                                val key = apiKeyInput.trim()
                                if (key.isNotBlank()) {
                                    UserPrefs.saveApiKey(ctx, provider.id, key)
                                    UserPrefs.setApiModel(ctx, provider.id, apiModelInput.trim())
                                    if (provider.name !in connectedApis) {
                                        connectedApis.add(provider.name)
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
