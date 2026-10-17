package com.karen.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real-time cloud chat calls for third-party providers whose API keys are
 * stored on-device (Model Manager → Connect Cloud API).
 *
 * No new dependencies: HttpURLConnection + org.json (bundled with Android).
 * All calls run on Dispatchers.IO via [CloudProvider.complete].
 * History entries are ("user" | "assistant", text) pairs, oldest first.
 */

enum class CloudProtocol { OPENAI, ANTHROPIC, GEMINI }

/** Third-party cloud providers connectable from the Model Manager. */
data class CloudProvider(
    val id: String,
    val name: String,
    val keyUrl: String,
    val note: String,
    val apiBase: String,
    val defaultModel: String,
    val protocol: CloudProtocol,
    /** True when the model reasons: effort controls thinking depth, and the
        effort pill is shown. Non-reasoning models hide the pill. */
    val reasoning: Boolean = false
)

val cloudProviders = listOf(
    CloudProvider("openai", "OpenAI GPT-4o", "platform.openai.com/api-keys", "GPT-4o + o-series via API", "https://api.openai.com/v1", "gpt-4o", CloudProtocol.OPENAI),
    CloudProvider("anthropic", "Anthropic Claude", "console.anthropic.com", "Claude Sonnet + Opus via API", "https://api.anthropic.com/v1", "claude-sonnet-4-20250514", CloudProtocol.ANTHROPIC, reasoning = true),
    CloudProvider("gemini", "Google Gemini", "aistudio.google.com/apikey", "Gemini Flash + Pro via API", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", CloudProtocol.GEMINI),
    CloudProvider("mistral", "Mistral Large", "console.mistral.ai", "Mistral Large + Codestral", "https://api.mistral.ai/v1", "mistral-large-latest", CloudProtocol.OPENAI),
    CloudProvider("grok", "xAI Grok", "console.x.ai", "Grok 3 + mini via API", "https://api.x.ai/v1", "grok-3", CloudProtocol.OPENAI),
    CloudProvider("openrouter", "OpenRouter", "openrouter.ai/keys", "One key → 200+ models gateway", "https://openrouter.ai/api/v1", "openrouter/auto", CloudProtocol.OPENAI, reasoning = true),
    CloudProvider("deepseek", "DeepSeek", "platform.deepseek.com", "DeepSeek V3 + R1 via API", "https://api.deepseek.com", "deepseek-chat", CloudProtocol.OPENAI),
    CloudProvider("groq", "Groq", "console.groq.com", "Ultra-low-latency LPU inference", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", CloudProtocol.OPENAI),
    CloudProvider("cerebras", "Cerebras", "cloud.cerebras.ai", "Free tier · ultra-fast inference", "https://api.cerebras.ai/v1", "llama-3.3-70b", CloudProtocol.OPENAI),
    CloudProvider("sambanova", "SambaNova", "cloud.sambanova.ai", "Free tier · fast open models", "https://api.sambanova.ai/v1", "Meta-Llama-3.3-70B-Instruct", CloudProtocol.OPENAI),
    CloudProvider("huggingface", "Hugging Face", "huggingface.co/settings/tokens", "Free credits · 1000s of models", "https://router.huggingface.co/v1", "meta-llama/Llama-3.1-8B-Instruct", CloudProtocol.OPENAI),
    CloudProvider("githubmodels", "GitHub Models", "github.com/settings/tokens", "Free with GitHub account · GPT/Llama/Mistral", "https://models.github.ai/inference", "openai/gpt-4o-mini", CloudProtocol.OPENAI),
    CloudProvider("nvidia", "NVIDIA NIM", "build.nvidia.com", "Free tier · 100+ open models", "https://integrate.api.nvidia.com/v1", "meta/llama-3.1-8b-instruct", CloudProtocol.OPENAI),
    CloudProvider("zai", "Zhipu GLM", "z.ai", "Free Flash tier · GLM models", "https://api.z.ai/api/paas/v4", "glm-4-flash", CloudProtocol.OPENAI)
)

/** Supported model ids per provider for the key dialog (default first). */
val providerModels: Map<String, List<String>> = mapOf(
    "openai" to listOf("gpt-4o", "gpt-4o-mini"),
    "anthropic" to listOf("claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022"),
    "gemini" to listOf("gemini-2.0-flash", "gemini-1.5-flash"),
    "mistral" to listOf("mistral-large-latest", "mistral-small-latest"),
    "grok" to listOf("grok-3", "grok-3-mini"),
    "openrouter" to listOf("openrouter/auto"),
    "deepseek" to listOf("deepseek-chat", "deepseek-reasoner"),
    "groq" to listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant"),
    "cerebras" to listOf("llama-3.3-70b", "qwen-3-235b-a22b-instruct-2507"),
    "sambanova" to listOf("Meta-Llama-3.3-70B-Instruct", "Meta-Llama-3.1-8B-Instruct"),
    "huggingface" to listOf("meta-llama/Llama-3.1-8B-Instruct", "Qwen/Qwen2.5-7B-Instruct"),
    "githubmodels" to listOf("openai/gpt-4o-mini", "meta/Llama-3.3-70B-Instruct"),
    "nvidia" to listOf("meta/llama-3.1-8b-instruct", "mistralai/mixtral-8x7b-instruct-v0.1"),
    "zai" to listOf("glm-4-flash", "glm-4-plus")
)

/** Dropdown marker for free-typed model ids (OpenRouter takes any model id). */
const val CUSTOM_MODEL = "Custom…"

/** Session cache of live model lists, so sheets reuse fetched OG values. */
val liveModelCache = mutableMapOf<String, List<LiveModel>>()

/** OG context for a model id: live cache first, then published specs. */
fun liveContextOf(providerId: String, modelId: String): Int {
    liveModelCache[providerId]?.find { it.id == modelId.trim() }?.let {
        if (it.context > 0) return it.context
    }
    return modelContextWindow(modelId)
}

/** Published context windows (tokens) for known cloud model ids. */
val modelContextWindows: Map<String, Int> = mapOf(
    "gpt-4o" to 128000,
    "gpt-4o-mini" to 128000,
    "claude-sonnet-4-20250514" to 200000,
    "claude-3-5-sonnet-20241022" to 200000,
    "claude-3-5-haiku-20241022" to 200000,
    "gemini-2.0-flash" to 1000000,
    "gemini-1.5-flash" to 1000000,
    "mistral-large-latest" to 128000,
    "mistral-small-latest" to 32000,
    "grok-3" to 131072,
    "grok-3-mini" to 131072,
    "deepseek-chat" to 64000,
    "deepseek-reasoner" to 64000,
    "llama-3.3-70b-versatile" to 128000,
    "llama-3.1-8b-instant" to 128000,
    "llama-3.3-70b" to 128000,
    "Meta-Llama-3.3-70B-Instruct" to 128000,
    "meta-llama/Llama-3.1-8B-Instruct" to 128000,
    "openai/gpt-4o-mini" to 128000,
    "meta/llama-3.1-8b-instruct" to 128000,
    "glm-4-flash" to 128000
)

/** Context window for a cloud model id, or 0 when unknown. */
fun modelContextWindow(modelId: String): Int = modelContextWindows[modelId.trim()] ?: 0

/** Compact context tag: 128K, 1M. Empty when unknown. */
fun formatContextWindow(tokens: Int): String = when {
    tokens <= 0 -> ""
    tokens >= 1000000 -> "${tokens / 1000000}M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> "$tokens"
}

/** Dropdown choices for a provider: known models, default first, then Custom. */
fun providerModelChoices(provider: CloudProvider, current: String): List<String> {
    val known = (providerModels[provider.id] ?: listOf(provider.defaultModel)).toMutableList()
    if (provider.defaultModel !in known) known.add(0, provider.defaultModel)
    val cur = current.trim()
    if (cur.isNotBlank() && cur != CUSTOM_MODEL && cur !in known) known.add(cur)
    known.add(CUSTOM_MODEL)
    return known
}

/**
 * Real-time model list for a provider, straight from its API. OpenRouter
 * needs no key; the rest authenticate with the given key. Returns ids with
 * the provider default first, or throws on network/API failure (callers fall
 * back to [providerModelChoices]).
 */
suspend fun CloudProvider.listModelsLive(apiKey: String): List<LiveModel> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ids: List<LiveModel> = when {
            id == "openrouter" -> {
                val json = getJson(
                    java.net.URL("$apiBase/models"),
                    mapOf("HTTP-Referer" to "https://karen.local", "X-Title" to "Karen")
                )
                json.optJSONArray("data")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        val modelId = o.optString("id", "").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                        val pricing = o.optJSONObject("pricing")
                        val free = ((pricing?.optString("prompt", "1")?.toDoubleOrNull() ?: 1.0) == 0.0) &&
                            ((pricing?.optString("completion", "1")?.toDoubleOrNull() ?: 1.0) == 0.0)
                        val ctxLen = o.optLong("context_length", 0L).toInt()
                        LiveModel(modelId, free, if (ctxLen > 0) ctxLen else modelContextWindow(modelId))
                    }
                } ?: throw CloudApiException(200, "bad model list")
            }
            id == "anthropic" -> throw CloudApiException(404, "Anthropic publishes no list endpoint")
            protocol == CloudProtocol.GEMINI -> {
                val json = getJson(
                    java.net.URL("$apiBase/models?pageSize=100&key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}")
                )
                json.optJSONArray("models")?.let { arr ->
                    (0 until arr.length()).mapNotNull { idx ->
                        val o = arr.optJSONObject(idx) ?: return@mapNotNull null
                        val methods = o.optJSONArray("supportedGenerationMethods")
                        val gen = (0 until (methods?.length() ?: 0)).any { i -> methods?.optString(i) == "generateContent" }
                        if (!gen) return@mapNotNull null
                        val name = o.optString("name", "").removePrefix("models/").takeIf { s -> s.isNotBlank() }
                            ?: return@mapNotNull null
                        LiveModel(name, false, 0)
                    }
                } ?: throw CloudApiException(200, "bad model list")
            }
            else -> {
                val json = getJson(
                    java.net.URL("$apiBase/models"),
                    mapOf("Authorization" to "Bearer $apiKey")
                )
                json.optJSONArray("data")?.let { arr ->
                    (0 until arr.length()).mapNotNull {
                        arr.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() }?.let { mid -> LiveModel(mid, false, modelContextWindow(mid)) }
                    }
                } ?: throw CloudApiException(200, "bad model list")
            }
        }
        val out = ids.toMutableList()
        val def = out.find { it.id == defaultModel }
        if (def != null) {
            out.remove(def)
            out.add(0, def)
        } else {
            out.add(0, LiveModel(defaultModel, false, modelContextWindow(defaultModel)))
        }
        out.distinctBy { it.id }
    }

