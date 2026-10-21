package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.rememberDeviceTelemetry

@Composable
fun HardwareScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()
    val ctx = androidx.compose.ui.platform.LocalContext.current

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Hardware & Governance",
            onMenuClick = onOpenDrawer,
            onBackClick = onNavigateToHome,
            moreActions = listOf(
                Triple("Refresh sensors", Icons.Default.Refresh) { android.widget.Toast.makeText(ctx, "Sensors refreshed", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Thermal report", Icons.Default.Whatshot) { android.widget.Toast.makeText(ctx, "Thermal report", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Battery stats", Icons.Default.BatteryChargingFull) { android.widget.Toast.makeText(ctx, "Battery stats", android.widget.Toast.LENGTH_SHORT).show() }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                HardwareHero(
                    networkUp = device.networkUp,
                    wifi = device.wifi,
                    patch = device.securityPatch.ifEmpty { "unknown" },
                    deviceLabel = "${device.deviceManufacturer} ${device.deviceModel}".trim()
                )
            }

            // Air-gap lockdown master card
            item {
                var isLockdownActive by remember { mutableStateOf(false) }
                LockdownCard(
                    active = isLockdownActive,
                    onToggle = { isLockdownActive = !isLockdownActive }
                )
            }

            // Sandbox & Tool Gates
            item {
                HardwareSectionLabel(
                    kicker = "Security gates & approvals",
                    icon = Icons.Default.Lock,
                    tint = colors.accentGreen,
                    trailing = "4 policies"
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GateItem("Calendar & Reminders", "Always Confirm", true)
                    HorizontalDivider(color = colors.border.copy(alpha = 0.65f), thickness = 0.5.dp)
                    GateItem("Documents RAG", "Local Vault / Auto-Approve", false)
                    HorizontalDivider(color = colors.border.copy(alpha = 0.65f), thickness = 0.5.dp)
                    GateItem("Shell & Code Execution", "Biometric Touch Required", true)
                    HorizontalDivider(color = colors.border.copy(alpha = 0.65f), thickness = 0.5.dp)
                    GateItem(
                        "Network Interface",
                        if (device.networkUp) (if (device.wifi) "Wi-Fi Active" else "Cellular Active") else "Hard Disabled · 0 KB sent",
                        !device.networkUp
                    )
                }
            }

            // Real device manifest
            item {
                HardwareSectionLabel(
                    kicker = "Device manifest",
                    icon = Icons.Default.PhoneAndroid,
                    tint = colors.accentBlue,
                    trailing = "${device.sdkInt} SDK"
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GateItem("Model", "${device.deviceManufacturer} ${device.deviceModel}", false)
                    HorizontalDivider(color = colors.border.copy(alpha = 0.65f), thickness = 0.5.dp)
                    GateItem("Android", "${device.androidVersion} (SDK ${device.sdkInt})", false)
                    HorizontalDivider(color = colors.border.copy(alpha = 0.65f), thickness = 0.5.dp)
                    GateItem("Security Patch", device.securityPatch.ifEmpty { "unknown" }, false)
                    HorizontalDivider(color = colors.border.copy(alpha = 0.65f), thickness = 0.5.dp)
                    GateItem("CPU", "${device.cpuCores} logical cores", false)
                    HorizontalDivider(color = colors.border.copy(alpha = 0.65f), thickness = 0.5.dp)
                    GateItem("Battery", "${device.batteryPercent}% · ${String.format("%.1f", device.batteryTempC)}°C", device.batteryTempC > 42f)
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.accentGreen.copy(alpha = 0.06f))
                        .border(1.dp, colors.accentGreen.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Policies are enforced on this device. Governance state never leaves the phone.",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

// ---- Hero ----

@Composable
private fun HardwareHero(
    networkUp: Boolean,
    wifi: Boolean,
    patch: String,
    deviceLabel: String
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.verticalGradient(
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
                Icon(Icons.Default.Shield, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "HARDWARE // GOVERNANCE",
                    color = colors.accentGreen,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                Spacer(Modifier.height(2.dp))
                Text("Sovereign device", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(deviceLabel.ifBlank { "This phone" }, color = colors.textMuted, fontSize = 11.sp, maxLines = 1)
            }
            HardwarePill(
                text = if (networkUp) "SURFACE ONLINE" else "AIR-GAPPED",
                tint = if (networkUp) colors.accentAmber else colors.accentGreen
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HardwareStat("NETWORK", if (networkUp) (if (wifi) "Wi-Fi" else "Cellular") else "Offline", if (networkUp) colors.accentAmber else colors.accentGreen, Modifier.weight(1f))
            HardwareStat("PATCH", patch, colors.accentBlue, Modifier.weight(1f))
            HardwareStat("POSTURE", if (networkUp) "Review" else "Hardened", if (networkUp) colors.accentAmber else colors.accentGreen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HardwareStat(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(colors.surfaceHover.copy(alpha = 0.45f))
            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(11.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Text(label, color = colors.textMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun HardwarePill(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text,
            color = tint,
            fontSize = 7.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp,
            maxLines = 1
        )
    }
}

// ---- Master lockdown ----

@Composable
private fun LockdownCard(active: Boolean, onToggle: () -> Unit) {
    val colors = LocalKarenColors.current
    val tint = if (active) colors.accentGreen else colors.accentRed
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        tint.copy(alpha = 0.10f),
                        colors.cardBackground
                    )
                )
            )
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.15f))
                    .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (active) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "AIR-GAP LOCKDOWN",
                    color = tint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                Text("Sovereign engine", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            HardwarePill(
                text = if (active) "ENGAGED" else "STANDBY",
                tint = tint
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Hardware-enforced zero cloud egress. All network syscalls (AF_INET/AF_INET6) dropped at kernel boundary via seccomp filter.",
            color = colors.textMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HardwareChip("AF_INET", tint)
            HardwareChip("AF_INET6", tint)
            HardwareChip("SECCOMP", tint)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) colors.accentGreen else colors.accentRed,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(
                if (active) Icons.Default.CheckCircle else Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (active) "Master Lockdown Engaged (Zero Egress)" else "Engage Master Lockdown",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun HardwareChip(text: String, tint: Color) {
    val colors = LocalKarenColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = tint,
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

// ---- Section label ----

@Composable
private fun HardwareSectionLabel(
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
            Text(
                trailing,
                color = colors.textMuted,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ---- Gate / manifest rows ----

@Composable
private fun GateItem(label: String, policy: String, isStrict: Boolean) {
    val colors = LocalKarenColors.current
    val visual = when (label) {
        "Calendar & Reminders" -> Icons.Default.CalendarToday to colors.accentAmber
        "Documents RAG" -> Icons.AutoMirrored.Filled.MenuBook to colors.accentBlue
        "Shell & Code Execution" -> Icons.Default.Terminal to colors.accentGreen
        "Network Interface" -> Icons.Default.Wifi to colors.accentBlue
        "Model" -> Icons.Default.Memory to colors.accentBlue
        "Android" -> Icons.Default.PhoneAndroid to colors.accentGreen
        "Security Patch" -> Icons.Default.Security to colors.accentGreen
        "CPU" -> Icons.Default.DeveloperBoard to colors.accentAmber
        "Battery" -> Icons.Default.BatteryChargingFull to if (isStrict) colors.accentRed else colors.accentGreen
        else -> Icons.Default.Tune to colors.textMuted
    }
    val tint = if (isStrict && label != "Battery") colors.accentGreen else visual.second
    val isManifest = label in setOf("Model", "Android", "Security Patch", "CPU", "Battery")
    val hotBattery = label == "Battery" && isStrict
    val statusText = when {
        hotBattery -> "HOT"
        isManifest -> "INFO"
        isStrict -> "STRICT"
        else -> "OPEN"
    }
    val statusTint = when {
        hotBattery -> colors.accentRed
        isManifest -> colors.textMuted
        isStrict -> tint
        else -> colors.textMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(visual.second.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(visual.first, contentDescription = null, tint = visual.second, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(label, color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Text(
                    policy,
                    color = colors.textMuted,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(if (isStrict || isManifest) statusTint.copy(alpha = 0.12f) else colors.surfaceHover)
                .border(
                    1.dp,
                    if (isStrict || isManifest) statusTint.copy(alpha = 0.4f) else colors.border,
                    RoundedCornerShape(999.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                statusText,
                color = if (isStrict || isManifest) statusTint else colors.textMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
