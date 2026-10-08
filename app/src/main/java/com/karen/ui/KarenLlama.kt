package com.karen.ui

import java.io.File

/**
 * On-device inference through the bundled llama.cpp core (CPU only).
 * One model stays loaded at a time; calls are serialized and must run on
 * Dispatchers.IO. Text crosses JNI as UTF-16 so emoji survives intact.
 */
object KarenLlama {
    /** False when the native library failed to load (e.g. missing ABI build). */
    var ready = false
        private set

    init {
        try {
            System.loadLibrary("karen_llama")
            ready = true
        } catch (_: UnsatisfiedLinkError) {
            ready = false
        }
    }

    private var handle = 0L
    private var loadedName = ""
    private var loadedCtx = 0

    fun isLoaded(name: String): Boolean = handle != 0L && loadedName == name

    @Synchronized
    fun ensureLoaded(file: File, displayName: String, nCtx: Int = 2048, nThreads: Int = 4): Boolean {
        if (!ready) return false
        // Same model but a different context window (e.g. user raised it to
        // 16k in Settings): reload so the new n_ctx actually applies.
        if (handle != 0L && loadedName == displayName && loadedCtx == nCtx) return true
        free()
        return try {
            handle = nativeInit(file.absolutePath, nCtx, nThreads)
            if (handle == 0L) {
                loadedName = ""
                loadedCtx = 0
                false
            } else {
                loadedName = displayName
                loadedCtx = nCtx
                true
            }
        } catch (_: Exception) {
            handle = 0L
            loadedName = ""
            loadedCtx = 0
            false
        }
    }

    @Synchronized
    fun complete(system: String, roles: Array<String>, texts: Array<String>, maxTokens: Int): String {
        check(handle != 0L) { "model not loaded" }
        return nativeComplete(handle, system, roles, texts, maxTokens) ?: ""
    }

    fun cancel() {
        if (handle != 0L) {
            try {
                nativeCancel(handle)
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun free() {
        if (handle != 0L) {
            try {
                nativeFree(handle)
            } catch (_: Exception) {
            }
            handle = 0L
            loadedName = ""
            loadedCtx = 0
        }
    }

    private external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeComplete(
        handle: Long,
        system: String,
        roles: Array<String>,
        texts: Array<String>,
        maxTokens: Int
    ): String?

    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
}
