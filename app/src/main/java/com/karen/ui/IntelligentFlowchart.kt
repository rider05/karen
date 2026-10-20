@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.karen.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Intelligent flowchart — a reusable, data-driven flow visualisation for the
 * Karen design system.
 *
 * Feed it a [FlowchartData] and it computes the layers, draws every node and
 * connector as live UI (no images), and lays them out vertically or
 * horizontally. Nothing here knows about a specific workflow: the sample
 * pipeline lives in `FlowchartSamples.kt`.
 *
 * Behaviour:
 *  - tap a node to select it and expand/collapse its description;
 *  - hover/focus/press feedback with animated borders and scale;
 *  - `activeNodeIds` lights the traversed path and animates its dashes;
 *  - loading, empty, and error panels share the same rounded-card chrome;
 *  - every node is a real clickable button (TalkBack + keyboard Enter/Space).
 */
@Composable
fun IntelligentFlowchart(
    data: FlowchartData,
    modifier: Modifier = Modifier,
    orientation: FlowchartOrientation = FlowchartOrientation.Vertical,
    activeNodeIds: Set<String> = emptySet(),
    animateActivePath: Boolean = true,
    isLoading: Boolean = false,
    errorMessage: String? = null,
    emptyMessage: String = "No steps to map yet",
    initialSelectedId: String? = null,
    onNodeClick: (FlowchartNode) -> Unit = {}
) {
    when {
        isLoading -> FlowStatePanel(
            title = "Mapping the flow",
            message = "Laying out nodes and connectors…",
            loading = true,
            modifier = modifier
        )
        errorMessage != null -> FlowStatePanel(
            title = "Flow unavailable",
            message = errorMessage,
            icon = Icons.Default.Warning,
            tint = LocalKarenColors.current.accentRed,
            modifier = modifier
        )
        data.nodes.isEmpty() -> FlowStatePanel(
            title = "Nothing to map",
            message = emptyMessage,
            icon = Icons.Default.AccountTree,
            tint = LocalKarenColors.current.textMuted,
            modifier = modifier
        )
        else -> {
            val layers = remember(data) { computeFlowLayers(data) }
            val nodeRects = remember(data) { mutableStateMapOf<String, Rect>() }
            var chartOrigin by remember { mutableStateOf<Offset?>(null) }
            var selectedId by remember(data) { mutableStateOf(initialSelectedId) }
            var expandedIds by remember(data) {
                mutableStateOf(initialSelectedId?.let { setOf(it) } ?: emptySet())
            }

            val toggle: (FlowchartNode) -> Unit = { node ->
                selectedId = node.id
                expandedIds = if (node.id in expandedIds) expandedIds - node.id else expandedIds + node.id
                onNodeClick(node)
            }

            BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
                val maxPerLayer = (layers.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
                val gapBetweenCards = 12.dp
                val fitted = (maxWidth - 20.dp - gapBetweenCards * (maxPerLayer - 1)) / maxPerLayer
                val verticalNodeWidth = fitted.coerceIn(118.dp, 232.dp)
                val connectorGap = if (layers.any { it.size > 1 }) 72.dp else 56.dp
                val scrollState = rememberScrollState()

                if (orientation == FlowchartOrientation.Horizontal) {
                    // Wide charts scroll sideways, never squeeze cards.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scrollState)
                    ) {
                        ChartSurface(
                            data = data,
                            layers = layers,
                            orientation = orientation,
                            nodeWidth = 208.dp,
                            connectorGap = 60.dp,
                            nodeRects = nodeRects,
                            chartOrigin = chartOrigin,
                            onOriginChange = { chartOrigin = it },
                            selectedId = selectedId,
                            expandedIds = expandedIds,
                            activeNodeIds = activeNodeIds,
                            animateActivePath = animateActivePath,
                            onToggle = toggle
                        )
                    }
                } else {
                    ChartSurface(
                        data = data,
                        layers = layers,
                        orientation = orientation,
                        nodeWidth = verticalNodeWidth,
                        connectorGap = connectorGap,
                        nodeRects = nodeRects,
                        chartOrigin = chartOrigin,
                        onOriginChange = { chartOrigin = it },
                        selectedId = selectedId,
                        expandedIds = expandedIds,
                        activeNodeIds = activeNodeIds,
                        animateActivePath = animateActivePath,
                        onToggle = toggle,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** One flowchart node: kind, copy, icon, badge, and current status. */
data class FlowchartNode(
    val id: String,
    val type: FlowchartNodeType = FlowchartNodeType.Process,
    val title: String,
    val description: String? = null,
    val icon: ImageVector? = null,
    val badge: String? = null,
    val status: FlowchartNodeStatus = FlowchartNodeStatus.Idle,
    val accent: Color? = null
)

/** A directed connector. [label] renders as a pill on the curve (e.g. Yes / No). */
data class FlowchartEdge(
    val from: String,
    val to: String,
    val label: String? = null,
    val tone: FlowchartEdgeTone = FlowchartEdgeTone.Default
)

/** Everything the component needs — swap this to visualise a different flow. */
data class FlowchartData(
    val nodes: List<FlowchartNode> = emptyList(),
    val edges: List<FlowchartEdge> = emptyList()
) {
    fun node(id: String): FlowchartNode? = nodes.firstOrNull { it.id == id }
}

enum class FlowchartNodeType(val label: String) {
    Start("START"),
    End("END"),
    Process("PROCESS"),
    Decision("DECISION"),
    InputOutput("INPUT / OUTPUT"),
    Custom("CUSTOM")
}

enum class FlowchartNodeStatus(val label: String) {
    Idle("Idle"),
    Active("Active"),
    Completed("Completed"),
    Pending("Pending"),
    Failed("Failed")
}

enum class FlowchartEdgeTone { Default, Positive, Negative, Warning }

enum class FlowchartOrientation(val label: String) {
    Vertical("Vertical"),
    Horizontal("Horizontal")
}

/** Default icon per node kind; a node's own [FlowchartNode.icon] wins. */
fun FlowchartNodeType.defaultIcon(): ImageVector = when (this) {
    FlowchartNodeType.Start -> Icons.Default.PlayArrow
    FlowchartNodeType.End -> Icons.Default.Flag
    FlowchartNodeType.Process -> Icons.Default.AutoAwesome
    FlowchartNodeType.Decision -> Icons.AutoMirrored.Filled.CallSplit
    FlowchartNodeType.InputOutput -> Icons.Default.SwapHoriz
    FlowchartNodeType.Custom -> Icons.Default.Tune
}

/** Theme-aware accent per node kind; a node's own [FlowchartNode.accent] wins. */
fun FlowchartNodeType.defaultAccent(colors: KarenColors): Color = when (this) {
    FlowchartNodeType.Start, FlowchartNodeType.End -> colors.accentGreen
    FlowchartNodeType.Process -> colors.accentBlue
    FlowchartNodeType.Decision -> colors.accentAmber
    FlowchartNodeType.InputOutput ->
        if (colors.isDark) Color(0xFFC084FC) else Color(0xFF7E22CE)
    FlowchartNodeType.Custom -> colors.accentBlue
}

/** Status colour used by the node dot and the legend in the sample screen. */
fun FlowchartNodeStatus.tint(colors: KarenColors): Color = when (this) {
    FlowchartNodeStatus.Idle -> colors.textMuted
    FlowchartNodeStatus.Active -> colors.accentBlue
    FlowchartNodeStatus.Completed -> colors.accentGreen
    FlowchartNodeStatus.Pending -> colors.accentAmber
    FlowchartNodeStatus.Failed -> colors.accentRed
}

/**
 * The measured chart: connectors behind, node cards in front. Node bounds are
 * captured in root coordinates and translated into chart-local space so the
 * canvas stays aligned while the host screen scrolls.
 */
@Composable
private fun ChartSurface(
    data: FlowchartData,
    layers: List<List<FlowchartNode>>,
    orientation: FlowchartOrientation,
    nodeWidth: Dp,
    connectorGap: Dp,
    nodeRects: MutableMap<String, Rect>,
    chartOrigin: Offset?,
    onOriginChange: (Offset) -> Unit,
    selectedId: String?,
    expandedIds: Set<String>,
    activeNodeIds: Set<String>,
    animateActivePath: Boolean,
    onToggle: (FlowchartNode) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.onGloballyPositioned { onOriginChange(it.positionInRoot()) }
    ) {
        FlowchartEdgeCanvas(
            data = data,
            bounds = nodeRects,
            origin = chartOrigin,
            orientation = orientation,
            activeNodeIds = activeNodeIds,
            animate = animateActivePath,
            modifier = Modifier.matchParentSize()
        )

        if (orientation == FlowchartOrientation.Vertical) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                layers.forEachIndexed { index, layer ->
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        layer.forEach { node ->
                            FlowchartNodeSlot(
                                node = node,
                                width = nodeWidth,
                                nodeRects = nodeRects,
                                selected = node.id == selectedId,
                                expanded = node.id in expandedIds,
                                active = node.id in activeNodeIds,
                                onToggle = { onToggle(node) }
                            )
                        }
                    }
                    if (index != layers.lastIndex) {
                        Spacer(Modifier.height(connectorGap))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(connectorGap),
                verticalAlignment = Alignment.Top
            ) {
                layers.forEach { layer ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        layer.forEach { node ->
                            FlowchartNodeSlot(
                                node = node,
                                width = nodeWidth,
                                nodeRects = nodeRects,
                                selected = node.id == selectedId,
                                expanded = node.id in expandedIds,
                                active = node.id in activeNodeIds,
                                onToggle = { onToggle(node) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Wraps a card and reports its bounds relative to the root for the canvas. */
@Composable
private fun FlowchartNodeSlot(
    node: FlowchartNode,
    width: Dp,
    nodeRects: MutableMap<String, Rect>,
    selected: Boolean,
    expanded: Boolean,
    active: Boolean,
    onToggle: () -> Unit
) {
    FlowchartNodeCard(
        node = node,
        width = width,
        selected = selected,
        expanded = expanded,
        active = active,
        onClick = onToggle,
        modifier = Modifier.onGloballyPositioned { coords ->
            val position = coords.positionInRoot()
            val rect = Rect(
                left = position.x,
                top = position.y,
                right = position.x + coords.size.width,
                bottom = position.y + coords.size.height
            )
            if (nodeRects[node.id] != rect) nodeRects[node.id] = rect
        }
    )
}

/** A single interactive node card. */
@Composable
private fun FlowchartNodeCard(
    node: FlowchartNode,
    width: Dp,
    selected: Boolean,
    expanded: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    val type = LocalKarenType.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()

    val accent = node.accent ?: node.type.defaultAccent(colors)
    val statusTint = node.status.tint(colors)
    val shape = RoundedCornerShape(18.dp)

    val background by animateColorAsState(
        targetValue = when {
            pressed -> colors.surfaceHover
            selected -> accent.copy(alpha = if (colors.isDark) 0.16f else 0.10f)
            active -> colors.accentBlue.copy(alpha = if (colors.isDark) 0.10f else 0.07f)
            hovered || focused -> colors.surfaceHover
            else -> colors.surface
        },
        animationSpec = tween(160),
        label = "flow_node_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            selected -> accent
            active -> colors.accentBlue
            focused -> colors.accentBlue
            hovered -> accent.copy(alpha = 0.55f)
            node.type == FlowchartNodeType.Decision -> accent.copy(alpha = 0.42f)
            else -> colors.border
        },
        animationSpec = tween(160),
        label = "flow_node_border"
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            selected -> 1.6.dp
            active -> 1.4.dp
            else -> 1.dp
        },
        animationSpec = tween(160),
        label = "flow_node_border_width"
    )
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.985f
            hovered -> 1.012f
            else -> 1f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "flow_node_scale"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "flow_node_chevron"
    )

    Box(
        modifier = modifier
            .width(width)
            .scale(scale)
            .clip(shape)
            .background(background)
            .border(borderWidth, borderColor, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .hoverable(interaction)
            .defaultMinSize(minHeight = 104.dp)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = when {
                    !node.description.isNullOrBlank() ->
                        if (expanded) "Expanded" else "Collapsed"
                    else -> node.status.label
                }
                contentDescription = buildString {
                    append(node.title)
                    node.description?.let { append(". "); append(it) }
                    append(". ")
                    append(node.type.label.lowercase())
                    append(", ")
                    append(node.status.label.lowercase())
                    node.badge?.let { append(", "); append(it) }
                }
            }
    ) {
        if (node.status != FlowchartNodeStatus.Idle) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusTint)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = node.icon ?: node.type.defaultIcon(),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(Modifier.height(9.dp))
            Text(
                text = node.type.label,
                color = accent.copy(alpha = 0.85f),
                fontFamily = type.mono,
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = node.title,
                color = colors.textPrimary,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )

            AnimatedVisibility(
                visible = expanded && !node.description.isNullOrBlank(),
                enter = fadeIn(tween(140)) + expandVertically(tween(190)),
                exit = fadeOut(tween(110)) + shrinkVertically(tween(160))
            ) {
                Text(
                    text = node.description.orEmpty(),
                    color = colors.textSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 7.dp)
                )
            }

            if (!node.description.isNullOrBlank()) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(15.dp)
                        .rotate(chevronRotation)
                )
            }

            if (!node.badge.isNullOrBlank()) {
                Spacer(Modifier.height(7.dp))
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
}

/** Connector drawing: curves, arrowheads, branch labels, animated active dash. */
@Composable
private fun FlowchartEdgeCanvas(
    data: FlowchartData,
    bounds: Map<String, Rect>,
    origin: Offset?,
    orientation: FlowchartOrientation,
    activeNodeIds: Set<String>,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    val phase: Float = if (animate && activeNodeIds.isNotEmpty()) {
        val transition = rememberInfiniteTransition(label = "flow_edges")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 22f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "flow_dash_phase"
        )
        animatedPhase
    } else {
        0f
    }
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val nodeIds = remember(data) { data.nodes.mapTo(HashSet()) { it.id } }
    val edges = remember(data) {
        data.edges.filter { it.from in nodeIds && it.to in nodeIds }
    }
    val incomingSlots = remember(edges) {
        val slots = HashMap<FlowchartEdge, Pair<Int, Int>>()
        edges.groupBy { it.to }.forEach { (_, group) ->
            group.forEachIndexed { index, edge -> slots[edge] = index to group.size }
        }
        slots
    }

    Canvas(modifier) {
        if (bounds.isEmpty() || edges.isEmpty()) return@Canvas
        val originOffset = origin ?: return@Canvas

        labelPaint.textSize = 9.5.sp.toPx()
        labelPaint.typeface = android.graphics.Typeface.MONOSPACE
        labelPaint.textAlign = android.graphics.Paint.Align.CENTER

        val gap = 8.dp.toPx()
        val labelOffset = 14.dp.toPx()
        val idleStroke = 1.4.dp.toPx()
        val activeStroke = 2.dp.toPx()
        val dashIntervals = floatArrayOf(5.dp.toPx(), 6.dp.toPx())
        val geometries = ArrayList<FlowchartEdgeGeometry>(edges.size)

        edges.forEach { edge ->
            val fromRect = bounds[edge.from] ?: return@forEach
            val toRect = bounds[edge.to] ?: return@forEach
            val from = fromRect.translate(-originOffset.x, -originOffset.y)
            val to = toRect.translate(-originOffset.x, -originOffset.y)

            val (slot, slotCount) = incomingSlots[edge] ?: (0 to 1)
            val mergeShift = if (slotCount > 1) {
                (slot - (slotCount - 1) / 2f) * 12.dp.toPx()
            } else {
                0f
            }

            val start: Offset
            val end: Offset
            val control1: Offset
            val control2: Offset
            if (orientation == FlowchartOrientation.Vertical) {
                start = Offset(from.center.x, from.bottom)
                end = Offset(to.center.x + mergeShift, to.top - gap)
                val midY = (start.y + end.y) / 2f
                control1 = Offset(start.x, midY)
                control2 = Offset(end.x, midY)
            } else {
                start = Offset(from.right, from.center.y)
                end = Offset(to.left - gap, to.center.y + mergeShift)
                val midX = (start.x + end.x) / 2f
                control1 = Offset(midX, start.y)
                control2 = Offset(midX, end.y)
            }

            val active = edge.from in activeNodeIds && edge.to in activeNodeIds
            val toneTint = edge.tone.tint(colors)
            val lineColor = if (active) {
                colors.accentBlue
            } else {
                toneTint.copy(alpha = if (edge.tone == FlowchartEdgeTone.Default) 0.38f else 0.62f)
            }
            geometries.add(
                FlowchartEdgeGeometry(edge, start, end, control1, control2, lineColor, toneTint, active)
            )
        }

        geometries.forEach { geometry ->
            val path = Path().apply {
                moveTo(geometry.start.x, geometry.start.y)
                cubicTo(
                    geometry.control1.x, geometry.control1.y,
                    geometry.control2.x, geometry.control2.y,
                    geometry.end.x, geometry.end.y
                )
            }
            drawPath(
                path = path,
                color = geometry.lineColor,
                style = Stroke(
                    width = if (geometry.active) activeStroke else idleStroke,
                    cap = StrokeCap.Round,
                    pathEffect = if (geometry.active && animate) {
                        PathEffect.dashPathEffect(dashIntervals, -phase * 2f)
                    } else {
                        null
                    }
                )
            )
            val direction = run {
                val delta = geometry.end - geometry.control2
                val length = delta.getDistance().coerceAtLeast(0.001f)
                Offset(delta.x / length, delta.y / length)
            }
            drawArrowHead(
                tip = geometry.end,
                direction = direction,
                color = geometry.lineColor,
                length = 8.dp.toPx(),
                halfWidth = 4.dp.toPx()
            )
        }

        // Labels last so their pills sit above every line.
        geometries.forEach { geometry ->
            val label = geometry.edge.label?.takeIf { it.isNotBlank() } ?: return@forEach
            val mid = cubicPoint(
                geometry.start, geometry.control1, geometry.control2, geometry.end, 0.5f
            )
            val center = if (orientation == FlowchartOrientation.Vertical) {
                mid + Offset(labelOffset, 0f)
            } else {
                mid - Offset(0f, labelOffset)
            }
            drawEdgeLabel(
                text = label,
                center = center,
                tint = if (geometry.active) colors.accentBlue else geometry.toneTint,
                paint = labelPaint,
                background = colors.surface
            )
        }
    }
}

private class FlowchartEdgeGeometry(
    val edge: FlowchartEdge,
    val start: Offset,
    val end: Offset,
    val control1: Offset,
    val control2: Offset,
    val lineColor: Color,
    val toneTint: Color,
    val active: Boolean
)

private fun FlowchartEdgeTone.tint(colors: KarenColors): Color = when (this) {
    FlowchartEdgeTone.Positive -> colors.accentGreen
    FlowchartEdgeTone.Negative -> colors.accentAmber
    FlowchartEdgeTone.Warning -> colors.accentAmber
    FlowchartEdgeTone.Default -> colors.textSecondary
}

private fun DrawScope.drawArrowHead(
    tip: Offset,
    direction: Offset,
    color: Color,
    length: Float,
    halfWidth: Float
) {
    val base = tip - direction * length
    val perpendicular = Offset(-direction.y, direction.x)
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + perpendicular.x * halfWidth, base.y + perpendicular.y * halfWidth)
        lineTo(base.x - perpendicular.x * halfWidth, base.y - perpendicular.y * halfWidth)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.drawEdgeLabel(
    text: String,
    center: Offset,
    tint: Color,
    paint: android.graphics.Paint,
    background: Color
) {
    val horizontalPad = 6.dp.toPx()
    val verticalPad = 2.5.dp.toPx()
    val metrics = paint.fontMetrics
    val textHeight = metrics.descent - metrics.ascent
    val width = paint.measureText(text) + horizontalPad * 2f
    val height = textHeight + verticalPad * 2f
    val topLeft = Offset(center.x - width / 2f, center.y - height / 2f)
    val radius = CornerRadius(height / 2f, height / 2f)

    drawRoundRect(
        color = background,
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = radius
    )
    drawRoundRect(
        color = tint.copy(alpha = 0.45f),
        topLeft = topLeft,
        size = Size(width, height),
        cornerRadius = radius,
        style = Stroke(width = 1.dp.toPx())
    )

    paint.color = tint.toArgb()
    drawContext.canvas.nativeCanvas.drawText(
        text,
        center.x,
        center.y - (metrics.ascent + metrics.descent) / 2f,
        paint
    )
}

private fun cubicPoint(
    p0: Offset,
    p1: Offset,
    p2: Offset,
    p3: Offset,
    t: Float
): Offset {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val c = 3f * u * t * t
    val d = t * t * t
    return Offset(
        a * p0.x + b * p1.x + c * p2.x + d * p3.x,
        a * p0.y + b * p1.y + c * p2.y + d * p3.y
    )
}

/**
 * Longest-path layering: every node sits one step below its deepest
 * predecessor, so decisions fan out on the same row and rejoin cleanly.
 */
private fun computeFlowLayers(data: FlowchartData): List<List<FlowchartNode>> {
    if (data.nodes.isEmpty()) return emptyList()
    val order = data.nodes.mapIndexed { index, node -> node.id to index }.toMap()
    val ids = order.keys
    val edges = data.edges.filter { it.from in ids && it.to in ids }
    val indegree = ids.associateWith { 0 }.toMutableMap()
    edges.forEach { edge -> indegree[edge.to] = (indegree[edge.to] ?: 0) + 1 }
    val layer = ids.associateWith { 0 }.toMutableMap()
    val queue = ArrayDeque<String>()
    indegree.filterValues { it == 0 }.keys
        .sortedBy { order[it] ?: 0 }
        .forEach { queue.addLast(it) }
    val visited = mutableSetOf<String>()

    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!visited.add(id)) continue
        edges.filter { it.from == id }.forEach { edge ->
            layer[edge.to] = maxOf(layer[edge.to] ?: 0, (layer[id] ?: 0) + 1)
            indegree[edge.to] = (indegree[edge.to] ?: 1) - 1
            if ((indegree[edge.to] ?: 0) <= 0) queue.addLast(edge.to)
        }
    }

    // Cycles / detached nodes: park them after their deepest known predecessor.
    data.nodes.filter { it.id !in visited }.forEach { node ->
        val predecessors = edges.filter { it.to == node.id }.mapNotNull { layer[it.from] }
        layer[node.id] = (predecessors.maxOrNull() ?: -1) + 1
    }

    return data.nodes
        .groupBy { layer[it.id] ?: 0 }
        .toSortedMap()
        .values
        .map { row -> row.sortedBy { order[it.id] ?: 0 } }
}

/** Shared chrome for loading / empty / error. */
@Composable
private fun FlowStatePanel(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = LocalKarenColors.current.accentBlue,
    loading: Boolean = false
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = tint,
                strokeWidth = 2.dp,
                modifier = Modifier.size(24.dp)
            )
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            color = colors.textPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            color = colors.textMuted,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center
        )
    }
}
