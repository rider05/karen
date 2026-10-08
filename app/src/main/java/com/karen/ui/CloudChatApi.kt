package com.karen.ui

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
    CloudProvider("groq", "Groq", "console.groq.com", "Ultra-low-latency LPU inference", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", CloudProtocol.OPENAI)
)

/** Thinking-token budget per effort level for reasoning models. */
fun thinkingBudgetFor(effort: String): Int = when (effort) {
    "Low" -> 1000
    "High" -> 4000
    "Max" -> 8000
    "Extreme" -> 16000
    else -> 2000
}

fun findCloudProviderByName(name: String): CloudProvider? =
    cloudProviders.find { it.name == name }

/** True for cloud entries that must never appear in installed-weights lists. */
fun isCloudProviderName(name: String): Boolean =
    cloudProviders.any { it.name == name }

/** Carries the HTTP status so the chat can show actionable key/quota errors. */
class CloudApiException(val status: Int, message: String) : Exception(message)

/** Result of a cloud call: text plus whether the model was cut off by its output cap. */
data class CloudResult(val text: String, val truncated: Boolean)

/** Effective model id: per-key override from Model Manager, else the default. */
fun CloudProvider.modelOrDefault(override: String?): String =
    if (override.isNullOrBlank()) defaultModel else override.trim()

suspend fun CloudProvider.complete(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    /** Thinking-token budget for reasoning models; null disables thinking. */
    thinkingBudget: Int? = null,
    /** How many trailing turns fit the configured content window. */
    historyLimit: Int = 20,
    /** Model id override (per stored key); null/blank uses [defaultModel]. */
    model: String? = null
): String = completeResult(apiKey, history, maxTokens, thinkingBudget, historyLimit, model).text

suspend fun CloudProvider.completeResult(
    apiKey: String,
    history: List<Pair<String, String>>,
    maxTokens: Int,
    thinkingBudget: Int? = null,
    historyLimit: Int = 20,
    model: String? = null
): CloudResult = withContext(Dispatchers.IO) {
    val trimmed = history.takeLast(historyLimit.coerceIn(4, 80))
    val effectiveModel = modelOrDefault(model)
    when (protocol) {
        CloudProtocol.OPENAI -> {
            fun body(tokenField: String) = JSONObject()
                .put("model", effectiveModel)
                .put(tokenField, maxTokens)
                .put(
                    "messages",
                    JSONArray(trimmed.map { (role, text) ->
                        JSONObject().put("role", role).put("content", text)
                    })
                )
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
                val first = post(URL("$apiBase/chat/completions"), headers, body("max_tokens"))
                CloudResult(extractOpenAiText(first), isLengthCutoff(first))
            } catch (e: CloudApiException) {
                // Newer models (o-series, GPT-5 class) reject max_tokens — retry once.
                if (e.status == 400 && e.message.orEmpty().contains("max_completion_tokens")) {
                    val retry = post(URL("$apiBase/chat/completions"), headers, body("max_completion_tokens"))
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
                .put(
                    "messages",
                    JSONArray(trimmed.map { (role, text) ->
                        JSONObject().put("role", role).put("content", text)
                    })
                )
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
                body
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
            val body = JSONObject().put(
                "contents",
                JSONArray(trimmed.map { (role, text) ->
                    JSONObject()
                        .put("role", if (role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                })
            )
            val json = post(
                URL("$apiBase/models/$effectiveModel:generateContent"),
                mapOf(
                    "x-goog-api-key" to apiKey,
                    "Content-Type" to "application/json"
                ),
                body
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

private fun post(url: URL, headers: Map<String, String>, body: JSONObject): JSONObject {
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 15000
        readTimeout = 90000
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
