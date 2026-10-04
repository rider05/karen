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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.rememberDeviceTelemetry

data class GgufModel(
    val name: String,
    val size: String,
    val ramRequired: String,
    val throughput: String,
    val quant: String
)

@Composable
fun ModelManagerScreen(
    onOpenDrawer: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()

    val models = remember {
        listOf(
            GgufModel("Karen 4B Instruct", "2.72 GB", "3.8 GB", "18.4 tok/s", "Q4_K_M"),
            GgufModel("Karen 2B Lightweight", "1.48 GB", "2.1 GB", "28.6 tok/s", "Q4_K_S"),
            GgufModel("Karen 9B Reasoning", "5.64 GB", "7.2 GB", "9.2 tok/s", "Q4_K_M"),
            GgufModel("Whisper Medium + Piper TTS", "380 MB", "540 MB", "110ms TTFS", "INT8")
        )
    }

    var activeModel by remember { mutableStateOf(models[0]) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Local Models (GGUF)",
            onMenuClick = onOpenDrawer
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(activeModel.name, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                        "File-backed GGUF runtime (${activeModel.quant}) · RAM ${activeModel.ramRequired} resident · ${device.cpuCores} threads · Vulkan / ARM NEON acceleration.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Weight: ${activeModel.size}", color = colors.textMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        Text("Speed: ${activeModel.throughput}", color = colors.accentGreen, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Installed GGUF Models
            item {
                Text("Installed GGUF Weights (${models.size})", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
            }

            items(models) { m ->
                val isActive = m.name == activeModel.name
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
        }
    }
}
