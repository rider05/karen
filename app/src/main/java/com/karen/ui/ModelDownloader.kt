package com.karen.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Real GGUF downloads via Android DownloadManager into the app-private
 * `models/` dir (no storage permission needed). One download at a time.
 *
 * UI screens poll [refresh] (cheap when idle); the first poller to observe a
 * terminal state consumes it, so overlapping pollers can't double-register.
 */
data class CatalogModel(
    val name: String,
    val fileName: String,
    val sizeLabel: String,
    val quant: String,
    val ram: String,
    val url: String,
    /** Approximate params in billions — the router weighs quality by size. */
    val paramsB: Float = 0f,
    /** True for thinking/reasoning models (R1, QwQ, Qwen3-hybrid class). */
    val reasoning: Boolean = false,
    /** True for solid code generators for its size. */
    val code: Boolean = false,
    /** True for embedding-only weights (vault/RAG): never chat-routed. */
    val embedding: Boolean = false
)

val modelCatalog = listOf(
    CatalogModel(
        "Qwen2.5 0.5B Fast", "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf", "≈0.4 GB", "Q4_K_M", "1.5 GB",
        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
        paramsB = 0.5f
    ),
    CatalogModel(
        "Underdog Saluki 27B · IQ2", "Underdog-Saluki-27B-1.0-IQ2-mix.gguf", "≈7.9 GB", "IQ2-mix", "10 GB",
        "https://huggingface.co/ConwayResearch/Underdog-Saluki-27B-1.0/resolve/main/Underdog-Saluki-27B-1.0-IQ2-mix.gguf",
        paramsB = 27f, reasoning = true
    ),
    CatalogModel(
        "Qwen 3.5 2B", "Qwen3.5-2B-IQ4_XS.gguf", "≈1.1 GB", "IQ4_XS", "2.0 GB",
        "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-IQ4_XS.gguf",
        paramsB = 2f, code = true
    ),
    CatalogModel(
        "Qwen2.5 1.5B", "Qwen2.5-1.5B-Instruct-Q4_K_M.gguf", "≈1.0 GB", "Q4_K_M", "2.0 GB",
        "https://huggingface.co/bartowski/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/Qwen2.5-1.5B-Instruct-Q4_K_M.gguf",
        paramsB = 1.5f, code = true
    ),
    CatalogModel(
        "Qwen2.5 3B", "Qwen2.5-3B-Instruct-Q4_K_M.gguf", "≈1.9 GB", "Q4_K_M", "2.5 GB",
        "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-Q4_K_M.gguf",
        paramsB = 3f, code = true
    ),
    CatalogModel(
        "Qwen2.5 7B", "Qwen2.5-7B-Instruct-Q4_K_M.gguf", "≈4.7 GB", "Q4_K_M", "5.5 GB",
        "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
        paramsB = 7f, code = true
    ),
    CatalogModel(
        "DeepSeek R1 1.5B · Reasoning", "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf", "≈1.1 GB", "Q4_K_M", "2.5 GB",
        "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        paramsB = 1.5f, reasoning = true, code = true
    ),
    CatalogModel(
        "EmbeddingGemma 2 BF16", "embeddinggemma-2-BF16.gguf", "≈0.5 GB", "BF16", "1.0 GB",
        "https://huggingface.co/unsloth/embeddinggemma-2-GGUF/resolve/main/embeddinggemma-2-BF16.gguf",
        paramsB = 0.5f, embedding = true
    )
)

/** Download terminal states (consumed once by the first poller). */
sealed interface DownloadEvent {
    data object None : DownloadEvent
    data class Completed(val name: String) : DownloadEvent
    data class Failed(val reason: String) : DownloadEvent
}

object ModelDownloader {
    var activeName by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    var status by mutableStateOf("")
        private set

    /** Remote additions merged over [modelCatalog] (remote wins name clashes). */
    var remoteCatalog by mutableStateOf<List<CatalogModel>>(emptyList())
        private set

    fun catalogModels(): List<CatalogModel> {
        val remote = remoteCatalog
        if (remote.isEmpty()) return modelCatalog
        val names = remote.map { it.name }.toSet()
        return remote + modelCatalog.filter { it.name !in names }
    }

