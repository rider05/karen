@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.karen.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the chart is showing right now (the overflow menu previews the states). */
private enum class FlowPreview { Live, Loading, Empty, Error }

/**
 * Intelligent Flowchart screen — a showcase host for the reusable
 * [IntelligentFlowchart] component. All content comes from
 * [FlowchartSamples.taskPipeline]; the component itself is workflow-agnostic.
 */
@Composable
fun FlowchartScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val scope = rememberCoroutineScope()
    val data = remember { FlowchartSamples.taskPipeline }

    var orientation by remember { mutableStateOf(FlowchartOrientation.Vertical) }
    var preview by remember { mutableStateOf(FlowPreview.Live) }
    var activeNodes by remember { mutableStateOf<Set<String>>(emptySet()) }
    var animating by remember { mutableStateOf(false) }
    var animationGeneration by remember { mutableStateOf(0) }
    var selectedNode by remember { mutableStateOf<FlowchartNode?>(null) }

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) { false }

    fun animatePath() {
        if (animating) return
        animating = true
        val generation = ++animationGeneration
        scope.launch {
            activeNodes = emptySet()
            FlowchartSamples.happyPath.forEach { id ->
                if (generation != animationGeneration) return@launch
                activeNodes = activeNodes + id
                delay(360)
            }
            if (generation == animationGeneration) animating = false
        }
    }

    fun resetFlow() {
        animationGeneration++
        animating = false
        activeNodes = emptySet()
        selectedNode = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Intelligent Flow",
            onMenuClick = onOpenDrawer,
            onBackClick = onNavigateToHome,
            moreActions = listOf(
                Triple("Live flow", Icons.Default.CheckCircle) { preview = FlowPreview.Live },
                Triple("Preview loading", Icons.Default.Refresh) { preview = FlowPreview.Loading },
                Triple("Preview empty", Icons.Default.AccountTree) { preview = FlowPreview.Empty },
                Triple("Preview error", Icons.Default.Warning) { preview = FlowPreview.Error }
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { FlowchartHero() }

            item {
                FlowchartControls(
                    orientation = orientation,
                    onOrientationChange = { orientation = it },
                    animating = animating,
                    onAnimate = { animatePath() },
                    onReset = { resetFlow() }
                )
            }

            item {
                FlowchartStage(
                    data = data,
                    orientation = orientation,
                    preview = preview,
                    activeNodes = activeNodes,
                    onNodeClick = { selectedNode = it }
                )
            }

            item { FlowchartLegend() }

            item {
                AnimatedVisibility(
                    visible = selectedNode != null && preview == FlowPreview.Live,
                    enter = fadeIn(tween(150)) + expandVertically(tween(180)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(150))
                ) {
                    selectedNode?.let { node ->
                        SelectedNodeCard(node = node, onClose = { selectedNode = null })
                    }
                }
            }

            item {
                Text(
                    text = "Tap a card to expand it · the ⋮ menu previews loading, empty, and error states.",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun FlowchartHero() {
    val colors = LocalKarenColors.current
    val type = LocalKarenType.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.accentGreen)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "INTELLIGENT FLOW",
                color = colors.accentGreen,
                fontFamily = type.mono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.3.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Task pipeline",
            color = colors.textPrimary,
            fontSize = 25.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = "A live map of one turn through Karen. Nodes, arrows, and branches are rendered from configurable data — nothing is a screenshot.",
            color = colors.textSecondary,
            fontSize = 12.5.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun FlowchartControls(
    orientation: FlowchartOrientation,
    onOrientationChange: (FlowchartOrientation) -> Unit,
    animating: Boolean,
    onAnimate: () -> Unit,
    onReset: () -> Unit
) {
    val colors = LocalKarenColors.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OrientationToggle(orientation = orientation, onChange = onOrientationChange)

        OutlinedButton(
            onClick = onAnimate,
            enabled = !animating,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, colors.accentBlue.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = colors.accentBlue,
                disabledContentColor = colors.textMuted
            ),
            contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (animating) "Animating…" else "Animate path",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        TextButton(onClick = onReset) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text("Reset", color = colors.textMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OrientationToggle(
    orientation: FlowchartOrientation,
    onChange: (FlowchartOrientation) -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(3.dp)
            .selectableGroup()
    ) {
        FlowchartOrientation.values().forEach { mode ->
            val active = mode == orientation
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) colors.accentGreen else Color.Transparent)
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onChange(mode) }
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = mode.label,
                    color = if (active) Color.White else colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun FlowchartStage(
    data: FlowchartData,
    orientation: FlowchartOrientation,
    preview: FlowPreview,
    activeNodes: Set<String>,
    onNodeClick: (FlowchartNode) -> Unit
) {
    val colors = LocalKarenColors.current
    val type = LocalKarenType.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.background)
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (preview) {
                    FlowPreview.Live -> "FLOW · ${data.nodes.size} NODES · ${data.edges.size} LINKS"
                    else -> "FLOW · PREVIEW"
                },
                color = colors.textMuted,
                fontFamily = type.mono,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            if (preview == FlowPreview.Live) {
                Text(
                    text = if (activeNodes.isEmpty()) "IDLE" else "LIVE",
                    color = if (activeNodes.isEmpty()) colors.textMuted else colors.accentGreen,
                    fontFamily = type.mono,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
            }
        }

        when (preview) {
            FlowPreview.Live -> IntelligentFlowchart(
                data = data,
                orientation = orientation,
                activeNodeIds = activeNodes,
                onNodeClick = onNodeClick
            )
            FlowPreview.Loading -> IntelligentFlowchart(
                data = data,
                isLoading = true
            )
            FlowPreview.Empty -> IntelligentFlowchart(
                data = FlowchartData(),
                emptyMessage = "No flow loaded yet — nodes appear here as soon as a pipeline is defined."
            )
            FlowPreview.Error -> IntelligentFlowchart(
                data = data,
                errorMessage = "This flow could not be rendered. Check the data and try again."
            )
        }
    }
}

@Composable
private fun FlowchartLegend() {
    val colors = LocalKarenColors.current
    val type = LocalKarenType.current
    Column {
        Text(
            text = "STATUS",
            color = colors.textMuted,
            fontFamily = type.mono,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp
        )
        Spacer(Modifier.height(7.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FlowchartNodeStatus.values().forEach { status ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(status.tint(colors))
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = status.label,
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedNodeCard(node: FlowchartNode, onClose: () -> Unit) {
    val colors = LocalKarenColors.current
    val type = LocalKarenType.current
    val accent = node.accent ?: node.type.defaultAccent(colors)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = node.icon ?: node.type.defaultIcon(),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${node.type.label} · ${node.status.label.uppercase()}",
                    color = accent,
                    fontFamily = type.mono,
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = node.title,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close details",
                tint = colors.textMuted,
                modifier = Modifier
                    .size(18.dp)
                    .clickable { onClose() }
            )
        }

        if (!node.description.isNullOrBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = node.description,
                color = colors.textSecondary,
                fontSize = 12.5.sp,
                lineHeight = 18.sp
            )
        }

        if (!node.badge.isNullOrBlank()) {
            Spacer(Modifier.height(9.dp))
            Text(
                text = node.badge.uppercase(),
                color = accent,
                fontFamily = type.mono,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.30f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }
}
