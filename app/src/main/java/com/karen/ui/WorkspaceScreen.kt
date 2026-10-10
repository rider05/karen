package com.karen.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import android.util.Base64
import com.karen.rememberDeviceTelemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProjectChat(val role: String, val text: String, val tookMs: Long = 0L)
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
    val terminalLog: MutableList<String> = mutableStateListOf(),
    var filesVersion: Long = 0L
)

/** True when the message asks for something to be built (not just discussed). */
internal fun projectNeedsScaffold(text: String): Boolean {
    val t = " $text ".lowercase()
    return listOf(" build ", " create ", " generate ", " implement ", " scaffold ", " add file ", " new file ", " write code ", " make an app ", " make a ")
        .any { t.contains(it) }
}

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

/** Quote a shell argument safely for `sh -c`. */
private fun shQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/** Strip embedded credentials from git output so tokens never land in logs. */
private fun sanitizeGitOutput(out: String): String =
    out.replace(Regex("://[^/\\s]*@"), "://***@")

/** Mask credentials for on-screen display of a remote URL. */
private fun maskRemote(url: String): String =
    if (url.isBlank()) "" else url.replace(Regex("://[^/\\s]*@"), "://***@")

private suspend fun gitCommitAll(root: File, message: String): String {
    val msg = message.trim().ifBlank { "Karen workspace update" }.take(200)
    val out = runShell(
        "git rev-parse --is-inside-work-tree >/dev/null 2>&1 || git init; " +
            "git add -A && git -c user.name=Karen -c user.email=karen@local commit -m ${shQuote(msg)}",
        root
    )
    return sanitizeGitOutput(out)
}

private suspend fun gitPushCurrent(root: File, remote: String): String {
    if (remote.isBlank()) return "No remote set — tap Remote first."
    // Point origin at the stored URL without echoing credentials to the log.
    runShell("git remote get-url origin >/dev/null 2>&1 || git remote add origin ${shQuote(remote)}", root)
    runShell("git remote set-url origin ${shQuote(remote)}", root)
    val branch = runShell("git branch --show-current", root).lines().firstOrNull()?.trim().orEmpty()
    val ref = branch.ifBlank { "HEAD" }
    return sanitizeGitOutput(runShell("git push -u origin $ref", root))
}

