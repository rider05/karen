package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Network Nodes — every device that ever talked to the local server, named
 * and live. The phone itself is the first node; LAN devices follow.
 */
@Composable
fun NodesScreen(onNavigateToHome: () -> Unit = {}) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    val nodes = LocalApiServer.nodeSnapshot
    // Bumps every 5 s so "active now / 2m ago" stays honest.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            tick++
        }
    }

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
                    android.widget.Toast.makeText(context, "Node history cleared", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 0.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The phone itself — the node everything else connects to.
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(CircleShape)
                                .background(if (LocalApiServer.running) colors.accentGreen else colors.textMuted)
                        )
                        Spacer(Modifier.width(9.dp))
                        Text("This phone · Karen", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (LocalApiServer.running) "ONLINE" else "OFFLINE",
                            color = if (LocalApiServer.running) colors.accentGreen else colors.textMuted,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        LocalApiServer.lanUrl(context),
                        color = if (LocalApiServer.running) colors.accentGreen else colors.textMuted,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "http://127.0.0.1:${UserPrefs.serverPort(context)}",
                        color = colors.textMuted,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${LocalApiServer.requestCount} request(s) received · ${LocalApiServer.status}",
                        color = colors.textMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            item {
                Text(
                    "Connected devices (${nodes.size})",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (nodes.isEmpty()) {
                item {
                    Text(
                        "No devices yet. Turn the server on and open its address from another device on the same Wi-Fi — every device that connects shows up here as a node.",
                        color = colors.textMuted,
                        fontSize = 12.5.sp
                    )
                }
            } else {
                items(nodes, key = { it.ip }) { node ->
                    NodeRow(node = node, colors = colors, tick = tick)
                }
            }

            item {
                Text(
                    "Names come from the device itself (dashboard header) or its network host name. Web pages and API calls both count as connections. A device that joined as GPU worker shows its worker state and model below — prompts can be routed to it from the dashboard's Runner picker.",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun NodeRow(node: NodeInfo, colors: KarenColors, tick: Long) {
    // Recompute "now" each tick so the relative time refreshes.
    val now = remember(tick) { System.currentTimeMillis() }
    val active = now - node.lastSeen < 60_000
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (active) colors.accentGreen else colors.textMuted)
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.name.ifBlank { "Device " + node.ip.substringAfterLast('.') },
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    node.ip,
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                node.kind,
                color = if (node.kind == "Dashboard") colors.accentBlue else colors.accentAmber,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (node.webgpu || node.gpu.isNotBlank() || node.gflops > 0.0) {
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("GPU: ")
                    append(node.gpu.ifBlank { "WebGPU ready" })
                    if (node.gflops > 0.0) {
                        append(" · ")
                        append(String.format(java.util.Locale.US, "%.0f", node.gflops))
                        append(" GFLOPS")
                    }
                },
                color = colors.accentBlue,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (node.worker) {
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("WORKER · ")
                    append(node.workerState.ifBlank { "idle" })
                    if (node.workerModel.isNotBlank()) {
                        append(" · ")
                        append(node.workerModel)
                    }
                },
                color = colors.accentGreen,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "${node.requests} request(s)",
                color = colors.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                relTime(now - node.lastSeen),
                color = if (active) colors.accentGreen else colors.textMuted,
                fontSize = 11.sp
            )
        }
    }
}

private fun relTime(deltaMs: Long): String = when {
    deltaMs < 15_000 -> "active now"
    deltaMs < 60_000 -> "${deltaMs / 1000}s ago"
    deltaMs < 3_600_000 -> "${deltaMs / 60_000}m ago"
    deltaMs < 86_400_000 -> "${deltaMs / 3_600_000}h ago"
    else -> "${deltaMs / 86_400_000}d ago"
}
