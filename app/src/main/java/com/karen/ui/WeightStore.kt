package com.karen.ui

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Master-side model weight store (redesign §2–3).
 *
 * Serves model artifacts to browser workers over HTTP with Range support so
 * downloads are chunked and resumable. The phone cannot convert GGUF → MLC,
 * so models are stored in whichever format the worker runtime needs; the
 * manifest records `format` ("mlc" | "gguf") and the worker picks accordingly.
 *
 * Layout:
 *   <models>/store/<model-slug>/
 *     manifest.json          # artifacts + chunk list (sizes, sha256)
 *     config.json            # model config
 *     tokenizer.json         # tokenizer
 *     weights/shard-000.bin  # 64 MB chunks
 *     weights/shard-001.bin
 *     lib/mlc_llm.wasm       # optional self-hosted engine
 */
class WeightStore(ctx: Context) {

    data class Artifact(
        val file: String,
        val size: Long,
        val sha256: String
    )

    data class Manifest(
        val model: String,
        val format: String,
        val layers: Int,
        val vramRequiredMB: Int,
        val artifacts: List<Artifact>
    )

    private val root: File = File(ModelDownloader.modelsDir(ctx), "store").apply { mkdirs() }

    fun modelDir(model: String): File = File(root, model)

    fun manifestFile(model: String): File = File(modelDir(model), "manifest.json")

    /** All model slugs present in the store, sorted. */
    fun listModels(): List<String> =
        root.listFiles { f -> f.isDirectory }?.map { it.name }?.sorted() ?: emptyList()

    fun readManifest(model: String): Manifest? {
        val f = manifestFile(model)
        if (!f.exists()) return null
        return try {
            val json = org.json.JSONObject(f.readText())
            val arts = json.optJSONArray("artifacts") ?: return null
            val list = mutableListOf<Artifact>()
            for (i in 0 until arts.length()) {
                val a = arts.getJSONObject(i)
                list.add(
                    Artifact(
                        file = a.getString("file"),
                        size = a.optLong("size"),
                        sha256 = a.optString("sha256")
                    )
                )
            }
            Manifest(
                model = json.optString("model", model),
                format = json.optString("format", "mlc"),
                layers = json.optInt("layers", 0),
                vramRequiredMB = json.optInt("vramRequiredMB", 0),
                artifacts = list
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build (or rebuild) the manifest for a model directory: scans top-level
     * files and any weight chunks, hashes them. Creates the store dir if
     * needed. Returns null when the model directory does not exist.
     */
    fun buildManifest(
        model: String,
        format: String = "mlc",
        layers: Int = 0,
        vramRequiredMB: Int = 0
    ): Manifest? {
        val dir = modelDir(model)
        if (!dir.exists()) return null
        val arts = mutableListOf<Artifact>()
        dir.listFiles { f -> f.isFile && f.name != "manifest.json" }?.sortedBy { it.name }?.forEach { f ->
            arts.add(Artifact(f.name, f.length(), sha256(f)))
        }
        File(dir, "weights").listFiles { f -> f.isFile }?.sortedBy { it.name }?.forEach { f ->
            arts.add(Artifact("weights/${f.name}", f.length(), sha256(f)))
        }
        val m = Manifest(model, format, layers, vramRequiredMB, arts)
        manifestFile(model).writeText(toJson(m))
        return m
    }

    /**
     * Split a large weight file into 64 MB chunks under `<model>/weights/`.
     * Returns the artifact paths (relative). Existing chunks are replaced.
     */
    fun chunkFile(model: String, source: File, chunkSize: Long = CHUNK): List<String> {
        val dir = File(modelDir(model), "weights").apply { mkdirs() }
        // Clear old chunks to avoid mixing shards from a previous file.
        dir.listFiles { f -> f.name.startsWith("shard-") }?.forEach { it.delete() }
        val out = mutableListOf<String>()
        if (!source.exists()) return out
        source.inputStream().use { input ->
            val buf = ByteArray(1024 * 1024)
            var index = 0
            while (true) {
                val chunk = File(dir, "shard-%03d.bin".format(index))
                var written = 0L
                chunk.outputStream().use { o ->
                    while (written < chunkSize) {
                        val toRead = minOf(buf.size.toLong(), chunkSize - written).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        written += n
                    }
                }
                if (written == 0L) {
                    chunk.delete()
                    break
                }
                out.add("weights/${chunk.name}")
                index++
            }
        }
        return out
    }

    /** Resolve a model-relative file path safely (no path traversal). */
    fun resolve(model: String, file: String): File? {
        val f = File(modelDir(model), file)
        return try {
            if (f.canonicalPath.startsWith(modelDir(model).canonicalPath) && f.exists()) f else null
        } catch (_: Exception) {
            null
        }
    }

    /** Total bytes of all artifacts in a manifest. */
    fun totalBytes(m: Manifest): Long = m.artifacts.sumOf { it.size }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toJson(m: Manifest): String {
        val arts = org.json.JSONArray()
        m.artifacts.forEach { a ->
            arts.put(
                org.json.JSONObject()
                    .put("file", a.file)
                    .put("size", a.size)
                    .put("sha256", a.sha256)
            )
        }
        return org.json.JSONObject()
            .put("model", m.model)
            .put("format", m.format)
            .put("layers", m.layers)
            .put("vramRequiredMB", m.vramRequiredMB)
            .put("artifacts", arts)
            .toString(2)
    }

    companion object {
        /** Default chunk size: 64 MB (redesign §3). */
        const val CHUNK = 64L * 1024 * 1024
    }
}
