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
fun PerformanceScreen(
    onOpenDrawer: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        ChatGPTTopAppBar(
            selectedModel = "Performance & TTFT",
            onMenuClick = onOpenDrawer,
            moreActions = listOf(
                Triple("Run benchmark", Icons.Default.Speed) { android.widget.Toast.makeText(ctx, "Run benchmark", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Copy metrics", Icons.Default.Share) { android.widget.Toast.makeText(ctx, "Copy metrics", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Clear log", Icons.Default.Delete) { android.widget.Toast.makeText(ctx, "Clear log", android.widget.Toast.LENGTH_SHORT).show() }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 0.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Live TTFT & Throughput Snapshot
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text("Device Telemetry", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricCard(
                            label = "Battery Level",
                            value = "${device.batteryPercent}%",
                            sub = if (device.batteryCharging) "charging" else "discharging",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        MetricCard(
                            label = "Battery Temp",
                            value = String.format("%.1f°C", device.batteryTempC),
                            sub = if (device.batteryTempC > 42f) "warm" else "nominal",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricCard(
                            label = "Unified RAM",
                            value = String.format("%.1f / %.0f GB", device.totalRamGb - device.freeRamGb, device.totalRamGb),
                            sub = "${device.usedRamPercent}% capacity",
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        MetricCard(
                            label = "Free RAM",
                            value = String.format("%.1f GB", device.freeRamGb),
                            sub = "low-memory ${if (device.freeRamGb < 1.0) "yes" else "no"} · uptime ${device.uptimeMinutes}m",
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Power & Governor Controls
            item {
                Text("Governor & Hardware Safety", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GovernorRow("CPU Cores", "${device.cpuCores} available")
                    GovernorRow("Thermal Headroom", if (device.thermalHeadroom >= 0f) String.format("%.0f%%", device.thermalHeadroom * 100f) else "n/a")
                    GovernorRow("Network", if (device.networkUp) (if (device.wifi) "Wi-Fi up" else "Cellular up") else "Offline · 0 egress")
                    GovernorRow("Internal Storage", String.format("%.1f / %.0f GB free", device.storageFreeGb, device.storageTotalGb))
                }
            }

            // Benchmark Card
            item {
                    var benchmarkResult by remember { mutableStateOf<String?>(null) }
                    var benchmarkRunning by remember { mutableStateOf(false) }
                    Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("On-Device LLM Benchmark", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Button(
                            onClick = {
                                benchmarkRunning = true
                                benchmarkResult = "No data found — no local model loaded"
                                benchmarkRunning = false
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(if (benchmarkRunning) "Running..." else "Run Benchmark", fontSize = 11.5.sp)
                        }
                    }
                    if (benchmarkResult != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(benchmarkResult!!, color = colors.accentGreen, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Throughput history (tok/s)", color = colors.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    val history = emptyList<Float>()
                    if (history.isEmpty()) {
                        Text("No data found", color = colors.textMuted, fontSize = 12.sp)
                    } else Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        history.forEachIndexed { i, v ->
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height((v / 20f * 44f).dp)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(if (i == history.lastIndex) colors.accentGreen else colors.accentGreen.copy(alpha = 0.35f))
                                )
                                Text(String.format("%.0f", v), color = colors.textMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceHover)
            .padding(10.dp)
    ) {
        Text(label, color = colors.textMuted, fontSize = 10.5.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = colors.accentGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(sub, color = colors.textMuted, fontSize = 10.sp)
    }
}

@Composable
private fun GovernorRow(label: String, value: String) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = colors.textSecondary, fontSize = 12.5.sp)
        Text(value, color = colors.textPrimary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
    }
}
