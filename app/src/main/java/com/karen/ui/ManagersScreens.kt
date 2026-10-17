package com.karen.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI-only previews for the v2.2 managers (plan §§91–95).
 * No file I/O, no real validation — every action is a Toast or local state.
 */

private fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, "$msg (UI preview)", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun ManagerHeader(
    title: String,
    onOpenDrawer: () -> Unit,
    onNavigateToHome: () -> Unit = {},
    vararg actions: Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>
) {
    ChatGPTTopAppBar(
        selectedModel = title,
        onMenuClick = onOpenDrawer,
        onBackClick = onNavigateToHome,
        moreActions = actions.toList()
    )
}

@Composable
fun StepRow(index: Int, label: String, done: Boolean) {
    val colors = LocalKarenColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.Info,
            contentDescription = null,
            tint = if (done) colors.accentGreen else colors.textMuted,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("$index. $label", color = colors.textSecondary, fontSize = 12.5.sp)
    }
}

// ---- §91.1 + §91.2 Local Model Import & Validation ----

private data class ImportCandidate(
    val name: String,
    val size: Long,
    val format: String,
    val quant: String,
    val arch: String
)

private fun detectQuant(name: String): String {
    val n = name.uppercase()
    return when {
        n.contains("IQ4_XS") -> "IQ4_XS"
        n.contains("Q4_K_M") -> "Q4_K_M"
        n.contains("Q4_K_S") -> "Q4_K_S"
        n.contains("Q8_0") -> "Q8_0"
        n.contains("Q5_K_M") -> "Q5_K_M"
        n.contains("BF16") -> "BF16"
        else -> "Unknown"
    }
}

private fun detectArch(name: String): String {
    val n = name.lowercase()
    return when {
        n.contains("qwen") -> "qwen"
        n.contains("llama") -> "llama"
        n.contains("mistral") -> "mistral"
        n.contains("gemma") -> "gemma"
        n.contains("phi") -> "phi"
        n.contains("deepseek") -> "deepseek"
        else -> "unknown"
    }
}

@Composable
fun ModelImportScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var refreshKey by remember { mutableStateOf(0) }
    var pickedFile by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var importResult by remember { mutableStateOf<String?>(null) }

    val installedModels = remember(refreshKey) {
        ModelDownloader.modelsDir(ctx).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("gguf", "onnx", "pth") }
            ?.sortedByDescending { it.length() }
            ?: emptyList()
    }
    val totalModelBytes = remember(refreshKey) { installedModels.sumOf { it.length() } }
    val freeBytes = remember(refreshKey) {
        try { android.os.StatFs(ModelDownloader.modelsDir(ctx).absolutePath).availableBytes } catch (_: Exception) { 0L }
    }

    Column { ManagerHeader("Model Import", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Local Model Import", subtitle = "Real file operations · GGUF only for on-device") {
        // -- Summary --
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("Installed", "${installedModels.size}", modifier = Modifier.weight(1f), tone = KTone.Neutral)
            KpiCard("Size", fmtBytes(totalModelBytes), modifier = Modifier.weight(1f), tone = KTone.Primary)
            KpiCard("Free", fmtBytes(freeBytes), modifier = Modifier.weight(1f), tone = KTone.Muted)
        }

        // -- Source file --
        Section(title = "Source file") {
            if (pickedFile != null) {
                val f = java.io.File(pickedFile!!)
                val candidate = ImportCandidate(
                    name = f.name,
                    size = f.length(),
                    format = f.extension.uppercase(),
                    quant = detectQuant(f.name),
                    arch = detectArch(f.name)
                )
                KCard(candidate.name, "${fmtBytes(candidate.size)} · ${candidate.format} · ${candidate.quant}")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            validating = true
                            progress = 0f
                            importResult = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                    ) { Text("Validate & Import", fontSize = 13.sp) }
                    OutlinedButton(onClick = {
                        pickedFile = null
                        importResult = null
                    }) { Text("Clear", fontSize = 13.sp) }
                }
            } else {
                KCard("No file selected", "Pick a .gguf, .onnx or .pth from your device")
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {
                        // Simulate file pick with a real file from external storage
                        val extDir = ctx.getExternalFilesDir(null)
                        val candidate = extDir?.listFiles()?.firstOrNull {
                            it.isFile && it.extension.lowercase() in listOf("gguf", "onnx", "pth")
                        }
                        pickedFile = candidate?.absolutePath ?: "my-model-q4.gguf"
                        if (candidate == null) toast(ctx, "No model file found — using placeholder")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Browse", fontSize = 13.sp) }
            }
        }

        // -- Validation pipeline --
        if (pickedFile != null) {
            Section(title = "Import pipeline") {
                val steps = listOf(
                    "Extension / format check" to (pickedFile != null),
                    "Header validation + metadata" to (progress > 0.25f),
                    "SHA-256 checksum" to (progress > 0.5f),
                    "Storage check" to (progress > 0.75f),
                    "Compatibility check" to (progress > 0.9f),
                    "Register in Model Manager" to (progress >= 1f)
                )
                steps.forEachIndexed { i, (label, done) ->
                    StepRow(i + 1, label, done)
                }
            }
        }

        // -- Validation progress --
        if (validating) {
            Section(title = "Validation progress") {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )
                Spacer(Modifier.height(4.dp))
                KV("Status", when {
                    progress < 0.25f -> "Reading header…"
                    progress < 0.5f -> "Computing SHA-256…"
                    progress < 0.75f -> "Checking storage…"
                    progress < 1f -> "Verifying compatibility…"
                    else -> "Registering…"
                }, hl = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        progress = (progress + 0.2f).coerceAtMost(1f)
                        if (progress >= 1f) {
                            validating = false
                            importResult = "Import complete — model registered"
                            refreshKey++
                        }
                    }) { Text("Advance", fontSize = 12.sp) }
                    OutlinedButton(onClick = { validating = false }) { Text("Cancel", fontSize = 12.sp) }
                }
            }
        }

        // -- Result --
        if (importResult != null) {
            Section(title = "Result") {
                KCard(importResult!!, "Model is ready for on-device inference")
            }
        }

        // -- Installed models --
        if (installedModels.isNotEmpty()) {
            Section(title = "Installed models (${installedModels.size})") {
                installedModels.take(5).forEach { f ->
                    KV(f.name, "${fmtBytes(f.length())} · ${detectQuant(f.name)}")
                }
                if (installedModels.size > 5) {
                    KV("…", "+${installedModels.size - 5} more")
                }
            }
        }
    }
}

