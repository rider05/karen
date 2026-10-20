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
    /** Model for turns that don't name one — set by the worker page's pick or
     *  the phone's assignment (see [LocalApiServer.assignWorkerModel]). */
    @Volatile var defaultModel: String,
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
    @Volatile var telemetryAt: Long = 0L,
    /** Master-side pause: it keeps polling (stays listed/online) but takes no
     *  new prompts — "auto" skips it and explicit routes are refused. */
    @Volatile var paused: Boolean = false,
    /** When the phone last assigned [defaultModel] (ms, 0 = never). The worker
     *  page applies the value and shows "phone assigned: …" — see workerPoll. */
    @Volatile var assignedAt: Long = 0L,
    /** True when the current [defaultModel] came from the phone's assignment
     *  (Network Nodes / POST /v1/workers/model), false when the worker page's
     *  own pick wrote it — so the page only ever labels the phone's value. */
    @Volatile var assignedByPhone: Boolean = false
) {
    fun online(now: Long): Boolean = now - lastSeenAt < ONLINE_WINDOW_MS

    companion object {
        /** No worker call for this long ⇒ shown as offline / skipped by "auto".
         *  Sits above the 30 s keepalive so a healthy held poll keeps it fresh. */
        const val ONLINE_WINDOW_MS = 90_000L

        /** A running task with no worker activity for this long fails. */
        const val TASK_STALL_MS = 120_000L

        /**
         * How long one poll request is held before the worker reconnects.
         * Tasks are handed over the moment they are queued and keepalives
         * flow every 30 s inside the same request, so a long hold only cuts
         * reconnect churn: an idle node is one request every 5 minutes.
         */
        const val POLL_HOLD_MS = 300_000L
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

    /** Redesign §4: when set, the poll emits `event: shard` (not `event: task`). */
    @Volatile
    var shardEvent: JSONObject? = null

    /** Redesign §4: when set, the poll emits `event: activation` (hidden state). */
    @Volatile
    var activationEvent: JSONObject? = null
}

/** One worker's contiguous layer range in a multi-node plan (redesign §4). */
data class ShardAssign(
    val workerId: String,
    val fromLayer: Int,
    val toLayer: Int
)

/** A master-computed plan to run one model across N workers (redesign §4). */
data class ShardPlan(
    val planId: String,
    val model: String,
    val shards: List<ShardAssign>,
    val leader: String,
    val createdAt: Long
)

/** A lease granting one worker exclusive ownership of one shard (redesign §4.5). */
data class ShardLease(
    val taskId: String,
    val shardId: String,
    val workerId: String,
    val token: String,
    val expiresAt: Long
)

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
 *   POST /v1/workers/pause     master-side pause/resume ({id, paused})
 *   POST /v1/workers/model     master-side model assignment ({id, model}; "" = the node's own pick)
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

    // ---- Redesign: master-hosted weight store (§2–3) ----
    private var weightStore: WeightStore? = null

    // ---- Redesign: multi-node single-task coordination (§4) ----
    private val shardPlans = ConcurrentHashMap<String, ShardPlan>()
    private val shardLeases = ConcurrentHashMap<String, ShardLease>()
    private val shardSeq = ConcurrentHashMap<String, Long>()
    private val shardDone = ConcurrentHashMap<String, MutableSet<String>>()

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
     * Master-side pause/resume from Network Nodes (or the console). A paused
     * node keeps polling — so it stays listed and online — but new prompts
     * skip it: "auto" ignores it and an explicit route is refused. A task
     * already running is left alone; pause bites on the next assignment.
     */
    fun setWorkerPaused(id: String, paused: Boolean): Boolean {
        val worker = workers[id] ?: return false
        worker.paused = paused
        val busy = worker.currentTask.isNotBlank() && tasks.containsKey(worker.currentTask)
        if (!busy) {
            worker.state = if (paused) "paused" else "idle"
            touchNodeWorker(worker.ip, worker.state, worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
        }
        return true
    }

    /** Disconnect every joined worker (Network Nodes header menu). Count returned. */
    fun disconnectAllWorkers(): Int {
        var n = 0
        workers.keys.toList().forEach { if (disconnectWorker(it)) n += 1 }
        return n
    }

    /** What the phone can assign to one joined node (Network Nodes "Assign model"). */
    data class WorkerAssign(val id: String, val assigned: String, val models: List<String>)

    /** The assignment + reported model list for one worker id, or null if gone. */
    fun workerAssignInfo(id: String): WorkerAssign? =
        workers[id]?.let { WorkerAssign(it.id, it.defaultModel, it.models) }

    /**
     * Master-side model assignment (Network Nodes, or POST /v1/workers/model).
     * Every node turn without its own model then runs [model]; "" hands the
     * node back to its own pick. The worker page applies this on its next
     * keepalive and labels it "phone assigned" — it stays until either side
     * changes it.
     */
    fun assignWorkerModel(id: String, model: String): Boolean {
        val worker = workers[id] ?: return false
        worker.defaultModel = model.trim().take(90)
        worker.assignedAt = System.currentTimeMillis()
        worker.assignedByPhone = true
        touchNodeWorker(worker.ip, worker.state, worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
        return true
    }

    // ---- Redesign §4: multi-node single-task coordination ----

    /**
     * Compute a shard plan for a model across the given workers. The model's
     * layers are split into contiguous ranges sized to each worker's reported
     * VRAM (`maxBufferMB` is a proxy). The highest-VRAM worker is the leader
     * (it streams tokens). Returns null when no plan fits — the caller then
     * falls back to single-node (redesign §4.2).
     */
    private fun computeShardPlan(model: String, workerIds: List<String>): ShardPlan? {
        val store = weightStore ?: return null
        val m = store.readManifest(model) ?: return null
        if (m.layers <= 0 || workerIds.isEmpty()) return null
        val now = System.currentTimeMillis()
        val candidates = workerIds.mapNotNull { wid ->
            workers[wid]?.takeIf { it.online(now) && !it.paused }
        }
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedByDescending { it.maxBufferMB }
        val perLayer = m.vramRequiredMB.toDouble() / m.layers
        val shards = mutableListOf<ShardAssign>()
        var layer = 0
        for (w in sorted) {
            if (layer > m.layers - 1) break
            val capacity = (w.maxBufferMB / perLayer).toInt().coerceAtLeast(1)
            val to = (layer + capacity - 1).coerceAtMost(m.layers - 1)
            shards.add(ShardAssign(w.id, layer, to))
            layer = to + 1
        }
        if (layer < m.layers) return null // not enough capacity for multi-node
        val plan = ShardPlan(
            planId = "p_" + UUID.randomUUID().toString().replace("-", "").take(6),
            model = model,
            shards = shards,
            leader = sorted.first().id,
            createdAt = now
        )
        shardPlans[plan.planId] = plan
        shardDone[plan.planId] = ConcurrentHashMap.newKeySet()
        return plan
    }

    /** Grant an exclusive lease for one shard of a task (redesign §4.5). */
    private fun grantShardLease(taskId: String, workerId: String): ShardLease? {
        val task = tasks[taskId] ?: return null
        val plan = shardPlans.values.firstOrNull { p ->
            p.shards.any { it.workerId == workerId }
        } ?: return null
        val shardId = plan.planId + ":" + workerId
        val existing = shardLeases[shardId]
        if (existing != null && existing.workerId != workerId) return null
        val lease = ShardLease(
            taskId = taskId,
            shardId = shardId,
            workerId = workerId,
            token = UUID.randomUUID().toString().replace("-", "").take(12),
            expiresAt = System.currentTimeMillis() + 15_000
        )
        shardLeases[shardId] = lease
        return lease
    }

    /** Relay a hidden-state activation to the next shard worker (deduped). */
    private fun relayActivation(taskId: String, toWorkerId: String, seq: Long, data: String): Boolean {
        val task = tasks[taskId] ?: return false
        val key = "$taskId:$toWorkerId:act"
        val last = shardSeq.getOrDefault(key, -1L)
        if (seq <= last) return true
        shardSeq[key] = seq
        val evt = JSONObject()
            .put("type", "activation")
            .put("taskId", taskId)
            .put("seq", seq)
            .put("data", data)
        // Deliver via the target worker's held poll queue as a pseudo-task.
        val pseudo = WorkerTask(
            id = "act_${taskId}_$seq",
            workerId = toWorkerId,
            model = "",
            messages = JSONArray(),
            temperature = 0.0,
            maxTokens = 0,
            createdAt = System.currentTimeMillis()
        )
        pseudo.activationEvent = evt
        workerQueueFor(toWorkerId).add(pseudo)
        return true
    }

    /** Per-shard progress; only the leader's text reaches the console. */
    private fun shardProgress(taskId: String, workerId: String, seq: Long, text: String) {
        val task = tasks[taskId] ?: return
        val key = "$taskId:$workerId:prog"
        val last = shardSeq.getOrDefault(key, -1L)
        if (seq <= last) return
        shardSeq[key] = seq
        val plan = shardPlans.values.firstOrNull { p ->
            p.shards.any { it.workerId == workerId }
        } ?: return
        if (plan.leader != workerId) return
        if (text.isNotEmpty()) {
            task.events.put(JSONObject().put("type", "delta").put("content", text))
        }
    }

    /** Terminal for one shard; when all shards finish, the task completes. */
    private fun shardDone(taskId: String, workerId: String, seq: Long, status: String, stats: JSONObject?) {
        val task = tasks[taskId] ?: return
        val key = "$taskId:$workerId:done"
        val last = shardSeq.getOrDefault(key, -1L)
        if (seq <= last) return
        shardSeq[key] = seq
        val plan = shardPlans.values.firstOrNull { p ->
            p.shards.any { it.workerId == workerId }
        } ?: return
        val doneSet = shardDone.getOrPut(plan.planId) { ConcurrentHashMap.newKeySet() }
        doneSet.add(workerId)
        if (doneSet.size >= plan.shards.size) {
            val merged = JSONObject()
            stats?.let { s ->
                merged.put("tokens", s.optInt("tokens"))
                merged.put("tps", s.optDouble("tps"))
                merged.put("ttftMs", s.optLong("ttftMs"))
                merged.put("elapsedMs", s.optLong("elapsedMs"))
            }
            merged.put("shards", doneSet.size)
            task.events.put(JSONObject().put("type", "done").put("status", status).put("stats", merged))
        }
    }

    /**
     * Start a multi-node single task: compute a plan, create the task, and
     * push a `shard` event to every assigned worker's poll (redesign §4.3).
     * Each shard event names the next worker in the chain so activations can
     * be forwarded. Returns the task id, or null when no plan fits.
     */
    fun startShardedTask(
        model: String,
        workerIds: List<String>,
        system: String,
        pairs: List<Pair<String, String>>,
        temperature: Double = 0.7,
        maxTokens: Int = 1024
    ): String? {
        val plan = computeShardPlan(model, workerIds) ?: return null
        val worker = workers[plan.leader] ?: return null
        val messages = JSONArray()
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        pairs.forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        val task = WorkerTask(
            id = "t_" + UUID.randomUUID().toString().replace("-", "").take(6),
            workerId = plan.leader,
            model = model,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens.coerceIn(1, 4096),
            createdAt = System.currentTimeMillis()
        )
        tasks[task.id] = task
        shardDone[plan.planId] = ConcurrentHashMap.newKeySet()
        // Push a shard event to each worker in plan order.
        val ordered = plan.shards.sortedBy { it.fromLayer }
        ordered.forEachIndexed { index, shard ->
            val nextWorkerId = ordered.getOrNull(index + 1)?.workerId ?: ""
            val pseudo = WorkerTask(
                id = "shard_${task.id}_${shard.workerId}",
                workerId = shard.workerId,
                model = model,
                messages = task.messages,
                temperature = temperature,
                maxTokens = maxTokens,
                createdAt = System.currentTimeMillis()
            )
            // Carry the real task so the worker can run it.
            pseudo.shardEvent = JSONObject()
                .put("type", "shard")
                .put("taskId", task.id)
                .put("model", model)
                .put("messages", messages)
                .put("fromLayer", shard.fromLayer)
                .put("toLayer", shard.toLayer)
                .put("leader", shard.workerId == plan.leader)
                .put("nextWorkerId", nextWorkerId)
                .put("maxTokens", task.maxTokens)
                .put("temperature", task.temperature)
            workerQueueFor(shard.workerId).add(pseudo)
        }
        return task.id
    }

    /**
     * Redesign §4.5: reassign a dead shard to another online worker, or fail
     * the task when nobody can take it. Called by the failover sweep.
     */
    private fun reassignShard(taskId: String, deadWorkerId: String): Boolean {
        val task = tasks[taskId] ?: return false
        val plan = shardPlans.values.firstOrNull { p ->
            p.shards.any { it.workerId == deadWorkerId }
        } ?: return false
        val deadShard = plan.shards.firstOrNull { it.workerId == deadWorkerId } ?: return false
        val now = System.currentTimeMillis()
        val replacement = workers.values.firstOrNull { w ->
            w.online(now) && !w.paused && w.id != deadWorkerId &&
                plan.shards.none { it.workerId == w.id }
        } ?: return false
        val newShards = plan.shards.map { if (it.workerId == deadWorkerId) it.copy(workerId = replacement.id) else it }
        val newPlan = plan.copy(shards = newShards)
        shardPlans[newPlan.planId] = newPlan
        shardDone[newPlan.planId] = ConcurrentHashMap.newKeySet()
        val ordered = newPlan.shards.sortedBy { it.fromLayer }
        val idx = ordered.indexOfFirst { it.workerId == replacement.id }
        val nextWorkerId = ordered.getOrNull(idx + 1)?.workerId ?: ""
        val evt = JSONObject()
            .put("type", "shard")
            .put("taskId", taskId)
            .put("model", newPlan.model)
            .put("messages", task.messages)
            .put("fromLayer", deadShard.fromLayer)
            .put("toLayer", deadShard.toLayer)
            .put("leader", replacement.id == newPlan.leader)
            .put("nextWorkerId", nextWorkerId)
            .put("maxTokens", task.maxTokens)
            .put("temperature", task.temperature)
        val pseudo = WorkerTask(
            id = "shard_${taskId}_${replacement.id}",
            workerId = replacement.id,
            model = newPlan.model,
            messages = task.messages,
            temperature = task.temperature,
            maxTokens = task.maxTokens,
            createdAt = System.currentTimeMillis()
        )
        pseudo.shardEvent = evt
        workerQueueFor(replacement.id).add(pseudo)
        return true
    }

    /**
     * Failover sweep: for every sharded task, check each shard worker is still
     * online. Dead shards are reassigned when possible; otherwise the task is
     * failed so the console is never stuck. Cheap — called from the poll
     * keepalive.
     */
    private fun sweepShardFailover() {
        val now = System.currentTimeMillis()
        val plans = shardPlans.values.toList()
        for (plan in plans) {
            val task = tasks.values.firstOrNull { t ->
                shardPlans.values.any { p -> p.planId == plan.planId } &&
                    (t.workerId == plan.leader)
            } ?: continue
            for (shard in plan.shards) {
                val w = workers[shard.workerId]
                val dead = w == null || !w.online(now)
                if (dead) {
                    val reassigned = reassignShard(task.id, shard.workerId)
                    if (!reassigned) {
                        task.events.put(
                            JSONObject().put("type", "done").put("status", "failed")
                                .put("error", "shard worker ${shard.workerId} died and no replacement was available")
                        )
                    }
                }
            }
        }
    }

    /** One GPU node offered to the app's "run on node" routing (chat/workspace). */
    data class WorkerPick(
        val id: String,
        val name: String,
        val model: String,
        /** The phone's assignment for this node ("" = the node's own pick). */
        val assigned: String = "",
        /** Karen model files the node reported it can run (for per-turn picks). */
        val models: List<String> = emptyList(),
        val gflops: Double,
        val state: String,
        val online: Boolean
    )

    /** Result of an app-side task run. `noWorker` ⇒ nothing could take it. */
    data class WorkerRun(
        val ok: Boolean,
        val noWorker: Boolean,
        val text: String,
        val error: String
    )

    /**
     * Live GPU nodes for the chat/workspace routing chip. Reads nodeSnapshot
     * (Compose state) so screens recompose as nodes come and go.
     */
    fun workerPicks(): List<WorkerPick> {
        val now = System.currentTimeMillis()
        return nodeSnapshot
            .filter { it.worker && it.workerId.isNotBlank() }
            .sortedWith(
                compareByDescending<NodeInfo> { now - it.lastSeen < WorkerInfo.ONLINE_WINDOW_MS }
                    .thenByDescending { it.gflops }
            )
            .map {
                val online = now - it.lastSeen < WorkerInfo.ONLINE_WINDOW_MS
                // Assignment + model list live on the worker record (not the node
                // list); reads are fresh even when nodeSnapshot lags a beat.
                val w = workers[it.workerId]
                WorkerPick(
                    id = it.workerId,
                    name = it.name,
                    model = it.workerModel,
                    assigned = w?.defaultModel ?: "",
                    models = w?.models ?: emptyList(),
                    gflops = it.gflops,
                    state = if (online) it.workerState.ifBlank { "idle" } else "offline",
                    online = online
                )
            }
    }

    /**
     * App-side task submission (chat/workspace "run on node"): assign one task
     * to a GPU node and consume its event stream (status → delta → done).
     * Blocking — call from Dispatchers.IO. `sel` is "auto" or a worker id from
     * [workerPicks]; `model` is the per-turn pick ("" = the node's assignment).
     * onStatus/onDelta fire on the calling thread. Returns noWorker=true when
     * nothing could take it, so the caller can fall back to running on this
     * phone.
     */
    fun runOnWorker(
        sel: String,
        system: String,
        pairs: List<Pair<String, String>>,
        temperature: Double = 0.7,
        maxTokens: Int = 1024,
        model: String = "",
        onStatus: ((String) -> Unit)? = null,
        onDelta: ((String) -> Unit)? = null,
        cancelled: () -> Boolean = { false }
    ): WorkerRun {
        if (sel.isBlank()) return WorkerRun(false, true, "", "no node selected")
        val worker = resolveWorker(sel, "")
        if (worker == null) {
            val w = workers[sel]
            val why = when {
                w?.paused == true -> "node \"${w.name}\" is paused — resume it in Network Nodes"
                w != null && !w.online(System.currentTimeMillis()) -> "node \"${w.name}\" went offline"
                sel != "auto" -> "that node is no longer connected"
                else -> "no GPU node is online right now"
            }
            return WorkerRun(false, true, "", why)
        }
        val messages = JSONArray()
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        pairs.forEach { (role, content) ->
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        // A per-turn pick the node no longer lists is named, never silently
        // swapped: the chip offers the node's reported models, and this catches
        // a stale pick between that report and the send.
        if (model.isNotBlank() && worker.models.isNotEmpty() &&
            worker.models.none { it.equals(model.trim(), ignoreCase = true) } &&
            !worker.loadedModel.equals(model.trim(), ignoreCase = true)
        ) {
            return WorkerRun(false, false, "", "node \"${worker.name}\" does not offer model \"${model.trim()}\" — pick another model for it")
        }
        val task = WorkerTask(
            id = "t_" + UUID.randomUUID().toString().replace("-", "").take(6),
            workerId = worker.id,
            model = if (model.isNotBlank()) model.trim().take(90) else worker.defaultModel,
            messages = messages,
            temperature = temperature,
            maxTokens = maxTokens.coerceIn(1, 4096),
            createdAt = System.currentTimeMillis()
        )
        tasks[task.id] = task
        workerQueueFor(worker.id).add(task)
        worker.state = "assigned"
        worker.currentTask = task.id
        touchNodeWorker(worker.ip, "assigned", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
        val buf = StringBuilder()
        try {
            var lastActivity = System.currentTimeMillis()
            while (true) {
                val evt = task.events.poll(1, TimeUnit.SECONDS)
                if (evt != null) {
                    lastActivity = System.currentTimeMillis()
                    when (evt.optString("type")) {
                        "status" -> onStatus?.invoke(
                            evt.optString("text").ifBlank { evt.optString("phase") }.ifBlank { "working…" }
                        )
                        "delta" -> {
                            val piece = evt.optString("content")
                            if (piece.isNotEmpty()) {
                                buf.append(piece)
                                onDelta?.invoke(piece)
                            }
                        }
                        "done" -> {
                            val status = evt.optString("status")
                            return if (status == "completed") {
                                WorkerRun(true, false, buf.toString(), "")
                            } else {
                                WorkerRun(
                                    false, false, buf.toString(),
                                    evt.optString("error").ifBlank { "the node reported a failure" }
                                )
                            }
                        }
                    }
                } else if (cancelled()) {
                    task.cancelRequested = true
                    task.state = "cancelled"
                    return WorkerRun(false, false, buf.toString(), "cancelled")
                } else if (System.currentTimeMillis() - lastActivity > WorkerInfo.TASK_STALL_MS) {
                    task.cancelRequested = true
                    task.state = "cancelled"
                    return WorkerRun(false, false, buf.toString(), "the node went quiet (tab closed?) — task aborted")
                }
            }
        } finally {
            tasks.remove(task.id)
            if (worker.currentTask == task.id) {
                worker.state = if (worker.paused) "paused" else "idle"
                worker.currentTask = ""
                touchNodeWorker(worker.ip, worker.state, worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
            }
        }
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
            // A touch is a sign of life: keepalives, progress and stream chunks
            // all land here, so a 5-minute poll hold never looks offline.
            lastSeen = System.currentTimeMillis(),
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
                        .put("paused", w.paused)
                )
            }
        return JSONObject().put("object", "list").put("data", data)
    }

    /** "auto" picks the best online, unpaused worker; otherwise the id must match exactly. */
    private fun resolveWorker(sel: String, model: String): WorkerInfo? {
        val now = System.currentTimeMillis()
        if (sel == "auto") {
            return workers.values
                .filter { w ->
                    w.online(now) && !w.paused && (
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
        return workers[sel]?.takeIf { !it.paused }
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
            // Only a deliberate pick on the worker page (it sets the key when the
            // user changes the select) rewrites the assignment — an idle rejoin
            // must not clobber a model the phone assigned meanwhile.
            defaultModel = if (body.has("defaultModel")) {
                body.optString("defaultModel", "").trim().take(90)
            } else {
                prev?.defaultModel ?: ""
            },
            models = models.take(400),
            joinedAt = prev?.joinedAt ?: now,
            lastSeenAt = now,
            state = if (busyTask != null) (prev?.state ?: "assigned")
                    else (if (prev?.paused == true) "paused" else "idle"),
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
            telemetryAt = prev?.telemetryAt ?: 0L,
            paused = prev?.paused ?: false,
            assignedAt = prev?.assignedAt ?: 0L,
            // A body that names the model is the worker page writing its own
            // pick (the phone's assignment is no longer the source).
            assignedByPhone = if (body.has("defaultModel")) false else (prev?.assignedByPhone ?: false)
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
                // The current assignment rides back so the page can show it the
                // moment it joins (before the first poll keepalive arrives).
                .put("defaultModel", worker.defaultModel)
                .put("assignedAt", worker.assignedAt)
                .put("assignedByPhone", worker.assignedByPhone)
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
            val st = if (worker.paused) "paused" else "idle"
            worker.state = st
            touchNodeWorker(worker.ip, st, worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
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
            // The keepalive doubles as the assignment channel: each one carries
            // the node's current model + when the phone last set it, so the
            // worker page applies it and labels "phone assigned" (same bytes on
            // the wire as the old bare ": ping" comment — one 30 s cadence).
            val writeAssigned = {
                val w = workers[workerId] ?: worker
                out.write(
                    (
                        "event: assigned\ndata: " +
                            JSONObject().put("model", w.defaultModel).put("at", w.assignedAt).put("byPhone", w.assignedByPhone) +
                            "\n\n"
                        ).toByteArray(Charsets.UTF_8)
                )
                out.flush()
            }
            writeAssigned()
            while (System.currentTimeMillis() < deadline) {
                // 30 s: keepalive cadence only — tasks still hand over the
                // instant they are queued (poll returns early).
                val task = queue.poll(30, TimeUnit.SECONDS)
                if (task != null) {
                    if (task.shardEvent != null) {
                        // Redesign §4: shard assignment — deliver as event: shard.
                        val se = task.shardEvent
                        val realTaskId = se?.optString("taskId") ?: task.id
                        val at = System.currentTimeMillis()
                        worker.lastSeenAt = at
                        worker.state = "assigned"
                        worker.currentTask = realTaskId
                        touchNodeWorker(worker.ip, "assigned", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
                        out.write(
                            ("event: shard\ndata: " + (se?.toString() ?: "{}") + "\n\n").toByteArray(Charsets.UTF_8)
                        )
                        out.flush()
                        return
                    }
                    if (task.activationEvent != null) {
                        // Redesign §4: relayed hidden state from the previous shard.
                        val at = System.currentTimeMillis()
                        worker.lastSeenAt = at
                        out.write(
                            ("event: activation\ndata: " + (task.activationEvent?.toString() ?: "{}") + "\n\n").toByteArray(Charsets.UTF_8)
                        )
                        out.flush()
                        // Keep holding the poll (activations don't end the hold).
                        continue
                    }
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
                // The keepalive is also the app-visible heartbeat: without
                // this touch a 5-minute hold would look offline in Network
                // Nodes / the chat chip after 90 s.
                touchNodeWorker(worker.ip, if (worker.paused) "paused" else "idle", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
                // Redesign §4.5: cheap periodic failover sweep for sharded tasks.
                sweepShardFailover()
                writeAssigned()
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
        // Long generations keep the node fresh in the app-visible list too.
        touchNodeWorker(worker.ip, "running", worker.loadedModel.ifBlank { worker.defaultModel }, worker.id)
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
            val paused = workers[sel]?.paused == true
            writeJson(
                socket, 503,
                JSONObject().put(
                    "error",
                    errorObj(
                        if (paused) "node \"${workers[sel]?.name ?: sel}\" is paused — resume it (Network Nodes on the phone, or Pause/Resume in the console) and retry"
                        else "no worker is online — open the dashboard on a laptop/desktop with WebGPU; it joins automatically as a slave node once the token is saved"
                    )
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

    /**
     * SSE writer for a multi-node task: the console watches the leader's
     * tokens. Aborting the request (Stop) marks the whole task cancelled; every
     * shard's next stream/activation post returns `cancel: true`.
     */
    private fun streamShardedTask(socket: Socket, task: WorkerTask) {
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
                    .put("phase", "sharded").put("text", "multi-node plan started") + "\n\n"
            )
            relayTask(task, emitRaw)
        } catch (_: Exception) {
        } finally {
            tasks.remove(task.id)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Worker-routed non-streaming chat: waits for the task, returns one JSON reply. */
    private fun workerChatJson(socket: Socket, body: JSONObject) {        val sel = body.optString("worker", "").trim()
        val wanted = body.optString("model", "").trim()
        val messages = body.optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) {
            writeJson(socket, 400, JSONObject().put("error", errorObj("messages[] required")))
            return
        }
        val worker = resolveWorker(sel, wanted)
        if (worker == null) {
            val paused = workers[sel]?.paused == true
            writeJson(
                socket, 503,
                JSONObject().put(
                    "error",
                    errorObj(
                        if (paused) "node \"${workers[sel]?.name ?: sel}\" is paused — resume it to send prompts"
                        else "no GPU worker is online"
                    )
                )
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

    /** "≈1.2 GB" style label for a file on disk (catalog entries carry theirs). */
    private fun sizeGbLabel(bytes: Long): String = when {
        bytes >= 1_000_000_000L ->
            String.format(java.util.Locale.US, "≈%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000L ->
            String.format(java.util.Locale.US, "≈%.0f MB", bytes / 1_000_000.0)
        else ->
            String.format(java.util.Locale.US, "≈%.0f KB", bytes / 1000.0)
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
            weightStore = WeightStore(appCtx)
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
                method == "GET" && path == "/v1/karen/models" -> {
                    // Karen's model universe for the slave-node pages: weights
                    // installed on the phone first, then everything in the
                    // downloadable catalog. The page maps each entry to the
                    // browser (WebLLM) build that should actually run — the
                    // built-in web-llm catalog is not used as the source list.
                    val data = JSONArray()
                    val installedNames = UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
                    val runnableFiles = mutableMapOf<String, File>()
                    scanModelFiles(ctx).filter { it.runnable }.forEach { f ->
                        runnableFiles[f.name.lowercase()] = File(f.path)
                    }
                    val usedFiles = mutableSetOf<String>()
                    ModelDownloader.catalogModels().forEach { c ->
                        val base = c.fileName.substringBeforeLast('.')
                        val installed = runnableFiles.containsKey(c.fileName.lowercase()) ||
                            installedNames.any {
                                it.equals(c.name, true) || it.equals(c.fileName, true) ||
                                    it.equals(base, true)
                            }
                        if (installed) usedFiles.add(c.fileName.lowercase())
                        data.put(
                            JSONObject().put("name", c.name)
                                .put("file", c.fileName)
                                .put("size", c.sizeLabel)
                                .put("quant", c.quant)
                                .put("ram", c.ram)
                                .put("paramsB", c.paramsB.toDouble())
                                .put("reasoning", c.reasoning)
                                .put("embedding", c.embedding)
                                .put("installed", installed)
                                .put("source", "catalog")
                        )
                    }
                    // Runnable weights that are not in the catalog (imports,
                    // direct downloads) are still models this phone owns.
                    runnableFiles.forEach { (key, file) ->
                        if (key in usedFiles) return@forEach
                        data.put(
                            JSONObject().put("name", file.name)
                                .put("file", file.name)
                                .put("size", sizeGbLabel(file.length()))
                                .put("quant", "")
                                .put("ram", "")
                                .put("paramsB", 0.0)
                                .put("reasoning", false)
                                .put("embedding", false)
                                .put("installed", true)
                                .put("source", "file")
                        )
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

                // ---- Redesign §2: master-hosted weight store ----
                method == "GET" && path == "/v1/weights" -> {
                    val store = weightStore
                    val data = JSONArray()
                    if (store != null) {
                        store.listModels().forEach { slug ->
                            val m = store.readManifest(slug)
                            data.put(
                                JSONObject()
                                    .put("model", slug)
                                    .put("format", m?.format ?: "")
                                    .put("layers", m?.layers ?: 0)
                                    .put("vramRequiredMB", m?.vramRequiredMB ?: 0)
                                    .put("sizeBytes", m?.let { store.totalBytes(it) } ?: 0L)
                                    .put("artifacts", m?.artifacts?.size ?: 0)
                                    .put("source", "store")
                            )
                        }
                    }
                    writeJson(socket, 200, JSONObject().put("object", "list").put("data", data))
                }
                method == "GET" && path.startsWith("/v1/weights/") -> {
                    val store = weightStore
                    val rest = path.removePrefix("/v1/weights/")
                    val model = rest.substringBefore('/')
                    val file = rest.substringAfter("/", "")
                    val range = headers["range"]
                    if (store != null && model.isNotBlank()) {
                        if (file.isEmpty() || file == "manifest.json") {
                            val m = store.readManifest(model) ?: store.buildManifest(model)
                            if (m != null) {
                                val data = JSONArray()
                                m.artifacts.forEach { a ->
                                    data.put(
                                        JSONObject()
                                            .put("file", a.file)
                                            .put("size", a.size)
                                            .put("sha256", a.sha256)
                                    )
                                }
                                writeJson(
                                    socket, 200,
                                    JSONObject()
                                        .put("model", m.model)
                                        .put("format", m.format)
                                        .put("layers", m.layers)
                                        .put("vramRequiredMB", m.vramRequiredMB)
                                        .put("artifacts", data)
                                )
                            } else {
                                writeJson(socket, 404, JSONObject().put("error", errorObj("model not in store")))
                            }
                        } else {
                            val f = store.resolve(model, file)
                            if (f != null) {
                                val ct = when (file.substringAfterLast('.').lowercase()) {
                                    "json" -> "application/json"
                                    "wasm" -> "application/wasm"
                                    else -> "application/octet-stream"
                                }
                                writeBinaryRange(socket, f, ct, range)
                            } else {
                                writeJson(socket, 404, JSONObject().put("error", errorObj("file not found")))
                            }
                        }
                    } else {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("model required")))
                    }
                }

                // ---- Redesign §4: multi-node single-task coordination ----
                method == "POST" && path == "/v1/task/plan" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        val model = body.optString("model", "").trim()
                        val workerIds = body.optJSONArray("workerIds")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optString(i, "").trim().takeIf { it.isNotBlank() }
                            }
                        } ?: emptyList()
                        val plan = computeShardPlan(model, workerIds)
                        if (plan == null) {
                            writeJson(socket, 503, JSONObject().put("error", errorObj("no multi-node plan fits — use a single node")))
                        } else {
                            val arr = JSONArray()
                            plan.shards.forEach { s ->
                                arr.put(
                                    JSONObject()
                                        .put("workerId", s.workerId)
                                        .put("fromLayer", s.fromLayer)
                                        .put("toLayer", s.toLayer)
                                )
                            }
                            writeJson(
                                socket, 200,
                                JSONObject()
                                    .put("ok", true)
                                    .put("planId", plan.planId)
                                    .put("model", plan.model)
                                    .put("leader", plan.leader)
                                    .put("shards", arr)
                            )
                        }
                    }
                }
                method == "POST" && path == "/v1/task/shard/lease" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        val taskId = body.optString("taskId", "").trim()
                        val workerId = body.optString("workerId", "").trim()
                        val lease = grantShardLease(taskId, workerId)
                        if (lease == null) {
                            writeJson(socket, 409, JSONObject().put("error", errorObj("no shard available for this worker")))
                        } else {
                            // ShardLease stores shardId as "<planId>:<workerId>".
                            val plan = shardPlans[lease.shardId.substringBefore(':')]
                            val shard = plan?.shards?.firstOrNull { it.workerId == workerId }
                            val store = weightStore
                            val m = plan?.model?.let { store?.readManifest(it) }
                            val chunks = JSONArray()
                            m?.artifacts?.forEach { a ->
                                chunks.put(
                                    JSONObject()
                                        .put("file", a.file)
                                        .put("size", a.size)
                                        .put("sha256", a.sha256)
                                        .put("url", "/v1/weights/${plan?.model}/${a.file}")
                                )
                            }
                            writeJson(
                                socket, 200,
                                JSONObject()
                                    .put("ok", true)
                                    .put("leaseToken", lease.token)
                                    .put("fromLayer", shard?.fromLayer ?: 0)
                                    .put("toLayer", shard?.toLayer ?: 0)
                                    .put("leader", plan?.leader == workerId)
                                    .put("chunks", chunks)
                            )
                        }
                    }
                }
                method == "POST" && path == "/v1/task/activation" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        val taskId = body.optString("taskId", "").trim()
                        val toWorkerId = body.optString("toWorkerId", "").trim()
                        val seq = body.optLong("seq", -1L)
                        val data = body.optString("data", "")
                        val ok = relayActivation(taskId, toWorkerId, seq, data)
                        val task = tasks[taskId]
                        writeJson(socket, if (ok) 200 else 404, JSONObject().put("ok", ok).put("cancel", task?.cancelRequested ?: false))
                    }
                }
                method == "POST" && path == "/v1/task/shard/stream" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        val taskId = body.optString("taskId", "").trim()
                        val workerId = body.optString("workerId", "").trim()
                        val seq = body.optLong("seq", -1L)
                        val text = body.optString("text", "")
                        shardProgress(taskId, workerId, seq, text)
                        val task = tasks[taskId]
                        writeJson(socket, 200, JSONObject().put("ok", true).put("cancel", task?.cancelRequested ?: true))
                    }
                }
                method == "POST" && path == "/v1/task/shard/done" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        val taskId = body.optString("taskId", "").trim()
                        val workerId = body.optString("workerId", "").trim()
                        val seq = body.optLong("seq", -1L)
                        val status = body.optString("status", "completed")
                        val stats = body.optJSONObject("stats")
                        shardDone(taskId, workerId, seq, status, stats)
                        writeJson(socket, 200, JSONObject().put("ok", true))
                    }
                }
                method == "POST" && path == "/v1/task/sharded" -> {
                    // Console/app entry point for a multi-node single task.
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        val model = body.optString("model", "").trim()
                        val workerIds = body.optJSONArray("workerIds")?.let { arr ->
                            (0 until arr.length()).mapNotNull { i ->
                                arr.optString(i, "").trim().takeIf { it.isNotBlank() }
                            }
                        } ?: emptyList()
                        val messages = body.optJSONArray("messages") ?: JSONArray()
                        val pairs = mutableListOf<Pair<String, String>>()
                        for (i in 0 until messages.length()) {
                            val m = messages.optJSONObject(i) ?: continue
                            pairs.add(m.optString("role", "user") to m.optString("content", ""))
                        }
                        val taskId = startShardedTask(
                            model = model,
                            workerIds = workerIds,
                            system = body.optString("system", ""),
                            pairs = pairs,
                            temperature = body.optDouble("temperature", 0.7),
                            maxTokens = body.optInt("maxTokens", 1024)
                        )
                        if (taskId == null) {
                            writeJson(socket, 503, JSONObject().put("error", errorObj("no multi-node plan fits — use fewer shards or a single node")))
                        } else if (body.optBoolean("stream", false)) {
                            // Console watches the leader's tokens as they stream.
                            val task = tasks[taskId]
                            if (task == null) {
                                writeJson(socket, 503, JSONObject().put("error", errorObj("sharded task vanished before streaming")))
                            } else {
                                streamShardedTask(socket, task)
                            }
                        } else {
                            writeJson(socket, 200, JSONObject().put("ok", true).put("taskId", taskId))
                        }
                    }
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
                method == "POST" && path == "/v1/workers/pause" -> {
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        val id = body.optString("id", body.optString("workerId", ""))
                        val paused = body.optBoolean("paused", true)
                        val ok = setWorkerPaused(id, paused)
                        writeJson(socket, 200, JSONObject().put("ok", ok))
                    }
                }
                method == "POST" && path == "/v1/workers/model" -> {
                    // Master-side assignment: every node turn without its own
                    // model runs this until either side changes it.
                    if (body == null) {
                        writeJson(socket, 400, JSONObject().put("error", errorObj("JSON body required")))
                    } else {
                        val id = body.optString("id", body.optString("workerId", ""))
                        val model = body.optString("model", "")
                        val ok = assignWorkerModel(id, model)
                        writeJson(socket, 200, JSONObject().put("ok", ok))
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
            // Pages are generated by the app: after an update the phone must
            // serve the new worker/console page, never a browser-cached one.
            "Cache-Control: no-store\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = socket.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    /**
     * Serve a byte range of a file (HTTP 206 Partial Content). Workers use this
     * to fetch weight chunks resumably (redesign §2.2 / §3). Without a Range
     * header the whole file is sent with a 200.
     */
    private fun writeBinaryRange(socket: Socket, file: File, contentType: String, range: String?) {
        val length = file.length()
        val out = socket.getOutputStream()
        try {
            // Parse "bytes=start-end" (end optional, may be "").
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range.orEmpty())
            val start = m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val end = m?.groupValues?.get(2)?.toLongOrNull() ?: (length - 1)
            val safeStart = start.coerceIn(0, length - 1)
            val safeEnd = end.coerceIn(safeStart, length - 1)
            val partial = m != null && (safeEnd - safeStart + 1) < length
            val status = if (partial) "206 Partial Content" else "200 OK"
            val contentLength = safeEnd - safeStart + 1
            val head = StringBuilder()
            head.append("HTTP/1.1 $status\r\n")
            head.append("Content-Type: $contentType\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            head.append("Cache-Control: no-store\r\n")
            head.append("Content-Length: $contentLength\r\n")
            if (partial) {
                head.append("Content-Range: bytes $safeStart-$safeEnd/$length\r\n")
            }
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            file.inputStream().use { input ->
                input.skip(safeStart)
                val buf = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            out.flush()
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }
    private fun authHintPage(): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Karen — Token Required</title>
<style>
  :root {
    --bg-base: #0a0c12;
    --bg-surface: #121520;
    --border-subtle: #1e2638;
    --accent-green: #10A37F;
    --accent-blue: #38bdf8;
    --text-primary: #f1f5f9;
    --text-secondary: #94a3b8;
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
         background: var(--bg-base); color: var(--text-primary); min-height: 100vh; display: flex;
         align-items: center; justify-content: center; margin: 0; padding: 20px; }
  .card { background: var(--bg-surface); border: 1px solid var(--border-subtle); border-radius: 16px;
          padding: 32px; max-width: 480px; width: 100%; box-shadow: 0 10px 30px rgba(0,0,0,0.4); }
  h1 { font-size: 20px; font-weight: 700; margin: 0 0 10px; color: #fff; }
  p { color: var(--text-secondary); font-size: 13.5px; line-height: 1.6; margin: 10px 0; }
  code { font-family: 'JetBrains Mono', monospace; background: #0c0e12;
         border: 1px solid #283042; border-radius: 6px; padding: 2px 7px; font-size: 12.5px; color: #38bdf8; }
  .badge { display: inline-block; background: rgba(245,158,11,0.12); color: #f59e0b;
           border: 1px solid rgba(245,158,11,0.3); border-radius: 20px; padding: 4px 14px;
           font-size: 11px; font-weight: 700; letter-spacing: 0.5px; margin-bottom: 16px; }
  .actions { display: flex; gap: 10px; margin-top: 20px; flex-wrap: wrap; }
  .btn { display: inline-block; text-decoration: none; padding: 9px 18px; border-radius: 9px;
         font-size: 13px; font-weight: 600; transition: all .15s ease; }
  .btn-primary { background: var(--accent-green); color: #fff; border: 1px solid var(--accent-green); }
  .btn-primary:hover { background: #0d8a6a; }
  .btn-secondary { background: rgba(255,255,255,0.04); color: var(--text-secondary); border: 1px solid var(--border-subtle); }
  .btn-secondary:hover { background: rgba(255,255,255,0.08); color: #fff; }
</style>
</head>
<body>
<div class="card">
  <div class="badge">TOKEN REQUIRED</div>
  <h1>Karen Local Server</h1>
  <p>Model and inference API routes require bearer authentication. Web interface pages do not:</p>
  <div class="actions">
    <a href="/dashboard" class="btn btn-primary">Open Dashboard ↗</a>
    <a href="/" class="btn btn-secondary">Open Slave Node ↗</a>
  </div>
  <p style="margin-top:20px;font-size:12.5px;color:#64748b">
    For API calls, pass header <code>Authorization: Bearer &lt;token&gt;</code>.<br>
    Find your token in Karen App → Settings → Local Server.
  </p>
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
  :root {
    --bg-base: #0a0c12;
    --bg-surface: #121520;
    --bg-surface-elevated: #161b28;
    --bg-input: #191f2e;
    --border-subtle: #1e2638;
    --border-strong: #2a354c;
    --accent-green: #10A37F;
    --accent-green-hover: #0d8a6a;
    --accent-blue: #38bdf8;
    --text-primary: #f1f5f9;
    --text-secondary: #94a3b8;
    --text-muted: #64748b;
  }
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
    background: var(--bg-base);
    color: var(--text-primary);
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }
  /* Scrollbar */
  ::-webkit-scrollbar { width: 6px; height: 6px; }
  ::-webkit-scrollbar-track { background: transparent; }
  ::-webkit-scrollbar-thumb { background: #263044; border-radius: 4px; }
  ::-webkit-scrollbar-thumb:hover { background: #354360; }

  /* App Header */
  header {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 12px 24px;
    background: var(--bg-surface);
    border-bottom: 1px solid var(--border-subtle);
    flex: none;
    box-shadow: 0 2px 10px rgba(0, 0, 0, 0.25);
  }
  .logo {
    width: 38px;
    height: 38px;
    background: linear-gradient(135deg, #10A37F 0%, #0d8a6a 100%);
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 800;
    color: #fff;
    font-size: 18px;
    flex: none;
    box-shadow: 0 2px 8px rgba(16, 163, 127, 0.35);
  }
  .header-info { display: flex; flex-direction: column; gap: 2px; }
  header h1 { font-size: 15px; font-weight: 700; color: #f8fafc; letter-spacing: -0.2px; }
  header .sub { font-size: 11.5px; color: var(--text-muted); }
  .header-actions {
    margin-left: auto;
    display: flex;
    align-items: center;
    gap: 12px;
  }
  .nav-switch-btn {
    text-decoration: none;
    font-size: 12px;
    font-weight: 600;
    color: var(--accent-blue);
    background: rgba(56, 189, 248, 0.08);
    border: 1px solid rgba(56, 189, 248, 0.25);
    padding: 5px 14px;
    border-radius: 20px;
    transition: all .2s ease;
    white-space: nowrap;
  }
  .nav-switch-btn:hover {
    background: rgba(56, 189, 248, 0.16);
    border-color: var(--accent-blue);
    color: #e0f2fe;
    transform: translateY(-1px);
  }
  .status {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 12px;
    color: var(--accent-green);
    white-space: nowrap;
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid var(--border-subtle);
    padding: 5px 12px;
    border-radius: 20px;
  }
  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: var(--accent-green);
    box-shadow: 0 0 8px rgba(16, 163, 127, 0.8);
    flex: none;
    transition: all .3s ease;
  }
  .dot.off {
    background: #f59e0b;
    box-shadow: 0 0 8px rgba(245, 158, 11, 0.6);
  }

  /* Form Elements */
  input, select, textarea {
    font-family: inherit;
    font-size: 13px;
    border-radius: 9px;
    border: 1px solid var(--border-strong);
    background: var(--bg-input);
    color: var(--text-primary);
    padding: 8px 12px;
    outline: none;
    transition: border-color .15s, box-shadow .15s;
  }
  input:focus, select:focus, textarea:focus {
    border-color: var(--accent-blue);
    box-shadow: 0 0 0 2px rgba(56, 189, 248, 0.15);
  }
  button {
    font-family: inherit;
    font-size: 13px;
    background: var(--accent-green);
    border: 1px solid var(--accent-green);
    color: #ffffff;
    font-weight: 600;
    cursor: pointer;
    padding: 8px 16px;
    border-radius: 9px;
    box-shadow: 0 2px 6px rgba(16, 163, 127, 0.2);
    transition: all .15s ease;
  }
  button:hover:not(:disabled) {
    background: var(--accent-green-hover);
    border-color: var(--accent-green-hover);
  }
  button.ghost {
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid var(--border-strong);
    color: var(--text-secondary);
    font-weight: 500;
    box-shadow: none;
  }
  button.ghost:hover:not(:disabled) {
    background: rgba(255, 255, 255, 0.08);
    border-color: #3b4964;
    color: #f8fafc;
  }
  button:disabled {
    opacity: .4;
    cursor: default;
    pointer-events: none;
  }
  input[type=checkbox] {
    width: 17px;
    height: 17px;
    accent-color: var(--accent-green);
    cursor: pointer;
    border-radius: 4px;
  }

  /* Shared Accordion / Details */
  details.files {
    background: var(--bg-surface);
    border-bottom: 1px solid var(--border-subtle);
    flex: none;
  }
  details.files summary {
    cursor: pointer;
    padding: 11px 24px;
    font-size: 12.5px;
    font-weight: 600;
    color: var(--text-secondary);
    list-style: none;
    display: flex;
    align-items: center;
    justify-content: space-between;
    user-select: none;
    transition: background .15s, color .15s;
  }
  details.files summary::-webkit-details-marker { display: none; }
  details.files summary:hover {
    color: var(--text-primary);
    background: rgba(255, 255, 255, 0.02);
  }
  details.files summary::after {
    content: '';
    display: inline-block;
    width: 6px;
    height: 6px;
    border-right: 2px solid #64748b;
    border-bottom: 2px solid #64748b;
    transform: rotate(45deg);
    transition: transform .2s ease;
    margin-left: 12px;
    flex: none;
  }
  details.files[open] summary::after {
    transform: rotate(-135deg);
  }
  .filelist {
    max-height: 280px;
    overflow-y: auto;
    padding: 6px 24px 14px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .frow {
    display: flex;
    align-items: center;
    gap: 10px;
    flex-wrap: wrap;
    padding: 10px 14px;
    border: 1px solid var(--border-subtle);
    border-radius: 10px;
    background: var(--bg-surface-elevated);
    transition: border-color .15s, background .15s;
  }
  .frow:hover {
    border-color: var(--border-strong);
    background: #192030;
  }
  .fname {
    flex: 1;
    min-width: 140px;
    font-family: 'JetBrains Mono', monospace;
    font-size: 12px;
    color: #f1f5f9;
    word-break: break-all;
  }
  .fmeta {
    font-size: 11.5px;
    color: var(--text-secondary);
    font-family: 'JetBrains Mono', monospace;
    white-space: nowrap;
  }
  .badge {
    font-size: 10.5px;
    font-weight: 600;
    border-radius: 6px;
    padding: 3px 8px;
    white-space: nowrap;
    letter-spacing: 0.2px;
  }
  .badge.ok {
    background: rgba(16, 163, 127, 0.15);
    color: var(--accent-green);
    border: 1px solid rgba(16, 163, 127, 0.3);
  }
  .badge.mid {
    background: rgba(56, 189, 248, 0.12);
    color: var(--accent-blue);
    border: 1px solid rgba(56, 189, 248, 0.25);
  }
  .badge.off {
    background: rgba(148, 163, 184, 0.12);
    color: var(--text-secondary);
    border: 1px solid rgba(148, 163, 184, 0.2);
  }
  .frow button {
    padding: 5px 12px;
    font-size: 11.5px;
    border-radius: 7px;
  }
  .probe {
    font-size: 11.5px;
    color: var(--text-secondary);
    font-family: 'JetBrains Mono', monospace;
    padding: 5px 12px;
    background: rgba(0, 0, 0, 0.3);
    border-radius: 6px;
    border-left: 3px solid var(--accent-blue);
    word-break: break-word;
  }
  .meta {
    color: var(--text-muted);
    font-size: 11px;
    font-family: 'JetBrains Mono', monospace;
    margin: -4px 0 6px 6px;
  }
  .toast {
    position: fixed;
    bottom: 96px;
    left: 50%;
    transform: translateX(-50%) translateY(10px);
    background: rgba(18, 22, 32, 0.95);
    backdrop-filter: blur(12px);
    border: 1px solid rgba(56, 189, 248, 0.4);
    color: #e0f2fe;
    padding: 10px 22px;
    border-radius: 24px;
    font-size: 13px;
    font-weight: 500;
    opacity: 0;
    transition: opacity .25s ease, transform .25s ease;
    pointer-events: none;
    white-space: nowrap;
    box-shadow: 0 10px 25px rgba(0, 0, 0, 0.5);
    z-index: 9999;
  }
  .toast.show {
    opacity: 1;
    transform: translateX(-50%) translateY(0);
  }

  /* Console View Layout */
  body.console {
    height: 100vh;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  body.console .bar {
    display: flex;
    gap: 10px;
    align-items: center;
    flex-wrap: wrap;
    padding: 10px 24px;
    background: #0e1118;
    border-bottom: 1px solid var(--border-subtle);
    flex: none;
  }
  body.console main {
    flex: 1;
    overflow-y: auto;
    padding: 24px;
    display: flex;
    flex-direction: column;
    gap: 16px;
    background: var(--bg-base);
  }
  .msg {
    max-width: 80%;
    padding: 12px 18px;
    border-radius: 16px;
    font-size: 14px;
    line-height: 1.6;
    white-space: pre-wrap;
    word-break: break-word;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  }
  .msg.user {
    align-self: flex-end;
    background: var(--accent-green);
    color: #ffffff;
    border-bottom-right-radius: 4px;
    box-shadow: 0 4px 12px rgba(16, 163, 127, 0.25);
  }
  .msg.assistant {
    align-self: flex-start;
    background: var(--bg-surface-elevated);
    border: 1px solid var(--border-subtle);
    border-bottom-left-radius: 4px;
    color: #e2e8f0;
  }
  .msg.sys {
    align-self: center;
    background: rgba(22, 27, 40, 0.8);
    border: 1px dashed var(--border-strong);
    color: var(--text-secondary);
    font-size: 12px;
    text-align: center;
    max-width: 90%;
    padding: 8px 18px;
    border-radius: 20px;
    box-shadow: none;
    line-height: 1.5;
  }
  .hint {
    font-size: 11.5px;
    color: var(--text-muted);
    padding: 0 24px 10px;
    background: var(--bg-surface);
    flex: none;
  }
  footer {
    display: flex;
    gap: 10px;
    padding: 12px 24px 16px;
    background: var(--bg-surface);
    border-top: 1px solid var(--border-subtle);
    align-items: flex-end;
    flex: none;
  }
  footer textarea {
    flex: 1;
    resize: none;
    height: 52px;
    border-radius: 12px;
    background: #0f121a;
    border: 1px solid var(--border-strong);
    padding: 14px 16px;
    font-size: 14px;
    line-height: 1.45;
  }
  footer textarea:focus {
    border-color: var(--accent-blue);
    background: #131722;
  }
  footer button {
    height: 52px;
    padding: 0 22px;
    font-size: 13.5px;
    border-radius: 12px;
  }
  footer button#stop:not(:disabled) {
    background: rgba(239, 68, 68, 0.15);
    border-color: rgba(239, 68, 68, 0.4);
    color: #f87171;
  }
  footer button#stop:not(:disabled):hover {
    background: rgba(239, 68, 68, 0.25);
    border-color: #ef4444;
    color: #fca5a5;
  }

  /* Worker View Layout */
  body.worker {
    min-height: 100vh;
    display: flex;
    flex-direction: column;
    align-items: center;
    overflow-y: auto;
    background: #090b10;
  }
  body.worker header {
    width: 100%;
  }
  body.worker .bar,
  body.worker details.files,
  body.worker .worker-guide-card {
    width: calc(100% - 32px);
    max-width: 900px;
    margin: 14px auto 0;
  }
  body.worker .bar {
    background: var(--bg-surface);
    border: 1px solid var(--border-subtle);
    border-radius: 14px;
    padding: 16px 20px;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }
  .bar-tag {
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 0.5px;
    color: var(--accent-blue);
    background: rgba(56, 189, 248, 0.1);
    border: 1px solid rgba(56, 189, 248, 0.25);
    padding: 4px 10px;
    border-radius: 6px;
    white-space: nowrap;
  }
  body.worker details.files {
    background: var(--bg-surface);
    border: 1px solid var(--border-subtle);
    border-radius: 14px;
    overflow: hidden;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
  }
  body.worker details.files summary {
    padding: 14px 20px;
    font-size: 13.5px;
    font-weight: 700;
    color: var(--accent-blue);
    background: #141824;
    border-bottom: 1px solid var(--border-subtle);
  }
  body.worker .filelist {
    max-height: none;
    padding: 18px 20px;
    gap: 14px;
  }

  /* Worker Node Hero Section */
  .worker-hero-section {
    background: linear-gradient(180deg, #161b27 0%, #121622 100%);
    border: 1px solid var(--border-strong);
    border-radius: 12px;
    padding: 16px 18px;
    display: flex;
    flex-direction: column;
    gap: 14px;
    box-shadow: 0 4px 12px rgba(0,0,0,0.2);
  }
  .worker-status-banner {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .worker-status-label {
    font-size: 10.5px;
    font-weight: 700;
    letter-spacing: 0.6px;
    color: var(--text-muted);
  }
  #workerStatus {
    background: rgba(16, 163, 127, 0.1);
    border: 1px solid rgba(16, 163, 127, 0.35);
    color: var(--accent-green);
    padding: 12px 16px;
    border-radius: 10px;
    font-weight: 600;
    font-size: 13.5px;
    line-height: 1.5;
    white-space: normal;
    display: flex;
    align-items: center;
    gap: 10px;
  }
  #workerStatus::before {
    content: '';
    display: inline-block;
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: var(--accent-green);
    box-shadow: 0 0 10px rgba(16, 163, 127, 0.9);
    flex: none;
  }
  .worker-toggle-box {
    background: var(--bg-surface-elevated);
    border: 1px solid var(--border-strong);
    border-radius: 10px;
    padding: 12px 16px;
  }
  .worker-model-box {
    background: var(--bg-surface-elevated);
    border: 1px solid var(--border-strong);
    border-radius: 10px;
    padding: 12px 16px;
    display: flex;
    align-items: center;
    gap: 12px;
    flex-wrap: wrap;
  }
  #modelNote {
    background: rgba(56, 189, 248, 0.08);
    border: 1px solid rgba(56, 189, 248, 0.25);
    color: #8ed5ff;
    padding: 10px 14px;
    border-radius: 8px;
    font-size: 12px;
    line-height: 1.5;
  }
  #gpuAdapter {
    background: var(--bg-surface-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: 10px;
    padding: 12px 16px;
    color: #f1f5f9;
    font-size: 12px;
    font-weight: 500;
    white-space: normal;
  }
  #gpuNote {
    background: rgba(245, 158, 11, 0.08);
    border: 1px solid rgba(245, 158, 11, 0.25);
    padding: 12px 16px;
    border-radius: 10px;
    font-size: 12px;
    line-height: 1.55;
    white-space: normal;
  }
  #secureHint {
    background: rgba(245, 158, 11, 0.08);
    border: 1px solid rgba(245, 158, 11, 0.3);
    padding: 14px 18px;
    border-radius: 10px;
    font-size: 12px;
    line-height: 1.6;
    white-space: normal;
    margin-top: 6px;
  }

  /* Guide Cards for Slave Node Page */
  .worker-guide-card {
    background: var(--bg-surface);
    border: 1px solid var(--border-subtle);
    border-radius: 14px;
    padding: 20px 24px;
    margin-bottom: 36px !important;
    box-shadow: 0 4px 16px rgba(0, 0, 0, 0.25);
  }
  .guide-header {
    font-size: 13px;
    font-weight: 700;
    color: var(--text-secondary);
    letter-spacing: 0.5px;
    margin-bottom: 14px;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .guide-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    gap: 14px;
  }
  .guide-col {
    background: var(--bg-surface-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: 10px;
    padding: 14px 16px;
    display: flex;
    flex-direction: column;
    gap: 6px;
  }
  .guide-col-num {
    font-size: 11px;
    font-weight: 800;
    color: var(--accent-green);
    background: rgba(16, 163, 127, 0.12);
    width: 22px;
    height: 22px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
  }
  .guide-col-title { font-size: 13px; font-weight: 700; color: #f1f5f9; }
  .guide-col-desc { font-size: 12px; color: var(--text-secondary); line-height: 1.5; }

  /* Page Mode Toggles */
  body.console .workerOnly { display: none !important; }
  body.worker .consoleOnly { display: none !important; }
</style>
</head>
<body class="$mode">
<header>
  <div class="logo">K</div>
  <div class="header-info">
    <h1>${if (worker) "Karen Slave Node" else "Karen Dashboard"}</h1>
    <div class="sub">${if (worker) "Distributed GPU worker — prompts from the phone execute on this device's GPU" else "On-device model console — or run prompts on a slave node GPU"}</div>
  </div>
  <div class="header-actions">
    ${if (worker) """<a href="/dashboard" class="nav-switch-btn">Console Dashboard ↗</a>""" else """<a href="/" class="nav-switch-btn">Slave Node Page ↗</a>"""}
    <div class="status"><span class="dot" id="dot"></span><span id="statusText">connecting…</span></div>
  </div>
</header>
<div class="bar">
  <span class="bar-tag workerOnly">NODE ACCESS TOKEN</span>
  <input id="token" type="password" placeholder="Bearer token — Karen App → Settings → Local Server" style="flex:1;min-width:240px">
  <button class="ghost" onclick="saveToken()">Save</button>
  <select id="model" class="consoleOnly" style="min-width:180px"><option value="">(pick model)</option></select>
  <button class="ghost consoleOnly" onclick="loadModels()">Refresh</button>
  <select id="runner" class="consoleOnly" style="min-width:200px" onchange="runnerChanged()" title="Where the prompt runs — the node with the most GPU is listed first">
    <option value="">Runner: Phone (llama.cpp)</option>
    <option value="auto">Runner: Auto — fastest GPU online</option>
  </select>
  <select id="wmodel" class="consoleOnly" style="min-width:220px;display:none" onchange="runnerChanged()" title="Model on the selected slave node"></select>
  <button id="mm" class="ghost consoleOnly" onclick="toggleMulti()" title="Run one prompt across several slave nodes (layer shards)">Multi-node: off</button>
</div>
<details class="files consoleOnly" id="filesPanel">
  <summary id="filesSummary">On-device model files — loading…</summary>
  <div class="filelist" id="fileList"></div>
</details>
<details class="files" id="gpuPanel"${if (worker) " open" else ""}>
  <summary id="gpuSummary">GPU offload — checking this device…</summary>
  <div class="filelist">
    <div class="worker-hero-section workerOnly">
      <div class="worker-status-banner">
        <div class="worker-status-label">SLAVE NODE STATUS</div>
        <div class="fmeta" id="workerStatus">Slave node — save the token above to pair this device.</div>
      </div>
      <div class="frow worker-toggle-box" style="margin-top:2px">
        <label class="fmeta" style="display:flex;align-items:center;gap:10px;flex:1;min-width:260px;cursor:pointer">
          <input type="checkbox" id="gpuWorker" onclick="toggleWorker(this.checked)">
          <span>Work as a <b>slave node</b> — this device runs prompts sent by the phone (keep this tab open; joins automatically once paired)</span>
        </label>
      </div>
      <div class="frow worker-model-box">
        <label class="fmeta" for="workerModel" style="min-width:140px;color:#94a3b8">Model this node runs</label>
        <select id="workerModel" style="max-width:420px;flex:1" onchange="saveWorkerModel()"></select>
      </div>
      <div class="fmeta" id="modelNote" style="display:none;white-space:normal;color:#8ed5ff"></div>
    </div>
    <div class="fmeta" id="gpuAdapter"></div>
    <div class="fmeta" id="gpuNote" style="display:none;color:#8ed5ff"></div>
    <div class="frow">
      <span class="fmeta" style="min-width:150px;color:#94a3b8">GPU adapters offered</span>
      <span class="fmeta" id="adapterList" style="flex:1;white-space:normal;color:#e2e2e8">probing…</span>
      <button class="ghost" onclick="reprobeAdapters()">Probe again</button>
    </div>
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
    <div class="fmeta consoleOnly" id="slavePageHint">To make a device work as a slave node, open this address there: <a href="/" style="color:#38bdf8">$workerUrl</a></div>
    <div class="fmeta" id="secureHint" style="display:none;color:#f59e0b"></div>
  </div>
</details>
<details class="files consoleOnly" id="workersPanel">
  <summary id="workersSummary">Slave nodes — loading…</summary>
  <div class="filelist" id="workerList"></div>
</details>
<div class="worker-guide-card workerOnly">
  <div class="guide-header">DISTRIBUTED AI ARCHITECTURE</div>
  <div class="guide-grid">
    <div class="guide-col">
      <div class="guide-col-num">1</div>
      <div class="guide-col-title">Master Phone</div>
      <div class="guide-col-desc">Karen coordinates distributed tasks, routes prompts across available nodes, and manages conversational context.</div>
    </div>
    <div class="guide-col">
      <div class="guide-col-num">2</div>
      <div class="guide-col-title">Client WebGPU</div>
      <div class="guide-col-desc">This browser runs inference on this device's GPU using WebLLM with zero binary downloads or command-line setup.</div>
    </div>
    <div class="guide-col">
      <div class="guide-col-num">3</div>
      <div class="guide-col-title">100% Private LAN</div>
      <div class="guide-col-desc">All prompt traffic and weights operate entirely on your local Wi-Fi. Zero data ever touches any third-party cloud.</div>
    </div>
  </div>
</div>
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
var S = { token: localStorage.getItem('karen_token') || '', busy: false, ctrl: null, log: [], node: nodeName(), boost: localStorage.getItem('karen_gpu_boost') === '1', llm: null, llmMod: null, llmModel: '', adapter: null, device: null, benchAdapter: null, gpuName: '', gflops: 0,
  w: { on: localStorage.getItem('karen_worker') !== '0' && !!localStorage.getItem('karen_token'), saved: localStorage.getItem('karen_worker') === '1', master: false, id: localStorage.getItem('karen_worker_id') || '', model: localStorage.getItem('karen_worker_model') || '', loaded: '', engine: null, interrupted: false, loop: false, assignedAt: Number(localStorage.getItem('karen_assign_at') || 0), edit: false },
  wModels: [], wlist: [], wmap: {}, routed: localStorage.getItem('karen_runner') || '',
  multi: localStorage.getItem('karen_multi') === '1',
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
  // The poll identifies the worker by header (GET has no body); without this
  // the phone never matched the id and every poll 404'd into a re-register
  // loop — the loudest source of idle node chatter.
  if (S.w && S.w.id) { h['X-Karen-Worker'] = S.w.id; }
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
    } else if (PAGE === 'worker' && S.token && !S.w.master && !S.w.on) {
      // Pairing with slave mode off: still fill "Model this node runs" so the
      // pick is usable before (and after) joining.
      ensureWorkerModels();
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
function copyText(t) {
  // navigator.clipboard needs a secure context; on plain-http origins fall
  // back to execCommand so "copy" still works while the bypass is active.
  try {
    var ta = document.createElement('textarea');
    ta.value = t;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    var ok = document.execCommand('copy');
    document.body.removeChild(ta);
    return ok;
  } catch (e) { return false; }
}
function showBypassUndo(origin) {
  // WebGPU exists although this origin is NOT secure — the only way that
  // happens is the chrome://flags insecure-origin bypass. Show how to take
  // it back: remove this origin from that flag's list and set it to Disabled
  // (browsers block clickable chrome:// links, hence the copy buttons).
  var hint = el('secureHint');
  if (!hint || el('bypassUndoBtn')) { return; }
  var flagsUrl = 'chrome://flags/#unsafely-treat-insecure-origin-as-secure';
  hint.style.display = 'block';
  hint.innerHTML =
    '<b>WebGPU works here through the insecure-origin bypass</b> (' + origin + ' is a plain http:// address). Keep it, or take the browser back to normal any time: ' +
    '<button class="ghost" id="bypassUndoBtn" type="button">Undo the bypass (back to normal)</button>' +
    '<div id="bypassUndo" style="display:none;margin-top:8px">' +
    'Open <b>' + flagsUrl + '</b> (browsers block clickable chrome:// links — use the copy button), ' +
    'remove <code style="background:#0c0e12;border:1px solid #283042;border-radius:5px;padding:1px 5px">' + origin + '</code> from the list, ' +
    'set the flag back to <b>Disabled</b>, then press <b>Relaunch</b>. That removes the bypass completely — after it, WebGPU needs a trusted address again ' +
    '(the tunnel: tools\\karen-tunnel.ps1 → http://127.0.0.1:11434/). Nothing else on the phone changes; slave work resumes the moment WebGPU is available again.' +
    '<div style="margin-top:8px">' +
    '<button class="ghost" id="bypassCopyBtn" type="button">Copy the undo steps</button>' +
    '<button class="ghost" id="bypassCopyFlagBtn" type="button" style="margin-left:6px">Copy the flags address</button>' +
    '</div></div>';
  el('bypassUndoBtn').onclick = function () {
    var d = el('bypassUndo');
    d.style.display = (d.style.display === 'none') ? 'block' : 'none';
  };
  el('bypassCopyBtn').onclick = function () {
    var steps = 'Open ' + flagsUrl + ', remove ' + origin + ' from "Insecure origins treated as secure", set that flag back to Disabled, then Relaunch the browser.';
    toast(copyText(steps) ? 'Undo steps copied' : steps);
  };
  el('bypassCopyFlagBtn').onclick = function () {
    toast(copyText(flagsUrl) ? 'Flags address copied — paste it into the address bar' : flagsUrl);
  };
}
function adapterName(ad) {
  var info = (ad && ad.info) || {};
  return [info.vendor, info.architecture, info.device, info.description].filter(Boolean).join(' · ') || 'WebGPU adapter';
}
// Which GPUs can this page actually use? Chrome on Windows ignores
// powerPreference (crbug 369219127 — it prints that warning in DevTools), so
// every request usually returns the one GPU the browser already runs on. All
// three preferences are still probed: on systems where the preference works
// the list has more than one entry, everywhere else the single honest answer
// is shown together with the way to move the browser onto the other GPU.
async function probeAdapters() {
  var out = { hp: null, lp: null, def: null, distinct: [], prefs: {}, pick: null };
  try { out.hp = await navigator.gpu.requestAdapter({ powerPreference: 'high-performance' }); } catch (e) {}
  try { out.lp = await navigator.gpu.requestAdapter({ powerPreference: 'low-power' }); } catch (e) {}
  try { out.def = await navigator.gpu.requestAdapter(); } catch (e) {}
  var seen = {};
  ['hp', 'lp', 'def'].forEach(function (k) {
    var a = out[k]; if (!a) { return; }
    var nm = adapterName(a);
    if (!seen[nm]) { seen[nm] = []; out.distinct.push(nm); }
    seen[nm].push(k === 'hp' ? 'high-performance' : (k === 'lp' ? 'low-power' : 'default'));
  });
  out.prefs = seen;
  out.pick = out.hp || out.lp || out.def;
  return out;
}
function renderAdapterInfo(pick) {
  var list = el('adapterList');
  if (list) {
    var parts = (pick.distinct || []).map(function (nm) {
      return '“' + nm + '” (' + (pick.prefs[nm] || []).join(' · ') + ')';
    });
    list.textContent = parts.length ? parts.join('  —  ') : 'none';
  }
  var gn = el('gpuNote');
  if (!gn) { return; }
  if ((pick.distinct || []).length <= 1) {
    // One adapter: the browser is locked to one GPU. On Windows the page cannot
    // switch — Chrome follows its own GPU — so name the real fix.
    gn.style.color = '#f59e0b';
    gn.textContent = 'Only one GPU is visible to this page. Chrome on Windows ignores the GPU a page asks for ' +
      '(crbug 369219127) and follows its own GPU — that is why a second card (e.g. a discrete NVIDIA/AMD GPU) can be missing here. ' +
      'To move the browser onto it: Windows Settings → System → Display → Graphics → add your browser → High performance ' +
      '(NVIDIA Control Panel: Manage 3D settings → Program Settings → your browser → High-performance NVIDIA processor), ' +
      'or chrome://flags/#force-high-performance-gpu — then relaunch and press “Probe again”. ' +
      'Slave-node inference asks for the high-performance GPU, so it follows the browser too.';
  } else {
    gn.style.color = '#8ed5ff';
    gn.textContent = 'Inference (slave-node tasks and GPU-boost chat) asks this browser for the high-performance GPU — ' +
      'that is “' + adapterName(pick.hp || pick.pick) + '”. The benchmark uses the same one.';
  }
  gn.style.display = 'block';
}
async function reprobeAdapters() {
  var list = el('adapterList');
  if (list) { list.textContent = 'probing…'; }
  if (!navigator.gpu) { if (list) { list.textContent = 'WebGPU is not exposed on this page'; } return; }
  try {
    var pick = await probeAdapters();
    if (!pick.pick) { if (list) { list.textContent = 'requestAdapter() found nothing'; } return; }
    S.adapter = pick.pick;
    S.device = null; S.benchAdapter = null;  // next bench starts from the fresh probe
    S.gpuName = adapterName(pick.pick);
    el('gpuSummary').textContent = 'GPU offload — this device: ' + S.gpuName;
    el('gpuAdapter').textContent = 'Adapter: ' + S.gpuName + ' — benchmark it below, or switch chat onto this GPU.';
    renderAdapterInfo(pick);
    reportNode();
    toast('GPU adapters re-probed');
  } catch (e) {
    if (list) { list.textContent = 'probe failed: ' + gpuErrText(e); }
  }
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
          '<code style="background:#0c0e12;border:1px solid #283042;border-radius:5px;padding:1px 5px">' + origin + '</code>, set it to Enabled, relaunch the browser. ' +
          'Once that bypass is active this page adds an "Undo the bypass" button that walks the browser back to normal in one look.';
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
    var pick = await probeAdapters();
    var ad = pick.pick;
    if (!ad) {
      el('gpuSummary').textContent = 'GPU offload — WebGPU is present, but this machine offered no GPU adapter';
      el('gpuAdapter').textContent = 'requestAdapter() found nothing — often software rendering or a blocklisted GPU (the phone keeps answering meanwhile).';
      if (el('adapterList')) { el('adapterList').textContent = 'none — requestAdapter() found nothing'; }
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
    S.gpuName = adapterName(ad);
    el('gpuSummary').textContent = 'GPU offload — this device: ' + S.gpuName;
    el('gpuAdapter').textContent = 'Adapter: ' + S.gpuName + ' — benchmark it below, or switch chat onto this GPU.';
    renderAdapterInfo(pick);
    toggleBoostText();
    if (!secure) { showBypassUndo(origin); }
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
    } else {
      // Paired but slave mode is off: still load the model list so the
      // "Model this node runs" pick is usable before joining.
      workerStatus('Slave node — off. Switch the box above on to start working.');
      ensureWorkerModels().catch(function () {});
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
async function benchDevice() {
  // One adapter can create exactly one device — ever. This page also hands
  // adapters to WebLLM chat, so a cached adapter may already be consumed by
  // the time the benchmark runs. Keep our first device, and otherwise ask
  // for a fresh adapter instead of reusing a possibly-consumed one.
  if (S.device) { return S.device; }
  var prefs = [{ powerPreference: 'high-performance' }, {}];
  var lastErr = null;
  for (var i = 0; i < prefs.length; i++) {
    var ad = null;
    try { ad = await navigator.gpu.requestAdapter(prefs[i]); } catch (e) { lastErr = e; }
    if (!ad) { continue; }
    try {
      var dev = await ad.requestDevice();
      S.device = dev;
      S.benchAdapter = ad;
      // If the GPU is ever lost (driver reset, tab throttled), drop the cache
      // so the next run acquires a brand-new adapter instead of a dead device.
      // The identity check keeps a later probe's fresh device from being
      // cleared by an old device's lost-event.
      dev.lost.then(function () { if (S.device === dev) { S.device = null; S.benchAdapter = null; } });
      return dev;
    } catch (e) { lastErr = e; }
  }
  throw lastErr || new Error('no adapter');
}
function gpuErrText(e) {
  var msg = (e && e.message) ? e.message : String(e);
  if (/consumed|already been used/i.test(msg)) {
    return 'the GPU device was already taken by an earlier run in this tab — reload the page, then start GPU work before another feature claims it';
  }
  return msg;
}
async function runBench() {
  var btn = el('benchBtn');
  btn.disabled = true;
  el('benchResult').textContent = 'running…';
  try {
    var device = await benchDevice();
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
    el('benchResult').textContent = gflops.toFixed(1) + ' GFLOPS (fp32 1024^3 x' + iters + ' · cell check ' + (okCell ? 'OK' : 'BAD ' + probe) + ') · ' + adapterName(S.benchAdapter || S.adapter);
    reportNode();
  } catch (e) {
    el('benchResult').textContent = 'benchmark failed: ' + gpuErrText(e);
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
function setModelNote(text) {
  var n = el('modelNote');
  if (!n) { return; }
  if (text) { n.textContent = text; n.style.display = 'block'; }
  else { n.textContent = ''; n.style.display = 'none'; }
}
// The phone can assign this node's model from Network Nodes (POST
// /v1/workers/model). The assignment rides every poll keepalive as
// {model, at, byPhone}; apply the newest one, put it in the select and label
// where it came from. `at` (a phone timestamp) is persisted, so a rejoin never
// re-applies an old value and never clobbers a pick made here afterwards.
function applyAssigned(m, at, byPhone, live) {
  if (!at || at < (S.w.assignedAt || 0)) { return; }
  if (at === (S.w.assignedAt || 0)) {
    // Already applied (same assignment). Re-label only when the value really
    // came from the phone and the select still shows it — a pick made on this
    // page writes the same slot without touching `at`, so it must never wear
    // the phone's label.
    var cur = el('workerModel');
    if (byPhone && m && cur && cur.value === m) { setModelNote('Assigned on the phone: ' + m); }
    return;
  }
  S.w.assignedAt = at;
  try { localStorage.setItem('karen_assign_at', String(at)); } catch (e) {}
  if (!m) {
    // Cleared on the phone: this node's own pick takes over again.
    setModelNote('');
    if (live) { toast('Phone cleared the model assignment'); }
    return;
  }
  var sel = el('workerModel');
  if (sel) {
    var found = false;
    for (var i = 0; i < sel.options.length; i++) { if (sel.options[i].value === m) { found = true; break; } }
    if (!found) {
      var o = document.createElement('option');
      o.value = m; o.textContent = m + ' · assigned by the phone';
      sel.appendChild(o);
    }
    sel.value = m;
  }
  S.w.model = m;
  S.w.edit = false;
  try { localStorage.setItem('karen_worker_model', m); } catch (e) {}
  if (byPhone) { setModelNote('Assigned on the phone: ' + m); } else { setModelNote(''); }
  if (live && byPhone) { toast('Phone assigned model: ' + m); }
}
function saveWorkerModel() {
  var sel = el('workerModel');
  if (!sel) { return; }
  var opt = sel.selectedIndex >= 0 ? sel.options[sel.selectedIndex] : null;
  if (opt && opt.disabled) { toast('No browser build for this one — pick another'); return; }
  S.w.model = sel.value;
  S.w.edit = true;  // this page writes the assignment on the next register
  try { localStorage.setItem('karen_worker_model', S.w.model); } catch (e) {}
  setModelNote('');
  toast('Node model: ' + (S.w.model || '—') + (S.w.on ? '' : ' — applies when this node joins'));
  if (S.w.on) { workerRegister(); }
}
// The slave-node model list comes from Karen's own model universe — weights
// installed on the phone plus the downloadable catalog — not web-llm's
// built-in catalog. Each Karen entry is matched to the browser (MLC) build
// that will actually run it; tasks are named with the Karen model and
// resolved to that build when the engine loads.
function karenModelBase(name) {
  var base = String(name || '').replace(/\.gguf$/i, '');
  var parts = base.split('-');
  while (parts.length > 1 && /^(i?q\d|bf16|f16|f32)/i.test(parts[parts.length - 1])) { parts.pop(); }
  return parts.join('-').toLowerCase().replace(/[^a-z0-9.]/g, '');
}
function karenMlcBuild(list, file) {
  var base = karenModelBase(file);
  if (!base) { return ''; }
  var cands = list.filter(function (x) { return karenModelBase(x.model_id).indexOf(base) === 0; });
  if (!cands.length) { return ''; }
  var q = cands.filter(function (x) { return /q4f16_1/.test(x.model_id); });
  if (q.length) { cands = q; }
  cands.sort(function (a, b) { return a.vram_required_MB - b.vram_required_MB; });
  return cands[0].model_id;
}
function karenTaskBuild(model) {
  var v = model || '';
  if (v && S.wmap && S.wmap[v]) { return S.wmap[v]; }
  return v;
}
// The web-llm module only supplies the prebuilt (browser) build list — the
// local pick maps Karen model files to those builds. It loads once from the CDN.
async function ensureLlmMod() {
  if (!S.llmMod) {
    S.llmMod = await import('https://esm.run/@mlc-ai/web-llm@0.2.86');
  }
  return S.llmMod;
}
// Fill "Model this node runs" even before the node joins, so the pick is
// visible and usable the moment the token is saved.
async function ensureWorkerModels() {
  try {
    await ensureLlmMod();
    await populateWorkerModelsFromKaren();
  } catch (e) {
    workerStatus('model list needs internet once (engine index): ' + (e && e.message ? e.message : e));
  }
}
async function populateWorkerModelsFromKaren() {
  var sel = el('workerModel');
  if (!sel) { return; }
  var entries = null;
  try {
    var r = await fetch('/v1/karen/models', { headers: headers(false) });
    if (r.ok) { var j = await r.json(); entries = (j && j.data) || null; }
  } catch (e) { entries = null; }
  if (!entries || !entries.length) {
    // Phone list unreachable (rare — same origin): keep the node usable with
    // the raw browser builds and say so.
    populateWorkerModels(boostModelList(S.llmMod));
    workerStatus('phone model list unavailable — showing raw browser builds');
    return;
  }
  var mlc = boostModelList(S.llmMod);
  entries.sort(function (a, b) { return (b.installed ? 1 : 0) - (a.installed ? 1 : 0); });
  S.wmap = {};
  S.wModels = [];
  sel.innerHTML = '';
  var firstOk = -1;
  entries.forEach(function (e) {
    var build = karenMlcBuild(mlc, e.file || e.name || '');
    if (build) { S.wmap[e.file] = build; S.wModels.push(e.file); }
    var o = document.createElement('option');
    o.value = e.file || e.name || '';
    var label = (e.name || e.file) + ' · ' + (e.size || '');
    label += e.installed ? ' · installed' : ' · catalog';
    if (!build) { label += ' · no browser build'; o.disabled = true; }
    o.textContent = label;
    sel.appendChild(o);
    if (build && firstOk < 0) { firstOk = sel.options.length - 1; }
  });
  var want = localStorage.getItem('karen_worker_model') || '';
  if (want) { sel.value = want; }
  var cur = sel.selectedIndex >= 0 ? sel.options[sel.selectedIndex] : null;
  if ((!cur || !cur.value || cur.disabled) && firstOk >= 0) { sel.selectedIndex = firstOk; }
  S.w.model = sel.value || '';
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
    bubble.textContent = 'GPU boost failed: ' + gpuErrText(e);
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
    await ensureLlmMod();
    await populateWorkerModelsFromKaren();
  } catch (e) {
    workerStatus('engine unavailable — needs internet once: ' + (e && e.message ? e.message : e));
    return;
  }
  var joined = await workerRegister();
  if (S.w.master) { S.w.on = false; el('gpuWorker').checked = false; return; }
  if (!joined) {
    // Registration failed (missing/wrong token, network): do NOT start the
    // poll loop — it used to hammer the phone with a 401/404 retry every 5 s.
    // Saving a valid token auto-rejoins from saveToken().
    S.w.on = false;
    el('gpuWorker').checked = false;
    return;
  }
  if (!isAuto) { toast('Working as a slave node'); }
  workerLoop();
}
async function workerRegister() {
  try { await Promise.race([collectTelemetryDeep(), workerSleep(1500)]); } catch (e) {}
  var body = {
    workerId: S.w.id,
    name: S.node,
    gpu: S.gpuName || '',
    gflops: S.gflops || 0,
    backend: 'webllm',
    engine: 'web-llm@0.2.86',
    models: S.wModels,
    loadedModel: S.w.loaded || '',
    cached: S.w.cached ? true : false,
    telemetry: collectTelemetry()
  };
  // Only a deliberate pick on this page rewrites the assignment: an idle
  // rejoin must not overwrite a model the phone assigned in Network Nodes.
  if (S.w.edit) {
    body.defaultModel = (el('workerModel') && el('workerModel').value) || S.w.model || '';
  }
  var j = await post('/v1/worker/register', body);
  if (j && j.master) {
    S.w.master = true;
    S.w.on = false;
    var wb = el('gpuWorker');
    if (wb) { wb.checked = false; wb.disabled = true; }
    workerStatus('this is the master device (the phone) — other devices work as slave nodes');
    refreshWorkers();
    return false;
  }
  if (j && j.workerId) {
    S.w.id = j.workerId;
    localStorage.setItem('karen_worker_id', S.w.id);
    workerStatus('registered — polling for tasks');
    // Show an assignment the phone made while this page was away.
    applyAssigned(j.defaultModel || '', j.assignedAt || 0, !!j.assignedByPhone, false);
    refreshWorkers();
    return true;
  }
  workerStatus('registration failed — is the token saved?');
  refreshWorkers();
  return false;
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
  if (!S.w.id) { return; }   // unregistered: never poll (workerJoin sets the id)
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
      if (r.status === 404) {
        // Stale worker id (server restarted / node removed): re-register once.
        // If that fails too, stop — a 404 → register → 404 loop with no
        // backoff was the worst source of "the node keeps asking the phone".
        workerStatus('re-registering…');
        var regOk = await workerRegister();
        if (!regOk) {
          workerStatus('re-register failed — save a valid token, then switch slave mode on again');
          S.w.on = false;
          if (el('gpuWorker')) { el('gpuWorker').checked = false; }
          return;
        }
        continue;
      }
      if (r.status === 401) {
        // Wrong/missing token: stop polling entirely instead of retrying
        // every 5 s forever. Saving the token re-joins automatically.
        workerStatus('token rejected — slave mode off. Save a valid token, then switch it back on.');
        S.w.on = false;
        if (el('gpuWorker')) { el('gpuWorker').checked = false; }
        return;
      }
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
            var evt = '', dl = null;
            var lines = block.split('\n');
            for (var k = 0; k < lines.length; k++) {
              if (lines[k].indexOf('event: ') === 0) { evt = lines[k].slice(7).trim(); }
              else if (lines[k].indexOf('data: ') === 0) { dl = lines[k].slice(6); }
            }
            if (evt === 'assigned') {
              // Model assignment from the phone (Network Nodes).
              try { var as = JSON.parse(dl || '{}'); applyAssigned(as.model || '', as.at || 0, !!as.byPhone, true); } catch (e) {}
              continue;
            }
            if (evt === 'shard') {
              // Redesign §4: this worker holds a layer shard of a multi-node
              // task. Run it; the poll loop stays responsive for activations.
              try {
                var sd = JSON.parse(dl || '{}');
                S.w.nextWorker = sd.nextWorkerId || '';
                runShardTask({ id: sd.taskId || '', model: sd.model || '', messages: sd.messages || [], temperature: sd.temperature || 0.7, max_tokens: sd.maxTokens || 512 });
              } catch (e) { workerStatus('bad shard: ' + (e && e.message ? e.message : e)); }
              continue;
            }
            if (evt === 'activation') {
              // Redesign §4: hidden state from the previous shard. Stored for
              // the engine integration point (a layer-sharded engine consumes
              // it; WebLLM runs the full model and ignores it).
              try { var ad = JSON.parse(dl || '{}'); S.w.pendingActivation = ad; } catch (e) {}
              continue;
            }
            if (evt !== 'task' || !dl) { continue; }
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
// Redesign §2: weights come from the master (this phone), not the CDN. The
// WebLLM JS engine still loads once from esm.run; every weight byte is
// fetched from the phone and cached in the browser Cache API.
function masterWeightUrl(model) {
  return location.origin + '/v1/weights/' + encodeURIComponent(model) + '/';
}
async function workerEnsureEngine(model, taskId) {
  if (!S.w.engine) {
    var cfg = {
      model: masterWeightUrl(model),
      model_lib: masterWeightUrl(model) + 'lib/mlc_llm.wasm'
    };
    S.w.engine = new S.llmMod.MLCEngine(cfg);
    S.w.loaded = '';
  }
  var target = karenTaskBuild(model) || S.w.loaded;
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
  S.w.cached = true;   // fetched from the master into the browser cache
}
async function workerCancel(id) {
  try { S.w.engine.interrupt(); } catch (e) {}
  await post('/v1/worker/done', { workerId: S.w.id, taskId: id, status: 'cancelled' });
  workerStatus('idle — last task cancelled');
  refreshWorkers();
}
// Redesign §4: run one shard of a multi-node task.
//
// Coordination protocol is fully implemented: exclusive lease, chunk fetch,
// activation relay, leader streaming, deduped done. The one engine-dependent
// step — executing ONLY [fromLayer, toLayer] and emitting a hidden state — is
// the integration point: WebLLM loads the whole model, so a true layer-sharded
// build is needed for partial-layer execution. Until then this runs the full
// model and forwards empty activations so the protocol is exercised.
async function runShardTask(task) {
  var id = task.id;
  S.w.interrupted = false;
  var model = task.model || S.w.model || (el('workerModel') ? el('workerModel').value : '');
  workerStatus('shard ' + id + ' → ' + (model || 'default'));
  try {
    // 1. Claim the exclusive shard lease + chunk list from the master.
    var lr = await post('/v1/task/shard/lease', { taskId: id, workerId: S.w.id });
    if (!lr || !lr.leaseToken) { throw new Error('no shard lease granted'); }
    var fromLayer = lr.fromLayer || 0, toLayer = lr.toLayer || 0, leader = !!lr.leader;
    // 2. Ensure the engine is loaded (pulls chunks from the master into cache).
    await workerEnsureEngine(model, id);
    if (S.w.interrupted) { await shardCancel(id); return; }
    // 3. Run inference. ENGINE INTEGRATION POINT: a layer-sharded engine would
    //    execute only [fromLayer, toLayer] here. WebLLM runs the full model.
    var t0 = performance.now(), ttft = 0, tokens = 0, seq = 0;
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
      // Forward the activation to the next shard (protocol; a real layer
      // build emits the hidden state here).
      seq += 1;
      post('/v1/task/activation', { taskId: id, toWorkerId: S.w.nextWorker || '', seq: seq, data: '' })
        .then(function (r) { if (r && r.cancel) { S.w.interrupted = true; } })
        .catch(function () {});
      if (leader) {
        var now = performance.now();
        if (now - lastFlush >= 40 || buf.length > 200) {
          lastFlush = now;
          var chunk = buf; buf = '';
          var resp = await post('/v1/task/shard/stream', { workerId: S.w.id, taskId: id, seq: seq, text: chunk });
          if (resp && resp.cancel) { cancel = true; S.w.interrupted = true; break; }
        }
      }
    }
    if (leader && buf && !cancel) { await post('/v1/task/shard/stream', { workerId: S.w.id, taskId: id, seq: seq + 1, text: buf }); }
    if (cancel) { try { S.w.engine.interrupt(); } catch (e) {} }
    var elapsed = Math.max(1, performance.now() - t0);
    var tps = Math.round(tokens / (elapsed / 1000) * 10) / 10;
    await post('/v1/task/shard/done', {
      workerId: S.w.id, taskId: id,
      status: cancel ? 'cancelled' : 'completed',
      stats: { ttftMs: Math.round(ttft), tokens: tokens, elapsedMs: Math.round(elapsed), tps: tps, fromLayer: fromLayer, toLayer: toLayer }
    });
    workerStatus(cancel ? 'idle — shard cancelled' : ('idle — shard done · ' + tps + ' tok/s'));
    refreshWorkers();
  } catch (e) {
    await post('/v1/task/shard/done', { workerId: S.w.id, taskId: id, status: 'failed', error: gpuErrText(e) });
    workerStatus('idle — shard failed: ' + (e && e.message ? e.message : e));
  }
}
function shardCancel(id) {
  try { if (S.w.engine) S.w.engine.interrupt(); } catch (e) {}
  return post('/v1/task/shard/done', { workerId: S.w.id, taskId: id, status: 'cancelled' });
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
    await post('/v1/worker/done', { workerId: S.w.id, taskId: id, status: 'failed', error: gpuErrText(e) });
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
function autoPickId(ws) {
  // Mirrors the server's "auto": best online node that is not paused
  // (idle ones first, then GFLOPS) — used for the "auto target" badge.
  var best = null;
  (ws || []).forEach(function (w) {
    if (w.state === 'offline' || w.paused) return;
    if (!best) { best = w; return; }
    var a = (w.state === 'idle') ? 1 : 0, b = (best.state === 'idle') ? 1 : 0;
    if (a !== b) { if (a > b) { best = w; } return; }
    if ((w.gflops || 0) > (best.gflops || 0)) { best = w; }
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
    var autoId = autoPickId(ws);
    ws.forEach(function (w) {
      var row = document.createElement('div');
      row.className = 'frow';
      var st = document.createElement('span');
      st.className = 'badge ' + (w.state === 'offline' ? 'off' : (w.paused ? '' : (w.state === 'idle' ? 'ok' : 'mid')));
      st.textContent = w.paused ? 'paused' : w.state;
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
      if (routed === w.id || (routed === 'auto' && w.id === autoId)) {
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
        : (w.paused ? 'node is paused — resume it first' : ('route prompts to ' + gpuLabel(w)));
      rt.disabled = (w.state === 'offline') || w.paused || (routed === w.id);
      rt.onclick = function () {
        el('runner').value = w.id;
        runnerChanged();
        refreshWorkers();
      };
      row.appendChild(rt);
      var pb = document.createElement('button');
      pb.className = 'ghost';
      pb.textContent = w.paused ? 'Resume' : 'Pause';
      pb.title = w.paused
        ? 'back in rotation — auto and manual routes may use it again'
        : 'stays joined and online, but takes no new prompts until resumed';
      pb.disabled = (w.state === 'offline');
      pb.onclick = function () {
        var want = !w.paused;
        post('/v1/workers/pause', { id: w.id, paused: want }).then(function (r) {
          if (!r || !r.ok) { toast('Could not ' + (want ? 'pause' : 'resume') + ' ' + w.name + ' — node gone?'); }
          else { toast((want ? 'Paused ' : 'Resumed ') + w.name + (want ? ' — it takes no new prompts' : ' — back in rotation')); }
          refreshWorkers();
        });
      };
      row.appendChild(pb);
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
function syncMultiUi() {
  var b = el('mm');
  if (!b) return;
  b.textContent = 'Multi-node: ' + (S.multi ? 'on' : 'off');
  b.className = 'ghost consoleOnly' + (S.multi ? ' on' : '');
  b.title = S.multi
    ? 'One prompt is split across online slave nodes (layer shards)'
    : 'Run one prompt across several slave nodes (layer shards)';
}
function toggleMulti() {
  S.multi = !S.multi;
  localStorage.setItem('karen_multi', S.multi ? '1' : '0');
  syncMultiUi();
  toast(S.multi ? 'Multi-node on — prompts are sharded across online nodes' : 'Multi-node off — prompts route to a single runner');
}
function shardSend(text, runner) {
  // Pick every online, unpaused slave (a specific runner goes first) and ask
  // the master for a layer-shard plan, then watch the leader's stream.
  var ws = (S.wlist || []).filter(function (w) { return w.state !== 'offline' && !w.paused; })
    .sort(function (a, b) { return (b.gflops || 0) - (a.gflops || 0); });
  var ids = ws.map(function (w) { return w.id; });
  if (runner && runner !== 'auto') {
    ids = [runner].concat(ids.filter(function (x) { return x !== runner; }));
  }
  ids = ids.slice(0, 6);
  if (ids.length < 2) { toast('Multi-node needs 2+ online slave nodes — ' + ids.length + ' online'); return; }
  var model = '';
  if (runner && runner !== 'auto') { model = el('wmodel').value || ''; }
  if (!model) {
    var first = runnerInfo(ids[0]);
    model = (first && (first.loadedModel || first.defaultModel)) || '';
  }
  el('input').value = '';
  add('user', text);
  S.log.push({ role: 'user', content: text });
  var bubble = add('assistant', '…');
  S.busy = true; el('send').disabled = true; el('stop').disabled = false;
  S.ctrl = new AbortController();
  var full = '';
  fetch('/v1/task/sharded', {
    method: 'POST',
    headers: headers(true),
    body: JSON.stringify({ model: model, workerIds: ids, messages: S.log.slice(-12), stream: true, temperature: 0.7, maxTokens: 1024 }),
    signal: S.ctrl.signal
  }).then(function (r) {
    if (!r.ok) {
      return r.text().then(function (t) {
        bubble.className = 'msg sys';
        bubble.textContent = 'Multi-node error ' + r.status + ' — ' + t.slice(0, 240);
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
                addMeta(bubble, 'Multi-node · ' + ids.length + ' shards · ' + (st.tokens || 0) + ' tok · ' + (st.tps || 0) + ' tok/s');
              }
              continue;
            }
            if (j.object === 'worker.error') {
              var msg = (j.error && j.error.message) ? j.error.message : 'multi-node error';
              bubble.className = 'msg sys';
              bubble.textContent = 'Multi-node error — ' + msg;
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
        bubble.className = 'msg sys'; bubble.textContent = '(no tokens — sharded run finished empty)';
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
  if (S.multi) {
    if (!S.token) { toast('Paste the bearer token first'); return; }
    shardSend(text, runner);
    return;
  }
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
syncMultiUi();
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