/** One live model: id, real pricing tier, context tokens (0 = unpublished). */
data class LiveModel(val id: String, val free: Boolean, val context: Int = 0)

private fun getJson(url: java.net.URL, headers: Map<String, String> = emptyMap()): org.json.JSONObject {
    val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10000
        readTimeout = 15000
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream.bufferedReader().use { it.readText() }
    conn.disconnect()
    if (code !in 200..299) throw CloudApiException(code, text.take(200))
    return org.json.JSONObject(text)
}

/** Thinking-token budget per effort level for reasoning models. */
fun thinkingBudgetFor(effort: String): Int = when (effort) {
    "Low" -> 1000
    "High" -> 4000
    "Max" -> 8000
    "Extreme" -> 16000
    else -> 2000
}

fun findCloudProviderByName(name: String, extra: List<CloudProvider> = emptyList()): CloudProvider? =
    (cloudProviders + extra).find { it.name == name }

/** True for cloud entries that must never appear in installed-weights lists. */
fun isCloudProviderName(name: String, extra: List<CloudProvider> = emptyList()): Boolean =
    (cloudProviders + extra).any { it.name == name }

/** Built-ins plus user-added custom API providers. */
fun allCloudProviders(ctx: Context): List<CloudProvider> =
    cloudProviders + UserPrefs.customProviders(ctx)