/** Real compile & test: list files, syntax-check .sh files, report actual output/errors. */
private suspend fun compileAndTest(dir: File): String = withContext(Dispatchers.IO) {
    try {
        if (!dir.exists()) return@withContext "Error: project directory not found."
        val entries = listProjectEntries(dir, 200)
        val files = entries.filter { it.isFile }
        if (files.isEmpty()) return@withContext "No data found — project directory is empty."
        val sb = StringBuilder()
        sb.appendLine("Files (${files.size}):")
        files.take(60).forEach { f ->
            sb.appendLine("- ${relPathOf(dir, f)} (${f.length()} bytes)")
        }
        val shFiles = files.filter { it.extension == "sh" }
        if (shFiles.isEmpty()) {
            sb.append("No runnable checks found (.sh scripts).")
        } else {
            shFiles.take(10).forEach { f ->
                try {
                    val p = ProcessBuilder("sh", "-n", f.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    val code = p.waitFor()
                    if (code == 0) sb.appendLine("PASS ${relPathOf(dir, f)}: syntax OK")
                    else sb.appendLine("FAIL ${relPathOf(dir, f)}: ${out.ifBlank { "syntax error" }} (exit $code)")
                } catch (e: Exception) {
                    sb.appendLine("FAIL ${relPathOf(dir, f)}: ${e.message}")
                }
            }
        }
        sb.toString().trimEnd()
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
}

private val SCAFFOLD_HEADER = Regex("(?m)^[ \t]*///[ \t]*FILE[ \t]*:(.+)$", RegexOption.IGNORE_CASE)
private val SCAFFOLD_END = Regex("(?m)^[ \t]*///[ \t]*END[ \t]*$", RegexOption.IGNORE_CASE)

fun parseScaffold(text: String): List<Pair<String, String>> {
    val results = mutableListOf<Pair<String, String>>()
    val matches = SCAFFOLD_HEADER.findAll(text).toList()
    for ((idx, m) in matches.withIndex()) {
        val relPath = m.groupValues[1].trim().replace('\\', '/')
        if (relPath.isBlank()) continue
        val blockStart = m.range.last + 1
        val blockEnd = matches.getOrNull(idx + 1)?.range?.first ?: text.length
        var content = text.substring(blockStart, blockEnd)
        // Cut at an explicit ///END; without one (cut-off reply) the rest
        // of the text still belongs to this file — never drop it.
        SCAFFOLD_END.find(content)?.let { content = content.substring(0, it.range.first) }
        content = stripWrappingFence(content).trim('\n')
        results.add(relPath to content)
    }
    return results
}

private fun stripWrappingFence(content: String): String {
    val lines = content.lines()
    if (lines.size >= 2 && lines.first().trimStart().startsWith("```") && lines.last().trim() == "```") {
        return lines.drop(1).dropLast(1).joinToString("\n")
    }
    return content
}

private val SCAFFOLD_DIR_RENAME = mapOf("lib/main/java" to "src/main/java")

internal fun safeScaffoldPath(project: Project, relPath: String): File? {
    var clean = relPath.trim().replace('\\', '/').removePrefix("./")
    for ((from, to) in SCAFFOLD_DIR_RENAME) {
        if (clean.startsWith(from)) clean = to + clean.removePrefix(from)
    }
    if (clean.isBlank() || clean.contains("..")) return null
    if (clean.startsWith('/') || clean.contains(':')) return null
    return File(project.dir, clean)
}

private fun writeScaffoldFiles(project: Project, files: List<Pair<String, String>>): String {
    var written = 0
    var skipped = 0
    for ((rel, content) in files.take(12)) {
        val target = safeScaffoldPath(project, rel) ?: continue
        try {
            target.parentFile?.mkdirs()
            target.writeText(content)
            written++
        } catch (_: Exception) {
            skipped++
        }
    }
    return if (written == 0 && skipped > 0) "Could not write files." else "$written file(s) written${if (skipped > 0) ", $skipped failed" else ""}"
}

// ---------- Project file-structure helpers (Workspace is project-first) ----------

/** Root dir for a project, created on demand. */
private fun projectRootDir(project: Project): File = File(project.dir).apply { mkdirs() }

/** Relative path of [f] inside [root], with '/' separators and no leading './'. */
private fun relPathOf(root: File, f: File): String = try {
    root.toURI().relativize(f.toURI()).path.trimEnd('/').removePrefix("./")
} catch (_: Exception) {
    f.name
}

private val BINARY_EXTS = setOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico",
    "mp3", "wav", "ogg", "m4a", "mp4", "mkv", "webm",
    "zip", "gz", "tar", "apk", "aab", "so", "dex",
    "gguf", "bin", "onnx", "tflite", "sqlite", "db"
)

private fun isBinaryName(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in BINARY_EXTS
}

/**
 * All entries (dirs + files) under [root], excluding the hidden `.karen`
 * state dir. Sorted with dirs first, then by relative path.
 */
private fun listProjectEntries(root: File, maxEntries: Int = 400): List<File> {
    if (!root.exists()) return emptyList()
    val out = mutableListOf<File>()
    val stack = ArrayDeque<File>()
    root.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach {
        if (it.name != ".karen") stack.addLast(it)
    }
    while (stack.isNotEmpty() && out.size < maxEntries) {
        val f = stack.removeFirst()
        out.add(f)
        if (f.isDirectory) {
            f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach {
                if (out.size + stack.size < maxEntries) stack.addLast(it)
            }
        }
    }
    return out.sortedWith(compareBy({ relPathOf(root, it) }))
}

/** Depth of [f] inside [root] (root children = 0). */
private fun depthOf(root: File, f: File): Int {
    val rel = relPathOf(root, f)
    if (rel.isBlank()) return 0
    return rel.count { it == '/' }
}

/** Short tree listing for prompts and the terminal, e.g. `- src/main/App.kt (1.2 KB)`. */
private fun projectTreeSummary(root: File, maxEntries: Int = 80): String {
    val entries = listProjectEntries(root, maxEntries)
    if (entries.isEmpty()) return "(empty project)"
    return entries.take(maxEntries).joinToString("\n") { f ->
        val rel = relPathOf(root, f)
        val indent = "  ".repeat(depthOf(root, f))
        if (f.isDirectory) "$indent- $rel/"
        else "$indent- $rel (${formatFileSize(f.length())})"
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

/** Read a text file for the editor/model; null when binary, missing, or too large. */
private fun readTextFileSafe(f: File, maxChars: Int = 15000): String? {
    if (!f.isFile) return null
    if (isBinaryName(f.name)) return null
    if (f.length() > 512 * 1024) return null
    return try {
        f.readText().take(maxChars)
    } catch (_: Exception) {
        null
    }
}

/**
 * Project context for the model: tree + truncated contents of the most
 * relevant text files, so follow-up prompts edit in place instead of
 * restarting from scratch.
 */
private fun projectContextForModel(root: File, maxChars: Int = 9000): String {
    val sb = StringBuilder()
    sb.appendLine(projectTreeSummary(root))
    var used = sb.length
    val files = listProjectEntries(root).filter { it.isFile && !isBinaryName(it.name) }
        .sortedBy { relPathOf(root, it) }
        .take(8)
    for (f in files) {
        val rel = relPathOf(root, f)
        val body = readTextFileSafe(f, maxChars = 2500) ?: continue
        val block = "\n\n--- $rel ---\n$body"
        if (used + block.length > maxChars) break
        sb.append(block)
        used += block.length
    }
    return sb.toString()
}

/** Webapp-mirror syntax colors: pink keywords, amber strings, blue types. */
private val CODE_KEYWORD = Color(0xFFF472B6)
private val CODE_STRING = Color(0xFFFBBF24)
private val CODE_NUMBER = Color(0xFFFACC15)
private val CODE_ANNOTATION = Color(0xFFC084FC)
private val CODE_FUNCTION = Color(0xFF60A5FA)

private val CODE_TOKEN = Regex(
    """(//[^\n]*)|("(?:[^"\\\n]|\\.)*")|\b(\d[\d_]*(?:\.\d+)?)\b|\b(package|import|class|interface|object|fun|val|var|private|public|protected|internal|override|open|abstract|data|sealed|enum|if|else|when|for|while|do|return|break|continue|in|is|as|try|catch|finally|throw|this|super|null|true|false|const|lateinit|companion|suspend|inline|operator|infix)\b|(@\w+)|\b([A-Za-z_]\w*)(?=\s*\()"""
)

/** Single-pass tokenizer: comment > string > number > keyword > annotation > call. */
private fun highlightCodeLine(
    line: String,
    base: Color,
    muted: Color
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var pos = 0
    for (m in CODE_TOKEN.findAll(line)) {
        if (m.range.first > pos) {
            builder.pushStyle(SpanStyle(color = base))
            builder.append(line.substring(pos, m.range.first))
            builder.pop()
        }
        val style = when {
            m.groupValues[1].isNotEmpty() -> SpanStyle(color = muted)
            m.groupValues[2].isNotEmpty() -> SpanStyle(color = CODE_STRING)
            m.groupValues[3].isNotEmpty() -> SpanStyle(color = CODE_NUMBER)
            m.groupValues[4].isNotEmpty() -> SpanStyle(color = CODE_KEYWORD, fontWeight = FontWeight.SemiBold)
            m.groupValues[5].isNotEmpty() -> SpanStyle(color = CODE_ANNOTATION)
            m.groupValues[6].isNotEmpty() -> SpanStyle(color = CODE_FUNCTION)
            else -> SpanStyle(color = base)
        }
        builder.pushStyle(style)
        builder.append(m.value)
        builder.pop()
        pos = m.range.last + 1
    }
    if (pos < line.length) {
        builder.pushStyle(SpanStyle(color = base))
        builder.append(line.substring(pos))
        builder.pop()
    }
    return builder.toAnnotatedString()
}

private fun fileIconFor(name: String, isDir: Boolean): androidx.compose.ui.graphics.vector.ImageVector {
    if (isDir) return Icons.Default.Folder
    return when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "java", "js", "ts", "tsx", "py", "rs", "go", "c", "cpp", "h", "cs", "swift" -> Icons.Default.Code
        "xml", "json", "toml", "gradle", "yaml", "yml" -> Icons.Default.DataObject
        "md", "txt", "pdf" -> Icons.Default.Description
        "png", "jpg", "jpeg", "gif", "webp" -> Icons.Default.Image
        "mp3", "wav", "ogg", "m4a" -> Icons.Default.AudioFile
        "mp4", "mkv", "webm" -> Icons.Default.VideoFile
        "sh" -> Icons.Default.Terminal
        else -> Icons.Default.InsertDriveFile
    }
}

/** Max model follow-ups per build: big projects stream in chunk after chunk. */
private const val MAX_SCAFFOLD_CHUNKS = 4

/** True when the reply was cut off mid-build: more blocks opened than closed. */
private fun isTruncatedScaffold(reply: String): Boolean {
    val opens = SCAFFOLD_HEADER.findAll(reply).count()
    val ends = SCAFFOLD_END.findAll(reply).count()
    if (opens > ends) return true
    val lastEnd = SCAFFOLD_END.findAll(reply).lastOrNull()?.range?.last ?: -1
    val tail = if (lastEnd < 0) reply else reply.substring(lastEnd + 1)
    return SCAFFOLD_HEADER.containsMatchIn(tail)
}

/**
 * Runs scaffold generation as a second pass over `userText`: the model is asked
 * for a multi-file project, and the app writes the delimited blocks to disk.
 * Works for cloud and local GGUF alike.
 *
 * Chunk-based: when the build exceeds the content window the reply is cut
 * mid-project ([isTruncatedScaffold]). Instead of stopping, the model is
 * asked to continue where it left off — up to [MAX_SCAFFOLD_CHUNKS] chunks —
 * each chunk written to disk immediately so partial progress survives.
 */
private suspend fun scaffoldWithModel(
    project: Project,
    userText: String,
    cloud: CloudProvider?,
    cloudKey: String,
    weightFile: File?,
    effort: String,
    threads: Int,
    selectedModelName: String,
    windowTokens: Int,
    modelOverride: String? = null,
    sendStartMs: Long = 0L
): String {
    val perFileCap = (windowTokens / 4).coerceIn(2000, 12000)
    val system = "You are a code projectator for Karen Workspace. Reply using this exact format:\n" +
        "///FILE: relative/path/File.ext\n<file content>\n///END\n" +
        "///FILE: relative/path/Next.ext\n<file content>\n///END\n" +
        "Rules: no explanations outside blocks, no code fences, relative paths only (like src/main/java/Main.kt, gradle/libs.versions.toml), up to 8 files per message, each under $perFileCap chars. " +
        "Every source file must be complete and runnable; do not embed prose or markdown around files. " +
        "If the whole project does not fit in one message, output as many COMPLETE blocks as fit and stop cleanly after an ///END — you will be asked to continue. Never leave a block unclosed. " +
        "Maintain the existing project structure on follow-ups: edit files in place, keep paths stable unless the user asks to restructure."
    // Give the model the current tree so follow-ups continue the same project.
    val tree = withContext(Dispatchers.IO) { projectTreeSummary(projectRootDir(project)) }
    val firstAsk = "Existing project structure:\n$tree\n\nRequest: $userText"

    suspend fun callModel(history: List<Pair<String, String>>): String? {
        if (cloud != null && cloudKey.isNotBlank()) {
            return cloud.complete(
                cloudKey,
                history,
                maxTokensFor(effort),
                thinkingBudget = if (cloud.reasoning) thinkingBudgetFor(effort) else null,
                historyLimit = historyTurnsFor(windowTokens),
                model = modelOverride
            )
        }
        if (weightFile != null && KarenLlama.ready) {
            val ok = withContext(Dispatchers.IO) {
                KarenLlama.ensureLoaded(weightFile, selectedModelName, windowTokens, threads)
            }
            if (!ok) return null
            val withSystem = buildList {
                add("user" to "System: $system")
                addAll(history)
            }
            return withContext(Dispatchers.IO) {
                KarenLlama.complete(
                    system,
                    withSystem.map { it.first }.toTypedArray(),
                    withSystem.map { it.second }.toTypedArray(),
                    maxTokensFor(effort)
                )
            }
        }
        return null
    }

    var reply = callModel(listOf("user" to firstAsk)) ?: return "No model available to scaffold."
    val allFiles = linkedMapOf<String, String>()
    var chunks = 0
    var stillTruncated = false
    while (true) {
        chunks++
        val parsed = parseScaffold(reply)
        if (parsed.isNotEmpty()) {
            withContext(Dispatchers.IO) { writeScaffoldFiles(project, parsed) }
            for ((p, c) in parsed) allFiles[p] = c
            project.filesVersion = System.currentTimeMillis()
        }
        stillTruncated = isTruncatedScaffold(reply)
        if (!stillTruncated || chunks >= MAX_SCAFFOLD_CHUNKS) break
        val doneList = allFiles.keys.sorted().joinToString(", ").ifBlank { "(none completed)" }
        val next = callModel(
            listOf(
                "user" to firstAsk,
                "assistant" to reply.takeLast(6000),
                "user" to "Continue exactly where you stopped. Do NOT resend completed files ($doneList). " +
                    "Output only the REMAINING files as complete ///FILE blocks ending with ///END. " +
                    "If you were cut inside a file, resend that whole file from its start."
            )
        )
        if (next.isNullOrBlank()) break
        reply = next
    }
    /** Stamps scaffold replies with send-relative timing (0 when unknown). */
    fun scaffoldStamp(): Long =
        if (sendStartMs > 0) System.currentTimeMillis() - sendStartMs else 0L
    if (allFiles.isEmpty()) {
        project.chat.add(ProjectChat("assistant", "The model replied, but produced no parseable files.", tookMs = scaffoldStamp()))
        return "0 files written"
    }
    val list = allFiles.toList()
    val chunkNote = if (chunks > 1) " (built in $chunks chunks)" else ""
    val truncNote = if (stillTruncated) " — still truncated after $chunks chunks; ask it to continue." else ""
    // Validate: confirm every written file is really on disk, non-empty.
    val verdict = validateScaffoldFiles(project, list.map { it.first })
    val verifyNote = if (verdict.ok) " Verified on disk." else " WARNING: ${verdict.detail}."
    project.chat.add(ProjectChat("assistant", "Scaffolded ${list.size} file(s)$chunkNote.\n${filesSummary(list)}$truncNote$verifyNote", tookMs = scaffoldStamp()))
    return "${list.size} file(s) written$chunkNote$truncNote$verifyNote"
}

private fun filesSummary(files: List<Pair<String, String>>): String =
    files.take(10).joinToString("\n") { "- ${it.first} (${it.second.length} chars)" }

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

/** Project memory: chat, steps and tasks survive restarts in a hidden dir. */
private fun stateDir(project: Project): File = File(project.dir, ".karen").apply { mkdirs() }

private fun enc(s: String): String =
    Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

private fun dec(s: String): String = try {
    String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
} catch (_: Exception) {
    ""
}

private fun saveProjectState(project: Project) {
    try {
        val dir = stateDir(project)
        File(dir, "chat.txt").writeText(
            project.chat.takeLast(200).joinToString("\n") { "${it.role}\t${enc(it.text)}\t${it.tookMs}" }
        )
        File(dir, "steps.txt").writeText(
            project.steps.joinToString("\n") { "${if (it.done) 1 else 0}\t${enc(it.text)}" }
        )
        File(dir, "tasks.txt").writeText(
            project.tasks.joinToString("\n") { "${if (it.done) 1 else 0}\t${enc(it.text)}" }
        )
    } catch (_: Exception) {
    }
}

/** Returns true when a previous session was restored. */
private fun loadProjectState(project: Project): Boolean {
    return try {
        val dir = File(project.dir, ".karen")
        if (!dir.exists()) return false
        var restored = false
        File(dir, "chat.txt").takeIf { it.exists() }?.let { f ->
            val items = f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 2 || (p[0] != "user" && p[0] != "assistant")) null
                else ProjectChat(p[0], dec(p[1]), p.getOrNull(2)?.toLongOrNull() ?: 0L)
            }
            if (items.isNotEmpty()) {
                project.chat.clear()
                project.chat.addAll(items)
                restored = true
            }
        }
        File(dir, "steps.txt").takeIf { it.exists() }?.let { f ->
            val items = f.readLines().mapNotNull { line ->
                val p = line.split('\t', limit = 2)
                if (p.size < 2) null else ProjectStep(dec(p[1]), p[0] == "1")
            }
            if (items.isNotEmpty()) {
                project.steps.clear()
                project.steps.addAll(items)
                restored = true
            }
        }
        File(dir, "tasks.txt").takeIf { it.exists() }?.let { f ->
            val items = f.readLines().mapNotNull { line ->
                val p = line.split('\t', limit = 2)
                if (p.size < 2) null else ProjectTask(dec(p[1]), p[0] == "1")
            }
            if (items.isNotEmpty()) {
                project.tasks.clear()
                project.tasks.addAll(items)
                restored = true
            }
        }
        restored
    } catch (_: Exception) {
        false
    }
}

