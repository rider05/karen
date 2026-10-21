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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.rememberDeviceTelemetry
import kotlinx.coroutines.launch

@Composable
fun PerformanceScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome)

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var benchmarkRunning by remember { mutableStateOf(false) }
    var benchmarkResult by remember { mutableStateOf<String?>(null) }
    // Real samples measured on this device this session (tok/s, newest last).
    var throughputHistory by remember { mutableStateOf(listOf<Float>()) }

    /** Runs a real short generation on the pinned/first installed GGUF. */
    fun runBenchmark() {
        if (benchmarkRunning) return
        val pinned = UserPrefs.selectedModel(ctx)
            .takeIf { it.isNotBlank() && !isCloudProviderName(it) && isSelectableModel(ctx, it) }
        val name = pinned ?: UserPrefs.models(ctx).firstOrNull { !isCloudProviderName(it) && !isNonChatModel(it) }
        if (name == null) {
            benchmarkResult = "No local model on device — download one in Model Manager first."
            return
        }
        val file = ModelDownloader.weightFileFor(ctx, name)
        if (file == null || !ModelDownloader.isRunnableWeight(ctx, name)) {
            benchmarkResult = "“$name” has no runnable GGUF file on disk."
            return
        }
        benchmarkRunning = true
        benchmarkResult = "Loading $name…"
        scope.launch {
            val measured = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val window = UserPrefs.contextTokens(ctx)
                    val threads = Runtime.getRuntime().availableProcessors().let {
                        (it - 1).coerceIn(2, 8)
                    }.let { minOf(it, device.cpuCores.coerceAtLeast(2)) }
                    if (!KarenLlama.ensureLoaded(file, name, window, threads)) return@withContext null
                    var pieces = 0
                    var firstPieceMs = 0L
                    val t0 = System.currentTimeMillis()
                    val reply = KarenLlama.completeStream(
                        "You are a benchmark. Answer in one short sentence.",
                        arrayOf("user"),
                        arrayOf("Name three colours."),
                        64
                    ) { _ ->
                        pieces++
                        if (pieces == 1) firstPieceMs = System.currentTimeMillis() - t0
                    }
                    val totalMs = (System.currentTimeMillis() - t0).coerceAtLeast(1L)
                    BenchOut(reply.trim(), pieces, firstPieceMs, totalMs)
                } catch (_: Exception) {
                    null
                }
            }
            benchmarkRunning = false
            if (measured == null) {
                benchmarkResult = "Benchmark failed — the model could not load or generate."
                return@launch
            }
            if (measured.pieces <= 0) {
                benchmarkResult = "Model produced no tokens — try a bigger context in Settings."
                return@launch
            }
            val tps = measured.pieces * 1000.0 / measured.totalMs
            throughputHistory = (throughputHistory + tps.toFloat()).takeLast(12)
            benchmarkResult = "%s · %.1f tok/s (%.1f s) · TTFT %d ms — “%s”".format(
                name,
                tps,
                measured.totalMs / 1000.0,
                measured.firstPieceMs,
                measured.reply.take(48)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Performance & TTFT",
            onMenuClick = onOpenDrawer,
            onBackClick = onNavigateToHome,
            moreActions = listOf(
                Triple("Run benchmark", Icons.Default.Speed) { runBenchmark() },
                Triple("Copy metrics", Icons.Default.Share) {
                    val text = buildString {
                        append("Karen performance — ${device.deviceModel}\n")
                        append("Battery: ${device.batteryPercent}% · ${String.format("%.1f", device.batteryTempC)}°C\n")
                        append("RAM: ${String.format("%.1f / %.0f GB", device.totalRamGb - device.freeRamGb, device.totalRamGb)}\n")
                        benchmarkResult?.let { append("Benchmark: $it\n") }
                        if (throughputHistory.isNotEmpty()) {
                            append("tok/s history: " + throughputHistory.joinToString(", ") { "%.1f".format(it) })
                        }
                    }
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    android.widget.Toast.makeText(ctx, "Metrics copied", android.widget.Toast.LENGTH_SHORT).show()
                },
                Triple("Clear history", Icons.Default.Delete) {
                    throughputHistory = emptyList()
                    benchmarkResult = null
                }
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
                PerformanceHero(
                    deviceModel = device.deviceModel,
                    latestTps = throughputHistory.lastOrNull(),
                    thermalHeadroom = device.thermalHeadroom,
                    batteryPercent = device.batteryPercent,
                    networkUp = device.networkUp,
                    samples = throughputHistory.size
                )
            }

            // Live TTFT & Throughput Snapshot
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PerformanceIconTile(Icons.Default.MonitorHeart, colors.accentBlue)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "DEVICE TELEMETRY",
                                color = colors.accentBlue,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text("Live snapshot", color = colors.textPrimary, fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                        }
                        PerformancePill("LIVE", colors.accentGreen)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricCard(
                            label = "Battery Level",
                            value = "${device.batteryPercent}%",
                            sub = if (device.batteryCharging) "charging" else "discharging",
                            icon = if (device.batteryCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            tint = colors.accentGreen,
                            progress = (device.batteryPercent / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            label = "Battery Temp",
                            value = String.format("%.1f°C", device.batteryTempC),
                            sub = if (device.batteryTempC > 42f) "warm" else "nominal",
                            icon = Icons.Default.Thermostat,
                            tint = if (device.batteryTempC > 42f) colors.accentRed else colors.accentAmber,
                            progress = (device.batteryTempC / 60f).coerceIn(0f, 1f),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricCard(
                            label = "Unified RAM",
                            value = String.format("%.1f / %.0f GB", device.totalRamGb - device.freeRamGb, device.totalRamGb),
                            sub = "${device.usedRamPercent}% capacity",
                            icon = Icons.Default.Memory,
                            tint = colors.accentBlue,
                            progress = (device.usedRamPercent / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.weight(1f)
                        )
                        MetricCard(
                            label = "Free RAM",
                            value = String.format("%.1f GB", device.freeRamGb),
                            sub = "low-mem ${if (device.freeRamGb < 1.0) "yes" else "no"} · up ${device.uptimeMinutes}m",
                            icon = Icons.Default.Storage,
                            tint = colors.accentGreen,
                            progress = if (device.totalRamGb > 0.0) {
                                (device.freeRamGb / device.totalRamGb).coerceIn(0.0, 1.0).toFloat()
                            } else 0f,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Power & Governor Controls
            item {
                PerformanceSectionLabel(
                    kicker = "Governor & hardware safety",
                    icon = Icons.Default.Tune,
                    tint = colors.accentAmber,
                    trailing = "${device.cpuCores} cores"
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.verticalGradient(listOf(colors.surface, colors.cardBackground))
                        )
                        .border(1.dp, colors.accentGreen.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PerformanceIconTile(Icons.Default.Speed, colors.accentGreen)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "BENCHMARK // ON-DEVICE",
                                color = colors.accentGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text("On-Device LLM Benchmark", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = { runBenchmark() },
                            enabled = !benchmarkRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (benchmarkRunning) "Running…" else "Run", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        "Runs a real short generation on the pinned model (else the first installed GGUF) and measures tok/s and TTFT.",
                        color = colors.textMuted,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    if (benchmarkResult != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            benchmarkResult!!,
                            color = colors.accentGreen,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(colors.accentGreen.copy(alpha = 0.08f))
                                .border(1.dp, colors.accentGreen.copy(alpha = 0.2f), RoundedCornerShape(9.dp))
                                .padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "THROUGHPUT HISTORY",
                            color = colors.textMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.weight(1f)
                        )
                        PerformancePill(
                            if (throughputHistory.isEmpty()) "NO SAMPLES" else "${throughputHistory.size} SAMPLES",
                            if (throughputHistory.isEmpty()) colors.textMuted else colors.accentBlue
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (throughputHistory.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceHover.copy(alpha = 0.35f))
                                .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ShowChart, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "No samples yet — run the benchmark to record this device's real speed.",
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                    } else {
                        Column {
                            Text(
                                "tok/s · ${String.format("%.1f", throughputHistory.maxOrNull() ?: 0f)} peak",
                                color = colors.textMuted,
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(62.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.Start),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                val maxV = (throughputHistory.maxOrNull() ?: 1f).coerceAtLeast(0.1f)
                                throughputHistory.forEachIndexed { i, v ->
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(((v / maxV) * 44f).coerceAtLeast(3f).dp)
                                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                                .background(
                                                    if (i == throughputHistory.lastIndex) colors.accentBlue
                                                    else colors.accentBlue.copy(alpha = 0.28f)
                                                )
                                        )
                                        Spacer(Modifier.height(3.dp))
                                        Text(String.format("%.0f", v), color = colors.textMuted, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Hero ----

@Composable
private fun PerformanceHero(
    deviceModel: String,
    latestTps: Float?,
    thermalHeadroom: Float,
    batteryPercent: Int,
    networkUp: Boolean,
    samples: Int
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(colors.surface, colors.cardBackground)))
            .border(1.dp, colors.accentBlue.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(colors.accentBlue.copy(alpha = 0.13f))
                    .border(1.dp, colors.accentBlue.copy(alpha = 0.28f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = colors.accentBlue, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "PERFORMANCE // TTFT",
                    color = colors.accentBlue,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp
                )
                Spacer(Modifier.height(2.dp))
                Text("Device headroom", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(deviceModel.ifBlank { "This phone" }, color = colors.textMuted, fontSize = 11.sp, maxLines = 1)
            }
            PerformancePill(
                text = if (networkUp) "NETWORK UP" else "OFFLINE",
                tint = if (networkUp) colors.accentAmber else colors.accentGreen
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PerformanceStat(
                label = "THROUGHPUT",
                value = latestTps?.let { String.format("%.1f", it) } ?: "—",
                unit = "tok/s",
                tint = colors.accentGreen,
                modifier = Modifier.weight(1f)
            )
            PerformanceStat(
                label = "THERMAL",
                value = if (thermalHeadroom >= 0f) String.format("%.0f", thermalHeadroom * 100f) else "—",
                unit = "% headroom",
                tint = if (thermalHeadroom in 0f..0.2f) colors.accentAmber else colors.accentBlue,
                modifier = Modifier.weight(1f)
            )
            PerformanceStat(
                label = "BATTERY",
                value = "$batteryPercent",
                unit = "% · $samples runs",
                tint = colors.accentGreen,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PerformanceStat(
    label: String,
    value: String,
    unit: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(colors.surfaceHover.copy(alpha = 0.45f))
            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(11.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Text(label, color = colors.textMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = tint, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(unit, color = colors.textMuted, fontSize = 8.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun PerformanceIconTile(icon: ImageVector, tint: Color, size: Int = 34) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.24f), RoundedCornerShape((size / 3).dp)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((size * 0.48f).dp))
    }
}

@Composable
private fun PerformancePill(text: String, tint: Color) {
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
            letterSpacing = 0.6.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun PerformanceSectionLabel(
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
private fun MetricCard(
    label: String,
    value: String,
    sub: String,
    icon: ImageVector,
    tint: Color,
    progress: Float,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surfaceHover.copy(alpha = 0.5f))
            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(13.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(tint.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(label, color = colors.textMuted, fontSize = 9.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
        Text(sub, color = colors.textMuted, fontSize = 9.sp, maxLines = 1)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(colors.border.copy(alpha = 0.6f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(tint)
            )
        }
    }
}

@Composable
private fun GovernorRow(label: String, value: String) {
    val colors = LocalKarenColors.current
    val visual = when (label) {
        "CPU Cores" -> Icons.Default.DeveloperBoard to colors.accentBlue
        "Thermal Headroom" -> Icons.Default.Thermostat to colors.accentAmber
        "Network" -> Icons.Default.Wifi to colors.accentGreen
        "Internal Storage" -> Icons.Default.Storage to colors.accentBlue
        else -> Icons.Default.Tune to colors.textMuted
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(visual.second.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(visual.first, contentDescription = null, tint = visual.second, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = colors.textSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(colors.surfaceHover.copy(alpha = 0.7f))
                .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                value,
                color = colors.textPrimary,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

/** One measured benchmark run (real numbers only). */
private data class BenchOut(
    val reply: String,
    val pieces: Int,
    val firstPieceMs: Long,
    val totalMs: Long
)
