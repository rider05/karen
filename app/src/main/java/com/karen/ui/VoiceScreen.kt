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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.karen.rememberDeviceTelemetry
import com.karen.rememberVoiceStt

/** Spoken-reply instruction: short, human, TTS-safe answers for voice mode. */
const val VOICE_REPLY_STYLE =
    "Reply as natural human speech for text-to-speech: 1-3 short spoken sentences, " +
        "warm and conversational, plain words only — no markdown, no lists, no tables, " +
        "no code, no emojis, no stage directions, no bullet points."

@Composable
fun VoiceScreen(
    onClose: () -> Unit = {}
) {
    // System back returns to Chat (same as the close button).
    androidx.activity.compose.BackHandler { onClose() }
    val device = rememberDeviceTelemetry()
    var isMuted by remember { mutableStateOf(false) }
    var userSpokenText by remember { mutableStateOf("") }
    var isVisionActive by remember { mutableStateOf(false) }

    // Real speech output state; stop it when leaving voice mode.
    val isSpeaking = TtsManager.speaking
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { TtsManager.stop() }
    }

    val colors = LocalKarenColors.current
    val personaCtx = androidx.compose.ui.platform.LocalContext.current
    var voicePersona by remember { mutableStateOf(UserPrefs.voicePersona(personaCtx)) }
    var personaMenu by remember { mutableStateOf(false) }

    // Voice chat loop: STT transcript → model API → human-spoken TTS reply.
    var voiceBusy by remember { mutableStateOf(false) }
    var voiceReply by remember { mutableStateOf("") }
    var cancelVoice by remember { mutableStateOf(false) }
    val voiceScope = rememberCoroutineScope()

    fun runVoiceTurn(prompt: String) {
        val text = prompt.trim()
        if (text.isBlank() || voiceBusy) return
        voiceBusy = true
        cancelVoice = false
        voiceReply = ""
        voiceScope.launch {
            try {
                val locals = UserPrefs.models(personaCtx)
                    .filter { !isCloudProviderName(it) && ModelDownloader.isRunnableWeight(personaCtx, it) }
                val keyed = cloudProviders.filter { UserPrefs.apiKey(personaCtx, it.id).isNotBlank() }
                val pick = routeModel(text, locals, keyed, UserPrefs.autoCloud(personaCtx))?.modelName
                    ?: UserPrefs.selectedModel(personaCtx).takeIf { isSelectableModel(personaCtx, it) }
                val cloud = pick?.let { findCloudProviderByName(it) }
                val key = cloud?.let { UserPrefs.apiKey(personaCtx, it.id) }.orEmpty()
                val answer = if (cloud != null && key.isNotBlank()) {
                    cloud.complete(
                        key,
                        listOf("user" to "$VOICE_REPLY_STYLE\n\n$text"),
                        maxTokensFor("Low"),
                        thinkingBudget = null,
                        historyLimit = 4,
                        model = UserPrefs.apiModel(personaCtx, cloud.id).ifBlank { null }
                    )
                } else {
                    val modelName = pick
                    val wf = if (cloud == null && modelName != null) ModelDownloader.weightFileFor(personaCtx, modelName) else null
                    if (wf != null && KarenLlama.ready && modelName != null) {
                        val threads = maxOf(2, minOf(6, Runtime.getRuntime().availableProcessors()))
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            KarenLlama.ensureLoaded(wf, modelName, 2048, threads)
                        }
                        if (!ok || cancelVoice) null
                        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            KarenLlama.complete(
                                "You are Karen, a voice assistant. $VOICE_REPLY_STYLE",
                                arrayOf("user"),
                                arrayOf(text),
                                maxTokensFor("Low")
                            )
                        }
                    } else null
                }
                if (cancelVoice) return@launch
                val said = answer?.trim().orEmpty()
                if (said.isNotBlank()) {
                    voiceReply = said
                    TtsManager.speak(personaCtx, said, voicePersona)
                } else {
                    voiceReply = "I couldn't answer that. Connect a model in Model Manager to chat by voice."
                    TtsManager.speak(personaCtx, voiceReply, voicePersona)
                }
            } catch (e: Exception) {
                if (!cancelVoice) {
                    voiceReply = "Sorry, something went wrong."
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

    val infiniteTransition = rememberInfiniteTransition(label = "voice_orb")

    // Rotation animation
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking || voiceStt.state.isListening) 5000 else 12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_rotation"
    )

    // Breathing scale animation
    val breatheScale by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = if (isSpeaking) 1.15f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isSpeaking) 1200 else 2400, easing = FastOutSlowInEasing),
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
                    contentDescription = "Close Voice",
                    tint = colors.textPrimary
                )
            }

            // TTS voice persona picker (Juniper/Sol/Cove/Breeze/Ember), persisted.
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
                    contentDescription = "Select voice",
                    tint = colors.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
            DropdownMenu(
                expanded = personaMenu,
                onDismissRequest = { personaMenu = false },
                modifier = Modifier.background(colors.surface)
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
                            if (voiceStt.state.isListening) colors.accentGreen.copy(alpha = statusPulse)
                            else if (isSpeaking) colors.accentBlue.copy(alpha = statusPulse)
                            else colors.textMuted
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (voiceStt.state.isListening) "Listening..." else if (isSpeaking) "Speaking · System TTS" else "Ready · Tap to Speak",
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
                        if (voiceBusy) {
                            cancelVoice = true
                            TtsManager.stop()
                        } else if (voiceStt.state.isListening) {
                            voiceStt.stopListening()
                        } else {
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
                    text = if (voiceStt.state.isListening) "YOU (LISTENING...)" else if (isSpeaking) "KAREN · ${voicePersona.uppercase()}" else "STANDBY",
                    color = colors.accentGreen,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        voiceStt.state.isListening -> "YOU (LISTENING...)"
                        voiceBusy -> "Thinking…"
                        voiceReply.isNotBlank() -> "“$voiceReply”"
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
                    contentDescription = "Mute",
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
                    contentDescription = "End call",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
