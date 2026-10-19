package com.karen.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    var confirmDisconnectAll by remember { mutableStateOf(false) }

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
        when {
            pendingDisconnect != null -> {
                pendingDisconnect = null
                true
            }
            confirmDisconnectAll -> {
                confirmDisconnectAll = false
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
                Triple("Restart server", Icons.Default.Refresh) {
                    if (LocalApiServer.running) {
                        restartKarenServer(context)
                        Toast.makeText(context, "Server restarted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Server is stopped — start it below", Toast.LENGTH_SHORT).show()
                    }
                },
                Triple("Disconnect all slaves", Icons.Default.Close) {
                    confirmDisconnectAll = true
                },
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
                    nodesTotal = nodes.size,
                    now = now
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
                        onPauseToggle = {
                            // State control, not a disconnect: a paused node keeps
                            // its connection but takes no new prompts.
                            val wantPause = node.workerState.lowercase(Locale.US) != "paused"
                            val ok = LocalApiServer.setWorkerPaused(node.workerId, wantPause)
                            Toast.makeText(
                                context,
                                when {
                                    !ok -> "Node already gone"
                                    wantPause -> "Paused — no new prompts route there"
                                    else -> "Resumed — back in rotation"
                                },
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

    if (confirmDisconnectAll) {
        AlertDialog(
            onDismissRequest = { confirmDisconnectAll = false },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Disconnect every slave node?") },
            text = {
                Text(
                    if (workers.isEmpty()) {
                        "No slave nodes are joined right now — nothing to disconnect."
                    } else {
                        "All ${workers.size} joined node(s) stop immediately and will not rejoin on their own — " +
                            "the slave node switch on each device has to be turned back on."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnectAll = false
                    val n = LocalApiServer.disconnectAllWorkers()
                    Toast.makeText(
                        context,
                        if (n == 0) "No slave nodes were joined" else "$n slave node(s) disconnected",
                        Toast.LENGTH_SHORT
                    ).show()
                }) { Text("Disconnect all", color = colors.accentRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnectAll = false }) { Text("Cancel") }
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

/** Stops and starts the local server — used by the master panel button and the header menu. */
private fun restartKarenServer(context: Context) {
    LocalApiServer.stop()
    val ok = LocalApiServer.start(context)
    if (!ok) {
        Toast.makeText(context, "Could not start the server — check the port in Settings", Toast.LENGTH_SHORT).show()
    }
}

/**
 * The phone itself: the two addresses other devices use, live counters, and
 * the server controls — start/stop, LAN exposure, open dashboard, restart.
 * Tapping an address copies it.
 */
@Composable
private fun MasterPanel(workersOnline: Int, workersTotal: Int, nodesTotal: Int, now: Long) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    // Real device readings — unlike browser workers, the phone can read its own
    // battery temperature and thermal status, actual RAM and storage.
    val tel = remember(now) { readPhoneTelemetry(context) }
    val running = LocalApiServer.running
    val lanUrl = LocalApiServer.lanUrl(context)
    val localUrl = "http://127.0.0.1:${UserPrefs.serverPort(context)}"

    // Pref-backed, so the switch flips immediately and survives restarts.
    var lanOn by remember { mutableStateOf(UserPrefs.serverLan(context)) }
    LaunchedEffect(Unit) { lanOn = UserPrefs.serverLan(context) }

    fun restartServer() {
        restartKarenServer(context)
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
        UrlRow("Worker", "$lanUrl/", highlight = running && lanOn)
        UrlRow("Console", "$lanUrl/dashboard", highlight = running && lanOn)
        UrlRow("Local", "$localUrl/dashboard", highlight = running)

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
        Spacer(Modifier.height(10.dp))

        val heatColor = when (tel.thermal) {
            "light" -> colors.accentBlue
            "moderate" -> colors.accentAmber
            "severe", "critical", "emergency", "shutdown" -> colors.accentRed
            else -> colors.textSecondary
        }
        KTelemetryRow("CPU", tel.cpu, valueColor = colors.textSecondary)
        KTelemetryRow("RAM", tel.ram, valueColor = colors.textSecondary)
        KTelemetryRow("Storage", tel.storage, valueColor = colors.textSecondary)
        KTelemetryRow("Heat", tel.heat, valueColor = heatColor)
        KTelemetryRow("Network", tel.network, valueColor = colors.textSecondary)

        Spacer(Modifier.height(10.dp))
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
                            Intent(Intent.ACTION_VIEW, Uri.parse("$localUrl/dashboard"))
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
    onPauseToggle: () -> Unit,
    onDisconnect: () -> Unit
) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val online = now - node.lastSeen < WorkerInfo.ONLINE_WINDOW_MS
    val state = node.workerState.lowercase(Locale.US)
    val paused = online && state == "paused"
    val hasGpu = node.gpu.isNotBlank() || node.webgpu || node.gflops > 0.0
    val busy = online && state in listOf("assigned", "loading", "running")
    // Master-side model assignment for this node (null when the record is gone).
    val assignInfo = LocalApiServer.workerAssignInfo(node.workerId)
    var assignMenu by remember { mutableStateOf(false) }

    val tone = when {
        !online -> KTone.Muted
        paused -> KTone.Amber
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
            buildString {
                append(node.gpu.ifBlank { if (node.webgpu) "WebGPU ready" else "not reported" })
                if (node.maxBufferMB > 0) append(" · buf ${node.maxBufferMB} MB")
            },
            valueColor = if (hasGpu) colors.accentBlue else colors.textMuted
        )
        if (node.gflops > 0.0) {
            KTelemetryRow(
                "Compute",
                String.format(Locale.US, "%.0f GFLOPS", node.gflops),
                valueColor = colors.accentBlue
            )
        }
        // Machine telemetry the worker page measured about itself. Browser
        // estimates: RAM is deviceMemory (capped at 8 GB), disk is the storage
        // quota, heat is the CPU pressure state (no °C API exists in browsers).
        val cpuText = nodeCpuText(node)
        val ramText = nodeRamText(node)
        val heatText = nodeHeatText(node)
        val diskText = nodeDiskText(node)
        val netText = nodeNetText(node)
        if (cpuText != null) KTelemetryRow("CPU", cpuText, valueColor = colors.textSecondary)
        if (ramText != null) KTelemetryRow("RAM", ramText, valueColor = colors.textSecondary)
        if (heatText != null) KTelemetryRow("Heat", heatText, valueColor = pressureColor(node.pressure, colors))
        if (diskText != null) KTelemetryRow("Disk", diskText, valueColor = colors.textSecondary)
        if (netText != null) KTelemetryRow("Network", netText, valueColor = colors.textSecondary)
        KTelemetryRow("Requests", "${node.requests}", valueColor = colors.textSecondary)
        KTelemetryRow(
            "Last seen",
            relTime(now - node.lastSeen),
            valueColor = if (online) colors.accentGreen else colors.textMuted
        )

        // Model assignment (master side): the whole point is that it is visible
        // here and nameable from here — the worker page labels it "assigned on
        // the phone" and every turn without its own model runs it.
        if (assignInfo != null) {
            Spacer(Modifier.height(10.dp))
            KHairline()
            Spacer(Modifier.height(10.dp))
            KTelemetryRow(
                "Assigned",
                assignInfo.assigned.ifBlank { "node's own pick" },
                valueColor = if (assignInfo.assigned.isNotBlank()) colors.accentGreen else colors.textMuted
            )
            Spacer(Modifier.height(8.dp))
            Box {
                KSecondaryButton(
                    label = "Assign model…",
                    onClick = { assignMenu = true },
                    enabled = online,
                    modifier = Modifier.fillMaxWidth()
                )
                KDropdownMenu(expanded = assignMenu, onDismissRequest = { assignMenu = false }) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Node's own pick", color = colors.textPrimary, fontSize = 13.sp)
                                Text(
                                    "the worker page decides",
                                    color = colors.textMuted,
                                    fontSize = 11.sp
                                )
                            }
                        },
                        trailingIcon = {
                            if (assignInfo.assigned.isBlank()) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(16.dp))
                            }
                        },
                        onClick = {
                            assignMenu = false
                            val ok = LocalApiServer.assignWorkerModel(node.workerId, "")
                            Toast.makeText(context, if (ok) "Assignment cleared — the node's own pick runs" else "Node already gone", Toast.LENGTH_SHORT).show()
                        }
                    )
                    if (assignInfo.models.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No models reported yet — the node lists them after it joins", color = colors.textMuted, fontSize = 12.sp) },
                            enabled = false,
                            onClick = {}
                        )
                    }
                    if (assignInfo.models.isNotEmpty()) KMenuDivider()
                    assignInfo.models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m, color = colors.textPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingIcon = {
                                if (assignInfo.assigned.equals(m, ignoreCase = true)) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(16.dp))
                                }
                            },
                            onClick = {
                                assignMenu = false
                                val ok = LocalApiServer.assignWorkerModel(node.workerId, m)
                                Toast.makeText(context, if (ok) "Assigned — new turns to this node run $m" else "Node already gone", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

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
                label = if (paused) "Resume" else "Pause",
                onClick = onPauseToggle,
                modifier = Modifier.weight(1f),
                enabled = online,
                tone = if (paused) KTone.Success else KTone.Muted
            )
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
                    if (node.maxBufferMB > 0) append(" · buf ${node.maxBufferMB} MB")
                },
                valueColor = colors.accentBlue
            )
        }
        val cpuText = nodeCpuText(node)
        val ramText = nodeRamText(node)
        val heatText = nodeHeatText(node)
        val diskText = nodeDiskText(node)
        val netText = nodeNetText(node)
        if (cpuText != null) KTelemetryRow("CPU", cpuText, valueColor = colors.textSecondary)
        if (ramText != null) KTelemetryRow("RAM", ramText, valueColor = colors.textSecondary)
        if (heatText != null) KTelemetryRow("Heat", heatText, valueColor = pressureColor(node.pressure, colors))
        if (diskText != null) KTelemetryRow("Disk", diskText, valueColor = colors.textSecondary)
        if (netText != null) KTelemetryRow("Network", netText, valueColor = colors.textSecondary)
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
                "Other devices work as slave nodes automatically: open the worker page (LAN address + /) " +
                "in Chrome/Edge with WebGPU and save the token once — the phone itself always stays master. " +
                "The control dashboard lives at LAN address + /dashboard. " +
                "On a plain http:// address every browser hides WebGPU — the page's GPU panel then prints " +
                "the exact fix: on the laptop run tools\\karen-tunnel.ps1 -Phone <this phone's IP> and use " +
                "the 127.0.0.1 address it opens.",
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
            "after 90 s without a call. CPU, RAM, disk and network are reported by each device itself: " +
            "browsers expose no temperature sensor, so a slave node's heat shows as its CPU pressure state, " +
            "while this phone's card shows the real battery °C and thermal status. RAM is the browser's own " +
            "estimate (capped at 8 GB), disk is the storage quota (~free space).",
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

