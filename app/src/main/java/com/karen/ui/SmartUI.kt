package com.karen.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------------------
// Charts (SmartUI-3 §2) — pure Canvas, no deps. Each has a table fallback.
// ---------------------------------------------------------------------------

/**
 * Line chart with area fill, grid lines, Y-axis labels, and touch tooltip.
 * Points are normalised to 0..1. `labels` shown under evenly spaced ticks (max 6).
 */
@Composable
fun KLineChart(
    points: List<Float>,
    labels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    lineColor: Color = LocalKarenColors.current.accentGreen,
    fillColor: Color = LocalKarenColors.current.accentGreen.copy(alpha = 0.12f),
    height: androidx.compose.ui.unit.Dp = 140.dp,
    showGrid: Boolean = true,
    showYLabels: Boolean = true,
    yLabelCount: Int = 4
) {
    val colors = LocalKarenColors.current
    if (points.isEmpty()) return
    val maxVal = points.max().takeIf { it > 0f } ?: 1f
    val animated = points.mapIndexed { i, p ->
        val state = remember { mutableStateOf(0f) }
        LaunchedEffect(Unit) { state.value = p }
        val v by animateFloatAsState(state.value, tween(600 + i * 40), label = "kLine$i")
        v
    }
    var touchIndex by remember { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .padding(12.dp)
                .pointerInput(points) {
                    detectTapGestures { offset ->
                        val n = animated.size
                        if (n < 2) return@detectTapGestures
                        val stepX = size.width / (n - 1)
                        touchIndex = ((offset.x / stepX).toInt()).coerceIn(0, n - 1)
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val n = animated.size
            if (n < 2) return@Canvas
            val stepX = w / (n - 1)
            // Grid lines + Y labels.
            if (showGrid) {
                for (g in 0..yLabelCount) {
                    val y = h * g / yLabelCount
                    drawLine(colors.border.copy(alpha = 0.4f), Offset(0f, y), Offset(w, y), strokeWidth = 0.5f)
                }
            }
            val path = Path().apply {
                moveTo(0f, h - (animated[0] / maxVal) * h)
                for (i in 1 until n) lineTo(i * stepX, h - (animated[i] / maxVal) * h)
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(w, h)
                lineTo(0f, h)
                close()
            }
            drawPath(fill, fillColor)
            drawPath(path, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
            // End dot.
            val lastX = (n - 1) * stepX
            val lastY = h - (animated.last() / maxVal) * h
            drawCircle(lineColor, 5f, Offset(lastX, lastY))
            // Touch crosshair.
            touchIndex?.let { ti ->
                val cx = ti * stepX
                val cy = h - (animated[ti] / maxVal) * h
                drawLine(lineColor.copy(alpha = 0.4f), Offset(cx, 0f), Offset(cx, h), strokeWidth = 1f)
                drawCircle(lineColor, 6f, Offset(cx, cy))
                drawCircle(Color.White, 3f, Offset(cx, cy))
            }
        }
        // Y-axis labels.
        if (showYLabels) {
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                (0..yLabelCount).reversed().forEach { g ->
                    val v = maxVal * g / yLabelCount
                    Text(
                        if (v >= 1000) "${(v / 1000).toInt()}k" else "${v.toInt()}",
                        color = colors.textMuted,
                        fontFamily = LocalKarenType.current.mono,
                        fontSize = 8.sp
                    )
                }
            }
        }
        // X labels.
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.take(6).forEach {
                    Text(
                        it,
                        color = colors.textMuted,
                        fontFamily = LocalKarenType.current.mono,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        // Touch tooltip.
        touchIndex?.let { ti ->
            if (ti in points.indices) {
                Spacer(Modifier.height(4.dp))
                val label = labels.getOrElse(ti) { "Point ${ti + 1}" }
                Text(
                    "$label: ${points[ti].toInt()}",
                    color = lineColor,
                    fontFamily = LocalKarenType.current.mono,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * Vertical bar chart with value labels on top, grid lines, and rounded bars.
 * `labels` align under bars (max 8).
 */
@Composable
fun KBarChart(
    values: List<Float>,
    labels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    barColor: Color = LocalKarenColors.current.accentGreen,
    height: androidx.compose.ui.unit.Dp = 140.dp,
    showValues: Boolean = true,
    showGrid: Boolean = true
) {
    val colors = LocalKarenColors.current
    if (values.isEmpty()) return
    val maxV = values.max().takeIf { it > 0f } ?: 1f
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            // Grid lines drawn behind bars.
            if (showGrid) {
                Canvas(Modifier.fillMaxSize()) {
                    for (g in 0..4) {
                        val y = size.height * g / 4
                        drawLine(
                            colors.border.copy(alpha = 0.3f),
                            Offset(0f, y), Offset(size.width, y),
                            strokeWidth = 0.5f
                        )
                    }
                }
            }
            values.take(8).forEach { v ->
                val frac = (v / maxV).coerceIn(0f, 1f)
                val h by animateFloatAsState(frac, tween(500), label = "kBar")
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(h),
                    verticalArrangement = Arrangement.Bottom,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (showValues && frac > 0.15f) {
                        Text(
                            "${v.toInt()}",
                            color = colors.textPrimary,
                            fontFamily = LocalKarenType.current.mono,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 3.dp)
                            .weight(1f)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(barColor)
                    )
                }
            }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                labels.take(8).forEach {
                    Text(
                        it,
                        color = colors.textMuted,
                        fontFamily = LocalKarenType.current.mono,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Donut chart (pie with a hole). `slices` are (label, value, color).
 * Shows a legend with values + percentages. Optional center total label.
 */
@Composable
fun KPieChart(
    slices: List<Triple<String, Float, Color>>,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 120.dp,
    centerLabel: String? = null,
    valueFormatter: (Float) -> String = { it.toInt().toString() }
) {
    val colors = LocalKarenColors.current
    if (slices.isEmpty()) return
    val total = slices.sumOf { it.second.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val pad = 8f
                val arcSize = Size(size.toPx() - pad * 2, size.toPx() - pad * 2)
                var startAngle = -90f
                slices.forEach { (_, value, color) ->
                    val sweep = (value / total) * 360f
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(pad, pad),
                        size = arcSize,
                        style = Stroke(width = 18f, cap = StrokeCap.Butt)
                    )
                    startAngle += sweep
                }
            }
            if (centerLabel != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        centerLabel,
                        color = colors.textPrimary,
                        fontFamily = LocalKarenType.current.mono,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        "total",
                        color = colors.textMuted,
                        fontSize = 9.sp
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            slices.forEach { (label, value, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${valueFormatter(value)} · ${((value / total) * 100).toInt()}%",
                        color = colors.textMuted,
                        fontFamily = LocalKarenType.current.mono,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// KPI / Statistic cards (SmartUI-3 §4) — metric with trend + sparkline.
// ---------------------------------------------------------------------------

/**
 * KPI card: label, big value, optional icon, subtitle, trend arrow and
 * delta, plus a mini sparkline. Used in dashboards and summary rows.
 */
@Composable
fun KpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trend: Float? = null,
    spark: List<Float> = emptyList(),
    tone: KTone = KTone.Primary,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val colors = LocalKarenColors.current
    val tint = tone.color(colors)
    Column(
        modifier
            .fillMaxWidth()
            .clip(KR.card)
            .background(colors.cardBackground)
            .border(1.dp, colors.cardBorder, KR.card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(tint.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    label.uppercase(),
                    color = colors.textMuted,
                    fontFamily = LocalKarenType.current.mono,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = colors.textMuted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trend != null) {
                val up = trend >= 0
                Icon(
                    if (up) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                    contentDescription = if (up) "Up" else "Down",
                    tint = if (up) colors.accentGreen else colors.accentRed,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "${if (up) "+" else ""}${(trend * 100).toInt()}%",
                    color = if (up) colors.accentGreen else colors.accentRed,
                    fontFamily = LocalKarenType.current.mono,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            value,
            color = tint,
            fontFamily = LocalKarenType.current.mono,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        if (spark.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            KLineChart(
                points = spark,
                height = 36.dp,
                lineColor = tint,
                fillColor = Color.Transparent
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Data table (SmartUI-3 §10) — sortable, with sticky header row.
// ---------------------------------------------------------------------------

/**
 * Sortable data table. Tap a header to sort asc/desc. `columns` define
 * header labels and cell content. Rows are (id, cells) pairs.
 * Optional zebra striping and row numbers.
 */
@Composable
fun KDataTable(
    columns: List<String>,
    rows: List<Pair<String, List<String>>>,
    modifier: Modifier = Modifier,
    sortColumn: Int = -1,
    sortAsc: Boolean = true,
    onSort: (Int, Boolean) -> Unit = { _, _ -> },
    zebra: Boolean = true,
    rowNumbers: Boolean = false
) {
    val colors = LocalKarenColors.current
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
    ) {
        // Header.
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceHover.copy(alpha = 0.5f))
                .horizontalScroll(scroll)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (rowNumbers) {
                Text(
                    "#",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(28.dp)
                )
            }
            columns.forEachIndexed { ci, col ->
                val sorted = ci == sortColumn
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onSort(ci, if (sorted) !sortAsc else true) }
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        col,
                        color = if (sorted) colors.accentGreen else colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (sorted) {
                        Icon(
                            if (sortAsc) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = if (sortAsc) "Ascending" else "Descending",
                            tint = colors.accentGreen,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
        KHairline()
        // Rows.
        if (rows.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No data",
                    color = colors.textMuted,
                    fontSize = 12.sp
                )
            }
        } else {
            rows.forEachIndexed { ri, (id, cells) ->
                val bg = if (zebra && ri % 2 == 1) colors.surfaceHover.copy(alpha = 0.3f) else Color.Transparent
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(bg)
                        .horizontalScroll(scroll)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rowNumbers) {
                        Text(
                            "${ri + 1}",
                            color = colors.textMuted,
                            fontFamily = LocalKarenType.current.mono,
                            fontSize = 10.sp,
                            modifier = Modifier.width(28.dp)
                        )
                    }
                    cells.forEachIndexed { ci, cell ->
                        Text(
                            cell,
                            color = if (ci == 0) colors.textPrimary else colors.textSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                KHairline()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Agent task timeline (SmartUI-3 §8) — progress with step states.
// ---------------------------------------------------------------------------

/**
 * Agent task timeline: a vertical list of steps with status icons
 * (done / active / pending), optional timestamps, and a progress bar.
 */
@Composable
fun KTaskTimeline(
    steps: List<Triple<String, String, Int>>, // (label, detail, status) — 0=done 1=active 2=pending
    progress: Float? = null,
    modifier: Modifier = Modifier,
    timestamps: List<String> = emptyList()
) {
    val colors = LocalKarenColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(KR.card)
            .background(colors.cardBackground)
            .border(1.dp, colors.cardBorder, KR.card)
            .padding(14.dp)
    ) {
        if (progress != null) {
            KProgressBar(progress)
            Spacer(Modifier.height(10.dp))
        }
        steps.forEachIndexed { i, (label, detail, status) ->
            Row(verticalAlignment = Alignment.Top) {
                // Status icon + connector.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val (icon, tint) = when (status) {
                        0 -> Icons.Default.Check to colors.accentGreen
                        1 -> Icons.Default.RadioButtonChecked to colors.accentAmber
                        else -> Icons.Default.RadioButtonUnchecked to colors.textMuted
                    }
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(tint.copy(alpha = if (status == 2) 0.08f else 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (status == 1) {
                            Icon(Icons.Default.Autorenew, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                        } else {
                            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                        }
                    }
                    if (i < steps.lastIndex) {
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(20.dp)
                                .background(
                                    if (status == 0) colors.accentGreen.copy(alpha = 0.4f)
                                    else colors.border
                                )
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            label,
                            color = if (status == 2) colors.textMuted else colors.textPrimary,
                            fontSize = 13.sp,
                            fontWeight = if (status == 1) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (i < timestamps.size && timestamps[i].isNotBlank()) {
                            Text(
                                timestamps[i],
                                color = colors.textMuted,
                                fontFamily = LocalKarenType.current.mono,
                                fontSize = 9.sp
                            )
                        }
                    }
                    if (detail.isNotBlank()) {
                        Text(
                            detail,
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (i < steps.lastIndex) Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Media / image viewer (SmartUI-3 §6) — grid + full-screen viewer.
// ---------------------------------------------------------------------------

/**
 * Image grid with a full-screen viewer on tap. `images` are (id, url, label).
 * Uses Coil if available, else a placeholder box.
 */
@Composable
fun KImageGrid(
    images: List<Triple<String, String, String>>,
    modifier: Modifier = Modifier,
    columns: Int = 3
) {
    val colors = LocalKarenColors.current
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxWidth()) {
        images.chunked(columns).forEach { rowItems ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEachIndexed { _, (id, url, label) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceHover)
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                            .clickable { viewerIndex = images.indexOfFirst { it.first == id } },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = label,
                            tint = colors.textMuted,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
                // Pad the last row if it's short.
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    // Full-screen viewer.
    val idx = viewerIndex
    if (idx != null && idx in images.indices) {
        val (id, url, label) = images[idx]
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable { viewerIndex = null },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Image, contentDescription = label, tint = Color.White, modifier = Modifier.size(64.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(label, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text("Tap anywhere to close", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state (SmartUI-3 §15) — icon, message, optional action.
// ---------------------------------------------------------------------------

/**
 * Empty state: centred icon, title, message, and an optional action button.
 * `variant` controls the icon tint: Info (muted), Warning (amber), Error (red).
 */
@Composable
fun KEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String = "",
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    variant: KEmptyVariant = KEmptyVariant.Info
) {
    val colors = LocalKarenColors.current
    val tint = when (variant) {
        KEmptyVariant.Info -> colors.textMuted
        KEmptyVariant.Warning -> colors.accentAmber
        KEmptyVariant.Error -> colors.accentRed
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.1f))
                .border(1.dp, tint.copy(alpha = 0.3f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (message.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(message, color = colors.textMuted, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            KSecondaryButton(actionLabel, onAction)
        }
    }
}

/** Visual variant for [KEmptyState]. */
enum class KEmptyVariant { Info, Warning, Error }

// ---------------------------------------------------------------------------
// Skeleton loader (SmartUI-3 §15) — shimmering placeholder blocks.
// ---------------------------------------------------------------------------

/**
 * Skeleton loader: shimmering placeholder blocks. `layout` picks a preset
 * shape — List (text lines), Card (title + lines), Chart (bars), or Table.
 */
@Composable
fun KSkeletonLoader(
    modifier: Modifier = Modifier,
    rows: Int = 3,
    layout: KSkeletonLayout = KSkeletonLayout.List
) {
    val colors = LocalKarenColors.current
    val shimmer = remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            shimmer.value = (shimmer.value + 0.02f) % 1f
            kotlinx.coroutines.delay(30)
        }
    }
    val alpha = 0.15f + 0.1f * sin(shimmer.value * Math.PI).toFloat()
    Column(
        modifier
            .fillMaxWidth()
            .clip(KR.card)
            .background(colors.cardBackground)
            .border(1.dp, colors.cardBorder, KR.card)
            .padding(14.dp)
    ) {
        when (layout) {
            KSkeletonLayout.List -> {
                repeat(rows) { i ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(if (i == 0) 20.dp else 14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceHover.copy(alpha = alpha + 0.1f))
                    )
                    if (i < rows - 1) Spacer(Modifier.height(10.dp))
                }
            }
            KSkeletonLayout.Card -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceHover.copy(alpha = alpha + 0.15f))
                )
                Spacer(Modifier.height(10.dp))
                repeat(rows) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfaceHover.copy(alpha = alpha + 0.08f))
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            KSkeletonLayout.Chart -> {
                Row(
                    Modifier.fillMaxWidth().height(80.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    repeat(5) { i ->
                        val h = 0.3f + 0.7f * ((i * 37 % 10) / 10f)
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(h)
                                .padding(horizontal = 3.dp)
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(colors.surfaceHover.copy(alpha = alpha + 0.1f))
                        )
                    }
                }
            }
            KSkeletonLayout.Table -> {
                repeat(rows + 1) { i ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(3) { c ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(if (i == 0) 14.dp else 12.dp)
                                    .padding(horizontal = 4.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(
                                        colors.surfaceHover.copy(
                                            alpha = if (i == 0) alpha + 0.15f else alpha + 0.06f
                                        )
                                    )
                            )
                        }
                    }
                    if (i < rows) Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** Preset skeleton layouts. */
enum class KSkeletonLayout { List, Card, Chart, Table }

// ---------------------------------------------------------------------------
// Search result card (SmartUI-3 §9) — title, snippet, source, freshness.
// ---------------------------------------------------------------------------

/**
 * Search result card: favicon, title, snippet, source domain, and a
 * freshness badge. Used in web search results and research panels.
 */
@Composable
fun KSearchResultCard(
    title: String,
    snippet: String,
    source: String,
    modifier: Modifier = Modifier,
    freshness: String? = null,
    onClick: (() -> Unit)? = null,
    favicon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val colors = LocalKarenColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(KR.card)
            .background(colors.cardBackground)
            .border(1.dp, colors.cardBorder, KR.card)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (favicon != null) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.surfaceHover),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(favicon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                source,
                color = colors.accentGreen,
                fontFamily = LocalKarenType.current.mono,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.accentGreen.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (freshness != null) {
                Spacer(Modifier.width(6.dp))
                val (freshColor, freshBg) = when {
                    freshness.contains("hour", true) || freshness.contains("min", true) ->
                        colors.accentGreen to colors.accentGreen.copy(alpha = 0.12f)
                    freshness.contains("day", true) || freshness.contains("week", true) ->
                        colors.accentAmber to colors.accentAmber.copy(alpha = 0.12f)
                    else ->
                        colors.textMuted to colors.surfaceHover
                }
                Text(
                    freshness,
                    color = freshColor,
                    fontFamily = LocalKarenType.current.mono,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(freshBg)
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(snippet, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------
// Rating input (SmartUI-3 §3) — star rating with interactive selection.
// ---------------------------------------------------------------------------

/**
 * Star rating input. `value` is 0..max. Tap a star to set the rating.
 * Optional label shows the current selection.
 */
@Composable
fun KRatingInput(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 5,
    label: String? = null
) {
    val colors = LocalKarenColors.current
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..max).forEach { star ->
                val filled = star <= value
                Icon(
                    if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = "Rate $star",
                    tint = if (filled) colors.accentAmber else colors.textMuted,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange(if (star == value) 0 else star) }
                        .padding(2.dp)
                )
            }
        }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (value > 0) "$label: $value/$max" else label,
                color = colors.textMuted,
                fontSize = 11.sp
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Accordion (SmartUI-3 §16) — expandable section with chevron.
// ---------------------------------------------------------------------------

/**
 * Expandable accordion section. Tap the header to toggle.
 * Optional icon and smooth expand/collapse animation.
 */
@Composable
fun KAccordion(
    title: String,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null
) {
    val colors = LocalKarenColors.current
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val anim by animateFloatAsState(if (expanded) 1f else 0f, tween(250), label = "acc")
    Column(
        modifier
            .fillMaxWidth()
            .clip(KR.card)
            .background(colors.cardBackground)
            .border(1.dp, colors.cardBorder, KR.card)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = colors.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }
        if (anim > 0f) {
            KHairline()
            Box(
                Modifier
                    .fillMaxWidth(anim)
                    .padding(14.dp)
            ) { content() }
        }
    }
}

// ---------------------------------------------------------------------------
// Progress ring (SmartUI-3 §5) — circular progress with center label.
// ---------------------------------------------------------------------------

/**
 * Circular progress ring with an optional center label (e.g. percentage).
 * `progress` is 0..1.
 */
@Composable
fun KProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 80.dp,
    strokeWidth: androidx.compose.ui.unit.Dp = 8.dp,
    color: Color = LocalKarenColors.current.accentGreen,
    trackColor: Color = LocalKarenColors.current.surfaceHover,
    centerLabel: String? = null
) {
    val colors = LocalKarenColors.current
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val pad = strokeWidth.toPx() / 2
            val arcSize = Size(size.toPx() - pad * 2, size.toPx() - pad * 2)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animated,
                useCenter = false,
                topLeft = Offset(pad, pad),
                size = arcSize,
                style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
            )
        }
        if (centerLabel != null) {
            Text(
                centerLabel,
                color = colors.textPrimary,
                fontFamily = LocalKarenType.current.mono,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Horizontal bar chart (SmartUI-3 §2b) — labeled bars with values.
// ---------------------------------------------------------------------------

/**
 * Horizontal bar chart. Each row: label on the left, bar + value on the right.
 * Good for rankings and comparisons with long labels.
 */
@Composable
fun KHorizontalBarChart(
    dataPoints: List<SmartUIDataPoint>,
    modifier: Modifier = Modifier,
    barColor: Color = LocalKarenColors.current.accentGreen,
    maxBarWidth: Float = 0.65f
) {
    val colors = LocalKarenColors.current
    if (dataPoints.isEmpty()) return
    val maxV = dataPoints.maxOf { it.value }.takeIf { it > 0f } ?: 1f
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        dataPoints.take(8).forEach { dp ->
            val frac = (dp.value / maxV).coerceIn(0f, 1f)
            val w by animateFloatAsState(frac, tween(500), label = "hbar")
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        dp.label,
                        color = colors.textPrimary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Text(
                        "${dp.value.toInt()}",
                        color = colors.textMuted,
                        fontFamily = LocalKarenType.current.mono,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth(maxBarWidth * w)
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(barColor)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Badge (SmartUI-3 §12) — small status pill.
// ---------------------------------------------------------------------------

/**
 * Small status badge / pill. `tone` controls the color.
 */
@Composable
fun KBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: KTone = KTone.Neutral
) {
    val colors = LocalKarenColors.current
    val tint = tone.color(colors)
    Text(
        text,
        color = tint,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = LocalKarenType.current.mono,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

// ---------------------------------------------------------------------------
// SmartUI Dynamic Analysis Engine
// Analyzes user input text and generates appropriate visualizations.
// ---------------------------------------------------------------------------

/** A single detected data point: label + numeric value. */
data class SmartUIDataPoint(val label: String, val value: Float)

/** Types of visuals the engine can produce. */
enum class SmartUIVisualType { BAR_CHART, PIE_CHART, LINE_CHART, KPI_CARDS, DATA_TABLE, TIMELINE }

/** A detected visual with its data and display metadata. */
data class SmartUIVisual(
    val type: SmartUIVisualType,
    val title: String,
    val dataPoints: List<SmartUIDataPoint> = emptyList(),
    val columns: List<String> = emptyList(),
    val rows: List<Pair<String, List<String>>> = emptyList(),
    val kpiLabels: List<String> = emptyList(),
    val kpiValues: List<String> = emptyList(),
    val timelineSteps: List<String> = emptyList()
)

/** Result of analyzing a user prompt. */
data class SmartUIAnalysis(
    val visuals: List<SmartUIVisual>,
    val summary: String
)

/**
 * Analyzes free-form user input and detects data patterns, then produces
 * appropriate SmartUI visualizations. Works entirely on-device — no network.
 *
 * Detection rules:
 *  - "compare / vs / versus" + ≥2 labeled numbers  → bar chart
 *  - "percent / % / share / proportion" + numbers  → pie chart
 *  - "trend / over time / growth / monthly" + nums  → line chart
 *  - "rank / top / best / worst" + numbered list    → bar chart (sorted)
 *  - "table / list / details" + structured rows     → data table
 *  - "steps / process / timeline / first…then"      → timeline
 *  - single number or "how many / total"            → KPI cards
 */
object SmartUIEngine {

    private val numberPattern = Regex("""(\d[\d,]*(?:\.\d+)?)\s*(%|percent|gb|mb|kb|mb/s|ms|s|sec|min|hr|hours?|days?|weeks?|months?|years?|x|k|m|b)?|(\d[\d,]*(?:\.\d+)?)\s*(%|percent)""", RegexOption.IGNORE_CASE)
    private val labelValuePattern = Regex("""([A-Za-z][A-Za-z0-9 _\-/]{1,30}?)\s*[:=]\s*(\d[\d,]*(?:\.\d+)?)\s*(%|percent|gb|mb|kb|ms|s|sec|min|hr|hours?|days?|weeks?|months?|years?|x|k|m|b)?""", RegexOption.IGNORE_CASE)
    private val comparePattern = Regex("""([A-Za-z][A-Za-z0-9 _\-/]{1,20}?)\s+(?:is|are|has|got|with|at)\s+(\d[\d,]*(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    fun analyze(input: String): SmartUIAnalysis {
        val text = input.trim()
        if (text.length < 3) return SmartUIAnalysis(emptyList(), "No data detected in input.")

        val lower = text.lowercase()
        val visuals = mutableListOf<SmartUIVisual>()

        // --- KPI cards: standalone numbers with context ---
        val kpiMatches = numberPattern.findAll(text).toList()
        if (kpiMatches.size == 1 && visuals.isEmpty()) {
            val m = kpiMatches.first()
            val value = m.groupValues[1].replace(",", "").toFloatOrNull() ?: 0f
            val unit = m.groupValues[2].ifBlank { m.groupValues[4] }
            val label = extractContextLabel(text, m.range.first)
            visuals.add(
                SmartUIVisual(
                    type = SmartUIVisualType.KPI_CARDS,
                    title = "Key metric",
                    kpiLabels = listOf(label),
                    kpiValues = listOf(formatValue(value, unit))
                )
            )
        }

        // --- Labeled data points (Label: value) ---
        val labeled = labelValuePattern.findAll(text).mapNotNull { m ->
            val label = m.groupValues[1].trim()
            val value = m.groupValues[2].replace(",", "").toFloatOrNull() ?: return@mapNotNull null
            val unit = m.groupValues[3]
            SmartUIDataPoint(label, value) to unit
        }.toList()

        // --- Comparison pattern (X is/has N) ---
        val compared = comparePattern.findAll(text).mapNotNull { m ->
            val label = m.groupValues[1].trim()
            val value = m.groupValues[2].replace(",", "").toFloatOrNull() ?: return@mapNotNull null
            SmartUIDataPoint(label, value) to ""
        }.toList()

        val allPoints = (labeled + compared).distinctBy { it.first.label }.map { it.first }
        val units = (labeled + compared).map { it.second }.filter { it.isNotBlank() }.distinct()
        val unit = units.firstOrNull() ?: ""

        // --- Pie chart: percentages / proportions ---
        if (lower.containsAny("percent", "%", "share", "proportion", "ratio", "distribution", "breakdown")) {
            val pctPoints = allPoints.filter { it.value in 0f..100f }
            if (pctPoints.size >= 2) {
                visuals.add(
                    SmartUIVisual(
                        type = SmartUIVisualType.PIE_CHART,
                        title = "Distribution",
                        dataPoints = pctPoints
                    )
                )
            }
        }

        // --- Line chart: trends over time ---
        if (lower.containsAny("trend", "over time", "growth", "monthly", "weekly", "daily", "history", "progress", "timeline of")) {
            if (allPoints.size >= 2) {
                visuals.add(
                    SmartUIVisual(
                        type = SmartUIVisualType.LINE_CHART,
                        title = "Trend",
                        dataPoints = allPoints
                    )
                )
            }
        }

        // --- Bar chart: comparisons / rankings ---
        if (lower.containsAny("compare", "vs", "versus", "comparison", "rank", "top", "best", "worst", "leaderboard", "benchmark")) {
            if (allPoints.size >= 2) {
                val sorted = allPoints.sortedByDescending { it.value }
                visuals.add(
                    SmartUIVisual(
                        type = SmartUIVisualType.BAR_CHART,
                        title = if (lower.containsAny("rank", "top", "best", "worst")) "Ranking" else "Comparison",
                        dataPoints = sorted
                    )
                )
            }
        }

        // --- Data table: structured rows ---
        if (lower.containsAny("table", "details", "breakdown by", "list of", "report")) {
            if (allPoints.size >= 2) {
                visuals.add(
                    SmartUIVisual(
                        type = SmartUIVisualType.DATA_TABLE,
                        title = "Details",
                        columns = listOf("Item", "Value"),
                        rows = allPoints.map { dp -> dp.label to listOf(formatValue(dp.value, unit)) }
                    )
                )
            }
        }

        // --- Timeline: process steps ---
        if (lower.containsAny("steps", "process", "how to", "first", "then", "finally", "phase", "stage")) {
            val steps = extractSteps(text)
            if (steps.size >= 2) {
                visuals.add(
                    SmartUIVisual(
                        type = SmartUIVisualType.TIMELINE,
                        title = "Process",
                        timelineSteps = steps
                    )
                )
            }
        }

        // --- Fallback: if we have data points but no visual matched, default to bar chart ---
        if (visuals.isEmpty() && allPoints.size >= 2) {
            visuals.add(
                SmartUIVisual(
                    type = SmartUIVisualType.BAR_CHART,
                    title = "Data",
                    dataPoints = allPoints
                )
            )
        }

        val summary = when {
            visuals.isEmpty() && allPoints.isEmpty() -> "No numerical data detected. Try including numbers with labels, e.g. 'Sales: 42, Support: 18'."
            visuals.isEmpty() -> "Found ${allPoints.size} data point(s) but no clear visualization intent."
            else -> "Detected ${visuals.size} visualization(s) from your input."
        }

        return SmartUIAnalysis(visuals, summary)
    }

    private fun extractContextLabel(text: String, position: Int): String {
        val before = text.substring(0, position).trim()
        val words = before.split(Regex("\\s+")).takeLast(3)
        return words.joinToString(" ").ifBlank { "Value" }
    }

    private fun extractSteps(text: String): List<String> {
        val stepPattern = Regex("""(?:^|\n|\.)\s*(?:\d+[.)]|step\s*\d+|[-•])\s*(.+?)(?=\n|$|\d+[.)]|step\s*\d+)""", RegexOption.IGNORE_CASE)
        return stepPattern.findAll(text).map { it.groupValues[1].trim() }.toList()
    }

    private fun formatValue(value: Float, unit: String): String {
        val formatted = if (value == value.toLong().toFloat()) value.toLong().toString() else "%.1f".format(value)
        return if (unit.isNotBlank()) "$formatted $unit" else formatted
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { this.contains(it) }
}

/**
 * Renders a SmartUIAnalysis result. Place this wherever the user input
 * was received (chat, notes, etc.) to show generated visuals inline.
 */
@Composable
fun SmartUIRenderer(
    analysis: SmartUIAnalysis,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (analysis.visuals.isEmpty()) {
            KEmptyState(
                icon = Icons.Default.BarChart,
                title = "No visualization",
                message = analysis.summary
            )
            return@Column
        }

        analysis.visuals.forEach { visual ->
            when (visual.type) {
                SmartUIVisualType.BAR_CHART -> {
                    Section(title = visual.title) {
                        KBarChart(
                            values = visual.dataPoints.map { it.value },
                            labels = visual.dataPoints.map { it.label }
                        )
                    }
                }
                SmartUIVisualType.PIE_CHART -> {
                    Section(title = visual.title) {
                        val palette = listOf(
                            colors.accentGreen, colors.accentBlue, colors.accentAmber,
                            androidx.compose.ui.graphics.Color(0xFF8B5CF6),
                            androidx.compose.ui.graphics.Color(0xFFEC4899),
                            androidx.compose.ui.graphics.Color(0xFF06B6D4),
                            androidx.compose.ui.graphics.Color(0xFFF97316),
                        )
                        val slices = visual.dataPoints.mapIndexed { i, dp ->
                            Triple(dp.label, dp.value, palette[i % palette.size])
                        }
                        KPieChart(slices)
                    }
                }
                SmartUIVisualType.LINE_CHART -> {
                    Section(title = visual.title) {
                        KLineChart(
                            points = visual.dataPoints.map { it.value },
                            labels = visual.dataPoints.map { it.label }
                        )
                    }
                }
                SmartUIVisualType.KPI_CARDS -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        visual.kpiLabels.forEachIndexed { i, label ->
                            KpiCard(
                                label = label,
                                value = visual.kpiValues.getOrElse(i) { "—" },
                                modifier = Modifier.weight(1f),
                                tone = KTone.Primary
                            )
                        }
                    }
                }
                SmartUIVisualType.DATA_TABLE -> {
                    Section(title = visual.title) {
                        KDataTable(
                            columns = visual.columns,
                            rows = visual.rows
                        )
                    }
                }
                SmartUIVisualType.TIMELINE -> {
                    Section(title = visual.title) {
                        visual.timelineSteps.forEachIndexed { i, step ->
                            StepRow(i + 1, step, i < visual.timelineSteps.size - 1)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Flowchart (ChatGPT Premium style) — nodes with icons, connected by lines.
// ---------------------------------------------------------------------------

/** A flowchart node: title, description, icon, and visual type. */
data class KFlowNode(
    val title: String,
    val subtitle: String = "",
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val type: KFlowNodeType = KFlowNodeType.Process
)

/** Visual style for a flowchart node. */
enum class KFlowNodeType { Start, Process, Decision, End }

/**
 * ChatGPT Premium-style flowchart. Vertical nodes with icons, connected by
 * animated lines. Decision nodes get a diamond accent.
 */
@Composable
fun KFlowchart(
    nodes: List<KFlowNode>,
    modifier: Modifier = Modifier,
    animate: Boolean = true
) {
    val colors = LocalKarenColors.current
    if (nodes.isEmpty()) return
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        nodes.forEachIndexed { i, node ->
            val nodeColor = when (node.type) {
                KFlowNodeType.Start -> colors.accentGreen
                KFlowNodeType.Process -> colors.accentBlue
                KFlowNodeType.Decision -> colors.accentAmber
                KFlowNodeType.End -> colors.accentGreen
            }
            Row(verticalAlignment = Alignment.Top) {
                // Icon column with connector.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(nodeColor.copy(alpha = 0.12f))
                            .border(1.5.dp, nodeColor.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (node.icon != null) {
                            Icon(
                                node.icon,
                                contentDescription = null,
                                tint = nodeColor,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Text(
                                "${i + 1}",
                                color = nodeColor,
                                fontFamily = LocalKarenType.current.mono,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Connector line to next node.
                    if (i < nodes.lastIndex) {
                        val lineH by animateFloatAsState(if (animate) 1f else 0f, tween(400 + i * 150), label = "fc$i")
                        Box(
                            Modifier
                                .width(2.dp)
                                .height((36 * lineH).dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(nodeColor.copy(alpha = 0.35f))
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                // Content.
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            node.title,
                            color = colors.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (node.type == KFlowNodeType.Decision) {
                            Spacer(Modifier.width(6.dp))
                            KBadge("Decision", tone = KTone.Amber)
                        }
                    }
                    if (node.subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            node.subtitle,
                            color = colors.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
            if (i < nodes.lastIndex) Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Question card — interactive Yes/No or multiple choice, ChatGPT style.
// ---------------------------------------------------------------------------

/**
 * Interactive question card. Shows a question with tappable option buttons.
 * `options` are the choices; tapping one calls `onAnswer` with the index.
 * Yes/No is just options = listOf("Yes", "No").
 */
@Composable
fun KQuestionCard(
    question: String,
    options: List<String>,
    onAnswer: (Int) -> Unit,
    modifier: Modifier = Modifier,
    selectedIndex: Int = -1,
    multiSelect: Boolean = false
) {
    val colors = LocalKarenColors.current
    var selection by remember { mutableStateOf(selectedIndex) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            question,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        options.forEachIndexed { i, option ->
            val selected = if (multiSelect) selection == i else selection == i
            val bg = if (selected) colors.accentGreen.copy(alpha = 0.08f) else Color.Transparent
            val borderC = if (selected) colors.accentGreen.copy(alpha = 0.4f) else colors.border
            val textC = if (selected) colors.accentGreen else colors.textSecondary
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .border(1.dp, borderC, RoundedCornerShape(8.dp))
                    .clickable {
                        selection = i
                        onAnswer(i)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(
                            1.5.dp,
                            if (selected) colors.accentGreen else colors.border,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = colors.accentGreen,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    option,
                    color = textC,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            if (i < options.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}
