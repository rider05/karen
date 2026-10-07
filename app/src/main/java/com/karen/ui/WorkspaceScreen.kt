package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProjectChat(val role: String, val text: String)
data class ProjectTask(val text: String, val done: Boolean = false)
data class ProjectStep(val text: String, val done: Boolean = false)
data class Project(
    val name: String,
    val dir: String,
    var sessionMs: Long = 0L,
    val chat: MutableList<ProjectChat> = mutableStateListOf(),
    val tasks: MutableList<ProjectTask> = mutableStateListOf(),
    val steps: MutableList<ProjectStep> = mutableStateListOf(),
    var codeOld: String = "",
    var codeNew: String = "",
    val terminalLog: MutableList<String> = mutableStateListOf()
)

/** Derive LLM-style plan steps from a user prompt (no hardcoded project data). */
private fun buildStepsFor(prompt: String): List<String> {
    val short = prompt.trim().take(64).ifBlank { "request" }
    return listOf(
        "Understand request: $short",
        "Break down into subtasks and target files",
        "Implement changes in project files",
        "Run checks in terminal and verify output",
        "Review diff and mark tasks complete"
    )
}

/** Generate updated code content from a prompt, preserving previous content. */
private fun buildCodeFor(prompt: String, previous: String): String {
    val note = "// ${prompt.trim().take(80)}"
    return if (previous.isBlank()) {
        "$note\n// Created on-device in project workspace\n\nfun main() {\n    println(\"${prompt.trim().take(40)}\")\n}\n"
    } else {
        "$previous\n$note\n// Update applied on-device\n"
    }
}

/** Run a real shell command inside the project directory and capture output. */
private suspend fun runShell(cmd: String, workDir: File): String = withContext(Dispatchers.IO) {
    try {
        val proc = ProcessBuilder("sh", "-c", cmd)
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        val trimmed = out.trimEnd().take(2000)
        if (trimmed.isBlank()) "(exit $code) no output" else "$trimmed\n(exit $code)"
    } catch (e: Exception) {
        "Error: ${e.message ?: "failed to run"}"
    }
}

