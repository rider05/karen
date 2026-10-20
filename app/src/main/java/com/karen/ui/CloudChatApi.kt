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
    CloudProvider("anthropic", "Anthropic Claude", "console.anthropic.com", "Claude Sonnet + Opus via API", "https://api.anthropic.com/v1", "claude-sonnet-5-5", CloudProtocol.ANTHROPIC, reasoning = true),
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
    "anthropic" to listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5", "claude-sonnet-4-6"),
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
    "claude-sonnet-5-5" to 1000000,
    "claude-opus-5-5" to 1000000,
    "claude-haiku-5-5" to 1000000,
    "claude-sonnet-4-6" to 1000000,
    "claude-opus-4-6" to 1000000,
    "claude-opus-4-5" to 1000000,
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
    // 1024 is the API minimum for budget_tokens; anything smaller is a 400.
    "Low" -> 1024
    "High" -> 4000
    "Max" -> 8000
    "Extreme" -> 16000
    "XHigh" -> 32000
    else -> 2000
}

/**
 * Karen's six effort pills → Anthropic `output_config.effort` levels.
 * Adaptive-thinking models (4.7+) reject `thinking.type: "enabled"`; depth is
 * steered by this string instead. Karen has one step more than the API, so
 * Extreme folds into max.
 */
fun anthropicEffortFor(effort: String): String = when (effort) {
    "Low" -> "low"
    "Medium" -> "medium"
    "High" -> "high"
    "Max" -> "max"
    "Extreme" -> "max"
    "XHigh" -> "xhigh"
    else -> "high"
}

fun findCloudProviderByName(name: String, extra: List<CloudProvider> = emptyList()): CloudProvider? =
    (cloudProviders + extra).find { it.name == name }

/** True for cloud entries that must never appear in installed-weights lists. */
fun isCloudProviderName(name: String, extra: List<CloudProvider> = emptyList()): Boolean =
    (cloudProviders + extra).any { it.name == name }

/**
 * True when a 400 means this model wants adaptive thinking rather than
 * `thinking.type: "enabled"` — the Claude 4.7+ contract.
 */
fun CloudApiException.isThinkingShapeRejected(): Boolean {
    if (status != 400) return false
    val m = message.orEmpty().lowercase()
    return "thinking" in m &&
        ("is not supported" in m || "not permitted" in m || "unexpected" in m || "extra inputs" in m)
}

/** True when a 400 names the effort level (not every model takes xhigh/max). */
fun CloudApiException.isEffortRejected(): Boolean {
    if (status != 400) return false
    val m = message.orEmpty().lowercase()
    return "effort" in m || "output_config" in m
}

/** Built-ins plus user-added custom API providers. */
fun allCloudProviders(ctx: Context): List<CloudProvider> =
    cloudProviders + UserPrefs.customProviders(ctx)

/** Carries the HTTP status so the chat can show actionable key/quota errors. */
class CloudApiException(val status: Int, message: String) : Exception(message)

/** Result of a cloud call: text plus whether the model was cut off by its output cap. */
/**
 * Provider-reported token counts for one request; all-zero means not reported.
 * [cacheRead] and [cacheWrite] are Anthropic prompt-cache buckets — a cache hit
 * bills at 0.1x input, so a cost readout needs them to be honest.
 */
data class CloudUsage(
    val input: Int = 0,
    val output: Int = 0,
    val reasoning: Int = 0,
    val cacheRead: Int = 0,
    val cacheWrite: Int = 0
) {
    val any: Boolean get() = input > 0 || output > 0 || reasoning > 0
}

data class CloudResult(val text: String, val truncated: Boolean, val usage: CloudUsage? = null)

/** OpenAI-style usage: prompt/completion counts plus the reasoning detail. */
private fun openAiUsage(json: JSONObject?): CloudUsage? {
    val u = json?.optJSONObject("usage") ?: return null
    val usage = CloudUsage(
        u.optInt("prompt_tokens", 0),
        u.optInt("completion_tokens", 0),
        u.optJSONObject("completion_tokens_details")?.optInt("reasoning_tokens", 0)
            ?: u.optInt("reasoning_tokens", 0)
    )
    return if (usage.any) usage else null
}