// ---- telemetry formatting (worker/device nodes) ---------------------------

/** "16 threads · x86 64-bit · score 512" — null when nothing was reported. */
private fun nodeCpuText(n: NodeInfo): String? {
    val parts = mutableListOf<String>()
    if (n.cpuCores > 0) parts.add("${n.cpuCores} threads")
    if (n.cpuArch.isNotBlank()) parts.add(n.cpuArch)
    if (n.jsScore > 0.0) parts.add(String.format(Locale.US, "score %.0f", n.jsScore))
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun nodeRamText(n: NodeInfo): String? =
    if (n.memGB > 0.0) String.format(Locale.US, "≈%.0f GB (browser estimate)", n.memGB) else null

/**
 * Browsers expose no temperature sensor. The Compute Pressure API state is the
 * closest real heat/load signal for a slave node; the phone card shows the
 * actual °C.
 */
private fun nodeHeatText(n: NodeInfo): String? =
    if (n.pressure.isNotBlank()) "CPU pressure ${n.pressure} — no °C in browsers" else null

/** Storage estimate ≈ free disk (Chrome quota ≈ 60% of remaining space). */
private fun nodeDiskText(n: NodeInfo): String? {
    if (n.quotaGB <= 0.0) return null
    var text = String.format(Locale.US, "≈%.1f GB free (quota)", n.quotaGB)
    if (n.usageGB > 0.0) text += String.format(Locale.US, " · %.2f used", n.usageGB)
    return text
}

private fun nodeNetText(n: NodeInfo): String? {
    val parts = mutableListOf<String>()
    if (n.netType.isNotBlank()) parts.add(n.netType)
    if (n.downlinkMbps > 0.0) parts.add(String.format(Locale.US, "↓%.0f Mbps", n.downlinkMbps))
    if (n.netRttMs > 0) parts.add("rtt ${n.netRttMs} ms")
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun pressureColor(pressure: String, colors: KarenColors): Color = when (pressure.lowercase(Locale.US)) {
    "nominal" -> colors.accentGreen
    "fair" -> colors.accentBlue
    "serious" -> colors.accentAmber
    "critical" -> colors.accentRed
    else -> colors.textSecondary
}

// ---- telemetry of the phone itself (the master) ----------------------------

/** Ready-to-show readings from this device — real sensors, not estimates. */
private data class PhoneTel(
    val cpu: String,
    val ram: String,
    val storage: String,
    val heat: String,
    val thermal: String,
    val network: String
)

private fun readPhoneTelemetry(context: Context): PhoneTel {
    val cores = Runtime.getRuntime().availableProcessors()
    val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.ifBlank { Build.HARDWARE } else Build.HARDWARE
    val cpu = buildString {
        append("$cores cores")
        if (soc.isNotBlank() && !soc.equals("unknown", ignoreCase = true)) {
            append(" · ").append(soc.replace('_', ' '))
        }
    }

    var ram = "not reported"
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        ram = String.format(
            Locale.US, "%.1f / %.1f GB used",
            (mi.totalMem - mi.availMem) / 1073741824.0, mi.totalMem / 1073741824.0
        )
    } catch (_: Exception) {
    }

    var storage = "not reported"
    try {
        val st = StatFs(Environment.getDataDirectory().path)
        storage = String.format(
            Locale.US, "%.0f / %.0f GB free",
            st.availableBytes / 1073741824.0, st.totalBytes / 1073741824.0
        )
    } catch (_: Exception) {
    }

    var tempC = 0.0
    try {
        // Sticky broadcast — no receiver registered, no permission needed.
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        if (tenths > 0) tempC = tenths / 10.0
    } catch (_: Exception) {
    }

    var thermal = ""
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            thermal = when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "nominal"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> ""
            }
        } catch (_: Exception) {
        }
    }
    val heat = buildString {
        if (tempC > 0.0) append(String.format(Locale.US, "%.1f °C battery", tempC))
        if (thermal.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append("thermal ").append(thermal)
        }
        if (isEmpty()) append("not reported")
    }

    var network = "not reported"
    try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (caps != null) {
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "network"
            }
            val down = caps.linkDownstreamBandwidthKbps / 1000
            val up = caps.linkUpstreamBandwidthKbps / 1000
            network = if (down > 0 || up > 0) "$kind · ↓$down / ↑$up Mbps" else kind
        }
    } catch (_: Exception) {
    }

    return PhoneTel(cpu = cpu, ram = ram, storage = storage, heat = heat, thermal = thermal, network = network)
}
