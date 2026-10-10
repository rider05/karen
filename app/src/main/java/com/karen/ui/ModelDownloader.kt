package com.karen.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

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
    /** True when the model is a solid code generator for its size. */
    val code: Boolean = false
)

val modelCatalog = listOf(
    CatalogModel(
        "Qwen2.5 0.5B Fast", "Qwen2.5-0.5B-Instruct-Q4_K_M.gguf", "≈0.4 GB", "Q4_K_M", "1.5 GB",
        "https://huggingface.co/bartowski/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/Qwen2.5-0.5B-Instruct-Q4_K_M.gguf",
        paramsB = 0.5f
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
    )
)

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

    /** True when the installed weight can actually run (llama.cpp = GGUF only). */
    fun isRunnableWeight(ctx: Context, displayName: String): Boolean {
        if (isCloudProviderName(displayName)) return false
        val f = weightFileFor(ctx, displayName) ?: return false
        return f.extension.lowercase() == "gguf"
    }

    fun modelFile(ctx: Context, fileName: String): File = File(modelsDir(ctx), fileName)

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
            "Voice-activity detection · needs ORT engine"
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
                        val pct = (voiceProgress * 100).toInt()
                        val sizeTxt = if (total > 0) {
                            "%.1f/%.1f MB".format(done / 1048576.0, total / 1048576.0)
                        } else {
                            "%.1f MB".format(done / 1048576.0)
                        }
                        voiceStatus = "Downloading $pct% · $sizeTxt"
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
                        val pct = (progress * 100).toInt()
                        val sizeTxt = if (total > 0) {
                            "%.1f/%.1f MB".format(done / 1048576.0, total / 1048576.0)
                        } else {
                            "%.1f MB".format(done / 1048576.0)
                        }
                        status = "Downloading $pct% · $sizeTxt"
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
}
