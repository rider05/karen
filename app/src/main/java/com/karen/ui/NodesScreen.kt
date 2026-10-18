package com.karen.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Network Nodes — this phone (the master) plus every device that ever talked
 * to its local server, live. Devices that joined as GPU workers get a fuller
 * readout: state, loaded model, GPU and measured compute.
 */
@Composable
fun NodesScreen(onNavigateToHome: () -> Unit = {}) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val nodes = LocalApiServer.nodeSnapshot

    // Bumps every 5 s so the relative times and offline windows stay honest.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            tick++
        }
    }
    val now = remember(tick) { System.currentTimeMillis() }

    val workers = nodes.filter { it.worker }
    val clients = nodes.filter { !it.worker }
    val workersOnline = workers.count { now - it.lastSeen < WorkerInfo.ONLINE_WINDOW_MS }

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) { false }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Network Nodes",
            onBackClick = onNavigateToHome,
            moreActions = listOf(
                Triple("Clear node history", Icons.Default.Delete) {
                    LocalApiServer.clearNodes()
                    Toast.makeText(context, "Node history cleared", Toast.LENGTH_SHORT).show()
                }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                MasterPanel(
                    workersOnline = workersOnline,
                    workersTotal = workers.size,
                    nodesTotal = nodes.size
                )
            }

            if (nodes.isEmpty()) {
                item { EmptyPanel() }
            }

            if (workers.isNotEmpty()) {
                item { KSectionLabel("GPU WORKERS · ${workers.size}") }
                items(workers, key = { "worker:" + it.ip }) { node ->
                    WorkerPanel(node = node, now = now)
                }
            }

            if (clients.isNotEmpty()) {
                item { KSectionLabel("DEVICES · ${clients.size}") }
                items(clients, key = { "device:" + it.ip }) { node ->
                    DevicePanel(node = node, now = now)
                }
            }

            item { FooterNote() }
        }
    }
}

/**
 * The phone itself: server state, the two addresses other devices use, and
 * live counters. Tapping an address copies it (LAN row turns green only when
 * it is actually reachable from the network).
 */
@Composable
private fun MasterPanel(workersOnline: Int, workersTotal: Int, nodesTotal: Int) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val running = LocalApiServer.running
    val lanOn = UserPrefs.serverLan(context)
    val lanUrl = LocalApiServer.lanUrl(context)
    val localUrl = "http://127.0.0.1:${UserPrefs.serverPort(context)}"

    KPanel(
        title = "This phone",
        subtitle = "${Build.MODEL.ifBlank { "Karen" }} · master",
        icon = { StatusDot(if (running) colors.accentGreen else colors.textMuted, 9.dp) },
        trailing = {
            KStatusChip(
                label = if (running) "ONLINE" else "OFFLINE",
                tone = if (running) KTone.Success else KTone.Muted,
                led = true
            )
        }
    ) {
        UrlRow("LAN", lanUrl, highlight = running && lanOn)
        UrlRow("Local", localUrl, highlight = running)

        val hint = when {
            !running -> "Server is stopped — start it in Settings › Local Server so devices can connect."
            !lanOn -> "LAN is off — enable \"Expose on local network\" in Settings so other devices can connect."
            else -> null
        }
        if (hint != null) {
            Spacer(Modifier.height(6.dp))
            Text(hint, color = colors.accentAmber, fontSize = 11.sp, lineHeight = 15.sp)
        }

        Spacer(Modifier.height(10.dp))
        KHairline()
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            KMetric(
                "Requests",
                "${LocalApiServer.requestCount}",
                Modifier.weight(1f),
                caption = "this run"
            )
            KMetric(
                "Workers",
                if (workersTotal == 0) "0" else "$workersOnline/$workersTotal",
                Modifier.weight(1f),
                tone = if (workersOnline > 0) KTone.Success else KTone.Muted,
                caption = if (workersTotal == 0) "none joined" else "online"
            )
            KMetric(
                "Devices",
                "$nodesTotal",
                Modifier.weight(1f),
                caption = "seen"
            )
        }
    }
}

