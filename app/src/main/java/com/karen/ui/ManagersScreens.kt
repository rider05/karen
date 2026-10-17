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
private fun StepRow(index: Int, label: String, done: Boolean) {
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

@Composable
fun ModelImportScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var pickedFile by remember { mutableStateOf<String?>(null) }
    var validating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0.65f) }

    Column { ManagerHeader("Model Import", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Local Model Import", subtitle = "§91.1 · §91.2 — UI preview, no file access yet") {
        Section(title = "Source file") {
            KCard(
                pickedFile ?: "No file selected",
                "Tap Browse to pick a .gguf (preview only — picker not wired)"
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        pickedFile = "my-model-q4.gguf · 2.72 GB"
                        toast(ctx, "File picker")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Browse", fontSize = 13.sp) }
                OutlinedButton(onClick = {
                    validating = true
                    toast(ctx, "Validation started")
                }) { Text("Validate", fontSize = 13.sp) }
            }
        }
        Section(title = "Import pipeline") {
            StepRow(1, "Extension / format check — GGUF", pickedFile != null)
            StepRow(2, "Header validation + metadata extract", false)
            StepRow(3, "SHA-256 checksum", false)
            StepRow(4, "Storage check (need 2.72 GB)", false)
            StepRow(5, "Compatibility check (arch / quant)", false)
            StepRow(6, "Register in Model Manager", false)
        }
        if (validating) {
            Section(title = "Validation progress") {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )
                Spacer(Modifier.height(4.dp))
                KV("Status", "Reading header…", hl = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        progress = (progress + 0.1f).coerceAtMost(1f)
                    }) { Text("Advance", fontSize = 12.sp) }
                    OutlinedButton(onClick = { validating = false }) { Text("Cancel", fontSize = 12.sp) }
                }
            }
        }
        Section(title = "Failure examples (preview)") {
            KCard("Unsupported model format.", "Supported: GGUF")
            KCard("Insufficient storage.", "Required: 3.1 GB · Available: 1.8 GB")
        }
        Section(title = "Detected metadata (mock)") {
            KV("Format", "GGUF")
            KV("Quantization", "Q4_K_M", hl = true)
            KV("Architecture", "qwen3.5-4B")
            KV("SHA-256", "9f2c…a41b (truncated)")
        }
    }
}

// ---- §91.3 + §91.4 Portable Model Export + Metadata ----

@Composable
fun ModelExportScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0.4f) }
    var includeMetadata by remember { mutableStateOf(true) }

    Column { ManagerHeader("Model Export", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Portable Model Export", subtitle = "§91.3 · §91.4 — UI preview") {
        Section(title = "Selected model") {
            KCard("Qwen3.5-2B-IQ4_XS.gguf", "1.1 GB · IQ4_XS · installed")
        }
        Section(title = "Destination") {
            KCard("Not chosen", "User-selected storage via system picker (not wired)")
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { toast(ctx, "Destination picker") },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
            ) { Text("Choose destination", fontSize = 13.sp) }
        }
        Section(title = "Options") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = includeMetadata,
                    onCheckedChange = { includeMetadata = it }
                )
                Text("Export metadata sidecar (.json)", color = colors.textSecondary, fontSize = 13.sp)
            }
            if (includeMetadata) {
                KCard(
                    "Qwen3.5-2B-IQ4_XS.json",
                    "model_id · format GGUF · quant IQ4_XS · version 1.0.0 · sha256 · exported_at · source"
                )
            }
            KV("Installed copy", "Kept — never auto-deleted", hl = true)
        }
        Section(title = "Export") {
            if (exporting) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )
                Spacer(Modifier.height(4.dp))
                KV("Copied", "${(progress * 2720).toInt()} / 2720 MB")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        progress = (progress + 0.15f).coerceAtMost(1f)
                        if (progress >= 1f) toast(ctx, "Checksum verified")
                    }) { Text("Advance", fontSize = 12.sp) }
                    OutlinedButton(onClick = { exporting = false }) { Text("Cancel", fontSize = 12.sp) }
                }
            } else {
                Button(
                    onClick = { exporting = true; toast(ctx, "Export started") },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Export model", fontSize = 13.sp) }
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

@Composable
fun BackupPackageScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var selectedMode by remember { mutableStateOf("Full") }

    Column { ManagerHeader("Backup Package", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Portable Karen Backup", subtitle = "§93 — UI preview") {
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
        Section(title = "Package contents (mock)") {
            KV("settings/settings.json", "12 KB")
            KV("memory/memory.json", "1.8 MB")
            KV("routines/routines.json", "24 KB")
            KV("conversations/conversations.json", if (selectedMode == "Quick") "excluded" else "6.2 MB")
            KV("models/model-manifest.json", "metadata only", hl = true)
            KV("checksums/sha256.json", "per-file hashes")
            if (selectedMode == "Complete Offline Copy") {
                KV("models/*.gguf", "2.72 GB — exported separately", hl = true)
            }
        }
        Section(title = "Actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { toast(ctx, "$selectedMode backup created") },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Create backup", fontSize = 13.sp) }
                OutlinedButton(onClick = { toast(ctx, "Backup restore") }) { Text("Restore…", fontSize = 13.sp) }
            }
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
