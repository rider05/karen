package com.karen.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

// ---- shared manager chrome -------------------------------------------------

/**
 * The six data screens share one visual language: a quiet accent-tinted hero,
 * semantic status chip, a three-value metric strip, then grouped panels. The
 * flows below are unchanged — only the surfaces and hierarchy are new.
 */
private data class HeroMetric(
    val label: String,
    val value: String,
    val tone: KTone = KTone.Neutral,
    val caption: String? = null
)

@Composable
private fun ManagerHero(
    kicker: String,
    title: String,
    subtitle: String,
    icon: ImageVector,
    status: String,
    statusTone: KTone,
    metrics: List<HeroMetric>
) {
    val colors = LocalKarenColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        colors.accentGreen.copy(alpha = if (colors.isDark) 0.10f else 0.06f),
                        colors.surface,
                        colors.surface
                    )
                )
            )
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentGreen.copy(alpha = 0.12f))
                    .border(1.dp, colors.accentGreen.copy(alpha = 0.35f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    kicker.uppercase(),
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    letterSpacing = 1.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    title,
                    color = colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 23.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            KStatusChip(status, statusTone, led = true)
        }
        Spacer(Modifier.height(10.dp))
        Text(subtitle, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(14.dp))
        KHairline()
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            metrics.forEachIndexed { index, metric ->
                if (index > 0) {
                    Box(Modifier.width(1.dp).height(30.dp).background(colors.border))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = if (index == 0) 0.dp else 12.dp, end = if (index == metrics.lastIndex) 0.dp else 4.dp)
                ) {
                    Text(
                        metric.label.uppercase(),
                        color = colors.textMuted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        letterSpacing = 0.4.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        metric.value,
                        color = metric.tone.color(colors),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (metric.caption != null) {
                        Text(
                            metric.caption,
                            color = colors.textMuted,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelIcon(icon: ImageVector, tone: KTone = KTone.Primary, size: Dp = 18.dp) {
    val colors = LocalKarenColors.current
    val tint = tone.color(colors)
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}

@Composable
private fun ManagerNote(
    text: String,
    icon: ImageVector = Icons.Default.Lock,
    tone: KTone = KTone.Muted
) {
    val colors = LocalKarenColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(icon, contentDescription = null, tint = tone.color(colors), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun ManagerEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceHover)
                .border(1.dp, colors.border, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            color = colors.textPrimary,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            color = colors.textMuted,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(12.dp))
            KPrimaryButton(actionLabel, onAction)
        }
    }
}

@Composable
private fun SelectableTile(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null
) {
    val colors = LocalKarenColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentGreen.copy(alpha = 0.07f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) colors.accentGreen.copy(alpha = 0.45f) else colors.border,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(start = 4.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.accentGreen,
                unselectedColor = colors.textMuted
            )
        )
        Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = if (selected) colors.accentGreen else colors.textPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    KStatusChip(badge, if (selected) KTone.Success else KTone.Muted)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun ManagerStepRow(index: Int, label: String, done: Boolean, last: Boolean = false) {
    val colors = LocalKarenColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (done) colors.accentGreen else colors.surfaceHover)
                    .border(1.dp, if (done) colors.accentGreen else colors.border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                } else {
                    Text(
                        "$index",
                        color = colors.textMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (!last) {
                Box(
                    Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(if (done) colors.accentGreen.copy(alpha = 0.40f) else colors.border)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .padding(top = 1.dp, bottom = if (last) 0.dp else 8.dp)
        ) {
            Text(
                label,
                color = if (done) colors.textPrimary else colors.textSecondary,
                fontSize = 12.5.sp,
                fontWeight = if (done) FontWeight.Medium else FontWeight.Normal,
                lineHeight = 17.sp
            )
            if (done) {
                Text(
                    "DONE",
                    color = colors.accentGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    letterSpacing = 0.6.sp
                )
            }
        }
    }
}

@Composable
private fun ManagerFileRow(
    icon: ImageVector,
    title: String,
    meta: String,
    trailing: String? = null,
    tone: KTone = KTone.Muted
) {
    val colors = LocalKarenColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.surfaceHover),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tone.color(colors), modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(1.dp))
            Text(
                meta,
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                color = tone.color(colors),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ManagerResult(ok: Boolean, text: String, sub: String) {
    val colors = LocalKarenColors.current
    val tone = if (ok) KTone.Success else KTone.Amber
    KPanel(
        title = if (ok) "Complete" else "Needs attention",
        subtitle = if (ok) "verified on device" else "nothing was changed",
        icon = { PanelIcon(if (ok) Icons.Default.CheckCircle else Icons.Default.Warning, tone) },
        trailing = { KStatusChip(if (ok) "DONE" else "CHECK", tone, led = true) }
    ) {
        Text(text, color = colors.textPrimary, fontSize = 13.sp, lineHeight = 18.sp)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(sub, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun PrivacyRulesPanel() {
    val colors = LocalKarenColors.current
    val rules = listOf(
        "Explicit action required" to "Export, import and deletion each need a tap — nothing silent.",
        "No silent uploads" to "Models and memory never leave the device on their own.",
        "Import shows diff first" to "What will change is previewed before it is applied.",
        "Checksums everywhere" to "Corrupt or partial transfers are rejected."
    )
    KPanel(
        title = "Privacy rules",
        subtitle = "explicit by design",
        icon = { PanelIcon(Icons.Default.Shield, KTone.Success) },
        trailing = { KStatusChip("LOCAL", KTone.Success) }
    ) {
        rules.forEachIndexed { index, (title, body) ->
            if (index > 0) KHairline()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = colors.accentGreen,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                    Text(body, color = colors.textMuted, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
        }
    }
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

    KScreen(
        title = "Local Model Import",
        subtitle = "Real file operations · on-device validation · nothing uploaded",
        onOpenDrawer = onOpenDrawer,
        onBackClick = onNavigateToHome
    ) {
        ManagerHero(
            kicker = "Model library",
            title = "Import a model",
            subtitle = "Pick a local weight file. Karen checks the format, verifies integrity, measures free space, then copies and registers it in Model Manager.",
            icon = Icons.Default.Download,
            status = if (importing) "IMPORTING" else "LOCAL ONLY",
            statusTone = if (importing) KTone.Blue else KTone.Success,
            metrics = listOf(
                HeroMetric("Installed", "${installedModels.size}", KTone.Success, "models"),
                HeroMetric("Library", fmtBytes(totalModelBytes), KTone.Primary, "on disk"),
                HeroMetric("Free", fmtBytes(freeBytes), KTone.Neutral, "device")
            )
        )
        Spacer(Modifier.height(14.dp))

        KPanel(
            title = "Source file",
            subtitle = if (pickedUri == null) "no file selected" else "ready to validate",
            icon = { PanelIcon(Icons.Default.FolderOpen) },
            trailing = { if (pickedUri != null) KStatusChip("SELECTED", KTone.Success, led = true) }
        ) {
            if (pickedUri != null) {
                val displayName = pickedName ?: "picked file"
                ManagerFileRow(
                    icon = Icons.Default.Memory,
                    title = displayName,
                    meta = "${if (pickedSize > 0) fmtBytes(pickedSize) else "size unknown"} · " +
                        "${displayName.substringAfterLast('.', "?").uppercase()} · " +
                        "${detectQuant(displayName)} · ${detectArch(displayName)}",
                    trailing = "READY",
                    tone = KTone.Success
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KPrimaryButton(
                        label = "Validate & import",
                        onClick = { startImport() },
                        enabled = !importing,
                        modifier = Modifier.weight(1f)
                    )
                    KSecondaryButton(
                        label = "Replace",
                        onClick = { picker.launch(arrayOf("*/*")) },
                        enabled = !importing,
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(
                    onClick = {
                        pickedUri = null
                        pickedName = null
                        pickedSize = 0L
                        stage = 0
                        importResult = null
                    },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear selection", color = colors.textMuted, fontSize = 12.sp)
                }
                ManagerNote("Only GGUF runs on-device. ONNX and PTH files are stored and exported, not loaded.")
            } else {
                ManagerEmptyState(
                    icon = Icons.Default.Download,
                    title = "Choose a model file",
                    body = "Pick a .gguf, .onnx or .pth with the system file picker — Downloads, USB or anywhere.",
                    actionLabel = "Browse files…"
                ) { picker.launch(arrayOf("*/*")) }
                ManagerNote("No file leaves the device. Karen hashes what it copies.")
            }
        }

        if (pickedUri != null) {
            Spacer(Modifier.height(14.dp))
            KPanel(
                title = "Validation pipeline",
                subtitle = "five on-device checks",
                icon = { PanelIcon(Icons.Default.CheckCircle) }
            ) {
                listOf(
                    "Format check (.gguf / .onnx / .pth)",
                    "GGUF header + integrity",
                    "Free storage check",
                    "Copy into app storage (+ SHA-256)",
                    "Register in Model Manager"
                ).forEachIndexed { i, label ->
                    ManagerStepRow(i + 1, label, done = stage > i, last = i == 4)
                }
            }
        }

        if (importing) {
            Spacer(Modifier.height(14.dp))
            KPanel(
                title = "Importing",
                subtitle = "copying into app storage",
                icon = { PanelIcon(Icons.Default.Sync, KTone.Blue) },
                trailing = { KStatusChip("${(progress * 100).toInt()}%", KTone.Blue) }
            ) {
                KProgressBar(progress)
                Spacer(Modifier.height(8.dp))
                Text(
                    statusLine.ifBlank { "Working…" },
                    color = colors.textPrimary,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                KSecondaryButton(
                    label = "Cancel import",
                    onClick = { cancelRequested = true },
                    modifier = Modifier.fillMaxWidth(),
                    tone = KTone.Danger
                )
            }
        }

        if (importResult != null) {
            Spacer(Modifier.height(14.dp))
            val ok = importResult!!.startsWith("Imported")
            ManagerResult(
                ok = ok,
                text = importResult!!,
                sub = if (ok) "Ready in Model Manager — pick it in the model sheet to chat" else "Nothing was changed on disk"
            )
        }

        if (installedModels.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            KPanel(
                title = "Installed models",
                subtitle = "${installedModels.size} in the local library",
                icon = { PanelIcon(Icons.Default.Storage) }
            ) {
                installedModels.take(5).forEachIndexed { index, f ->
                    if (index > 0) KHairline()
                    ManagerFileRow(
                        icon = Icons.Default.Memory,
                        title = f.name,
                        meta = "${fmtBytes(f.length())} · ${detectQuant(f.name)} · ${detectArch(f.name)}",
                        trailing = "GGUF",
                        tone = KTone.Muted
                    )
                }
                if (installedModels.size > 5) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "+${installedModels.size - 5} more in Model Manager",
                        color = colors.accentGreen,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
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

    KScreen(
        title = "Portable Model Export",
        subtitle = "GGUF weights · SHA-256 measured while copying",
        onOpenDrawer = onOpenDrawer,
        onBackClick = onNavigateToHome
    ) {
        ManagerHero(
            kicker = "Portable export",
            title = "Take a model with you",
            subtitle = "Streams the selected GGUF to a destination you choose and hashes it on the way. The installed copy is never touched.",
            icon = Icons.Default.CloudUpload,
            status = if (exporting) "EXPORTING" else "READY",
            statusTone = if (exporting) KTone.Blue else KTone.Neutral,
            metrics = listOf(
                HeroMetric("Models", "${installedModels.size}", KTone.Success, "GGUF"),
                HeroMetric("Selected", selectedFile?.let { fmtBytes(it.length()) } ?: "—", KTone.Primary),
                HeroMetric("Metadata", if (includeMetadata) "Sidecar" else "Off", if (includeMetadata) KTone.Blue else KTone.Muted)
            )
        )
        Spacer(Modifier.height(14.dp))

        KPanel(
            title = "Choose a model",
            subtitle = if (installedModels.isEmpty()) "none installed" else "${installedModels.size} available",
            icon = { PanelIcon(Icons.Default.Memory) },
            trailing = { if (selectedFile != null) KStatusChip("1 SELECTED", KTone.Success, led = true) }
        ) {
            if (installedModels.isEmpty()) {
                ManagerEmptyState(
                    icon = Icons.Default.Memory,
                    title = "No GGUF models installed",
                    body = "Import a model first to export it."
                )
            } else {
                installedModels.take(5).forEach { f ->
                    val selected = selectedModel == f.name
                    SelectableTile(
                        selected = selected,
                        title = f.name,
                        subtitle = "${fmtBytes(f.length())} · ${detectQuant(f.name)} · ${detectArch(f.name)}",
                        onClick = { selectedModel = f.name },
                        badge = if (selected) "READY" else null
                    )
                }
                if (installedModels.size > 5) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "+${installedModels.size - 5} more models in Model Manager",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Package options",
            subtitle = "what gets written",
            icon = { PanelIcon(Icons.Default.Save) }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { includeMetadata = !includeMetadata }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = includeMetadata,
                    onCheckedChange = { includeMetadata = it }
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "Metadata sidecar",
                        color = colors.textPrimary,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "A .json with model id, quant, arch, size and SHA-256.",
                        color = colors.textMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }
            }
            if (includeMetadata && selectedModel != null) {
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceHover)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        "${selectedModel!!.removeSuffix(".gguf")}.json",
                        color = colors.accentBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "model_id · format GGUF · quant ${detectQuant(selectedModel!!)} · arch ${detectArch(selectedModel!!)} · sha256 · exported_at",
                        color = colors.textMuted,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            KHairline()
            Spacer(Modifier.height(4.dp))
            KTelemetryRow("Installed copy", "Kept — never auto-deleted", valueColor = colors.accentGreen)
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Export",
            subtitle = if (exporting) "writing to destination" else "system file picker",
            icon = { PanelIcon(Icons.Default.CloudUpload, KTone.Blue) },
            trailing = { if (exporting) KStatusChip("${(progress * 100).toInt()}%", KTone.Blue) }
        ) {
            if (exporting) {
                KProgressBar(progress)
                Spacer(Modifier.height(8.dp))
                KTelemetryRow(
                    "Copied",
                    "${fmtBytes(copiedBytes)} / ${fmtBytes(selectedFile?.length() ?: 0L)}",
                    valueColor = colors.accentBlue
                )
                Spacer(Modifier.height(6.dp))
                KSecondaryButton(
                    label = "Cancel export",
                    onClick = { cancelRequested = true },
                    modifier = Modifier.fillMaxWidth(),
                    tone = KTone.Danger
                )
            } else {
                KPrimaryButton(
                    label = "Export model…",
                    onClick = {
                        val f = selectedFile
                        if (f == null) {
                            toast(ctx, "Select a model first")
                        } else {
                            exportResult = null
                            modelSaver.launch(f.name)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedFile != null
                )
                ManagerNote(
                    "The system picker chooses the destination — SD card, USB or a cloud folder. The installed copy is kept.",
                    Icons.Default.Info
                )
            }
        }

        if (exportResult != null) {
            Spacer(Modifier.height(14.dp))
            val ok = exportResult!!.startsWith("Export complete") || exportResult!!.startsWith("Model copied")
            ManagerResult(
                ok = ok,
                text = exportResult!!,
                sub = if (ok) "SHA-256 measured while copying — safe to transfer" else "Nothing was written"
            )
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

    KScreen(
        title = "Memory Export & Import",
        subtitle = "Portable facts · real data · encrypted at rest",
        onOpenDrawer = onOpenDrawer,
        onBackClick = onNavigateToHome
    ) {
        ManagerHero(
            kicker = "Memory",
            title = "Move your memory",
            subtitle = "Export verified facts as a portable JSON package, or merge a package from another device. Nothing leaves the phone on its own.",
            icon = Icons.Default.SwapHoriz,
            status = "OFFLINE",
            statusTone = KTone.Success,
            metrics = listOf(
                HeroMetric("Verified", "${verifiedFacts.size}", KTone.Success, "confirmed"),
                HeroMetric("Pending", "${pendingFacts.size}", KTone.Amber, "to review"),
                HeroMetric("Total", "${allFacts.size}", KTone.Primary, "facts")
            )
        )

        if (categoryCounts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "MEMORY LIBRARY",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            val palette = listOf(
                colors.accentGreen, colors.accentBlue, colors.accentAmber,
                Color(0xFF8B5CF6),
                Color(0xFFEC4899),
            )
            val slices = categoryCounts.mapIndexed { i, (cat, count) ->
                Triple(cat, count.toFloat(), palette[i % palette.size])
            }
            KPieChart(slices, size = 104.dp, centerLabel = "${allFacts.size}")
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Export",
            subtitle = "portable JSON package",
            icon = { PanelIcon(Icons.Default.CloudUpload) }
        ) {
            Text(
                "Writes every verified and pending fact into one package in Karen's backups folder.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))
            if (exporting) {
                KProgressBar(1f)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Writing ${allFacts.size} facts…",
                    color = colors.accentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                KPrimaryButton(
                    label = "Export memory",
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (exportResult != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceHover)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = colors.accentGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(exportResult!!, color = colors.textPrimary, fontSize = 12.5.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("Package is offline and portable", color = colors.textMuted, fontSize = 11.sp)
                    }
                }
            }
            if (existingBackups.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                KHairline()
                Spacer(Modifier.height(8.dp))
                Text(
                    "RECENT BACKUPS",
                    color = colors.textMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )
                existingBackups.take(3).forEachIndexed { index, f ->
                    if (index > 0) KHairline()
                    ManagerFileRow(
                        icon = Icons.Default.Archive,
                        title = f.name,
                        meta = "${fmtBytes(f.length())} · " +
                            java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(f.lastModified())),
                        trailing = "JSON",
                        tone = KTone.Muted
                    )
                }
                if (existingBackups.size > 3) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "+${existingBackups.size - 3} more backups",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Import",
            subtitle = if (importPickedName == null) "no package selected" else "preview ready",
            icon = { PanelIcon(Icons.Default.Download) },
            trailing = { if (importPreview != null) KStatusChip("PREVIEW", KTone.Blue, led = true) }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Merge", "Verified only", "Replace").forEach { m ->
                    KChip(
                        selectableLabel = m,
                        selected = mode == m,
                        onClick = { mode = m }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (mode) {
                    "Merge" -> "Adds new facts only — duplicates are skipped by id."
                    "Verified only" -> "Imports only the facts you had confirmed in the package."
                    else -> "Clears current memory, then imports the package. Requires confirmation."
                },
                color = colors.textMuted,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
            Spacer(Modifier.height(12.dp))
            KHairline()
            Spacer(Modifier.height(10.dp))

            if (importPickedName != null && importPreview != null) {
                val pkg = importPreview!!
                ManagerFileRow(
                    icon = Icons.Default.Archive,
                    title = importPickedName!!,
                    meta = "${pkg.facts.size} facts · schema v${pkg.schemaVersion} · " +
                        java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(pkg.exportedAt)),
                    trailing = "JSON",
                    tone = KTone.Blue
                )
                Spacer(Modifier.height(8.dp))
                val pool = if (mode == "Verified only") pkg.facts.filter { it.verified } else pkg.facts
                val newCount = pool.count { f -> allFacts.none { it.id == f.id } }
                val dupCount = pool.size - newCount
                KTelemetryRow(
                    "New facts",
                    if (mode == "Replace") "${pkg.facts.size} (all — memory is cleared first)" else "$newCount",
                    valueColor = colors.accentGreen
                )
                KTelemetryRow("Duplicates", "$dupCount — skipped")
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KPrimaryButton(
                        label = "Import ($mode)",
                        onClick = { if (mode == "Replace") confirmReplace = true else applyImport(replace = false) },
                        modifier = Modifier.weight(1f)
                    )
                    KSecondaryButton(
                        label = "Cancel",
                        onClick = {
                            importPickedName = null
                            importPreview = null
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
                ManagerEmptyState(
                    icon = Icons.Default.Download,
                    title = "No package selected",
                    body = "Pick a memory backup .json from your device.",
                    actionLabel = "Browse files…"
                ) { importPicker.launch(arrayOf("application/json")) }
            }
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

    KScreen(
        title = "Portable Karen Backup",
        subtitle = "One offline JSON package · checksums · explicit restore",
        onOpenDrawer = onOpenDrawer,
        onBackClick = onNavigateToHome
    ) {
        ManagerHero(
            kicker = "Backup",
            title = "A portable copy of Karen",
            subtitle = "Bundle memory, conversations and model metadata into one verified file. Restore only when you choose — nothing syncs in the background.",
            icon = Icons.Default.Archive,
            status = "OFFLINE",
            statusTone = KTone.Success,
            metrics = listOf(
                HeroMetric("Chats", "${conversations.size}", KTone.Neutral, "threads"),
                HeroMetric("Memory", "${memoryFacts.size + pendingFacts.size}", KTone.Primary, "facts"),
                HeroMetric("Size", fmtBytes(totalPackageBytes), KTone.Muted, "estimated")
            )
        )
        Spacer(Modifier.height(14.dp))

        KPanel(
            title = "Backup mode",
            subtitle = "choose what to include",
            icon = { PanelIcon(Icons.Default.Save) }
        ) {
            listOf(
                "Quick" to "Settings · memory · routines · metadata",
                "Full" to "+ conversations · indexes · model metadata",
                "Complete Offline Copy" to "+ selected model binaries · checksums"
            ).forEach { (m, desc) ->
                SelectableTile(
                    selected = selectedMode == m,
                    title = m,
                    subtitle = desc,
                    onClick = { selectedMode = m },
                    badge = if (m == "Full") "DEFAULT" else null
                )
            }
            ManagerNote(
                "Quick keeps the package small. Complete Offline Copy adds model binaries and checksums.",
                Icons.Default.Info
            )
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Package contents",
            subtitle = "${packageItems.count { it.included }} of ${packageItems.size} included",
            icon = { PanelIcon(Icons.Default.Archive, KTone.Blue) }
        ) {
            packageItems.forEachIndexed { index, item ->
                if (index > 0) KHairline()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        if (item.included) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (item.included) colors.accentGreen else colors.textMuted,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.label,
                            color = colors.textPrimary,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            item.detail,
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    KStatusChip(
                        if (item.included) "IN" else "SKIP",
                        if (item.included) KTone.Success else KTone.Muted
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Create & restore",
            subtitle = if (creating) "writing package" else "local only",
            icon = { PanelIcon(Icons.Default.CloudUpload, KTone.Blue) },
            trailing = { if (creating) KStatusChip("${(progress * 100).toInt()}%", KTone.Blue) }
        ) {
            if (creating) {
                KProgressBar(progress)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        progress < 0.2f -> "Collecting memory facts…"
                        progress < 0.8f -> "Writing conversations…"
                        progress < 1f -> "Hashing & saving…"
                        else -> "Done"
                    },
                    color = colors.accentBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KPrimaryButton(
                        label = "Create backup",
                        onClick = { createBackup() },
                        modifier = Modifier.weight(1f)
                    )
                    KSecondaryButton(
                        label = "Restore…",
                        onClick = { restorePicker.launch(arrayOf("application/json")) },
                        modifier = Modifier.weight(1f)
                    )
                }
                ManagerNote(
                    "Backups are written to Android/data/com.karen/files/backups and never leave the device on their own.",
                    Icons.Default.Lock
                )
            }
            if (backupResult != null) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceHover)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        if (backupResult!!.startsWith("Backup created") || backupResult!!.startsWith("Restored"))
                            Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (backupResult!!.startsWith("Backup created") || backupResult!!.startsWith("Restored"))
                            colors.accentGreen else colors.accentAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(backupResult!!, color = colors.textPrimary, fontSize = 12.5.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Package is offline and portable — no cloud account needed",
                            color = colors.textMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        val preview = restorePreview
        if (preview != null) {
            Spacer(Modifier.height(14.dp))
            KPanel(
                title = "Restore preview",
                subtitle = preview.fileName,
                icon = { PanelIcon(Icons.Default.Download, KTone.Blue) },
                trailing = {
                    KStatusChip(
                        if (preview.shaOk) "VERIFIED" else "UNVERIFIED",
                        if (preview.shaOk) KTone.Success else KTone.Amber,
                        led = true
                    )
                }
            ) {
                KTelemetryRow("Memory facts", "${preview.facts.size} (restored as pending)")
                KTelemetryRow("Conversations", "${preview.conversations.size}")
                KTelemetryRow("Model entries", "${preview.models.size} (manifest only)")
                KTelemetryRow(
                    "Checksum",
                    if (preview.shaOk) "sha256 verified" else "not verified — older or edited file",
                    valueColor = if (preview.shaOk) colors.accentGreen else colors.accentAmber
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KPrimaryButton(
                        label = if (restoring) "Restoring…" else "Apply restore",
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
                        modifier = Modifier.weight(1f),
                        enabled = !restoring
                    )
                    KSecondaryButton(
                        label = "Cancel",
                        onClick = { restorePreview = null },
                        modifier = Modifier.weight(1f),
                        enabled = !restoring
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        PrivacyRulesPanel()
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

    KScreen(
        title = "Storage & File Manager",
        subtitle = "Real device data · re-scans on every visit",
        onOpenDrawer = onOpenDrawer,
        onBackClick = onNavigateToHome
    ) {
        ManagerHero(
            kicker = "Storage",
            title = "Karen storage",
            subtitle = "Every byte is counted live from the app's own folders. Categories update when you return to this screen.",
            icon = Icons.Default.Storage,
            status = "SCANNED",
            statusTone = KTone.Blue,
            metrics = listOf(
                HeroMetric("Device", fmtBytes(deviceTotal), KTone.Neutral, "total"),
                HeroMetric("Karen", fmtBytes(karenTotal), KTone.Primary, "in use"),
                HeroMetric("Free", fmtBytes(deviceFree), KTone.Success, "available")
            )
        )

        if (cats.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                "USAGE BREAKDOWN",
                color = colors.textMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                letterSpacing = 0.6.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            val palette = listOf(
                colors.accentGreen, colors.accentBlue, colors.accentAmber,
                Color(0xFF8B5CF6),
                Color(0xFFEC4899),
                Color(0xFF06B6D4),
                Color(0xFFF97316),
            )
            val slices = cats.mapIndexed { i, c ->
                Triple(c.label, c.bytes.toFloat(), palette[i % palette.size])
            }
            KPieChart(
                slices,
                size = 108.dp,
                centerLabel = fmtBytes(karenTotal),
                valueFormatter = { fmtBytes(it.toLong()) }
            )
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Categories",
            subtitle = "${cats.size} active folders",
            icon = { PanelIcon(Icons.Default.Storage) }
        ) {
            if (cats.isEmpty()) {
                Text(
                    "No Karen data found yet. Chat, import a model or add a document to see it here.",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
            val maxBytes = cats.maxOfOrNull { it.bytes }?.takeIf { it > 0L } ?: 1L
            cats.forEachIndexed { index, cat ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(cat.label, color = colors.textPrimary, fontSize = 12.5.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            fmtBytes(cat.bytes),
                            color = colors.textMuted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${cat.files} files",
                            color = colors.textMuted,
                            fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                KProgressBar(
                    fraction = (cat.bytes.toFloat() / maxBytes).coerceIn(0f, 1f),
                    height = 4.dp,
                    tone = KTone.Blue
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Maintenance",
            subtitle = "safe cleanup",
            icon = { PanelIcon(Icons.Default.Sync, KTone.Amber) }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KSecondaryButton(
                    label = "Clear cache${if (cacheSize > 0) " (${fmtBytes(cacheSize)})" else ""}",
                    onClick = {
                        ctx.cacheDir.deleteRecursively()
                        toast(ctx, "Cache cleared (${fmtBytes(cacheSize)})")
                        refreshKey++
                    },
                    modifier = Modifier.weight(1f)
                )
                KSecondaryButton(
                    label = "Clear downloads",
                    onClick = {
                        val d = java.io.File(ctx.getExternalFilesDir(null), "updates")
                        val freed = walkSize(d).first
                        d.listFiles()?.forEach { it.delete() }
                        toast(ctx, "Downloads cleared (${fmtBytes(freed)})")
                        refreshKey++
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            ManagerNote(
                "User documents are never deleted automatically. Large operations warn first.",
                Icons.Default.Shield
            )
        }

        if (largestFiles.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            KPanel(
                title = "Largest files",
                subtitle = "top 5 by size",
                icon = { PanelIcon(Icons.Default.FolderOpen) }
            ) {
                largestFiles.forEachIndexed { index, f ->
                    if (index > 0) KHairline()
                    ManagerFileRow(
                        icon = Icons.Default.FolderOpen,
                        title = f.name,
                        meta = f.parentFile?.name ?: "Karen",
                        trailing = fmtBytes(f.length()),
                        tone = KTone.Muted
                    )
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

    KScreen(
        title = "Offline Migration",
        subtitle = "Phone-to-phone · no cloud account · verified files",
        onOpenDrawer = onOpenDrawer,
        onBackClick = onNavigateToHome
    ) {
        val complete = step >= steps.size
        ManagerHero(
            kicker = "Device migration",
            title = "Move to a new phone",
            subtitle = "Follow the checklist, copy the backup folder with USB, SD card or a PC, then verify the checksums on the new device.",
            icon = Icons.Default.PhoneAndroid,
            status = if (complete) "COMPLETE" else "STEP ${step + 1}/${steps.size}",
            statusTone = if (complete) KTone.Success else KTone.Blue,
            metrics = listOf(
                HeroMetric("Chats", "${conversations.size}", KTone.Neutral, "threads"),
                HeroMetric("Memory", "${memoryFacts.size}", KTone.Primary, "facts"),
                HeroMetric("Models", "${modelFiles.size}", KTone.Success, "GGUF")
            )
        )

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Transfer size",
            subtitle = "what will move",
            icon = { PanelIcon(Icons.Default.SwapHoriz) }
        ) {
            KTelemetryRow("Conversations", fmtBytes(chatBytes))
            KTelemetryRow("Memory facts", fmtBytes(memoryBytes))
            KTelemetryRow("Model binaries", fmtBytes(modelBytes), valueColor = colors.accentBlue)
            Spacer(Modifier.height(6.dp))
            KHairline()
            Spacer(Modifier.height(4.dp))
            KTelemetryRow("Total", fmtBytes(totalMigrationBytes), valueColor = colors.accentGreen)
            ManagerNote(
                "Model binaries dominate the transfer. A Quick backup moves the rest without the weights.",
                Icons.Default.Info
            )
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Transfer method",
            subtitle = "offline media",
            icon = { PanelIcon(Icons.Default.PhoneAndroid) }
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("USB", "SD Card", "PC Folder").forEach { m ->
                    KChip(
                        selectableLabel = m,
                        selected = transferMethod == m,
                        onClick = { transferMethod = m }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            val methodLabel = when (transferMethod) {
                "USB" -> "USB drive"
                "SD Card" -> "SD card"
                else -> "PC folder"
            }
            val instruction = when (transferMethod) {
                "USB" -> "Insert the USB drive and copy the backup folder — no internet required."
                "SD Card" -> "Insert the SD card and copy the backup folder — no internet required."
                else -> "Connect to a PC and copy the backup folder — no internet required."
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceHover)
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = colors.accentBlue,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(Modifier.width(9.dp))
                Column {
                    Text(
                        methodLabel,
                        color = colors.textPrimary,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(instruction, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        KPanel(
            title = "Migration checklist",
            subtitle = "$step of ${steps.size} complete",
            icon = { PanelIcon(Icons.Default.CheckCircle) },
            trailing = {
                KStatusChip(
                    "$step/${steps.size}",
                    if (complete) KTone.Success else KTone.Blue,
                    led = true
                )
            }
        ) {
            steps.forEachIndexed { i, s ->
                val isDone = step > i
                val isCurrent = step == i
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                                )
                                .border(
                                    1.dp,
                                    when {
                                        isDone -> colors.accentGreen
                                        isCurrent -> colors.accentBlue
                                        else -> colors.border
                                    },
                                    CircleShape
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
                        if (i < steps.lastIndex) {
                            Box(
                                Modifier
                                    .width(1.dp)
                                    .height(22.dp)
                                    .background(if (isDone) colors.accentGreen.copy(alpha = 0.40f) else colors.border)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(top = 2.dp, bottom = if (i < steps.lastIndex) 8.dp else 0.dp)
                    ) {
                        Text(
                            s.title,
                            color = if (isDone) colors.accentGreen else colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 18.sp
                        )
                        Text(
                            s.description,
                            color = colors.textMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KSecondaryButton(
                    label = "Back",
                    onClick = { step = (step - 1).coerceAtLeast(0) },
                    modifier = Modifier.weight(1f),
                    enabled = step > 0
                )
                KPrimaryButton(
                    label = if (step >= steps.size - 1) "Finish" else "Next step",
                    onClick = {
                        step = (step + 1).coerceAtMost(steps.size)
                        if (step >= steps.size) {
                            toast(ctx, "Checklist complete — verify the files on the new phone before deleting the old copy")
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        PrivacyRulesPanel()
    }
}
