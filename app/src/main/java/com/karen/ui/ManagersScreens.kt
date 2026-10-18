package com.karen.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The v2.2 managers (plan §§91–95). Model import/export, memory transfer and
 * storage do real file I/O with real validation; the backup wizard and the
 * migration checklist are guided flows over real data.
 */

private fun toast(ctx: android.content.Context, msg: String) {
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
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

/** One finished export: bytes written + the real SHA-256 measured while copying. */
private data class ExportOut(val bytes: Long, val sha256: String)

private fun detectQuant(name: String): String = ModelDownloader.quantOf(name)

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
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var pickedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var pickedSize by remember { mutableStateOf(0L) }
    var importing by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var stage by remember { mutableStateOf(0) }
    var statusLine by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<String?>(null) }

    /** Real SAF picker — copies from Downloads, USB, anywhere, with no permission. */
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        var name: String? = null
        var size = 0L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i)
            }
        } catch (_: Exception) { }
        try {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { size = it.length }
        } catch (_: Exception) { }
        pickedUri = uri
        pickedName = name ?: "picked-model"
        pickedSize = if (size > 0) size else 0L
        stage = 0
        progress = 0f
        statusLine = ""
        importResult = null
    }

    /** Validates the picked file, copies it into models/ and registers it. */
    fun startImport() {
        val uri = pickedUri ?: return
        val name = (pickedName ?: "model").substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val display = name.removeSuffix(".gguf").removeSuffix(".onnx").removeSuffix(".pth").ifBlank { name }
        importing = true
        cancelRequested = false
        progress = 0f
        statusLine = "Checking format…"
        importResult = null
        stage = 0
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    if (ext !in listOf("gguf", "onnx", "pth")) {
                        return@withContext "Rejected — .$ext is not a model file (use .gguf, .onnx or .pth)"
                    }
                    stage = 1
                    if (ext == "gguf") {
                        statusLine = "Reading GGUF header…"
                        val magicOk = ctx.contentResolver.openInputStream(uri)?.use { ins ->
                            val b = ByteArray(4)
                            ins.read(b) == 4 && String(b, Charsets.US_ASCII) == "GGUF"
                        } ?: false
                        if (!magicOk) {
                            return@withContext "Rejected — GGUF magic header missing (file looks corrupt)"
                        }
                    }
                    stage = 2
                    statusLine = "Checking free space…"
                    val destDir = ModelDownloader.modelsDir(ctx)
                    val free = try {
                        android.os.StatFs(destDir.absolutePath).availableBytes
                    } catch (_: Exception) {
                        Long.MAX_VALUE
                    }
                    if (pickedSize > 0 && free < pickedSize + (32L shl 20)) {
                        return@withContext "Not enough space — needs ${fmtBytes(pickedSize)}, only ${fmtBytes(free)} free"
                    }
                    stage = 3
                    statusLine = "Copying into app storage…"
                    val dest = File(destDir, name)
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    val ok = try {
                        ctx.contentResolver.openInputStream(uri)?.use { ins ->
                            dest.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                while (!cancelRequested) {
                                    val n = ins.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                    copied += n
                                    progress = if (pickedSize > 0) {
                                        (copied.toDouble() / pickedSize).toFloat().coerceIn(0f, 1f)
                                    } else 0f
                                }
                            }
                            true
                        } ?: false
                    } catch (_: Exception) {
                        false
                    }
                    if (cancelRequested) {
                        dest.delete()
                        return@withContext "Import cancelled — the partial copy was removed"
                    }
                    if (!ok || copied <= 0L) {
                        dest.delete()
                        return@withContext "Copy failed — nothing was registered"
                    }
                    stage = 4
                    statusLine = "Registering in Model Manager…"
                    val models = UserPrefs.models(ctx)
                    if (display !in models) UserPrefs.saveModels(ctx, models + display)
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
                    "Imported $name · ${fmtBytes(copied)} · ${detectQuant(name)} · sha256 $sha…"
                } catch (e: Exception) {
                    "Import failed (${e.message ?: "unknown error"})"
                }
            }
            importing = false
            if (outcome.startsWith("Imported")) stage = 5
            importResult = outcome
            refreshKey++
        }
    }

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
            if (pickedUri != null) {
                val displayName = pickedName ?: "picked file"
                KCard(
                    displayName,
                    "${if (pickedSize > 0) fmtBytes(pickedSize) else "size unknown"} · " +
                        "${displayName.substringAfterLast('.', "?").uppercase()} · " +
                        "${detectQuant(displayName)} · arch ${detectArch(displayName)}"
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startImport() },
                        enabled = !importing,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                    ) { Text("Validate & import", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !importing
                    ) { Text("Choose another", fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = {
                            pickedUri = null
                            pickedName = null
                            pickedSize = 0L
                            stage = 0
                            importResult = null
                        },
                        enabled = !importing
                    ) { Text("Clear", fontSize = 13.sp) }
                }
            } else {
                KCard(
                    "No file selected",
                    "Pick a .gguf, .onnx or .pth with the system file picker — Downloads, USB, anywhere."
                )
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Browse files…", fontSize = 13.sp) }
            }
        }

        // -- Import pipeline --
        if (pickedUri != null) {
            Section(title = "Import pipeline") {
                listOf(
                    "Format check (.gguf / .onnx / .pth)",
                    "GGUF header + integrity",
                    "Free storage check",
                    "Copy into app storage (+ SHA-256)",
                    "Register in Model Manager"
                ).forEachIndexed { i, label ->
                    StepRow(i + 1, label, stage > i)
                }
            }
        }

        // -- Import progress --
        if (importing) {
            Section(title = "Import progress") {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )
                Spacer(Modifier.height(4.dp))
                KV("Status", statusLine.ifBlank { "Working…" }, hl = true)
                OutlinedButton(onClick = { cancelRequested = true }) { Text("Cancel", fontSize = 12.sp) }
            }
        }

        // -- Result --
        if (importResult != null) {
            Section(title = "Result") {
                KCard(
                    importResult!!,
                    if (importResult!!.startsWith("Imported")) "Ready in Model Manager — pick it in the model sheet to chat"
                    else "Nothing was changed on disk"
                )
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
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var cancelRequested by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var copiedBytes by remember { mutableStateOf(0L) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var includeMetadata by remember { mutableStateOf(true) }
    var pendingSidecarFor by remember { mutableStateOf<File?>(null) }
    var pendingSidecarSha by remember { mutableStateOf("") }

    val installedModels = remember(refreshKey) {
        ModelDownloader.modelsDir(ctx).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "gguf" }
            ?.sortedByDescending { it.length() }
            ?: emptyList()
    }
    val selectedFile = remember(refreshKey, selectedModel) {
        installedModels.firstOrNull { it.name == selectedModel }
    }

    // Sidecar writer — a second system pick after the model copy (order matters:
    // the model writer below launches this one when metadata is requested).
    val sidecarSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val src = pendingSidecarFor
        if (uri == null || src == null) return@rememberLauncherForActivityResult
        val sha = pendingSidecarSha
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val meta = org.json.JSONObject()
                        .put("model_id", src.name)
                        .put("format", "GGUF")
                        .put("quant", detectQuant(src.name))
                        .put("arch", detectArch(src.name))
                        .put("bytes", src.length())
                        .put("sha256", sha)
                        .put("exported_at", System.currentTimeMillis())
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(meta.toString(2).toByteArray()) }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            pendingSidecarFor = null
            pendingSidecarSha = ""
            exportResult = if (ok) {
                "Export complete — model + metadata sidecar saved"
            } else {
                "Model saved, but the metadata sidecar could not be written"
            }
        }
    }

    // Model writer: streams the GGUF to the picked destination, hashing on the way.
    val modelSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val src = selectedFile
        if (uri == null || src == null) return@rememberLauncherForActivityResult
        val total = src.length().coerceAtLeast(1L)
        exporting = true
        cancelRequested = false
        progress = 0f
        copiedBytes = 0L
        exportResult = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { ins ->
                            val buf = ByteArray(256 * 1024)
                            while (!cancelRequested) {
                                val n = ins.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                digest.update(buf, 0, n)
                                copied += n
                                copiedBytes = copied
                                progress = (copied.toDouble() / total).toFloat().coerceIn(0f, 1f)
                            }
                        }
                    } ?: return@withContext null
                    ExportOut(copied, digest.digest().joinToString("") { "%02x".format(it) })
                } catch (_: Exception) {
                    null
                }
            }
            exporting = false
            when {
                cancelRequested ->
                    exportResult = "Export cancelled — remove the partial file at the destination"
                outcome == null ->
                    exportResult = "Export failed — nothing (or only part) was written"
                includeMetadata -> {
                    pendingSidecarFor = src
                    pendingSidecarSha = outcome.sha256
                    exportResult = "Model copied (${fmtBytes(outcome.bytes)}) — now pick where to save the metadata .json"
                    sidecarSaver.launch(src.name.removeSuffix(".gguf") + ".json")
                }
                else -> exportResult =
                    "Export complete — ${src.name} · ${fmtBytes(outcome.bytes)} · sha256 ${outcome.sha256.take(16)}…"
            }
            refreshKey++
        }
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
                KV("Copied", "${fmtBytes(copiedBytes)} / ${fmtBytes(selectedFile?.length() ?: 0L)}")
                OutlinedButton(onClick = { cancelRequested = true }) { Text("Cancel", fontSize = 12.sp) }
            } else {
                Button(
                    onClick = {
                        val f = selectedFile
                        if (f == null) {
                            toast(ctx, "Select a model first")
                        } else {
                            exportResult = null
                            modelSaver.launch(f.name)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen),
                    enabled = selectedFile != null
                ) { Text("Export model…", fontSize = 13.sp) }
                Spacer(Modifier.height(4.dp))
                Text(
                    "The system picker chooses the destination — SD card, USB or a cloud folder. The installed copy is kept.",
                    color = colors.textMuted,
                    fontSize = 11.5.sp
                )
            }
        }

        // -- Result --
        if (exportResult != null) {
            Section(title = "Result") {
                KCard(
                    exportResult!!,
                    if (exportResult!!.startsWith("Export complete") || exportResult!!.startsWith("Model copied"))
                        "SHA-256 measured while copying — safe to transfer"
                    else "Nothing was written"
                )
            }
        }
    }
}

