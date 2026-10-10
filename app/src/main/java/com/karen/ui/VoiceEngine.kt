package com.karen.ui

import android.content.Context
import java.io.File

/**
 * Voice runtime seam: Whistle (.cact, Cactus Needle engine) and sherpa-onnx
 * files (silero VAD, transducers via onnxruntime).
 *
 * What works today: downloading/listing/deleting voice weights
 * (ModelManager → Voice Models) plus the zero-MB system STT/TTS engines.
 *
 * What "use it" still needs (one native drop-in, no app redesign):
 *  1. Vendor `android-arm64/libneedle.a` + `needle.h` from
 *     https://huggingface.co/Cactus-Compute/needle3 (arm64 only — there is
 *     NO x86_64 Android slice, so the emulator can stage but never run it).
 *  2. Add `karen_voice.cpp` JNI bridge calling the C API from needle.h:
 *     `needle_load(whistle.cact)` once, then `needle_transcribe()` for clips
 *     or `needle_stream_transcribe_process/stop()` for the mic, plus
 *     `needle_embed()` for retrieval. Link the static lib in CMakeLists.txt.
 *  3. Feed it 16 kHz mono PCM from AudioRecord (RECORD_AUDIO permission
 *     already exists for voice memos) and route results into VoiceSttManager.
 *  sherpa-onnx files need onnxruntime Android AARs the same way.
 *
 * Until an engine .so ships, [engineReady] is false and the UI says so —
 * models are staged, never pretended runnable.
 */
object VoiceEngine {
    fun voicesDir(ctx: Context): File = ModelDownloader.voicesDir(ctx)

    fun whistleFile(ctx: Context): File = File(voicesDir(ctx), "whistle.cact")
    fun sileroFile(ctx: Context): File = File(voicesDir(ctx), "silero_vad.onnx")

    fun hasWhistle(ctx: Context): Boolean =
        whistleFile(ctx).let { it.exists() && it.length() > 1_000_000 }

    fun hasSilero(ctx: Context): Boolean =
        sileroFile(ctx).let { it.exists() && it.length() > 10_000 }

    /** Needle ships arm64/armv7 Android slices only — never the emulator ABI. */
    fun needleSupportedAbi(): Boolean =
        android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }

    /** False until a needle/sherpa bridge .so is bundled (see above). */
    fun engineReady(): Boolean = false

    /** One-line engine state for Model Manager rows. */
    fun status(ctx: Context, entry: ModelDownloader.VoiceModel): String {
        val file = ModelDownloader.voiceFile(ctx, entry.fileName)
        if (!file.exists() || file.length() <= 0) return "Not downloaded"
        if (!needleSupportedAbi() && entry.engine.startsWith("Cactus")) {
            return "Downloaded · needs Needle engine (arm64 device)"
        }
        return "Downloaded · engine pending"
    }
}
