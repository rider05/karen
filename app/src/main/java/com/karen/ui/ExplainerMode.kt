package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Premium Visual Technical Explainer Mode (docs/premium_visual_technical_explainer_prompt.md).
 *
 * When enabled, chat and workspace prompts carry [EXPLAINER_STYLE_GUIDE] so
 * answers come back structured (title, tables, mermaid flowcharts, checklists,
 * labeled code blocks), and the renderer below draws them premium: native
 * color-coded architecture cards, real checkboxes, styled tables.
 */
const val EXPLAINER_STYLE_GUIDE =
    "Reply style (Premium Visual Technical Explainer): start with a short # title and a 1-2 sentence " +
        "quick answer, then ## sections. Compare options in narrow markdown tables. Show architecture or data " +
        "flow as a ```mermaid flowchart TD with labeled boxes and arrows. List action steps as - [ ] checklists. " +
        "Put code in labeled fenced blocks. Keep paragraphs short and mobile-friendly. Never invent services, " +
        "prices, quotas, or benchmarks; say when information may be outdated."

/**
 * True for study/learning asks: explicit explain/tutorial/detail requests.
 * Used to auto-apply Explainer Mode only when asked that way.
 */
fun isStudyAsk(prompt: String): Boolean {
    val t = " ${prompt.lowercase()} "
    return listOf(
        "explain", "tutorial", "learn", "study", "in detail", "detailed",
        "architecture", "diagram", "how does", "how do", "what is", "what are",
        " why ", "teach", "lesson", "step by step", "professional", "report",
        "compare", "how it works"
    ).any { it in t }
}
/**
 * Explainer Mode applies only when asked: Settings toggle on, or the prompt
 * itself is a study ask (explain/tutorial/in-detail…).
 */
fun wantsExplainer(prompt: String, ctx: android.content.Context): Boolean =
    UserPrefs.explainerMode(ctx) || isStudyAsk(prompt)

/** One box in a flowchart: [shape] is rect, decision, db, circle, subroutine, parallelogram, flag. */
data class FlowNode(val id: String, val label: String, val shape: String)

/** One arrow: [label] is the optional |edge text|. */
data class FlowEdge(val from: String, val to: String, val label: String?)

data class FlowChart(val vertical: Boolean, val nodes: List<FlowNode>, val edges: List<FlowEdge>)

/**
 * Minimal mermaid flowchart parser (graph/flowchart, TD/TB/BT/LR/RL).
 * Handles node shapes, labeled arrows, chains, `;` separators, and skips
 * subgraph/class/click/style directives. Returns null when unparseable so
 * callers fall back to a plain code block. No new dependencies.
 */