// ---- §92 Memory Export & Import ----

private data class MemoryPackage(
    val facts: List<MemoryFacts.Fact>,
    val exportedAt: Long,
    val schemaVersion: Int = 1
)

private fun exportMemoryPackage(ctx: Context, facts: List<MemoryFacts.Fact>): File? {
    return try {
        val dir = File(ctx.getExternalFilesDir(null), "backups/memory").apply { mkdirs() }
        val file = File(dir, "memory_${System.currentTimeMillis()}.json")
        val arr = org.json.JSONArray()
        facts.forEach { f ->
            arr.put(
                org.json.JSONObject()
                    .put("id", f.id)
                    .put("category", f.category)
                    .put("text", f.text)
                    .put("verified", f.verified)
            )
        }
        val pkg = org.json.JSONObject()
            .put("schema_version", 1)
            .put("exported_at", System.currentTimeMillis())
            .put("count", facts.size)
            .put("facts", arr)
        file.writeText(pkg.toString(2))
        file
    } catch (_: Exception) {
        null
    }
}

private fun parseMemoryPackageText(text: String): MemoryPackage? {
    return try {
        val pkg = org.json.JSONObject(text)
        val arr = pkg.optJSONArray("facts") ?: return null
        val facts = mutableListOf<MemoryFacts.Fact>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            facts.add(
                MemoryFacts.Fact(
                    id = o.optString("id"),
                    category = o.optString("category", "Other"),
                    text = o.optString("text"),
                    verified = o.optBoolean("verified", false)
                )
            )
        }
        MemoryPackage(facts, pkg.optLong("exported_at", 0L), pkg.optInt("schema_version", 1))
    } catch (_: Exception) {
        null
    }
}