/** Anthropic usage: message counts plus thinking and prompt-cache buckets. */
private fun anthropicUsage(json: JSONObject?): CloudUsage? {
    val u = json?.optJSONObject("usage") ?: return null
    return anthropicUsageOf(u)
}

/** Shared by the one-shot reply and the SSE `message_delta` event. */
private fun anthropicUsageOf(u: JSONObject?): CloudUsage? {
    if (u == null) return null
    val usage = CloudUsage(
        input = u.optInt("input_tokens", 0),
        output = u.optInt("output_tokens", 0),
        // Thinking bills as output; this is the only place it surfaces.
        reasoning = u.optJSONObject("output_tokens_details")?.optInt("thinking_tokens", 0) ?: 0,
        cacheRead = u.optInt("cache_read_input_tokens", 0),
        cacheWrite = u.optInt("cache_creation_input_tokens", 0)
    )
    return if (usage.any) usage else null
}

/** Gemini usage: usageMetadata with the thought-token detail. */
private fun geminiUsage(json: JSONObject?): CloudUsage? {
    val u = json?.optJSONObject("usageMetadata") ?: return null
    val usage = CloudUsage(
        u.optInt("promptTokenCount", 0),
        u.optInt("candidatesTokenCount", 0),
        u.optInt("thoughtsTokenCount", 0)
    )
    return if (usage.any) usage else null
}

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
        ("does not exist" in m && "model" in m) ||
        // Anthropic: {"type":"error","error":{"type":"not_found_error","message":"model: …"}}
        ("not_found_error" in m && "model" in m)
}

/**
 * True when a turn was rejected for token/context room rather than for a bad
 * key or a network failure: context overflow, an output cap above the model's
 * limit, an oversized body, or a credit balance that only covers a few tokens.
 * Another model from the same provider can still take the turn.
 */
fun CloudApiException.isTokenLimit(): Boolean {
    if (status !in listOf(400, 402, 413, 422)) return false
    val m = message.orEmpty().lowercase()
    return "context length" in m || "context_length" in m ||
        "maximum context" in m || "max context" in m ||
        "too many tokens" in m || "token limit" in m ||
        "max_tokens" in m || "maximum number of tokens" in m ||
        "prompt is too long" in m || "input is too long" in m ||
        "reduce the length" in m || "string_above_max_length" in m ||
        "request too large" in m || "request entity too large" in m ||
        "fewer max_tokens" in m || "can only afford" in m ||
        "requires more credits" in m || "exceeds the maximum" in m
}

/** Models tried after the picked one rejects a turn for token room. */
private const val MAX_MODEL_FALLBACKS = 4

/** True when the rejection was about credit balance, not raw context size. */
private fun CloudApiException.isCreditsLimit(): Boolean {
    val m = message.orEmpty().lowercase()
    return "credits" in m || "can only afford" in m || "fewer max_tokens" in m
}

/**
 * Other models of this provider worth trying after [failing] was rejected.
 * Curated ids come first, then any live model list already fetched in Model
 * Manager. A credit error prefers free models; a context overflow prefers
 * models with a larger window than the one that failed.
 */
private suspend fun CloudProvider.fallbackModels(failing: String, credits: Boolean): List<String> {
    // OpenRouter's curated list is only "auto"; fetch its live catalog once so
    // a credit/context failure has real alternatives (free models first).
    var live = liveModelCache[id].orEmpty()
    if (live.isEmpty() && id == "openrouter") {
        live = try {
            listModelsLive("")
        } catch (_: Exception) {
            emptyList()
        }
        if (live.isNotEmpty()) liveModelCache[id] = live
    }
    val pool = (providerModelChoices(this, failing) + live.map { it.id })
        .distinct()
        .filter { it != CUSTOM_MODEL && !it.equals(failing, ignoreCase = true) }
    if (credits) {
        val free = live.filter { it.free }.map { it.id }
        return (free.filter { it in pool } + pool).distinct().take(MAX_MODEL_FALLBACKS)
    }
    val failingCtx = liveContextOf(id, failing)
    val larger = if (failingCtx > 0) {
        pool.filter { liveContextOf(id, it) > failingCtx }
    } else {
        emptyList()
    }
    val ordered = if (larger.isNotEmpty()) larger + pool else pool
    return ordered.distinct()
        .sortedByDescending { liveContextOf(id, it) }
        .take(MAX_MODEL_FALLBACKS)
}

