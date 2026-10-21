package com.karen.ui

import android.content.Context

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

/** Bundle + remote catalog entry for a display name (if known). */
fun catalogEntryFor(name: String): CatalogModel? {
    val catalog = ModelDownloader.catalogModels() + legacyCatalog
    catalog.find { it.name == name }?.let { return it }
    // Hugging Face pulls register under the file's base name — match that too.
    return catalog.find { it.fileName.substringBeforeLast('.').equals(name, ignoreCase = true) }
}

/** Params in billions: catalog value, else read from the name (…-27B-…). */
fun paramsOf(name: String): Float {
    catalogEntryFor(name)?.paramsB?.takeIf { it > 0f }?.let { return it }
    val m = Regex("(?i)(\\d+(?:\\.\\d+)?)b").find(name)
    return m?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
}

/** Higher wins. Quick chats prefer the smallest (fast, cool) model. */
fun scoreLocal(name: String, intent: TaskIntent): Double {
    val e = catalogEntryFor(name)
    val size = paramsOf(name).toDouble()
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
        "cerebras" -> 68
        "githubmodels" -> 66
        "groq" -> 55
        "sambanova" -> 58
        "nvidia" -> 58
        "huggingface" -> 56
        "zai" -> 56
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
        "cerebras" -> 66
        "githubmodels" -> 64
        "sambanova" -> 62
        "nvidia" -> 62
        "huggingface" -> 60
        "zai" -> 58
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
        "huggingface" -> 68
        "githubmodels" -> 64
        "cerebras" -> 62
        "nvidia" -> 60
        "sambanova" -> 58
        "zai" -> 56
        else -> 50
    }
    TaskIntent.SIMPLE -> when (providerId) {
        "groq" -> 90
        "cerebras" -> 88
        "gemini" -> 85
        "sambanova" -> 80
        "githubmodels" -> 78
        "openai" -> 75
        "nvidia" -> 75
        "huggingface" -> 72
        "zai" -> 72
        "deepseek" -> 70
        "anthropic" -> 70
        "openrouter" -> 70
        "mistral" -> 65
        "grok" -> 65
        else -> 50
    }
}

/**
 * Higher wins for Live mode. Voice turns are short, so first-token latency
 * beats flagship answer quality: a direct small/fast route should answer
 * before a thinker has finished planning. Users can still pin a specific
 * model in the Live mode pill or Model Manager.
 */
fun scoreCloudVoice(providerId: String): Int = when (providerId) {
    "groq" -> 100
    "cerebras" -> 98
    "sambanova" -> 92
    "gemini" -> 90
    "nvidia" -> 84
    "githubmodels" -> 82
    "openai" -> 76
    "huggingface" -> 72
    "zai" -> 70
    "mistral" -> 64
    "deepseek" -> 62
    "openrouter" -> 58
    "grok" -> 56
    "anthropic" -> 52
    else -> 48
}

/** True for thinking models: reasoning cloud providers or reasoning catalog weights. */
fun isReasoningModel(name: String): Boolean {
    if (findCloudProviderByName(name)?.reasoning == true) return true
    return catalogEntryFor(name)?.reasoning == true
}

/** System-prompt nudge so effort levels steer local thinkers, not just caps. */
fun reasoningEffortHint(effort: String): String = when (effort) {
    "Low" -> " Reason briefly: one short pass, then answer."
    "High" -> " Think carefully step by step before answering."
    "Max" -> " Think hard and double-check your reasoning before answering."
    "Extreme" -> " Think as long as needed; explore alternatives and verify before answering."
    else -> ""
}

/** True when a stored pick is still usable: installed weight or keyed cloud. */
fun isSelectableModel(ctx: Context, name: String): Boolean {
    if (name.isBlank()) return false
    val customs = UserPrefs.customProviders(ctx)
    if (isCloudProviderName(name, customs)) {
        return findCloudProviderByName(name, customs)?.let { UserPrefs.apiKey(ctx, it.id).isNotBlank() } ?: false
    }
    // Catalog-only names are downloadable models, not installed weights. Treating
    // them as selectable made Live Mode pick a model with no file on disk and
    // fall back to "connect a model" even when a cloud key was configured.
    return name in UserPrefs.models(ctx) && !isNonChatModel(name)
}

/** True for embedding-only weights: downloaded, but never chat-routed. */
fun isEmbeddingModel(name: String): Boolean = catalogEntryFor(name)?.embedding == true

/** True for mmproj companions: downloaded next to a vision model, never chat-routed. */
fun isProjectorModel(name: String): Boolean = catalogEntryFor(name)?.projector == true

/** True when a file name belongs to a catalog projector companion. */
fun isProjectorFile(name: String): Boolean {
    val base = name.substringBeforeLast('.')
    return ModelDownloader.catalogModels().any {
        it.projector &&
            (it.fileName.equals(name, true) || it.fileName.substringBeforeLast('.').equals(base, true))
    }
}

/** True when a catalog weight cannot serve a chat turn on its own. */
fun isNonChatModel(name: String): Boolean {
    val entry = catalogEntryFor(name) ?: return false
    return entry.embedding || entry.projector
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
    allowCloud: Boolean,
    /** Display name favored for local picks (blank = none). */
    preferredLocal: String = "",
    /** Display name favored for cloud picks (blank = none). */
    preferredApi: String = ""
): RouteDecision? {
    // Embeddings and mmproj projectors are files, not chat weights: never route
    // a turn to one even if a caller forgets to filter them out.
    val locals = installedLocals.filter { !isNonChatModel(it) }
    if (locals.isEmpty() && (!allowCloud || keyedClouds.isEmpty())) return null
    val intent = detectIntent(prompt)
    val reason = intentReason(intent)
    val bestLocal = locals.maxByOrNull {
        scoreLocal(it, intent) + if (it == preferredLocal) 0.5 else 0.0
    }
    val bestLocalSize = bestLocal
        ?.let { name -> paramsOf(name) }
        ?: 0f
    if (!allowCloud || keyedClouds.isEmpty()) {
        return bestLocal?.let { RouteDecision(it, reason) }
    }
    val bestCloud = keyedClouds.maxByOrNull {
        scoreCloud(it.id, intent) + if (it.name == preferredApi) 8 else 0
    }?.name
    val pick = when {
        bestLocal == null -> bestCloud ?: return null
        bestCloud == null -> bestLocal
        intent == TaskIntent.SIMPLE -> bestLocal
        bestLocalSize >= 3f -> bestLocal
        else -> bestCloud
    }
    return RouteDecision(pick, reason)
}
