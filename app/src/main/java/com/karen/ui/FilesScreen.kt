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
import com.karen.rememberDeviceTelemetry
import com.karen.sandboxFiles
import com.karen.formatSize

@Composable
fun FilesScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigate: (String) -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedVault by remember { mutableStateOf("Knowledge Vault") }
    var showVaultSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = selectedVault,
            onMenuClick = onOpenDrawer,
            onModelClick = { showVaultSheet = true },
            moreActions = listOf(
                Triple("Index all", Icons.Default.DoneAll) { android.widget.Toast.makeText(context, "Indexing all", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Refresh", Icons.Default.Refresh) { android.widget.Toast.makeText(context, "Refresh", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Open in Study Book", Icons.Default.MenuBook) { onNavigate("StudyBook") }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 0.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header stats card
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sovereign Storage", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("${String.format("%.1f", device.storageTotalGb - device.storageFreeGb)} / ${String.format("%.0f", device.storageTotalGb)} GB", color = colors.accentGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "All documents embedded locally into sqlite-vec with 768-D vectors. Zero network egress.",
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Index File", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Top Match / Active RAG Item
            item {
                Text("Active RAG Document", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.cardBackground)
                        .border(1.dp, colors.accentGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(sandboxFiles(context).firstOrNull()?.name ?: "No documents yet", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sandboxFiles(context).firstOrNull()?.let { "${formatSize(it.length())} · Modified ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified()))}" } ?: "Add files to the vault to enable on-device RAG.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onNavigate("StudyBook") },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Open in Study Book", fontSize = 11.5.sp)
                        }
                    }
                }
            }

            // File items list (Secure Vault view)
            if (selectedVault == "Secure Vault") item {
                Text("Indexed Vault Items (${sandboxFiles(context).size})", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val files = sandboxFiles(context)
                    if (files.isEmpty()) {
                        Text("No indexed files yet", color = colors.textMuted, fontSize = 12.sp)
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
                            }
                        )
                    }
                }
            }

            // Knowledge Vault section (mirrors Sovereign Storage, for knowledge assets)
            if (selectedVault == "Knowledge Vault") item {
                Text("Knowledge Vault", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val kvFiles = sandboxFiles(context).filter { it.extension.lowercase() in listOf("pdf", "md", "txt") }
                    if (kvFiles.isEmpty()) {
                        Text("Drop PDFs/notes into the vault to build your knowledge base", color = colors.textMuted, fontSize = 12.sp)
                    }
                    kvFiles.forEach { f ->
                        VaultFileRow(
                            f.name,
                            "${formatSize(f.length())} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(f.lastModified()))}",
                            Icons.Default.MenuBook
                        )
                    }
                }
            }
        }

        if (showVaultSheet) {
            VaultSelectorSheet(
                selectedVault = selectedVault,
                onSelectVault = { selectedVault = it },
                onDismiss = { showVaultSheet = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultSelectorSheet(
    selectedVault: String,
    onSelectVault: (String) -> Unit,
    onDismiss: () -> Unit
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
            Text("Vault Selector", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                "Switch between vault views — one secure screen, two logical stores",
                color = colors.textMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
            )
            val vaults = listOf(
                Triple("Knowledge Vault", "PDFs, notes & markdown embedded for on-device RAG", "Active"),
                Triple("Secure Vault", "All indexed files in sovereign sandboxed storage", "Ready")
            )
            vaults.forEach { (name, desc, badge) ->
                val isSelected = selectedVault == name
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) colors.surfaceHover else Color.Transparent)
                        .border(1.dp, if (isSelected) colors.accentGreen else colors.border, RoundedCornerShape(12.dp))
                        .clickable {
                            onSelectVault(name)
                            onDismiss()
                        }
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = colors.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) colors.accentGreen.copy(alpha = 0.2f) else colors.border.copy(alpha = 0.4f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(badge, color = if (isSelected) colors.accentGreen else colors.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(desc, color = colors.textMuted, fontSize = 11.5.sp)
                    }
                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            onSelectVault(name)
                            onDismiss()
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = colors.accentGreen, unselectedColor = colors.textMuted)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VaultFileRow(name: String, meta: String, icon: ImageVector) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = name, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(meta, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = colors.textMuted, modifier = Modifier.size(18.dp))
    }
}