/**
 * Runs [call] with [override] as the model id, then repairs predictable model
 * problems inside the same provider:
 *
 * - an id the provider does not know is cleared from Model Manager and the
 *   call retries on the provider default (one bad id never bricks a provider);
 * - a turn rejected for token/context room (with Model Fallback enabled in
 *   Settings) retries on up to [MAX_MODEL_FALLBACKS] other models from the
 *   same provider, largest known context window first.
 */
suspend fun <T> CloudProvider.withModelFallback(
    ctx: Context,
    override: String?,
    call: suspend (model: String?) -> T
): T {
    val stored = override?.takeIf { it.isNotBlank() }
    try {
        return call(stored)
    } catch (e: CloudApiException) {
        if (stored != null && e.isUnknownModel()) {
            UserPrefs.setApiModel(ctx, id, "")
            android.widget.Toast.makeText(
                ctx,
                "$name has no model \"$stored\" — switched back to $defaultModel",
                android.widget.Toast.LENGTH_LONG
            ).show()
            return call(null)
        }
        if (!UserPrefs.modelFallback(ctx) || !e.isTokenLimit()) throw e
        val failing = stored ?: defaultModel
        val credits = e.isCreditsLimit()
        val candidates = fallbackModels(failing, credits)
        for (next in candidates) {
            try {
                val result = call(next)
                android.widget.Toast.makeText(
                    ctx,
                    "$name: $failing hit its ${if (credits) "credit" else "token"} limit — retried on $next for this turn",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return result
            } catch (e2: CloudApiException) {
                // A missing or equally capped alternative is skipped; a real
                // failure (auth, network) is reported as-is.
                if (!e2.isTokenLimit() && !e2.isUnknownModel()) throw e2
            }
        }
        throw e
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
    timeoutMs: Int = 90000,
    /** Karen effort pill; becomes Anthropic `output_config.effort` on 4.7+. */
    effort: String? = null
): String = completeResult(apiKey, history, maxTokens, thinkingBudget, historyLimit, model, images, timeoutMs, effort).text

/**
 * Last output budget a provider's credits could cover ("can only afford N").
 * Cached briefly so continuation rounds don't re-trigger the same 402.
 */
private data class CreditBudget(val tokens: Int, val at: Long)
private val creditBudgets =
    java.util.concurrent.ConcurrentHashMap<String, CreditBudget>()
private const val CREDIT_BUDGET_TTL_MS = 10 * 60 * 1000L

/** Requested budget, clamped to a fresh credit ceiling for this provider. */
private fun CloudProvider.creditBudget(maxTokens: Int): Int {
    val cached = creditBudgets[id] ?: return maxTokens
    if (System.currentTimeMillis() - cached.at > CREDIT_BUDGET_TTL_MS) {
        creditBudgets.remove(id)
        return maxTokens
    }
    return minOf(maxTokens, cached.tokens)
}

/**
 * Reads "can only afford 222" out of a credit rejection, remembers it for
 * this provider, and returns the affordable budget. Null when the error has
 * no numeric ceiling or asking for less would change nothing.
 */
private fun CloudProvider.affordableBudget(requested: Int, e: CloudApiException): Int? {
    if (!e.isTokenLimit()) return null
    val n = Regex("(?i)afford\\s+(\\d+)").find(e.message.orEmpty())
        ?.groupValues?.get(1)?.toIntOrNull() ?: return null
    if (n <= 0) return null
    // Two tokens of headroom: the provider's price rounding can reject a
    // request that sits exactly on the ceiling it just quoted.
    val target = (n - 2).coerceAtLeast(16)
    if (target >= requested) return null
    creditBudgets[id] = CreditBudget(target, System.currentTimeMillis())
    return target
}

/**
 * Chat completion with a credit-aware budget. OpenRouter answers 402 with
 * "You requested up to 256 tokens, but can only afford 222"; instead of
 * failing the turn, the same model is retried with the affordable budget and
 * thinking disabled (its reservation is what blew the balance). Continuation
 * then completes the answer in parts.
 */
suspend fun CloudProvider.completeResult(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    thinkingBudget: Int? = null,
    historyLimit: Int = 20,
    model: String? = null,
    images: List<VisionImage> = emptyList(),
    timeoutMs: Int = 90000,
    effort: String? = null
): CloudResult {
    val budget = creditBudget(maxTokens)
    val thinking = if (budget < maxTokens) null else thinkingBudget
    return try {
        completeResultOnce(apiKey, history, budget, thinking, historyLimit, model, images, timeoutMs, effort, budget < maxTokens)
    } catch (e: CloudApiException) {
        val affordable = affordableBudget(budget, e) ?: throw e
        completeResultOnce(apiKey, history, affordable, null, historyLimit, model, images, timeoutMs, effort, true)
    }
}

private suspend fun CloudProvider.completeResultOnce(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    thinkingBudget: Int? = null,
    historyLimit: Int = 20,
    model: String? = null,
    images: List<VisionImage> = emptyList(),
    timeoutMs: Int = 90000,
    effort: String? = null,
    /** OpenRouter: send reasoning.enabled=false so a tiny credit budget
        reaches the visible answer instead of hidden thinking tokens. */
    disableReasoning: Boolean = false
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
                    if (id == "openrouter") {
                        when {
                            thinkingBudget != null ->
                                put("reasoning", JSONObject().put("max_tokens", thinkingBudget))
                            disableReasoning ->
                                put("reasoning", JSONObject().put("enabled", false))
                        }
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
                CloudResult(extractOpenAiText(first), isLengthCutoff(first), openAiUsage(first))
            } catch (e: CloudApiException) {
                // Newer models (o-series, GPT-5 class) reject max_tokens — retry once.
                if (e.status == 400 && e.message.orEmpty().contains("max_completion_tokens")) {
                    val retry = post(URL("$apiBase/chat/completions"), headers, body("max_completion_tokens"), timeoutMs)
                    CloudResult(extractOpenAiText(retry), isLengthCutoff(retry), openAiUsage(retry))
                } else throw e
            }
        }
        CloudProtocol.ANTHROPIC -> {
            // Thinking budget must stay below max_tokens: headroom for the answer.
            val total = if (thinkingBudget != null) maxOf(maxTokens, thinkingBudget + 1024) else maxTokens
            val effortLevel = anthropicEffortFor(effort ?: "High")
            fun body(adaptive: Boolean, level: String) = JSONObject()
                .put("model", effectiveModel)
                .put("max_tokens", total)
                .put("messages", anthropicMessages(trimmed, images))
                .apply {
                    if (thinkingBudget != null) {
                        if (adaptive) {
                            // 4.7+ rejects "enabled"+budget_tokens; effort steers depth.
                            put("thinking", JSONObject().put("type", "adaptive"))
                            put("output_config", JSONObject().put("effort", level))
                        } else {
                            put(
                                "thinking",
                                JSONObject()
                                    .put("type", "enabled")
                                    .put("budget_tokens", thinkingBudget)
                            )
                        }
                    }
                }
            val headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json"
            )
            val url = URL("$apiBase/messages")
            val json = try {
                // Manual thinking first: the only mode on 4.5 and earlier.
                post(url, headers, body(false, effortLevel), timeoutMs)
            } catch (e: CloudApiException) {
                if (!e.isThinkingShapeRejected()) throw e
                // Adaptive thinking. If this model also lacks the effort level
                // (xhigh/max are not on every model), fall back to high.
                try {
                    post(url, headers, body(true, effortLevel), timeoutMs)
                } catch (e2: CloudApiException) {
                    if (!e2.isEffortRejected()) throw e2
                    post(url, headers, body(true, "high"), timeoutMs)
                }
            }
            val blocks = json.getJSONArray("content")
            val text = (0 until blocks.length())
                .map { blocks.getJSONObject(it) }
                .firstOrNull { it.optString("type") == "text" }
                ?.optString("text", "")
            val truncated = json.optString("stop_reason", "") == "max_tokens"
            if (!text.isNullOrBlank()) CloudResult(text, truncated, anthropicUsage(json))
            else {
                // Thinking-only reply: surface the thinking rather than nothing.
                val thinking = (0 until blocks.length())
                    .map { blocks.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "thinking" }
                    ?.optString("thinking", "")
                if (!thinking.isNullOrBlank()) CloudResult(thinking, truncated, anthropicUsage(json))
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
            CloudResult(text, truncated, geminiUsage(json))
        }
    }
}