    /**
     * Fetches a remote catalog so models ship without an APK update.
     * Expected shape: {"models":[{"name","file","url","size","quant","ram",
     * "paramsB","reasoning","code"}]} — unknown entries are skipped, max 50.
     */
    suspend fun refreshRemoteCatalog(ctx: Context): String = withContext(Dispatchers.IO) {
        val url = UserPrefs.catalogUrl(ctx).trim()
        if (url.isBlank()) {
            remoteCatalog = emptyList()
            return@withContext "bundled catalog"
        }
        try {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 20000
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) return@withContext "catalog HTTP $code"
            val arr = JSONObject(text).optJSONArray("models")
                ?: return@withContext "no models[] in catalog JSON"
            val out = mutableListOf<CatalogModel>()
            for (i in 0 until minOf(arr.length(), 50)) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "").trim()
                val file = o.optString("file", "").trim()
                val link = o.optString("url", "").trim()
                if (name.isBlank() || file.isBlank()) continue
                if (!link.startsWith("http://") && !link.startsWith("https://")) continue
                out.add(
                    CatalogModel(
                        name = name.take(60),
                        fileName = file.substringAfterLast('/').take(120).ifBlank { file.take(120) },
                        sizeLabel = o.optString("size", "≈?").take(16).ifBlank { "≈?" },
                        quant = o.optString("quant", "?").take(16).ifBlank { "?" },
                        ram = o.optString("ram", "?").take(16).ifBlank { "?" },
                        url = link,
                        paramsB = o.optDouble("paramsB", 0.0).toFloat(),
                        reasoning = o.optBoolean("reasoning", false),
                        code = o.optBoolean("code", false)
                    )
                )
            }
            remoteCatalog = out
            if (out.isEmpty()) "catalog added no usable models" else "added ${out.size} remote model${if (out.size == 1) "" else "s"}"
        } catch (e: Exception) {
            "catalog fetch failed (${e.message})"
        }
    }

    private const val PREFS = "karen_downloads"
    private const val P_ID = "dl_id"
    private const val P_NAME = "dl_name"
    private const val P_FILE = "dl_file"

    private var downloadId: Long = -1

    private fun dlPrefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelsDir(ctx: Context): File =
        (ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models")).apply { mkdirs() }

    /** Resolves an installed display name to its weight file, or null if missing. */
    fun weightFileFor(ctx: Context, displayName: String): File? {
        val dir = modelsDir(ctx)
        if (isCloudProviderName(displayName)) return null
        File(dir, displayName).takeIf { it.exists() && it.length() > 0 }?.let { return it }
        File(dir, "$displayName.gguf").takeIf { it.exists() && it.length() > 0 }?.let { return it }
        modelCatalog.find { it.name == displayName }?.let {
            File(dir, it.fileName).takeIf { f -> f.exists() && f.length() > 0 }?.let { return it }
        }
        return dir.listFiles()
            ?.firstOrNull { it.isFile && it.nameWithoutExtension == displayName && it.length() > 0 }
    }

    /** Display format for an installed weight name. Only GGUF runs on-device. */
    fun formatOf(displayName: String): String = when (displayName.substringAfterLast('.', "").lowercase()) {
        "onnx" -> "ONNX"
        "pth", "pt" -> "PyTorch"
        else -> "GGUF"
    }

    /** Best-effort quant tag read from a file name (Q4_K_M, IQ2_XS, BF16…). */
    fun quantOf(fileName: String): String {
        val stem = fileName.substringBeforeLast('.')
        val m = Regex("(?i)(iq\\d[\\w]*|q\\d[\\w]*|bf16|f16|fp16)").find(stem)
        return m?.value?.uppercase() ?: "GGUF"
    }

    /** True when the installed weight can actually run (llama.cpp = GGUF only). */
    fun isRunnableWeight(ctx: Context, displayName: String): Boolean {
        if (isCloudProviderName(displayName)) return false
        val f = weightFileFor(ctx, displayName) ?: return false
        return f.extension.lowercase() == "gguf"
    }

    fun modelFile(ctx: Context, fileName: String): File = File(modelsDir(ctx), fileName)

    // ---- Hugging Face repo resolution ----
    //
    // "Download via URL" accepts a Hugging Face repo id (`org/name`) besides a
    // direct file URL. The repo id is resolved against the public HF API, the
    // GGUF files it contains are listed (vision projectors skipped), and the
    // picked file lands in models/ like any catalog download — so it shows up
    // in the model manager and the picker with no extra step.

    /** Direct file URL, HF repo id, or something we cannot use. */
    data class ModelInput(val kind: Kind, val value: String = "", val revision: String = "main") {
        enum class Kind { DIRECT_URL, HF_REPO, INVALID }
    }

    /**
     * Interprets the dialog text: trims quotes, strips a huggingface.co host,
     * turns /blob/ file pages into their /resolve/ download URL, and reads
     * `org/name/tree/<rev>` style links. Everything else with a scheme stays
     * a direct URL.
     */
    fun parseModelInput(raw: String): ModelInput {
        var s = raw.trim().trim('"', '\'')
        if (s.isEmpty()) return ModelInput(ModelInput.Kind.INVALID)
        val lower = s.lowercase()
        val hfHosts = listOf(
            "https://huggingface.co/", "http://huggingface.co/",
            "https://hf.co/", "http://hf.co/",
            "huggingface.co/", "hf.co/"
        )
        val host = hfHosts.firstOrNull { lower.startsWith(it) }
        if (host != null) s = s.substring(host.length)
        s = s.substringBefore('?').substringBefore('#').trimEnd('/')
        if (s.isEmpty()) return ModelInput(ModelInput.Kind.INVALID)
        if (s.contains("/blob/")) {
            val repo = s.substringBefore("/blob/")
            val rest = s.substringAfter("/blob/")
            val rev = rest.substringBefore('/').ifBlank { "main" }
            val file = rest.substringAfter('/', "")
            if (file.isBlank()) return ModelInput(ModelInput.Kind.INVALID)
            return ModelInput(ModelInput.Kind.DIRECT_URL, "https://huggingface.co/$repo/resolve/$rev/$file")
        }
        if (s.contains("/resolve/")) {
            return ModelInput(ModelInput.Kind.DIRECT_URL, "https://huggingface.co/$s")
        }
        if (s.startsWith("http://") || s.startsWith("https://")) {
            return ModelInput(ModelInput.Kind.DIRECT_URL, s)
        }
        val segs = s.trim('/').split('/')
        if (segs.size < 2) return ModelInput(ModelInput.Kind.INVALID)
        val repo = segs[0] + "/" + segs[1]
        if (!Regex("^[A-Za-z0-9][\\w.-]*/[A-Za-z0-9][\\w.-]*$").matches(repo)) {
            return ModelInput(ModelInput.Kind.INVALID)
        }
        val rev = if (segs.size >= 4 && segs[2] == "tree") segs[3].ifBlank { "main" } else "main"
        return ModelInput(ModelInput.Kind.HF_REPO, repo, rev)
    }

    /** One downloadable GGUF inside a Hugging Face repo. */
    data class HfFile(val path: String, val sizeBytes: Long, val url: String) {
        val name: String get() = path.substringAfterLast('/')
        /** Name shown in the model manager and used as the installed-model key. */
        val displayName: String get() = name.removeSuffix(".gguf").ifBlank { name }
        val sizeLabel: String
            get() = if (sizeBytes > 0) "≈%.2f GB".format(sizeBytes / 1073741824.0) else "size unknown"
    }

    sealed interface HfResolveResult {
        data class Ok(val repoId: String, val revision: String, val files: List<HfFile>) : HfResolveResult
        data class Failed(val message: String) : HfResolveResult
    }

    /**
     * Lists the GGUF files of a HF repo (public API, no token — anonymous rate
     * limits apply). Gated/private repos answer 401/403 and say so honestly.
     */
    suspend fun resolveHuggingFace(repoId: String, revision: String = "main"): HfResolveResult =
        withContext(Dispatchers.IO) {
            try {
                val rev = revision.ifBlank { "main" }
                val api = if (rev == "main") {
                    "https://huggingface.co/api/models/$repoId?blobs=true"
                } else {
                    "https://huggingface.co/api/models/$repoId/revision/$rev?blobs=true"
                }
                val conn = (java.net.URL(api).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "Karen-Android")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 12000
                    readTimeout = 20000
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                when (code) {
                    401, 403 -> return@withContext HfResolveResult.Failed("repo is gated or private — a HF login token is required")
                    404 -> return@withContext HfResolveResult.Failed("repo not found on huggingface.co")
                }
                if (code !in 200..299) return@withContext HfResolveResult.Failed("Hugging Face HTTP $code")
                val root = try {
                    JSONObject(text)
                } catch (_: Exception) {
                    return@withContext HfResolveResult.Failed("unreadable reply from huggingface.co")
                }
                val siblings = root.optJSONArray("siblings")
                    ?: return@withContext HfResolveResult.Failed("repo listed no files")
                val files = mutableListOf<HfFile>()
                var skippedProjectors = 0
                for (i in 0 until siblings.length()) {
                    val o = siblings.optJSONObject(i) ?: continue
                    val path = o.optString("rfilename", "").trim()
                    if (path.isEmpty() || !path.lowercase().endsWith(".gguf")) continue
                    val base = path.substringAfterLast('/').lowercase()
                    if (base.contains("mmproj")) {
                        skippedProjectors++
                        continue
                    }
                    val size = o.optJSONObject("lfs")?.optLong("size", 0L) ?: o.optLong("size", 0L)
                    files.add(
                        HfFile(
                            path = path,
                            sizeBytes = size,
                            url = "https://huggingface.co/$repoId/resolve/$rev/$path"
                        )
                    )
                }
                // Smallest first: fastest to fetch, easiest to try.
                files.sortBy { if (it.sizeBytes > 0) it.sizeBytes else Long.MAX_VALUE }
                if (files.isEmpty()) {
                    return@withContext HfResolveResult.Failed(
                        if (skippedProjectors > 0) {
                            "only vision projector (mmproj) GGUFs here — those cannot chat alone"
                        } else {
                            "no .gguf files in this repo — Karen runs GGUF weights only"
                        }
                    )
                }
                HfResolveResult.Ok(repoId, rev, files.take(20))
            } catch (e: Exception) {
                HfResolveResult.Failed("lookup failed (${e.message ?: "network error"})")
            }
        }

    // ---- Voice models (whistle.cact, sherpa-onnx files) ----
    // Separate slot + registry so voice downloads never pollute chat weights.

    /** Downloadable voice model: STT/VAD weight + the engine that runs it. */
    data class VoiceModel(
        val name: String,
        val fileName: String,
        val sizeLabel: String,
        val url: String,
        val engine: String,
        val note: String
    )

    val voiceCatalog = listOf(
        VoiceModel(
            "Whistle · STT (7 langs)", "whistle.cact", "≈16.9 MB",
            "https://huggingface.co/Cactus-Compute/whistle/resolve/main/whistle.cact",
            "Cactus Needle",
            "16 kHz mono · word timestamps · needs Needle engine (arm64)"
        ),
        VoiceModel(
            "Silero VAD", "silero_vad.onnx", "≈2 MB",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            "sherpa-onnx/ORT",
            "Voice-activity detection · runs on bundled ORT"
        )
    )

    var activeVoiceName by mutableStateOf<String?>(null)
        private set
    var voiceProgress by mutableStateOf(0f)
        private set
    var voiceStatus by mutableStateOf("")
        private set

    private const val V_ID = "voice_dl_id"
    private const val V_NAME = "voice_dl_name"
    private const val V_FILE = "voice_dl_file"

    private var voiceDownloadId: Long = -1

    fun voicesDir(ctx: Context): File =
        (ctx.getExternalFilesDir("voices") ?: File(ctx.filesDir, "voices")).apply { mkdirs() }

    fun voiceFile(ctx: Context, fileName: String): File = File(voicesDir(ctx), fileName)

    /** URL pull for voice-domain .onnx weights (lands in voices/). */
    fun startVoiceUrl(ctx: Context, displayName: String, fileName: String, url: String): Boolean {
        if (activeName != null || activeVoiceName != null) return false
        return try {
            val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifBlank { "remote.onnx" }
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("Karen voice: $displayName")
                .setDescription("Downloading voice model")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, "voices", safe)
                .setAllowedOverMetered(true)
            val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
            val id = dm.enqueue(req)
            voiceDownloadId = id
            dlPrefs(ctx).edit()
                .putLong(V_ID, id).putString(V_NAME, displayName).putString(V_FILE, safe)
                .apply()
            activeVoiceName = displayName
            voiceProgress = 0f
            voiceStatus = "Starting…"
            true
        } catch (_: Exception) {
            false
        }
    }

    fun startVoice(ctx: Context, entry: VoiceModel): Boolean {
        if (activeName != null || activeVoiceName != null) return false
        try {
            val req = DownloadManager.Request(Uri.parse(entry.url))
                .setTitle("Karen voice: ${entry.name}")
                .setDescription("Downloading voice model")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, "voices", entry.fileName)
                .setAllowedOverMetered(true)
            val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
            val id = dm.enqueue(req)
            voiceDownloadId = id
            dlPrefs(ctx).edit()
                .putLong(V_ID, id).putString(V_NAME, entry.name).putString(V_FILE, entry.fileName)
                .apply()
            activeVoiceName = entry.name
            voiceProgress = 0f
            voiceStatus = "Starting…"
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun cancelVoice(ctx: Context) {
        try {
            val id = if (voiceDownloadId >= 0) voiceDownloadId
            else dlPrefs(ctx).getLong(V_ID, -1)
            if (id >= 0) ctx.getSystemService(DownloadManager::class.java)?.remove(id)
        } catch (_: Exception) {
        }
        clearVoice(ctx)
    }

    private fun clearVoice(ctx: Context) {
        voiceDownloadId = -1
        dlPrefs(ctx).edit().remove(V_ID).remove(V_NAME).remove(V_FILE).apply()
        activeVoiceName = null
        voiceProgress = 0f
        voiceStatus = ""
    }

    /** Poll voice download once. Terminal event at most once per download. */
    fun refreshVoice(ctx: Context): DownloadEvent {
        var id = voiceDownloadId
        if (id < 0) {
            id = dlPrefs(ctx).getLong(V_ID, -1)
            if (id >= 0) voiceDownloadId = id
        }
        if (id < 0) {
            if (activeVoiceName != null) {
                activeVoiceName = null
                voiceProgress = 0f
                voiceStatus = ""
            }
            return DownloadEvent.None
        }
        try {
            val dm = ctx.getSystemService(DownloadManager::class.java)
                ?: return DownloadEvent.None
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) {
                    clearVoice(ctx)
                    return DownloadEvent.Failed("download vanished")
                }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (total > 0) voiceProgress = (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
                return when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val name = dlPrefs(ctx).getString(V_NAME, "voice model") ?: "voice model"
                        val fileName = dlPrefs(ctx).getString(V_FILE, "") ?: ""
                        val file = if (fileName.isNotEmpty()) voiceFile(ctx, fileName) else null
                        clearVoice(ctx)
                        if (file != null && file.exists() && file.length() > 0) {
                            val installed = UserPrefs.voiceModels(ctx).toMutableList()
                            if (name !in installed) {
                                installed.add(name)
                                UserPrefs.saveVoiceModels(ctx, installed)
                            }
                            DownloadEvent.Completed(name)
                        } else {
                            DownloadEvent.Failed("file missing after download")
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        clearVoice(ctx)
                        DownloadEvent.Failed(reasonText(reason))
                    }
                    else -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        if (stalledOut(id, st, done)) {
                            clearVoice(ctx)
                            return DownloadEvent.Failed("stalled with no progress — tap to retry")
                        }
                        voiceStatus = describeProgress(st, reason, done, total)
                        DownloadEvent.None
                    }
                }
            }
        } catch (e: Exception) {
            clearVoice(ctx)
            return DownloadEvent.Failed(e.message ?: "query failed")
        }
    }

    fun start(ctx: Context, name: String, fileName: String, url: String): Boolean {
        if (activeName != null) return false
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("Karen: $name")
                .setDescription("Downloading model weights")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(ctx, "models", fileName)
                .setAllowedOverMetered(true)
            val dm = ctx.getSystemService(DownloadManager::class.java) ?: return false
            val id = dm.enqueue(req)
            downloadId = id
            dlPrefs(ctx).edit()
                .putLong(P_ID, id).putString(P_NAME, name).putString(P_FILE, fileName)
                .apply()
            activeName = name
            progress = 0f
            status = "Starting…"
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun startEntry(ctx: Context, entry: CatalogModel): Boolean =
        start(ctx, entry.name, entry.fileName, entry.url)

    fun cancel(ctx: Context) {
        try {
            val id = currentId(ctx)
            if (id >= 0) ctx.getSystemService(DownloadManager::class.java)?.remove(id)
        } catch (_: Exception) {
        }
        clear(ctx)
    }

    /** Poll once. Returns a terminal event at most once per download. */
    fun refresh(ctx: Context): DownloadEvent {
        val id = currentId(ctx)
        if (id < 0) {
            if (activeName != null) {
                activeName = null
                progress = 0f
                status = ""
            }
            return DownloadEvent.None
        }
        try {
            val dm = ctx.getSystemService(DownloadManager::class.java)
                ?: return DownloadEvent.None
            dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) {
                    clear(ctx)
                    return DownloadEvent.Failed("download vanished")
                }
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (total > 0) progress = (done.toDouble() / total).toFloat().coerceIn(0f, 1f)
                return when (st) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val name = dlPrefs(ctx).getString(P_NAME, "model") ?: "model"
                        val fileName = dlPrefs(ctx).getString(P_FILE, "") ?: ""
                        val file = if (fileName.isNotEmpty()) modelFile(ctx, fileName) else null
                        clear(ctx)
                        if (file != null && file.exists() && file.length() > 0) {
                            val installed = UserPrefs.models(ctx).toMutableList()
                            if (name !in installed) {
                                installed.add(name)
                                UserPrefs.saveModels(ctx, installed)
                            }
                            DownloadEvent.Completed(name)
                        } else {
                            DownloadEvent.Failed("file missing after download")
                        }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        clear(ctx)
                        DownloadEvent.Failed(reasonText(reason))
                    }
                    else -> {
                        val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        if (stalledOut(id, st, done)) {
                            clear(ctx)
                            return DownloadEvent.Failed("stalled with no progress — tap to retry")
                        }
                        status = describeProgress(st, reason, done, total)
                        DownloadEvent.None
                    }
                }
            }
        } catch (e: Exception) {
            clear(ctx)
            return DownloadEvent.Failed(e.message ?: "query failed")
        }
    }

    private fun currentId(ctx: Context): Long {
        if (downloadId < 0) {
            val saved = dlPrefs(ctx).getLong(P_ID, -1)
            if (saved >= 0) {
                downloadId = saved
                if (activeName == null) {
                    activeName = dlPrefs(ctx).getString(P_NAME, null)
                }
            }
        }
        return downloadId
    }

    private fun clear(ctx: Context) {
        downloadId = -1
        activeName = null
        progress = 0f
        status = ""
        dlPrefs(ctx).edit().clear().apply()
    }

    private fun reasonText(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "no space left on device"
        DownloadManager.ERROR_CANNOT_RESUME -> "server refused resume — retry"
        DownloadManager.ERROR_FILE_ERROR -> "storage write failed"
        DownloadManager.ERROR_HTTP_DATA_ERROR -> "server/URL error — check link"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects — check link"
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server rejected the request"
        else -> "failed (code $reason)"
    }

    // ---- Honest download states: queued/paused/stall, shared by weights + voice ----

    private var stallId = -1L
    private var stallBytes = 0L
    private var stallTs = 0L

    private fun describeProgress(st: Int, reason: Int, done: Long, total: Long): String {
        val sizeTxt = if (total > 0) {
            "%.1f/%.1f MB".format(done / 1048576.0, total / 1048576.0)
        } else {
            "%.1f MB".format(done / 1048576.0)
        }
        return when (st) {
            DownloadManager.STATUS_PENDING -> "Queued — waiting for a download slot"
            DownloadManager.STATUS_PAUSED -> when (reason) {
                DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Paused — needs Wi-Fi"
                DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Paused — no connection"
                DownloadManager.PAUSED_WAITING_TO_RETRY -> "Paused — retrying connection"
                else -> "Paused"
            } + " · $sizeTxt"
            else -> {
                val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                "Downloading $pct% · $sizeTxt"
            }
        }
    }

    /**
     * Stall watchdog: bytes frozen while RUNNING >90s (PENDING >3min) fails
     * the download so Cancel/retry actually works. Paused never fails —
     * it already says why it waits.
     */
    private fun stalledOut(id: Long, st: Int, done: Long): Boolean {
        val now = System.currentTimeMillis()
        if (id != stallId) {
            stallId = id
            stallBytes = done
            stallTs = now
            return false
        }
        if (done > stallBytes) {
            stallBytes = done
            stallTs = now
            return false
        }
        val limit = if (st == DownloadManager.STATUS_PENDING) 180_000L else 90_000L
        return (st == DownloadManager.STATUS_RUNNING || st == DownloadManager.STATUS_PENDING) &&
            now - stallTs > limit
    }
}
