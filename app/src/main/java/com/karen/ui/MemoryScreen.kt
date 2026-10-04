package com.karen.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MemoryTrait(
    val id: String,
    val category: String,
    val text: String,
    val confidence: String
)

@Composable
fun MemoryScreen(
    onOpenDrawer: () -> Unit = {}
) {
    val colors = LocalKarenColors.current

    val pendingTraits = remember {
        mutableStateListOf(
            MemoryTrait(
                id = "p1",
                category = "UI & Tone",
                text = "“User prefers high-contrast OLED dark mode with concise technical explanations without disclaimers.”",
                confidence = "94%"
            )
        )
    }

    val verifiedTraits = remember {
        mutableStateListOf(
            MemoryTrait(
                id = "v1",
                category = "Coding Architecture",
                text = "Default target is Android SDK 34 using Jetpack Compose, Kotlin Coroutines, and Clean Architecture.",
                confidence = "98%"
            ),
            MemoryTrait(
                id = "v2",
                category = "Academic Schedule",
                text = "Distributed Systems Lab exam is scheduled on Thursday, Oct 24, 2:00 PM in Lab 4B.",
                confidence = "99%"
            ),
            MemoryTrait(
                id = "v3",
                category = "Hardware Guard",
                text = "Limit background compile jobs to 6 worker threads to prevent SoC junction temp exceeding 58°C.",
                confidence = "91%"
            ),
            MemoryTrait(
                id = "v4",
                category = "Project Context",
                text = "TreeVision-Android is a biometric security vault app targeting offline seccomp kernel sandboxing.",
                confidence = "95%"
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Memory & Preferences",
            onMenuClick = onOpenDrawer
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Memory Governance Card
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Sovereign Memory Bank", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.accentGreen.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("100% On-Device", color = colors.accentGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Karen only remembers facts with your explicit verification. Stored encrypted in sqlite-vec.",
                        color = colors.textMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {},
                            colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceHover, contentColor = colors.textPrimary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Export JSON", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                verifiedTraits.clear()
                                pendingTraits.clear()
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Flush Cache", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Pending Verification Section
            if (pendingTraits.isNotEmpty()) {
                item {
                    Text("Pending Verification (${pendingTraits.size})", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                }

                items(pendingTraits, key = { it.id }) { trait ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.cardBackground)
                            .border(1.dp, colors.accentAmber.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            trait.text,
                            color = colors.textPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Detected from Chat session · Confidence: ${trait.confidence}", color = colors.textMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    pendingTraits.remove(trait)
                                    verifiedTraits.add(0, trait.copy(confidence = "100% (Verified)"))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Confirm & Remember", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = {
                                    pendingTraits.remove(trait)
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("Discard", fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }

            // Confirmed Memories
            item {
                Text("Verified Memory Traits (${verifiedTraits.size})", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
            }

            items(verifiedTraits, key = { it.id }) { trait ->
                MemoryTraitCard(
                    category = trait.category,
                    text = trait.text,
                    confidence = trait.confidence
                )
            }
        }
    }
}

@Composable
private fun MemoryTraitCard(category: String, text: String, confidence: String) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(category, color = colors.accentGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(confidence, color = colors.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        Text(text, color = colors.textPrimary, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