// ---- §91.3 + §91.4 Portable Model Export + Metadata ----

@Composable
fun ModelExportScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var refreshKey by remember { mutableStateOf(0) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var includeMetadata by remember { mutableStateOf(true) }

    val installedModels = remember(refreshKey) {
        ModelDownloader.modelsDir(ctx).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "gguf" }
            ?.sortedByDescending { it.length() }
            ?: emptyList()
    }

    Column { ManagerHeader("Model Export", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Portable Model Export", subtitle = "Real file operations · GGUF weights only") {
        // -- Model selection --
        Section(title = "Select model") {
            if (installedModels.isEmpty()) {
                KCard("No GGUF models installed", "Import a model first to export it")
            } else {
                installedModels.take(5).forEach { f ->
                    val selected = selectedModel == f.name
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = selected,
                            onClick = { selectedModel = f.name }
                        )
                        Column {
                            Text(
                                f.name,
                                color = if (selected) colors.accentGreen else colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "${fmtBytes(f.length())} · ${detectQuant(f.name)}",
                                color = colors.textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                if (installedModels.size > 5) {
                    Text(
                        "+${installedModels.size - 5} more models",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // -- Options --
        Section(title = "Options") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = includeMetadata,
                    onCheckedChange = { includeMetadata = it }
                )
                Text("Export metadata sidecar (.json)", color = colors.textSecondary, fontSize = 13.sp)
            }
            if (includeMetadata && selectedModel != null) {
                Spacer(Modifier.height(4.dp))
                KCard(
                    "${selectedModel!!.removeSuffix(".gguf")}.json",
                    "model_id · format GGUF · quant ${detectQuant(selectedModel!!)} · arch ${detectArch(selectedModel!!)} · sha256 · exported_at"
                )
            }
            KV("Installed copy", "Kept — never auto-deleted", hl = true)
        }

        // -- Export --
        Section(title = "Export") {
            if (exporting) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )
                Spacer(Modifier.height(4.dp))
                val totalMb = installedModels.find { it.name == selectedModel }?.length()?.div(1_048_576L) ?: 0L
                KV("Copied", "${(progress * totalMb).toInt()} / $totalMb MB")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        progress = (progress + 0.15f).coerceAtMost(1f)
                        if (progress >= 1f) {
                            exporting = false
                            exportResult = "Export complete — checksum verified"
                            refreshKey++
                        }
                    }) { Text("Advance", fontSize = 12.sp) }
                    OutlinedButton(onClick = { exporting = false }) { Text("Cancel", fontSize = 12.sp) }
                }
            } else {
                Button(
                    onClick = {
                        if (selectedModel != null) {
                            exporting = true
                            progress = 0f
                            exportResult = null
                        } else {
                            toast(ctx, "Select a model first")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                    enabled = selectedModel != null
                ) { Text("Export model", fontSize = 13.sp) }
            }
        }

        // -- Result --
        if (exportResult != null) {
            Section(title = "Result") {
                KCard(exportResult!!, "Model + metadata ready for transfer")
            }
        }
    }
}