/** Carries the HTTP status so the chat can show actionable key/quota errors. */
class CloudApiException(val status: Int, message: String) : Exception(message)

/** Result of a cloud call: text plus whether the model was cut off by its output cap. */
data class CloudResult(val text: String, val truncated: Boolean)

/** One page image for vision-capable requests (JPEG base64). */
data class VisionImage(val base64: String, val mime: String = "image/jpeg")

/** Effective model id: per-key override from Model Manager, else the default. */
fun CloudProvider.modelOrDefault(override: String?): String =
    if (override.isNullOrBlank()) defaultModel else override.trim()

/** True when the provider rejected the stored model id as non-existent. */
fun CloudApiException.isUnknownModel(): Boolean {
    if (status != 404) return false
    val m = message.orEmpty().lowercase()
    return "no endpoints found" in m || "model not found" in m ||
        ("does not exist" in m && "model" in m)
}

/**
 * Runs [call] with [override] as the model id. When the provider rejects that
 * id as non-existent (OpenRouter: "No endpoints found"), the stale override is
 * cleared from Model Manager and the call is retried once on the default
 * model, so one bad id can never brick a whole provider.
 */
suspend fun <T> CloudProvider.withModelFallback(
    ctx: Context,
    override: String?,
    call: suspend (model: String?) -> T
): T {
    val stored = override?.takeIf { it.isNotBlank() }
    return try {
        call(stored)
    } catch (e: CloudApiException) {
        if (stored == null || !e.isUnknownModel()) throw e
        UserPrefs.setApiModel(ctx, id, "")
        android.widget.Toast.makeText(
            ctx,
            "$name has no model \"$stored\" — switched back to $defaultModel",
            android.widget.Toast.LENGTH_LONG
        ).show()
        call(null)
    }
}