/**
 * Streaming chat completion for the chat view and realtime voice: answer
 * deltas are delivered to [onToken] as they arrive (SSE) and the full text
 * returns with the finish reason so chat can continue cut-off replies.
 * Thinking deltas are never shown live — they only back up the body when a
 * model emits no visible text (mirrors [extractOpenAiText]).
 *
 * Applies the same credit-aware budget as [completeResult].
 */
suspend fun CloudProvider.completeStreamingResult(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    thinkingBudget: Int? = null,
    historyLimit: Int = 20,
    model: String? = null,
    /** Page/document images attached to the last user message. */
    images: List<VisionImage> = emptyList(),
    onToken: (String) -> Unit = {},
    isCancelled: () -> Boolean = { false },
    /** Karen effort pill; becomes Anthropic `output_config.effort` on 4.7+. */
    effort: String? = null
): CloudResult {
    val budget = creditBudget(maxTokens)
    val thinking = if (budget < maxTokens) null else thinkingBudget
    var emitted = false
    val tracked: (String) -> Unit = { piece ->
        emitted = true
        onToken(piece)
    }
    return try {
        completeStreamingResultOnce(
            apiKey, history, budget, thinking, historyLimit, model, images, tracked, isCancelled, effort, budget < maxTokens
        )
    } catch (e: CloudApiException) {
        val affordable = affordableBudget(budget, e) ?: throw e
        // A 402 arrives before the first delta; if bytes already streamed,
        // a retry would duplicate them.
        if (emitted) throw e
        completeStreamingResultOnce(
            apiKey, history, affordable, null, historyLimit, model, images, tracked, isCancelled, effort, true
        )
    }
}

