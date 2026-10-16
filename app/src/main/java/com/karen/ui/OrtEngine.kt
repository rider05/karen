package com.karen.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime engine (onnxruntime-android, CPU) for voice-domain models.
 * First client: Silero VAD — speech probability per 16 kHz window, driving
 * barge-in and turn-taking on real voice instead of raw loudness.
 */
object OrtEngine {
    private val lock = Any()
    private var env: OrtEnvironment? = null
    private var vadSession: OrtSession? = null
    private var vadInputName: String? = null
    private var vadWindow: Int = 512
    private var vadModelPath: String? = null

    /** True when the ORT native libraries load on this device/ABI. */
    val ortAvailable: Boolean by lazy {
        try {
            OrtEnvironment.getEnvironment()
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        } catch (_: Exception) {
            false
        }
    }

    /** True when a VAD session is live and scoring. */
    val vadReady: Boolean
        get() = synchronized(lock) { vadSession != null && vadInputName != null }

    /** Window size the loaded VAD expects (samples @16 kHz). 0 = unavailable. */
    fun vadWindowSize(): Int = synchronized(lock) {
        if (vadSession == null) 0 else vadWindow
    }

    /**
     * Loads [file] as the VAD model (idempotent per path). Inspects the
     * graph's input shape so fixed-window and dynamic-window exports both
     * work. Returns false with everything released on any failure.
     */
    fun ensureVad(ctx: Context, file: File = VoiceEngine.sileroFile(ctx)): Boolean {
        synchronized(lock) {
            if (vadSession != null && vadModelPath == file.absolutePath) return true
            closeVadLocked()
            try {
                if (!ortAvailable) return false
                if (!file.exists() || file.length() < 10_000) return false
                val environment = env ?: OrtEnvironment.getEnvironment().also { env = it }
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val session = environment.createSession(file.absolutePath, opts)
                val name = session.inputNames?.iterator()?.next() ?: run {
                    session.close()
                    return false
                }
                val info = session.inputInfo[name]?.info
                var window = 512
                try {
                    val shape = (info as? ai.onnxruntime.TensorInfo)?.shape
                    val frames = shape?.getOrNull(1)?.toInt()
                    if (frames != null && frames > 0) window = frames.coerceIn(256, 4096)
                } catch (_: Exception) {
                }
                vadSession = session
                vadInputName = name
                vadWindow = window
                vadModelPath = file.absolutePath
                return true
            } catch (_: Exception) {
                closeVadLocked()
                return false
            }
        }
    }

    /**
     * Speech probability for exactly [vadWindowSize] 16 kHz samples in
     * [-1, 1]. Returns 0 on any failure (fail-closed: treated as silence).
     */
    fun vadProb(samples: FloatArray): Float {
        val session: OrtSession
        val name: String
        synchronized(lock) {
            session = vadSession ?: return 0f
            name = vadInputName ?: return 0f
        }
        return try {
            val expected = vadWindowSize().takeIf { it > 0 } ?: return 0f
            val data = FloatArray(expected)
            System.arraycopy(samples, 0, data, 0, minOf(samples.size, expected))
            val tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(data),
                longArrayOf(1, expected.toLong())
            )
            try {
                session.run(mapOf(name to tensor)).use { out ->
                    val first = out.get(0)?.value
                    when (first) {
                        is FloatArray -> first.getOrNull(0) ?: 0f
                        is Array<*> -> ((first.getOrNull(0) as? FloatArray)?.getOrNull(0)) ?: 0f
                        else -> 0f
                    }
                }
            } finally {
                try {
                    tensor.close()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            0f
        }
    }

    fun pcm16ToFloat(pcm: ShortArray, n: Int = pcm.size): FloatArray {
        val out = FloatArray(n.coerceIn(0, pcm.size))
        for (i in out.indices) out[i] = (pcm[i] / 32768f).coerceIn(-1f, 1f)
        return out
    }

    private fun closeVadLocked() {
        try {
            vadSession?.close()
        } catch (_: Exception) {
        }
        vadSession = null
        vadInputName = null
        vadModelPath = null
    }

    fun closeVad() {
        synchronized(lock) { closeVadLocked() }
    }
}
