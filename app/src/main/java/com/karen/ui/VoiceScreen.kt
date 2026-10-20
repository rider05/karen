package com.karen.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.karen.rememberDeviceTelemetry
import com.karen.rememberVoiceStt

/** Spoken-reply instruction: short, human, TTS-safe answers for Live mode. */
const val VOICE_REPLY_STYLE =
    "Answer immediately with the useful part first — no preamble, no filler, " +
        "no restating the question. Reply as natural human speech for text-to-speech: " +
        "1-3 short spoken sentences, warm and conversational, plain words only — " +
        "no markdown, no lists, no tables, no code, no emojis, no stage directions, " +
        "no bullet points."

/**
 * One useful spoken sentence for a failed Live-mode chat request.
 *
 * Deliberately does not echo raw exception text: Gemini puts its key in the
 * request URL, and transport errors can include that URL. Status codes are
 * enough to tell the user which key/quota/model to fix.
 */
private fun voiceFailureText(e: Exception): String {
    val status = (e as? CloudApiException)?.status
    return when {
        status == 400 -> "The chat provider rejected the request. Check the selected model."
        status == 401 || status == 403 -> "The chat provider rejected the API key."
        status == 402 -> "The chat provider says the account has no credits or quota left."
        status == 404 -> "The selected chat model was not found."
        status == 408 || status == 504 -> "The chat provider timed out."
        status == 429 -> "The chat provider is rate-limiting requests right now."
        status != null && status >= 500 -> "The chat provider is temporarily unavailable."
        e is java.net.SocketTimeoutException -> "The chat provider timed out."
        e is java.net.UnknownHostException || e is java.net.ConnectException ->
            "Could not reach the chat provider. Check the connection and key."
        else -> "The chat request failed. Check the provider key and model in Model Manager."
    }
}

/**
 * Voice turns care about first-token time, not the largest model. When the
 * user has not pinned a specific cloud model, use the provider's small fast
 * sibling instead of its flagship default.
 */
private fun voiceModelFor(provider: CloudProvider, stored: String): String {
    if (stored.isNotBlank()) return stored
    return when (provider.id) {
        "groq" -> "llama-3.1-8b-instant"
        "openai" -> "gpt-4o-mini"
        "sambanova" -> "Meta-Llama-3.1-8B-Instruct"
        "githubmodels" -> "openai/gpt-4o-mini"
        "nvidia" -> "meta/llama-3.1-8b-instruct"
        else -> stored
    }
}

