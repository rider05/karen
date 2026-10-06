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
import androidx.compose.runtime.Composable
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

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()
    val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    val greeting = when {
        hourOfDay < 12 -> "Good morning"
        hourOfDay < 18 -> "Good afternoon"
        else -> "Good evening"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Greeting Header
        item {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.accentGreen.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Air-Gapped",
                                    tint = colors.accentGreen,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "Air-Gapped Sovereign",
                                    color = colors.accentGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (device.networkUp) "Network Up" else "0 KB Egress", color = colors.textMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = colors.textPrimary)
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "$greeting, ${UserPrefs.name(androidx.compose.ui.platform.LocalContext.current)}",
                    color = colors.textPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "What would you like to build or synthesize today?",
                    color = colors.textMuted,
                    fontSize = 14.sp
                )
            }
        }

        // Quick Prompt Shortcut Grid (2x2)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PromptShortcutCard(
                        title = "Ask Karen",
                        subtitle = "Type or paste anything",
                        icon = Icons.Default.ChatBubble,
                        iconTint = colors.accentBlue,
                        modifier = Modifier.weight(1f)
                    ) { onNavigate("Chat") }

                    PromptShortcutCard(
                        title = "Voice Chat",
                        subtitle = "Hands-free AI",
                        icon = Icons.Default.GraphicEq,
                        iconTint = colors.accentGreen,
                        modifier = Modifier.weight(1f)
                    ) { onNavigate("Voice") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PromptShortcutCard(
                        title = "Knowledge Vault",
                        subtitle = "Your files & notes",
                        icon = Icons.Default.Folder,
                        iconTint = colors.accentAmber,
                        modifier = Modifier.weight(1f)
                    ) { onNavigate("Files") }

                    PromptShortcutCard(
                        title = "Canvas",
                        subtitle = "Write & plan",
                        icon = Icons.Default.Terminal,
                        iconTint = Color(0xFFEC4899),
                        modifier = Modifier.weight(1f)
                    ) { onNavigate("Workspace") }
                }
            }
        }

        // Live Execution Task Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.cardBackground)
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.accentGreen)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Memory Pressure",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text("${device.usedRamPercent}% RAM", color = colors.accentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    "No active task",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "No data found",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Spacer(Modifier.height(10.dp))
                // Progress bar
                LinearProgressIndicator(
                    progress = { device.usedRamPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onNavigate("Workspace") },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentGreen,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text("Resume in Canvas", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Recent Knowledge & Vault
        item {
            Text(
                "Recent Knowledge & Vault",
                color = colors.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No data found", color = colors.textMuted, fontSize = 12.5.sp)
            }
        }

        // System Telemetry Snapshot
        item {
            Text(
                "System Diagnostics",
                color = colors.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TelemetryCol(label = "RAM Free", value = String.format("%.1f / %.0f GB", device.freeRamGb, device.totalRamGb))
                TelemetryCol(label = "Battery", value = "${device.batteryPercent}% · ${String.format("%.1f", device.batteryTempC)}°C")
                TelemetryCol(label = "Storage Free", value = String.format("%.1f GB", device.storageFreeGb))
            }
        }
    }
}

@Composable
private fun PromptShortcutCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceHover),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = title, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = colors.textMuted, fontSize = 11.5.sp)
    }
}

@Composable
private fun RecentDocRow(
    title: String,
    sub: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = title, tint = colors.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = colors.textMuted, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = "Open", tint = colors.textMuted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun TelemetryCol(label: String, value: String) {
    val colors = LocalKarenColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = colors.textMuted, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = colors.textPrimary, fontSize = 13.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}