suspend fun CloudProvider.complete(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    /** Thinking-token budget for reasoning models; null disables thinking. */
    thinkingBudget: Int? = null,
    /** How many trailing turns fit the configured content window. */
    historyLimit: Int = 20,
    /** Model id override (per stored key); null/blank uses [defaultModel]. */
    model: String? = null,
    /** Page/document images attached to the last user message. */
    images: List<VisionImage> = emptyList(),
    /** Socket read timeout per request. */
    timeoutMs: Int = 90000
): String = completeResult(apiKey, history, maxTokens, thinkingBudget, historyLimit, model, images, timeoutMs).text

suspend fun CloudProvider.completeResult(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    thinkingBudget: Int? = null,
    historyLimit: Int = 20,
    model: String? = null,
    images: List<VisionImage> = emptyList(),
    timeoutMs: Int = 90000
): CloudResult = withContext(Dispatchers.IO) {
    val trimmed = history.takeLast(historyLimit.coerceIn(4, 80))
    val effectiveModel = modelOrDefault(model)
    when (protocol) {
        CloudProtocol.OPENAI -> {
            fun body(tokenField: String) = JSONObject()
                .put("model", effectiveModel)
                .put(tokenField, maxTokens)
                .put("messages", openAiMessages(trimmed, images))
                .apply {
                    // Effort becomes thinking depth on reasoning-capable routes.
                    if (id == "openrouter" && thinkingBudget != null) {
                        put("reasoning", JSONObject().put("max_tokens", thinkingBudget))
                    }
                }
            val headers = mutableMapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json"
            )
            if (id == "openrouter") {
                headers["HTTP-Referer"] = "https://karen.local"
                headers["X-Title"] = "Karen"
            }
            try {
                val first = post(URL("$apiBase/chat/completions"), headers, body("max_tokens"), timeoutMs)
                CloudResult(extractOpenAiText(first), isLengthCutoff(first))
            } catch (e: CloudApiException) {
                // Newer models (o-series, GPT-5 class) reject max_tokens — retry once.
                if (e.status == 400 && e.message.orEmpty().contains("max_completion_tokens")) {
                    val retry = post(URL("$apiBase/chat/completions"), headers, body("max_completion_tokens"), timeoutMs)
                    CloudResult(extractOpenAiText(retry), isLengthCutoff(retry))
                } else throw e
            }
        }
        CloudProtocol.ANTHROPIC -> {
            // Thinking budget must stay below max_tokens: headroom for the answer.
            val total = if (thinkingBudget != null) maxOf(maxTokens, thinkingBudget + 1024) else maxTokens
            val body = JSONObject()
                .put("model", effectiveModel)
                .put("max_tokens", total)
                .put("messages", anthropicMessages(trimmed, images))
                .apply {
                    if (thinkingBudget != null) {
                        put(
                            "thinking",
                            JSONObject()
                                .put("type", "enabled")
                                .put("budget_tokens", thinkingBudget)
                        )
                    }
                }
            val json = post(
                URL("$apiBase/messages"),
                mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                    "Content-Type" to "application/json"
                ),
                body,
                timeoutMs
            )
            val blocks = json.getJSONArray("content")
            val text = (0 until blocks.length())
                .map { blocks.getJSONObject(it) }
                .firstOrNull { it.optString("type") == "text" }
                ?.optString("text", "")
            val truncated = json.optString("stop_reason", "") == "max_tokens"
            if (!text.isNullOrBlank()) CloudResult(text, truncated)
            else {
                // Thinking-only reply: surface the thinking rather than nothing.
                val thinking = (0 until blocks.length())
                    .map { blocks.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "thinking" }
                    ?.optString("thinking", "")
                if (!thinking.isNullOrBlank()) CloudResult(thinking, truncated)
                else throw CloudApiException(200, "model returned no text")
            }
        }
        CloudProtocol.GEMINI -> {
            val body = JSONObject().put("contents", geminiContents(trimmed, images))
            val json = post(
                URL("$apiBase/models/$effectiveModel:generateContent"),
                mapOf(
                    "x-goog-api-key" to apiKey,
                    "Content-Type" to "application/json"
                ),
                body,
                timeoutMs
            )
            val text = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text", "")
            if (text.isNullOrBlank()) throw CloudApiException(200, "model returned no text")
            val truncated = json.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optString("finishReason", "") == "MAX_TOKENS"
            CloudResult(text, truncated)
        }
    }
}

