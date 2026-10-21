package com.karen.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.karen.rememberDeviceTelemetry
import com.karen.formatSize
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** User document stores: the vault plus chat-saved attachments. */
internal fun vaultDirs(context: Context): List<File> =
    listOf(File(context.filesDir, "vault"), File(context.filesDir, "attachments"))

/** Real file list for the Files screen, newest first. */
internal fun vaultFiles(context: Context): List<File> =
    vaultDirs(context)
        .filter { it.exists() && it.isDirectory }
        .flatMap { it.listFiles()?.toList() ?: emptyList() }
        .filter { it.isFile }
        .sortedByDescending { it.lastModified() }

/** True when [f] lives inside the user stores (never models/voices). */
private fun isDeletable(context: Context, f: File): Boolean = try {
    val canon = f.canonicalPath
    vaultDirs(context).any { canon.startsWith(it.canonicalPath + File.separator) }
} catch (_: Exception) {
    false
}

internal fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "txt", "md" -> "text/plain"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "ogg" -> "audio/ogg"
    "mp4" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "zip" -> "application/zip"
    else -> "*/*"
}

/** Text preview for small text files, null when not previewable. */
internal fun readPreview(f: File, maxChars: Int = 50_000): String? {
    if (f.length() > 2_000_000) return null
    return try {
        f.readText(Charsets.UTF_8).take(maxChars)
    } catch (_: Exception) {
        null
    }
}

