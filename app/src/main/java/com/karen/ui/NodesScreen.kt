package com.karen.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Network Nodes — this phone (the master) plus every device that ever talked
 * to its local server, live. The master card carries the server controls
 * (start/stop, LAN exposure, dashboard, restart); GPU workers get per-worker
 * controls (cancel the running task, disconnect for good); other devices can
 * be forgotten individually.
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

    var pendingDisconnect by remember { mutableStateOf<NodeInfo?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
        when {
            pendingDisconnect != null -> {
                pendingDisconnect = null
                true
            }
            confirmClear -> {
                confirmClear = false
                true
            }
            else -> false
        }
    }

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
                    confirmClear = true
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
                item { KSectionLabel("SLAVE NODES · ${workers.size}") }
                items(workers, key = { "worker:" + it.ip }) { node ->
                    WorkerPanel(
                        node = node,
                        now = now,
                        onCancelTask = {
                            val ok = LocalApiServer.cancelTaskOnWorker(node.workerId)
                            Toast.makeText(
                                context,
                                if (ok) "Task cancelled — the node stops at its next chunk" else "Nothing running to cancel",
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onDisconnect = { pendingDisconnect = node }
                    )
                }
            }

            if (clients.isNotEmpty()) {
                item { KSectionLabel("DEVICES · ${clients.size}") }
                items(clients, key = { "device:" + it.ip }) { node ->
                    DevicePanel(
                        node = node,
                        now = now,
                        onForget = {
                            LocalApiServer.forgetNode(node.ip)
                            Toast.makeText(
                                context,
                                "Forgot ${node.name.ifBlank { node.ip }}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }

            item { FooterNote() }
        }
    }

    val doomed = pendingDisconnect
    if (doomed != null) {
        val workerName = doomed.name.ifBlank { "Node " + doomed.ip.substringAfterLast('.') }
        AlertDialog(
            onDismissRequest = { pendingDisconnect = null },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Disconnect this slave node?") },
            text = {
                Text(
                    "\u201C$workerName\u201D stops immediately and will not rejoin on its own — " +
                        "the slave node switch on that device has to be turned back on."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDisconnect = null
                    val ok = LocalApiServer.disconnectWorker(doomed.workerId)
                    Toast.makeText(
                        context,
                        if (ok) "Slave node disconnected" else "Node already gone",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("Disconnect", color = colors.accentRed) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisconnect = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Clear node history?") },
            text = {
                Text(
                    "Every remembered device is dropped from this list. Devices that are still " +
                        "connected reappear on their next request; joined workers keep working."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    LocalApiServer.clearNodes()
                    Toast.makeText(context, "Node history cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear", color = colors.accentRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * The phone itself: the two addresses other devices use, live counters, and
 * the server controls — start/stop, LAN exposure, open dashboard, restart.
 * Tapping an address copies it.
 */
@Composable
private fun MasterPanel(workersOnline: Int, workersTotal: Int, nodesTotal: Int) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val running = LocalApiServer.running
    val lanUrl = LocalApiServer.lanUrl(context)
    val localUrl = "http://127.0.0.1:${UserPrefs.serverPort(context)}"

    // Pref-backed, so the switch flips immediately and survives restarts.
    var lanOn by remember { mutableStateOf(UserPrefs.serverLan(context)) }
    LaunchedEffect(Unit) { lanOn = UserPrefs.serverLan(context) }

    fun restartServer() {
        LocalApiServer.stop()
        val ok = LocalApiServer.start(context)
        if (!ok) {
            Toast.makeText(context, "Could not start the server — check the port in Settings", Toast.LENGTH_SHORT).show()
        }
    }

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
                "Slaves",
                if (workersTotal == 0) "0" else "$workersOnline/$workersTotal",
                Modifier.weight(1f),
                tone = if (workersOnline > 0) KTone.Success else KTone.Muted,
                caption = if (workersTotal == 0) "none yet" else "online"
            )
            KMetric(
                "Devices",
                "$nodesTotal",
                Modifier.weight(1f),
                caption = "seen"
            )
        }

        Spacer(Modifier.height(12.dp))
        KHairline()
        Spacer(Modifier.height(8.dp))

        SwitchRow(
            title = "Server",
            subtitle = if (running) LocalApiServer.status else "Stopped",
            subtitleColor = if (running) colors.accentGreen else colors.textMuted,
            checked = running,
            accent = colors.accentGreen
        ) { want ->
            if (want) {
                val ok = LocalApiServer.start(context)
                if (!ok) {
                    Toast.makeText(context, "Could not start — check the port in Settings", Toast.LENGTH_SHORT).show()
                }
            } else {
                LocalApiServer.stop()
            }
        }

        SwitchRow(
            title = "LAN access",
            subtitle = if (lanOn) "Anyone on your Wi-Fi with the token can call it" else "Localhost only",
            subtitleColor = if (lanOn) colors.accentAmber else colors.textMuted,
            checked = lanOn,
            accent = colors.accentAmber
        ) { want ->
            lanOn = want
            UserPrefs.setServerLan(context, want)
            // The bind address is decided at start — flip the listener over.
            if (LocalApiServer.running) restartServer()
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KSecondaryButton(
                label = "Open dashboard",
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(localUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = running
            )
            KSecondaryButton(
                label = "Restart",
                onClick = { restartServer() },
                modifier = Modifier.weight(1f),
                enabled = running
            )
        }
    }
}

/** One joined GPU worker: state, loaded model, GPU, traffic, and its controls. */
@Composable
private fun WorkerPanel(
    node: NodeInfo,
    now: Long,
    onCancelTask: () -> Unit,
    onDisconnect: () -> Unit
) {
    val colors = LocalKarenColors.current
    val online = now - node.lastSeen < WorkerInfo.ONLINE_WINDOW_MS
    val state = node.workerState.lowercase(Locale.US)
    val hasGpu = node.gpu.isNotBlank() || node.webgpu || node.gflops > 0.0
    val busy = online && state in listOf("assigned", "loading", "running")

    val tone = when {
        !online -> KTone.Muted
        state == "running" -> KTone.Primary
        state == "loading" || state == "assigned" -> KTone.Amber
        else -> KTone.Success
    }
    val label = if (!online) "OFFLINE" else state.ifBlank { "idle" }.uppercase(Locale.US)

    KPanel(
        title = node.name.ifBlank { "Node " + node.ip.substringAfterLast('.') },
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

        Spacer(Modifier.height(10.dp))
        KHairline()
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (busy) {
                KSecondaryButton(
                    label = "Cancel task",
                    onClick = onCancelTask,
                    modifier = Modifier.weight(1f),
                    tone = KTone.Amber
                )
            }
            KSecondaryButton(
                label = "Disconnect",
                onClick = onDisconnect,
                modifier = Modifier.weight(1f),
                tone = KTone.Danger
            )
        }
    }
}

/** Any other device that hit the server: dashboard pages and API callers. */
@Composable
private fun DevicePanel(node: NodeInfo, now: Long, onForget: () -> Unit) {
    val colors = LocalKarenColors.current
    val active = now - node.lastSeen < 60_000
    val hasGpu = node.gpu.isNotBlank() || node.webgpu || node.gflops > 0.0

    KPanel(
        title = node.name.ifBlank { "Device " + node.ip.substringAfterLast('.') },
        subtitle = node.ip,
        icon = { StatusDot(if (active) colors.accentGreen else colors.textMuted, 9.dp) },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KStatusChip(
                    label = node.kind.ifBlank { "Device" }.uppercase(Locale.US),
                    tone = if (node.kind.equals("Dashboard", ignoreCase = true)) KTone.Blue else KTone.Amber
                )
                Spacer(Modifier.width(2.dp))
                KIconButton(onClick = onForget) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Forget this device",
                        tint = colors.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
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
                "Other devices work as slave nodes automatically: open the dashboard in Chrome/Edge " +
                "with WebGPU and save the token once — the phone itself always stays master.",
            color = colors.textMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun FooterNote() {
    Text(
        "Names come from each device or its network host name. A slave node runs prompts on its own GPU — " +
            "Cancel task stops its current prompt, Disconnect stops the node for good. It shows offline " +
            "after 75 s without a call.",
        color = LocalKarenColors.current.textMuted,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

/** Labeled switch row used by the master controls. */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    checked: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = subtitleColor, fontSize = 11.5.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = accent,
                uncheckedThumbColor = colors.textMuted,
                uncheckedTrackColor = colors.surfaceHover
            )
        )
    }
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