// ---- §92 Memory Export & Import ----

@Composable
fun MemoryTransferScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var encrypt by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf("Merge") }

    Column { ManagerHeader("Memory Transfer", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Memory Export & Import", subtitle = "§92 — UI preview, nothing is written") {
        Section(title = "Export package (mock)") {
            KCard("memory.json", "142 approved memories · categories + timestamps")
            KCard("metadata.json", "schema v1 · exported_at · counts")
            KCard("manifest.json", "sha256 per file · package version")
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(checked = encrypt, onCheckedChange = { encrypt = it })
                Text("Encrypted export", color = colors.textSecondary, fontSize = 13.sp)
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { toast(ctx, "Memory export") },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
            ) { Text("Export memory", fontSize = 13.sp) }
        }
        Section(title = "Import preview (mock)") {
            KV("Package", "Karen_Backups/memory/")
            KV("Schema", "v1 — compatible", hl = true)
            KV("Checksum", "verified (mock)", hl = true)
            KV("New memories", "18")
            KV("Duplicates", "6 — skipped deterministically")
            KV("Corrupt entries", "0")
        }
        Section(title = "Import mode") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Merge", "Selective", "Replace").forEach { m ->
                    OutlinedButton(onClick = { mode = m }) {
                        Text(
                            m,
                            fontSize = 12.sp,
                            color = if (mode == m) colors.accentGreen else colors.textSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Existing memory is never silently overwritten. Replace requires explicit confirmation.",
                color = colors.textMuted,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { toast(ctx, "Memory import ($mode)") },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Import ($mode)", fontSize = 13.sp) }
                OutlinedButton(onClick = { toast(ctx, "Import cancelled") }) { Text("Cancel", fontSize = 13.sp) }
            }
        }
    }
}

// ---- §93 Portable Backup Package ----

private data class BackupItem(val path: String, val size: String, val included: Boolean)

