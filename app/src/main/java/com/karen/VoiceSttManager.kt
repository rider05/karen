package com.karen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Failures that mean the recognition backend dropped the session, not that
 * the user said nothing. These are safe to retry on a freshly built engine.
 */
private val STT_TRANSIENT_ERRORS = setOf(
    SpeechRecognizer.ERROR_AUDIO,
    SpeechRecognizer.ERROR_CLIENT,
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
    SpeechRecognizer.ERROR_SERVER,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
)

/** Automatic reconnects after a dropped session before asking for a tap. */
private const val STT_MAX_AUTO_RETRIES = 2

/** Android speech error code → one short line the Live-mode card can show. */
private fun sttErrorMessage(error: Int): String = when (error) {
    SpeechRecognizer.ERROR_AUDIO -> "Microphone unavailable — another app may be using it"
    SpeechRecognizer.ERROR_CLIENT -> "Recognition was interrupted"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
    SpeechRecognizer.ERROR_NETWORK -> "Speech service needs a network connection"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech service timed out"
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
    SpeechRecognizer.ERROR_SERVER -> "Speech service error"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Speech service is rate-limiting"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Speech service disconnected"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "This language is not supported by speech"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Speech language pack unavailable"
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "Speech recognition unsupported on this device"
    else -> "Recognition error ($error)"
}

/** Retry bookkeeping kept outside Compose state so it never recomposes. */
private class SttRetryState {
    var attempts = 0

    /** True while a start the user still wants is in flight. */
    var armed = false

    /** True after a deliberate stop/cancel; late errors are not surfaced. */
    var cancelled = false
    var job: Job? = null
}

class VoiceSttState(
    isListening: Boolean = false,
    partialText: String = "",
    rmsDb: Float = 0f,
    errorMessage: String? = null,
    isTranscribing: Boolean = false
) {
    var isListening by mutableStateOf(isListening)
    var partialText by mutableStateOf(partialText)
    var rmsDb by mutableStateOf(rmsDb)
    var errorMessage by mutableStateOf(errorMessage)

    /** True between end-of-speech and the final transcript. */
    var isTranscribing by mutableStateOf(isTranscribing)

    /** True while a dropped recognition session is being rebuilt. */
    var isRecovering by mutableStateOf(false)
}

class VoiceSttController(
    val state: VoiceSttState,
    private val onStartListening: () -> Unit,
    private val onStopListening: () -> Unit,
    private val onCancelListening: () -> Unit
) {
    fun startListening() = onStartListening()
    fun stopListening() = onStopListening()
    fun cancel() = onCancelListening()
    fun toggle() {
        if (state.isListening) stopListening() else startListening()
    }
}

/**
 * Remember a native Android Speech-To-Text recognizer with runtime permission handling,
 * real-time partial streaming, audio level (RMS) metering, and RecognizerIntent fallback.
 */