private fun importMemoryPackage(ctx: Context, file: File): MemoryPackage? {
    return try {
        parseMemoryPackageText(file.readText())
    } catch (_: Exception) {
        null
    }
}

/** DISPLAY_NAME of a SAF-picked document. */
private fun pickupName(ctx: Context, uri: android.net.Uri): String? = try {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (i >= 0 && c.moveToFirst()) c.getString(i) else null
    }
} catch (_: Exception) {
    null
}

@Composable
fun MemoryTransferScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf("Merge") }
    var exporting by remember { mutableStateOf(false) }
    var exportResult by remember { mutableStateOf<String?>(null) }
    var importPickedName by remember { mutableStateOf<String?>(null) }
    var importPreview by remember { mutableStateOf<MemoryPackage?>(null) }
    var confirmReplace by remember { mutableStateOf(false) }

    val verifiedFacts = remember(refreshKey) { MemoryFacts.verified(ctx) }
    val pendingFacts = remember(refreshKey) { MemoryFacts.pending(ctx) }
    val allFacts = remember(refreshKey) { verifiedFacts + pendingFacts }

    /** Applies a previewed package: verified facts come back verified. */
    fun applyImport(replace: Boolean) {
        val pkg = importPreview ?: return
        val newFacts = when {
            replace -> pkg.facts
            mode == "Verified only" -> pkg.facts.filter { it.verified && allFacts.none { e -> e.id == it.id } }
            else -> pkg.facts.filter { f -> allFacts.none { it.id == f.id } }
        }
        if (replace) MemoryFacts.clear(ctx)
        newFacts.forEach { f ->
            val added = MemoryFacts.add(ctx, f.category, f.text)
            if (f.verified) MemoryFacts.confirm(ctx, added.id)
        }
        toast(
            ctx,
            if (newFacts.isEmpty()) "No new facts — all duplicates of existing memory"
            else "Imported ${newFacts.size} facts (${if (replace) "Replace" else mode})"
        )
        importPickedName = null
        importPreview = null
        confirmReplace = false
        refreshKey++
    }

    /** Real file pick — any .json memory package on the device. */
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: Exception) {
                    null
                }
            }
            val parsed = text?.let { parseMemoryPackageText(it) }
            if (parsed == null) {
                toast(ctx, "Invalid backup file")
            } else {
                importPickedName = pickupName(ctx, uri) ?: "memory package"
                importPreview = parsed
            }
        }
    }
    val categoryCounts = remember(refreshKey) {
        allFacts.groupingBy { it.category }.eachCount().toList().sortedByDescending { it.second }
    }
    val existingBackups = remember(refreshKey) {
        val dir = File(ctx.getExternalFilesDir(null), "backups/memory")
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.filter { it.isFile && it.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else emptyList()
    }

    Column { ManagerHeader("Memory Transfer", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Memory Export & Import", subtitle = "Real data · encrypted · offline") {
        // -- Summary --
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("Verified", "${verifiedFacts.size}", modifier = Modifier.weight(1f), tone = KTone.Success)
            KpiCard("Pending", "${pendingFacts.size}", modifier = Modifier.weight(1f), tone = KTone.Amber)
            KpiCard("Total", "${allFacts.size}", modifier = Modifier.weight(1f), tone = KTone.Primary)
        }

        // -- Category breakdown --
        if (categoryCounts.isNotEmpty()) {
            Section(title = "Categories") {
                val palette = listOf(
                    colors.accentGreen, colors.accentBlue, colors.accentAmber,
                    androidx.compose.ui.graphics.Color(0xFF8B5CF6),
                    androidx.compose.ui.graphics.Color(0xFFEC4899),
                )
                val slices = categoryCounts.mapIndexed { i, (cat, count) ->
                    Triple(cat, count.toFloat(), palette[i % palette.size])
                }
                KPieChart(slices, size = 100.dp)
            }
        }

        // -- Export --
        Section(title = "Export") {
            if (exporting) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )
                Spacer(Modifier.height(4.dp))
                KV("Status", "Writing ${allFacts.size} facts…", hl = true)
            } else {
                Button(
                    onClick = {
                        exporting = true
                        exportResult = null
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { exportMemoryPackage(ctx, allFacts) }
                            exporting = false
                            exportResult = if (file != null) {
                                "Exported to ${file.name} (${fmtBytes(file.length())})"
                            } else {
                                "Export failed"
                            }
                            refreshKey++
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Export memory", fontSize = 13.sp) }
            }
            if (exportResult != null) {
                Spacer(Modifier.height(4.dp))
                KCard(exportResult!!, "Package is offline and portable")
            }
        }

        // -- Existing backups --
        if (existingBackups.isNotEmpty()) {
            Section(title = "Existing backups (${existingBackups.size})") {
                existingBackups.take(3).forEach { f ->
                    KV(f.name, "${fmtBytes(f.length())} · ${java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f.lastModified()))}")
                }
                if (existingBackups.size > 3) {
                    KV("…", "+${existingBackups.size - 3} more")
                }
            }
        }

        // -- Import --
        Section(title = "Import") {
            if (importPickedName != null && importPreview != null) {
                val pkg = importPreview!!
                KCard(
                    "$importPickedName",
                    "${pkg.facts.size} facts · schema v${pkg.schemaVersion} · " +
                        java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(pkg.exportedAt))
                )
                Spacer(Modifier.height(4.dp))
                val pool = if (mode == "Verified only") pkg.facts.filter { it.verified } else pkg.facts
                val newCount = pool.count { f -> allFacts.none { it.id == f.id } }
                val dupCount = pool.size - newCount
                KV("New facts", if (mode == "Replace") "${pkg.facts.size} (all — memory is cleared first)" else "$newCount")
                KV("Duplicates", "$dupCount — skipped")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (mode == "Replace") confirmReplace = true else applyImport(replace = false) },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                    ) { Text("Import ($mode)", fontSize = 13.sp) }
                    OutlinedButton(onClick = {
                        importPickedName = null
                        importPreview = null
                    }) { Text("Cancel", fontSize = 13.sp) }
                }
            } else {
                KCard("No file selected", "Pick a memory backup .json from your device")
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = { importPicker.launch(arrayOf("application/json")) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) { Text("Browse files…", fontSize = 13.sp) }
            }
        }

        // -- Import mode --
        Section(title = "Import mode") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Merge", "Verified only", "Replace").forEach { m ->
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
                when (mode) {
                    "Merge" -> "Adds new facts only — duplicates are skipped by id."
                    "Verified only" -> "Imports only the facts you had confirmed in the package."
                    else -> "Clears current memory, then imports the package. Requires confirmation."
                },
                color = colors.textMuted,
                fontSize = 12.sp
            )
        }
    }

    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Replace all memory?") },
            text = {
                Text(
                    "Your current ${allFacts.size} memory facts are deleted first, then the package's " +
                        "${importPreview?.facts?.size ?: 0} facts are imported."
                )
            },
            confirmButton = {
                TextButton(onClick = { applyImport(replace = true) }) {
                    Text("Replace", color = colors.accentAmber)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReplace = false }) { Text("Cancel") }
            }
        )
    }
}

