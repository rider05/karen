package com.karen.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device OpenAI-compatible API (Thinai-style local server, no new deps).
 *
 * Plain ServerSocket HTTP/1.1 on localhost:11434 by default:
 *   GET  /v1/models            installed weights actually on this phone
 *   POST /v1/chat/completions  runs the loaded local GGUF (non-streaming)
 *   POST /v1/embeddings        501 — no embedding runtime ships on-device
 *   GET  /api/tags             Ollama-shaped model list
 *
 * Bearer token auth is mandatory (generated on first start, regenerable in
 * Settings). LAN bind is an explicit opt-in with a warning. The server lives
 * with the app process — it stops when Karen is swiped away.
 */
object LocalApiServer {
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("Stopped")
        private set

    private var socket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val busy = AtomicBoolean(false)

    fun token(ctx: Context): String {
        val cur = UserPrefs.serverToken(ctx)
        if (cur.isNotBlank()) return cur
        val fresh = UUID.randomUUID().toString().replace("-", "")
        UserPrefs.setServerToken(ctx, fresh)
        return fresh
    }

    fun start(ctx: Context): Boolean {
        if (running) return true
        val port = UserPrefs.serverPort(ctx)
        if (port !in 1..65535) {
            status = "Bad port (1–65535)"
            return false
        }
        return try {
            val bindAddr = if (UserPrefs.serverLan(ctx)) null else InetAddress.getByName("127.0.0.1")
            val server = if (bindAddr == null) ServerSocket(port) else ServerSocket(port, 8, bindAddr)
            socket = server
            val appCtx = ctx.applicationContext
            val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
            running = true
            status = "Listening on ${if (bindAddr == null) "0.0.0.0" else "127.0.0.1"}:$port"
            job.launch {
                try {
                    while (running) {
                        val client = try {
                            server.accept()
                        } catch (_: Exception) {
                            break
                        }
                        launch { handle(appCtx, client) }
                    }
                } finally {
                    running = false
                }
            }
            true
        } catch (e: Exception) {
            status = "Could not bind :$port (${e.message})"
            running = false
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            socket = null
            false
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        try {
            scope?.cancel()
        } catch (_: Exception) {
        }
        scope = null
        status = "Stopped"
    }

    private fun handle(ctx: Context, socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedInputStream(socket.getInputStream())
            val head = readHead(input) ?: run {
                socket.close()
                return
            }
            val requestLine = head.first
            val headers = head.second
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeJson(socket, 400, JSONObject().put("error", errorObj("bad request")))
                socket.close()
                return
            }
            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 524288) ?: 0
            val bodyBytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bodyBytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            val body = if (contentLength > 0) {
                try {
                    JSONObject(String(bodyBytes, 0, read, Charsets.UTF_8))
                } catch (_: Exception) {
                    null
                }
            } else null

            // Bearer auth is mandatory, even on localhost.
            val auth = headers["authorization"].orEmpty()
            if (auth != "Bearer ${token(ctx)}") {
                writeJson(socket, 401, JSONObject().put("error", errorObj("invalid bearer token")))
                socket.close()
                return
            }

