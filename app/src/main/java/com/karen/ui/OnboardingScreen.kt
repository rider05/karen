package com.karen.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OnboardingScreen(onDone: () -> Unit = {}) {
    val colors = LocalKarenColors.current
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    val types = listOf("Student", "Personal", "Developer", "Researcher", "Professional")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        KHero(
            kicker = "Onboarding // local profile",
            title = "Welcome to Karen",
            subtitle = "Tell me a little about yourself — stored only on this device.",
            icon = Icons.Default.Psychology,
            tone = colors.accentGreen,
            status = "LOCAL ONLY",
            statusTone = KTone.Success
        ) {
            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = karenFieldColors(colors)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = age,
                onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 3) age = it },
                label = { Text("Age") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = karenFieldColors(colors)
            )
            Spacer(Modifier.height(16.dp))
            Text("Usage type", color = colors.textSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                types.chunked(3).forEach { rowTypes ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowTypes.forEach { t ->
                            val selected = type == t
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (selected) colors.accentGreen.copy(alpha = 0.16f) else colors.surfaceHover)
                                    .border(1.dp, if (selected) colors.accentGreen.copy(alpha = 0.7f) else colors.border, RoundedCornerShape(999.dp))
                                    .clickable { type = t }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(t, color = if (selected) colors.accentGreen else colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        UserPrefs.save(context, name.trim(), age, type.ifBlank { "Personal" })
                        onDone()
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(999.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White)
            ) {
                Text("Continue", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
