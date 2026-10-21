package com.karen.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speech output for Karen.
 *
 * Preferred engine: Fish Audio through the direct API when a Fish Audio key is
 * saved, otherwise OpenRouter ([CloudTts]) — cloud voice, MediaPlayer playback,
 * sentence-by-sentence pipeline for Live mode. Fallback: Android's system TTS
 * (zero MB, offline),
 * with personas mapped to pitch/rate variants. STT input stays on the system
 * SpeechRecognizer (VoiceSttManager).
 */
object TtsManager {
    var speaking by mutableStateOf(false)
        private set
    var speakingText by mutableStateOf<String?>(null)
        private set

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Triple<Context, String, String>? = null

    // Realtime stream state: sentences speak as they complete.
    private var streamPending = 0
    private var streamClosed = true
    private var streamSpokenLen = 0
    @Volatile private var streamLatest = ""
    private var streamPersona = "Juniper"
    private var streamCtx: Context? = null
    private var streamDone: (() -> Unit)? = null
    @Volatile private var pumpScheduled = false
    private val pumpRunnable = Runnable {
        pumpScheduled = false
        pumpStream()
    }

    // Cloud voice state (Fish Audio direct, or via OpenRouter).
    private var streamCloud = false
    private var cloudSentences: Channel<String>? = null
    private var cloudJob: Job? = null
    private var cloudPlayer: MediaPlayer? = null
    @Volatile private var cloudPcmStream: CloudTts.PcmStream? = null
    @Volatile private var cloudTrack: AudioTrack? = null
    private var cloudGen = 0
    private var cloudWarned = false
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class CloudClip(val file: File, val text: String)

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    private fun onMain(fn: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) fn()
        else mainHandler.post { fn() }
    }

    /** Persona voice character: pitch multiplier + speech rate. */
    private fun personaProsody(persona: String): Pair<Float, Float> = when (persona) {
        "Sol" -> 0.9f to 0.95f
        "Cove" -> 1.1f to 1.0f
        "Breeze" -> 1.05f to 1.12f
        "Ember" -> 0.85f to 0.9f
        else -> 1.0f to 1.0f // Juniper
    }

    private fun applyProsody(engine: TextToSpeech, ctx: Context, persona: String) {
        val (pitch, rate) = personaProsody(persona)
        try {
            val locale = Locale.getDefault()
            if (engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
                engine.language = locale
            } else {
                engine.language = Locale.US
            }
        } catch (_: Exception) {
        }
        engine.setPitch(pitch)
        engine.setSpeechRate(rate)
    }

    private fun ensure(ctx: Context, onReady: () -> Unit) {
        val cur = tts
        if (ready && cur != null) {
            onReady()
            return
        }
        if (cur == null) {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                onMain {
                    ready = status == TextToSpeech.SUCCESS
                    if (ready) {
                        val p = pending
                        pending = null
                        if (p != null) {
                            speak(p.first, p.second, p.third)
                        } else {
                            onReady()
                        }
                    } else {
                        pending = null
                        speaking = false
                        speakingText = null
                    }
                }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onMain { speaking = true }
                }

                override fun onDone(utteranceId: String?) {
                    onMain {
                        if (utteranceId?.startsWith("karen_stream_") == true) {
                            streamPending = (streamPending - 1).coerceAtLeast(0)
                            checkStreamDone()
                        } else {
                            speaking = false
                            speakingText = null
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onMain {
                        if (utteranceId?.startsWith("karen_stream_") == true) {
                            streamPending = (streamPending - 1).coerceAtLeast(0)
                            checkStreamDone()
                        } else {
                            speaking = false
                            speakingText = null
                        }
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onMain {
                        if (utteranceId?.startsWith("karen_stream_") == true) {
                            streamPending = (streamPending - 1).coerceAtLeast(0)
                            checkStreamDone()
                        } else {
                            speaking = false
                            speakingText = null
                        }
                    }
                }
            })
        } else {
            pending = null
        }
    }

    /** Speaks text aloud (interrupts anything playing). Blank text stops. */
    fun speak(ctx: Context, text: String, persona: String = "Juniper") {
        val clean = text.trim()
        if (clean.isEmpty()) {
            stop()
            return
        }
        if (CloudTts.available(ctx)) speakCloud(ctx, clean, persona) else speakSystem(ctx, clean, persona)
    }

    /** Android's on-device engine (offline fallback and no-key default). */
    private fun speakSystem(ctx: Context, clean: String, persona: String) {
        ensure(ctx) {
            val engine = tts ?: return@ensure
            applyProsody(engine, ctx, persona)
            speakingText = clean
            speaking = true
            engine.speak(clean.take(2000), TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
        }
        // Engine warming up: queue the request for onInit.
        if (!ready) {
            pending = Triple(ctx.applicationContext, clean, persona)
            speakingText = clean
        }
    }

    /** Pre-warms the engine so the first streamed sentence has no init lag. */
    fun warmup(ctx: Context) {
        if (CloudTts.available(ctx)) return // nothing to initialize for cloud voice
        ensure(ctx) {
            if (!streamClosed && streamCtx != null) pumpStream()
        }
    }

    // ---- Cloud voice (Fish Audio via OpenRouter) ----

    /** One notice per app session when cloud voice silently falls back. */
    private fun warnCloudFallback(ctx: Context) {
        if (cloudWarned) return
        cloudWarned = true
        android.widget.Toast.makeText(
            ctx,
            "Fish Audio unavailable — using the system voice",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * Speaks a full reply through Fish Audio: clean markdown, split into
     * request-sized pieces, synthesize and play in order. If a request fails,
     * the remaining text is spoken by the system engine instead of going
     * silent.
     */
    private fun speakCloud(ctx: Context, text: String, persona: String) {
        stop()
        val gen = ++cloudGen
        val appCtx = ctx.applicationContext
        val voice = UserPrefs.fishVoiceId(appCtx)
        // Code-only replies clean to nothing: fall back to the raw text so the
        // read-aloud button is never silent.
        val speakable = CloudTts.cleanForSpeech(text).ifBlank { text }
        val chunks = CloudTts.chunks(speakable, 1800)
        if (chunks.isEmpty()) {
            stop()
            return
        }
        speakingText = text
        speaking = true
        cloudJob = cloudScope.launch {
            var fellBack = false
            var index = 0
            try {
                while (index < chunks.size) {
                    if (gen != cloudGen) return@launch
                    val file = CloudTts.synthesize(appCtx, chunks[index], voice)
                    if (gen != cloudGen) {
                        file.delete()
                        return@launch
                    }
                    // Keep speakingText as the full reply: ChatScreen and
                    // Workspace match it to highlight the active speaker icon.
                    playFile(file, gen)
                    index++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (gen == cloudGen && index < chunks.size) {
                    fellBack = true
                    speaking = false
                    speakingText = null
                    warnCloudFallback(appCtx)
                    speakSystem(appCtx, chunks.drop(index).joinToString(" "), persona)
                }
            } finally {
                if (!fellBack && gen == cloudGen) {
                    speaking = false
                    speakingText = null
                }
            }
        }
    }

    /** Plays one MP3 and suspends until it ends (or is cancelled). */
    private suspend fun playFile(file: File, gen: Int) {
        if (gen != cloudGen) {
            file.delete()
            return
        }
        val player = MediaPlayer()
        cloudPlayer = player
        val done = CompletableDeferred<Unit>()
        try {
            player.setOnCompletionListener { done.complete(Unit) }
            player.setOnErrorListener { _, _, _ ->
                done.complete(Unit)
                true
            }
            player.setDataSource(file.absolutePath)
            withContext(Dispatchers.IO) { player.prepare() }
            if (gen != cloudGen) return
            player.start()
            done.await()
        } finally {
            try {
                player.release()
            } catch (_: Exception) {
            }
            if (cloudPlayer === player) cloudPlayer = null
            file.delete()
        }
    }

    // ---- Realtime sentence streaming ----

    /** Starts a stream: completed sentences speak as tokens arrive. */
    fun startStream(ctx: Context, persona: String = "Juniper", onDone: (() -> Unit)? = null) {
        stop()
        streamCtx = ctx.applicationContext
        streamPersona = persona
        streamPending = 0
        streamSpokenLen = 0
        streamLatest = ""
        streamClosed = false
        streamDone = onDone
        streamCloud = CloudTts.available(ctx)
        // Don't claim to be speaking until audio really starts: the UI can
        // show "Thinking…" during model + first-token latency instead of a
        // misleading "Speaking" state over dead air.
        speaking = false
        speakingText = null
        if (streamCloud) {
            val sentences = Channel<String>(Channel.UNLIMITED)
            cloudSentences = sentences
            if (CloudTts.canStreamPcm(ctx)) {
                startPcmPipeline(ctx.applicationContext, sentences)
            } else {
                startCloudPipeline(ctx.applicationContext, sentences)
            }
        } else {
            ensure(ctx) {
                pumpStream()
            }
        }
    }

    /**
     * Synthesizes sentences on IO while the main coroutine plays them in
     * order. The unbounded clip channel lets the next sentence render during
     * playback, so Live mode keeps a natural cadence instead of a network
     * pause per sentence.
     */
    private fun startCloudPipeline(ctx: Context, sentences: Channel<String>) {
        val gen = ++cloudGen
        val voice = UserPrefs.fishVoiceId(ctx)
        cloudJob = cloudScope.launch {
            val clips = Channel<CloudClip>(Channel.UNLIMITED)
            val producer = launch(Dispatchers.IO) {
                try {
                    for (sentence in sentences) {
                        if (gen != cloudGen) break
                        try {
                            clips.send(CloudClip(CloudTts.synthesize(ctx, sentence, voice), sentence))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Skip a sentence the service could not voice.
                        }
                    }
                } finally {
                    clips.close()
                }
            }
            var played = false
            for (clip in clips) {
                if (gen != cloudGen) {
                    clip.file.delete()
                    break
                }
                speakingText = clip.text
                speaking = true
                try {
                    playFile(clip.file, gen)
                    played = true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A malformed clip is skipped; the queue keeps playing.
                }
            }
            producer.cancel()
            if (gen == cloudGen) {
                // Whole cloud stream produced nothing (key/credit/network):
                // speak the reply with the offline engine rather than silence.
                if (!played && streamLatest.isNotBlank()) {
                    streamCtx = null
                    cloudSentences = null
                    streamDone = null
                    val fallback = CloudTts.cleanForSpeech(streamLatest).ifBlank { streamLatest }
                    warnCloudFallback(ctx)
                    speakSystem(ctx, fallback.take(2000), streamPersona)
                    return@launch
                }
                speaking = false
                speakingText = null
                streamCtx = null
                cloudSentences = null
                val cb = streamDone
                streamDone = null
                cb?.invoke()
            }
        }
    }

    private sealed interface PcmEvent {
        data class Start(val sampleRate: Int, val channels: Int) : PcmEvent
        data class Text(val sentence: String) : PcmEvent
        data class Data(val bytes: ByteArray) : PcmEvent
    }

    /**
     * Low-latency Live-mode path: each sentence is requested as raw PCM and
     * written to one AudioTrack as the service generates it. A small channel
     * between the network producer and the writer lets the next sentence start
     * downloading while the current one is still being heard.
     *
     * If nothing reached the speaker (bad key, PCM rejected, no audio device),
     * the reply is handed to the existing MP3 pipeline, which can still use
     * OpenRouter and finally the system voice.
     */
    private fun startPcmPipeline(ctx: Context, sentences: Channel<String>) {
        val gen = ++cloudGen
        val voice = UserPrefs.fishVoiceId(ctx)
        cloudJob = cloudScope.launch {
            val events = Channel<PcmEvent>(64)
            val anyAudio = AtomicBoolean(false)
            val producer = launch(Dispatchers.IO) {
                try {
                    for (sentence in sentences) {
                        if (gen != cloudGen) break
                        var stream: CloudTts.PcmStream? = null
                        try {
                            stream = CloudTts.openPcmStream(ctx, sentence, voice)
                            cloudPcmStream = stream
                            events.send(PcmEvent.Start(stream.sampleRate, stream.channels))
                            events.send(PcmEvent.Text(sentence))
                            val buffer = ByteArray(8192)
                            while (gen == cloudGen) {
                                val n = stream.input.read(buffer)
                                if (n < 0) break
                                if (n > 0) events.send(PcmEvent.Data(buffer.copyOf(n)))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Nothing heard yet: let the MP3/OpenRouter pipeline
                            // try this sentence instead of failing silently.
                            if (!anyAudio.get()) break
                            // Mid-reply hiccup: skip this sentence and keep the
                            // rest of the reply flowing.
                        } finally {
                            stream?.close()
                            if (cloudPcmStream === stream) cloudPcmStream = null
                        }
                    }
                } finally {
                    events.close()
                }
            }

            var track: AudioTrack? = null
            var sampleRate = 0
            var channels = 1
            var frameBytes = 2
            var pending = ByteArray(0)
            var pendingLen = 0
            var skipCurrent = false
            var written = 0L
            var started = false
            var played = false

            fun bytesPerSecond() = sampleRate * channels * 2

            /** 200 ms fills the gap before the first sample without a long wait. */
            fun prebufferBytes() = maxOf(frameBytes, bytesPerSecond() / 5)

            fun releaseTrack() {
                val old = track
                track = null
                cloudTrack = null
                pendingLen = 0
                started = false
                skipCurrent = true
                try {
                    old?.stop()
                } catch (_: Exception) {
                }
                try {
                    old?.flush()
                } catch (_: Exception) {
                }
                try {
                    old?.release()
                } catch (_: Exception) {
                }
            }

            fun startPlayback(player: AudioTrack) {
                if (started) return
                try {
                    player.play()
                    started = true
                    played = true
                    onMain { if (gen == cloudGen) speaking = true }
                } catch (_: Exception) {
                    releaseTrack()
                }
            }

            /**
             * Writes only whole PCM frames. A partial frame at the end of a
             * network chunk is carried into the next chunk; writing half a
             * sample is heard as a click. `write(..., WRITE_BLOCKING)` also
             * blocks while the track buffer is full, so no audio is dropped
             * when the mixer is momentarily behind.
             */
            suspend fun flushFrames(player: AudioTrack) {
                val usable = pendingLen - (pendingLen % frameBytes)
                if (usable <= 0) return
                var offset = 0
                var zeroRetries = 0
                while (offset < usable) {
                    val n = try {
                        player.write(
                            pending,
                            offset,
                            usable - offset,
                            AudioTrack.WRITE_BLOCKING
                        )
                    } catch (_: Exception) {
                        -1
                    }
                    if (n < 0) {
                        releaseTrack()
                        return
                    }
                    if (n == 0) {
                        // Some devices return 0 while the mixer starts up or
                        // repairs an underrun. Retry briefly instead of
                        // dropping the chunk and leaving a hole in the voice.
                        zeroRetries++
                        if (zeroRetries > 40) {
                            releaseTrack()
                            return
                        }
                        delay(5)
                        continue
                    }
                    zeroRetries = 0
                    offset += n
                    written += n
                    anyAudio.set(true)
                }
                val left = pendingLen - offset
                if (left > 0) System.arraycopy(pending, offset, pending, 0, left)
                pendingLen = left
                if (!started && written >= prebufferBytes()) startPlayback(player)
            }

            try {
                withContext(Dispatchers.IO) {
                    for (event in events) {
                        if (gen != cloudGen) break
                        when (event) {
                            is PcmEvent.Start -> {
                                val formatChanged = event.sampleRate != sampleRate ||
                                    event.channels != channels
                                if (track == null || formatChanged) {
                                    if (track != null) releaseTrack()
                                    sampleRate = event.sampleRate
                                    channels = event.channels
                                    frameBytes = 2 * channels.coerceAtLeast(1)
                                    pending = ByteArray(0)
                                    pendingLen = 0
                                    written = 0L
                                    started = false
                                    track = createPcmTrack(sampleRate, channels)
                                    cloudTrack = track
                                    skipCurrent = track == null
                                } else {
                                    // Same format: the next sentence reuses this
                                    // track. Clear a transient failure so a
                                    // later sentence can still be heard.
                                    skipCurrent = false
                                }
                            }
                            is PcmEvent.Text -> onMain {
                                if (gen == cloudGen) speakingText = event.sentence
                            }
                            is PcmEvent.Data -> {
                                val player = track ?: continue
                                if (skipCurrent) continue
                                val need = pendingLen + event.bytes.size
                                if (pending.size < need) {
                                    pending = pending.copyOf(
                                        maxOf(need, pending.size * 2, 8192)
                                    )
                                }
                                System.arraycopy(
                                    event.bytes,
                                    0,
                                    pending,
                                    pendingLen,
                                    event.bytes.size
                                )
                                pendingLen = need
                                flushFrames(player)
                            }
                        }
                    }
                    // Flush the last complete frame, then start playback if the
                    // sentence was shorter than the prebuffer.
                    if (gen == cloudGen && !skipCurrent && pendingLen >= frameBytes) {
                        track?.let { flushFrames(it) }
                    }
                    val playTrack = track
                    if (gen == cloudGen && playTrack != null && written > 0 && !started) {
                        startPlayback(playTrack)
                    }
                    // Let the hardware buffer finish before releasing the track.
                    if (gen == cloudGen && written > 0 && track != null) {
                        val frames = (written / frameBytes.coerceAtLeast(1)).toInt()
                        val durationMs = frames * 1000L / sampleRate.coerceAtLeast(1)
                        val deadline = SystemClock.elapsedRealtime() + durationMs + 2500
                        while (gen == cloudGen &&
                            (track?.playbackHeadPosition ?: 0) < frames &&
                            SystemClock.elapsedRealtime() < deadline
                        ) {
                            delay(40)
                        }
                    }
                }
            } finally {
                producer.cancel()
                try {
                    track?.stop()
                } catch (_: Exception) {
                }
                try {
                    track?.release()
                } catch (_: Exception) {
                }
                if (cloudTrack === track) cloudTrack = null
            }

            if (gen != cloudGen) return@launch
            if (!played) {
                // Nothing was audible. Hand the known reply to the MP3
                // pipeline; it can still fall back to OpenRouter and then the
                // system voice. Future sentences continue through `retry`.
                val retry = Channel<String>(Channel.UNLIMITED)
                cloudSentences = retry
                val known = streamLatest
                streamSpokenLen = known.length
                val spoken = CloudTts.cleanForSpeech(known).ifBlank { known }
                if (spoken.isNotBlank()) retry.trySend(spoken)
                if (streamClosed) retry.close()
                startCloudPipeline(ctx, retry)
                return@launch
            }
            speaking = false
            speakingText = null
            streamCtx = null
            cloudSentences = null
            val cb = streamDone
            streamDone = null
            cb?.invoke()
        }
    }

    private fun createPcmTrack(sampleRate: Int, channels: Int): AudioTrack? {
        val channelMask = if (channels == 2) {
            AudioFormat.CHANNEL_OUT_STEREO
        } else {
            AudioFormat.CHANNEL_OUT_MONO
        }
        val min = AudioTrack.getMinBufferSize(
            sampleRate,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return null
        // A full second of slack absorbs Bluetooth/A2DP and network jitter.
        // It does not delay the first sample: playback starts as soon as the
        // 200 ms prebuffer has been written.
        val bytesPerSecond = sampleRate * channels * 2
        val bufferSize = maxOf(min * 4, bytesPerSecond)
        return try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) {
            null
        }
    }

    /** Feeds the latest full text; newly completed sentences are spoken. */
    fun pushStream(fullText: String) {
        streamLatest = fullText
        // Token streams call this dozens of times a second. Coalesce the main
        // thread pumps so TTS starts on time instead of queueing behind UI work.
        if (pumpScheduled) return
        pumpScheduled = true
        mainHandler.postDelayed(pumpRunnable, 16L)
    }

    /** Speaks whatever is left and closes the stream. */
    fun endStream(fullText: String) {
        streamLatest = fullText
        streamClosed = true
        onMain {
            mainHandler.removeCallbacks(pumpRunnable)
            pumpScheduled = false
            pumpStream()
            val rest = streamLatest.substring(streamSpokenLen.coerceIn(0, streamLatest.length)).trim()
            streamSpokenLen = streamLatest.length
            val ctx = streamCtx
            if (streamCloud) {
                if (rest.isNotBlank()) {
                    val spoken = CloudTts.cleanForSpeech(rest)
                    if (spoken.isNotBlank()) cloudSentences?.trySend(spoken)
                }
                cloudSentences?.close()
                cloudSentences = null
                // The pipeline plays the queue, then clears speaking/onDone.
            } else if (rest.isNotBlank() && ctx != null) {
                if (ready) speakChunkNow(ctx, rest) else speakSystem(ctx, rest, streamPersona)
            } else {
                checkStreamDone()
            }
        }
    }

    /**
     * Emits the *first* completed thought, not the last one in the buffer.
     * The old loop scanned to the end and spoke a whole paragraph as one
     * request; this starts audio on the first sentence and lets the pipeline
     * synthesize the next one while it plays.
     */
    private fun pumpStream() {
        val ctx = streamCtx ?: return
        // No streamClosed guard: endStream calls pump once so a complete final
        // sentence starts its own TTS request before the remainder is queued.
        if (!streamCloud && !ready) return
        val text = streamLatest
        val start = streamSpokenLen.coerceIn(0, text.length)
        if (start >= text.length) return
        val firstChunk = start == 0
        var end = -1
        var idx = start
        while (idx < text.length && end < 0) {
            val c = text[idx]
            val next = if (idx + 1 < text.length) text[idx + 1] else null
            val sentence = c == '!' || c == '?' || c == '\n' ||
                (c == '.' && (next == null || next.isWhitespace()))
            val clause = c == ',' || c == ';' || c == ':'
            val len = idx + 1 - start
            val minSentence = if (firstChunk) 2 else 8
            val minClause = if (firstChunk) 28 else 120
            if (sentence && len >= minSentence) {
                end = idx + 1
            } else if (clause && len >= minClause) {
                end = idx + 1
            }
            idx++
        }
        // No punctuation yet: don't wait for a whole paragraph. Cut at the
        // last word boundary after a sensible amount of speech.
        if (end < 0 && text.length - start >= 180) {
            val cap = (start + 180).coerceAtMost(text.length)
            val space = text.lastIndexOf(' ', cap)
            if (space > start + 40) end = space + 1
        }
        if (end > start) {
            val chunk = text.substring(start, end).trim()
            streamSpokenLen = end
            emitChunk(ctx, chunk)
        }
    }

    private fun emitChunk(ctx: Context, chunk: String) {
        if (chunk.isBlank()) return
        if (streamCloud) {
            val spoken = CloudTts.cleanForSpeech(chunk)
            if (spoken.isNotBlank()) cloudSentences?.trySend(spoken)
        } else {
            speakChunkNow(ctx, chunk)
        }
    }

    private fun speakChunkNow(ctx: Context, chunk: String) {
        val engine = tts ?: return
        try {
            applyProsody(engine, ctx, streamPersona)
            speakingText = chunk
            speaking = true
            val id = "karen_stream_${streamPending}"
            streamPending++
            engine.speak(chunk.take(1200), TextToSpeech.QUEUE_ADD, null, id)
        } catch (_: Exception) {
        }
    }

    private fun checkStreamDone() {
        if (streamClosed && streamPending <= 0) {
            speaking = false
            speakingText = null
            streamCtx = null
            val cb = streamDone
            streamDone = null
            cb?.invoke()
        }
    }

    /** Toggles playback for the given text. */
    fun toggle(ctx: Context, text: String, persona: String = "Juniper") {
        if (speaking) stop() else speak(ctx, text, persona)
    }

    fun stop() {
        cloudGen++
        cloudJob?.cancel()
        cloudJob = null
        cloudSentences?.close()
        cloudSentences = null
        streamCloud = false
        try {
            cloudPcmStream?.close()
        } catch (_: Exception) {
        }
        cloudPcmStream = null
        try {
            cloudTrack?.pause()
        } catch (_: Exception) {
        }
        try {
            cloudTrack?.flush()
        } catch (_: Exception) {
        }
        try {
            cloudTrack?.release()
        } catch (_: Exception) {
        }
        cloudTrack = null
        try {
            cloudPlayer?.release()
        } catch (_: Exception) {
        }
        cloudPlayer = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        mainHandler.removeCallbacks(pumpRunnable)
        pumpScheduled = false
        pending = null
        streamPending = 0
        streamClosed = true
        streamSpokenLen = 0
        streamLatest = ""
        streamCtx = null
        streamDone = null
        speaking = false
        speakingText = null
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }
}
