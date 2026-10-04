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
    errorMessage: String? = null
) {
    var isListening by mutableStateOf(isListening)
    var partialText by mutableStateOf(partialText)
    var rmsDb by mutableStateOf(rmsDb)
    var errorMessage by mutableStateOf(errorMessage)
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

    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    // Fallback system dialog intent launcher
    val systemIntentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        state.isListening = false
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val spokenMatches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = spokenMatches?.firstOrNull()?.trim()
            if (!text.isNullOrEmpty()) {
                state.partialText = ""
                onResult(text)
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
        }
    }

    val launchSystemDialogFallback = {
        try {
            val intent = createRecognizerIntent()
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Karen...")
            systemIntentLauncher.launch(intent)
        } catch (e: Exception) {
            state.isListening = false
            state.errorMessage = "No speech recognition service available"
            Toast.makeText(context, "Speech recognition not available on device", Toast.LENGTH_SHORT).show()
        }
    }

    val startRecognizingInternal = {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            try {
                speechRecognizer?.destroy()
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            state.isListening = true
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
                            state.isListening = false
                        }

                        override fun onError(error: Int) {
                            state.isListening = false
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
                                // Fallback to system dialog
                                launchSystemDialogFallback()
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            state.isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val finalSpeech = matches?.firstOrNull()?.trim()
                            if (!finalSpeech.isNullOrEmpty()) {
                                state.partialText = ""
                                onResult(finalSpeech)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val interim = matches?.firstOrNull()?.trim() ?: ""
                            if (interim.isNotEmpty()) {
                                state.partialText = interim
                                onPartialResult(interim)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
                speechRecognizer = recognizer
                recognizer.startListening(createRecognizerIntent())
                state.isListening = true
            } catch (e: Exception) {
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
