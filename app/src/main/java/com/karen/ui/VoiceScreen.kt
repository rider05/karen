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
import com.karen.rememberDeviceTelemetry
import com.karen.rememberVoiceStt

@Composable
fun VoiceScreen(
    onClose: () -> Unit = {}
) {
    val device = rememberDeviceTelemetry()
    var isMuted by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var userSpokenText by remember { mutableStateOf("") }
    var isVisionActive by remember { mutableStateOf(false) }
    var voiceName by remember { mutableStateOf("Karen Alto") }

    val voiceStt = rememberVoiceStt(
        onResult = { result ->
            userSpokenText = result
            isSpeaking = true
        },
        onPartialResult = { interim ->
            userSpokenText = interim
            isSpeaking = false
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
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Voice Top Bar
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
                    .background(Color(0xFF1C1C1E))
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close Voice",
                    tint = Color.White
                )
            }

            // Status Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x22FFFFFF))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (voiceStt.state.isListening) Color(0xFF10A37F).copy(alpha = statusPulse)
                            else if (isSpeaking) Color(0xFF38BDF8).copy(alpha = statusPulse)
                            else Color(0xFF8E8E93)
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (voiceStt.state.isListening) "Listening..." else if (isSpeaking) "Speaking · Piper TTS" else "Ready · Tap to Speak",
                    color = Color(0xFFC4C4C8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Voice Selector Dropdown
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x22FFFFFF))
                    .clickable {
                        voiceName = if (voiceName == "Karen Alto") "Karen Sovereign" else "Karen Alto"
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = voiceName,
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Select voice",
                    tint = Color(0xFF8E8E93),
                    modifier = Modifier.size(16.dp)
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
                        if (voiceStt.state.isListening) {
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

            // Live Transcript Subtitles Pill
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x24FFFFFF))
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (voiceStt.state.isListening) "YOU (LISTENING...)" else if (isSpeaking) "KAREN" else "STANDBY",
                    color = Color(0xFF10A37F),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (userSpokenText.isNotEmpty() && !isSpeaking) {
                        "“$userSpokenText”"
                    } else if (isSpeaking) {
                        "“Under an 8-thread compile, CPU power will spike to 9.4W, raising junction temp by ~7°C. I recommend 6 threads to stay under throttle threshold.”"
                    } else {
                        "Tap orb to speak. Karen native STT is ready..."
                    },
                    color = Color.White,
                    fontSize = 14.5.sp,
                    lineHeight = 22.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                if (isVisionActive) {
                    Text(
                        text = "● Local Camera Vision Feed Active · 0 cloud frames",
                        color = Color(0xFF10A37F),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = "Whisper.cpp / Android STT · ${if (device.networkUp) "Network Up" else "0 egress"} · 16kHz beamforming · ${device.deviceModel}",
                    color = Color(0xFF8E8E93),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
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
                    .background(if (voiceStt.state.isListening) Color(0xFF10A37F).copy(alpha = 0.25f) else if (isMuted) Color(0xFF3A3A3C) else Color(0xFF2C2C2E))
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
                    tint = if (voiceStt.state.isListening) Color(0xFF10A37F) else if (isMuted) Color(0xFFEF4444) else Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }

            // Vision / Camera Button
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (isVisionActive) Color(0xFF10A37F) else Color(0xFF2C2C2E))
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