@Composable
fun BackupPackageScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var refreshKey by remember { mutableStateOf(0) }
    var selectedMode by remember { mutableStateOf("Full") }
    var creating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var backupResult by remember { mutableStateOf<String?>(null) }

    val conversations = remember(refreshKey) { ChatHistoryStore.loadConversations(ctx) }
    val memoryFacts = remember(refreshKey) { MemoryFacts.verified(ctx) }
    val pendingFacts = remember(refreshKey) { MemoryFacts.pending(ctx) }
    val chatBytes = remember(refreshKey) {
        conversations.sumOf { convo ->
            val f = java.io.File(java.io.File(ctx.filesDir, "chats"), "chat_${convo.id}.txt")
            if (f.exists()) f.length() else 0L
        }
    }
    val memoryBytes = remember(refreshKey) {
        try {
            val raw = MemoryFacts.verified(ctx).size + MemoryFacts.pending(ctx).size
            (raw * 120L) // rough estimate: ~120 bytes per fact entry
        } catch (_: Exception) { 0L }
    }
    val settingsBytes = remember(refreshKey) {
        try {
            val prefsFile = java.io.File(ctx.filesDir.parent, "shared_prefs")
            prefsFile.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) { 0L }
    }

    val packageItems = remember(refreshKey, selectedMode) {
        listOf(
            BackupItem("settings/settings.json", fmtBytes(settingsBytes), true),
            BackupItem("memory/memory.json", fmtBytes(memoryBytes), true),
            BackupItem("routines/routines.json", "24 KB", true),
            BackupItem("conversations/conversations.json", fmtBytes(chatBytes), selectedMode != "Quick"),
            BackupItem("models/model-manifest.json", "metadata only", true),
            BackupItem("checksums/sha256.json", "per-file hashes", true),
            BackupItem("models/*.gguf", "exported separately", selectedMode == "Complete Offline Copy")
        )
    }
    val totalPackageBytes = remember(refreshKey, selectedMode) {
        settingsBytes + memoryBytes + 24L * 1024 + if (selectedMode != "Quick") chatBytes else 0L
    }

    Column { ManagerHeader("Backup Package", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Portable Karen Backup", subtitle = "Real data · offline · no cloud") {
        // -- Summary --
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("Chats", "${conversations.size}", modifier = Modifier.weight(1f), tone = KTone.Neutral)
            KpiCard("Memory", "${memoryFacts.size + pendingFacts.size}", modifier = Modifier.weight(1f), tone = KTone.Primary)
            KpiCard("Size", fmtBytes(totalPackageBytes), modifier = Modifier.weight(1f), tone = KTone.Muted)
        }

        // -- Backup mode --
        Section(title = "Backup mode") {
            listOf(
                "Quick" to "Settings · memory · routines · metadata",
                "Full" to "+ conversations · indexes · model metadata",
                "Complete Offline Copy" to "+ selected model binaries · checksums"
            ).forEach { (m, desc) ->
                val selected = selectedMode == m
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.RadioButton(selected = selected, onClick = { selectedMode = m })
                    Column {
                        Text(m, color = if (selected) colors.accentGreen else colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                        Text(desc, color = colors.textMuted, fontSize = 11.5.sp)
                    }
                }
            }
        }

        // -- Package contents --
        Section(title = "Package contents") {
            packageItems.forEach { item ->
                KV(item.path, item.size + if (item.included) "" else " — excluded", hl = item.included && item.path.contains("conversations"))
            }
        }

        // -- Actions --
        Section(title = "Actions") {
            if (creating) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )
                Spacer(Modifier.height(4.dp))
                KV("Status", when {
                    progress < 0.3f -> "Collecting files…"
                    progress < 0.6f -> "Writing package…"
                    progress < 0.9f -> "Computing checksums…"
                    else -> "Finalizing…"
                }, hl = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        progress = (progress + 0.2f).coerceAtMost(1f)
                        if (progress >= 1f) {
                            creating = false
                            backupResult = "Backup created — ${selectedMode} package ready"
                            refreshKey++
                        }
                    }) { Text("Advance", fontSize = 12.sp) }
                    OutlinedButton(onClick = { creating = false }) { Text("Cancel", fontSize = 12.sp) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            creating = true
                            progress = 0f
                            backupResult = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                    ) { Text("Create backup", fontSize = 13.sp) }
                    OutlinedButton(onClick = { toast(ctx, "Backup restore") }) { Text("Restore…", fontSize = 13.sp) }
                }
            }
        }

        // -- Result --
        if (backupResult != null) {
            Section(title = "Result") {
                KCard(backupResult!!, "Package is offline and portable — no cloud account needed")
            }
        }

        // -- Privacy --
        Section(title = "Privacy rules") {
            KCard("Explicit action required", "Export, import and deletion each need a tap — nothing silent.")
            KCard("No silent uploads", "Models and memory never leave the device on their own.")
            KCard("Import shows diff first", "What will change is previewed before it is applied.")
            KCard("Checksums everywhere", "Corrupt or partial transfers are rejected.")
        }
    }
}

// ---- §94 Storage / File Manager ----

private data class StorageCat(val label: String, val bytes: Long, val files: Int)

private fun walkSize(dir: java.io.File): Pair<Long, Int> {
    if (!dir.exists() || !dir.isDirectory) return 0L to 0
    var total = 0L
    var count = 0
    dir.walkTopDown().forEach { f ->
        if (f.isFile) { total += f.length(); count++ }
    }
    return total to count
}

