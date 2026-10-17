package com.karen.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
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
 *   GET  /                    browser dashboard — chat with the on-device model
 *   GET  /v1/models            installed weights actually on this phone
 *   POST /v1/chat/completions  runs the loaded local GGUF (stream:true → SSE)
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

    /** Diagnostics for the Settings card: last request + total since start. */
    var lastRequest by mutableStateOf("")
        private set
    var requestCount by mutableStateOf(0)
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

    /** Browser address other devices on the same Wi-Fi can open. */
    fun lanUrl(ctx: Context): String = "http://${lanIp(ctx)}:${UserPrefs.serverPort(ctx)}"

    /** Probes the running server like a real client; short human verdict. */
    suspend fun selfTest(ctx: Context): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        fun probe(url: String): String = try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            when {
                code == 200 && body.contains("Hello from Karen") -> "OK"
                code == 200 -> "OK (unexpected body)"
                else -> "HTTP $code"
            }
        } catch (e: Exception) {
            val n = e.javaClass.simpleName
            if (n.contains("Timeout", ignoreCase = true)) "timeout"
            else "$n${e.message?.let { ": ${it.take(50)}" } ?: ""}"
        }
        val port = UserPrefs.serverPort(ctx)
        val ip = lanIp(ctx)
        val loop = probe("http://127.0.0.1:$port/")
        val lan = if (ip == "127.0.0.1") "no Wi-Fi address" else probe("http://$ip:$port/")
        "Loopback → $loop · LAN ($ip) → $lan"
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
            lastRequest = ""
            requestCount = 0
            status = "Listening on ${if (bindAddr == null) "0.0.0.0" else "127.0.0.1"}:$port"
            job.launch {
                try {
                    while (running) {
                        val client = try {
                            server.accept()
                        } catch (_: Exception) {
                            // Transient accept hiccup: keep listening.
                            if (!running) break
                            kotlinx.coroutines.delay(100)
                            continue
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
            val peer = try {
                socket.inetAddress?.hostAddress ?: "?"
            } catch (_: Exception) {
                "?"
            }
            lastRequest = "$method $path from $peer"
            requestCount += 1
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

            // Dashboard — no auth required; model/API calls from it need the token.
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                writeHtml(socket, dashboardPage(ctx))
                socket.close()
                return
            }

            // Bearer auth is mandatory for API routes.
            val auth = headers["authorization"].orEmpty()
            if (auth != "Bearer ${token(ctx)}") {
                // Browser visits get a readable HTML hint; API clients get JSON.
                if (method == "GET" && headers["accept"].orEmpty().contains("text/html")) {
                    writeHtml(socket, authHintPage(), 401)
                } else {
                    writeJson(socket, 401, JSONObject().put("error", errorObj("invalid bearer token")))
                }
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
                        chatCompletionSse(ctx, socket, body)
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
            val picked = pickModel(ctx, socket, body) ?: return
            val (pick, file) = picked
            val window = UserPrefs.contextTokens(ctx)
            if (!KarenLlama.ensureLoaded(file, pick, window, 4)) {
                writeJson(socket, 500, JSONObject().put("error", errorObj("could not load $pick into RAM")))
                return
            }
            val (roles, texts) = readTurns(body)
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

    /** Resolves the requested model; on failure writes the error and returns null. */
    private fun pickModel(ctx: Context, socket: Socket, body: JSONObject): Pair<String, File>? {
        val want = body.optString("model", "").trim()
        val installed = UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
        val pick = when {
            want.isNotBlank() && want in installed -> want
            want.isNotBlank() -> {
                writeJson(
                    socket, 404,
                    JSONObject().put("error", errorObj("model '$want' not on device — download it in Model Manager"))
                )
                return null
            }
            else -> {
                val preferred = UserPrefs.preferredLocalModel(ctx).takeIf { it in installed }
                preferred ?: installed.firstOrNull()
            }
        }
        if (pick == null) {
            writeJson(
                socket, 503,
                JSONObject().put("error", errorObj("no local model installed — download one in Model Manager"))
            )
            return null
        }
        val file = ModelDownloader.weightFileFor(ctx, pick)
        if (file == null || !KarenLlama.ready) {
            writeJson(socket, 503, JSONObject().put("error", errorObj("runtime not ready")))
            return null
        }
        return pick to file
    }

    /** OpenAI-style turns from the request body into parallel role/text lists. */
    private fun readTurns(body: JSONObject): Pair<List<String>, List<String>> {
        val turns = body.optJSONArray("messages") ?: JSONArray()
        val roles = mutableListOf<String>()
        val texts = mutableListOf<String>()
        for (i in 0 until turns.length()) {
            val m = turns.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = when (val c = m.opt("content")) {
                is String -> c
                is JSONArray -> (0 until c.length()).mapNotNull { idx ->
                    c.optJSONObject(idx)?.optString("text", "")?.takeIf { it.isNotBlank() }
                }.joinToString("\n")
                else -> null
            } ?: continue
            roles.add(if (role == "assistant" || role == "system") role else "user")
            texts.add(content)
        }
        return roles to texts
    }

    /**
     * SSE variant for `stream: true`: each generated piece is written as a
     * `data: {chunk}` event and flushed immediately, so the browser dashboard
     * shows tokens live. Close-delimited (no Content-Length) — the final
     * `data: [DONE]` plus socket close ends the stream.
     */
    private fun chatCompletionSse(ctx: Context, socket: Socket, body: JSONObject) {
        if (!busy.compareAndSet(false, true)) {
            writeJson(socket, 429, JSONObject().put("error", errorObj("model busy — retry shortly")))
            return
        }
        try {
            val picked = pickModel(ctx, socket, body) ?: return
            val (pick, file) = picked
            val window = UserPrefs.contextTokens(ctx)
            if (!KarenLlama.ensureLoaded(file, pick, window, 4)) {
                writeJson(socket, 500, JSONObject().put("error", errorObj("could not load $pick into RAM")))
                return
            }
            val (roles, texts) = readTurns(body)
            if (texts.isEmpty()) {
                writeJson(socket, 400, JSONObject().put("error", errorObj("messages[] required")))
                return
            }
            val maxTokens = body.optInt("max_tokens", 512).coerceIn(1, 4096)
            val out = socket.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            out.flush()
            val id = "karen-${System.currentTimeMillis()}"
            var gone = false
            fun emit(obj: JSONObject) {
                if (gone) return
                try {
                    out.write(("data: $obj\n\n").toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (_: Exception) {
                    // Client left: stop generating.
                    gone = true
                    KarenLlama.cancel()
                }
            }
            try {
                KarenLlama.completeStream(
                    "You are Karen, a helpful on-device assistant.",
                    roles.toTypedArray(),
                    texts.toTypedArray(),
                    maxTokens
                ) { piece ->
                    if (piece.isNotEmpty()) {
                        emit(
                            JSONObject().put("id", id)
                                .put("object", "chat.completion.chunk")
                                .put("model", pick)
                                .put(
                                    "choices",
                                    JSONArray().put(
                                        JSONObject().put("index", 0)
                                            .put("delta", JSONObject().put("content", piece))
                                    )
                                )
                        )
                    }
                }
            } catch (_: Exception) {
            }
            emit(
                JSONObject().put("id", id)
                    .put("object", "chat.completion.chunk")
                    .put("model", pick)
                    .put(
                        "choices",
                        JSONArray().put(
                            JSONObject().put("index", 0)
                                .put("finish_reason", "stop")
                        )
                    )
            )
            if (!gone) {
                try {
                    out.write("data: [DONE]\n\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        } finally {
            busy.set(false)
            try {
                socket.close()
            } catch (_: Exception) {
            }
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

    private fun writeHtml(socket: Socket, html: String, code: Int = 200) {
        val body = html.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    /** Friendly browser page for token-protected routes (never shows the token). */
    private fun authHintPage(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Karen — Token required</title>
<style>
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         background: #0c0e12; color: #e2e2e8; min-height: 100vh; display: flex;
         align-items: center; justify-content: center; margin: 0; }
  .card { background: #1a1c20; border: 1px solid #283042; border-radius: 16px;
          padding: 32px; max-width: 460px; width: 88%; }
  h1 { font-size: 19px; margin: 0 0 6px; }
  p { color: #94a3b8; font-size: 13.5px; line-height: 1.55; margin: 8px 0; }
  code { font-family: 'JetBrains Mono', monospace; background: #0c0e12;
         border: 1px solid #283042; border-radius: 6px; padding: 1px 6px; font-size: 12.5px; }
  .badge { display: inline-block; background: rgba(245,158,11,0.14); color: #f59e0b;
           border-radius: 20px; padding: 3px 12px; font-size: 11.5px; font-weight: 600; margin-bottom: 14px; }
</style>
</head>
<body>
<div class="card">
  <div class="badge">TOKEN REQUIRED</div>
  <h1>This is the Karen API</h1>
  <p>Only model/API calls need the bearer token. The dashboard does not:</p>
  <p>Dashboard → <code>/</code></p>
  <p>API calls → send <code>Authorization: Bearer &lt;token&gt;</code>.
     Find the token in Karen → Settings → Local Server → Bearer token.</p>
</div>
</body>
</html>
""".trimIndent()

    /** Browser dashboard — chat with the on-device model, no app install. */
    private fun dashboardPage(ctx: Context): String {
        val ip = lanIp(ctx)
        val port = UserPrefs.serverPort(ctx)
        val models = UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Karen — Dashboard</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         background: #0c0e12; color: #e2e2e8; height: 100vh; display: flex; flex-direction: column; }
  header { display: flex; align-items: center; gap: 12px; padding: 13px 18px;
           background: #14171f; border-bottom: 1px solid #283042; }
  .logo { width: 36px; height: 36px; background: #10A37F; border-radius: 9px;
          display: flex; align-items: center; justify-content: center;
          font-weight: 700; color: #fff; font-size: 17px; flex: none; }
  header h1 { font-size: 15px; font-weight: 600; }
  header .sub { font-size: 11.5px; color: #64748b; }
  .status { margin-left: auto; display: flex; align-items: center; gap: 7px;
            font-size: 12px; color: #10A37F; white-space: nowrap; }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: #10A37F; }
  .dot.off { background: #f59e0b; }
  .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap;
         padding: 10px 18px; background: #111317; border-bottom: 1px solid #1e252e; }
  input, select, button, textarea { font: inherit; border-radius: 8px; border: 1px solid #283042;
         background: #1a1c20; color: #e2e2e8; padding: 8px 10px; outline: none; }
  input:focus, select:focus, textarea:focus { border-color: #38bdf8; }
  button { background: #10A37F; border-color: #10A37F; color: #fff; font-weight: 600;
           cursor: pointer; padding: 8px 16px; }
  button.ghost { background: transparent; border-color: #283042; color: #94a3b8; font-weight: 500; }
  button:disabled { opacity: .45; cursor: default; }
  main { flex: 1; overflow-y: auto; padding: 18px; display: flex; flex-direction: column; gap: 12px; }
  .msg { max-width: 82%; padding: 10px 14px; border-radius: 14px; font-size: 14px;
         line-height: 1.55; white-space: pre-wrap; word-break: break-word; }
  .msg.user { align-self: flex-end; background: #10A37F; color: #fff; border-bottom-right-radius: 4px; }
  .msg.assistant { align-self: flex-start; background: #1b2029; border: 1px solid #283042;
                   border-bottom-left-radius: 4px; }
  .msg.sys { align-self: center; background: transparent; border: 1px dashed #283042;
             color: #94a3b8; font-size: 12.5px; text-align: center; max-width: 92%; }
  .hint { font-size: 11.5px; color: #64748b; padding: 0 18px 10px; background: #14171f; }
  footer { display: flex; gap: 8px; padding: 10px 18px 14px; background: #14171f;
           border-top: 1px solid #283042; }
  footer textarea { flex: 1; resize: none; height: 54px; border-radius: 12px;
                    background: #0f1116; padding: 15px 14px; }
  .toast { position: fixed; bottom: 96px; left: 50%; transform: translateX(-50%);
           background: #1b2029; border: 1px solid #38bdf8; color: #cce5ff;
           padding: 9px 16px; border-radius: 20px; font-size: 12.5px; opacity: 0;
           transition: opacity .25s; pointer-events: none; white-space: nowrap; }
  .toast.show { opacity: 1; }
</style>
</head>
<body>
<header>
  <div class="logo">K</div>
  <div>
    <h1>Karen Dashboard</h1>
    <div class="sub">the model runs on the phone — this page is just the window</div>
  </div>
  <div class="status"><span class="dot" id="dot"></span><span id="statusText">connecting…</span></div>
</header>
<div class="bar">
  <input id="token" type="password" placeholder="Bearer token — Karen → Settings → Local Server" style="flex:1;min-width:230px">
  <button class="ghost" onclick="saveToken()">Save</button>
  <select id="model" style="min-width:180px"><option value="">(pick model)</option></select>
  <button class="ghost" onclick="loadModels()">Refresh</button>
</div>
<main id="chat"></main>
<div class="hint">Enter sends · Shift+Enter makes a new line · Stop cancels generation</div>
<footer>
  <textarea id="input" placeholder="Ask the on-device model…"></textarea>
  <button id="send" onclick="send()">Send</button>
  <button id="stop" class="ghost" onclick="stopGen()" disabled>Stop</button>
</footer>
<div class="toast" id="toast"></div>
<script>
var S = { token: localStorage.getItem('karen_token') || '', busy: false, ctrl: null, log: [] };
function el(id) { return document.getElementById(id); }
function toast(m) { var t = el('toast'); t.textContent = m; t.classList.add('show');
  setTimeout(function () { t.classList.remove('show'); }, 2400); }
function setStatus(ok, text) { el('dot').className = ok ? 'dot' : 'dot off'; el('statusText').textContent = text; }
function add(role, text) { var d = document.createElement('div'); d.className = 'msg ' + role;
  d.textContent = text; el('chat').appendChild(d); el('chat').scrollTop = el('chat').scrollHeight; return d; }
function saveToken() { S.token = el('token').value.trim();
  localStorage.setItem('karen_token', S.token);
  toast(S.token ? 'Token saved' : 'Token cleared'); loadModels(); }
function loadModels() {
  fetch('/v1/models', { headers: { 'Authorization': 'Bearer ' + S.token } }).then(function (r) {
    if (r.status === 401) { setStatus(false, 'token needed'); return null; }
    if (!r.ok) { setStatus(false, 'HTTP ' + r.status); return null; }
    return r.json();
  }).then(function (j) {
    if (!j) return;
    var ms = (j.data || []).map(function (m) { return m.id; });
    var sel = el('model'); var cur = sel.value;
    sel.innerHTML = '';
    if (!ms.length) { var o0 = document.createElement('option'); o0.value = ''; o0.textContent = '(no local models)'; sel.appendChild(o0); }
    ms.forEach(function (m) { var o = document.createElement('option'); o.value = m; o.textContent = m; sel.appendChild(o); });
    if (cur && ms.indexOf(cur) >= 0) { sel.value = cur; } else if (ms.length) { sel.value = ms[0]; }
    setStatus(true, 'online · ' + ms.length + ' model(s)');
  }).catch(function () { setStatus(false, 'unreachable'); });
}
function stopGen() { if (S.ctrl) { S.ctrl.abort(); } }
function send() {
  var text = el('input').value.trim();
  if (!text || S.busy) return;
  if (!S.token) { toast('Paste the bearer token first'); return; }
  var model = el('model').value;
  if (!model) { toast('Pick a model'); return; }
  el('input').value = '';
  add('user', text);
  S.log.push({ role: 'user', content: text });
  var bubble = add('assistant', '…');
  S.busy = true; el('send').disabled = true; el('stop').disabled = false;
  S.ctrl = new AbortController();
  var full = '';
  fetch('/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + S.token },
    body: JSON.stringify({ model: model, messages: S.log.slice(-12), stream: true, max_tokens: 1024 }),
    signal: S.ctrl.signal
  }).then(function (r) {
    if (!r.ok) {
      return r.text().then(function (t) {
        bubble.className = 'msg sys';
        bubble.textContent = 'Error ' + r.status + ' — ' + t.slice(0, 240);
        if (S.log.length && S.log[S.log.length - 1].role === 'user') { S.log.pop(); }
      });
    }
    var reader = r.body.getReader();
    var dec = new TextDecoder();
    var buf = '';
    function pump() {
      return reader.read().then(function (step) {
        if (step.done) return;
        buf += dec.decode(step.value, { stream: true });
        var i;
        while ((i = buf.indexOf('\n\n')) >= 0) {
          var evt = buf.slice(0, i); buf = buf.slice(i + 2);
          if (!evt.startsWith('data:')) continue;
          var payload = evt.slice(5).trim();
          if (payload === '[DONE]') continue;
          try {
            var j = JSON.parse(payload);
            var ch = j.choices && j.choices[0];
            var d = ch && ch.delta && ch.delta.content;
            if (d) { full += d; bubble.textContent = full; el('chat').scrollTop = el('chat').scrollHeight; }
          } catch (e) {}
        }
        return pump();
      });
    }
    return pump().then(function () {
      if (!full) {
        bubble.className = 'msg sys'; bubble.textContent = '(empty reply)';
        if (S.log.length && S.log[S.log.length - 1].role === 'user') { S.log.pop(); }
      } else { S.log.push({ role: 'assistant', content: full }); }
    });
  }).catch(function () {
    if (full) { S.log.push({ role: 'assistant', content: full }); bubble.textContent = full + ' [stopped]'; }
    else {
      bubble.className = 'msg sys'; bubble.textContent = 'stopped';
      if (S.log.length && S.log[S.log.length - 1].role === 'user') { S.log.pop(); }
    }
  }).then(function () {
    S.busy = false; el('send').disabled = false; el('stop').disabled = true; S.ctrl = null;
  });
}
el('input').addEventListener('keydown', function (e) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});
el('token').value = S.token;
add('sys', 'Hello from Karen — connected to $ip:$port. ${models.size} local model(s) on this phone. Paste the token from Settings → Local Server, pick a model, then ask away.');
loadModels();
</script>
</body>
</html>
        """.trimIndent()
    }

    /**
     * IPv4 address other LAN devices can reach: active Wi-Fi from
     * ConnectivityManager first (the legacy WifiManager read returns 0 on
     * many modern builds), the legacy read next, then a raw interface scan
     * (covers hotspot/tethering where wlan0 is unset).
     */
    private fun lanIp(ctx: Context): String {
        val app = ctx.applicationContext
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val hit = cm.getLinkProperties(net)?.linkAddresses?.firstOrNull {
                    val a = it.address
                    a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress
                }?.address?.hostAddress
                if (!hit.isNullOrBlank()) return hit
            }
        } catch (_: Exception) {
        }
        try {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) {
                return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
            }
        } catch (_: Exception) {
        }
        try {
            val sites = mutableListOf<Pair<String, String>>()
            val nifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (nifs.hasMoreElements()) {
                val nif = nifs.nextElement() ?: continue
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                if (!name.startsWith("wlan") && !name.startsWith("eth") &&
                    !name.startsWith("ap") && !name.startsWith("swlan")
                ) continue
                for (ia in nif.inetAddresses) {
                    val a = ia as? Inet4Address ?: continue
                    if (a.isLoopbackAddress || a.isLinkLocalAddress) continue
                    sites.add(name to (a.hostAddress ?: continue))
                }
            }
            sites.firstOrNull { it.first.startsWith("wlan") }?.let { return it.second }
            sites.firstOrNull()?.let { return it.second }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }
}