@Composable
fun WorkspaceScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToHome: () -> Unit = {},
    onNavigateToModelManager: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    var activeTab by remember { mutableStateOf("Code") }
    val tabs = listOf("Code", "Plan", "Terminal", "Diff")

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val device = rememberDeviceTelemetry()
    val scope = rememberCoroutineScope()
    val projects = remember {
        mutableStateListOf<Project>().apply {
            UserPrefs.projects(ctx).forEach { (n, d) -> add(Project(n, d)) }
        }
    }
    var selected by remember { mutableStateOf<Project?>(null) }
    var showNewProject by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    // Per-card overflow menu + rename/delete state (keyed by project dir).
    var cardMenuDir by remember { mutableStateOf<String?>(null) }
    var renamingDir by remember { mutableStateOf<String?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var deletingDir by remember { mutableStateOf<String?>(null) }

    // System back: dismiss dialog first, then step out of the open project,
    // then route to Home — same in-app behaviour as the chat screen.
    // Cloud model for project chat — same selection as Chat (locals + keyed APIs).
    var selectedModel by remember { mutableStateOf(UserPrefs.models(ctx).firstOrNull { !isCloudProviderName(it) } ?: modelCatalog.first().name) }
    // Auto routing: best installed/connected model per project message.
    var autoRoute by remember { mutableStateOf(UserPrefs.autoRoute(ctx)) }
    var lastRouted by remember { mutableStateOf<String?>(null) }

    fun routeFor(prompt: String): String {
        if (!autoRoute) return selectedModel
        val locals = UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
        val keyed = cloudProviders.filter { UserPrefs.apiKey(ctx, it.id).isNotBlank() }
        val decision = routeModel(prompt, locals, keyed, UserPrefs.autoCloud(ctx))
        if (decision != null) {
            if (decision.modelName != lastRouted) {
                lastRouted = decision.modelName
                android.widget.Toast.makeText(ctx, "Auto: ${decision.modelName} (${decision.reason})", android.widget.Toast.LENGTH_SHORT).show()
            }
            return decision.modelName
        }
        return selectedModel
    }
    var effort by remember { mutableStateOf(UserPrefs.defaultEffort(ctx)) }
    var showModelSheet by remember { mutableStateOf(false) }

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
        when {
            showModelSheet -> { showModelSheet = false; true }
            showNewProject -> { showNewProject = false; true }
            renamingDir != null -> { renamingDir = null; true }
            deletingDir != null -> { deletingDir = null; true }
            cardMenuDir != null -> { cardMenuDir = null; true }
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
    val chatThreadState = androidx.compose.foundation.lazy.rememberLazyListState()

    // Keep the thinking indicator in view while a reply generates.
    LaunchedEffect(chatBusy) {
        if (!chatBusy) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        val total = chatThreadState.layoutInfo.totalItemsCount
        if (total > 0) {
            try { chatThreadState.animateScrollToItem(total - 1) } catch (_: Exception) {}
        }
    }

    fun sendProjectMessage(project: Project, text: String) {
        val t = text.trim()
        if (t.isBlank() || chatBusy) return
        chatBusy = true
        project.chat.add(ProjectChat("user", t))
        // Auto routing swaps in the best model for this prompt.
        val sendModel = routeFor(t)
        scope.launch {
            val sendStartMs = System.currentTimeMillis()
            /** Assistant replies stamp how long they took to appear. */
            fun addAssistant(text: String) {
                project.chat.add(ProjectChat("assistant", text, tookMs = System.currentTimeMillis() - sendStartMs))
            }
            // 1. Plan steps derived from the ask (the model restructures on send).
            val steps = buildStepsFor(t)
            project.steps.clear()
            steps.forEach { project.steps.add(ProjectStep(it)) }
            project.tasks.clear()
            steps.drop(1).forEach { project.tasks.add(ProjectTask(it)) }
            // 2. Project root on disk. No seed files are ever created here —
            //    content appears only from real model scaffolds, chat file
            //    blocks, or the user's own editor/terminal work.
            val dir = projectRootDir(project)
            // Tool routing: one decision drives scaffold / checks / web / chat.
            val tool = routeTool(t)
            // Helper: apply ///FILE blocks from any model reply so follow-up
            // prompts continue the same structure instead of starting over.
            suspend fun applyModelFiles(reply: String): Boolean {
                val files = parseScaffold(reply)
                if (files.isEmpty()) return false
                val summary = withContext(Dispatchers.IO) { writeScaffoldFiles(project, files) }
                project.filesVersion = System.currentTimeMillis()
                addAssistant("Applied to ${project.name}: $summary\n${filesSummary(files)}")
                return true
            }
            // 3. SCAFFOLD tool: the model writes a full multi-file scaffold
            //    into the project. Files land on real paths.
            if (tool == WorkspaceTool.SCAFFOLD) {
                val canCloud = findCloudProviderByName(sendModel)?.let { p ->
                    UserPrefs.apiKey(ctx, p.id).isNotBlank()
                } ?: false
                val canLocal = ModelDownloader.weightFileFor(ctx, sendModel) != null && KarenLlama.ready
                if (canCloud || canLocal) {
                    val threads = maxOf(2, minOf(6, Runtime.getRuntime().availableProcessors()))
                    val cloudScaffold = findCloudProviderByName(sendModel)
                    val cwCloudKey = cloudScaffold?.let { UserPrefs.apiKey(ctx, it.id) }.orEmpty()
                    val scaffoldMsg = scaffoldWithModel(
                        project, t, cloudScaffold, cwCloudKey,
                        weightFile = ModelDownloader.weightFileFor(ctx, sendModel),
                        effort = effort, threads = threads,
                        selectedModelName = sendModel,
                        windowTokens = UserPrefs.contextTokens(ctx),
                        modelOverride = cloudScaffold?.let { UserPrefs.apiModel(ctx, it.id) }.orEmpty().ifBlank { null },
                        sendStartMs = sendStartMs
                    )
                    addAssistant(scaffoldMsg)
                    project.filesVersion = System.currentTimeMillis()
                }
            }
            // 4. Terminal echo of the send + a recursive listing across the project dir.
            project.terminalLog.add("$ $t".take(120))
            val lsOut = runShell("ls -Rp | head -n 80", dir)
            project.terminalLog.add("[tool] ls -Rp\n$lsOut")
            // CHECKS tool: run compile & test now and report the verdict.
            if (tool == WorkspaceTool.CHECKS) {
                val checksOut = compileAndTest(dir)
                project.terminalLog.add("[tool] compile & test\n$checksOut")
                val verdict = if (checksOut.startsWith("Error") || "FAIL" in checksOut) {
                    ToolVerdict(false, "checks failing — see Terminal tab")
                } else {
                    ToolVerdict(true, "checks clean")
                }
                addAssistant(
                    if (verdict.ok) "Checks clean.\n$checksOut".take(1200)
                    else "Checks failing: ${verdict.detail}\n$checksOut".take(1200)
                )
            }
            // 5. Web context — only with prior consent (asked once in Chat) + enabled.
            var web: String? = null
            if (UserPrefs.webSearchEnabled(ctx) && UserPrefs.webSearchAsked(ctx) && WebSearch.needsSearch(t)) {
                web = try {
                    WebSearch.search(WebSearch.cleanQuery(t))
                        .takeIf { it.isNotEmpty() }
                        ?.let(WebSearch::formatForModel)
                } catch (_: Exception) {
                    null
                }
            }
            // Project structure + file contents so the model continues in place.
            // Scaled by the Settings content window (up to 16k).
            val window = UserPrefs.contextTokens(ctx)
            val cloudLimit = historyTurnsFor(window)
            val localLimit = localTurnsFor(window)
            val modelCtx = withContext(Dispatchers.IO) { projectContextForModel(dir, contextCharsFor(window)) }
            val buildInstruction =
                "You are Karen's workspace builder for project \"${project.name}\". " +
                    "Maintain the existing file structure. When changing code, output each changed file as " +
                    "///FILE: relative/path\n<complete file content>\n///END (up to 8 files, no fences, no prose inside blocks), " +
                    "then a 1-2 line summary. For discussion-only replies, answer normally with no ///FILE blocks." +
                    if (isReasoningModel(sendModel)) reasoningEffortHint(effort) else "" +
                    if (UserPrefs.explainerMode(ctx)) " $EXPLAINER_STYLE_GUIDE" else ""
            // 6. Assistant reply — live cloud call, on-device GGUF, else canned.
            val cloud = findCloudProviderByName(sendModel)
            val cloudKey = cloud?.let { UserPrefs.apiKey(ctx, it.id) }.orEmpty()
            val weightFile = if (cloud == null) ModelDownloader.weightFileFor(ctx, sendModel) else null
            if (cloud != null && cloudKey.isNotBlank()) {
                try {
                    val history = mutableListOf<Pair<String, String>>()
                    history.add("user" to "$buildInstruction\n\nProject files:\n$modelCtx")
                    project.chat.mapNotNullTo(history) { m ->
                        when (m.role) {
                            "user" -> "user" to m.text
                            "assistant" -> "assistant" to m.text
                            else -> null
                        }
                    }
                    val enriched = if (web != null) {
                        (history + ("user" to "Use these fresh web results if relevant:\n$web")).takeLast(cloudLimit)
                    } else history.takeLast(cloudLimit)
                    val reply = cloud.complete(
                        cloudKey,
                        enriched,
                        maxTokensFor(effort),
                        thinkingBudget = if (cloud.reasoning) thinkingBudgetFor(effort) else null,
                        historyLimit = cloudLimit,
                        model = UserPrefs.apiModel(ctx, cloud.id).ifBlank { null }
                    )
                    project.chat.add(ProjectChat("assistant", reply, tookMs = System.currentTimeMillis() - sendStartMs))
                    applyModelFiles(reply)
                } catch (e: CloudApiException) {
                    addAssistant("⚠ ${cloud.name} error (HTTP ${e.status}): ${e.message}")
                } catch (e: Exception) {
                    addAssistant("⚠ Could not reach ${cloud.name} — check internet and your API key. (${e.message})")
                }
            } else if (weightFile != null) {
                // Real on-device inference through the bundled llama.cpp core.
                if (!KarenLlama.ready) {
                    addAssistant("Local runtime failed to load on this device.")
                } else if (UserPrefs.thermalGuard(ctx) && device.batteryTempC >= UserPrefs.thermalLimitC(ctx)) {
                    addAssistant(
                        "Paused by Thermal Guard — battery ${"%.0f".format(device.batteryTempC)}°C " +
                            "is at/above your ${UserPrefs.thermalLimitC(ctx).toInt()}°C limit. Let the phone cool down."
                    )
                } else {
                    try {
                        val threads = maxOf(2, minOf(6, Runtime.getRuntime().availableProcessors()))
                        val loaded = withContext(Dispatchers.IO) {
                            KarenLlama.ensureLoaded(weightFile, sendModel, window, threads)
                        }
                        if (!loaded) {
                            addAssistant("Could not load $sendModel into RAM.")
                        } else {
                            val pairs = mutableListOf<Pair<String, String>>()
                            pairs.add("user" to "Project files:\n$modelCtx")
                            project.chat.mapNotNullTo(pairs) { m ->
                                when (m.role) {
                                    "user" -> "user" to m.text
                                    "assistant" -> "assistant" to m.text
                                    else -> null
                                }
                            }
                            val withWeb = if (web != null) {
                                (pairs + ("user" to "Use these fresh web results if relevant:\n$web")).takeLast(localLimit)
                            } else pairs.takeLast(localLimit)
                            val reply = withContext(Dispatchers.IO) {
                                KarenLlama.complete(
                                    buildInstruction,
                                    withWeb.map { it.first }.toTypedArray(),
                                    withWeb.map { it.second }.toTypedArray(),
                                    maxTokensFor(effort)
                                )
                            }
                            if (reply.isNotBlank()) {
                                project.chat.add(ProjectChat("assistant", reply, tookMs = System.currentTimeMillis() - sendStartMs))
                                applyModelFiles(reply)
                            } else {
                                addAssistant("The model returned an empty reply.")
                            }
                        }
                    } catch (e: Exception) {
                        addAssistant("Local model error: ${e.message}")
                    }
                }
            } else if (web != null) {
                addAssistant("Fresh web results:\n$web")
            } else {
                // No model answered: validate the web tool when it was the routed one.
                val webVerdict = if (tool == WorkspaceTool.WEB) validateWebResult(web) else null
                val webNote = if (webVerdict != null && !webVerdict.ok) {
                    " Web lookup came back empty — check connection and try again."
                } else ""
                addAssistant(
                    "Planned ${steps.size} steps and listed the project tree. See Plan / Code / Terminal.$webNote"
                )
            }
            saveProjectState(project)
            chatBusy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = if (autoRoute) "Auto → " + (lastRouted ?: selectedModel) else selectedModel,
            onMenuClick = onOpenDrawer,
            onModelClick = { showModelSheet = true },
            onBackClick = onNavigateToHome,
            effort = if (isReasoningModel(if (autoRoute) (lastRouted ?: selectedModel) else selectedModel)) effort else null,
            efforts = listOf("Low", "Medium", "High", "Max", "Extreme", "XHigh"),
            onSelectEffort = { effort = it },
            moreActions = listOf(
                Triple("Scaffold project from prompt", Icons.Default.AutoFixHigh) {
                    scope.launch {
                        // Guarded: the overflow menu also exists on the project
                        // list, where no project is open (old NPE crash).
                        val target = selected
                        if (target == null) {
                            android.widget.Toast.makeText(ctx, "Open a project first", android.widget.Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        chatBusy = true
                        val menuStartMs = System.currentTimeMillis()
                        val threads = maxOf(2, minOf(6, Runtime.getRuntime().availableProcessors()))
                        val cloud = findCloudProviderByName(selectedModel)
                        val cloudKey = cloud?.let { UserPrefs.apiKey(ctx, it.id) }.orEmpty()
                        val scaffold = scaffoldWithModel(
                            target,
                            "Create a small but complete Gradle hello-world project",
                            cloud, cloudKey,
                            weightFile = ModelDownloader.weightFileFor(ctx, selectedModel),
                            effort = effort, threads = threads,
                            selectedModelName = selectedModel,
                            windowTokens = UserPrefs.contextTokens(ctx),
                            modelOverride = cloud?.let { UserPrefs.apiModel(ctx, it.id) }.orEmpty().ifBlank { null },
                            sendStartMs = menuStartMs
                        )
                        target.chat.add(ProjectChat("assistant", scaffold, tookMs = System.currentTimeMillis() - menuStartMs))
                        target.filesVersion = System.currentTimeMillis()
                        saveProjectState(target)
                        chatBusy = false
                    }
                },
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
                            Box {
                                IconButton(
                                    onClick = { cardMenuDir = p.dir },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Project options", tint = colors.textMuted, modifier = Modifier.size(17.dp))
                                }
                                DropdownMenu(
                                    expanded = cardMenuDir == p.dir,
                                    onDismissRequest = { cardMenuDir = null },
                                    modifier = Modifier.background(colors.surface)
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Rename", color = colors.textPrimary, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp)) },
                                        onClick = {
                                            cardMenuDir = null
                                            renameInput = p.name
                                            renamingDir = p.dir
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Duplicate", color = colors.textPrimary, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp)) },
                                        onClick = {
                                            cardMenuDir = null
                                            scope.launch {
                                                val src = withContext(Dispatchers.IO) { File(p.dir) }
                                                val base = "${p.name} copy"
                                                var target = base
                                                var n = 2
                                                while (projects.any { it.name == target }) {
                                                    target = "$base $n"
                                                    n++
                                                }
                                                val dst = File(src.parentFile, target)
                                                val ok = withContext(Dispatchers.IO) {
                                                    try {
                                                        src.copyRecursively(dst, overwrite = false)
                                                        File(dst, ".karen").deleteRecursively()
                                                        true
                                                    } catch (_: Exception) { false }
                                                }
                                                if (ok) {
                                                    projects.add(Project(target, dst.absolutePath))
                                                    UserPrefs.saveProjects(ctx, projects.map { it.name to it.dir })
                                                    android.widget.Toast.makeText(ctx, "Duplicated as $target", android.widget.Toast.LENGTH_SHORT).show()
                                                } else {
                                                    android.widget.Toast.makeText(ctx, "Duplicate failed", android.widget.Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete", color = colors.accentRed, fontSize = 13.sp) },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = colors.accentRed, modifier = Modifier.size(16.dp)) },
                                        onClick = {
                                            cardMenuDir = null
                                            deletingDir = p.dir
                                        }
                                    )
                                }
                            }
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
                            colors = karenFieldColors(colors)
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

            // Rename project: renames the on-disk folder, keeps its files.
            val renaming = renamingDir?.let { dir -> projects.firstOrNull { it.dir == dir } }
            if (renaming != null) {
                AlertDialog(
                    onDismissRequest = { renamingDir = null },
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                    textContentColor = colors.textSecondary,
                    title = { Text("Rename project") },
                    text = {
                        OutlinedTextField(
                            value = renameInput,
                            onValueChange = { renameInput = it },
                            label = { Text("Project name", color = colors.textMuted) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                            colors = karenFieldColors(colors)
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val clean = renameInput.trim().replace('/', '_').replace('\\', '_')
                            if (clean.isBlank() || clean.contains("..")) return@TextButton
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    try {
                                        val src = File(renaming.dir)
                                        if (clean == renaming.name) return@withContext true
                                        if (projects.any { it.name == clean && it.dir != renaming.dir }) return@withContext false
                                        val dst = File(src.parentFile, clean)
                                        if (dst.exists()) return@withContext false
                                        if (!src.renameTo(dst)) return@withContext false
                                        withContext(Dispatchers.Main) {
                                            val idx = projects.indexOfFirst { it.dir == renaming.dir }
                                            if (idx >= 0) projects[idx] = renaming.copy(name = clean, dir = dst.absolutePath)
                                            UserPrefs.saveProjects(ctx, projects.map { it.name to it.dir })
                                        }
                                        true
                                    } catch (_: Exception) { false }
                                }
                                if (!ok) {
                                    android.widget.Toast.makeText(ctx, "Rename failed — name taken?", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                renamingDir = null
                            }
                        }) { Text("Rename", color = colors.accentGreen) }
                    },
                    dismissButton = { TextButton(onClick = { renamingDir = null }) { Text("Cancel", color = colors.textMuted) } }
                )
            }

            // Delete project: removes the folder and its files for good.
            val deleting = deletingDir?.let { dir -> projects.firstOrNull { it.dir == dir } }
            if (deleting != null) {
                AlertDialog(
                    onDismissRequest = { deletingDir = null },
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                    textContentColor = colors.textSecondary,
                    title = { Text("Delete project?") },
                    text = { Text("“${deleting.name}” and all its files will be removed from this device. This cannot be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try { File(deleting.dir).deleteRecursively() } catch (_: Exception) {}
                                }
                                projects.removeAll { it.dir == deleting.dir }
                                if (selected?.dir == deleting.dir) selected = null
                                UserPrefs.saveProjects(ctx, projects.map { it.name to it.dir })
                                deletingDir = null
                                android.widget.Toast.makeText(ctx, "Deleted ${deleting.name}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Delete", color = colors.accentRed) }
                    },
                    dismissButton = { TextButton(onClick = { deletingDir = null }) { Text("Cancel", color = colors.textMuted) } }
                )
            }
            return@Column
        }

        val project = selected!!
        // Restore a previous session on open; archive on leave.
        LaunchedEffect(project) {
            if (project.chat.isEmpty() && project.steps.isEmpty() && project.tasks.isEmpty()) {
                withContext(Dispatchers.IO) { loadProjectState(project) }
            }
        }
        DisposableEffect(project) {
            onDispose { saveProjectState(project) }
        }
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
            state = chatThreadState,
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
                    UserMessageBubble(
                        text = m.text,
                        onEdit = { edited ->
                            project.chat[i] = ProjectChat("user", edited)
                            saveProjectState(project)
                        }
                    )
                } else {
                    val clipboard = LocalClipboardManager.current
                    var copied by remember(m.text) { mutableStateOf(false) }
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
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(m.text))
                                    copied = true
                                },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = "Copy reply",
                                    tint = if (copied) colors.accentGreen else colors.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        val thinkSplit = remember(m.text) { splitThinkBlock(m.text) }
                        if (thinkSplit.first != null) {
                            ThoughtAccordion(
                                thoughtDuration = "Reasoning trace",
                                content = thinkSplit.first!!,
                                initialExpanded = false
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (thinkSplit.second.isNotBlank()) {
                            SelectionContainer {
                                MarkdownText(
                                    text = thinkSplit.second,
                                    color = colors.textPrimary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                        if (m.tookMs > 0) {
                            Text(
                                text = "took ${formatDuration(m.tookMs)}",
                                color = colors.textMuted,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }
            // Live thinking animation while the project reply generates.
            if (chatBusy) {
                item {
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
                        ThinkingIndicator(label = "Thinking")
                    }
                }
            }
        }

        // Artifact tabs — webapp-mirror pill bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(50.dp))
                .padding(3.dp)
        ) {
            tabs.forEach { tab ->
                val isSelected = activeTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50.dp))
                        .background(if (isSelected) colors.surfaceHover else Color.Transparent)
                        .border(
                            1.dp,
                            if (isSelected) colors.accentGreen.copy(alpha = 0.45f) else Color.Transparent,
                            RoundedCornerShape(50.dp)
                        )
                        .clickable { activeTab = tab }
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        color = if (isSelected) colors.accentGreen else colors.textMuted,
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
                colors = karenFieldColors(colors)
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

    // Model selector (locals + keyed cloud APIs), shared with Chat.
    if (showModelSheet) {
        ModelSelectorSheet(
            selectedModel = selectedModel,
            onSelectModel = {
                selectedModel = it
                if (autoRoute) {
                    autoRoute = false
                    UserPrefs.setAutoRoute(ctx, false)
                }
            },
            onDismiss = { showModelSheet = false },
            onOpenModelManager = onNavigateToModelManager,
            autoRoute = autoRoute,
            onSelectAuto = {
                autoRoute = true
                UserPrefs.setAutoRoute(ctx, true)
            }
        )
    }
}

@Composable
private fun CodeCanvasView(project: Project) {
    val colors = LocalKarenColors.current
    val scope = rememberCoroutineScope()
    val root = remember(project) { File(project.dir) }
    var entries by remember(project) { mutableStateOf(emptyList<File>()) }
    var expanded by remember(project) { mutableStateOf(setOf("")) }
    var currentRel by remember(project) { mutableStateOf<String?>(null) }
    var content by remember(project) { mutableStateOf("") }
    var savedText by remember(project) { mutableStateOf("") }
    var loadedFor by remember(project) { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var previewMode by remember { mutableStateOf(true) }
    var binaryNote by remember(project) { mutableStateOf<String?>(null) }
    var status by remember(project) { mutableStateOf<String?>(null) }
    var statusOk by remember(project) { mutableStateOf(true) }
    var refreshTick by remember(project) { mutableStateOf(0L) }
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var confirmDeleteRel by remember { mutableStateOf<String?>(null) }
    var nameInput by remember { mutableStateOf("") }

    fun relOf(f: File): String = relPathOf(root, f)

    fun parentRelOf(f: File): String {
        val p = f.parentFile ?: return ""
        return if (p.absolutePath == root.absolutePath) "" else relOf(p)
    }

    fun ancestorsExpanded(f: File): Boolean {
        var p = f.parentFile ?: return true
        while (p.absolutePath != root.absolutePath) {
            if (!expanded.contains(relOf(p))) return false
            p = p.parentFile ?: return true
        }
        return true
    }

    suspend fun rescan(selectRel: String? = currentRel) {
        val list = withContext(Dispatchers.IO) {
            root.mkdirs()
            listProjectEntries(root)
        }
        entries = list
        val rels = list.map { relOf(it) }.toSet()
        if (selectRel != null && rels.contains(selectRel)) {
            currentRel = selectRel
        } else if (currentRel != null && !rels.contains(currentRel)) {
            currentRel = null
            content = ""
            savedText = ""
            loadedFor = null
            dirty = false
            binaryNote = null
        }
        if (currentRel == null) {
            val mainRel = list.firstOrNull { !it.isDirectory && relOf(it) == "main.txt" }?.let { relOf(it) }
                ?: list.firstOrNull { !it.isDirectory }?.let { relOf(it) }
            currentRel = mainRel
        }
    }

    suspend fun loadRel(rel: String) {
        val target = File(root, rel)
        binaryNote = null
        if (target.isDirectory) return
        if (isBinaryName(target.name) || target.length() > 512 * 1024) {
            withContext(Dispatchers.Main) {
                content = ""
                savedText = ""
                loadedFor = rel
                dirty = false
                binaryNote = if (isBinaryName(target.name)) "Binary file — preview not supported (${formatFileSize(target.length())})"
                else "File too large to edit on-device (${formatFileSize(target.length())})"
            }
            return
        }
        val text = withContext(Dispatchers.IO) {
            try { target.readText() } catch (e: Exception) { "Error: ${e.message}" }
        }.take(15000)
        content = text
        savedText = text
        loadedFor = rel
        dirty = false
        previewMode = true
    }

    // Initial + model-write refresh. Typing never triggers this — only
    // filesVersion (model scaffold) and explicit refresh do. Unsaved edits
    // (dirty) are never clobbered by a model refresh.
    LaunchedEffect(project, project.filesVersion, refreshTick) {
        val keep = currentRel
        rescan(selectRel = keep)
        val cur = currentRel
        if (cur != null && !dirty && File(root, cur).isFile) loadRel(cur)
    }

    // Back dismisses file dialogs first (screen-level handler runs after).
    if (showNewFile || showNewFolder || showRename || confirmDeleteRel != null) {
        androidx.activity.compose.BackHandler {
            showNewFile = false
            showNewFolder = false
            showRename = false
            confirmDeleteRel = null
        }
    }

    val visible = entries.filter { ancestorsExpanded(it) }
    val currentFile = currentRel?.let { File(root, it) }?.takeIf { it.isFile }

    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        // Toolbar: breadcrumb + file ops
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                currentRel ?: "No file selected",
                color = if (currentRel != null) colors.textPrimary else colors.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (dirty) {
                Box(Modifier.padding(end = 4.dp).size(8.dp).clip(CircleShape).background(colors.accentAmber))
            }
            IconButton(onClick = { nameInput = ""; showNewFile = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.NoteAdd, contentDescription = "New file", tint = colors.textSecondary, modifier = Modifier.size(17.dp))
            }
            IconButton(onClick = { nameInput = ""; showNewFolder = true }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "New folder", tint = colors.textSecondary, modifier = Modifier.size(17.dp))
            }
            IconButton(
                onClick = { nameInput = currentFile?.name ?: ""; showRename = true },
                enabled = currentFile != null,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Rename", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }
            IconButton(
                onClick = { confirmDeleteRel = currentRel },
                enabled = currentRel != null,
                modifier = Modifier.size(30.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { refreshTick = System.currentTimeMillis() }, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            // Explorer
            Column(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (colors.isDark) Color(0xFF0F0F11) else Color(0xFFF4F4F5))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    "FILES · ${entries.count { it.isFile }}",
                    color = colors.textMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                "Empty — ask the model to scaffold or tap +",
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(visible.size) { idx ->
                        val f = visible[idx]
                        val rel = relOf(f)
                        val depth = depthOf(root, f)
                        val isDir = f.isDirectory
                        val isSel = rel == currentRel
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSel) colors.accentGreen.copy(alpha = 0.16f) else Color.Transparent)
                                .clickable {
                                    if (isDir) {
                                        expanded = if (expanded.contains(rel)) expanded - rel else expanded + rel
                                    } else {
                                        currentRel = rel
                                        scope.launch { loadRel(rel) }
                                    }
                                }
                                .padding(start = (6 + depth * 12).dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
                        ) {
                            if (isDir) {
                                Icon(
                                    if (expanded.contains(rel)) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = colors.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Icon(
                                    if (expanded.contains(rel)) Icons.Default.FolderOpen else Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = colors.accentAmber,
                                    modifier = Modifier.size(15.dp)
                                )
                            } else {
                                Spacer(Modifier.width(14.dp))
                                Icon(
                                    fileIconFor(f.name, false),
                                    contentDescription = null,
                                    tint = if (isSel) colors.accentGreen else colors.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                f.name,
                                color = if (isSel) colors.textPrimary else colors.textSecondary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // Editor
            Column(modifier = Modifier.weight(1.5f).fillMaxHeight()) {
                if (currentFile == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a file to read & edit", color = colors.textMuted, fontSize = 12.sp)
                    }
                } else if (binaryNote != null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.InsertDriveFile, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(binaryNote!!, color = colors.textMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(currentFile.name, color = colors.textSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                } else if (previewMode) {
                    // Webapp-mirror colored preview: line numbers + syntax tint.
                    val previewLines = remember(content) { content.lines() }
                    SelectionContainer {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (colors.isDark) Color(0xFF0F0F11) else Color(0xFFF4F4F5))
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                .padding(vertical = 8.dp)
                        ) {
                            items(previewLines.size) { idx ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        "${idx + 1}",
                                        color = colors.textMuted,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(28.dp)
                                    )
                                    Text(
                                        highlightCodeLine(
                                            previewLines[idx].ifBlank { " " },
                                            colors.textPrimary,
                                            colors.textMuted
                                        ),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it; dirty = it != savedText },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        colors = karenFieldColors(colors)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { previewMode = !previewMode },
                        enabled = currentFile != null && binaryNote == null,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            if (previewMode) Icons.Default.Edit else Icons.Default.Visibility,
                            contentDescription = if (previewMode) "Edit" else "Preview",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (previewMode) "Edit" else "Preview", fontSize = 11.5.sp)
                    }
                    Button(
                        onClick = {
                            val f = currentFile ?: return@Button
                            val rel = currentRel ?: return@Button
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val prev = try { if (f.exists()) f.readText() else "" } catch (_: Exception) { "" }
                                    f.parentFile?.mkdirs()
                                    f.writeText(content)
                                    withContext(Dispatchers.Main) {
                                        project.codeOld = prev
                                        project.codeNew = content
                                        loadedFor = rel
                                        savedText = content
                                        dirty = false
                                        status = "Saved $rel (${content.length} chars)"
                                        statusOk = true
                                        refreshTick = System.currentTimeMillis()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        status = "Error saving: ${e.message}"
                                        statusOk = false
                                    }
                                }
                            }
                        },
                        enabled = currentFile != null && binaryNote == null && content.length <= 15000,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Save", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val out = compileAndTest(root)
                                status = out.take(600)
                                statusOk = !out.startsWith("Error") && !out.contains("FAIL")
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Compile & Test", fontSize = 11.5.sp)
                    }
                }
                if (status != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(status!!, color = if (statusOk) colors.accentGreen else colors.accentRed, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, maxLines = 4)
                }
            }
        }
    }

    // --- New file dialog (accepts nested paths like src/main/App.kt) ---
    if (showNewFile) {
        AlertDialog(
            onDismissRequest = { showNewFile = false },
            containerColor = colors.surface,
            title = { Text("New file", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Path, e.g. src/main/App.kt") },
                    placeholder = { Text("src/main/App.kt") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontFamily = FontFamily.Monospace),
                    colors = karenFieldColors(colors)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val rel = nameInput.trim()
                    if (rel.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val target = safeScaffoldPath(project, rel)
                            var err: String? = null
                            if (target == null) err = "Invalid path."
                            else try {
                                target.parentFile?.mkdirs()
                                if (!target.exists()) target.writeText("")
                                val newRel = relOf(target)
                                withContext(Dispatchers.Main) {
                                    val dirRel = parentRelOf(target)
                                    if (dirRel.isNotBlank()) {
                                        var chain = dirRel
                                        val toAdd = mutableSetOf<String>()
                                        while (chain.isNotBlank()) {
                                            toAdd.add(chain)
                                            val pf = File(root, chain).parentFile
                                            chain = if (pf == null || pf.absolutePath == root.absolutePath) "" else relOf(pf)
                                        }
                                        expanded = expanded + toAdd
                                    } else {
                                        expanded = expanded + ""
                                    }
                                    showNewFile = false
                                    nameInput = ""
                                    status = "Created $newRel"
                                    statusOk = true
                                    currentRel = newRel
                                    loadRel(newRel)
                                    refreshTick = System.currentTimeMillis()
                                }
                            } catch (e: Exception) {
                                err = e.message
                            }
                            if (err != null) withContext(Dispatchers.Main) {
                                status = "Error: $err"
                                statusOk = false
                            }
                        }
                    }
                }) { Text("Create", color = colors.accentGreen) }
            },
            dismissButton = { TextButton(onClick = { showNewFile = false }) { Text("Cancel", color = colors.textMuted) } }
        )
    }
    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            containerColor = colors.surface,
            title = { Text("New folder", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("Path, e.g. src/assets") },
                    placeholder = { Text("src/assets") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontFamily = FontFamily.Monospace),
                    colors = karenFieldColors(colors)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val rel = nameInput.trim()
                    if (rel.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            val target = safeScaffoldPath(project, rel.trimEnd('/') + "/placeholder.keep")
                                ?.parentFile
                            var err: String? = null
                            if (target == null) err = "Invalid path."
                            else try {
                                target.mkdirs()
                                withContext(Dispatchers.Main) {
                                    expanded = expanded + relOf(target)
                                    showNewFolder = false
                                    nameInput = ""
                                    status = "Created ${relOf(target)}/"
                                    statusOk = true
                                    refreshTick = System.currentTimeMillis()
                                }
                            } catch (e: Exception) {
                                err = e.message
                            }
                            if (err != null) withContext(Dispatchers.Main) {
                                status = "Error: $err"
                                statusOk = false
                            }
                        }
                    }
                }) { Text("Create", color = colors.accentGreen) }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel", color = colors.textMuted) } }
        )
    }
    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            containerColor = colors.surface,
            title = { Text("Rename", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("New name") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                    colors = karenFieldColors(colors)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val rel = currentRel ?: return@TextButton
                    val clean = nameInput.trim().replace('/', '_').replace('\\', '_')
                    if (clean.isBlank() || clean.contains("..")) return@TextButton
                    scope.launch(Dispatchers.IO) {
                        try {
                            val src = File(root, rel)
                            val dst = File(src.parentFile, clean)
                            val ok = src.renameTo(dst)
                            withContext(Dispatchers.Main) {
                                showRename = false
                                if (ok) {
                                    currentRel = relOf(dst)
                                    loadRel(relOf(dst))
                                    status = "Renamed to ${relOf(dst)}"
                                    statusOk = true
                                } else {
                                    status = "Rename failed."
                                    statusOk = false
                                }
                                refreshTick = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                status = "Error: ${e.message}"
                                statusOk = false
                            }
                        }
                    }
                }) { Text("Rename", color = colors.accentGreen) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel", color = colors.textMuted) } }
        )
    }
    val delRel = confirmDeleteRel
    if (delRel != null) {
        AlertDialog(
            onDismissRequest = { confirmDeleteRel = null },
            containerColor = colors.surface,
            title = { Text("Delete?", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("Delete $delRel and its contents? This cannot be undone.", color = colors.textSecondary, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val target = File(root, delRel)
                            if (target.isDirectory) target.deleteRecursively() else target.delete()
                            withContext(Dispatchers.Main) {
                                if (currentRel == delRel) {
                                    currentRel = null
                                    content = ""
                                    savedText = ""
                                    loadedFor = null
                                    dirty = false
                                    binaryNote = null
                                }
                                confirmDeleteRel = null
                                status = "Deleted $delRel"
                                statusOk = true
                                refreshTick = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                status = "Error: ${e.message}"
                                statusOk = false
                                confirmDeleteRel = null
                            }
                        }
                    }
                }) { Text("Delete", color = colors.accentRed) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteRel = null }) { Text("Cancel", color = colors.textMuted) } }
        )
    }
}