/**
 * Streaming chat completion for the chat view and realtime voice: answer
 * deltas are delivered to [onToken] as they arrive (SSE) and the full text
 * returns with the finish reason so chat can continue cut-off replies.
 * Thinking deltas are never shown live — they only back up the body when a
 * model emits no visible text (mirrors [extractOpenAiText]).
 */
suspend fun CloudProvider.completeStreamingResult(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    thinkingBudget: Int? = null,
    historyLimit: Int = 20,
    model: String? = null,
    onToken: (String) -> Unit = {},
    isCancelled: () -> Boolean = { false }
): CloudResult = withContext(Dispatchers.IO) {
    val trimmed = history.takeLast(historyLimit.coerceIn(4, 80))
    val effectiveModel = modelOrDefault(model)
    val full = StringBuilder()
    val thoughts = StringBuilder()
    var truncated = false
    fun emit(piece: String) {
        if (piece.isNotEmpty()) {
            full.append(piece)
            onToken(piece)
        }
    }
    when (protocol) {
        CloudProtocol.OPENAI -> {
            fun body(tokenField: String) = JSONObject()
                .put("model", effectiveModel)
                .put(tokenField, maxTokens)
                .put("stream", true)
                .put(
                    "messages",
                    JSONArray(trimmed.map { (role, text) ->
                        JSONObject().put("role", role).put("content", text)
                    })
                )
                .apply {
                    if (id == "openrouter" && thinkingBudget != null) {
                        put("reasoning", JSONObject().put("max_tokens", thinkingBudget))
                    }
                }
            val headers = mutableMapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream"
            )
            if (id == "openrouter") {
                headers["HTTP-Referer"] = "https://karen.local"
                headers["X-Title"] = "Karen"
            }
            fun runOnce(tokenField: String) {
                postStreamLines(URL("$apiBase/chat/completions"), headers, body(tokenField), isCancelled) { line ->
                    if (!line.startsWith("data:")) return@postStreamLines
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isBlank() || payload == "[DONE]") return@postStreamLines
                    try {
                        val choice = JSONObject(payload)
                            .optJSONArray("choices")
                            ?.optJSONObject(0) ?: return@postStreamLines
                        val reason = choice.optString("finish_reason", "")
                        if (reason == "length" || reason == "max_tokens") truncated = true
                        val delta = choice.optJSONObject("delta") ?: return@postStreamLines
                        val content = delta.opt("content")
                        if (content is String) emit(content)
                        // Thinking models (DeepSeek/OpenRouter): backup body only.
                        if (content !is String || content.isEmpty()) {
                            val think = delta.opt("reasoning_content") ?: delta.opt("reasoning")
                            if (think is String) thoughts.append(think)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                runOnce("max_tokens")
            } catch (e: CloudApiException) {
                if (e.status == 400 && e.message.orEmpty().contains("max_completion_tokens")) {
                    runOnce("max_completion_tokens")
                } else throw e
            }
        }
        CloudProtocol.ANTHROPIC -> {
            val total = if (thinkingBudget != null) maxOf(maxTokens, thinkingBudget + 1024) else maxTokens
            val body = JSONObject()
                .put("model", effectiveModel)
                .put("max_tokens", total)
                .put("stream", true)
                .put(
                    "messages",
                    JSONArray(trimmed.map { (role, text) ->
                        JSONObject().put("role", role).put("content", text)
                    })
                )
            postStreamLines(
                URL("$apiBase/messages"),
                mapOf(
                    "x-api-key" to apiKey,
                    "anthropic-version" to "2023-06-01",
                    "Content-Type" to "application/json",
                    "Accept" to "text/event-stream"
                ),
                body,
                isCancelled
            ) { line ->
                if (!line.startsWith("data:")) return@postStreamLines
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank()) return@postStreamLines
                try {
                    val data = JSONObject(payload)
                    when (data.optString("type")) {
                        "content_block_delta" -> {
                            val delta = data.optJSONObject("delta")
                            if (delta?.optString("type") == "thinking_delta") {
                                thoughts.append(delta.optString("thinking", ""))
                            } else {
                                emit(delta?.optString("text", "").orEmpty())
                            }
                        }
                        "message_delta" -> {
                            if (data.optJSONObject("delta")?.optString("stop_reason", "") == "max_tokens") truncated = true
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        CloudProtocol.GEMINI -> {
            val body = JSONObject().put(
                "contents",
                JSONArray(trimmed.map { (role, text) ->
                    JSONObject()
                        .put("role", if (role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                })
            )
            var lastChunk = ""
            postStreamLines(
                URL("$apiBase/models/$effectiveModel:streamGenerateContent?alt=sse&key=${java.net.URLEncoder.encode(apiKey, "UTF-8")}"),
                mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream"),
                body,
                isCancelled
            ) { line ->
                if (!line.startsWith("data:")) return@postStreamLines
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank()) return@postStreamLines
                try {
                    val cand = JSONObject(payload)
                        .optJSONArray("candidates")
                        ?.optJSONObject(0) ?: return@postStreamLines
                    if (cand.optString("finishReason", "") == "MAX_TOKENS") truncated = true
                    val text = cand
                        .optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "").orEmpty()
                    // Chunks repeat cumulative text: only speak what is new.
                    if (text.isNotEmpty()) {
                        if (text.startsWith(lastChunk)) emit(text.removePrefix(lastChunk))
                        else emit(text)
                        lastChunk = text
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
    // Thinking-only replies: surface the thinking rather than nothing.
    if (full.isEmpty() && thoughts.isNotEmpty()) full.append(thoughts)
    CloudResult(full.toString(), truncated)
}

/** Convenience wrapper for callers that only need the text (realtime voice). */
suspend fun CloudProvider.completeStreaming(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    thinkingBudget: Int? = null,
    historyLimit: Int = 20,
    model: String? = null,
    onToken: (String) -> Unit = {},
    isCancelled: () -> Boolean = { false }
): String = completeStreamingResult(
    apiKey, history, maxTokens, thinkingBudget, historyLimit, model, onToken, isCancelled
).text

/** POSTs a JSON body and streams SSE `data:` lines until disconnect/cancel. */
private fun postStreamLines(
    url: URL,
    headers: Map<String, String>,
    body: JSONObject,
    isCancelled: () -> Boolean,
    onLine: (String) -> Unit
) {
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 90000
        doOutput = true
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    if (code !in 200..299) {
        val err = try {
            conn.errorStream?.bufferedReader()?.use { it.readText() }
        } catch (_: Exception) {
            null
        } ?: ""
        conn.disconnect()
        throw CloudApiException(code, err.take(300))
    }
    try {
        conn.inputStream.bufferedReader().use { reader ->
            while (!isCancelled()) {
                val line = try {
                    reader.readLine()
                } catch (_: Exception) {
                    break
                } ?: break
                onLine(line)
            }
        }
    } finally {
        conn.disconnect()
    }
}

/**
 * True when an OpenAI-style reply stopped because it hit the output cap
 * (`finish_reason == "length"`) rather than ending naturally.
 */
private fun isLengthCutoff(json: JSONObject): Boolean = try {
    json.getJSONArray("choices")
        .optJSONObject(0)
        ?.optString("finish_reason", "") == "length"
} catch (_: Exception) {
    false
}

/**
 * Reads a chat-completion reply without ever surfacing null: normal content
 * first, then reasoning fallbacks (DeepSeek `reasoning_content`, OpenRouter
 * `reasoning` / `reasoning_details`) used by thinking models.
 */
private fun extractOpenAiText(json: JSONObject): String {
    val message = json.getJSONArray("choices")
        .getJSONObject(0)
        .getJSONObject("message")
    val content = message.optString("content", "")
    if (content.isNotBlank()) return content
    val reasoning = message.optString("reasoning", "")
        .ifBlank { message.optString("reasoning_content", "") }
    if (reasoning.isNotBlank()) return reasoning
    val details = message.optJSONArray("reasoning_details")
    if (details != null) {
        val sb = StringBuilder()
        for (i in 0 until details.length()) {
            val t = details.optJSONObject(i)?.optString("text", "")
            if (!t.isNullOrBlank()) sb.append(t)
        }
        if (sb.isNotEmpty()) return sb.toString()
    }
    throw CloudApiException(200, "model returned no text — try a non-reasoning model")
}

/** OpenAI-style messages; page images attach to the last user message. */
private fun openAiMessages(trimmed: List<Pair<String, String>>, images: List<VisionImage>): JSONArray =
    JSONArray(trimmed.mapIndexed { idx, (role, text) ->
        if (idx == trimmed.lastIndex && role == "user" && images.isNotEmpty()) {
            val parts = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            images.forEach { img ->
                parts.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:${img.mime};base64,${img.base64}"))
                )
            }
            JSONObject().put("role", role).put("content", parts)
        } else {
            JSONObject().put("role", role).put("content", text)
        }
    })

/** Anthropic messages; page images attach to the last user message. */
private fun anthropicMessages(trimmed: List<Pair<String, String>>, images: List<VisionImage>): JSONArray =
    JSONArray(trimmed.mapIndexed { idx, (role, text) ->
        if (idx == trimmed.lastIndex && role == "user" && images.isNotEmpty()) {
            val blocks = JSONArray().put(JSONObject().put("type", "text").put("text", text))
            images.forEach { img ->
                blocks.put(
                    JSONObject().put("type", "image")
                        .put(
                            "source",
                            JSONObject().put("type", "base64")
                                .put("media_type", img.mime)
                                .put("data", img.base64)
                        )
                )
            }
            JSONObject().put("role", role).put("content", blocks)
        } else {
            JSONObject().put("role", role).put("content", text)
        }
    })

/** Gemini contents; page images attach to the last user message. */
private fun geminiContents(trimmed: List<Pair<String, String>>, images: List<VisionImage>): JSONArray =
    JSONArray(trimmed.mapIndexed { idx, (role, text) ->
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (idx == trimmed.lastIndex && role == "user" && images.isNotEmpty()) {
            images.forEach { img ->
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", img.mime).put("data", img.base64)
                    )
                )
            }
        }
        JSONObject()
            .put("role", if (role == "assistant") "model" else "user")
            .put("parts", parts)
    })

private fun post(url: URL, headers: Map<String, String>, body: JSONObject, timeoutMs: Int = 90000): JSONObject {
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = timeoutMs
        doOutput = true
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
    val code = conn.responseCode
    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
    val text = stream.bufferedReader().use { it.readText() }
    conn.disconnect()
    if (code !in 200..299) throw CloudApiException(code, text.take(300))
    return JSONObject(text)
}
