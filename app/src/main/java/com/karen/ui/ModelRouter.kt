package com.karen.ui

/**
 * Task-aware model routing: picks the best available model per message
 * instead of sending everything to one fixed model.
 *
 * Candidates are the models actually on this device — installed local
 * weights plus cloud providers with a stored key (only when the user opts
 * cloud APIs into routing). Nothing is ever uploaded for the decision;
 * it is pure keyword intent detection over the prompt text.
 */
enum class TaskIntent { CODE, REASONING, LONG, SIMPLE }

data class RouteDecision(val modelName: String, val reason: String)

fun detectIntent(text: String): TaskIntent {
    val t = " ${text.lowercase()} "
    fun has(vararg words: String) = words.any { w ->
        Regex("\\b$w").containsMatchIn(t)
    }
    if (has("summar", "document", "pdf", "paper", "article", "thesis", "transcript", "entire file", "whole file", "long text")) return TaskIntent.LONG
    if (has("build", "code", "coding", "program", "script", "function", "bug", "fix", "error", "exception", "implement", "scaffold", "refactor", "debug", "compile", "gradle", "kotlin", "python", "java", "sql", "endpoint", "app")) return TaskIntent.CODE
    if (has("why", "prove", "proof", "math", "calculat", "solve", "think", "reason", "analy", "compare", "decide", "logic", "riddle", "equation", "physics", "strategy")) return TaskIntent.REASONING
    return TaskIntent.SIMPLE
}

fun intentReason(intent: TaskIntent): String = when (intent) {
    TaskIntent.CODE -> "code task"
    TaskIntent.REASONING -> "reasoning task"
    TaskIntent.LONG -> "long read"
    TaskIntent.SIMPLE -> "quick chat"
}

/** Higher wins. Quick chats prefer the smallest (fast, cool) model. */
fun scoreLocal(name: String, intent: TaskIntent): Double {
    val e = modelCatalog.find { it.name == name }
    val size = (e?.paramsB ?: 1f).toDouble()
    return when (intent) {
        TaskIntent.SIMPLE -> -size
        TaskIntent.LONG -> size
        TaskIntent.CODE -> size *
            (if (e?.code == true) 1.5 else 1.0) *
            (if (e?.reasoning == true) 1.3 else 1.0)
        TaskIntent.REASONING -> size *
            (if (e?.reasoning == true) 2.2 else 1.0)
    }
}

/** Higher wins. Per-provider quality tier for each intent. */
fun scoreCloud(providerId: String, intent: TaskIntent): Int = when (intent) {
    TaskIntent.REASONING -> when (providerId) {
        "anthropic" -> 100
        "openrouter" -> 95
        "deepseek" -> 90
        "openai" -> 75
        "gemini" -> 70
        "grok" -> 65
        "mistral" -> 60
        "groq" -> 55
        else -> 50
    }
    TaskIntent.CODE -> when (providerId) {
        "deepseek" -> 95
        "anthropic" -> 92
        "openrouter" -> 90
        "mistral" -> 82
        "openai" -> 80
        "grok" -> 70
        "gemini" -> 70
        "groq" -> 60
        else -> 50
    }
    TaskIntent.LONG -> when (providerId) {
        "gemini" -> 92
        "anthropic" -> 88
        "openrouter" -> 85
        "openai" -> 80
        "deepseek" -> 65
        "grok" -> 60
        "mistral" -> 60
        "groq" -> 55
        else -> 50
    }
    TaskIntent.SIMPLE -> when (providerId) {
        "groq" -> 90
        "gemini" -> 85
        "openai" -> 75
        "deepseek" -> 70
        "anthropic" -> 70
        "openrouter" -> 70
        "mistral" -> 65
        "grok" -> 65
        else -> 50
    }
}

/** True for thinking models: reasoning cloud providers or reasoning catalog weights. */
fun isReasoningModel(name: String): Boolean {
    if (findCloudProviderByName(name)?.reasoning == true) return true
    return modelCatalog.any { it.name == name && it.reasoning }
}

/** System-prompt nudge so effort levels steer local thinkers, not just caps. */
fun reasoningEffortHint(effort: String): String = when (effort) {
    "Low" -> " Reason briefly: one short pass, then answer."
    "High" -> " Think carefully step by step before answering."
    "Max" -> " Think hard and double-check your reasoning before answering."
    "Extreme" -> " Think as long as needed; explore alternatives and verify before answering."
    else -> ""
}

/**
 * Returns the best model for [prompt], or null when nothing usable is
 * installed/connected (caller falls back to manual selection).
 * Hard tasks go to the cloud only when on-device is tiny (< 3B) — quick
 * chats always stay on the fastest local weight.
 */
fun routeModel(
    prompt: String,
    installedLocals: List<String>,
    keyedClouds: List<CloudProvider>,
    allowCloud: Boolean
): RouteDecision? {
    if (installedLocals.isEmpty() && (!allowCloud || keyedClouds.isEmpty())) return null
    val intent = detectIntent(prompt)
    val reason = intentReason(intent)
    val bestLocal = installedLocals.maxByOrNull { scoreLocal(it, intent) }
    val bestLocalSize = bestLocal
        ?.let { name -> modelCatalog.find { it.name == name }?.paramsB ?: 1f }
        ?: 0f
    if (!allowCloud || keyedClouds.isEmpty()) {
        return bestLocal?.let { RouteDecision(it, reason) }
    }
    val bestCloud = keyedClouds.maxByOrNull { scoreCloud(it.id, intent) }?.name
    val pick = when {
        bestLocal == null -> bestCloud ?: return null
        bestCloud == null -> bestLocal
        intent == TaskIntent.SIMPLE -> bestLocal
        bestLocalSize >= 3f -> bestLocal
        else -> bestCloud
    }
    return RouteDecision(pick, reason)
}
