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
import java.util.Locale

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

    // The recognizer is reused across hands-free turns; keep the newest
    // callbacks without recreating it and losing the warm engine.
    val currentOnResult = rememberUpdatedState(onResult)
    val currentOnPartial = rememberUpdatedState(onPartialResult)

    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

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
    val currentFallback = rememberUpdatedState(launchSystemDialogFallback)

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                state.isListening = true
                state.isTranscribing = false
                state.errorMessage = null
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
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                    else -> "Recognition error ($error)"
                }
                state.errorMessage = msg
                if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    // The engine is wedged: discard it before the dialog.
                    try {
                        speechRecognizer?.destroy()
                    } catch (_: Exception) {
                    }
                    speechRecognizer = null
                    currentFallback.value()
                }
            }

            override fun onResults(results: Bundle?) {
                state.isListening = false
                state.isTranscribing = false
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

    val startRecognizingInternal = {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            state.isTranscribing = false
            state.errorMessage = null
            state.partialText = ""
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
                launchSystemDialogFallback()
            }
        } else {
            launchSystemDialogFallback()
        }
    }

    // Permission launcher for RECORD_AUDIO
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startRecognizingInternal()
        } else {
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
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        state.isListening = false
    }

    val cancelListening = {
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        state.isListening = false
        state.isTranscribing = false
        state.partialText = ""
    }

    DisposableEffect(Unit) {
        onDispose {
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