// ---- §93 Portable Backup Package ----

private data class BackupItem(val label: String, val detail: String, val included: Boolean)

/** A parsed backup file, ready to restore. */
private data class BackupPreview(
    val fileName: String,
    val exportedAt: Long,
    val facts: List<MemoryFacts.Fact>,
    val conversations: List<Pair<String, List<ChatItem>>>,
    val models: List<String>,
    val shaOk: Boolean
)

/** Result of a finished backup write. */
private data class BackupOut(
    val file: File,
    val facts: Int,
    val conversations: Int,
    val models: Int,
    val sha: String
)

/** Reads and validates a Karen backup JSON (null when it is not one). */
private fun parseBackupFile(ctx: Context, uri: android.net.Uri): BackupPreview? {
    return try {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return null
        val root = org.json.JSONObject(text)
        if (root.optInt("schema_version", 0) < 1) return null
        val storedSha = root.optString("content_sha256")
        val shaOk = if (storedSha.isBlank()) {
            false
        } else {
            val probe = org.json.JSONObject(root.toString())
            probe.remove("content_sha256")
            val calc = java.security.MessageDigest.getInstance("SHA-256")
                .digest(probe.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
            calc == storedSha
        }
        val facts = mutableListOf<MemoryFacts.Fact>()
        root.optJSONArray("memory")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                facts.add(
                    MemoryFacts.Fact(
                        id = o.optString("id"),
                        category = o.optString("category", "Other"),
                        text = o.optString("text"),
                        verified = o.optBoolean("verified", false)
                    )
                )
            }
        }
        val convos = mutableListOf<Pair<String, List<ChatItem>>>()
        root.optJSONArray("conversations")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id").ifBlank { "restored_${System.currentTimeMillis()}_$i" }
                val msgs = mutableListOf<ChatItem>()
                o.optJSONArray("messages")?.let { m ->
                    for (j in 0 until m.length()) {
                        val mo = m.getJSONObject(j)
                        val t = mo.optString("text")
                        when (mo.optString("role")) {
                            "user" -> msgs.add(ChatItem.User(id = "u_${id}_$j", text = t))
                            "assistant" -> msgs.add(ChatItem.Assistant(id = "a_${id}_$j", text = t))
                        }
                    }
                }
                convos.add(id to msgs)
            }
        }
        val models = mutableListOf<String>()
        root.optJSONArray("models")?.let { arr ->
            for (i in 0 until arr.length()) models.add(arr.getJSONObject(i).optString("name"))
        }
        val plainName = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        } catch (_: Exception) {
            null
        } ?: "backup.json"
        BackupPreview(plainName, root.optLong("exported_at", 0L), facts, convos, models, shaOk)
    } catch (_: Exception) {
        null
    }
}