@Composable
fun rememberVoiceStt(
    onResult: (String) -> Unit = {},
    onPartialResult: (String) -> Unit = {}
): VoiceSttController {
    val context = LocalContext.current
    val state = remember { VoiceSttState() }
    val retry = remember { SttRetryState() }
    val scope = rememberCoroutineScope()
    // Filled on every composition; the remembered listener calls through it
    // so it always reaches the latest start logic (no stale capture).
    val startHolder = remember { arrayOfNulls<(Boolean) -> Unit>(1) }

    // The recognizer is reused across hands-free turns; keep the newest
    // callbacks without recreating it and losing the warm engine.
    val currentOnResult = rememberUpdatedState(onResult)
    val currentOnPartial = rememberUpdatedState(onPartialResult)

    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun destroyRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }
        speechRecognizer = null
    }

    fun cancelRetry() {
        retry.job?.cancel()
        retry.job = null
        retry.armed = false
        retry.cancelled = true
        state.isRecovering = false
    }

    // Fallback system dialog intent launcher
    val systemIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        state.isListening = false
        state.isTranscribing = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenMatches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = spokenMatches?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) {
                state.partialText = ""
                currentOnResult.value(text)
            }
        }
    }

    val createRecognizerIntent: () -> Intent = {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Ask the service to endpoint a little sooner. Live Mode feels
            // dead when the recognizer sits on a finished sentence; these are
            // hints, and recognizers that ignore them keep their defaults.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 750L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
        }
    }

    val launchSystemDialogFallback = {
        try {
            val intent = createRecognizerIntent()
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Karen...")
            systemIntentLauncher.launch(intent)
        } catch (e: Exception) {
            state.isListening = false
            state.isTranscribing = false
            state.errorMessage = "No speech recognition service available"
            Toast.makeText(context, "Speech recognition not available on device", Toast.LENGTH_SHORT).show()
        }
    }

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                state.isListening = true
                state.isTranscribing = false
                state.errorMessage = null
                state.isRecovering = false
            }

            override fun onBeginningOfSpeech() {
                state.isListening = true
            }

            override fun onRmsChanged(rmsdB: Float) {
                state.rmsDb = (rmsdB.coerceIn(0f, 10f) / 10f)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                // The final transcript is still in flight. Showing this
                // immediately removes the silent gap before "Thinking…".
                state.isListening = false
                state.isTranscribing = true
            }

            override fun onError(error: Int) {
                state.isListening = false
                state.isTranscribing = false
                state.rmsDb = 0f
                // A reused engine keeps a broken server connection after
                // ERROR_SERVER_DISCONNECTED (11), which is exactly why the
                // old code looped back into the same error. Drop every dead
                // engine; a clean one is cheap to build.
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    destroyRecognizer()
                }

                // stopListening()/cancel() also end in a client error on most
                // devices. That is the user's intent, not a failure to report.
                if (retry.cancelled) {
                    retry.cancelled = false
                    state.errorMessage = null
                    state.isRecovering = false
                    return
                }

                val transient = error in STT_TRANSIENT_ERRORS
                if (transient && retry.armed && retry.attempts < STT_MAX_AUTO_RETRIES) {
                    retry.attempts++
                    state.errorMessage = null
                    state.isRecovering = true
                    state.partialText = ""
                    retry.job?.cancel()
                    retry.job = scope.launch {
                        // Short backoff: long enough for the mic/audio route
                        // to free up, short enough to feel like a reconnect.
                        delay(if (retry.attempts <= 1) 350L else 1_000L)
                        if (retry.armed) startHolder[0]?.invoke(true)
                    }
                } else {
                    retry.armed = false
                    retry.job?.cancel()
                    retry.job = null
                    state.isRecovering = false
                    state.errorMessage = sttErrorMessage(error)
                }
            }

            override fun onResults(results: Bundle?) {
                state.isListening = false
                state.isTranscribing = false
                state.isRecovering = false
                retry.attempts = 0
                retry.armed = false
                retry.cancelled = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val finalSpeech = matches?.firstOrNull()?.trim()
                if (!finalSpeech.isNullOrEmpty()) {
                    state.partialText = ""
                    currentOnResult.value(finalSpeech)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val interim = matches?.firstOrNull()?.trim() ?: ""
                if (interim.isNotEmpty()) {
                    state.partialText = interim
                    currentOnPartial.value(interim)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    fun createRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            speechRecognizer = it
        }

    fun beginListening(recognizer: SpeechRecognizer) {
        recognizer.startListening(createRecognizerIntent())
        state.isListening = true
        state.isTranscribing = false
    }

    fun startRecognizingInternal(isRetry: Boolean = false) {
        // Never stack sessions: two overlapping starts are the classic cause
        // of ERROR_CLIENT and of the mic being stolen from the recognizer.
        if (state.isListening || state.isTranscribing) return
        if (!isRetry) retry.attempts = 0
        retry.armed = true
        retry.cancelled = false
        retry.job?.cancel()
        retry.job = null
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            state.isTranscribing = false
            state.errorMessage = null
            state.partialText = ""
            state.isRecovering = isRetry
            try {
                val recognizer = speechRecognizer ?: createRecognizer()
                try {
                    beginListening(recognizer)
                } catch (_: Exception) {
                    // A reused engine can occasionally be left busy; rebuild
                    // once instead of dropping to the system dialog.
                    try {
                        recognizer.destroy()
                    } catch (_: Exception) {
                    }
                    speechRecognizer = null
                    beginListening(createRecognizer())
                }
            } catch (e: Exception) {
                state.isListening = false
                state.isTranscribing = false
                state.isRecovering = false
                retry.armed = false
                launchSystemDialogFallback()
            }
        } else {
            retry.armed = false
            state.isRecovering = false
            launchSystemDialogFallback()
        }
    }
    // The remembered listener must reach the current lambda, not a stale one.
    startHolder[0] = { isRetry -> startRecognizingInternal(isRetry) }

    // Permission launcher for RECORD_AUDIO
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecognizingInternal()
        } else {
            cancelRetry()
            state.isListening = false
            state.isTranscribing = false
            state.errorMessage = "Audio recording permission required"
            Toast.makeText(context, "Microphone permission is required for voice input", Toast.LENGTH_SHORT).show()
        }
    }

    val startListening = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startRecognizingInternal()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val stopListening = {
        // A deliberate stop must not be undone by a pending reconnect.
        cancelRetry()
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        state.isListening = false
    }

    val cancelListening = {
        cancelRetry()
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        state.isListening = false
        state.isTranscribing = false
        state.partialText = ""
    }

    DisposableEffect(Unit) {
        onDispose {
            retry.job?.cancel()
            retry.job = null
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
        }
    }

    return remember(state) {
        VoiceSttController(
            state = state,
            onStartListening = startListening,
            onStopListening = stopListening,
            onCancelListening = cancelListening
        )
    }
}