private suspend fun CloudProvider.completeStreamingResultOnce(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    thinkingBudget: Int? = null,
    historyLimit: Int = 20,
    model: String? = null,
    /** Page/document images attached to the last user message. */
    images: List<VisionImage> = emptyList(),
    onToken: (String) -> Unit = {},
    isCancelled: () -> Boolean = { false },
    /** Karen effort pill; becomes Anthropic `output_config.effort` on 4.7+. */
    effort: String? = null,
    /** OpenRouter: send reasoning.enabled=false on a clamped budget. */
    disableReasoning: Boolean = false
): CloudResult = withContext(Dispatchers.IO) {
    val trimmed = history.takeLast(historyLimit.coerceIn(4, 80))
    val effectiveModel = modelOrDefault(model)
    val full = StringBuilder()
    val thoughts = StringBuilder()
    var truncated = false
    var usage: CloudUsage? = null
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
                .put("messages", openAiMessages(trimmed, images))
                .apply {
                    if (id == "openrouter") {
                        when {
                            thinkingBudget != null ->
                                put("reasoning", JSONObject().put("max_tokens", thinkingBudget))
                            disableReasoning ->
                                put("reasoning", JSONObject().put("enabled", false))
                        }
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
                        val chunk = JSONObject(payload)
                        // Usage (when the vendor sends it) usually rides the
                        // final chunk, which has an empty choices array.
                        openAiUsage(chunk)?.let { usage = it }
                        val choice = chunk
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
            val effortLevel = anthropicEffortFor(effort ?: "High")
            fun body(adaptive: Boolean, level: String) = JSONObject()
                .put("model", effectiveModel)
                .put("max_tokens", total)
                .put("stream", true)
                .put("messages", anthropicMessages(trimmed, images))
                .apply {
                    if (thinkingBudget != null) {
                        if (adaptive) {
                            put("thinking", JSONObject().put("type", "adaptive"))
                            put("output_config", JSONObject().put("effort", level))
                        } else {
                            put(
                                "thinking",
                                JSONObject()
                                    .put("type", "enabled")
                                    .put("budget_tokens", thinkingBudget)
                            )
                        }
                    }
                }
            val headers = mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream"
            )
            val url = URL("$apiBase/messages")
            val onEvent: (String) -> Unit = on@{ line ->
                if (!line.startsWith("data:")) return@on
                val payload = line.removePrefix("data:").trim()
                if (payload.isNotBlank()) {
                    try {
                        val data = JSONObject(payload)
                        when (data.optString("type")) {
                            "message_start" -> {
                                anthropicUsageOf(data.optJSONObject("message")?.optJSONObject("usage"))
                                    ?.let { usage = it.copy(output = usage?.output ?: 0) }
                            }
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
                                // Thinking tokens ride this event, on the final delta.
                                anthropicUsageOf(data.optJSONObject("usage"))?.let {
                                    usage = CloudUsage(
                                        input = usage?.input ?: it.input,
                                        output = it.output,
                                        reasoning = it.reasoning,
                                        cacheRead = it.cacheRead.takeIf { c -> c > 0 } ?: (usage?.cacheRead ?: 0),
                                        cacheWrite = it.cacheWrite.takeIf { c -> c > 0 } ?: (usage?.cacheWrite ?: 0)
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            try {
                postStreamLines(url, headers, body(false, effortLevel), isCancelled, onEvent)
            } catch (e: CloudApiException) {
                // Nothing was emitted yet: the 400 arrived before the stream.
                if (full.isNotEmpty() || thoughts.isNotEmpty() || !e.isThinkingShapeRejected()) throw e
                try {
                    postStreamLines(url, headers, body(true, effortLevel), isCancelled, onEvent)
                } catch (e2: CloudApiException) {
                    if (!e2.isEffortRejected()) throw e2
                    postStreamLines(url, headers, body(true, "high"), isCancelled, onEvent)
                }
            }
        }
        CloudProtocol.GEMINI -> {
            val body = JSONObject().put("contents", geminiContents(trimmed, images))
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
                    val chunk = JSONObject(payload)
                    // usageMetadata often rides a chunk whose candidates are empty.
                    geminiUsage(chunk)?.let { usage = it }
                    val cand = chunk
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
    CloudResult(full.toString(), truncated, usage?.takeIf { it.any })
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
    isCancelled: () -> Boolean = { false },
    effort: String? = null
): String = completeStreamingResult(
    apiKey = apiKey,
    history = history,
    maxTokens = maxTokens,
    thinkingBudget = thinkingBudget,
    historyLimit = historyLimit,
    model = model,
    onToken = onToken,
    isCancelled = isCancelled,
    effort = effort
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
    // A blocked readLine() cannot see the flag until the next token (up to the
    // 90 s read timeout), so disconnect the socket from a watchdog thread the
    // moment Stop flips it. Without this the turn kept "generating" in the UI
    // long after the user pressed Stop.
    val watchdog = Thread {
        try {
            while (!isCancelled()) Thread.sleep(100)
            runCatching { conn.disconnect() }
        } catch (_: InterruptedException) {
        }
    }.apply {
        isDaemon = true
        name = "karen-cancel-watchdog"
        start()
    }
    try {
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            } ?: ""
            throw CloudApiException(code, err.take(300))
        }
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
        watchdog.interrupt()
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