/** One joined GPU worker: state pill, loaded model, GPU, compute, traffic. */
@Composable
private fun WorkerPanel(node: NodeInfo, now: Long) {
    val colors = LocalKarenColors.current
    val online = now - node.lastSeen < WorkerInfo.ONLINE_WINDOW_MS
    val state = node.workerState.lowercase(Locale.US)
    val hasGpu = node.gpu.isNotBlank() || node.webgpu || node.gflops > 0.0

    val tone = when {
        !online -> KTone.Muted
        state == "running" -> KTone.Primary
        state == "loading" || state == "assigned" -> KTone.Amber
        else -> KTone.Success
    }
    val label = if (!online) "OFFLINE" else state.ifBlank { "idle" }.uppercase(Locale.US)

    KPanel(
        title = node.name.ifBlank { "Worker " + node.ip.substringAfterLast('.') },
        subtitle = node.ip,
        icon = { StatusDot(if (online) colors.accentGreen else colors.textMuted, 9.dp) },
        trailing = { KStatusChip(label = label, tone = tone, led = true) }
    ) {
        KTelemetryRow(
            "Model",
            node.workerModel.ifBlank { "not loaded" },
            valueColor = if (node.workerModel.isNotBlank()) colors.accentGreen else colors.textMuted
        )
        KTelemetryRow(
            "GPU",
            node.gpu.ifBlank { if (node.webgpu) "WebGPU ready" else "not reported" },
            valueColor = if (hasGpu) colors.accentBlue else colors.textMuted
        )
        if (node.gflops > 0.0) {
            KTelemetryRow(
                "Compute",
                String.format(Locale.US, "%.0f GFLOPS", node.gflops),
                valueColor = colors.accentBlue
            )
        }
        KTelemetryRow("Requests", "${node.requests}", valueColor = colors.textSecondary)
        KTelemetryRow(
            "Last seen",
            relTime(now - node.lastSeen),
            valueColor = if (online) colors.accentGreen else colors.textMuted
        )
    }
}

/** Any other device that hit the server: dashboard pages and API callers. */
@Composable
private fun DevicePanel(node: NodeInfo, now: Long) {
    val colors = LocalKarenColors.current
    val active = now - node.lastSeen < 60_000
    val hasGpu = node.gpu.isNotBlank() || node.webgpu || node.gflops > 0.0

    KPanel(
        title = node.name.ifBlank { "Device " + node.ip.substringAfterLast('.') },
        subtitle = node.ip,
        icon = { StatusDot(if (active) colors.accentGreen else colors.textMuted, 9.dp) },
        trailing = {
            KStatusChip(
                label = node.kind.ifBlank { "Device" }.uppercase(Locale.US),
                tone = if (node.kind.equals("Dashboard", ignoreCase = true)) KTone.Blue else KTone.Amber
            )
        }
    ) {
        if (hasGpu) {
            KTelemetryRow(
                "GPU",
                buildString {
                    append(node.gpu.ifBlank { "WebGPU ready" })
                    if (node.gflops > 0.0) {
                        append(String.format(Locale.US, " · %.0f GFLOPS", node.gflops))
                    }
                },
                valueColor = colors.accentBlue
            )
        }
        KTelemetryRow("Requests", "${node.requests}", valueColor = colors.textSecondary)
        KTelemetryRow(
            "Last seen",
            relTime(now - node.lastSeen),
            valueColor = if (active) colors.accentGreen else colors.textMuted
        )
    }
}

@Composable
private fun EmptyPanel() {
    val colors = LocalKarenColors.current
    KPanel(
        title = "No devices yet",
        subtitle = "waiting for the first connection",
        icon = {
            Icon(
                Icons.Default.Router,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    ) {
        Text(
            "Start the local server, then open its address from another device on the same Wi-Fi. " +
                "Every dashboard page and API call appears here — a device can also join as a GPU worker from the dashboard.",
            color = colors.textMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun FooterNote() {
    Text(
        "Names come from each device or its network host name. A worker runs prompts on its own GPU — " +
            "route a chat to it under Runner in the dashboard; it shows offline after 75 s without a call.",
        color = LocalKarenColors.current.textMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

/** Monospaced address row with a tap-to-copy affordance. */
@Composable
private fun UrlRow(label: String, value: String, highlight: Boolean) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, "$label address copied", Toast.LENGTH_SHORT).show()
                copied = true
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = colors.textSecondary, fontSize = 13.sp, modifier = Modifier.width(48.dp))
        Text(
            value,
            color = if (highlight) colors.accentGreen else colors.textMuted,
            fontFamily = LocalKarenType.current.mono,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = "Copy $label address",
            tint = if (copied) colors.accentGreen else colors.textMuted,
            modifier = Modifier.size(14.dp)
        )
    }
}

/** Static state indicator dot (no glow — state, not decoration). */
@Composable
private fun StatusDot(color: Color, size: Dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

private fun relTime(deltaMs: Long): String = when {
    deltaMs < 15_000 -> "active now"
    deltaMs < 60_000 -> "${deltaMs / 1000}s ago"
    deltaMs < 3_600_000 -> "${deltaMs / 60_000}m ago"
    deltaMs < 86_400_000 -> "${deltaMs / 3_600_000}h ago"
    else -> "${deltaMs / 86_400_000}d ago"
}