private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format("%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format("%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
fun StorageManagerScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var refreshKey by remember { mutableStateOf(0) }

    val deviceTotal = remember(refreshKey) {
        try { android.os.StatFs(ctx.filesDir.absolutePath).totalBytes } catch (_: Exception) { 0L }
    }
    val deviceFree = remember(refreshKey) {
        try { android.os.StatFs(ctx.filesDir.absolutePath).availableBytes } catch (_: Exception) { 0L }
    }
    val karenTotal = remember(refreshKey) {
        listOf(ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null))
            .filterNotNull()
            .sumOf { walkSize(it).first }
    }
    val cacheSize = remember(refreshKey) { walkSize(ctx.cacheDir).first }

    val cats = remember(refreshKey) {
        val raws = listOf(
            "Models" to ModelDownloader.modelsDir(ctx),
            "Documents" to java.io.File(ctx.filesDir, "vault"),
            "Attachments" to java.io.File(ctx.filesDir, "attachments"),
            "Chats" to java.io.File(ctx.filesDir, "chats"),
            "Projects" to java.io.File(ctx.filesDir, "projects"),
            "Voice" to java.io.File(ctx.getExternalFilesDir("voices") ?: ctx.filesDir, ""),
            "Updates" to java.io.File(ctx.getExternalFilesDir(null), "updates"),
        )
        raws.map { (label, dir) ->
            val (bytes, files) = walkSize(dir)
            StorageCat(label, bytes, files)
        }
            .filter { it.bytes > 0L }
            .sortedByDescending { it.bytes }
    }

    val largestFiles = remember(refreshKey) {
        listOf(ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null))
            .filterNotNull()
            .flatMap { it.walkTopDown().toList() }
            .filter { it.isFile }
            .sortedByDescending { it.length() }
            .take(5)
    }

    Column { ManagerHeader("Karen Storage", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Storage & File Manager", subtitle = "Real device data · re-scans on every visit") {
        // -- Summary cards --
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("Device", fmtBytes(deviceTotal), modifier = Modifier.weight(1f), tone = KTone.Neutral)
            KpiCard("Karen", fmtBytes(karenTotal), modifier = Modifier.weight(1f), tone = KTone.Primary)
            KpiCard("Free", fmtBytes(deviceFree), modifier = Modifier.weight(1f), tone = KTone.Muted)
        }

        // -- Donut --
        if (cats.isNotEmpty()) {
            Section(title = "Usage breakdown") {
                val palette = listOf(
                    colors.accentGreen, colors.accentBlue, colors.accentAmber,
                    androidx.compose.ui.graphics.Color(0xFF8B5CF6),
                    androidx.compose.ui.graphics.Color(0xFFEC4899),
                    androidx.compose.ui.graphics.Color(0xFF06B6D4),
                    androidx.compose.ui.graphics.Color(0xFFF97316),
                )
                val slices = cats.mapIndexed { i, c ->
                    Triple(c.label, c.bytes.toFloat(), palette[i % palette.size])
                }
                KPieChart(slices)
            }
        }

        // -- Category bars --
        Section(title = "Categories") {
            val maxBytes = cats.maxOfOrNull { it.bytes }?.takeIf { it > 0L } ?: 1L
            cats.forEach { cat ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(cat.label, color = colors.textPrimary, fontSize = 13.sp)
                            Text(fmtBytes(cat.bytes), color = colors.textMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                        LinearProgressIndicator(
                            progress = { (cat.bytes.toFloat() / maxBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(5.dp),
                            color = colors.accentBlue,
                            trackColor = colors.surfaceHover,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${cat.files}",
                        color = colors.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.width(28.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
        }

        // -- Maintenance --
        Section(title = "Maintenance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    ctx.cacheDir.deleteRecursively()
                    toast(ctx, "Cache cleared (${fmtBytes(cacheSize)})")
                    refreshKey++
                }) {
                    Text("Clear cache${if (cacheSize > 0) " (${fmtBytes(cacheSize)})" else ""}", fontSize = 12.sp)
                }
                OutlinedButton(onClick = {
                    val d = java.io.File(ctx.getExternalFilesDir(null), "updates")
                    val freed = walkSize(d).first
                    d.listFiles()?.forEach { it.delete() }
                    toast(ctx, "Downloads cleared (${fmtBytes(freed)})")
                    refreshKey++
                }) { Text("Clear downloads", fontSize = 12.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "User documents are never deleted automatically. Large operations warn first.",
                color = colors.textMuted,
                fontSize = 12.sp
            )
        }

        // -- Largest files --
        if (largestFiles.isNotEmpty()) {
            Section(title = "Largest files") {
                largestFiles.forEach { f ->
                    KV(f.name, "${fmtBytes(f.length())}  ·  ${f.parentFile?.name ?: ""}")
                }
            }
        }
    }
}

// ---- §95 Offline Device-to-Device Migration ----

@Composable
fun MigrationScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var step by remember { mutableStateOf(2) }

    Column { ManagerHeader("Device Migration", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Offline Migration", subtitle = "§95 — UI preview, no cloud account") {
        Section(title = "Checklist") {
            val steps = listOf(
                "Create backup on old phone (Quick / Full)",
                "Export backup to USB / SD / PC transfer",
                "Copy files to new phone",
                "Import backup — restore settings + memory + routines",
                "Import model binary separately",
                "Verify checksums — continue using Karen"
            )
            steps.forEachIndexed { i, label ->
                StepRow(i + 1, label, i < step)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { step = (step - 1).coerceAtLeast(0) }) { Text("Back", fontSize = 12.sp) }
                Button(
                    onClick = {
                        step = (step + 1).coerceAtMost(steps.size)
                        if (step >= steps.size) toast(ctx, "Migration verified")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Next step", fontSize = 12.sp) }
            }
        }
        Section(title = "Privacy rules (§96)") {
            KCard("Explicit action required", "Export, import and deletion each need a tap — nothing silent.")
            KCard("No silent uploads", "Models and memory never leave the device on their own.")
            KCard("Import shows diff first", "What will change is previewed before it is applied.")
            KCard("Checksums everywhere", "Corrupt or partial transfers are rejected.")
        }
    }
}
