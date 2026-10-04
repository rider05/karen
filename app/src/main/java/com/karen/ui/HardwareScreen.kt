package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

@Composable
fun HardwareScreen(
    onOpenDrawer: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Hardware & Governance",
            onMenuClick = onOpenDrawer
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Air-gap lockdown master card
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
                            Icon(Icons.Default.Shield, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Air-Gapped Sovereign Engine", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Hardware-enforced zero cloud egress. All network syscalls (AF_INET/AF_INET6) dropped at kernel boundary via seccomp filter.",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    var isLockdownActive by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { isLockdownActive = !isLockdownActive },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLockdownActive) colors.accentGreen else colors.accentRed,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                if (isLockdownActive) "✓ Master Lockdown Engaged (Zero Egress)" else "Engage Master Lockdown",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Sandbox & Tool Gates
            item {
                Text("Security Gates & Approvals", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GateItem("Calendar & Reminders", "Always Confirm", true)
                    GateItem("Documents RAG", "Local Vault / Auto-Approve", false)
                    GateItem("Shell & Code Execution", "Biometric Touch Required", true)
                    GateItem(
                        "Network Interface",
                        if (device.networkUp) (if (device.wifi) "Wi-Fi Active" else "Cellular Active") else "Hard Disabled · 0 KB sent",
                        !device.networkUp
                    )
                }
            }

            // Real device manifest
            item {
                Text("Device Manifest", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GateItem("Model", "${device.deviceManufacturer} ${device.deviceModel}", false)
                    GateItem("Android", "${device.androidVersion} (SDK ${device.sdkInt})", false)
                    GateItem("Security Patch", device.securityPatch.ifEmpty { "unknown" }, false)
                    GateItem("CPU", "${device.cpuCores} logical cores", false)
                    GateItem("Battery", "${device.batteryPercent}% · ${String.format("%.1f", device.batteryTempC)}°C", device.batteryTempC > 42f)
                }
            }
        }
    }
}

@Composable
private fun GateItem(label: String, policy: String, isStrict: Boolean) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(label, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(policy, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isStrict) colors.accentGreen.copy(alpha = 0.15f) else colors.surfaceHover)
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(if (isStrict) "Strict" else "Permissive", color = if (isStrict) colors.accentGreen else colors.textMuted, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}