@Composable
fun VoiceScreen(
    onClose: () -> Unit = {},
    onOpenModelManager: () -> Unit = {}
) {
    val device = rememberDeviceTelemetry()
    var isMuted by remember { mutableStateOf(false) }
    var userSpokenText by remember { mutableStateOf("") }
    var isVisionActive by remember { mutableStateOf(false) }

    // Real speech output state; stop it when leaving Live mode.
    val isSpeaking = TtsManager.speaking
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { TtsManager.stop() }
    }

    val colors = LocalKarenColors.current
    val personaCtx = androidx.compose.ui.platform.LocalContext.current
    var voicePersona by remember { mutableStateOf(UserPrefs.voicePersona(personaCtx)) }
    var personaMenu by remember { mutableStateOf(false) }

    // Fish Audio voice is editable here, not only in Settings. The persona
    // remains the Android TTS fallback when no cloud key is configured.
    val fishReady = UserPrefs.fishAudioKey(personaCtx).isNotBlank() ||
        UserPrefs.apiKey(personaCtx, "openrouter").isNotBlank()
    var fishVoice by remember { mutableStateOf(UserPrefs.fishVoiceId(personaCtx)) }
    var fishVoiceName by remember { mutableStateOf(UserPrefs.fishVoiceName(personaCtx)) }
    var showVoicePicker by remember { mutableStateOf(false) }
    val selectedVoiceLabel = fishVoiceDisplayName(fishVoice, fishVoiceName)
    val activeVoiceLabel = if (CloudTts.available(personaCtx)) selectedVoiceLabel else voicePersona

    // System back closes the voice picker first, then Live mode.
    androidx.activity.compose.BackHandler {
        if (showVoicePicker) showVoicePicker = false else onClose()
    }

    // Voice chat loop: STT transcript → model API → human-spoken TTS reply.
    var voiceBusy by remember { mutableStateOf(false) }
    var voiceReply by remember { mutableStateOf("") }
    // True when a turn failed for a setup reason (no chat model/key, or the
    // provider rejected the key/model). The transcript then offers a direct
    // jump to Model Manager instead of only telling the user to go find it.
    var voiceNeedsModel by remember { mutableStateOf(false) }
    var cancelVoice by remember { mutableStateOf(false) }
    var autoVoice by remember { mutableStateOf(true) }
    var autoArmed by remember { mutableStateOf(false) }
    var wasSpeaking by remember { mutableStateOf(false) }
    val voiceScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    // A quiet tick when the turn leaves the microphone and moves to the
    // model. It makes the 1-2 seconds of thinking feel acknowledged.
    androidx.compose.runtime.LaunchedEffect(voiceBusy) {
        if (voiceBusy) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    fun runVoiceTurn(prompt: String) {
        val text = prompt.trim()
        if (text.isBlank() || voiceBusy) return
        voiceBusy = true
        cancelVoice = false
        voiceReply = ""
        voiceNeedsModel = false
        voiceScope.launch {
            try {
                val customs = UserPrefs.customProviders(personaCtx)
                val locals = UserPrefs.models(personaCtx)
                    .filter {
                        !isCloudProviderName(it, customs) && !isEmbeddingModel(it) &&
                            ModelDownloader.isRunnableWeight(personaCtx, it)
                    }
                val keyed = allCloudProviders(personaCtx).filter { UserPrefs.apiKey(personaCtx, it.id).isNotBlank() }
                val manual = UserPrefs.selectedModel(personaCtx).takeIf { isSelectableModel(personaCtx, it) }
                val manualCloud = manual?.let { findCloudProviderByName(it, customs) }
                val manualLocal = manual?.takeIf { it in locals }
                // Route even when Auto is off: if the manual pick vanished,
                // still use any installed/keyed model instead of failing.
                val routed = routeModel(
                    text, locals, keyed, UserPrefs.autoCloud(personaCtx),
                    UserPrefs.preferredLocalModel(personaCtx), UserPrefs.preferredApiModel(personaCtx)
                )?.modelName
                val auto = UserPrefs.autoRoute(personaCtx)
                // Speech has no "typing" time to hide behind: loading a local
                // GGUF can take seconds before the first token. With auto
                // routing on, take the fastest connected cloud provider; local
                // still wins when it is the only option or the user pinned it.
                // Keep cloud routing off if the user disabled it.
                val fastestCloud = if (UserPrefs.autoCloud(personaCtx)) {
                    keyed.maxByOrNull { scoreCloud(it.id, detectIntent(text)) }?.name
                } else {
                    null
                }
                val pick = when {
                    !auto && manualCloud != null -> manualCloud.name
                    !auto && manualLocal != null -> manualLocal
                    auto && fastestCloud != null -> fastestCloud
                    manualCloud != null -> manualCloud.name
                    manualLocal != null -> manualLocal
                    else -> routed
                }
                val cloud = pick?.let { findCloudProviderByName(it, customs) }
                val key = cloud?.let { UserPrefs.apiKey(personaCtx, it.id) }.orEmpty()
                var failure: String? = null
                // A setup problem (missing key/model) is worth an inline jump
                // to Model Manager; a transient network failure is not.
                var needsSetup = false
                // Realtime: stream tokens straight into sentence TTS so speech
                // starts on the first sentence instead of the full reply.
                var full = ""
                var lastUiAt = 0L
                val speechFinished = java.util.concurrent.atomic.AtomicBoolean(false)
                TtsManager.startStream(personaCtx, voicePersona) {
                    speechFinished.set(true)
                }
                fun publish(force: Boolean = false) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (force || now - lastUiAt >= 70L) {
                        lastUiAt = now
                        voiceReply = full
                    }
                }
                fun onPiece(piece: String) {
                    if (cancelVoice) return
                    full += piece
                    TtsManager.pushStream(full)
                    // Recomposition per token fights the orb animation; 14 fps
                    // is plenty for the live transcript and keeps the main
                    // thread free for the first TTS pump.
                    publish()
                }
                if (cloud != null && key.isNotBlank()) {
                    // One failing provider should not sink the turn when another
                    // connected key works. The chosen provider goes first, then
                    // the best remaining keyed providers for this intent.
                    val preferredApi = UserPrefs.preferredApiModel(personaCtx)
                    val candidates = buildList {
                        add(cloud to key)
                        keyed.filter { it.name != cloud.name }
                            .sortedByDescending {
                                scoreCloud(it.id, detectIntent(text)) +
                                    if (it.name == preferredApi) 8 else 0
                            }
                            .forEach { add(it to UserPrefs.apiKey(personaCtx, it.id)) }
                    }
                    for ((provider, providerKey) in candidates) {
                        if (cancelVoice) break
                        val voiceModel = voiceModelFor(
                            provider,
                            UserPrefs.apiModel(personaCtx, provider.id)
                        )
                        val streamText = try {
                            provider.withModelFallback(personaCtx, voiceModel) { useModel ->
                                provider.completeStreaming(
                                    providerKey,
                                    listOf("user" to "$VOICE_REPLY_STYLE\n\n$text"),
                                    maxTokensFor("Low"),
                                    thinkingBudget = null,
                                    historyLimit = 4,
                                    model = useModel,
                                    onToken = ::onPiece,
                                    isCancelled = { cancelVoice },
                                    connectTimeoutMs = 6000,
                                    readTimeoutMs = 12000
                                )
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (cancelVoice) {
                                TtsManager.stop()
                                return@launch
                            }
                            ""
                        }
                        if (cancelVoice) break
                        if (full.isBlank() && streamText.isNotBlank()) {
                            full = streamText
                            publish(force = true)
                        }
                        // A partial stream must not be replayed by another
                        // provider; only fail over when nothing was produced.
                        if (full.isNotBlank()) break
                        // Streaming can succeed with no token events (or fail
                        // after the endpoint rejected SSE). A one-shot call is
                        // the bridge before trying the next connected key.
                        val once = try {
                            provider.withModelFallback(personaCtx, voiceModel) { useModel ->
                                provider.complete(
                                    providerKey,
                                    listOf("user" to "$VOICE_REPLY_STYLE\n\n$text"),
                                    maxTokensFor("Low"),
                                    thinkingBudget = null,
                                    historyLimit = 4,
                                    model = useModel,
                                    timeoutMs = 20000
                                )
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failure = voiceFailureText(e)
                            val status = (e as? CloudApiException)?.status
                            if (status != null && status in 400..404) needsSetup = true
                            ""
                        }
                        if (!cancelVoice && once.isNotBlank()) {
                            full = once
                            publish(force = true)
                            break
                        }
                    }
                } else if (cloud != null) {
                    failure = "No API key is stored for ${cloud.name}."
                    needsSetup = true
                } else {
                    val modelName = pick
                    val wf = if (modelName != null) ModelDownloader.weightFileFor(personaCtx, modelName) else null
                    if (wf != null && KarenLlama.ready && modelName != null) {
                        val threads = maxOf(2, minOf(6, Runtime.getRuntime().availableProcessors()))
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            KarenLlama.ensureLoaded(wf, modelName, 2048, threads)
                        }
                        if (ok && !cancelVoice) {
                            val reply = try {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    KarenLlama.completeStream(
                                        "You are Karen, a voice assistant. $VOICE_REPLY_STYLE",
                                        arrayOf("user"),
                                        arrayOf(text),
                                        maxTokensFor("Low")
                                    ) { piece ->
                                        if (!cancelVoice) {
                                            full += piece
                                            TtsManager.pushStream(full)
                                            publish()
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (!cancelVoice) {
                                    failure = "The on-device model failed: " +
                                        (e.message?.trim()?.take(120) ?: "unknown error")
                                }
                                null
                            }
                            if (!reply.isNullOrBlank()) full = reply.trim()
                            if (!cancelVoice) voiceReply = full
                        } else if (!cancelVoice) {
                            failure = "Could not load $modelName on this device."
                            needsSetup = true
                        }
                    } else if (!cancelVoice && modelName != null) {
                        failure = if (!KarenLlama.ready) {
                            "The on-device model runtime is unavailable."
                        } else {
                            "No runnable GGUF is installed for $modelName."
                        }
                        needsSetup = true
                    }
                }
                if (!cancelVoice) TtsManager.endStream(full) else TtsManager.stop()
                if (cancelVoice) return@launch
                val said = full.trim()
                if (said.isNotBlank()) {
                    voiceReply = said
                    voiceNeedsModel = false
                    autoArmed = true
                } else {
                    TtsManager.stop()
                    val noModel = pick == null
                    voiceNeedsModel = noModel || needsSetup
                    voiceReply = when {
                        noModel && keyed.isNotEmpty() ->
                            "A chat API key is connected, but cloud routing is off. Turn on cloud routing or choose a model in Model Manager."
                        noModel && CloudTts.available(personaCtx) ->
                            "Fish Audio is set up for the voice, but no chat model is connected. Add a chat API key (for example OpenRouter) or download a local GGUF in Model Manager."
                        noModel ->
                            "Connect a chat model in Model Manager to talk by voice."
                        failure != null ->
                            "I couldn't answer that. $failure"
                        else ->
                            "I couldn't answer that. The model returned an empty reply."
                    }
                    TtsManager.speak(personaCtx, voiceReply, voicePersona)
                }
                // Keep "Thinking…" up until the first audio actually starts
                // (or the clip finishes instantly). Without this, the pill
                // flips to Ready during the TTS header wait and invites a tap
                // that cancels the pending reply.
                if (!cancelVoice && said.isNotBlank()) {
                    kotlinx.coroutines.withTimeoutOrNull(4000) {
                        while (!speechFinished.get() && !TtsManager.speaking) {
                            kotlinx.coroutines.delay(50)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!cancelVoice) {
                    TtsManager.stop()
                    voiceNeedsModel = false
                    voiceReply = "Sorry, something went wrong. ${voiceFailureText(e)}"
                    TtsManager.speak(personaCtx, voiceReply, voicePersona)
                }
            } finally {
                voiceBusy = false
            }
        }
    }

    val voiceStt = rememberVoiceStt(
        onResult = { result ->
            userSpokenText = result
            runVoiceTurn(result)
        },
        onPartialResult = { interim ->
            userSpokenText = interim
        }
    )
    val isTranscribing = voiceStt.state.isTranscribing
    val isThinking = voiceBusy && !isSpeaking

    // Hands-free loop: when the spoken reply finishes, listen again.
    androidx.compose.runtime.LaunchedEffect(TtsManager.speaking) {
        if (wasSpeaking && !TtsManager.speaking) {
            if (autoArmed && !cancelVoice && autoVoice && !isMuted && voiceReply.isNotBlank()) {
                autoArmed = false
                kotlinx.coroutines.delay(100)
                voiceStt.startListening()
            } else {
                autoArmed = false
            }
        }
        wasSpeaking = TtsManager.speaking
    }

    /** Barge-in: user talks over Karen → stop everything, listen to them. */
    fun interruptVoice() {
        if (!TtsManager.speaking && !voiceBusy) return
        cancelVoice = true
        autoArmed = false
        try {
            voiceStt.cancel()
        } catch (_: Exception) {
        }
        TtsManager.stop()
        try {
            KarenLlama.cancel()
        } catch (_: Exception) {
        }
        voiceBusy = false
        voiceReply = ""
        voiceStt.startListening()
    }

    // Warm the TTS engine and start listening on entry (Gemini-style).
    androidx.compose.runtime.LaunchedEffect(Unit) {
        TtsManager.warmup(personaCtx)
        // DNS/TLS + VAD warm in parallel; neither blocks the mic.
        voiceScope.launch(kotlinx.coroutines.Dispatchers.IO) { CloudTts.warmup(personaCtx) }
        voiceScope.launch(kotlinx.coroutines.Dispatchers.IO) { VoiceEngine.vadReady(personaCtx) }
        kotlinx.coroutines.delay(250)
        if (!voiceBusy && !TtsManager.speaking && !isMuted) voiceStt.startListening()
    }

    // Barge-in watch: Silero VAD voice probability while speaking
    // interrupts; raw loudness is only the fallback when VAD is absent.
    // No echo cancellation on-device, so either path needs sustained
    // voice — TTS echo alone should not trip it.
    androidx.compose.runtime.LaunchedEffect(TtsManager.speaking) {
        if (!TtsManager.speaking) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var rec: android.media.AudioRecord? = null
            try {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        personaCtx,
                        android.Manifest.permission.RECORD_AUDIO
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) return@withContext
                val useVad = VoiceEngine.vadReady(personaCtx)
                val window = if (useVad) OrtEngine.vadWindowSize().takeIf { it > 0 } ?: 512 else 1600
                val sr = 16000
                val minBuf = android.media.AudioRecord.getMinBufferSize(
                    sr,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) return@withContext
                rec = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sr,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf * 2, window * 4)
                )
                if (rec.state != android.media.AudioRecord.STATE_INITIALIZED) return@withContext
                rec.startRecording()
                kotlinx.coroutines.delay(500)
                val buf = ShortArray(window)
                val carry = mutableListOf<Short>()
                var voiced = 0
                // VAD reacts in ~0.2s (6 windows); legacy loudness needs ~0.8s.
                val need = if (useVad) 6 else 8
                while (TtsManager.speaking && !cancelVoice) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var sum = 0L
                    for (i in 0 until n) {
                        val v = buf[i].toInt()
                        sum += (v * v).toLong()
                    }
                    val rms = kotlin.math.sqrt(sum.toDouble() / n)
                    val isVoice = if (useVad) {
                        // Cheap energy gate first: silence never reaches ORT.
                        if (rms < 250) {
                            carry.clear()
                            false
                        } else {
                            for (i in 0 until n) carry.add(buf[i])
                            var heard = false
                            while (carry.size >= window) {
                                val frame = ShortArray(window) { carry[it] }
                                repeat(window) { carry.removeAt(0) }
                                if (OrtEngine.vadProb(OrtEngine.pcm16ToFloat(frame)) >= 0.5f) {
                                    heard = true
                                }
                            }
                            heard
                        }
                    } else {
                        rms > 2000
                    }
                    if (isVoice) voiced++ else voiced = 0
                    if (voiced >= need) break
                }
                if (voiced >= need && TtsManager.speaking && !cancelVoice) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        interruptVoice()
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    rec?.stop()
                } catch (_: Exception) {
                }
                try {
                    rec?.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb")

    // Rotation animation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                when {
                    isSpeaking || voiceStt.state.isListening -> 5000
                    isThinking || isTranscribing -> 2600
                    else -> 12000
                },
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_rotation"
    )

    // Breathing scale animation
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = when {
            isSpeaking -> 1.15f
            isThinking || isTranscribing -> 1.07f
            else -> 1.02f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(
                when {
                    isSpeaking -> 1200
                    isThinking || isTranscribing -> 850
                    else -> 2400
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_breathe"
    )

    val audioScale = breatheScale + (if (voiceStt.state.isListening) voiceStt.state.rmsDb * 0.35f else 0f)

    // Pulsing alpha for the status dot
    val statusPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Voice Top Bar: close + persisted TTS voice persona picker.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Close / Back button
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(1.dp, colors.border, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Live mode",
                    tint = colors.textPrimary
                )
            }

            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (fishReady) {
                        // Fish Audio voice used by cloud TTS; tap to change it.
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                                .clickable { showVoicePicker = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = colors.accentGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = selectedVoiceLabel,
                                color = colors.textPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 130.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Change Fish Audio voice",
                                tint = colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        // Android TTS fallback prosody, opened separately so
                        // the primary pill always belongs to the Fish voice.
                        IconButton(
                            onClick = { personaMenu = true },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(colors.surface)
                                .border(1.dp, colors.border, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "System fallback voice",
                                tint = colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        // No cloud key: the persona drives Android TTS.
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                                .clickable { personaMenu = true }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.RecordVoiceOver,
                                contentDescription = null,
                                tint = colors.accentGreen,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = voicePersona,
                                color = colors.textPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Select system voice",
                                tint = colors.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                KDropdownMenu(
                    expanded = personaMenu,
                    onDismissRequest = { personaMenu = false }
                ) {
                    UserPrefs.voicePersonas.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(v, color = if (v == voicePersona) colors.accentGreen else colors.textPrimary) },
                            onClick = {
                                voicePersona = v
                                UserPrefs.setVoicePersona(personaCtx, v)
                                personaMenu = false
                            }
                        )
                    }
                }
            }
        }

        // Live status card: centered, rounded on all four corners.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                voiceStt.state.isListening -> colors.accentGreen.copy(alpha = statusPulse)
                                isSpeaking -> colors.accentBlue.copy(alpha = statusPulse)
                                isThinking || isTranscribing -> colors.accentGreen.copy(alpha = statusPulse)
                                else -> colors.textMuted
                            }
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        voiceStt.state.isListening -> "Listening..."
                        isSpeaking -> "Speaking · ${if (CloudTts.available(personaCtx)) selectedVoiceLabel else "System TTS"}"
                        isTranscribing -> "Got it…"
                        isThinking -> "Thinking…"
                        else -> "Ready · Tap to Speak"
                    },
                    color = colors.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Center Stage: ChatGPT Advanced Glowing Orb & Transcript
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Interactive ChatGPT Voice Orb
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        if (voiceBusy || isSpeaking) {
                            cancelVoice = true
                            autoArmed = false
                            voiceStt.stopListening()
                            TtsManager.stop()
                        } else if (voiceStt.state.isListening) {
                            voiceStt.stopListening()
                        } else {
                            cancelVoice = false
                            voiceReply = ""
                            voiceNeedsModel = false
                            voiceStt.startListening()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val baseRadius = size.minDimension / 2.8f

                    // Layer 0: Ambient Outer Glow
                    scale(audioScale) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF38BDF8).copy(alpha = 0.35f),
                                    Color(0xFF10A37F).copy(alpha = 0.20f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = baseRadius * 1.6f
                            ),
                            radius = baseRadius * 1.6f,
                            center = center
                        )
                    }

                    // Layer 1: Rotating Multi-color Nebula Gradient
                    rotate(rotation) {
                        scale(audioScale * 0.95f) {
                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color(0xFF10A37F),
                                        Color(0xFF38BDF8),
                                        Color(0xFF6366F1),
                                        Color(0xFF10A37F)
                                    ),
                                    center = center
                                ),
                                radius = baseRadius * 1.1f,
                                center = center,
                                alpha = 0.75f
                            )
                        }
                    }

                    // Layer 2: Dynamic Core Fluid Body
                    scale(audioScale) {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFFFFFF),
                                    Color(0xFFBAE6FD),
                                    Color(0xFF0284C7),
                                    Color(0xFF0F172A)
                                ),
                                center = Offset(center.x - 20f, center.y - 20f),
                                radius = baseRadius
                            ),
                            radius = baseRadius * 0.85f,
                            center = center
                        )
                    }

                    // Layer 3: Central Specular Luminous Core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.9f),
                                Color(0xFFBAE6FD).copy(alpha = 0.6f),
                                Color.Transparent
                            ),
                            center = Offset(center.x - 12f, center.y - 12f),
                            radius = baseRadius * 0.45f
                        ),
                        radius = baseRadius * 0.45f,
                        center = center
                    )
                }
            }

            Spacer(Modifier.height(40.dp))

            // Hands-free auto-continue toggle.
            androidx.compose.material3.TextButton(onClick = { autoVoice = !autoVoice }) {
                Text(
                    text = if (autoVoice) "Auto-conversation: On" else "Auto-conversation: Off",
                    color = if (autoVoice) colors.accentGreen else colors.textMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(4.dp))

            // Live Transcript Subtitles Pill — tap to hear it read aloud.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                    .clickable(enabled = userSpokenText.isNotBlank()) {
                        TtsManager.toggle(personaCtx, userSpokenText, voicePersona)
                    }
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        voiceStt.state.isListening -> "YOU (LISTENING...)"
                        isSpeaking -> "KAREN · ${activeVoiceLabel.uppercase()}"
                        isThinking || isTranscribing -> "KAREN · THINKING"
                        else -> "STANDBY"
                    },
                    color = colors.accentGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        voiceStt.state.isListening -> "YOU (LISTENING...)"
                        isTranscribing -> "Got it — thinking…"
                        voiceBusy -> "Thinking…"
                        voiceReply.isNotBlank() -> "“$voiceReply”"
                        !voiceStt.state.errorMessage.isNullOrBlank() -> "⚠ ${voiceStt.state.errorMessage} — tap orb to retry"
                        userSpokenText.isNotEmpty() -> "“$userSpokenText”"
                        else -> "Tap orb to speak. Karen native STT is ready..."
                    },
                    color = colors.textPrimary,
                    fontSize = 14.5.sp,
                    lineHeight = 22.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                if (isVisionActive) {
                    Text(
                        text = "● Local Camera Vision Feed Active · 0 cloud frames",
                        color = colors.accentGreen,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = "Android STT · ${if (device.networkUp) "Network Up" else "0 egress"} · 16kHz beamforming · ${device.deviceModel}",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Live amplitude waveform strip
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val bars = 24
                    for (i in 0 until bars) {
                        val phase = kotlin.math.sin((i.toFloat() / bars) * Math.PI).toFloat()
                        val h = if (voiceStt.state.isListening) {
                            (4f + phase * (4f + voiceStt.state.rmsDb * 40f)).coerceIn(4f, 22f)
                        } else 4f
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 1.5.dp)
                                .width(3.dp)
                                .height(h.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (voiceStt.state.isListening) colors.accentGreen else colors.border)
                        )
                    }
                }

                if (voiceNeedsModel) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = onOpenModelManager,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Open Model Manager →",
                            color = colors.accentGreen,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Bottom Circular Control Dock
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute / Unmute
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (voiceStt.state.isListening) colors.accentGreen.copy(alpha = 0.25f) else if (isMuted) colors.surface else colors.surfaceHover)
                    .border(1.dp, colors.border, CircleShape)
                    .clickable {
                        isMuted = !isMuted
                        if (voiceStt.state.isListening) {
                            voiceStt.stopListening()
                        } else {
                            voiceStt.startListening()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (voiceStt.state.isListening) Icons.Default.Mic else if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Mute Live mode",
                    tint = if (voiceStt.state.isListening) colors.accentGreen else if (isMuted) colors.accentRed else colors.textPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Vision / Camera Button
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (isVisionActive) colors.accentGreen else colors.surfaceHover)
                    .border(1.dp, colors.border, CircleShape)
                    .clickable { isVisionActive = !isVisionActive },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = "Camera Vision",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            // End Call (Red Circular Button)
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFEF4444))
                    .clickable {
                        voiceStt.stopListening()
                        TtsManager.stop()
                        onClose()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CallEnd,
                    contentDescription = "End Live mode",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        if (showVoicePicker) {
            FishVoicePickerDialog(
                colors = colors,
                hasKey = UserPrefs.fishAudioKey(personaCtx).isNotBlank(),
                selectedId = fishVoice,
                selectedName = fishVoiceName,
                onDismiss = { showVoicePicker = false },
                onPick = { id, name ->
                    fishVoice = id
                    fishVoiceName = name
                    UserPrefs.setFishVoiceId(personaCtx, id)
                    UserPrefs.setFishVoiceName(personaCtx, name)
                    showVoicePicker = false
                }
            )
        }
    }
}
