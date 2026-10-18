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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
    val requests: Int,
    val webgpu: Boolean = false,
    val gpu: String = "",
    val gflops: Double = 0.0,
    val worker: Boolean = false,
    val workerState: String = "",
    val workerModel: String = "",
    /** Joined worker's id (`w_xxxxxx`) while the node is an active worker. */
    val workerId: String = "",
    // Self-reported machine telemetry (browser pages — see the worker page JS).
    // Browsers have no temperature sensor: `pressure` is the Compute Pressure
    // API CPU state, the closest honest heat/load signal; `memGB` is the
    // browser's approximate device memory; `quotaGB` is the storage estimate
    // (~free disk); `netType`/`downlinkMbps`/`netRttMs` come from the Network
    // Information API; `jsScore` is a fixed-loop CPU speed measure; `maxBufferMB`
    // is the WebGPU adapter's max buffer size (a VRAM tier proxy).
    val cpuCores: Int = 0,
    val cpuArch: String = "",
    val memGB: Double = 0.0,
    val pressure: String = "",
    val quotaGB: Double = 0.0,
    val usageGB: Double = 0.0,
    val netType: String = "",
    val downlinkMbps: Double = 0.0,
    val netRttMs: Int = 0,
    val jsScore: Double = 0.0,
    val maxBufferMB: Int = 0,
    val telemetryAt: Long = 0L
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
 * One GPU worker: a dashboard page that joined worker mode and runs inference
 * tasks on its own GPU (WebLLM). See docs/distributed-ai-architecture.md.
 */
class WorkerInfo(
    val id: String,
    val name: String,
    val ip: String,
    val gpu: String,
    val gflops: Double,
    val backend: String,
    val engine: String,
    val defaultModel: String,
    val models: List<String>,
    val joinedAt: Long,
    @Volatile var lastSeenAt: Long,
    @Volatile var state: String,
    @Volatile var currentTask: String,
    @Volatile var loadedModel: String,
    @Volatile var tasksDone: Int,
    @Volatile var lastTps: Double,
    @Volatile var lastTtftMs: Long,
    // Machine telemetry the worker page reports (poll query + register body):
    // cores/arch/score = CPU, memGB = device RAM estimate, pressure = Compute
    // Pressure API state (browsers expose no °C), quota/usage = storage
    // estimate (≈disk), netType/downlinkMbps/netRttMs = connection, maxBufferMB
    // = WebGPU adapter max buffer (VRAM tier proxy).
    @Volatile var cpuCores: Int = 0,
    @Volatile var cpuArch: String = "",
    @Volatile var memGB: Double = 0.0,
    @Volatile var pressure: String = "",
    @Volatile var quotaGB: Double = 0.0,
    @Volatile var usageGB: Double = 0.0,
    @Volatile var netType: String = "",
    @Volatile var downlinkMbps: Double = 0.0,
    @Volatile var netRttMs: Int = 0,
    @Volatile var jsScore: Double = 0.0,
    @Volatile var maxBufferMB: Int = 0,
    @Volatile var telemetryAt: Long = 0L
) {
    fun online(now: Long): Boolean = now - lastSeenAt < ONLINE_WINDOW_MS

    companion object {
        /** No worker call for this long ⇒ shown as offline / skipped by "auto". */
        const val ONLINE_WINDOW_MS = 75_000L

        /** A running task with no worker activity for this long fails. */
        const val TASK_STALL_MS = 120_000L

        /**
         * How long one poll request is held before the worker reconnects.
         * Tasks are handed over the moment they are queued and keepalives
         * flow every 10 s, so a longer hold only cuts request churn.
         */
        const val POLL_HOLD_MS = 60_000L
    }
}

/** One inference task handed to a worker; events relay to the requesting console. */
class WorkerTask(
    val id: String,
    val workerId: String,
    val model: String,
    val messages: JSONArray,
    val temperature: Double,
    val maxTokens: Int,
    val createdAt: Long
) {
    val events = LinkedBlockingQueue<JSONObject>()

    @Volatile
    var state: String = "queued"

    @Volatile
    var cancelRequested: Boolean = false

    @Volatile
    var lastActivityAt: Long = System.currentTimeMillis()
}