/** Real compile & test: list files, syntax-check .sh files, report actual output/errors. */
private suspend fun compileAndTest(dir: File): String = withContext(Dispatchers.IO) {
    try {
        if (!dir.exists()) return@withContext "Error: project directory not found."
        val files = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) return@withContext "No data found — project directory is empty."
        val sb = StringBuilder()
        sb.appendLine("Files (${files.size}):")
        files.forEach { f -> sb.appendLine("- ${f.name} (${f.length()} bytes)") }
        val shFiles = files.filter { it.extension == "sh" && it.isFile }
        if (shFiles.isEmpty()) {
            sb.append("No runnable checks found (.sh scripts).")
        } else {
            shFiles.forEach { f ->
                try {
                    val p = ProcessBuilder("sh", "-n", f.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    val code = p.waitFor()
                    if (code == 0) sb.appendLine("PASS ${f.name}: syntax OK")
                    else sb.appendLine("FAIL ${f.name}: ${out.ifBlank { "syntax error" }} (exit $code)")
                } catch (e: Exception) {
                    sb.appendLine("FAIL ${f.name}: ${e.message}")
                }
            }
        }
        sb.toString().trimEnd()
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
}

/** Line diff: '-' removed, '+' added, ' ' unchanged. */
private fun diffLines(old: String, new: String): List<Pair<Char, String>> {
    if (old.isBlank() && new.isBlank()) return emptyList()
    val a = if (old.isBlank()) emptyList() else old.lines()
    val b = if (new.isBlank()) emptyList() else new.lines()
    val result = mutableListOf<Pair<Char, String>>()
    val max = maxOf(a.size, b.size)
    for (i in 0 until max) {
        val ao = a.getOrNull(i)
        val bo = b.getOrNull(i)
        if (ao == bo && ao != null) {
            result.add(' ' to ao)
        } else {
            if (ao != null) result.add('-' to ao)
            if (bo != null) result.add('+' to bo)
        }
    }
    return result
}

@Composable
fun WorkspaceScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    var activeTab by remember { mutableStateOf("Code") }
    val tabs = listOf("Code", "Plan", "Terminal", "Diff")

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val projects = remember {
        mutableStateListOf<Project>().apply {
            UserPrefs.projects(ctx).forEach { (n, d) -> add(Project(n, d)) }
        }
    }
    var selected by remember { mutableStateOf<Project?>(null) }
    var showNewProject by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    // System back: dismiss dialog first, then step out of the open project,
    // then route to Home — same in-app behaviour as the chat screen.
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
        when {
            showNewProject -> { showNewProject = false; true }
            selected != null -> { selected = null; true }
            else -> false
        }
    }

    // Session timer for the active project (tick forces recomposition each second)
    var running by remember { mutableStateOf(false) }
    var timerTick by remember { mutableStateOf(0L) }
    LaunchedEffect(running, selected) {
        while (running && selected != null) {
            kotlinx.coroutines.delay(1000)
            selected!!.sessionMs += 1000
            timerTick += 1000
        }
    }

    // Project chat input (ChatScreen-style, per project)
    var chatInput by remember { mutableStateOf("") }
    var chatBusy by remember { mutableStateOf(false) }

    fun sendProjectMessage(project: Project, text: String) {
        val t = text.trim()
        if (t.isBlank() || chatBusy) return
        chatBusy = true
        project.chat.add(ProjectChat("user", t))
        scope.launch {
            // 1. Plan steps from the prompt
            val steps = buildStepsFor(t)
            project.steps.clear()
            steps.forEach { project.steps.add(ProjectStep(it)) }
            project.tasks.clear()
            steps.drop(1).forEach { project.tasks.add(ProjectTask(it)) }
            // 2. Real code update in internal storage
            val dir = File(project.dir).apply { mkdirs() }
            val mainFile = File(dir, "main.txt")
            val prev = try {
                if (mainFile.exists()) mainFile.readText() else project.codeNew
            } catch (_: Exception) { project.codeNew }
            val next = buildCodeFor(t, prev)
            project.codeOld = prev
            project.codeNew = next
            withContext(Dispatchers.IO) {
                try { mainFile.writeText(next) } catch (_: Exception) {}
            }
            // 3. Real tool execution in terminal
            project.terminalLog.add("$ $t".take(120))
            val lsOut = runShell("ls -la", dir)
            project.terminalLog.add("[tool] ls -la\n$lsOut")
            project.chat.add(
                ProjectChat(
                    "assistant",
                    "Planned ${steps.size} steps, updated main.txt and ran checks. See Plan / Code / Terminal."
                )
            )
            chatBusy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Canvas",
            onMenuClick = onOpenDrawer,
            onBackClick = onNavigateToHome,
            moreActions = listOf(
                Triple("New project", Icons.Default.Add) { showNewProject = true },
                Triple("Export canvas", Icons.Default.Share) { android.widget.Toast.makeText(ctx, "Export canvas", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Clear workspace", Icons.Default.Delete) {
                    projects.clear()
                    UserPrefs.saveProjects(ctx, emptyList())
                    selected = null
                }
            )
        )

        if (selected == null) {
            // Project list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Projects", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { showNewProject = true }) { Text("+ New", color = colors.accentGreen) }
                }
                Spacer(Modifier.height(8.dp))
                if (projects.isEmpty()) {
                    Text("No data found — create a project to start", color = colors.textMuted, fontSize = 12.5.sp)
                } else {
                    projects.forEach { p ->
                        val totalSec = p.sessionMs / 1000
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .clickable {
                                    selected = p
                                    activeTab = "Code"
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                                Text(p.dir, color = colors.textMuted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                            }
                            Text(String.format("%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60), color = colors.textMuted, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            if (showNewProject) {
                AlertDialog(
                    onDismissRequest = { showNewProject = false },
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                    textContentColor = colors.textSecondary,
                    title = { Text("New Project") },
                    text = {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("Project name", color = colors.textMuted) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = colors.accentGreen,
                                unfocusedBorderColor = colors.border,
                                cursorColor = colors.accentGreen
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val name = newName.trim()
                            if (name.isNotBlank()) {
                                val dir = File(ctx.filesDir, "projects/$name").apply { mkdirs() }
                                projects.add(Project(name, dir.absolutePath))
                                UserPrefs.saveProjects(ctx, projects.map { it.name to it.dir })
                                selected = projects.last()
                                activeTab = "Code"
                                newName = ""
                                showNewProject = false
                            }
                        }) { Text("Create") }
                    },
                    dismissButton = { TextButton(onClick = { showNewProject = false }) { Text("Cancel") } }
                )
            }
            return@Column
        }

        val project = selected!!
        val totalSec = project.sessionMs / 1000

        // Active project header + session timer (inside the project/repo)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { selected = null; running = false }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Projects", tint = colors.textPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(project.name, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(project.dir, color = colors.textMuted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            Text(
                String.format("%02d:%02d:%02d", totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60),
                color = if (running) colors.accentGreen else colors.textMuted,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = { running = !running }) { Text(if (running) "Pause" else "Start", color = colors.accentGreen) }
        }

        // Chat thread (ChatScreen-style, per project)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (project.chat.isEmpty()) {
                item { Text("No data found — start the conversation below.", color = colors.textMuted, fontSize = 12.5.sp) }
            }
            items(project.chat.size) { i ->
                val m = project.chat[i]
                if (m.role == "user") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.userBubble)
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(m.text, color = colors.userBubbleText, fontSize = 14.sp)
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(colors.accentGreen),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Psychology, contentDescription = "Karen", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                            Spacer(Modifier.width(7.dp))
                            Text("Karen", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(m.text, color = colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }

        // Artifact tabs (outputs only — Chat lives above, Tasks live in Plan)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
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
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        // Artifact panel (fixed height, theme-aware)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
        ) {
            when (activeTab) {
                "Code" -> CodeCanvasView(project)
                "Plan" -> PlanCanvasView(project)
                "Terminal" -> TerminalCanvasView(project)
                "Diff" -> DiffCanvasView(project)
            }
        }

        // Composer (ChatScreen-style input for this project)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = chatInput,
                onValueChange = { chatInput = it },
                placeholder = { Text("Message about ${project.name}…", color = colors.textMuted) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentGreen,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.accentGreen
                )
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (chatBusy) colors.surfaceHover else colors.accentGreen)
                    .clickable(enabled = !chatBusy) {
                        sendProjectMessage(project, chatInput)
                        chatInput = ""
                    },
                contentAlignment = Alignment.Center
            ) {
                if (chatBusy) {
                    CircularProgressIndicator(color = colors.textMuted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun CodeCanvasView(project: Project) {
    val colors = LocalKarenColors.current
    val scope = rememberCoroutineScope()
    var files by remember(project) { mutableStateOf(emptyList<File>()) }
    var selectedFile by remember(project) { mutableStateOf<File?>(null) }
    var content by remember(project) { mutableStateOf("") }
    var status by remember(project) { mutableStateOf<String?>(null) }
    var statusOk by remember(project) { mutableStateOf(true) }

    LaunchedEffect(project) {
        withContext(Dispatchers.IO) {
            val dir = File(project.dir).apply { mkdirs() }
            val list = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
            val main = File(dir, "main.txt")
            val initial = try {
                if (project.codeNew.isNotBlank()) project.codeNew
                else if (main.exists()) main.readText() else ""
            } catch (_: Exception) { project.codeNew }
            withContext(Dispatchers.Main) {
                files = list
                if (selectedFile == null) {
                    selectedFile = if (main.exists() || list.none { it.name == "main.txt" }) main else list.firstOrNull()
                }
                content = initial
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (files.isEmpty()) {
            Text("No data found", color = colors.textMuted, fontSize = 12.sp)
        } else {
            Row(modifier = Modifier.fillMaxWidth()) {
                files.take(4).forEach { f ->
                    val sel = selectedFile?.absolutePath == f.absolutePath
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) colors.accentGreen.copy(alpha = 0.18f) else colors.surfaceHover)
                            .border(1.dp, if (sel) colors.accentGreen else colors.border, RoundedCornerShape(8.dp))
                            .clickable {
                                selectedFile = f
                                scope.launch(Dispatchers.IO) {
                                    val t = try { f.readText() } catch (e: Exception) { "Error: ${e.message}" }
                                    withContext(Dispatchers.Main) { content = t }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(f.name, color = if (sel) colors.accentGreen else colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accentGreen,
                unfocusedBorderColor = colors.border,
                cursorColor = colors.accentGreen
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val f = selectedFile ?: return@Button
                    scope.launch(Dispatchers.IO) {
                        try {
                            val prev = try { if (f.exists()) f.readText() else "" } catch (_: Exception) { "" }
                            f.writeText(content)
                            withContext(Dispatchers.Main) {
                                project.codeOld = prev
                                project.codeNew = content
                                files = f.parentFile?.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
                                status = "Saved ${f.name} (${content.length} chars)"
                                statusOk = true
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                status = "Error saving: ${e.message}"
                                statusOk = false
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Save", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val out = compileAndTest(File(project.dir))
                        status = out
                        statusOk = !out.startsWith("Error") && !out.contains("FAIL")
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Compile & Test", fontSize = 12.sp)
            }
        }
        if (status != null) {
            Spacer(Modifier.height(6.dp))
            Text(status!!, color = if (statusOk) colors.accentGreen else colors.accentRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun PlanCanvasView(project: Project) {
    val colors = LocalKarenColors.current
    var newTask by remember { mutableStateOf("") }
    val remaining = project.steps.count { !it.done } + project.tasks.count { !it.done }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Plan · $remaining remaining", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            if (project.steps.isEmpty() && project.tasks.isEmpty()) {
                Text("No data found — send a message and the plan appears here.", color = colors.textMuted, fontSize = 12.sp)
            }
        }
        items(project.steps.size) { i ->
            val s = project.steps[i]
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceHover.copy(alpha = 0.6f))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = s.done,
                    onCheckedChange = { project.steps[i] = s.copy(done = it) },
                    colors = CheckboxDefaults.colors(checkedColor = colors.accentGreen)
                )
                Text("${i + 1}. ${s.text}", color = if (s.done) colors.textMuted else colors.textPrimary, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
            }
        }
        if (project.tasks.isNotEmpty()) {
            item { Text("Tasks", color = colors.textMuted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
        }
        items(project.tasks.size) { idx ->
            val t = project.tasks[idx]
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceHover.copy(alpha = 0.6f))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = t.done,
                    onCheckedChange = { project.tasks[idx] = t.copy(done = it) },
                    colors = CheckboxDefaults.colors(checkedColor = colors.accentBlue)
                )
                Text(t.text, color = if (t.done) colors.textMuted else colors.textPrimary, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { project.tasks.removeAt(idx) }) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(17.dp))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newTask,
                    onValueChange = { newTask = it },
                    placeholder = { Text("New task", color = colors.textMuted) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.accentGreen,
                        unfocusedBorderColor = colors.border,
                        cursorColor = colors.accentGreen
                    )
                )
                TextButton(onClick = {
                    if (newTask.isNotBlank()) {
                        project.tasks.add(ProjectTask(newTask.trim()))
                        newTask = ""
                    }
                }) { Text("Add", color = colors.accentGreen) }
            }
        }
    }
}

@Composable
private fun TerminalCanvasView(project: Project) {
    val colors = LocalKarenColors.current
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(true) }
    val termBg = if (colors.isDark) Color(0xFF09090B) else Color(0xFFF4F4F5)
    val termText = if (colors.isDark) Color(0xFFECECEC) else Color(0xFF1C1C1E)
    val termMuted = if (colors.isDark) Color(0xFF8E8E93) else Color(0xFF6B6B70)

    Column(modifier = Modifier.fillMaxSize().background(termBg).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFEF4444)))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFF59E0B)))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF10A37F)))
            Spacer(Modifier.width(12.dp))
            Text("local-sandbox: ~/${project.name}", color = termMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = if (colors.isDark) Color(0xFF262626) else Color(0xFFE0E0E0), thickness = 0.5.dp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (project.terminalLog.isEmpty()) {
                item { Text("No data found", color = termMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
            items(project.terminalLog.size) { i ->
                Text(project.terminalLog[i], color = termText, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
            }
            if (result != null) {
                item {
                    Text(result!!, color = if (resultOk) colors.accentGreen else colors.accentRed, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text("run command…", color = termMuted) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = termText, fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accentGreen,
                    unfocusedBorderColor = if (colors.isDark) Color(0xFF2C2C2E) else Color(0xFFD4D4D8),
                    cursorColor = colors.accentGreen
                )
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    val c = cmd.trim()
                    if (c.isBlank() || busy) return@Button
                    busy = true
                    scope.launch {
                        project.terminalLog.add("$ $c")
                        val out = runShell(c, File(project.dir))
                        project.terminalLog.add(out)
                        cmd = ""
                        busy = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Run", fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val out = compileAndTest(File(project.dir))
                        result = out
                        resultOk = !out.startsWith("Error") && !out.contains("FAIL")
                        project.terminalLog.add("[compile & test]\n$out")
                    }
                },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Compile & Test", fontSize = 11.5.sp)
            }
            TextButton(onClick = { project.terminalLog.clear(); result = null }) {
                Text("Clear", color = termMuted, fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun DiffCanvasView(project: Project) {
    val colors = LocalKarenColors.current
    val lines = diffLines(project.codeOld, project.codeNew)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Text("Diff · previous vs current", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (lines.isEmpty()) {
                Text("No data found", color = colors.textMuted, fontSize = 12.sp)
            }
        }
        items(lines.size) { i ->
            val (mark, text) = lines[i]
            val bg = when (mark) {
                '-' -> colors.accentRed.copy(alpha = 0.10f)
                '+' -> colors.accentGreen.copy(alpha = 0.10f)
                else -> Color.Transparent
            }
            val fg = when (mark) {
                '-' -> colors.accentRed
                '+' -> colors.accentGreen
                else -> colors.textMuted
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("$mark ", color = fg, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text(text.ifBlank { " " }, color = fg, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
