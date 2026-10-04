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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Knowledge Vault",
            onMenuClick = onOpenDrawer
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp),
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

            // File items list
            item {
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