            when {
                method == "GET" && path == "/v1/models" -> {
                    val data = JSONArray()
                    UserPrefs.models(ctx).filter { !isCloudProviderName(it) }.forEach { name ->
                        data.put(
                            JSONObject().put("id", name)
                                .put("object", "model")
                                .put("owned_by", "karen")
                        )
                    }
                    writeJson(
                        socket, 200,
                        JSONObject().put("object", "list").put("data", data)
                    )
                }
                method == "GET" && path == "/api/tags" -> {
                    val models = JSONArray()
                    UserPrefs.models(ctx).filter { !isCloudProviderName(it) }.forEach { name ->
                        models.put(JSONObject().put("name", name).put("model", name))
                    }
                    writeJson(socket, 200, JSONObject().put("models", models))
                }
                method == "POST" && path == "/v1/chat/completions" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else if (body.optBoolean("stream", false)) {
                        writeJson(
                            socket, 400,
                            JSONObject().put("error", errorObj("streaming not supported — retry with stream:false"))
                        )
                    } else {
                        chatCompletion(ctx, socket, body)
                    }
                }
                method == "POST" && (path == "/v1/embeddings" || path == "/api/embed") -> {
                    writeJson(
                        socket, 501,
                        JSONObject().put(
                            "error",
                            errorObj("embeddings unavailable — no embedding runtime ships on-device")
                        )
                    )
                }
                else -> {
                    writeJson(socket, 404, JSONObject().put("error", errorObj("unknown route $path")))
                }
            }
            socket.close()
        } catch (_: Exception) {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun chatCompletion(ctx: Context, socket: Socket, body: JSONObject) {
        if (!busy.compareAndSet(false, true)) {
            writeJson(socket, 429, JSONObject().put("error", errorObj("model busy — retry shortly")))
            return
        }
        try {
            val want = body.optString("model", "").trim()
            val installed = UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
            val pick = when {
                want.isNotBlank() && want in installed -> want
                want.isNotBlank() -> {
                    writeJson(
                        socket, 404,
                        JSONObject().put("error", errorObj("model '$want' not on device — download it in Model Manager"))
                    )
                    return
                }
                else -> installed.firstOrNull()
            }
            if (pick == null) {
                writeJson(
                    socket, 503,
                    JSONObject().put("error", errorObj("no local model installed — download one in Model Manager"))
                )
                return
            }
            val file = ModelDownloader.weightFileFor(ctx, pick)
            if (file == null || !KarenLlama.ready) {
                writeJson(socket, 503, JSONObject().put("error", errorObj("runtime not ready")))
                return
            }
            val window = UserPrefs.contextTokens(ctx)
            if (!KarenLlama.ensureLoaded(file, pick, window, 4)) {
                writeJson(socket, 500, JSONObject().put("error", errorObj("could not load $pick into RAM")))
                return
            }
            val turns = body.optJSONArray("messages") ?: JSONArray()
            val roles = mutableListOf<String>()
            val texts = mutableListOf<String>()
            for (i in 0 until turns.length()) {
                val m = turns.optJSONObject(i) ?: continue
                val role = m.optString("role", "user")
                val content = when (val c = m.opt("content")) {
                    is String -> c
                    is JSONArray -> (0 until c.length()).mapNotNull { idx ->
                        c.optJSONObject(idx)?.optString("text", null)
                    }.joinToString("\n")
                    else -> null
                } ?: continue
                roles.add(if (role == "assistant" || role == "system") role else "user")
                texts.add(content)
            }
            if (texts.isEmpty()) {
                writeJson(socket, 400, JSONObject().put("error", errorObj("messages[] required")))
                return
            }
            val maxTokens = body.optInt("max_tokens", 512).coerceIn(1, 4096)
            // Note: the on-device sampler is fixed (temp 0.7); a temperature
            // field is accepted for compatibility but does not retune it.
            val reply = try {
                KarenLlama.complete(
                    "You are Karen, a helpful on-device assistant.",
                    roles.toTypedArray(),
                    texts.toTypedArray(),
                    maxTokens
                )
            } catch (e: Exception) {
                writeJson(socket, 500, JSONObject().put("error", errorObj("generation failed (${e.message})")))
                return
            }
            val completionTokens = reply.length / 4
            val promptTokens = texts.sumOf { it.length } / 4
            writeJson(
                socket, 200,
                JSONObject()
                    .put("id", "karen-${System.currentTimeMillis()}")
                    .put("object", "chat.completion")
                    .put("created", System.currentTimeMillis() / 1000)
                    .put("model", pick)
                    .put(
                        "choices",
                        JSONArray().put(
                            JSONObject()
                                .put("index", 0)
                                .put(
                                    "message",
                                    JSONObject().put("role", "assistant").put("content", reply)
                                )
                                .put("finish_reason", "stop")
                        )
                    )
                    .put(
                        "usage",
                        JSONObject()
                            .put("prompt_tokens", promptTokens)
                            .put("completion_tokens", completionTokens)
                            .put("total_tokens", promptTokens + completionTokens)
                    )
            )
        } finally {
            busy.set(false)
        }
    }

    private fun errorObj(message: String): JSONObject =
        JSONObject().put("message", message).put("type", "server_error")

    private fun readHead(input: BufferedInputStream): Pair<String, Map<String, String>>? {
        val raw = ByteArrayOutputStream()
        val one = ByteArray(1)
        var matched = 0
        // Cap headers at 64 KB.
        while (raw.size() < 65536) {
            val n = try {
                input.read(one)
            } catch (_: Exception) {
                return null
            }
            if (n < 0) break
            raw.write(one[0].toInt())
            matched = if (one[0] == "\r\n\r\n"[matched].code.toByte()) matched + 1 else 0
            if (matched == 4) break
        }
        val text = try {
            raw.toString("UTF-8")
        } catch (_: Exception) {
            return null
        }
        val lines = text.split("\r\n")
        if (lines.isEmpty() || lines[0].isBlank()) return null
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        return lines[0] to headers
    }

    private fun writeJson(socket: Socket, code: Int, json: JSONObject) {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }
}
