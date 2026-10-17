package com.karen.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
 * Line chart with optional area fill. Points are normalised to 0..1.
 * `labels` are shown under evenly spaced ticks (max 6).
 */
@Composable
fun KLineChart(
    points: List<Float>,
    labels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    lineColor: Color = LocalKarenColors.current.accentGreen,
    fillColor: Color = LocalKarenColors.current.accentGreen.copy(alpha = 0.12f),
    height: androidx.compose.ui.unit.Dp = 140.dp
) {
    val colors = LocalKarenColors.current
    if (points.isEmpty()) return
    val animated = points.mapIndexed { i, p ->
        val state = remember { mutableStateOf(0f) }
        LaunchedEffect(Unit) { state.value = p }
        val v by animateFloatAsState(state.value, tween(600 + i * 40), label = "kLine$i")
        v
    }
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            val w = size.width
            val h = size.height
            val n = animated.size
            if (n < 2) return@Canvas
            val stepX = w / (n - 1)
            val path = Path().apply {
                moveTo(0f, h - animated[0] * h)
                for (i in 1 until n) lineTo(i * stepX, h - animated[i] * h)
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
            val lastY = h - animated.last() * h
            drawCircle(lineColor, 5f, Offset(lastX, lastY))
        }
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
    }
}

/**
 * Vertical bar chart. `labels` align under bars (max 8).
 */
@Composable
fun KBarChart(
    values: List<Float>,
    labels: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    barColor: Color = LocalKarenColors.current.accentGreen,
    height: androidx.compose.ui.unit.Dp = 140.dp
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
            values.take(8).forEach { v ->
                val frac = (v / maxV).coerceIn(0f, 1f)
                val h by animateFloatAsState(frac, tween(500), label = "kBar")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(h)
                        .padding(horizontal = 3.dp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(barColor)
                )
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
 * Shows a legend with percentages to the right.
 */
@Composable
fun KPieChart(
    slices: List<Triple<String, Float, Color>>,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 120.dp
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
                        "${((value / total) * 100).toInt()}%",
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
 * KPI card: label, big value, optional trend arrow and delta, plus a
 * mini sparkline. Used in dashboards and summary rows.
 */
@Composable
fun KpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trend: Float? = null,
    spark: List<Float> = emptyList(),
    tone: KTone = KTone.Primary
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
            Text(
                label.uppercase(),
                color = colors.textMuted,
                fontFamily = LocalKarenType.current.mono,
                fontSize = 10.sp,
                letterSpacing = 0.5.sp,
                modifier = Modifier.weight(1f)
            )
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
 */
@Composable
fun KDataTable(
    columns: List<String>,
    rows: List<Pair<String, List<String>>>,
    modifier: Modifier = Modifier,
    sortColumn: Int = -1,
    sortAsc: Boolean = true,
    onSort: (Int, Boolean) -> Unit = { _, _ -> }
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
        rows.forEach { (id, cells) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

// ---------------------------------------------------------------------------
// Agent task timeline (SmartUI-3 §8) — progress with step states.
// ---------------------------------------------------------------------------

/**
 * Agent task timeline: a vertical list of steps with status icons
 * (done / active / pending) and an optional progress bar.
 */
@Composable
fun KTaskTimeline(
    steps: List<Triple<String, String, Int>>, // (label, detail, status) — 0=done 1=active 2=pending
    progress: Float? = null,
    modifier: Modifier = Modifier
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
                    Text(
                        label,
                        color = if (status == 2) colors.textMuted else colors.textPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (status == 1) FontWeight.SemiBold else FontWeight.Normal
                    )
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
 */
@Composable
fun KEmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String = "",
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = LocalKarenColors.current
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
                .background(colors.surfaceHover)
                .border(1.dp, colors.border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(26.dp))
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

// ---------------------------------------------------------------------------
// Skeleton loader (SmartUI-3 §15) — shimmering placeholder blocks.
// ---------------------------------------------------------------------------

/**
 * Skeleton loader: a column of shimmering placeholder blocks.
 * Used while content loads.
 */
@Composable
fun KSkeletonLoader(
    modifier: Modifier = Modifier,
    rows: Int = 3
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
}

// ---------------------------------------------------------------------------
// Search result card (SmartUI-3 §9) — title, snippet, source, freshness.
// ---------------------------------------------------------------------------

/**
 * Search result card: title, snippet, source domain, and a freshness label.
 * Used in web search results and research panels.
 */
@Composable
fun KSearchResultCard(
    title: String,
    snippet: String,
    source: String,
    modifier: Modifier = Modifier,
    freshness: String? = null,
    onClick: (() -> Unit)? = null
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
                Text(
                    freshness,
                    color = colors.textMuted,
                    fontFamily = LocalKarenType.current.mono,
                    fontSize = 9.sp
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
 * Star rating input. `value` is 0..5. Tap a star to set the rating.
 */
@Composable
fun KRatingInput(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    max: Int = 5
) {
    val colors = LocalKarenColors.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
}

// ---------------------------------------------------------------------------
// Accordion (SmartUI-3 §16) — expandable section with chevron.
// ---------------------------------------------------------------------------

/**
 * Expandable accordion section. Tap the header to toggle.
 */
@Composable
fun KAccordion(
    title: String,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = LocalKarenColors.current
    var expanded by remember { mutableStateOf(defaultExpanded) }
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
        if (expanded) {
            KHairline()
            Box(Modifier.padding(14.dp)) { content() }
        }
    }
}