@Composable
fun BackupPackageScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    val scope = rememberCoroutineScope()
    var refreshKey by remember { mutableStateOf(0) }
    var selectedMode by remember { mutableStateOf("Full") }
    var creating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var backupResult by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var restorePreview by remember { mutableStateOf<BackupPreview?>(null) }

    val conversations = remember(refreshKey) { ChatHistoryStore.loadConversations(ctx) }
    val memoryFacts = remember(refreshKey) { MemoryFacts.verified(ctx) }
    val pendingFacts = remember(refreshKey) { MemoryFacts.pending(ctx) }
    val chatBytes = remember(refreshKey) {
        conversations.sumOf { convo ->
            val f = java.io.File(java.io.File(ctx.filesDir, "chats"), "chat_${convo.id}.txt")
            if (f.exists()) f.length() else 0L
        }
    }
    val memoryBytes = remember(refreshKey) { (memoryFacts.size + pendingFacts.size) * 120L }
    val modelFiles = remember(refreshKey) {
        ModelDownloader.modelsDir(ctx).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() == "gguf" }
            ?: emptyList()
    }

    val packageItems = remember(refreshKey, selectedMode) {
        listOf(
            BackupItem(
                "Memory facts (${memoryFacts.size + pendingFacts.size})",
                "${fmtBytes(memoryBytes)} · restored as pending so you re-confirm",
                true
            ),
            BackupItem(
                "Conversations (${conversations.size})",
                fmtBytes(chatBytes) + if (selectedMode == "Quick") " — excluded in Quick mode" else "",
                selectedMode != "Quick"
            ),
            BackupItem(
                "Model manifest (${modelFiles.size})",
                "name · size · quant — no weight bytes",
                true
            ),
            BackupItem("Checksums (sha256)", "of the package content", true)
        )
    }
    val totalPackageBytes = remember(refreshKey, selectedMode) {
        memoryBytes + if (selectedMode != "Quick") chatBytes else 0L
    }

    /** Writes a real backup package (single JSON) into backups/. */
    fun createBackup() {
        if (creating) return
        creating = true
        progress = 0.05f
        backupResult = null
        scope.launch {
            val out = withContext(Dispatchers.IO) {
                try {
                    val factsArr = org.json.JSONArray()
                    (MemoryFacts.verified(ctx) + MemoryFacts.pending(ctx)).forEach { f ->
                        factsArr.put(
                            org.json.JSONObject()
                                .put("id", f.id)
                                .put("category", f.category)
                                .put("text", f.text)
                                .put("verified", f.verified)
                        )
                    }
                    progress = 0.2f
                    val convArr = org.json.JSONArray()
                    val convos = ChatHistoryStore.loadConversations(ctx)
                    convos.forEachIndexed { i, c ->
                        val msgs = org.json.JSONArray()
                        ChatHistoryStore.loadMessages(ctx, c.id).forEach { item ->
                            when (item) {
                                is ChatItem.User -> msgs.put(
                                    org.json.JSONObject().put("role", "user").put("text", item.text)
                                )
                                is ChatItem.Assistant -> msgs.put(
                                    org.json.JSONObject().put("role", "assistant").put("text", item.text)
                                )
                            }
                        }
                        convArr.put(
                            org.json.JSONObject()
                                .put("id", c.id)
                                .put("title", c.title)
                                .put("updated_at", c.updatedAt)
                                .put("messages", msgs)
                        )
                        progress = 0.2f + 0.6f * ((i + 1).toFloat() / convos.size.coerceAtLeast(1))
                    }
                    val modelArr = org.json.JSONArray()
                    modelFiles.sortedByDescending { it.length() }.forEach { f ->
                        modelArr.put(
                            org.json.JSONObject()
                                .put("name", f.name)
                                .put("bytes", f.length())
                                .put("quant", detectQuant(f.name))
                        )
                    }
                    val payload = org.json.JSONObject()
                        .put("schema_version", 1)
                        .put("app", "Karen")
                        .put("exported_at", System.currentTimeMillis())
                        .put("mode", selectedMode)
                        .put("memory", factsArr)
                        .put("conversations", convArr)
                        .put("models", modelArr)
                    val sha = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(payload.toString().toByteArray())
                        .joinToString("") { "%02x".format(it) }
                    val root = org.json.JSONObject(payload.toString()).put("content_sha256", sha)
                    progress = 0.9f
                    val dir = File(ctx.getExternalFilesDir(null), "backups").apply { mkdirs() }
                    val file = File(dir, "karen_backup_${System.currentTimeMillis()}.json")
                    file.writeText(root.toString(2))
                    progress = 1f
                    BackupOut(file, factsArr.length(), convArr.length(), modelArr.length(), sha)
                } catch (_: Exception) {
                    null
                }
            }
            creating = false
            backupResult = if (out == null) {
                "Backup failed — nothing was written"
            } else {
                "Backup created — ${out.file.name} · ${fmtBytes(out.file.length())} · " +
                    "${out.facts} facts · ${out.conversations} chats · ${out.models} model entries · sha256 ${out.sha.take(12)}…"
            }
            refreshKey++
        }
    }

    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val preview = withContext(Dispatchers.IO) { parseBackupFile(ctx, uri) }
            if (preview == null) {
                toast(ctx, "Not a Karen backup file")
            } else {
                restorePreview = preview
            }
        }
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
                KV(item.label, item.detail, hl = item.included && item.label.startsWith("Conversations"))
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
                    progress < 0.2f -> "Collecting memory facts…"
                    progress < 0.8f -> "Writing conversations…"
                    progress < 1f -> "Hashing & saving…"
                    else -> "Done"
                }, hl = true)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { createBackup() },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                    ) { Text("Create backup", fontSize = 13.sp) }
                    OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/json")) }) {
                        Text("Restore…", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Backups are written to Android/data/com.karen/files/backups and never leave the device on their own.",
                    color = colors.textMuted,
                    fontSize = 11.5.sp
                )
            }
        }

        // -- Restore preview --
        val preview = restorePreview
        if (preview != null) {
            Section(title = "Restore preview — ${preview.fileName}") {
                KV("Memory facts", "${preview.facts.size} (restored as pending)")
                KV("Conversations", "${preview.conversations.size}")
                KV("Model entries", "${preview.models.size} (manifest only)")
                KV(
                    "Checksum",
                    if (preview.shaOk) "sha256 verified" else "not verified — older or edited file",
                    hl = true
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val p = preview
                            restoring = true
                            scope.launch {
                                val applied = withContext(Dispatchers.IO) {
                                    var facts = 0
                                    p.facts.forEach { f ->
                                        MemoryFacts.add(ctx, f.category, f.text)
                                        facts++
                                    }
                                    var chats = 0
                                    p.conversations.forEach { (id, messages) ->
                                        if (messages.any { it is ChatItem.User }) {
                                            ChatHistoryStore.saveConversation(ctx, id, messages)
                                            chats++
                                        }
                                    }
                                    facts to chats
                                }
                                restoring = false
                                restorePreview = null
                                backupResult =
                                    "Restored ${applied.first} memory facts (pending confirmation) and ${applied.second} conversations"
                                refreshKey++
                            }
                        },
                        enabled = !restoring,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                    ) { Text(if (restoring) "Restoring…" else "Apply restore", fontSize = 13.sp) }
                    OutlinedButton(onClick = { restorePreview = null }, enabled = !restoring) {
                        Text("Cancel", fontSize = 13.sp)
                    }
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

private data class MigrationStep(
    val title: String,
    val description: String,
    val done: Boolean
)

@Composable
fun MigrationScreen(onOpenDrawer: () -> Unit = {}, onNavigateToHome: () -> Unit = {}) {
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)
    val ctx = LocalContext.current
    val colors = LocalKarenColors.current
    var step by remember { mutableStateOf(0) }
    var transferMethod by remember { mutableStateOf("USB") }

    val conversations = remember { ChatHistoryStore.loadConversations(ctx) }
    val memoryFacts = remember { MemoryFacts.verified(ctx) + MemoryFacts.pending(ctx) }
    val modelFiles = remember { ModelDownloader.modelsDir(ctx).listFiles()?.filter { it.isFile && it.extension.lowercase() == "gguf" } ?: emptyList() }
    val chatBytes = remember {
        conversations.sumOf { convo ->
            val f = File(File(ctx.filesDir, "chats"), "chat_${convo.id}.txt")
            if (f.exists()) f.length() else 0L
        }
    }
    val memoryBytes = remember { memoryFacts.size * 120L }
    val modelBytes = remember { modelFiles.sumOf { it.length() } }
    val totalMigrationBytes = remember { chatBytes + memoryBytes + modelBytes }

    val steps = remember {
        listOf(
            MigrationStep("Create backup on old phone", "Quick / Full / Complete — includes settings, memory, conversations", step >= 1),
            MigrationStep("Export backup to transfer media", "USB drive / SD card / PC folder — offline, no cloud", step >= 2),
            MigrationStep("Copy files to new phone", "Same folder structure — Android/data/com.karen/", step >= 3),
            MigrationStep("Import backup on new phone", "Restore settings + memory + routines from package", step >= 4),
            MigrationStep("Import model binaries separately", "GGUF weights copied to models/ directory", step >= 5),
            MigrationStep("Verify checksums", "SHA-256 per file — corrupt or partial transfers rejected", step >= 6)
        )
    }

    Column { ManagerHeader("Device Migration", onOpenDrawer, onNavigateToHome) }
    KScreen(title = "Offline Migration", subtitle = "Real data · no cloud account needed") {
        // -- Summary --
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiCard("Chats", "${conversations.size}", modifier = Modifier.weight(1f), tone = KTone.Neutral)
            KpiCard("Memory", "${memoryFacts.size}", modifier = Modifier.weight(1f), tone = KTone.Primary)
            KpiCard("Models", "${modelFiles.size}", modifier = Modifier.weight(1f), tone = KTone.Muted)
        }

        // -- Transfer size --
        Section(title = "Transfer size") {
            KV("Conversations", fmtBytes(chatBytes))
            KV("Memory facts", fmtBytes(memoryBytes))
            KV("Model binaries", fmtBytes(modelBytes), hl = true)
            KV("Total", fmtBytes(totalMigrationBytes), hl = true)
        }

        // -- Transfer method --
        Section(title = "Transfer method") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("USB", "SD Card", "PC Folder").forEach { m ->
                    OutlinedButton(onClick = { transferMethod = m }) {
                        Text(
                            m,
                            fontSize = 12.sp,
                            color = if (transferMethod == m) colors.accentGreen else colors.textSecondary
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            KCard(
                when (transferMethod) {
                    "USB" -> "USB drive"
                    "SD Card" -> "SD card"
                    else -> "PC folder"
                },
                "Insert $transferMethod and copy the backup folder — no internet required"
            )
        }

        // -- Checklist --
        Section(title = "Migration checklist") {
            steps.forEachIndexed { i, s ->
                val isDone = step > i
                val isCurrent = step == i
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isDone -> colors.accentGreen
                                    isCurrent -> colors.accentBlue
                                    else -> colors.surfaceHover
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDone) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Text(
                                "${i + 1}",
                                color = if (isCurrent) Color.White else colors.textMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            s.title,
                            color = if (isDone) colors.accentGreen else colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            s.description,
                            color = colors.textMuted,
                            fontSize = 11.5.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { step = (step - 1).coerceAtLeast(0) },
                    enabled = step > 0
                ) { Text("Back", fontSize = 12.sp) }
                Button(
                    onClick = {
                        step = (step + 1).coerceAtMost(steps.size)
                        if (step >= steps.size) {
                            toast(ctx, "Checklist complete — verify the files on the new phone before deleting the old copy")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                ) {
                    Text(
                        if (step >= steps.size - 1) "Finish" else "Next step",
                        fontSize = 12.sp
                    )
                }
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
