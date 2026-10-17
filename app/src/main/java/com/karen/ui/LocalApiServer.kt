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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One device that ever talked to the local server (the app's "nodes"). */
data class NodeInfo(
    val ip: String,
    val name: String,
    val kind: String,
    val firstSeen: Long,
    val lastSeen: Long,
    val requests: Int
)

/** One model artifact actually found on disk, with its real runtime verdict. */
data class LocalModelFile(
    val name: String,
    val path: String,
    val format: String,
    val sizeBytes: Long,
    val runtime: String,
    val runnable: Boolean,
    val note: String,
    val source: String
)

/**
 * On-device OpenAI-compatible API (Thinai-style local server, no new deps).
 *
 * Plain ServerSocket HTTP/1.1 on localhost:11434 by default:
 *   GET  /                    browser dashboard — chat + on-device model files
 *   GET  /v1/models            installed weights + runnable .gguf files
 *   GET  /v1/files             every model file found on disk (fmt + runtime)
 *   POST /v1/chat/completions  runs the loaded local GGUF (stream:true → SSE)
 *   POST /v1/probe             loads a file with its real runtime (ORT/GGUF)
 *   POST /v1/embeddings        501 — no embedding runtime ships on-device
 *   GET  /api/tags             Ollama-shaped model list
 *
 * Every device that talks to the server is tracked as a "node" (name + IP +
 * last seen) and listed in the app's Network Nodes screen.
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

    /** Devices that ever talked to the server — shown as nodes in the app. */
    var nodeSnapshot by mutableStateOf<List<NodeInfo>>(emptyList())
        private set
    private val nodes = ConcurrentHashMap<String, NodeInfo>()
    private val nodeNames = ConcurrentHashMap<String, String>()
    private val dnsInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Clears node history (Network Nodes screen → "Clear"). */
    fun clearNodes() {
        nodes.clear()
        nodeSnapshot = emptyList()
    }

    private fun recordNode(remoteIp: String?, path: String, userAgent: String, headerName: String) {
        try {
            val ip = remoteIp ?: return
            if (ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1") return
            val now = System.currentTimeMillis()
            val prev = nodes[ip]
            val kind = if (path == "/" || path == "/index.html" || userAgent.contains("Mozilla/")) "Dashboard" else "API"
            if (headerName.isNotBlank()) nodeNames[ip] = headerName.take(40)
            val name = nodeNames[ip] ?: prev?.name ?: ("Device " + ip.substringAfterLast('.'))
            nodes[ip] = NodeInfo(
                ip = ip,
                name = name,
                kind = kind,
                firstSeen = prev?.firstSeen ?: now,
                lastSeen = now,
                requests = (prev?.requests ?: 0) + 1
            )
            nodeSnapshot = nodes.values.sortedByDescending { it.lastSeen }
            if (nodeNames[ip] == null) {
                resolveNodeName(ip)
            }
        } catch (_: Exception) {
        }
    }

    /** Reverse-DNS the node name in the background (never blocks a request). */
    private fun resolveNodeName(ip: String) {
        val sc = scope ?: return
        if (!dnsInFlight.add(ip)) return
        sc.launch(Dispatchers.IO) {
            try {
                val addr = InetAddress.getByName(ip)
                val host = addr.canonicalHostName
                if (host.isNotBlank() && host != ip) {
                    val clean = host.substringBefore('.').take(40)
                    nodeNames[ip] = clean
                    nodes[ip]?.let {
                        nodes[ip] = it.copy(name = clean)
                        nodeSnapshot = nodes.values.sortedByDescending { n -> n.lastSeen }
                    }
                }
            } catch (_: Exception) {
            } finally {
                dnsInFlight.remove(ip)
            }
        }
    }

    // ---- On-device model file scan (real files, real runtimes) --------------

    private var scanAt = 0L
    private var scanList: List<LocalModelFile> = emptyList()

    /**
     * Walks the app's model dir and the public Download/Documents folders for
     * model artifacts (.gguf/.onnx/.tflite/…), tagging each with the runtime
     * that can actually open it. Cached for 5 s — called per request.
     */
    fun scanModelFiles(ctx: Context, force: Boolean = false): List<LocalModelFile> {
        val now = System.currentTimeMillis()
        if (!force && scanAt > 0 && now - scanAt < 5000) return scanList
        val found = mutableMapOf<String, LocalModelFile>()
        val dirs = mutableListOf<Pair<File, String>>()
        try {
            dirs += ModelDownloader.modelsDir(ctx) to "Karen models"
        } catch (_: Exception) {
        }
        try {
            ctx.getExternalFilesDir(null)?.let { dirs += it to "App storage" }
        } catch (_: Exception) {
        }
        try {
            val pub = android.os.Environment.getExternalStorageDirectory()
            if (pub != null) {
                listOf("Download", "Downloads", "Documents", "Models", "Karen").forEach { sub ->
                    val d = File(pub, sub)
                    try {
                        if (d.isDirectory) dirs += d to sub
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        for ((dir, source) in dirs) {
            try {
                val level1 = dir.listFiles() ?: continue
                for (f in level1) {
                    val name = f.name ?: continue
                    if (f.isDirectory) {
                        if (name.startsWith(".") || name.equals("Android", true)) continue
                        try {
                            f.listFiles()?.take(200)?.forEach { sub -> addScanHit(found, sub, source) }
                        } catch (_: Exception) {
                        }
                    } else {
                        addScanHit(found, f, source)
                    }
                }
            } catch (_: Exception) {
            }
        }
        val list = found.values.sortedWith(
            compareByDescending<LocalModelFile> { it.runnable }
                .thenByDescending { it.sizeBytes }
        )
        scanList = list
        scanAt = now
        return list
    }

    private fun addScanHit(out: MutableMap<String, LocalModelFile>, f: File, source: String) {
        try {
            if (!f.isFile) return
            val name = f.name ?: return
            val ext = name.substringAfterLast('.', "").lowercase()
            val format = when (ext) {
                "gguf" -> "GGUF"
                "ggml" -> "GGML"
                "onnx" -> "ONNX"
                "tflite" -> "TFLITE"
                "task" -> "TASK"
                "safetensors" -> "SAFETENSORS"
                else -> return
            }
            val size = f.length()
            if (size <= 0) return
            val (runtime, runnable, note) = when (format) {
                "GGUF" -> if (ggufMagicOk(f)) {
                    Triple("llama.cpp", true, "")
                } else {
                    Triple("llama.cpp", false, "not a valid GGUF (bad file header)")
                }
                "GGML" -> Triple("none", false, "legacy GGML — convert to GGUF to run")
                "ONNX" -> Triple("onnxruntime", false, "loadable via probe — no chat pipeline")
                else -> Triple("none", false, "no runtime bundled for this format")
            }
            out[f.absolutePath] = LocalModelFile(
                name = name,
                path = f.absolutePath,
                format = format,
                sizeBytes = size,
                runtime = runtime,
                runnable = runnable,
                note = note,
                source = source
            )
        } catch (_: Exception) {
        }
    }

    private fun ggufMagicOk(f: File): Boolean = try {
        f.inputStream().use { ins ->
            val b = ByteArray(4)
            ins.read(b, 0, 4) == 4 &&
                b[0] == 'G'.code.toByte() && b[1] == 'G'.code.toByte() &&
                b[2] == 'U'.code.toByte() && b[3] == 'F'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

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

            // Every request counts its device as a node (shown in the app).
            recordNode(
                remoteIp = try {
                    socket.inetAddress?.hostAddress
                } catch (_: Exception) {
                    null
                },
                path = path,
                userAgent = headers["user-agent"].orEmpty(),
                headerName = headers["x-karen-node"].orEmpty()
            )

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
                    val seen = mutableSetOf<String>()
                    val installedPaths = mutableSetOf<String>()
                    UserPrefs.models(ctx).filter { !isCloudProviderName(it) }.forEach { name ->
                        seen.add(name.lowercase())
                        ModelDownloader.weightFileFor(ctx, name)?.let { installedPaths.add(it.absolutePath) }
                        data.put(
                            JSONObject().put("id", name)
                                .put("object", "model")
                                .put("owned_by", "karen")
                                .put("source", "installed")
                        )
                    }
                    // Real .gguf files found on disk run through the same runtime
                    // (skip files already represented by an installed model).
                    scanModelFiles(ctx).filter { it.runnable }.forEach { f ->
                        if (installedPaths.contains(f.path)) return@forEach
                        if (seen.add(f.name.lowercase())) {
                            data.put(
                                JSONObject().put("id", f.name)
                                    .put("object", "model")
                                    .put("owned_by", "karen")
                                    .put("source", "file")
                                    .put("path", f.path)
                            )
                        }
                    }
                    writeJson(
                        socket, 200,
                        JSONObject().put("object", "list").put("data", data)
                    )
                }
                method == "GET" && path == "/v1/files" -> {
                    val data = JSONArray()
                    scanModelFiles(ctx).forEach { f ->
                        data.put(
                            JSONObject().put("name", f.name)
                                .put("path", f.path)
                                .put("format", f.format)
                                .put("size", f.sizeBytes)
                                .put("runtime", f.runtime)
                                .put("runnable", f.runnable)
                                .put("note", f.note)
                                .put("source", f.source)
                        )
                    }
                    writeJson(socket, 200, JSONObject().put("object", "list").put("data", data))
                }
                method == "POST" && path == "/v1/probe" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        probeModelFile(ctx, socket, body)
                    }
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

    /**
     * Resolves the requested model — installed names first, then runnable
     * .gguf files found on disk; on failure writes the error and returns null.
     */
    private fun pickModel(ctx: Context, socket: Socket, body: JSONObject): Pair<String, File>? {
        val want = body.optString("model", "").trim()
        val installed = UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
        var pick: String? = null
        var file: File? = null

        val inst = installed.firstOrNull { it.equals(want, ignoreCase = true) }
        if (inst != null) {
            pick = inst
            file = ModelDownloader.weightFileFor(ctx, inst)
        } else if (want.isNotBlank()) {
            val hit = scanModelFiles(ctx).firstOrNull {
                it.runnable &&
                    (it.name.equals(want, ignoreCase = true) || it.path.equals(want, ignoreCase = true))
            }
            if (hit != null) {
                pick = hit.name
                file = File(hit.path)
            } else {
                writeJson(
                    socket, 404,
                    JSONObject().put(
                        "error",
                        errorObj("model '$want' not found — download it in Model Manager or copy a .gguf onto the phone (see /v1/files)")
                    )
                )
                return null
            }
        } else {
            val preferred = UserPrefs.preferredLocalModel(ctx).let { p ->
                installed.firstOrNull { it.equals(p, ignoreCase = true) }
            }
            val firstInstalled = preferred ?: installed.firstOrNull()
            if (firstInstalled != null) {
                pick = firstInstalled
                file = ModelDownloader.weightFileFor(ctx, firstInstalled)
            } else {
                val hit = scanModelFiles(ctx).firstOrNull { it.runnable }
                if (hit != null) {
                    pick = hit.name
                    file = File(hit.path)
                }
            }
        }

        if (pick == null || file == null || !KarenLlama.ready) {
            val msg = if (pick == null) {
                "no local model installed — download one in Model Manager or copy a .gguf onto the phone"
            } else {
                "runtime not ready"
            }
            writeJson(socket, 503, JSONObject().put("error", errorObj(msg)))
            return null
        }
        return pick to file
    }

    /** Loads a listed file with its real runtime and reports what happened. */
    private fun probeModelFile(ctx: Context, socket: Socket, body: JSONObject) {
        val want = body.optString("path", "").trim()
        val hit = scanModelFiles(ctx).firstOrNull {
            it.path.equals(want, ignoreCase = true) || it.name.equals(want, ignoreCase = true)
        }
        if (hit == null) {
            writeJson(socket, 404, JSONObject().put("error", errorObj("no such file — refresh the file list")))
            return
        }
        val report = when (hit.format) {
            "GGUF" -> if (ggufMagicOk(File(hit.path))) {
                "GGUF header OK — loadable by llama.cpp (" + hit.sizeBytes / (1024 * 1024) + " MB)"
            } else {
                "GGUF header invalid — llama.cpp will refuse this file"
            }
            "ONNX" -> OrtEngine.describe(File(hit.path)) ?: "onnxruntime unavailable on this device"
            "TFLITE", "TASK" -> "no TFLite runtime is bundled in Karen — file listed only"
            "GGML" -> "legacy GGML — convert to GGUF first"
            else -> "no runtime bundled for " + hit.format
        }
        writeJson(
            socket, 200,
            JSONObject().put("name", hit.name).put("format", hit.format)
                .put("ok", report.contains("OK", ignoreCase = true)).put("report", report)
        )
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
  details.files { background: #111317; border-bottom: 1px solid #1e252e; }
  details.files summary { cursor: pointer; padding: 9px 18px; font-size: 12px; color: #94a3b8; list-style: none; }
  details.files summary::-webkit-details-marker { display: none; }
  details.files summary:hover { color: #e2e2e8; }
  .filelist { max-height: 240px; overflow-y: auto; padding: 0 18px 12px; display: flex; flex-direction: column; gap: 6px; }
  .frow { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 8px 10px; border: 1px solid #283042; border-radius: 10px; background: #14171f; }
  .fname { flex: 1; min-width: 140px; font-family: 'JetBrains Mono', monospace; font-size: 12px; color: #e2e2e8; word-break: break-all; }
  .fmeta { font-size: 10.5px; color: #64748b; font-family: 'JetBrains Mono', monospace; white-space: nowrap; }
  .badge { font-size: 10px; font-weight: 600; border-radius: 10px; padding: 2px 8px; white-space: nowrap; }
  .badge.ok { background: rgba(16,163,127,0.15); color: #10A37F; }
  .badge.mid { background: rgba(56,189,248,0.12); color: #38bdf8; }
  .badge.off { background: rgba(148,163,184,0.12); color: #94a3b8; }
  .frow button { padding: 5px 10px; font-size: 11px; border-radius: 7px; }
  .probe { font-size: 11px; color: #94a3b8; font-family: 'JetBrains Mono', monospace; padding: 2px 6px 4px; word-break: break-word; }
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
<details class="files" id="filesPanel">
  <summary id="filesSummary">On-device model files — loading…</summary>
  <div class="filelist" id="fileList"></div>
</details>
<main id="chat"></main>
<div class="hint">Enter sends · Shift+Enter makes a new line · Stop cancels generation</div>
<footer>
  <textarea id="input" placeholder="Ask the on-device model…"></textarea>
  <button id="send" onclick="send()">Send</button>
  <button id="stop" class="ghost" onclick="stopGen()" disabled>Stop</button>
</footer>
<div class="toast" id="toast"></div>
<script>
var S = { token: localStorage.getItem('karen_token') || '', busy: false, ctrl: null, log: [], node: nodeName() };
function el(id) { return document.getElementById(id); }
function nodeName() {
  var ua = navigator.userAgent, os = 'Device', br = 'Browser';
  if (ua.indexOf('Windows') >= 0) os = 'Windows';
  else if (ua.indexOf('Android') >= 0) os = 'Android';
  else if (ua.indexOf('iPhone') >= 0 || ua.indexOf('iPad') >= 0) os = 'iOS';
  else if (ua.indexOf('Mac OS') >= 0) os = 'Mac';
  else if (ua.indexOf('Linux') >= 0) os = 'Linux';
  if (ua.indexOf('Edg/') >= 0) br = 'Edge';
  else if (ua.indexOf('Firefox/') >= 0) br = 'Firefox';
  else if (ua.indexOf('Chrome/') >= 0) br = 'Chrome';
  else if (ua.indexOf('Safari/') >= 0) br = 'Safari';
  return os + ' · ' + br;
}
function headers(json) {
  var h = { 'Authorization': 'Bearer ' + S.token, 'X-Karen-Node': S.node };
  if (json) h['Content-Type'] = 'application/json';
  return h;
}
function toast(m) { var t = el('toast'); t.textContent = m; t.classList.add('show');
  setTimeout(function () { t.classList.remove('show'); }, 2400); }
function setStatus(ok, text) { el('dot').className = ok ? 'dot' : 'dot off'; el('statusText').textContent = text; }
function add(role, text) { var d = document.createElement('div'); d.className = 'msg ' + role;
  d.textContent = text; el('chat').appendChild(d); el('chat').scrollTop = el('chat').scrollHeight; return d; }
function saveToken() { S.token = el('token').value.trim();
  localStorage.setItem('karen_token', S.token);
  toast(S.token ? 'Token saved' : 'Token cleared'); loadModels(); }
function loadModels() {
  fetch('/v1/models', { headers: headers(false) }).then(function (r) {
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
    loadFiles();
  }).catch(function () { setStatus(false, 'unreachable'); });
}
function fmtSize(n) {
  if (n > 1073741824) return (n / 1073741824).toFixed(2) + ' GB';
  if (n > 1048576) return (n / 1048576).toFixed(1) + ' MB';
  return Math.max(1, Math.round(n / 1024)) + ' KB';
}
function loadFiles() {
  fetch('/v1/files', { headers: headers(false) }).then(function (r) {
    if (r.status === 401) { el('filesSummary').textContent = 'On-device model files — save the token to list'; return null; }
    if (!r.ok) { el('filesSummary').textContent = 'On-device model files — HTTP ' + r.status; return null; }
    return r.json();
  }).then(function (j) {
    if (!j) return;
    var fs = j.data || [];
    var box = el('fileList');
    box.innerHTML = '';
    el('filesSummary').textContent = 'On-device model files (' + fs.length + ') — GGUF runs via llama.cpp, ONNX via onnxruntime';
    if (!fs.length) {
      var d = document.createElement('div');
      d.className = 'fmeta';
      d.textContent = 'No .gguf / .onnx / .tflite files found in the app model folder or Download/Documents.';
      box.appendChild(d);
      return;
    }
    fs.forEach(function (f) {
      var row = document.createElement('div');
      row.className = 'frow';
      var nm = document.createElement('span');
      nm.className = 'fname';
      nm.textContent = f.name;
      row.appendChild(nm);
      var fb = document.createElement('span');
      fb.className = 'badge mid';
      fb.textContent = f.format;
      row.appendChild(fb);
      var sz = document.createElement('span');
      sz.className = 'fmeta';
      sz.textContent = fmtSize(f.size);
      row.appendChild(sz);
      var rb = document.createElement('span');
      rb.className = 'badge ' + (f.runnable ? 'ok' : (f.runtime === 'none' ? 'off' : 'mid'));
      rb.textContent = f.runtime;
      row.appendChild(rb);
      if (f.runnable) {
        var use = document.createElement('button');
        use.textContent = 'Use';
        use.onclick = function () { useModel(f.name); };
        row.appendChild(use);
      }
      var test = document.createElement('button');
      test.className = 'ghost';
      test.textContent = 'Test';
      test.onclick = function () { probe(f.path, row); };
      row.appendChild(test);
      box.appendChild(row);
    });
  }).catch(function () {
    el('filesSummary').textContent = 'On-device model files — unavailable';
  });
}
function useModel(name) {
  var sel = el('model'), found = false;
  for (var i = 0; i < sel.options.length; i++) {
    if (sel.options[i].value === name) { sel.selectedIndex = i; found = true; break; }
  }
  if (found) { toast('Selected ' + name); }
  else { toast('Refreshing models — then pick ' + name); loadModels(); }
}
function probe(path, row) {
  var done = function (text) {
    var d = document.createElement('div');
    d.className = 'probe';
    d.textContent = text;
    if (row.parentNode) { row.parentNode.insertBefore(d, row.nextSibling); }
  };
  fetch('/v1/probe', { method: 'POST', headers: headers(true), body: JSON.stringify({ path: path }) }).then(function (r) {
    return r.json();
  }).then(function (j) {
    var msg = j.report || (j.error && j.error.message) || 'no report';
    done((j.ok ? 'OK — ' : '') + msg);
  }).catch(function () { done('probe failed'); });
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
    headers: headers(true),
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
add('sys', 'Hello from Karen — connected to $ip:$port. ${models.size} installed model(s). Paste the token from Settings → Local Server, then pick a model — .gguf files found on disk can run too (expand the On-device model files panel below).');
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
