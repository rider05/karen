package com.karen.ui

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tool routing + validation for the workspace pipeline.
 *
 * Routing picks the right tool per prompt (build vs lookup vs checks vs
 * plain chat) so the model doesn't have to guess from free text. Validation
 * verifies each tool's output actually happened — files on disk, exit
 * status captured, web context non-empty — so progress can never be faked.
 */
enum class WorkspaceTool { CHAT, WEB, SCAFFOLD, CHECKS }

/** Route a prompt to the right tool. Order: explicit checks → build → web → chat. */
fun routeTool(prompt: String): WorkspaceTool {
    val t = " $prompt ".lowercase()
    if (listOf("compile", "run tests", "run the tests", "lint", "syntax check", "verify build", "typecheck").any { t.contains(it) }) {
        return WorkspaceTool.CHECKS
    }
    if (projectNeedsScaffold(prompt)) return WorkspaceTool.SCAFFOLD
    if (WebSearch.needsSearch(prompt)) return WorkspaceTool.WEB
    return WorkspaceTool.CHAT
}

data class ToolVerdict(val ok: Boolean, val detail: String)

/** Verify scaffold output actually landed on disk, non-empty. */
suspend fun validateScaffoldFiles(project: Project, expected: List<String>): ToolVerdict =
    withContext(Dispatchers.IO) {
        if (expected.isEmpty()) return@withContext ToolVerdict(false, "no files produced")
        val missing = expected.filter { rel ->
            val f = safeScaffoldPath(project, rel) ?: return@filter true
            !f.isFile || f.length() == 0L
        }
        if (missing.isEmpty()) ToolVerdict(true, "${expected.size}/${expected.size} files verified on disk")
        else ToolVerdict(false, "missing or empty: ${missing.take(5).joinToString(", ")}")
    }

/** Verify a captured shell transcript ended cleanly (exit 0). */
fun validateShellOutput(out: String): ToolVerdict {
    val code = Regex("\\(exit (\\d+)\\)").findAll(out).lastOrNull()
        ?.groupValues?.getOrNull(1)?.toIntOrNull()
    return when {
        code == null -> ToolVerdict(false, "no exit status captured")
        code == 0 -> ToolVerdict(true, "exit 0")
        else -> ToolVerdict(false, "exit $code")
    }
}

/** Verify a web lookup returned usable context. */
fun validateWebResult(text: String?): ToolVerdict =
    if (text.isNullOrBlank()) ToolVerdict(false, "empty web result")
    else ToolVerdict(true, "${text.length} chars of fresh context")