fun parseMermaidFlowchart(code: String): FlowChart? {
    val nodes = linkedMapOf<String, FlowNode>()
    val edges = mutableListOf<FlowEdge>()
    var vertical = true
    fun addNode(id: String, label: String?, shape: String?) {
        if (id.isBlank() || nodes.size >= 24) return
        val clean = (label ?: id).trim().trim('"').trim('\'').ifBlank { id }
        val prev = nodes[id]
        // A later shaped definition upgrades a bare edge-only reference.
        if (prev == null || (prev.label == prev.id && clean != id) || (prev.shape == "rect" && shape != null && shape != "rect")) {
            nodes[id] = FlowNode(id, clean, shape ?: prev?.shape ?: "rect")
        }
    }
    // Opener alternation: longest first so [( )]] and [/ /] win over plain [.
    val nodeDefRe =
        Regex("([A-Za-z0-9_]+)\\s*(\\[\\[|\\(\\(|\\(\\[|\\{\\{|\\[/|\\[\\\\|\\{|\\[|>)(.+?)(\\]\\]|\\)\\)|\\)\\]|\\}\\}|\\}|\\]|/\\]|\\\\\\])")
    val edgeRe = Regex("([A-Za-z0-9_]+)\\s*(-+>|=+>|\\.-+>|~~~)\\s*(?:\\|([^|]*)\\|\\s*)?([A-Za-z0-9_]+)")
    val dirRe = Regex("^(flowchart|graph)\\s+(TD|TB|BT|RL|LR)\\s*$", RegexOption.IGNORE_CASE)
    val skipRe = Regex("^(subgraph|end\\b|classDef\\b|class\\b|click\\b|style\\b|linkStyle\\b)", RegexOption.IGNORE_CASE)
    fun shapeOf(opener: String): String = when (opener) {
        "[[" -> "subroutine"
        "((" -> "circle"
        "[(" -> "db"
        "{{" -> "hexagon"
        "{" -> "decision"
        "[/", "[\\" -> "parallelogram"
        ">" -> "flag"
        else -> "rect"
    }
    val statements = code.lines()
        .map { it.substringBefore("%%").trim() }
        .flatMap { it.split(";") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    for (stmt in statements) {
        val dir = dirRe.matchEntire(stmt)
        if (dir != null) {
            vertical = dir.groupValues[2].uppercase() != "LR" && dir.groupValues[2].uppercase() != "RL"
            continue
        }
        if (skipRe.containsMatchIn(stmt)) continue
        for (m in nodeDefRe.findAll(stmt)) {
            addNode(m.groupValues[1], m.groupValues[3], shapeOf(m.groupValues[2]))
        }
        for (m in edgeRe.findAll(stmt)) {
            if (edges.size >= 32) break
            val from = m.groupValues[1]
            val to = m.groupValues[4]
            addNode(from, null, null)
            addNode(to, null, null)
            edges.add(FlowEdge(from, to, m.groupValues[3].trim().ifBlank { null }))
        }
    }
    if (nodes.isEmpty()) return null
    return FlowChart(vertical, nodes.values.toList(), edges)
}

private enum class FlowRole { APP, STORAGE, API, INFRA, DECISION, NEUTRAL }

/** Spec color coding: blue apps, green storage, purple APIs, gray infra, amber decisions. */
private fun flowRoleOf(node: FlowNode): FlowRole {
    if (node.shape == "db") return FlowRole.STORAGE
    if (node.shape == "decision") return FlowRole.DECISION
    val t = "${node.id} ${node.label}".lowercase()
    return when {
        listOf("db", "database", "storage", "store", "repo", "vault", "cache", "bucket", "disk", "sqlite", "model weights", "dataset").any { it in t } -> FlowRole.STORAGE
        listOf("api", "gateway", "endpoint", "service", "rest", "graphql", "route").any { it in t } -> FlowRole.API
        listOf("app", "client", "ui", "screen", "frontend", "mobile", "web", "backend", "server", "activity", "compose", "training", "inference").any { it in t } -> FlowRole.APP
        listOf("cloud", "deploy", "host", "infra", "docker", "k8s", "kubernetes", "pipeline", "ci/cd").any { it in t } -> FlowRole.INFRA
        else -> FlowRole.NEUTRAL
    }
}

@Composable
private fun flowRoleColor(role: FlowRole): Color {
    val colors = LocalKarenColors.current
    return when (role) {
        FlowRole.APP -> colors.accentBlue
        FlowRole.STORAGE -> colors.accentGreen
        FlowRole.API -> Color(0xFFA78BFA)
        FlowRole.INFRA -> colors.textMuted
        FlowRole.DECISION -> colors.accentAmber
        FlowRole.NEUTRAL -> colors.accentBlue
    }
}

@Composable
private fun FlowNodeCard(node: FlowNode, modifier: Modifier = Modifier) {
    val colors = LocalKarenColors.current
    val role = flowRoleOf(node)
    val tint = flowRoleColor(role)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.08f))
            .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = node.label,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = role.name,
            color = colors.textMuted,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun FlowArrow(label: String?, vertical: Boolean) {
    val colors = LocalKarenColors.current
    if (vertical) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    color = colors.textSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceHover)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
            Text(text = "↓", color = colors.textMuted, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!label.isNullOrBlank()) {
                Text(
                    text = label,
                    color = colors.textSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceHover)
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(text = "→", color = colors.textMuted, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Native offline architecture diagram: color-coded node cards joined by
 * labeled arrows. Falls back to a plain code block when unparseable.
 */
@Composable
fun MermaidDiagram(code: String) {
    val colors = LocalKarenColors.current
    val chart = remember(code) { parseMermaidFlowchart(code) }
    if (chart == null) {
        CodeBlockView(language = "mermaid", code = code)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.AccountTree,
                contentDescription = null,
                tint = colors.accentBlue,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Architecture",
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (chart.vertical) "flow ↓" else "flow →",
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(10.dp))
        val byId = chart.nodes.associateBy { it.id }
        val rendered = mutableSetOf<String>()
        if (chart.vertical) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                for (e in chart.edges) {
                    if (rendered.add(e.from)) {
                        byId[e.from]?.let { FlowNodeCard(it, Modifier.fillMaxWidth()) }
                    }
                    FlowArrow(e.label, vertical = true)
                    if (rendered.add(e.to)) {
                        byId[e.to]?.let { FlowNodeCard(it, Modifier.fillMaxWidth()) }
                    } else {
                        byId[e.to]?.let {
                            Text(
                                text = "↩ ${it.label}",
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                // Isolated boxes with no arrows.
                chart.nodes.filter { it.id !in rendered }.forEach {
                    Spacer(Modifier.height(4.dp))
                    FlowNodeCard(it, Modifier.fillMaxWidth())
                }
                if (chart.edges.isEmpty() && chart.nodes.isNotEmpty() && rendered.isEmpty()) {
                    chart.nodes.forEach {
                        FlowNodeCard(it, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (e in chart.edges) {
                    if (rendered.add(e.from)) {
                        byId[e.from]?.let { FlowNodeCard(it, Modifier.width(168.dp)) }
                        Spacer(Modifier.width(6.dp))
                    }
                    FlowArrow(e.label, vertical = false)
                    Spacer(Modifier.width(6.dp))
                    if (rendered.add(e.to)) {
                        byId[e.to]?.let { FlowNodeCard(it, Modifier.width(168.dp)) }
                        Spacer(Modifier.width(6.dp))
                    }
                }
                chart.nodes.filter { it.id !in rendered }.forEach {
                    FlowNodeCard(it, Modifier.width(168.dp))
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