@Composable
private fun PlanNodeDot(done: Boolean, active: Boolean) {
    val colors = LocalKarenColors.current
    // Gentle pulse on the current step only — everything else stays static.
    val pulse = rememberInfiniteTransition(label = "plan_node")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "node_pulse"
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "node_halo"
    )
    Box(
        modifier = Modifier.size(32.dp),
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .scale(scale)
                    .clip(CircleShape)
                    .background(colors.accentGreen.copy(alpha = alpha))
            )
        }
        Crossfade(targetState = done, label = "node_state") { isDone ->
            if (isDone) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(colors.accentGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Done",
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (active) colors.accentGreen.copy(alpha = 0.25f) else Color.Transparent)
                        .border(
                            1.5.dp,
                            if (active) colors.accentGreen else colors.textMuted,
                            CircleShape
                        )
                )
            }
        }
    }
}

/** Evidence verdict for a model plan step: manual taps can't fake it. */
private data class StepVerdict(val ok: Boolean, val reason: String)

/**
 * Verifies a plan step against real project state. Classification uses the
 * fixed template prefix (startsWith) so user prompt text embedded in step 1
 * can't trigger the wrong check. A step counts as done only when its work
 * actually exists: replies in chat, files on disk, clean checks.
 */
private suspend fun verifyPlanStep(project: Project, index: Int): StepVerdict {
    val step = project.steps.getOrNull(index) ?: return StepVerdict(false, "Step not found.")
    val t = step.text.lowercase()
    val root = File(project.dir)
    // Final review: everything before it verified + files exist.
    if (t.startsWith("review")) {
        val priorPending = project.steps.mapIndexedNotNull { i, s -> if (i != index && !s.done) i + 1 else null }
        if (priorPending.isNotEmpty()) return StepVerdict(false, "Steps ${priorPending.joinToString(", ")} still pending.")
        val hasFiles = withContext(Dispatchers.IO) {
            listProjectEntries(root).any { it.isFile && it.name != "placeholder.keep" && it.length() > 0 }
        }
        return if (hasFiles) StepVerdict(true, "Prior steps done, files present")
        else StepVerdict(false, "No project files yet — Code tab is empty.")
    }
    if (t.startsWith("understand")) {
        return if (project.chat.any { it.role == "assistant" }) StepVerdict(true, "Assistant replied")
        else StepVerdict(false, "No assistant reply yet — send a message first.")
    }
    if (t.startsWith("break")) {
        return if (project.steps.size >= 2 && project.chat.any { it.role == "user" }) StepVerdict(true, "Plan generated")
        else StepVerdict(false, "Plan not generated yet — send a message first.")
    }
    if (t.startsWith("implement")) {
        val count = withContext(Dispatchers.IO) {
            listProjectEntries(root).count { it.isFile && it.name != "placeholder.keep" && it.length() > 0 }
        }
        return if (count > 0) StepVerdict(true, "$count file(s) in project")
        else StepVerdict(false, "No project files yet — Code tab is empty.")
    }
    if (t.startsWith("run")) {
        if (project.terminalLog.isEmpty()) return StepVerdict(false, "Terminal never ran — open the Terminal tab.")
        val out = compileAndTest(root)
        return when {
            out.startsWith("Error") -> StepVerdict(false, out.take(120))
            "FAIL" in out -> StepVerdict(false, "Checks failing — see Terminal tab.")
            else -> StepVerdict(true, "Checks clean")
        }
    }
    // Unknown step text (future restructures): only when everything before it is done.
    val priorPending = project.steps.mapIndexedNotNull { i, s -> if (i < index && !s.done) i + 1 else null }
    return if (priorPending.isEmpty()) StepVerdict(true, "Prior steps done")
    else StepVerdict(false, "Steps ${priorPending.joinToString(", ")} still pending.")
}