@Composable
fun FilesScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedVault by remember { mutableStateOf(UserPrefs.selectedVault(context)) }
    var showVaultSheet by remember { mutableStateOf(false) }
    var fileQuery by remember { mutableStateOf("") }
    /** List order for the vault lists below (cycles from the ⋯ menu). */
    var sortMode by remember { mutableStateOf("Newest") }
    /** Bumped after import/delete so the lists rescan. */
    var refreshTick by remember { mutableStateOf(0L) }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    var renamingFile by remember { mutableStateOf<File?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var showNewNote by remember { mutableStateOf(false) }
    var noteName by remember { mutableStateOf("") }
    var noteContent by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    /** Case-insensitive name filter for the vault lists below. */
    fun matchesQuery(f: java.io.File): Boolean =
        fileQuery.isBlank() || f.name.contains(fileQuery.trim(), ignoreCase = true)

    fun currentFiles(): List<File> = vaultFiles(context)

    fun toast(msg: String) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Copies picked documents into the vault (dedupe by counter suffix). */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val dir = File(context.filesDir, "vault").apply { mkdirs() }
            var added = 0
            uris.forEach { uri ->
                try {
                    val raw = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && i >= 0) c.getString(i) else null
                    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                    val safe = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifBlank { "file" }
                    var dest = File(dir, safe)
                    var n = 2
                    while (dest.exists()) {
                        val stem = safe.substringBeforeLast('.', safe)
                        val ext = safe.substringAfterLast('.', "")
                        dest = File(dir, "$stem-$n${if (ext.isNotEmpty() && ext != safe) ".$ext" else ""}")
                        n++
                    }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
                    }
                    if (dest.exists() && dest.length() > 0) added++
                    else try { dest.delete() } catch (_: Exception) {}
                } catch (_: Exception) {
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                refreshTick = System.currentTimeMillis()
                toast(if (added > 0) "Added $added file${if (added == 1) "" else "s"} to the vault" else "Import failed")
            }
        }
    }

    val vaultPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        importUris(uris)
    }

    /** In-app text preview when possible, else a system viewer. */
    fun openFile(f: File) {
        if (!f.exists()) {
            refreshTick = System.currentTimeMillis()
            toast("File no longer exists")
            return
        }
        if (f.extension.lowercase() in listOf("txt", "md")) {
            val text = readPreview(f)
            if (text != null) {
                previewFile = f
                return
            }
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: mimeFor(f.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: Exception) {
            toast("No app can open this file")
        }
    }

    fun deleteFile(f: File) {
        try {
            if (!isDeletable(context, f)) {
                toast("Only vault files can be deleted here")
                return
            }
            if (f.delete() || !f.exists()) toast("${f.name} deleted")
            else toast("Delete failed")
        } catch (_: Exception) {
            toast("Delete failed")
        }
        refreshTick = System.currentTimeMillis()
    }

    /** Renames a vault file (same folder, dedupe by counter suffix). */
    fun renameFile(f: File, newName: String) {
        val clean = newName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        if (clean.isBlank() || clean == f.name) return
        if (!isDeletable(context, f)) {
            toast("Only vault files can be renamed here")
            return
        }
        try {
            val dir = f.parentFile ?: return
            val stem = clean.substringBeforeLast('.', clean)
            val ext = clean.substringAfterLast('.', "")
            val withExt = if (ext.isNotEmpty() && ext != clean) ".$ext" else ""
            var dest = File(dir, clean)
            var n = 2
            while (dest.exists()) {
                dest = File(dir, "$stem-$n$withExt")
                n++
            }
            if (f.renameTo(dest)) toast("Renamed to ${dest.name}")
            else toast("Rename failed")
        } catch (_: Exception) {
            toast("Rename failed")
        }
        refreshTick = System.currentTimeMillis()
    }

    /** Creates a new text note directly in the vault. */
    fun createNote() {
        val content = noteContent.trimEnd()
        if (content.isBlank()) {
            toast("Write something first")
            return
        }
        try {
            val dir = File(context.filesDir, "vault").apply { mkdirs() }
            var base = noteName.trim().replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifBlank { "note" }
            if ('.' !in base) base += ".md"
            var dest = File(dir, base)
            var n = 2
            while (dest.exists()) {
                val stem = base.substringBeforeLast('.')
                val ext = base.substringAfterLast('.', "")
                dest = File(dir, "$stem-$n.$ext")
                n++
            }
            dest.writeText(content)
            noteName = ""
            noteContent = ""
            showNewNote = false
            refreshTick = System.currentTimeMillis()
            toast("${dest.name} saved to the vault")
        } catch (_: Exception) {
            toast("Could not save the note")
        }
    }

    /** Shares a vault file with another app (view-only grant). */
    fun shareFile(f: File) {
        try {
            if (!f.exists()) {
                refreshTick = System.currentTimeMillis()
                toast("File no longer exists")
                return
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: mimeFor(f.name)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${f.name}"))
        } catch (_: Exception) {
            toast("Share failed")
        }
    }

    // System back: dismiss dialogs/sheet first, otherwise route to Home —
    // same in-app behaviour as the chat screen (never exits to phone home).
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
        if (previewFile != null) { previewFile = null; true }
        else if (pendingDelete != null) { pendingDelete = null; true }
        else if (renamingFile != null) { renamingFile = null; true }
        else if (showNewNote) { showNewNote = false; true }
        else if (showVaultSheet) { showVaultSheet = false; true } else false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = selectedVault,
            onMenuClick = onOpenDrawer,
            onBackClick = onNavigateToHome,
            onModelClick = { showVaultSheet = true },
            moreActions = listOf(
                Triple("Add File", Icons.Default.Add) { vaultPicker.launch(arrayOf("*/*")) },
                Triple("New note", Icons.Default.NoteAdd) { showNewNote = true },
                Triple("Sort: $sortMode", Icons.Default.Sort) {
                    sortMode = when (sortMode) {
                        "Newest" -> "Oldest"
                        "Oldest" -> "Name"
                        "Name" -> "Size"
                        else -> "Newest"
                    }
                },
                Triple("Refresh", Icons.Default.Refresh) { refreshTick = System.currentTimeMillis() }
            )
        )

        // Real vault contents (rescanned after import/delete/refresh).
        val allFiles = remember(refreshTick, sortMode) {
            val base = currentFiles()
            when (sortMode) {
                "Oldest" -> base.sortedBy { it.lastModified() }
                "Name" -> base.sortedBy { it.name.lowercase() }
                "Size" -> base.sortedByDescending { it.length() }
                else -> base
            }
        }
        val vaultBytes = remember(allFiles) { allFiles.sumOf { it.length() } }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header hero
            item {
                VaultHero(
                    vaultName = selectedVault,
                    fileCount = allFiles.size,
                    vaultBytes = vaultBytes,
                    usedGb = device.storageTotalGb - device.storageFreeGb,
                    totalGb = device.storageTotalGb,
                    onAdd = { vaultPicker.launch(arrayOf("*/*")) }
                )
            }

            // Search + latest file card
            item {
                OutlinedTextField(
                    value = fileQuery,
                    onValueChange = { fileQuery = it },
                    placeholder = { Text("Search local documents…", color = colors.textMuted) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = colors.accentBlue, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (fileQuery.isNotEmpty()) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = colors.textMuted,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { fileQuery = "" }
                            )
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontSize = 13.5.sp),
                    colors = karenFieldColors(colors),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )
                val topDoc = remember(allFiles) { allFiles.firstOrNull() }
                VaultSectionLabel(
                    kicker = "Latest file",
                    icon = Icons.Default.History,
                    tint = colors.accentGreen,
                    trailing = if (topDoc == null) "empty" else formatSize(topDoc.length())
                )
                Spacer(Modifier.height(6.dp))
                LatestVaultCard(file = topDoc, onOpen = { topDoc?.let { openFile(it) } })
            }

            // File items list (Secure Vault view)
            if (selectedVault == "Secure Vault") item {
                VaultSectionLabel(
                    kicker = "Indexed vault items",
                    icon = Icons.Default.FolderSpecial,
                    tint = colors.accentGreen,
                    trailing = "${allFiles.size} files"
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val files = allFiles.filter { matchesQuery(it) }
                    if (files.isEmpty()) {
                        VaultEmptyState(
                            icon = Icons.Default.FolderOpen,
                            title = if (fileQuery.isBlank()) "No indexed files yet" else "No matches",
                            body = if (fileQuery.isBlank()) "Add files with Add File above." else "Nothing matches “${fileQuery.trim()}”.",
                            tint = colors.accentGreen
                        )
                    }
                    files.forEach { f ->
                        VaultFileRow(
                            f.name,
                            "${formatSize(f.length())} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(f.lastModified()))}",
                            when (f.extension.lowercase()) {
                                "kt", "rs", "java" -> Icons.Default.Code
                                "wav", "mp3" -> Icons.Default.Mic
                                "gguf" -> Icons.Default.Memory
                                "md", "txt", "pdf" -> Icons.Default.Description
                                else -> Icons.AutoMirrored.Filled.InsertDriveFile
                            },
                            onOpen = { openFile(f) },
                            onShare = { shareFile(f) },
                            onRename = {
                                renamingFile = f
                                renameInput = f.name
                            },
                            onDelete = { pendingDelete = f }
                        )
                    }
                }
            }

            // Knowledge Vault section (mirrors Sovereign Storage, for knowledge assets)
            if (selectedVault == "Knowledge Vault") item {
                VaultSectionLabel(
                    kicker = "Knowledge vault",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    tint = colors.accentBlue,
                    trailing = "${allFiles.count { it.extension.lowercase() in listOf("pdf", "md", "txt") }} docs"
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val kvFiles = allFiles.filter { it.extension.lowercase() in listOf("pdf", "md", "txt") && matchesQuery(it) }
                    if (kvFiles.isEmpty()) {
                        VaultEmptyState(
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            title = if (fileQuery.isBlank()) "No knowledge documents" else "No matches",
                            body = if (fileQuery.isBlank()) "Add PDFs or notes with Add File above." else "Nothing matches “${fileQuery.trim()}”.",
                            tint = colors.accentBlue
                        )
                    }
                    kvFiles.forEach { f ->
                        VaultFileRow(
                            f.name,
                            "${formatSize(f.length())} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(f.lastModified()))}",
                            Icons.AutoMirrored.Filled.MenuBook,
                            onOpen = { openFile(f) },
                            onShare = { shareFile(f) },
                            onRename = {
                                renamingFile = f
                                renameInput = f.name
                            },
                            onDelete = { pendingDelete = f }
                        )
                    }
                }
            }
        }

        if (showVaultSheet) {
            VaultSelectorSheet(
                selectedVault = selectedVault,
                onSelectVault = {
                    selectedVault = it
                    UserPrefs.setSelectedVault(context, it)
                },
                onDismiss = { showVaultSheet = false },
                knowledgeCount = allFiles.count { it.extension.lowercase() in listOf("pdf", "md", "txt") },
                secureCount = allFiles.size
            )
        }

        val doomed = pendingDelete
        if (doomed != null) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = { Text("Delete file?") },
                text = { Text("“${doomed.name}” will be removed from the vault. This cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        deleteFile(doomed)
                    }) { Text("Delete", color = colors.accentRed) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                }
            )
        }

        val renaming = renamingFile
        if (renaming != null) {
            AlertDialog(
                onDismissRequest = { renamingFile = null },
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = { Text("Rename file") },
                text = {
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it.take(80) },
                        label = { Text("File name", color = colors.textMuted) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = colors.textPrimary,
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = karenFieldColors(colors),
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        renamingFile = null
                        renameFile(renaming, renameInput)
                    }) { Text("Rename", color = colors.accentGreen) }
                },
                dismissButton = {
                    TextButton(onClick = { renamingFile = null }) { Text("Cancel") }
                }
            )
        }

        if (showNewNote) {
            AlertDialog(
                onDismissRequest = { showNewNote = false },
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = { Text("New note") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = noteName,
                            onValueChange = { noteName = it.take(60) },
                            label = { Text("Name (blank → note.md)", color = colors.textMuted) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = colors.textPrimary,
                                fontFamily = FontFamily.Monospace
                            ),
                            colors = karenFieldColors(colors),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = noteContent,
                            onValueChange = { noteContent = it },
                            label = { Text("Content", color = colors.textMuted) },
                            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                            colors = karenFieldColors(colors),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { createNote() }) { Text("Save", color = colors.accentGreen) }
                },
                dismissButton = {
                    TextButton(onClick = { showNewNote = false }) { Text("Cancel") }
                }
            )
        }

        val preview = previewFile
        if (preview != null) {
            val previewText = remember(preview) { readPreview(preview) ?: "(cannot preview this file)" }
            AlertDialog(
                onDismissRequest = { previewFile = null },
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = { Text(preview.name) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(previewText, color = colors.textSecondary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { previewFile = null }) { Text("Close") }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultSelectorSheet(
    selectedVault: String,
    onSelectVault: (String) -> Unit,
    onDismiss: () -> Unit,
    knowledgeCount: Int = 0,
    secureCount: Int = 0
) {
    val colors = LocalKarenColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.border) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(colors.accentBlue.copy(alpha = 0.13f))
                        .border(1.dp, colors.accentBlue.copy(alpha = 0.28f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = colors.accentBlue, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "VAULT SELECTOR",
                        color = colors.accentBlue,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp
                    )
                    Text("Choose a local store", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "Switch between vault views — one secure screen, two logical stores",
                color = colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
            )
            val vaults = listOf(
                Triple("Knowledge Vault", "PDFs, notes and markdown stored on-device", "$knowledgeCount files"),
                Triple("Secure Vault", "Every file in the vault and attachments", "$secureCount files")
            )
            vaults.forEach { (name, desc, badge) ->
                val isSelected = selectedVault == name
                val tint = if (name == "Knowledge Vault") colors.accentBlue else colors.accentGreen
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) tint.copy(alpha = 0.08f) else Color.Transparent)
                        .border(1.dp, if (isSelected) tint.copy(alpha = 0.5f) else colors.border, RoundedCornerShape(14.dp))
                        .clickable {
                            onSelectVault(name)
                            onDismiss()
                        }
                        .padding(13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(tint.copy(alpha = 0.13f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (name == "Knowledge Vault") Icons.AutoMirrored.Filled.MenuBook else Icons.Default.Lock,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isSelected) tint.copy(alpha = 0.14f) else colors.border.copy(alpha = 0.35f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    badge,
                                    color = if (isSelected) tint else colors.textMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(desc, color = colors.textMuted, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            onSelectVault(name)
                            onDismiss()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = tint, unselectedColor = colors.textMuted)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.surfaceHover.copy(alpha = 0.4f))
                    .padding(11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    "Both stores live under app-private storage — no sync, no upload.",
                    color = colors.textMuted,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- Vault visual primitives ----

@Composable
private fun VaultHero(
    vaultName: String,
    fileCount: Int,
    vaultBytes: Long,
    usedGb: Double,
    totalGb: Double,
    onAdd: () -> Unit
) {
    val colors = LocalKarenColors.current
    val usedFraction = if (totalGb > 0.0) (usedGb / totalGb).toFloat().coerceIn(0f, 1f) else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
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
                Icon(Icons.Default.FolderSpecial, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "LOCAL KNOWLEDGE // ZERO CLOUD",
                    color = colors.accentGreen,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.05.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(vaultName, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${allFilesLabel(fileCount)} · ${formatSize(vaultBytes)} stored on device",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            VaultPill("SEALED", colors.accentGreen)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VaultHeroStat("FILES", "$fileCount", colors.accentGreen, Modifier.weight(1f))
            VaultHeroStat("VAULT", formatSize(vaultBytes), colors.accentBlue, Modifier.weight(1f))
            VaultHeroStat("DEVICE", "${String.format("%.0f", totalGb)} GB", colors.accentAmber, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "DEVICE STORAGE",
                color = colors.textMuted,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Text(
                "${String.format("%.1f", usedGb)} / ${String.format("%.0f", totalGb)} GB",
                color = colors.textSecondary,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.border.copy(alpha = 0.7f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(usedFraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.accentGreen)
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onAdd,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
            shape = RoundedCornerShape(999.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text("Add File", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun allFilesLabel(count: Int): String = "$count file${if (count == 1) "" else "s"}"

@Composable
private fun VaultHeroStat(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(colors.surfaceHover.copy(alpha = 0.45f))
            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(11.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Text(label, color = colors.textMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun VaultPill(text: String, tint: Color) {
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
            letterSpacing = 0.6.sp
        )
    }
}

@Composable
private fun VaultSectionLabel(
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

@Composable
private fun VaultEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    tint: Color
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.11f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun LatestVaultCard(file: File?, onOpen: () -> Unit) {
    val colors = LocalKarenColors.current
    val tint = file?.let { vaultTintFor(it.name, colors) } ?: colors.accentGreen
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(tint.copy(alpha = 0.08f), colors.cardBackground)
                )
            )
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        if (file == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("No documents yet", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Add files with Add File above.", color = colors.textMuted, fontSize = 11.5.sp)
                }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(tint.copy(alpha = 0.13f))
                    .border(1.dp, tint.copy(alpha = 0.22f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(vaultIconFor(file.name), contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "LATEST // ${vaultKindFor(file.name)}",
                        color = tint,
                        fontSize = 8.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    file.name,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            VaultPill("LOCAL", tint)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${formatSize(file.length())} · Modified ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))}",
            color = colors.textSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = Color.White),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                Text("Open", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun VaultFileRow(
    name: String,
    meta: String,
    icon: ImageVector,
    onOpen: () -> Unit = {},
    onShare: () -> Unit = {},
    onRename: () -> Unit = {},
    onDelete: (() -> Unit)? = null
) {
    val colors = LocalKarenColors.current
    val tint = vaultTintFor(name, colors)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable { onOpen() }
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.12f))
                .border(1.dp, tint.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = name, tint = tint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(tint.copy(alpha = 0.10f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        vaultKindFor(name),
                        color = tint,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(meta, color = colors.textMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        IconButton(onClick = onShare, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Share, contentDescription = "Share $name", tint = colors.textMuted, modifier = Modifier.size(15.dp))
        }
        IconButton(onClick = onRename, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "Rename $name", tint = colors.textMuted, modifier = Modifier.size(15.dp))
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete $name", tint = colors.accentRed.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
            }
        }
    }
}

private fun vaultTintFor(name: String, colors: KarenColors): Color = when (name.substringAfterLast('.', "").lowercase()) {
    "pdf" -> colors.accentRed
    "md", "txt" -> colors.accentBlue
    "kt", "rs", "java" -> colors.accentGreen
    "wav", "mp3", "m4a", "ogg" -> colors.accentAmber
    "jpg", "jpeg", "png", "webp", "gif" -> Color(0xFFA78BFA)
    "mp4", "mkv" -> Color(0xFFF472B6)
    "zip" -> Color(0xFF94A3B8)
    else -> colors.accentGreen
}

private fun vaultKindFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "" -> "FILE"
        "jpeg" -> "JPG"
        "txt" -> "TXT"
        "md" -> "MD"
        else -> ext.uppercase().take(4)
    }
}

private fun vaultIconFor(name: String): ImageVector = when (name.substringAfterLast('.', "").lowercase()) {
    "kt", "rs", "java" -> Icons.Default.Code
    "wav", "mp3", "m4a", "ogg" -> Icons.Default.Mic
    "gguf" -> Icons.Default.Memory
    "md", "txt", "pdf" -> Icons.Default.Description
    "jpg", "jpeg", "png", "webp", "gif" -> Icons.Default.Image
    "mp4", "mkv" -> Icons.Default.Movie
    "zip" -> Icons.Default.Archive
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}
