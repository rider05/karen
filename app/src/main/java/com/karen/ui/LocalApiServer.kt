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

            // Landing page — no auth required.
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                writeHtml(socket, landingPage(ctx))
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
  <p>Only model/API routes need the bearer token. The status page does not:</p>
  <p>Status page → <code>/</code></p>
  <p>API calls → send <code>Authorization: Bearer &lt;token&gt;</code>.
     Find the token in Karen → Settings → Local Server → Bearer token.</p>
</div>
</body>
</html>
""".trimIndent()

    private fun landingPage(ctx: Context): String {
        val ip = lanIp(ctx)
        val port = UserPrefs.serverPort(ctx)
        val models = UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Karen — Local Server</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #0c0e12;
    color: #e2e2e8;
    min-height: 100vh;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .card {
    background: #1a1c20;
    border: 1px solid #283042;
    border-radius: 16px;
    padding: 40px;
    max-width: 480px;
    width: 90%;
    text-align: center;
  }
  .logo {
    width: 56px; height: 56px;
    background: #10A37F;
    border-radius: 14px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    font-size: 24px;
    font-weight: 700;
    color: #fff;
    margin-bottom: 20px;
  }
  h1 { font-size: 22px; font-weight: 700; margin-bottom: 8px; }
  .sub { color: #94a3b8; font-size: 14px; margin-bottom: 24px; }
  .row {
    display: flex;
    justify-content: space-between;
    padding: 10px 0;
    border-bottom: 1px solid #283042;
    font-size: 13px;
  }
  .row:last-child { border-bottom: none; }
  .label { color: #64748b; }
  .value { color: #e2e2e8; font-family: 'JetBrains Mono', monospace; }
  .status {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: rgba(16,163,127,0.12);
    color: #10A37F;
    padding: 4px 12px;
    border-radius: 20px;
    font-size: 12px;
    font-weight: 600;
    margin-top: 20px;
  }
  .dot { width: 8px; height: 8px; background: #10A37F; border-radius: 50%; }
  .models { margin-top: 16px; font-size: 12px; color: #64748b; }
  .models span { color: #94a3b8; }
</style>
</head>
<body>
<div class="card">
  <div class="logo">K</div>
  <h1>Hello from Karen</h1>
  <p class="sub">Your local AI server is running</p>
  <div class="row"><span class="label">Address</span><span class="value">$ip:$port</span></div>
  <div class="row"><span class="label">Status</span><span class="value">Active</span></div>
  <div class="row"><span class="label">Models</span><span class="value">${models.size} installed</span></div>
  <div class="status"><span class="dot"></span>&nbsp;Server Online</div>
  ${if (models.isNotEmpty()) "<p class=\"models\">${models.take(3).joinToString(", ")}${if (models.size > 3) " +${models.size - 3} more" else ""}</p>" else ""}
</div>
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