/**
 * On-device OpenAI-compatible API (Thinai-style local server, no new deps).
 *
 * Plain ServerSocket HTTP/1.1 on localhost:11434 by default:
 *   GET  /                    browser worker page — join as a slave node (GPU)
 *   GET  /dashboard            browser dashboard — chat + on-device model files
 *   GET  /v1/models            installed weights + runnable .gguf files
 *   GET  /v1/files             every model file found on disk (fmt + runtime)
 *   POST /v1/chat/completions  runs the loaded local GGUF (stream:true → SSE);
 *                              "worker":"auto"|<id> routes it to a GPU worker
 *   POST /v1/probe             loads a file with its real runtime (ORT/GGUF)
 *   POST /v1/nodes/hello       nodes report WebGPU/GPU capability + GFLOPS
 *   GET  /v1/workers           GPU workers (dashboard pages in worker mode)
 *   POST /v1/worker/register|progress|stream|done|leave   worker lifecycle
 *   GET  /v1/worker/poll       worker SSE long-poll (X-Karen-Worker header)
 *   POST /v1/workers/remove    master-side disconnect (worker then gets 410)
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

    /** GPU workers: dashboard pages that joined worker mode (execution plane). */
    private val workers = ConcurrentHashMap<String, WorkerInfo>()
    private val workerQueues = ConcurrentHashMap<String, LinkedBlockingQueue<WorkerTask>>()
    private val tasks = ConcurrentHashMap<String, WorkerTask>()
    private val removedWorkers = ConcurrentHashMap.newKeySet<String>()
    private val removedOrder = LinkedBlockingQueue<String>()

    /** Clears node history (Network Nodes screen → "Clear"). */
    fun clearNodes() {
        nodes.clear()
        nodeSnapshot = emptyList()
    }

    /** Drops one device from history; it simply reappears if it connects again. */
    fun forgetNode(ip: String) {
        nodes.remove(ip)
        nodeSnapshot = nodes.values.sortedByDescending { it.lastSeen }
    }

    /**
     * Master-side disconnect from the Network Nodes screen — same effect as the
     * dashboard's Disconnect button: the worker gets 410 on its next call and
     * stops for good (it will not rejoin on its own). True if it was joined.
     */
    fun disconnectWorker(id: String): Boolean {
        val worker = workers.remove(id) ?: return false
        cancelWorkerTask(worker, "worker was disconnected by the master")
        failQueued(worker, "worker was disconnected by the master")
        workerQueues.remove(id)
        clearNodeWorker(worker.ip)
        removedWorkers.add(id)
        removedOrder.add(id)
        while (removedOrder.size > 64) {
            val old = removedOrder.poll()
            if (old != null) removedWorkers.remove(old)
        }
        return true
    }

    /**
     * Cancels the task a worker is currently on. The worker stops at its next
     * chunk (the stream response carries the cancel flag) and the console
     * watching that task sees "cancelled". False when nothing is running.
     */
    fun cancelTaskOnWorker(id: String): Boolean {
        val worker = workers[id] ?: return false
        val task = tasks[worker.currentTask] ?: return false
        if (task.state == "completed" || task.state == "failed" || task.state == "cancelled") return false
        cancelWorkerTask(worker, "cancelled from the phone")
        return true
    }

    private fun recordNode(remoteIp: String?, path: String, userAgent: String, headerName: String) {
        try {
            val ip = remoteIp ?: return
            if (ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1") return
            val now = System.currentTimeMillis()
            val prev = nodes[ip]
            val kind = when {
                path == "/dashboard" -> "Dashboard"
                path == "/" || path == "/index.html" || path == "/worker" || path == "/slave" -> "Worker"
                userAgent.contains("Mozilla/") -> "Dashboard"
                else -> "API"
            }
            if (headerName.isNotBlank()) nodeNames[ip] = headerName.take(40)
            val name = nodeNames[ip] ?: prev?.name ?: ("Device " + ip.substringAfterLast('.'))
            nodes[ip] = NodeInfo(
                ip = ip,
                name = name,
                kind = kind,
                firstSeen = prev?.firstSeen ?: now,
                lastSeen = now,
                requests = (prev?.requests ?: 0) + 1,
                webgpu = prev?.webgpu ?: false,
                gpu = prev?.gpu ?: "",
                gflops = prev?.gflops ?: 0.0,
                worker = prev?.worker ?: false,
                workerState = prev?.workerState ?: "",
                workerModel = prev?.workerModel ?: "",
                workerId = prev?.workerId ?: ""
            ).carryTelemetry(prev)
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

    // ---- GPU workers (distributed inference, request-level) ----------------
    //
    // A "worker" is a dashboard page on another device that loaded WebLLM and
    // long-polls for tasks. The phone never pushes to a browser: the worker
    // always starts the conversation (poll → task → progress/stream/done).
    // See docs/distributed-ai-architecture.md for the protocol contract.

    private fun peerIp(socket: Socket): String? = try {
        socket.inetAddress?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** True for connections from the phone itself (loopback or its own LAN IP). */
    private fun isMasterPeer(ctx: Context, ip: String?): Boolean {
        if (ip == null) return false
        if (ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1") return true
        return try {
            ip == lanIp(ctx)
        } catch (_: Exception) {
            false
        }
    }

    private fun newWorkerId(): String =
        "w_" + UUID.randomUUID().toString().replace("-", "").take(6)

    private fun workerQueueFor(id: String): LinkedBlockingQueue<WorkerTask> =
        workerQueues.getOrPut(id) { LinkedBlockingQueue() }

    /** Mirror worker state onto the node list so the app's Nodes screen shows it. */
    private fun touchNodeWorker(ip: String?, state: String, model: String, workerId: String = "") {
        val key = ip ?: return
        val prev = nodes[key] ?: return
        nodes[key] = prev.copy(
            worker = true,
            workerState = state,
            workerModel = model.take(80),
            workerId = workerId
        )
        nodeSnapshot = nodes.values.sortedByDescending { it.lastSeen }
    }

    private fun clearNodeWorker(ip: String?) {
        val key = ip ?: return
        val prev = nodes[key] ?: return
        if (!prev.worker) return
        nodes[key] = prev.copy(worker = false, workerState = "", workerModel = "", workerId = "")
        nodeSnapshot = nodes.values.sortedByDescending { it.lastSeen }
    }

    // ---- Node telemetry ------------------------------------------------------
    //
    // Browser pages measure what the platform actually exposes and attach it to
    // requests they were going to make anyway (worker poll query string, nodes
    // hello body, console workers refresh) — no extra traffic. Browsers cannot
    // read temperatures; every "heat" figure here is either the CPU pressure
    // state (browser) or the phone's real battery/thermal sensors (app).

    /** One `name=value` entry of a URL query string (percent-decoded). */
    private fun queryParam(query: String, name: String): String? {
        if (query.isBlank()) return null
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0 || part.substring(0, eq) != name) continue
            return try {
                java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
            } catch (_: Exception) {
                part.substring(eq + 1)
            }
        }
        return null
    }

    /** Telemetry blob a browser page attaches to its request (`?tel=<json>`). */
    private fun queryTelemetry(query: String): JSONObject? = try {
        queryParam(query, "tel")?.let { JSONObject(it) }
    } catch (_: Exception) {
        null
    }

    /** Carry self-reported telemetry over a node-record rewrite (see recordNode). */
    private fun NodeInfo.carryTelemetry(prev: NodeInfo?): NodeInfo = if (prev == null) {
        this
    } else {
        copy(
            cpuCores = prev.cpuCores,
            cpuArch = prev.cpuArch,
            memGB = prev.memGB,
            pressure = prev.pressure,
            quotaGB = prev.quotaGB,
            usageGB = prev.usageGB,
            netType = prev.netType,
            downlinkMbps = prev.downlinkMbps,
            netRttMs = prev.netRttMs,
            jsScore = prev.jsScore,
            maxBufferMB = prev.maxBufferMB,
            telemetryAt = prev.telemetryAt
        )
    }

    /** Store self-reported telemetry on the node list (the app's Nodes screen). */
    private fun applyNodeTelemetry(ip: String?, tel: JSONObject?) {
        if (ip == null || tel == null) return
        val prev = nodes[ip] ?: return
        nodes[ip] = prev.copy(
            cpuCores = tel.optInt("c", prev.cpuCores),
            cpuArch = tel.optString("a", prev.cpuArch).take(28),
            memGB = tel.optDouble("m", prev.memGB),
            pressure = tel.optString("p", prev.pressure).take(12),
            quotaGB = tel.optDouble("sq", prev.quotaGB),
            usageGB = tel.optDouble("su", prev.usageGB),
            netType = tel.optString("nt", prev.netType).take(12),
            downlinkMbps = tel.optDouble("nd", prev.downlinkMbps),
            netRttMs = tel.optInt("nr", prev.netRttMs),
            jsScore = tel.optDouble("js", prev.jsScore),
            maxBufferMB = tel.optInt("ab", prev.maxBufferMB),
            telemetryAt = System.currentTimeMillis()
        )
        nodeSnapshot = nodes.values.sortedByDescending { it.lastSeen }
    }

    /** Same telemetry on the worker record the console's node list reads. */
    private fun applyWorkerTelemetry(w: WorkerInfo, tel: JSONObject?) {
        if (tel == null) return
        w.cpuCores = tel.optInt("c", w.cpuCores)
        w.cpuArch = tel.optString("a", w.cpuArch).take(28)
        w.memGB = tel.optDouble("m", w.memGB)
        w.pressure = tel.optString("p", w.pressure).take(12)
        w.quotaGB = tel.optDouble("sq", w.quotaGB)
        w.usageGB = tel.optDouble("su", w.usageGB)
        w.netType = tel.optString("nt", w.netType).take(12)
        w.downlinkMbps = tel.optDouble("nd", w.downlinkMbps)
        w.netRttMs = tel.optInt("nr", w.netRttMs)
        w.jsScore = tel.optDouble("js", w.jsScore)
        w.maxBufferMB = tel.optInt("ab", w.maxBufferMB)
        w.telemetryAt = System.currentTimeMillis()
    }

    private fun workersListJson(): JSONObject {
        val now = System.currentTimeMillis()
        val data = JSONArray()
        workers.values
            .sortedWith(compareByDescending<WorkerInfo> { it.online(now) }.thenByDescending { it.gflops })
            .forEach { w ->
                val models = JSONArray()
                w.models.forEach { models.put(it) }
                data.put(
                    JSONObject()
                        .put("id", w.id)
                        .put("name", w.name)
                        .put("ip", w.ip)
                        .put("gpu", w.gpu)
                        .put("gflops", w.gflops)
                        .put("backend", w.backend)
                        .put("engine", w.engine)
                        .put("state", if (w.online(now)) w.state else "offline")
                        .put("loadedModel", w.loadedModel)
                        .put("defaultModel", w.defaultModel)
                        .put("modelCount", w.models.size)
                        .put("models", models)
                        .put("lastSeenAt", w.lastSeenAt)
                        .put("tasksDone", w.tasksDone)
                        .put("lastTps", w.lastTps)
                        .put("lastTtftMs", w.lastTtftMs)
                        .put("cpuCores", w.cpuCores)
                        .put("cpuArch", w.cpuArch)
                        .put("memGB", w.memGB)
                        .put("pressure", w.pressure)
                        .put("quotaGB", w.quotaGB)
                        .put("usageGB", w.usageGB)
                        .put("netType", w.netType)
                        .put("downlinkMbps", w.downlinkMbps)
                        .put("netRttMs", w.netRttMs)
                        .put("jsScore", w.jsScore)
                        .put("maxBufferMB", w.maxBufferMB)
                        .put("telemetryAt", w.telemetryAt)
                )
            }
        return JSONObject().put("object", "list").put("data", data)
    }

    /** "auto" picks the best online worker; otherwise the id must match exactly. */
    private fun resolveWorker(sel: String, model: String): WorkerInfo? {
        val now = System.currentTimeMillis()
        if (sel == "auto") {
            return workers.values
                .filter { w ->
                    w.online(now) && (
                        model.isBlank() ||
                            w.models.isEmpty() ||
                            w.models.any { it.equals(model, ignoreCase = true) } ||
                            w.loadedModel.equals(model, ignoreCase = true)
                        )
                }
                .maxWithOrNull(
                    compareBy(
                        { if (it.state == "idle") 1 else 0 },
                        { if (it.loadedModel.equals(model, ignoreCase = true)) 1 else 0 },
                        { it.gflops },
                        { -it.tasksDone }
                    )
                )
        }
        return workers[sel]
    }

    /** Worker announces itself (and its model list) or refreshes capabilities. */
    private fun registerWorker(ctx: Context, socket: Socket, body: JSONObject) {
        val ip = peerIp(socket)
        // The phone is the master: it coordinates and never works as its own
        // slave node. Loopback and the phone's own LAN address both count.
        if (isMasterPeer(ctx, ip)) {
            writeJson(
                socket, 200,
                JSONObject().put("ok", true).put("master", true)
                    .put("note", "this is the master device — other devices work as slave nodes")
            )
            return
        }
        val proposed = body.optString("workerId", "").trim()
        val id = if (proposed.startsWith("w_") && !removedWorkers.contains(proposed)) {
            proposed
        } else {
            newWorkerId()
        }
        val now = System.currentTimeMillis()
        val prev = workers[id]
        val name = body.optString("name", "").trim().ifBlank { prev?.name ?: ("Worker " + id.takeLast(4)) }
        val models = body.optJSONArray("models")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").trim().takeIf { it.isNotBlank() }
            }
        } ?: prev?.models ?: emptyList()
        val busyTask = prev?.currentTask?.takeIf { it.isNotBlank() && tasks.containsKey(it) }
        val worker = WorkerInfo(
            id = id,
            name = name.take(40),
            ip = ip ?: "?",
            gpu = body.optString("gpu", prev?.gpu ?: "").trim().take(90),
            gflops = body.optDouble("gflops", prev?.gflops ?: 0.0),
            backend = body.optString("backend", prev?.backend ?: "webllm").take(24),
            engine = body.optString("engine", prev?.engine ?: "").take(48),
            defaultModel = body.optString("defaultModel", prev?.defaultModel ?: "").trim().take(90),
            models = models.take(400),
            joinedAt = prev?.joinedAt ?: now,
            lastSeenAt = now,
            state = if (busyTask != null) (prev?.state ?: "assigned") else "idle",
            currentTask = busyTask ?: "",
            loadedModel = body.optString("loadedModel", prev?.loadedModel ?: "").trim().take(90),
            tasksDone = prev?.tasksDone ?: 0,
            lastTps = prev?.lastTps ?: 0.0,
            lastTtftMs = prev?.lastTtftMs ?: 0L,
            cpuCores = prev?.cpuCores ?: 0,
            cpuArch = prev?.cpuArch ?: "",
            memGB = prev?.memGB ?: 0.0,
            pressure = prev?.pressure ?: "",
            quotaGB = prev?.quotaGB ?: 0.0,
            usageGB = prev?.usageGB ?: 0.0,
            netType = prev?.netType ?: "",
            downlinkMbps = prev?.downlinkMbps ?: 0.0,
            netRttMs = prev?.netRttMs ?: 0,
            jsScore = prev?.jsScore ?: 0.0,
            maxBufferMB = prev?.maxBufferMB ?: 0,
            telemetryAt = prev?.telemetryAt ?: 0L
        )
        val tel = body.optJSONObject("telemetry")
        applyWorkerTelemetry(worker, tel)
        applyNodeTelemetry(ip, tel)
        workers[id] = worker
        workerQueueFor(id)
        removedWorkers.remove(id)
        touchNodeWorker(ip, worker.state, worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
        writeJson(
            socket, 200,
            JSONObject().put("ok", true)
                .put("workerId", id)
                .put("pollSeconds", (WorkerInfo.POLL_HOLD_MS / 1000).toInt())
                .put("taskTimeoutSeconds", (WorkerInfo.TASK_STALL_MS / 1000).toInt())
        )
    }

    /**
     * SSE long-poll: hands the worker one queued task, otherwise keepalives.
     * The hold is bounded; the worker reconnects immediately after.
     */
    private fun workerPoll(socket: Socket, workerId: String, tel: JSONObject? = null) {
        val worker = workers[workerId]
        if (worker == null) {
            val gone = removedWorkers.contains(workerId)
            writeJson(
                socket, if (gone) 410 else 404,
                JSONObject().put(
                    "error",
                    errorObj(
                        if (gone) "worker removed by the master — stop polling"
                        else "unknown worker — register again"
                    )
                )
            )
            return
        }
        // Fresh machine telemetry rides along with the poll the worker makes
        // anyway — zero extra requests for cpu/ram/disk/network updates.
        if (tel != null) {
            applyWorkerTelemetry(worker, tel)
            applyNodeTelemetry(worker.ip, tel)
        }
        worker.lastSeenAt = System.currentTimeMillis()
        val current = worker.currentTask
        if (current.isBlank() || tasks[current] == null) {
            worker.currentTask = ""
            worker.state = "idle"
            touchNodeWorker(worker.ip, "idle", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
        }
        val out = socket.getOutputStream()
        try {
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            out.flush()
            val queue = workerQueueFor(workerId)
            val deadline = System.currentTimeMillis() + WorkerInfo.POLL_HOLD_MS
            while (System.currentTimeMillis() < deadline) {
                val task = queue.poll(10, TimeUnit.SECONDS)
                if (task != null) {
                    if (!tasks.containsKey(task.id) || task.cancelRequested) continue
                    val at = System.currentTimeMillis()
                    worker.lastSeenAt = at
                    worker.state = "assigned"
                    worker.currentTask = task.id
                    task.state = "assigned"
                    task.lastActivityAt = at
                    touchNodeWorker(worker.ip, "assigned", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
                    task.events.add(
                        JSONObject().put("type", "status").put("phase", "assigned")
                            .put("text", "worker picked up the task").put("model", task.model)
                    )
                    out.write(
                        (
                            "event: task\ndata: " +
                                JSONObject()
                                    .put("id", task.id)
                                    .put("model", task.model)
                                    .put("messages", task.messages)
                                    .put("temperature", task.temperature)
                                    .put("max_tokens", task.maxTokens) +
                                "\n\n"
                            ).toByteArray(Charsets.UTF_8)
                    )
                    out.flush()
                    return
                }
                worker.lastSeenAt = System.currentTimeMillis()
                out.write(": ping\n\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
            out.write("event: idle\ndata: {}\n\n".toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (_: Exception) {
            // Worker disconnected mid-hold — staleness rules cover it.
        }
    }

    /** Worker reports load/generation progress; the console gets a status event. */
    private fun workerProgress(socket: Socket, body: JSONObject) {
        val worker = workers[body.optString("workerId", "")]
        if (worker == null) {
            writeJson(socket, 410, JSONObject().put("error", errorObj("unknown or removed worker — register again")))
            return
        }
        val now = System.currentTimeMillis()
        worker.lastSeenAt = now
        val loaded = body.optString("loadedModel", "").trim()
        if (loaded.isNotBlank()) worker.loadedModel = loaded.take(90)
        val task = tasks[body.optString("taskId", "")]
        if (task != null) {
            task.lastActivityAt = now
            val phase = body.optString("phase", "")
            if (phase == "loading") task.state = "loading"
            if (phase == "running") task.state = "running"
            if (phase == "loading" || phase == "running") {
                worker.state = phase
                val evt = JSONObject().put("type", "status").put("phase", phase)
                    .put("text", body.optString("text", "")).put("model", worker.loadedModel)
                val progress = body.optDouble("progress", -1.0)
                if (progress >= 0.0) evt.put("progress", progress)
                task.events.add(evt)
            }
            touchNodeWorker(worker.ip, worker.state, worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
        }
        writeJson(
            socket, 200,
            JSONObject().put("ok", task != null).put("cancel", task?.cancelRequested ?: true)
        )
    }

    /** One streamed chunk of generated text; the response carries the cancel flag. */
    private fun workerStream(socket: Socket, body: JSONObject) {
        val worker = workers[body.optString("workerId", "")]
        if (worker == null) {
            writeJson(socket, 410, JSONObject().put("error", errorObj("unknown or removed worker — register again")))
            return
        }
        val now = System.currentTimeMillis()
        worker.lastSeenAt = now
        val task = tasks[body.optString("taskId", "")]
        if (task == null) {
            writeJson(socket, 200, JSONObject().put("ok", false).put("cancel", true).put("stale", true))
            return
        }
        task.lastActivityAt = now
        if (task.state == "assigned" || task.state == "loading") task.state = "running"
        worker.state = "running"
        val delta = body.optString("delta", "")
        if (delta.isNotEmpty()) task.events.add(JSONObject().put("type", "delta").put("content", delta))
        writeJson(socket, 200, JSONObject().put("ok", true).put("cancel", task.cancelRequested))
    }

    /** Task terminal report: stats land on the worker, the console gets "done". */
    private fun workerDone(socket: Socket, body: JSONObject) {
        val worker = workers[body.optString("workerId", "")]
        if (worker == null) {
            writeJson(socket, 410, JSONObject().put("error", errorObj("unknown or removed worker — register again")))
            return
        }
        val now = System.currentTimeMillis()
        worker.lastSeenAt = now
        val task = tasks[body.optString("taskId", "")]
        if (task == null) {
            writeJson(socket, 200, JSONObject().put("ok", false).put("stale", true))
            return
        }
        task.lastActivityAt = now
        val status = body.optString("status", "completed")
        task.state = when (status) {
            "failed" -> "failed"
            "cancelled" -> "cancelled"
            else -> "completed"
        }
        val stats = body.optJSONObject("stats")
        if (task.state == "completed") {
            worker.tasksDone += 1
            val tps = stats?.optDouble("tps", 0.0) ?: 0.0
            val ttft = stats?.optLong("ttftMs", 0L) ?: 0L
            if (tps > 0.0) worker.lastTps = tps
            if (ttft > 0L) worker.lastTtftMs = ttft
            worker.loadedModel = task.model
        }
        worker.state = "idle"
        worker.currentTask = ""
        touchNodeWorker(worker.ip, "idle", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
        task.events.add(
            JSONObject().put("type", "done")
                .put("status", task.state)
                .put("error", body.optString("error", ""))
                .put("stats", stats ?: JSONObject())
        )
        writeJson(socket, 200, JSONObject().put("ok", true))
    }

    private fun workerLeave(socket: Socket, body: JSONObject) {
        val id = body.optString("workerId", "")
        val worker = workers.remove(id)
        if (worker != null) {
            cancelWorkerTask(worker, "worker left")
            failQueued(worker, "worker left")
            workerQueues.remove(id)
            clearNodeWorker(worker.ip)
        }
        writeJson(socket, 200, JSONObject().put("ok", true))
    }

    /** Master-side disconnect: the worker gets 410 afterwards and stops for good. */
    private fun workerRemove(socket: Socket, body: JSONObject) {
        val id = body.optString("id", body.optString("workerId", ""))
        disconnectWorker(id)
        writeJson(socket, 200, JSONObject().put("ok", true))
    }

    private fun cancelWorkerTask(worker: WorkerInfo, reason: String) {
        val task = tasks[worker.currentTask] ?: return
        task.state = "cancelled"
        task.cancelRequested = true
        task.events.add(
            JSONObject().put("type", "done").put("status", "cancelled")
                .put("error", reason).put("stats", JSONObject())
        )
    }

    private fun failQueued(worker: WorkerInfo, reason: String) {
        val queue = workerQueues[worker.id] ?: return
        while (true) {
            val task = queue.poll() ?: break
            if (tasks.containsKey(task.id)) {
                task.state = "cancelled"
                task.events.add(
                    JSONObject().put("type", "done").put("status", "cancelled")
                        .put("error", reason).put("stats", JSONObject())
                )
            }
        }
    }

    /** Worker-routed streaming chat: queue the task, relay SSE to the console. */
    private fun workerChatSse(socket: Socket, body: JSONObject) {
        val sel = body.optString("worker", "").trim()
        val wanted = body.optString("model", "").trim()
        val messages = body.optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) {
            writeJson(socket, 400, JSONObject().put("error", errorObj("messages[] required")))
            return
        }
        val worker = resolveWorker(sel, wanted)
        if (worker == null) {
            writeJson(
                socket, 503,
                JSONObject().put(
                    "error",
                    errorObj("no worker is online — open the dashboard on a laptop/desktop with WebGPU; it joins automatically as a slave node once the token is saved")
                )
            )
            return
        }
        if (wanted.isNotBlank() && worker.models.isNotEmpty() &&
            worker.models.none { it.equals(wanted, ignoreCase = true) } &&
            !worker.loadedModel.equals(wanted, ignoreCase = true)
        ) {
            writeJson(
                socket, 400,
                JSONObject().put("error", errorObj("worker '${worker.name}' does not offer model '$wanted'"))
            )
            return
        }
        val task = WorkerTask(
            id = "t_" + UUID.randomUUID().toString().replace("-", "").take(6),
            workerId = worker.id,
            model = if (wanted.isNotBlank()) wanted else worker.defaultModel,
            messages = messages,
            temperature = body.optDouble("temperature", 0.7),
            maxTokens = body.optInt("max_tokens", 512).coerceIn(1, 4096),
            createdAt = System.currentTimeMillis()
        )
        tasks[task.id] = task
        workerQueueFor(worker.id).add(task)
        worker.state = "assigned"
        worker.currentTask = task.id
        touchNodeWorker(worker.ip, "assigned", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
        val out = socket.getOutputStream()
        var gone = false
        val emitRaw = { s: String ->
            if (!gone) {
                try {
                    out.write(s.toByteArray(Charsets.UTF_8))
                    out.flush()
                } catch (_: Exception) {
                    gone = true
                    task.cancelRequested = true
                }
            }
        }
        try {
            out.write(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Cache-Control: no-cache\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            out.flush()
            emitRaw(
                "data: " + JSONObject().put("id", task.id).put("object", "worker.status")
                    .put("phase", "queued").put("text", "waiting for " + worker.name) + "\n\n"
            )
            relayTask(task, emitRaw)
        } catch (_: Exception) {
        } finally {
            tasks.remove(task.id)
            if (worker.currentTask == task.id) {
                worker.state = "idle"
                worker.currentTask = ""
                touchNodeWorker(worker.ip, "idle", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Consumes a task's events: relays status/chunk/done to the caller's stream. */
    private fun relayTask(task: WorkerTask, emitRaw: (String) -> Unit) {
        var lastActivity = System.currentTimeMillis()
        while (true) {
            val evt = task.events.poll(1, TimeUnit.SECONDS)
            if (evt != null) {
                lastActivity = System.currentTimeMillis()
                when (evt.optString("type")) {
                    "status" -> {
                        val j = JSONObject().put("id", task.id).put("object", "worker.status")
                            .put("phase", evt.optString("phase"))
                            .put("text", evt.optString("text"))
                            .put("model", evt.optString("model"))
                        val progress = evt.optDouble("progress", -1.0)
                        if (progress >= 0.0) j.put("progress", progress)
                        emitRaw("data: $j\n\n")
                    }
                    "delta" -> {
                        val chunk = JSONObject().put("id", task.id)
                            .put("object", "chat.completion.chunk")
                            .put("model", task.model)
                            .put(
                                "choices",
                                JSONArray().put(
                                    JSONObject().put("index", 0)
                                        .put("delta", JSONObject().put("content", evt.optString("content")))
                                )
                            )
                        emitRaw("data: $chunk\n\n")
                    }
                    "done" -> {
                        emitRaw(
                            "data: " + JSONObject().put("id", task.id).put("object", "worker.done")
                                .put("state", evt.optString("status"))
                                .put("stats", evt.optJSONObject("stats") ?: JSONObject()) + "\n\n"
                        )
                        if (evt.optString("status") == "failed") {
                            emitRaw(
                                "data: " + JSONObject().put("id", task.id).put("object", "worker.error")
                                    .put("error", errorObj(evt.optString("error").ifBlank { "worker reported failure" })) + "\n\n"
                            )
                        }
                        emitRaw("data: [DONE]\n\n")
                        return
                    }
                }
            } else if (System.currentTimeMillis() - lastActivity > WorkerInfo.TASK_STALL_MS) {
                task.state = "failed"
                task.cancelRequested = true
                emitRaw(
                    "data: " + JSONObject().put("id", task.id).put("object", "worker.error")
                        .put("error", errorObj("the worker went quiet (tab closed?) — task aborted")) + "\n\n"
                )
                emitRaw("data: [DONE]\n\n")
                return
            }
        }
    }

    /** Worker-routed non-streaming chat: waits for the task, returns one JSON reply. */
    private fun workerChatJson(socket: Socket, body: JSONObject) {
        val sel = body.optString("worker", "").trim()
        val wanted = body.optString("model", "").trim()
        val messages = body.optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) {
            writeJson(socket, 400, JSONObject().put("error", errorObj("messages[] required")))
            return
        }
        val worker = resolveWorker(sel, wanted)
        if (worker == null) {
            writeJson(socket, 503, JSONObject().put("error", errorObj("no GPU worker is online")))
            return
        }
        val task = WorkerTask(
            id = "t_" + UUID.randomUUID().toString().replace("-", "").take(6),
            workerId = worker.id,
            model = if (wanted.isNotBlank()) wanted else worker.defaultModel,
            messages = messages,
            temperature = body.optDouble("temperature", 0.7),
            maxTokens = body.optInt("max_tokens", 512).coerceIn(1, 4096),
            createdAt = System.currentTimeMillis()
        )
        tasks[task.id] = task
        workerQueueFor(worker.id).add(task)
        worker.state = "assigned"
        worker.currentTask = task.id
        val text = StringBuilder()
        var stats = JSONObject()
        var failure: String? = null
        var lastActivity = System.currentTimeMillis()
        try {
            while (true) {
                val evt = task.events.poll(1, TimeUnit.SECONDS)
                if (evt == null) {
                    if (System.currentTimeMillis() - lastActivity > WorkerInfo.TASK_STALL_MS) {
                        failure = "the worker went quiet (tab closed?) — task aborted"
                        break
                    }
                    continue
                }
                lastActivity = System.currentTimeMillis()
                when (evt.optString("type")) {
                    "delta" -> text.append(evt.optString("content"))
                    "done" -> {
                        stats = evt.optJSONObject("stats") ?: JSONObject()
                        if (evt.optString("status") == "failed") {
                            failure = evt.optString("error").ifBlank { "worker reported failure" }
                        }
                        break
                    }
                }
            }
        } finally {
            tasks.remove(task.id)
            if (worker.currentTask == task.id) {
                worker.state = "idle"
                worker.currentTask = ""
                touchNodeWorker(worker.ip, "idle", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
            }
        }
        if (failure != null) {
            writeJson(socket, 502, JSONObject().put("error", errorObj(failure)))
            return
        }
        writeJson(
            socket, 200,
            JSONObject()
                .put("id", task.id)
                .put("object", "chat.completion")
                .put("created", System.currentTimeMillis() / 1000)
                .put("model", task.model)
                .put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put("index", 0)
                            .put("message", JSONObject().put("role", "assistant").put("content", text.toString()))
                            .put("finish_reason", "stop")
                    )
                )
                .put("karen", JSONObject().put("worker", worker.id).put("stats", stats))
        )
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
            // Query strings carry node telemetry on requests that happen anyway
            // (worker poll, console refresh) — see queryTelemetry().
            val query = parts[1].substringAfter('?', "")
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

            // Worker page (root) + dashboard — both are token-free shells;
            // model/API calls made from them need the token. `/` is the page a
            // device just opens to start working; `/dashboard` controls things.
            if (method == "GET" && (path == "/" || path == "/index.html")) {
                writeHtml(socket, dashboardPage(ctx, worker = true))
                socket.close()
                return
            }
            if (method == "GET" && (path == "/dashboard")) {
                writeHtml(socket, dashboardPage(ctx))
                socket.close()
                return
            }
            if (method == "GET" && (path == "/worker" || path == "/slave")) {
                writeHtml(socket, dashboardPage(ctx, worker = true))
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
                method == "POST" && path == "/v1/nodes/hello" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        registerNodeCapabilities(ctx, socket, body)
                    }
                }
                method == "GET" && path == "/v1/workers" -> {
                    // The console refresh also carries its own machine telemetry
                    // in the query — the device you run the dashboard on shows up
                    // in the app's node list with live cpu/ram/disk/network too.
                    applyNodeTelemetry(peerIp(socket), queryTelemetry(query))
                    writeJson(socket, 200, workersListJson())
                }
                method == "POST" && path == "/v1/worker/register" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        registerWorker(ctx, socket, body)
                    }
                }
                method == "GET" && path == "/v1/worker/poll" -> {
                    // SSE long-poll; the worker id travels in a header (GET has
                    // no body), fresh machine telemetry in the query string.
                    workerPoll(
                        socket,
                        headers["x-karen-worker"].orEmpty().trim(),
                        queryTelemetry(query)
                    )
                }
                method == "POST" && path == "/v1/worker/progress" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        workerProgress(socket, body)
                    }
                }
                method == "POST" && path == "/v1/worker/stream" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        workerStream(socket, body)
                    }
                }
                method == "POST" && path == "/v1/worker/done" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        workerDone(socket, body)
                    }
                }
                method == "POST" && path == "/v1/worker/leave" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        workerLeave(socket, body)
                    }
                }
                method == "POST" && path == "/v1/workers/remove" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        workerRemove(socket, body)
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
                    } else if (body.optString("worker", "").isNotBlank()) {
                        // Request-level distribution: a GPU worker runs this one.
                        if (body.optBoolean("stream", false)) {
                            workerChatSse(socket, body)
                        } else {
                            workerChatJson(socket, body)
                        }
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

    /** Dashboard reports a node's hardware: WebGPU presence, adapter, GFLOPS. */
    private fun registerNodeCapabilities(ctx: Context, socket: Socket, body: JSONObject) {
        val ip = peerIp(socket)
        if (ip == null || isMasterPeer(ctx, ip)) {
            writeJson(
                socket, 200,
                JSONObject().put("ok", true).put("master", true)
                    .put("note", "this is the master device — other devices work as slave nodes")
            )
            return
        }
        val now = System.currentTimeMillis()
        val prev = nodes[ip]
        val name = body.optString("name", "").trim()
        if (name.isNotBlank()) nodeNames[ip] = name.take(40)
        nodes[ip] = NodeInfo(
            ip = ip,
            name = nodeNames[ip] ?: prev?.name ?: ("Device " + ip.substringAfterLast('.')),
            kind = prev?.kind ?: "Worker",
            firstSeen = prev?.firstSeen ?: now,
            lastSeen = now,
            requests = prev?.requests ?: 0,
            webgpu = body.optBoolean("webgpu", prev?.webgpu ?: false),
            gpu = body.optString("gpu", prev?.gpu ?: "").take(90),
            gflops = body.optDouble("gflops", prev?.gflops ?: 0.0),
            worker = prev?.worker ?: false,
            workerState = prev?.workerState ?: "",
            workerModel = prev?.workerModel ?: "",
            workerId = prev?.workerId ?: ""
        ).carryTelemetry(prev)
        applyNodeTelemetry(ip, body.optJSONObject("telemetry"))
        nodeSnapshot = nodes.values.sortedByDescending { it.lastSeen }
        writeJson(socket, 200, JSONObject().put("ok", true))
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
            410 -> "Gone"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            501 -> "Not Implemented"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: application/json\r\n" +
            "Cache-Control: no-store\r\n" +
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
  <p>Only model/API calls need the bearer token. The pages do not:</p>
  <p>Dashboard (control) → <code>/dashboard</code></p>
  <p>Worker page (join as a slave node) → <code>/</code></p>
  <p>API calls → send <code>Authorization: Bearer &lt;token&gt;</code>.
     Find the token in Karen → Settings → Local Server → Bearer token.</p>
</div>
</body>
</html>
""".trimIndent()

    /** Browser dashboard — chat with the on-device model, no app install. */
    private fun dashboardPage(ctx: Context, worker: Boolean = false): String {
        val ip = lanIp(ctx)
        val port = UserPrefs.serverPort(ctx)
        val mode = if (worker) "worker" else "console"
        val workerUrl = "http://" + ip.ifBlank { "127.0.0.1" } + ":" + port
        val models = UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${if (worker) "Karen — Slave node" else "Karen — Dashboard"}</title>
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
  input[type=checkbox] { width: 15px; height: 15px; padding: 0; accent-color: #10A37F; }
  .meta { color: #64748b; font-size: 10.5px; font-family: 'JetBrains Mono', monospace; margin: -4px 0 6px 6px; }
  .toast { position: fixed; bottom: 96px; left: 50%; transform: translateX(-50%);
           background: #1b2029; border: 1px solid #38bdf8; color: #cce5ff;
           padding: 9px 16px; border-radius: 20px; font-size: 12.5px; opacity: 0;
           transition: opacity .25s; pointer-events: none; white-space: nowrap; }
  .toast.show { opacity: 1; }
  /* Split pages: "/" is the slave-node worker page, "/dashboard" is the console. */
  body.console .workerOnly { display: none !important; }
  body.worker .consoleOnly { display: none !important; }
</style>
</head>
<body class="$mode">
<header>
  <div class="logo">K</div>
  <div>
    <h1>${if (worker) "Karen Slave Node" else "Karen Dashboard"}</h1>
    <div class="sub">${if (worker) "slave node working page — prompts from the phone run on this device's GPU" else "the model runs on the phone — or on another device working as a slave node"}</div>
  </div>
  <div class="status"><span class="dot" id="dot"></span><span id="statusText">connecting…</span></div>
</header>
<div class="bar">
  <input id="token" type="password" placeholder="Bearer token — Karen → Settings → Local Server" style="flex:1;min-width:230px">
  <button class="ghost" onclick="saveToken()">Save</button>
  <select id="model" class="consoleOnly" style="min-width:180px"><option value="">(pick model)</option></select>
  <button class="ghost consoleOnly" onclick="loadModels()">Refresh</button>
  <select id="runner" class="consoleOnly" style="min-width:200px" onchange="runnerChanged()" title="Where the prompt runs — the node with the most GPU is listed first">
    <option value="">Runner: Phone (llama.cpp)</option>
    <option value="auto">Runner: Auto — fastest GPU online</option>
  </select>
  <select id="wmodel" class="consoleOnly" style="min-width:220px;display:none" onchange="runnerChanged()" title="Model on the selected slave node"></select>
</div>
<details class="files consoleOnly" id="filesPanel">
  <summary id="filesSummary">On-device model files — loading…</summary>
  <div class="filelist" id="fileList"></div>
</details>
<details class="files" id="gpuPanel">
  <summary id="gpuSummary">GPU offload — checking this device…</summary>
  <div class="filelist">
    <div class="fmeta" id="gpuAdapter"></div>
    <div class="frow">
      <button class="ghost" id="benchBtn" onclick="runBench()">Run GPU benchmark</button>
      <span class="fmeta" id="benchResult"></span>
    </div>
    <div class="frow consoleOnly">
      <label class="fmeta" style="display:flex;align-items:center;gap:8px;flex:1;min-width:260px">
        <input type="checkbox" id="gpuBoost" onclick="toggleBoost(this.checked)">
        <span>Run chat on <b>this device's GPU</b> (WebLLM — first reply downloads the engine + model once, then it is cached)</span>
      </label>
      <select id="boostModel" style="max-width:330px" onchange="saveBoostModel()"></select>
    </div>
    <div class="fmeta consoleOnly" id="boostStatus">Chat runs on the phone (llama.cpp) over the LAN.</div>
    <div class="frow workerOnly" style="border-top:1px solid #283042;padding-top:10px;margin-top:6px">
      <label class="fmeta" style="display:flex;align-items:center;gap:8px;flex:1;min-width:260px">
        <input type="checkbox" id="gpuWorker" onclick="toggleWorker(this.checked)">
        <span>Work as a <b>slave node</b> — this device runs prompts sent by the phone (keep this tab open; it joins automatically once the token is saved)</span>
      </label>
      <select id="workerModel" style="max-width:330px" onchange="saveWorkerModel()"></select>
    </div>
    <div class="fmeta workerOnly" id="workerStatus">Slave node — save the token above to pair this device.</div>
    <div class="fmeta consoleOnly" id="slavePageHint">To make a device work as a slave node, open this address there: <a href="/" style="color:#38bdf8">$workerUrl</a></div>
    <div class="fmeta" id="secureHint" style="display:none;color:#f59e0b"></div>
  </div>
</details>
<details class="files consoleOnly" id="workersPanel">
  <summary id="workersSummary">Slave nodes — loading…</summary>
  <div class="filelist" id="workerList"></div>
</details>
<main id="chat" class="consoleOnly"></main>
<div class="hint consoleOnly">Enter sends · Shift+Enter makes a new line · Stop cancels generation</div>
<footer class="consoleOnly">
  <textarea id="input" placeholder="Ask the on-device model…"></textarea>
  <button id="send" onclick="send()">Send</button>
  <button id="stop" class="ghost" onclick="stopGen()" disabled>Stop</button>
</footer>
<div class="toast" id="toast"></div>
<script>
var PAGE = '$mode';
var S = { token: localStorage.getItem('karen_token') || '', busy: false, ctrl: null, log: [], node: nodeName(), boost: localStorage.getItem('karen_gpu_boost') === '1', llm: null, llmMod: null, llmModel: '', adapter: null, gpuName: '', gflops: 0,
  w: { on: localStorage.getItem('karen_worker') !== '0' && !!localStorage.getItem('karen_token'), saved: localStorage.getItem('karen_worker') === '1', master: false, id: localStorage.getItem('karen_worker_id') || '', model: localStorage.getItem('karen_worker_model') || '', loaded: '', engine: null, interrupted: false, loop: false },
  wModels: [], wlist: [], routed: localStorage.getItem('karen_runner') || '',
  tel: {}, pressure: '', jsScore: 0, maxBufferMB: 0 };
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
  toast(S.token ? 'Token saved' : 'Token cleared'); loadModels();
  reportNode().then(function () {
    // Pairing on the slave page is the only manual step: once the token is
    // saved, a /worker device starts working on its own (WebGPU + not opted out).
    if (PAGE === 'worker' && S.token && !S.w.on && !S.w.master && S.adapter && localStorage.getItem('karen_worker') !== '0') {
      toast('Paired — this device now works as a slave node');
      workerJoin(true);
    }
  }); }
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
var WGSL_MM = [
'struct Dims { m: u32, n: u32, k: u32, pad: u32 }',
'@group(0) @binding(0) var<storage, read> A: array<f32>;',
'@group(0) @binding(1) var<storage, read> B: array<f32>;',
'@group(0) @binding(2) var<storage, read_write> C: array<f32>;',
'@group(0) @binding(3) var<uniform> dims: Dims;',
'var<workgroup> tileA: array<f32, 256>;',
'var<workgroup> tileB: array<f32, 256>;',
'@compute @workgroup_size(16, 16)',
'fn main(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_id) lid: vec3<u32>) {',
'  let row = gid.y; let col = gid.x; let lr = lid.y; let lc = lid.x;',
'  var acc = 0.0;',
'  let M = dims.m; let N = dims.n; let K = dims.k;',
'  let tiles = (K + 15u) / 16u;',
'  for (var t = 0u; t < tiles; t = t + 1u) {',
'    let aCol = t * 16u + lc; let bRow = t * 16u + lr;',
'    var av = 0.0; var bv = 0.0;',
'    if (row < M && aCol < K) { av = A[row * K + aCol]; }',
'    if (bRow < K && col < N) { bv = B[bRow * N + col]; }',
'    tileA[lr * 16u + lc] = av; tileB[lr * 16u + lc] = bv;',
'    workgroupBarrier();',
'    for (var kk = 0u; kk < 16u; kk = kk + 1u) {',
'      acc = acc + tileA[lr * 16u + kk] * tileB[kk * 16u + lc];',
'    }',
'    workgroupBarrier();',
'  }',
'  if (row < M && col < N) { C[row * N + col] = acc; }',
'}'
].join('\n');
// ---- machine telemetry -----------------------------------------------------
// What a browser can honestly read about its own machine. There is no
// temperature API in any browser: where the UI says "heat" for a slave node it
// shows the CPU pressure state from PressureObserver (Chrome/Edge 125+) — the
// closest real load/thermal signal — while the phone app shows its real
// battery temperature and thermal status. deviceMemory is capped at 8 GB by
// spec; storage.estimate().quota ≈ free disk; downlink/rtt are browser
// estimates; maxBufferSize is a WebGPU VRAM-tier proxy.
function measureCpu() {
  try {
    var t0 = performance.now(), x = 0;
    for (var i = 1; i < 2000000; i++) { x = (x + i) % 99991; }
    var dt = performance.now() - t0;
    if (dt > 0 && x >= 0) { S.jsScore = Math.round(400000 / dt) / 100; }
  } catch (e) {}
}
function initPressure() {
  try {
    if (!window.PressureObserver) { return; }
    var po = new PressureObserver(function (recs) {
      if (recs && recs.length) { S.pressure = recs[recs.length - 1].state || ''; }
    });
    po.observe('cpu', { sampleInterval: 30000 }).catch(function () {});
  } catch (e) {}
}
function collectTelemetry() {
  var t = S.tel || {};
  try { t.c = navigator.hardwareConcurrency || t.c || 0; } catch (e) {}
  try { t.m = navigator.deviceMemory || t.m || 0; } catch (e) {}
  try { if (S.pressure) { t.p = S.pressure; } } catch (e) {}
  try { if (S.jsScore) { t.js = S.jsScore; } } catch (e) {}
  try { if (S.maxBufferMB) { t.ab = S.maxBufferMB; } } catch (e) {}
  try {
    var conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
    if (conn) {
      t.nt = conn.effectiveType || t.nt || '';
      t.nd = conn.downlink || t.nd || 0;
      t.nr = conn.rtt || t.nr || 0;
    }
  } catch (e) {}
  S.tel = t;
  return t;
}
async function collectTelemetryDeep() {
  try {
    if (!S.tel.a && navigator.userAgentData && navigator.userAgentData.getHighEntropyValues) {
      var h = await navigator.userAgentData.getHighEntropyValues(['architecture', 'bitness']);
      var bits = [];
      if (h.architecture) { bits.push(h.architecture); }
      if (h.bitness) { bits.push(h.bitness + '-bit'); }
      if (bits.length) { S.tel.a = bits.join(' '); }
    }
  } catch (e) {}
  try {
    var est = await navigator.storage.estimate();
    if (est && est.quota) { S.tel.sq = Math.round(est.quota / 10737418.24) / 100; }
    if (est && est.usage) { S.tel.su = Math.round(est.usage / 10737418.24) / 100; }
  } catch (e) {}
  return collectTelemetry();
}
function telQuery() {
  try { collectTelemetry(); return '?tel=' + encodeURIComponent(JSON.stringify(S.tel)); }
  catch (e) { return ''; }
}
async function reportNode() {
  if (!S.token) return null;
  try { await collectTelemetryDeep(); } catch (e) {}
  return fetch('/v1/nodes/hello', {
    method: 'POST',
    headers: headers(true),
    body: JSON.stringify({ name: S.node, webgpu: !!S.adapter, gpu: S.gpuName || '', gflops: S.gflops || 0, telemetry: collectTelemetry() })
  }).then(function (r) { return r.json().catch(function () { return null; }); })
    .then(function (j) {
      if (j && j.master) {
        S.w.master = true;
        if (S.w.on) { S.w.on = false; }
        var wb = el('gpuWorker');
        if (wb) { wb.checked = false; wb.disabled = true; }
        el('workerStatus').textContent = 'This is the master device (the phone) — it coordinates; other devices work as slave nodes.';
      }
      return j;
    })
    .catch(function () { return null; });
}
function toggleBoostText() {
  el('boostStatus').textContent = S.boost
    ? 'Chat runs on THIS device\'s GPU (WebLLM). The first reply downloads the engine + model once, then it is cached.'
    : 'Chat runs on the phone (llama.cpp) over the LAN. Tick boost to run it here instead.';
}
async function initGpu() {
  var box = el('gpuBoost');
  box.checked = S.boost;
  var host = location.hostname;
  var pport = location.port || '80';
  var origin = location.origin;
  var secure = window.isSecureContext;
  var hint = el('secureHint');
  if (!navigator.gpu) {
    if (!secure) {
      // Every browser — Chrome, Edge, Brave, Firefox — hides navigator.gpu on
      // plain-http LAN origins. It is a security rule, not a browser problem,
      // so name the real cause and give both fixes with this page's values.
      el('gpuSummary').textContent = 'GPU offload — blocked: ' + origin + ' is a plain http:// address, and every browser hides WebGPU there';
      el('gpuAdapter').textContent = 'This is the same in Chrome, Edge and Firefox by design (not a version thing). The phone keeps answering on llama.cpp meanwhile. Two fixes:';
      if (hint) {
        hint.style.display = 'block';
        hint.innerHTML =
          '<b>Fix 1 — tunnel, no flags (desktop):</b> run on this PC ' +
          '<code style="background:#0c0e12;border:1px solid #283042;border-radius:5px;padding:1px 5px">powershell -ExecutionPolicy Bypass -File tools\\karen-tunnel.ps1 -Phone ' + host + ' -Port ' + pport + '</code>' +
          ' and open <a href="http://127.0.0.1:11434/" style="color:#38bdf8">http://127.0.0.1:11434/</a> — 127.0.0.1 is a trusted address, so WebGPU is exposed there.<br>' +
          '<b>Fix 2 — trust this address:</b> open <b>chrome://flags/#unsafely-treat-insecure-origin-as-secure</b>, add ' +
          '<code style="background:#0c0e12;border:1px solid #283042;border-radius:5px;padding:1px 5px">' + origin + '</code>, set it to Enabled, relaunch the browser.';
      }
    } else {
      el('gpuSummary').textContent = 'GPU offload — WebGPU is switched off in this browser';
      el('gpuAdapter').textContent = 'The address is fine (' + origin + ' is trusted), so the browser itself is not exposing WebGPU — open chrome://gpu and look for "WebGPU: Disabled".';
      if (hint) {
        hint.style.display = 'block';
        hint.textContent = 'chrome://gpu → "WebGPU: Disabled" is the reason. Usually: hardware acceleration turned off in browser settings, an outdated browser (needs Chrome/Edge 113+), or a blocklisted GPU driver.';
      }
    }
    el('benchBtn').disabled = true;
    box.disabled = true;
    box.checked = false;
    S.boost = false;
    el('boostStatus').textContent = 'GPU boost unavailable on this device.';
    var wbox0 = el('gpuWorker');
    if (wbox0) { wbox0.disabled = true; wbox0.checked = false; }
    S.w.on = false;
    el('workerStatus').textContent = 'Slave node mode needs WebGPU on this device.';
    if (el('workerModel')) { el('workerModel').disabled = true; }
    return;
  }
  try {
    var ad = await navigator.gpu.requestAdapter();
    if (!ad) {
      el('gpuSummary').textContent = 'GPU offload — WebGPU is present, but this machine offered no GPU adapter';
      el('gpuAdapter').textContent = 'requestAdapter() found nothing — often software rendering or a blocklisted GPU (the phone keeps answering meanwhile).';
      if (hint) {
        hint.style.display = 'block';
        hint.textContent = 'Check chrome://gpu → "Graphics Feature Status": look for "Hardware accelerated: No" or "WebGPU: Disabled". Updating the GPU driver usually fixes this.';
      }
      el('benchBtn').disabled = true;
      box.disabled = true;
      return;
    }
    S.adapter = ad;
    try {
      var mbuf = ad.limits && ad.limits.maxBufferSize;
      if (mbuf) { S.maxBufferMB = Math.round(mbuf / 1048576); }
    } catch (e) {}
    var info = ad.info || {};
    S.gpuName = [info.vendor, info.architecture, info.device, info.description].filter(Boolean).join(' · ') || 'WebGPU adapter';
    el('gpuSummary').textContent = 'GPU offload — this device: ' + S.gpuName;
    el('gpuAdapter').textContent = 'Adapter: ' + S.gpuName + ' — benchmark it below, or switch chat onto this GPU.';
    toggleBoostText();
    await reportNode();
    if (PAGE !== 'worker') { return; }  // the console never auto-joins as a slave
    el('gpuWorker').disabled = false;
    if (S.w.master) {
      // The phone itself: master, not a slave node.
      el('gpuWorker').disabled = true;
      el('workerStatus').textContent = 'This is the master device (the phone) — other devices work as slave nodes.';
    } else if (S.w.on) {
      workerStatus(S.w.saved ? 'rejoining…' : 'this device works as a slave node — joining…');
      workerJoin(true);
    } else if (!S.token) {
      el('workerStatus').textContent = 'Slave node — save the token above once; this device then starts working automatically.';
    }
  } catch (e) {
    el('gpuSummary').textContent = 'GPU offload — adapter check failed: ' + (e && e.message ? e.message : e);
    if (hint) {
      hint.style.display = 'block';
      hint.textContent = 'requestAdapter() threw — WebGPU is only partially enabled here. Check chrome://gpu, then reload this page.';
    }
    el('benchBtn').disabled = true;
    box.disabled = true;
  }
}
function toggleBoost(on) {
  if (on && !S.adapter) {
    el('gpuBoost').checked = false;
    toast('No WebGPU adapter on this device');
    return;
  }
  S.boost = on;
  localStorage.setItem('karen_gpu_boost', on ? '1' : '0');
  toggleBoostText();
  toast(on ? 'GPU boost ON — chat runs on this device' : 'GPU boost OFF — back to the phone');
  if (on) reportNode();
}
async function runBench() {
  var btn = el('benchBtn');
  btn.disabled = true;
  el('benchResult').textContent = 'running…';
  try {
    if (!S.adapter) { S.adapter = await navigator.gpu.requestAdapter(); }
    if (!S.adapter) throw new Error('no adapter');
    var device = await S.adapter.requestDevice();
    var N = 1024;
    var bytes = N * N * 4;
    var STORAGE = 0x80, COPY_DST = 0x8, COPY_SRC = 0x4, UNIFORM = 0x40, MAP_READ = 0x1;
    var fill = new Float32Array(N * N);
    fill.fill(0.5);
    var A = device.createBuffer({ size: bytes, usage: STORAGE | COPY_DST });
    var B = device.createBuffer({ size: bytes, usage: STORAGE | COPY_DST });
    var C = device.createBuffer({ size: bytes, usage: STORAGE | COPY_SRC });
    var dims = device.createBuffer({ size: 16, usage: UNIFORM | COPY_DST });
    device.queue.writeBuffer(A, 0, fill);
    device.queue.writeBuffer(B, 0, fill);
    device.queue.writeBuffer(dims, 0, new Uint32Array([N, N, N, 0]));
    var module = device.createShaderModule({ code: WGSL_MM });
    var pipeline = device.createComputePipeline({ layout: 'auto', compute: { module: module, entryPoint: 'main' } });
    var bind = device.createBindGroup({
      layout: pipeline.getBindGroupLayout(0),
      entries: [
        { binding: 0, resource: { buffer: A } },
        { binding: 1, resource: { buffer: B } },
        { binding: 2, resource: { buffer: C } },
        { binding: 3, resource: { buffer: dims } }
      ]
    });
    async function once() {
      var enc = device.createCommandEncoder();
      var pass = enc.beginComputePass();
      pass.setPipeline(pipeline);
      pass.setBindGroup(0, bind);
      pass.dispatchWorkgroups(N / 16, N / 16);
      pass.end();
      device.queue.submit([enc.finish()]);
      await device.queue.onSubmittedWorkDone();
    }
    await once();
    var iters = 5;
    var t0 = performance.now();
    for (var i = 0; i < iters; i++) { await once(); }
    var dt = Math.max(1, performance.now() - t0) / 1000;
    var gflops = (2 * N * N * N * iters) / dt / 1e9;
    S.gflops = gflops;
    var stage = device.createBuffer({ size: 4, usage: COPY_DST | MAP_READ });
    var enc2 = device.createCommandEncoder();
    enc2.copyBufferToBuffer(C, 0, stage, 0, 4);
    device.queue.submit([enc2.finish()]);
    await stage.mapAsync(1);
    var probe = new Float32Array(stage.getMappedRange())[0];
    var okCell = Math.abs(probe - 256) < 2;
    el('benchResult').textContent = gflops.toFixed(1) + ' GFLOPS (fp32 1024^3 x' + iters + ' · cell check ' + (okCell ? 'OK' : 'BAD ' + probe) + ')';
    reportNode();
  } catch (e) {
    el('benchResult').textContent = 'benchmark failed: ' + (e && e.message ? e.message : e);
  } finally {
    btn.disabled = false;
  }
}
function boostModelList(m) {
  return ((m.prebuiltAppConfig && m.prebuiltAppConfig.model_list) || [])
    .filter(function (x) { return /q4f16_1|q4f32_1/.test(x.model_id); })
    .sort(function (a, b) { return a.vram_required_MB - b.vram_required_MB; });
}
function populateWorkerModels(list) {
  var sel = el('workerModel');
  if (!sel) return;
  var qwen = list.filter(function (x) { return /^Qwen2\.5/.test(x.model_id); });
  var rest = list.filter(function (x) { return !/^Qwen2\.5/.test(x.model_id); });
  var ordered = qwen.concat(rest.slice(0, 40));
  S.wModels = ordered.map(function (x) { return x.model_id; });
  sel.innerHTML = '';
  ordered.forEach(function (x) {
    var o = document.createElement('option');
    o.value = x.model_id;
    o.textContent = x.model_id + ' (' + Math.round(x.vram_required_MB) + ' MB VRAM)';
    sel.appendChild(o);
  });
  var want = sel.value || localStorage.getItem('karen_worker_model') || '';
  if (want) { sel.value = want; }
  if (!sel.value && sel.options.length) { sel.selectedIndex = 0; }
  S.w.model = sel.value || '';
}
function saveWorkerModel() {
  S.w.model = el('workerModel').value;
  localStorage.setItem('karen_worker_model', S.w.model);
  toast('Slave node model: ' + S.w.model);
  if (S.w.on) { workerRegister(); }
}
function populateBoostModels(m) {
  var sel = el('boostModel');
  var list = boostModelList(m);
  sel.innerHTML = '';
  list.forEach(function (x) {
    var o = document.createElement('option');
    o.value = x.model_id;
    o.textContent = x.model_id + ' (' + Math.round(x.vram_required_MB) + ' MB VRAM)';
    sel.appendChild(o);
  });
  var want = localStorage.getItem('karen_gpu_model') || '';
  if (want) { sel.value = want; }
  if (!sel.value && sel.options.length) { sel.selectedIndex = 0; }
  S.llmModel = sel.value || '';
  populateWorkerModels(list);
}
function saveBoostModel() {
  S.llmModel = el('boostModel').value;
  localStorage.setItem('karen_gpu_model', S.llmModel);
  if (S.llm) { try { S.llm.unload(); } catch (e) {} S.llm = null; }
  toast('GPU model: ' + S.llmModel);
}
async function initEngine(bubble) {
  if (!navigator.gpu) throw new Error('WebGPU not available in this browser');
  if (!S.llmMod) {
    bubble.textContent = 'Loading WebLLM engine (one time)…';
    try {
      S.llmMod = await import('https://esm.run/@mlc-ai/web-llm@0.2.86');
    } catch (e) {
      throw new Error('could not load WebLLM from the CDN — GPU boost needs internet once to fetch the engine: ' + (e && e.message ? e.message : e));
    }
    populateBoostModels(S.llmMod);
  }
  if (!S.llmModel) { populateBoostModels(S.llmMod); }
  if (!S.llmModel) { throw new Error('no GPU model list available'); }
  var engine = new S.llmMod.MLCEngine();
  engine.setInitProgressCallback(function (p) {
    bubble.textContent = 'GPU: ' + (p && p.text ? p.text : 'loading…');
  });
  await engine.reload(S.llmModel);
  S.llm = engine;
  reportNode();
}
async function boostSend(text) {
  el('input').value = '';
  add('user', text);
  S.log.push({ role: 'user', content: text });
  var bubble = add('assistant', '…');
  S.busy = true; el('send').disabled = true; el('stop').disabled = false;
  S.ctrl = new AbortController();
  var full = '';
  try {
    if (!S.llm) { await initEngine(bubble); }
    bubble.textContent = '…';
    var it = await S.llm.chat.completions.create({
      messages: S.log.slice(-12),
      stream: true,
      temperature: 0.7
    });
    for await (var piece of it) {
      if (S.ctrl.signal.aborted) break;
      var d = piece.choices && piece.choices[0] && piece.choices[0].delta ? piece.choices[0].delta.content : '';
      if (d) { full += d; bubble.textContent = full; el('chat').scrollTop = el('chat').scrollHeight; }
    }
    if (full) { S.log.push({ role: 'assistant', content: full }); }
    else {
      bubble.className = 'msg sys'; bubble.textContent = 'stopped';
      if (S.log.length && S.log[S.log.length - 1].role === 'user') { S.log.pop(); }
    }
  } catch (e) {
    bubble.className = 'msg sys';
    bubble.textContent = 'GPU boost failed: ' + (e && e.message ? e.message : e);
    if (S.log.length && S.log[S.log.length - 1].role === 'user') { S.log.pop(); }
  } finally {
    S.busy = false; el('send').disabled = false; el('stop').disabled = true; S.ctrl = null;
  }
}
function stopGen() {
  if (S.ctrl) { S.ctrl.abort(); }
  if (S.llm && S.boost) { try { S.llm.interrupt(); } catch (e) {} }
}
// ---- GPU worker mode: this device runs tasks sent by any dashboard -------
function post(url, obj) {
  return fetch(url, { method: 'POST', headers: headers(true), body: JSON.stringify(obj) })
    .then(function (r) { return r.json().catch(function () { return null; }); })
    .catch(function () { return null; });
}
function workerSleep(ms) { return new Promise(function (res) { setTimeout(res, ms); }); }
function workerStatus(text) { el('workerStatus').textContent = 'Slave node ' + (S.w.id ? '(' + S.w.id + ') ' : '') + text; }
function addMeta(bubble, text) {
  var d = document.createElement('div'); d.className = 'meta'; d.textContent = text;
  if (bubble.parentNode) { bubble.parentNode.insertBefore(d, bubble.nextSibling); }
}
function toggleWorker(on) {
  if (on && S.w.master) {
    el('gpuWorker').checked = false;
    toast('This is the master device — it does not join as a slave node');
    return;
  }
  if (on && !S.adapter) {
    el('gpuWorker').checked = false;
    toast('No WebGPU adapter on this device');
    return;
  }
  if (on && !S.token) {
    el('gpuWorker').checked = false;
    workerStatus('save the token above first — then this device works as a slave node');
    toast('Token needed first');
    return;
  }
  localStorage.setItem('karen_worker', on ? '1' : '0');
  if (on) { S.w.saved = true; workerJoin(false); } else { workerLeave('left from this device'); }
}
async function workerJoin(isAuto) {
  S.w.on = true;
  el('gpuWorker').checked = true;
  workerStatus('starting…');
  try {
    if (!S.llmMod) {
      S.llmMod = await import('https://esm.run/@mlc-ai/web-llm@0.2.86');
      populateWorkerModels(boostModelList(S.llmMod));
    }
  } catch (e) {
    workerStatus('engine unavailable — needs internet once: ' + (e && e.message ? e.message : e));
    return;
  }
  await workerRegister();
  if (S.w.master) { S.w.on = false; el('gpuWorker').checked = false; return; }
  if (!isAuto) { toast('Working as a slave node'); }
  workerLoop();
}
async function workerRegister() {
  try { await Promise.race([collectTelemetryDeep(), workerSleep(1500)]); } catch (e) {}
  var j = await post('/v1/worker/register', {
    workerId: S.w.id,
    name: S.node,
    gpu: S.gpuName || '',
    gflops: S.gflops || 0,
    backend: 'webllm',
    engine: 'web-llm@0.2.86',
    defaultModel: (el('workerModel') && el('workerModel').value) || S.w.model || '',
    models: S.wModels,
    loadedModel: S.w.loaded || '',
    telemetry: collectTelemetry()
  });
  if (j && j.master) {
    S.w.master = true;
    S.w.on = false;
    var wb = el('gpuWorker');
    if (wb) { wb.checked = false; wb.disabled = true; }
    workerStatus('this is the master device (the phone) — other devices work as slave nodes');
    refreshWorkers();
    return;
  }
  if (j && j.workerId) {
    S.w.id = j.workerId;
    localStorage.setItem('karen_worker_id', S.w.id);
    workerStatus('registered — polling for tasks');
  } else {
    workerStatus('registration failed — is the token saved?');
  }
  refreshWorkers();
}
function workerLeave(reason) {
  S.w.on = false;
  localStorage.setItem('karen_worker', '0');
  el('gpuWorker').checked = false;
  if (S.w.id) { post('/v1/worker/leave', { workerId: S.w.id }).then(function () { refreshWorkers(); }); }
  workerStatus('off — ' + (reason || 'left'));
}
async function workerLoop() {
  if (S.w.loop) { return; }  // exactly one poll loop per tab, ever
  S.w.loop = true;
  try {
    while (S.w.on) {
      var r = null;
      var tq = '';
      // Fresh telemetry rides on the poll that was happening anyway — the
      // console/app see live cpu/ram/disk/network with zero extra requests.
      try { await Promise.race([collectTelemetryDeep(), workerSleep(1500)]); } catch (e) {}
      try { tq = telQuery(); } catch (e) {}
      try { r = await fetch('/v1/worker/poll' + tq, { headers: headers(true) }); }
      catch (e) { workerStatus('connection lost — retrying'); await workerSleep(3000); continue; }
      if (!r) { await workerSleep(3000); continue; }
      if (r.status === 410) { workerLeave('disconnected by the phone'); toast('Removed by the phone — slave mode off'); return; }
      if (r.status === 404) { workerStatus('re-registering…'); await workerRegister(); continue; }
      if (r.status === 401) { workerStatus('token needed — save it above'); await workerSleep(5000); continue; }
      if (!r.ok) { workerStatus('poll HTTP ' + r.status); await workerSleep(3000); continue; }
      if (S.w.on) { workerStatus('idle — waiting for tasks'); }
      var reader = r.body.getReader();
      var dec = new TextDecoder();
      var buf = '';
      try {
        while (true) {
          var step = await reader.read();
          if (step.done) break;
          buf += dec.decode(step.value, { stream: true });
          var i;
          while ((i = buf.indexOf('\n\n')) >= 0) {
            var block = buf.slice(0, i); buf = buf.slice(i + 2);
            if (block.indexOf('event: task') !== 0) continue;
            var dl = null;
            var lines = block.split('\n');
            for (var k = 0; k < lines.length; k++) { if (lines[k].indexOf('data: ') === 0) { dl = lines[k].slice(6); break; } }
            if (!dl) continue;
            try { var task = JSON.parse(dl); await workerRunTask(task); }
            catch (e) { workerStatus('bad task: ' + (e && e.message ? e.message : e)); }
          }
        }
      } catch (e) {
        workerStatus('poll stream error — retrying');
      }
    }
  } finally {
    S.w.loop = false;
  }
}
async function workerEnsureEngine(model, taskId) {
  if (!S.w.engine) { S.w.engine = new S.llmMod.MLCEngine(); S.w.loaded = ''; }
  var target = model || S.w.loaded;
  if (!target) { throw new Error('no model selected for this worker'); }
  if (S.w.loaded === target) { return; }
  var lastReport = 0;
  S.w.engine.setInitProgressCallback(function (p) {
    var txt = (p && p.text) ? p.text : 'loading';
    workerStatus('loading ' + target + ' — ' + txt);
    var now = performance.now();
    if (now - lastReport > 400) {
      lastReport = now;
      post('/v1/worker/progress', {
        workerId: S.w.id, taskId: taskId, phase: 'loading',
        progress: (p && typeof p.progress === 'number') ? p.progress : -1,
        text: txt
      }).then(function (r) { if (r && r.cancel) { S.w.interrupted = true; } });
    }
  });
  await S.w.engine.reload(target);
  S.w.loaded = target;
}
async function workerCancel(id) {
  try { S.w.engine.interrupt(); } catch (e) {}
  await post('/v1/worker/done', { workerId: S.w.id, taskId: id, status: 'cancelled' });
  workerStatus('idle — last task cancelled');
  refreshWorkers();
}
async function workerRunTask(task) {
  var id = task.id;
  S.w.interrupted = false;
  var model = task.model || S.w.model || (el('workerModel') ? el('workerModel').value : '');
  workerStatus('running ' + id + ' → ' + (model || 'default'));
  var t0 = 0, ttft = 0, tokens = 0;
  try {
    await post('/v1/worker/progress', { workerId: S.w.id, taskId: id, phase: 'loading', text: 'preparing ' + (model || 'model') });
    await workerEnsureEngine(model, id);
    if (S.w.interrupted) { await workerCancel(id); return; }
    var pr = await post('/v1/worker/progress', { workerId: S.w.id, taskId: id, phase: 'running', text: 'generating', loadedModel: S.w.loaded });
    if (pr && pr.cancel) { await workerCancel(id); return; }
    t0 = performance.now();
    var it = await S.w.engine.chat.completions.create({
      messages: task.messages || [],
      stream: true,
      temperature: task.temperature || 0.7,
      max_tokens: task.max_tokens || 512
    });
    var buf = '', lastFlush = 0, cancel = false;
    for await (var piece of it) {
      if (S.w.interrupted) { cancel = true; break; }
      var d = piece.choices && piece.choices[0] && piece.choices[0].delta ? piece.choices[0].delta.content : '';
      if (!d) continue;
      if (!ttft) { ttft = performance.now() - t0; }
      tokens += 1;
      buf += d;
      var now = performance.now();
      if (now - lastFlush >= 40) {
        lastFlush = now;
        var chunk = buf; buf = '';
        var resp = await post('/v1/worker/stream', { workerId: S.w.id, taskId: id, delta: chunk });
        if (resp && resp.cancel) { cancel = true; S.w.interrupted = true; break; }
      }
    }
    if (buf && !cancel) { await post('/v1/worker/stream', { workerId: S.w.id, taskId: id, delta: buf }); }
    if (cancel) { try { S.w.engine.interrupt(); } catch (e) {} }
    var elapsed = Math.max(1, performance.now() - t0);
    var tps = Math.round(tokens / (elapsed / 1000) * 10) / 10;
    await post('/v1/worker/done', {
      workerId: S.w.id, taskId: id,
      status: cancel ? 'cancelled' : 'completed',
      stats: { ttftMs: Math.round(ttft), tokens: tokens, elapsedMs: Math.round(elapsed), tps: tps }
    });
    workerStatus(cancel ? 'idle — last task cancelled' : ('idle — last ' + tps + ' tok/s'));
    refreshWorkers();
  } catch (e) {
    await post('/v1/worker/done', { workerId: S.w.id, taskId: id, status: 'failed', error: (e && e.message ? e.message : String(e)) });
    workerStatus('idle — last task failed: ' + (e && e.message ? e.message : e));
  }
}
function runnerName(id) {
  for (var i = 0; i < S.wlist.length; i++) { if (S.wlist[i].id === id) { return S.wlist[i].name; } }
  return id;
}
function runnerInfo(id) {
  for (var i = 0; i < S.wlist.length; i++) { if (S.wlist[i].id === id) { return S.wlist[i]; } }
  return null;
}
function topGpuId(ws) {
  // The node with the most GPU: highest GFLOPS benchmark among online nodes
  // (the phone already returns /v1/workers sorted online-first, GFLOPS-desc).
  var best = null;
  (ws || []).forEach(function (w) {
    if (w.state === 'offline') return;
    if (!best || (w.gflops || 0) > (best.gflops || 0)) { best = w; }
  });
  return best ? best.id : '';
}
function gpuLabel(w) {
  var bits = w.name;
  bits += (w.state === 'offline') ? ' — offline' : (' — ' + w.state);
  if (w.gpu) { bits += ' · ' + w.gpu; }
  bits += w.gflops ? (' · ' + Math.round(w.gflops) + ' GFLOPS') : ' · no bench';
  return bits;
}
function telemetryLine(w) {
  // One compact line of what the worker page honestly measured about itself.
  var tp = [];
  try {
    if (w.cpuCores || w.cpuArch) {
      tp.push('CPU ' + (w.cpuCores ? w.cpuCores + 'c' : '') + ((w.cpuCores && w.cpuArch) ? ' ' : '') + (w.cpuArch || ''));
    }
    if (w.jsScore) { tp.push('score ' + w.jsScore); }
    if (w.memGB) { tp.push('RAM ≈' + w.memGB + ' GB'); }
    if (w.pressure) { tp.push('pressure ' + w.pressure); }
    if (w.quotaGB) { tp.push('disk ≈' + w.quotaGB + ' GB' + (w.usageGB ? ' (used ' + w.usageGB + ')' : '')); }
    if (w.netType || w.downlinkMbps || w.netRttMs) {
      var net = w.netType || 'net';
      if (w.downlinkMbps) { net += ' ↓' + w.downlinkMbps + ' Mbps'; }
      if (w.netRttMs) { net += ' rtt ' + w.netRttMs + ' ms'; }
      tp.push(net);
    }
    if (w.maxBufferMB) { tp.push('GPU buf ' + w.maxBufferMB + ' MB'); }
  } catch (e) {}
  return tp.join(' · ');
}
function syncRunnerUi() {
  var sel = el('runner');
  var box = el('wmodel');
  if (!sel.value || sel.value === 'auto') { box.style.display = 'none'; return; }
  box.style.display = '';
  var cur = box.value;
  box.innerHTML = '';
  var o0 = document.createElement('option');
  o0.value = ''; o0.textContent = '(worker default model)';
  box.appendChild(o0);
  var w = null;
  for (var i = 0; i < S.wlist.length; i++) { if (S.wlist[i].id === sel.value) { w = S.wlist[i]; break; } }
  if (w) {
    (w.models || []).slice(0, 60).forEach(function (m) {
      var o = document.createElement('option'); o.value = m; o.textContent = m; box.appendChild(o);
    });
    var has = false;
    for (var k = 0; k < box.options.length; k++) { if (box.options[k].value === cur) { has = true; break; } }
    if (has) { box.value = cur; }
    else if (w.loadedModel) {
      for (var k2 = 0; k2 < box.options.length; k2++) { if (box.options[k2].value === w.loadedModel) { box.value = w.loadedModel; break; } }
    }
  }
}
function runnerChanged() {
  syncRunnerUi();
  var v = el('runner').value;
  S.routed = v;
  localStorage.setItem('karen_runner', v);
  if (v === '') { toast('Runner: the phone (llama.cpp)'); }
  else if (v === 'auto') { toast('Runner: auto — the fastest GPU online gets each prompt'); }
  else {
    var w = runnerInfo(v);
    toast(w ? ('Runner: ' + gpuLabel(w)) : ('Runner: ' + runnerName(v)));
  }
}
function renderRunner(ws) {
  var sel = el('runner');
  var cur = sel.value || S.routed || '';
  sel.innerHTML = '';
  var online = 0;
  (ws || []).forEach(function (w) { if (w.state !== 'offline') online += 1; });
  var o0 = document.createElement('option');
  o0.value = ''; o0.textContent = 'Runner: Phone (llama.cpp)';
  sel.appendChild(o0);
  var topId = topGpuId(ws);
  var oa = document.createElement('option');
  oa.value = 'auto'; oa.textContent = 'Runner: Auto — fastest GPU online (' + online + ' online)';
  sel.appendChild(oa);
  (ws || []).forEach(function (w) {
    var o = document.createElement('option');
    o.value = w.id;
    o.textContent = 'Runner: ' + gpuLabel(w) + (w.id === topId ? ' ★ fastest' : '');
    sel.appendChild(o);
  });
  var has = false;
  for (var i = 0; i < sel.options.length; i++) { if (sel.options[i].value === cur) { has = true; break; } }
  sel.value = has ? cur : '';
  // Remember the pick so a reload keeps routing to the same node.
  S.routed = sel.value;
  localStorage.setItem('karen_runner', S.routed);
  syncRunnerUi();
}
function refreshWorkers() {
  // The slave list only exists on the console; the worker page has nothing to
  // show there, so it must not keep asking the phone for it. This used to run
  // every 5 s from every open tab — on worker pages that was the bulk of the
  // "slaves keep sending requests" traffic.
  if (PAGE !== 'console') { return; }
  fetch('/v1/workers' + telQuery(), { headers: headers(false) }).then(function (r) {
    if (r.status === 401) { el('workersSummary').textContent = 'Slave nodes — save the token to list them'; return null; }
    if (!r.ok) { return null; }
    return r.json();
  }).then(function (j) {
    if (!j) return;
    var ws = j.data || [];
    S.wlist = ws;
    var online = 0;
    for (var i = 0; i < ws.length; i++) { if (ws[i].state !== 'offline') online += 1; }
    var topId = topGpuId(ws);
    var topW = runnerInfo(topId);
    el('workersSummary').textContent = 'Slave nodes (' + ws.length + ') — ' + online + ' online' +
      (topW ? ' · fastest: ' + topW.name + (topW.gflops ? ' (' + Math.round(topW.gflops) + ' GFLOPS)' : '') : '');
    var box = el('workerList');
    box.innerHTML = '';
    if (!ws.length) {
      var d = document.createElement('div');
      d.className = 'fmeta';
      d.textContent = 'No slave nodes yet. On a laptop/desktop open this dashboard and save the token — it joins automatically as a slave node, then select it here (or in Runner). The node with the most GPU is listed first.';
      box.appendChild(d);
    }
    var routed = (el('runner') ? el('runner').value : '') || S.routed || '';
    ws.forEach(function (w) {
      var row = document.createElement('div');
      row.className = 'frow';
      var st = document.createElement('span');
      st.className = 'badge ' + (w.state === 'offline' ? 'off' : (w.state === 'idle' ? 'ok' : 'mid'));
      st.textContent = w.state;
      row.appendChild(st);
      var nm = document.createElement('span');
      nm.className = 'fname';
      nm.textContent = w.name + ' — ' + (w.gpu || 'GPU');
      row.appendChild(nm);
      var gp = document.createElement('span');
      gp.className = 'badge mid';
      gp.textContent = w.gflops ? (Math.round(w.gflops) + ' GFLOPS') : 'no bench';
      row.appendChild(gp);
      if (w.id === topId) {
        var fb = document.createElement('span');
        fb.className = 'badge ok';
        fb.textContent = 'fastest GPU';
        row.appendChild(fb);
      }
      if (routed === w.id || (routed === 'auto' && w.id === topId)) {
        var rb = document.createElement('span');
        rb.className = 'badge mid';
        rb.textContent = (routed === 'auto') ? 'auto target' : 'routed here';
        row.appendChild(rb);
      }
      var md = document.createElement('span');
      md.className = 'fmeta';
      md.textContent = (w.loadedModel ? 'loaded: ' + w.loadedModel : 'default: ' + (w.defaultModel || '—')) +
        ' · ' + w.tasksDone + ' task(s)' +
        (w.lastTps ? (' · last ' + w.lastTps + ' tok/s · ttft ' + ((w.lastTtftMs || 0) / 1000).toFixed(2) + ' s') : '');
      row.appendChild(md);
      var tp = telemetryLine(w);
      if (tp) {
        var tl = document.createElement('span');
        tl.className = 'fmeta';
        tl.textContent = tp;
        tl.title = 'Reported by the worker page itself. Browsers expose no temperature sensor — pressure is the CPU pressure state (nominal/fair/serious/critical); RAM is the browser estimate (capped at 8 GB); disk is the storage quota (~free disk); downlink/rtt are browser network estimates.';
        row.appendChild(tl);
      }
      var rt = document.createElement('button');
      rt.className = 'ghost';
      rt.textContent = (routed === w.id) ? 'Selected ✓' : 'Select for chat';
      rt.title = (w.state === 'offline')
        ? 'node is offline — it takes no prompts right now'
        : ('route prompts to ' + gpuLabel(w));
      rt.disabled = (w.state === 'offline') || (routed === w.id);
      rt.onclick = function () {
        el('runner').value = w.id;
        runnerChanged();
        refreshWorkers();
      };
      row.appendChild(rt);
      var rm = document.createElement('button');
      rm.className = 'ghost';
      rm.textContent = 'Disconnect';
      rm.onclick = function () {
        post('/v1/workers/remove', { id: w.id }).then(function () { toast('Disconnected ' + w.name); refreshWorkers(); });
      };
      row.appendChild(rm);
      box.appendChild(row);
    });
    renderRunner(ws);
  }).catch(function () {});
}
function workerSend(text, runner, wmodel) {
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
    body: JSON.stringify({ worker: runner, model: wmodel || '', messages: S.log.slice(-12), stream: true, temperature: 0.7, max_tokens: 1024 }),
    signal: S.ctrl.signal
  }).then(function (r) {
    if (!r.ok) {
      return r.text().then(function (t) {
        bubble.className = 'msg sys';
        bubble.textContent = 'Worker route error ' + r.status + ' — ' + t.slice(0, 240);
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
            if (j.object === 'worker.status') {
              if (!full) { bubble.textContent = '(' + (j.phase || '…') + ') ' + (j.text || ''); }
              continue;
            }
            if (j.object === 'worker.done') {
              var st = j.stats || {};
              if (full && j.state !== 'failed') {
                addMeta(bubble, 'Slave node · ' + (st.tokens || 0) + ' tok · ' + (st.tps || 0) + ' tok/s · ttft ' + (((st.ttftMs || 0) / 1000).toFixed(2)) + ' s');
              }
              continue;
            }
            if (j.object === 'worker.error') {
              var msg = (j.error && j.error.message) ? j.error.message : 'worker error';
              bubble.className = 'msg sys';
              bubble.textContent = 'Worker error — ' + msg;
              continue;
            }
            var ch = j.choices && j.choices[0];
            var d = ch && ch.delta && ch.delta.content;
            if (d) { full += d; bubble.textContent = full; el('chat').scrollTop = el('chat').scrollHeight; }
          } catch (e) {}
        }
        return pump();
      });
    }
    return pump().then(function () {
      if (full) { S.log.push({ role: 'assistant', content: full }); }
      else if (bubble.className.indexOf('sys') < 0) {
        bubble.className = 'msg sys'; bubble.textContent = '(no tokens — worker finished empty)';
        if (S.log.length && S.log[S.log.length - 1].role === 'user') { S.log.pop(); }
      }
    });
  }).catch(function () {
    if (full) { S.log.push({ role: 'assistant', content: full }); bubble.textContent = full + ' [stopped]'; }
    else {
      bubble.className = 'msg sys'; bubble.textContent = 'stopped';
      if (S.log.length && S.log[S.log.length - 1].role === 'user') { S.log.pop(); }
    }
  }).then(function () {
    S.busy = false; el('send').disabled = false; el('stop').disabled = true; S.ctrl = null;
    refreshWorkers();
  });
}
function send() {
  var text = el('input').value.trim();
  if (!text || S.busy) return;
  var runner = el('runner') ? el('runner').value : '';
  if (runner) {
    if (!S.token) { toast('Paste the bearer token first'); return; }
    workerSend(text, runner, runner === 'auto' ? '' : el('wmodel').value);
    return;
  }
  if (S.boost) { boostSend(text); return; }
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
measureCpu();
initPressure();
collectTelemetryDeep().catch(function () {});
if (PAGE === 'console') {
  add('sys', 'Hello from Karen — connected to $ip:$port. ${models.size} installed model(s). Paste the token from Settings → Local Server, then pick a model — .gguf files found on disk can run too (expand the On-device model files panel below). Runner → Auto hands prompts to a laptop/desktop working as a slave node — open the worker page ($workerUrl) on that device; it joins automatically once the token is saved.');
}
loadModels();
initGpu();
if (PAGE === 'console') {
  refreshWorkers();
  // Live slave list, console only: refresh when the tab is visible, never in
  // the background, and at a calmer 10 s cadence (was every 5 s on all pages).
  if (window.setInterval) { setInterval(function () { if (!document.hidden) { refreshWorkers(); } }, 10000); }
  document.addEventListener('visibilitychange', function () { if (!document.hidden) { refreshWorkers(); } });
}
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