@Composable
private fun PlanCanvasView(project: Project) {
    val colors = LocalKarenColors.current
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var newTask by remember { mutableStateOf("") }
    var verifyingIndex by remember { mutableStateOf<Int?>(null) }
    var verifyMsg by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val remaining = project.steps.count { !it.done } + project.tasks.count { !it.done }
    val verifiedCount = project.steps.count { it.done }

    /** Verify-only: no manual check-off — a step counts only while its evidence holds. */
    fun requestStepDone(i: Int) {
        val s = project.steps.getOrNull(i) ?: return
        if (verifyingIndex != null) return
        scope.launch {
            verifyingIndex = i
            verifyMsg = null
            val v = verifyPlanStep(project, i)
            if (v.ok) {
                project.steps[i] = project.steps.getOrNull(i)?.copy(done = true) ?: s.copy(done = true)
            } else {
                project.steps[i] = project.steps.getOrNull(i)?.copy(done = false) ?: s.copy(done = false)
                verifyMsg = i to v.reason
                android.widget.Toast.makeText(ctx, v.reason, android.widget.Toast.LENGTH_SHORT).show()
            }
            verifyingIndex = null
            saveProjectState(project)
        }
    }

    // Honest progress: every step is re-verified against real project state
    // whenever chat or terminal activity lands — marks appear only while the
    // work truly exists, and vanish again if it regresses.
    val chatSig = project.chat.size
    val termSig = project.terminalLog.size
    val filesSig = project.filesVersion
    LaunchedEffect(chatSig, termSig, filesSig) {
        if (project.steps.isEmpty() || verifyingIndex != null) return@LaunchedEffect
        var changed = false
        project.steps.forEachIndexed { i, s ->
            val ok = verifyPlanStep(project, i).ok
            if (ok != s.done) {
                project.steps[i] = s.copy(done = ok)
                changed = true
            }
        }
        if (changed) saveProjectState(project)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Plan · $verifiedCount of ${project.steps.size} verified · $remaining remaining", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Marks appear only while the work really exists — tap a step to re-check.",
                color = colors.textMuted,
                fontSize = 11.5.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (project.steps.isEmpty() && project.tasks.isEmpty()) {
                Text("No data found — send a message and the plan appears here.", color = colors.textMuted, fontSize = 12.sp)
            }
        }
        items(project.steps.size) { i ->
            val s = project.steps[i]
            val isActive = !s.done && project.steps.take(i).all { it.done }
            // Staggered entrance — each node fades/slides in after the previous.
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(
                    animationSpec = tween(350, delayMillis = i * 110, easing = FastOutSlowInEasing)
                ) + slideInVertically(
                    animationSpec = tween(350, delayMillis = i * 110, easing = FastOutSlowInEasing),
                    initialOffsetY = { it / 3 }
                )
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlanNodeDot(done = s.done, active = isActive)
                        Spacer(Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceHover.copy(alpha = 0.6f))
                                .border(1.dp, if (isActive) colors.accentGreen.copy(alpha = 0.5f) else colors.border, RoundedCornerShape(10.dp))
                                .clickable { requestStepDone(i) }
                                .padding(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (verifyingIndex == i) {
                                    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = colors.accentGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text("${i + 1}. ${s.text}", color = if (s.done) colors.textMuted else colors.textPrimary, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                            }
                            // Failed verification explains what is actually missing.
                            if (verifyMsg?.first == i) {
                                Spacer(Modifier.height(4.dp))
                                Text(verifyMsg!!.second, color = colors.accentAmber, fontSize = 11.5.sp)
                            } else if (!s.done) {
                                Text(
                                    "Tap the step to verify against project state",
                                    color = colors.textMuted,
                                    fontSize = 10.5.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            } else {
                                Text(
                                    "Verified complete",
                                    color = colors.accentGreen,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                    // Link to the next node — green once this step is done.
                    if (i < project.steps.size - 1) {
                        Box(
                            modifier = Modifier
                                .padding(start = 15.dp)
                                .width(2.dp)
                                .height(10.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(
                                    if (s.done) colors.accentGreen.copy(alpha = 0.6f)
                                    else colors.border
                                )
                        )
                    }
                }
            }
        }
        if (project.tasks.isNotEmpty()) {
            item { Text("Tasks · manual check-off (your own todos)", color = colors.textMuted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
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
                    colors = karenFieldColors(colors)
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
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var cmd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(true) }
    // Git: remote stored on-device per project; credentials never echoed.
    var gitRemoteUrl by remember(project) { mutableStateOf(UserPrefs.gitRemote(ctx, project.dir)) }
    var gitBusy by remember { mutableStateOf(false) }
    var showRemoteDialog by remember { mutableStateOf(false) }
    var remoteInput by remember { mutableStateOf("") }
    var showCommitDialog by remember { mutableStateOf(false) }
    var commitInput by remember { mutableStateOf("") }
    val termBg = if (colors.isDark) Color(0xFF09090B) else Color(0xFFF4F4F5)
    val termText = if (colors.isDark) Color(0xFFECECEC) else Color(0xFF1C1C1E)
    val termMuted = if (colors.isDark) Color(0xFF8E8E93) else Color(0xFF6B6B70)

    if (showRemoteDialog || showCommitDialog) {
        androidx.activity.compose.BackHandler {
            showRemoteDialog = false
            showCommitDialog = false
        }
    }

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
                colors = karenFieldColors(colors)
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
        // Git row: commit the project snapshot, push to the stored remote.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = termMuted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (gitRemoteUrl.isBlank()) "No remote" else maskRemote(gitRemoteUrl),
                color = termMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = { remoteInput = gitRemoteUrl; showRemoteDialog = true },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) { Text("Remote", color = colors.accentGreen, fontSize = 11.5.sp) }
            OutlinedButton(
                onClick = { commitInput = ""; showCommitDialog = true },
                enabled = !gitBusy,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) { Text("Commit", fontSize = 11.5.sp) }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (gitBusy) return@Button
                    gitBusy = true
                    scope.launch {
                        val root = File(project.dir)
                        val ver = runShell("git --version", root)
                        if (!ver.contains("git version")) {
                            result = "git is not available on this device — push from a machine with git, or copy files out of ${project.name}."
                            resultOk = false
                        } else {
                            val out = gitPushCurrent(root, gitRemoteUrl)
                            result = out.take(800)
                            resultOk = !out.startsWith("No remote") && ("Done" in out || "up-to-date" in out || "->" in out || "Everything" in out)
                            project.terminalLog.add("[git] push\n$out".take(1200))
                        }
                        gitBusy = false
                    }
                },
                enabled = !gitBusy,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text(if (gitBusy) "…" else "Push", fontSize = 11.5.sp) }
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

    if (showRemoteDialog) {
        AlertDialog(
            onDismissRequest = { showRemoteDialog = false },
            containerColor = colors.surface,
            title = { Text("Git remote", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = remoteInput,
                        onValueChange = { remoteInput = it },
                        label = { Text("origin URL (https or ssh)") },
                        placeholder = { Text("https://github.com/you/repo.git") },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        colors = karenFieldColors(colors)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Stored on-device only. URLs with tokens are masked on screen and in logs.", color = colors.textMuted, fontSize = 11.5.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = remoteInput.trim()
                    UserPrefs.setGitRemote(ctx, project.dir, clean)
                    gitRemoteUrl = clean
                    showRemoteDialog = false
                    project.terminalLog.add("[git] remote set (URL hidden)")
                }) { Text("Save", color = colors.accentGreen) }
            },
            dismissButton = { TextButton(onClick = { showRemoteDialog = false }) { Text("Cancel", color = colors.textMuted) } }
        )
    }
    if (showCommitDialog) {
        AlertDialog(
            onDismissRequest = { showCommitDialog = false },
            containerColor = colors.surface,
            title = { Text("Commit snapshot", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = commitInput,
                    onValueChange = { commitInput = it },
                    label = { Text("Commit message") },
                    placeholder = { Text("Karen workspace update") },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                    colors = karenFieldColors(colors)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCommitDialog = false
                    gitBusy = true
                    val msg = commitInput
                    scope.launch {
                        val root = File(project.dir)
                        val ver = runShell("git --version", root)
                        if (!ver.contains("git version")) {
                            result = "git is not available on this device."
                            resultOk = false
                        } else {
                            val out = gitCommitAll(root, msg)
                            result = out.take(800)
                            resultOk = !out.startsWith("Error") && ("nothing to commit" in out || "files changed" in out || "create mode" in out || "master" in out || "main" in out)
                            project.terminalLog.add("[git] commit\n$out".take(1200))
                        }
                        gitBusy = false
                    }
                }) { Text("Commit", color = colors.accentGreen) }
            },
            dismissButton = { TextButton(onClick = { showCommitDialog = false }) { Text("Cancel", color = colors.textMuted) } }
        )
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
