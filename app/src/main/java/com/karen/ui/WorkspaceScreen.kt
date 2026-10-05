package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WorkspaceScreen(
    onOpenDrawer: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    var activeTab by remember { mutableStateOf("Code") }
    val tabs = listOf("Code", "Plan", "Terminal", "Diff")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Workspace Top Bar
        ChatGPTTopAppBar(
            selectedModel = "Canvas · Karen 4B",
            onMenuClick = onOpenDrawer
        )

        // Sub-header with File info & Segmented Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Terminal,
                        contentDescription = "File",
                        tint = colors.accentGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "feature_extractor.rs",
                        color = colors.textPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceHover)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Rust 2024", color = colors.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Segmented Tab Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(3.dp)
            ) {
                tabs.forEach { tab ->
                    val isSelected = activeTab == tab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) colors.surfaceHover else Color.Transparent)
                            .clickable { activeTab = tab }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab,
                            color = if (isSelected) colors.textPrimary else colors.textMuted,
                            fontSize = 12.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = colors.border, thickness = 0.5.dp)

        // Content Area
        when (activeTab) {
            "Code" -> CodeCanvasView()
            "Plan" -> PlanCanvasView()
            "Terminal" -> TerminalCanvasView()
            "Diff" -> DiffCanvasView()
        }
    }
}

@Composable
private fun CodeCanvasView() {
    val colors = LocalKarenColors.current

    val codeLines = listOf(
        "use ndarray::ArrayView2;",
        "use std::sync::atomic::{AtomicUsize, Ordering};",
        "",
        "pub struct BiometricExtractor {",
        "    tensor_dimensions: (usize, usize),",
        "    inference_threads: usize,",
        "}",
        "",
        "impl BiometricExtractor {",
        "    pub fn new(dim_x: usize, dim_y: usize) -> Self {",
        "        Self {",
        "            tensor_dimensions: (dim_x, dim_y),",
        "            inference_threads: 6, // Karen: Optimized to prevent throttling",
        "        }",
        "    }",
        "",
        "    pub fn extract_liveness(&self, frame: ArrayView2<f32>) -> bool {",
        "        // On-device NPU accelerated feature pass",
        "        frame.mean().unwrap_or(0.0) > 0.42",
        "    }",
        "}"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 0.dp, bottom = 80.dp)
    ) {
        // AI Review Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.accentGreen.copy(alpha = 0.12f))
                    .border(1.dp, colors.accentGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Psychology, contentDescription = "AI", tint = colors.accentGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Karen AI Suggestion", color = colors.accentGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Replaced 8-thread compile with 6 threads to keep peak CPU junction temperature under 65°C on local hardware.",
                    color = colors.textPrimary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

        // Code Editor
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (colors.isDark) Color(0xFF0F0F12) else Color(0xFFF7F7FA))
                    .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                codeLines.forEachIndexed { index, line ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    ) {
                        Text(
                            text = (index + 1).toString().padStart(2, ' '),
                            color = colors.textMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(28.dp)
                        )
                        Text(
                            text = line,
                            color = if (line.contains("//")) colors.accentGreen else colors.textPrimary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Action Buttons Row
        item {
            var testRunStatus by remember { mutableStateOf<String?>(null) }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { testRunStatus = "✓ cargo test: 27 unit tests passed in 0.08s (0 errors)" },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Run", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Compile & Test", fontSize = 12.5.sp)
                }

                OutlinedButton(
                    onClick = { testRunStatus = "✓ rustfmt formatting completed" },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = "Format", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Format Code", fontSize = 12.5.sp)
                }
            }

            if (testRunStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = testRunStatus!!,
                    color = colors.accentGreen,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PlanCanvasView() {
    val colors = LocalKarenColors.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Execution Plan · TreeVision Biometrics", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text("Step-by-step verified local subtasks", color = colors.textMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }

        val tasks = listOf(
            Triple("1. Initialize Rust Cargo workspace with ndarray", true, "Passed (0.2s)"),
            Triple("2. Vectorize feature extraction matrix multiplication", true, "Passed (0.4s)"),
            Triple("3. Thermal safeguard: Clamp MAKEFLAGS=-j6", true, "Passed (0.1s)"),
            Triple("4. Integrate local SQLite embedded cache", false, "In progress..."),
            Triple("5. Run deterministic test suite (27 tests)", false, "Pending"),
            Triple("6. Air-gap verification: 0 bytes outbound", false, "Pending")
        )

        items(tasks.size) { i ->
            val (taskName, completed, status) = tasks[i]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (completed) colors.accentGreen else colors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(taskName, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(status, color = colors.textMuted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TerminalCanvasView() {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444)))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFF59E0B)))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF10A37F)))
            Spacer(Modifier.width(12.dp))
            Text("local-sandbox: ~/treevision", color = Color(0xFF8E8E93), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFF262626), thickness = 0.5.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "$ cargo test --release -- --nocapture\n" +
                    "   Compiling treevision-core v0.1.0\n" +
                    "    Finished release [optimized] target(s) in 2.14s\n" +
                    "     Running unittests src/lib.rs\n\n" +
                    "test tests::test_liveness_detection ... ok\n" +
                    "test tests::test_avx512_vectorization ... ok\n" +
                    "test tests::test_zero_cloud_egress ... ok\n\n" +
                    "test result: ok. 27 passed; 0 failed; 0 ignored; finished in 0.08s",
            color = Color(0xFFECECEC),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun DiffCanvasView() {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Text("Local Git Diff: HEAD~1", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (colors.isDark) Color(0xFF0F0F12) else Color(0xFFF7F7FA))
                .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Text("@@ -12,4 +12,4 @@", color = colors.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text("- inference_threads: 8, // caused thermal spikes", color = Color(0xFFEF4444), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
            Text("+ inference_threads: 6, // Karen: Optimized for 58°C junction", color = colors.accentGreen, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
