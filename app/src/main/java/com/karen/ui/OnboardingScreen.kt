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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(colors.accentGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Welcome to Karen", color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Tell me a little about yourself — stored only on this device.", color = colors.textMuted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(20.dp))

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
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) colors.accentGreen.copy(alpha = 0.2f) else colors.surfaceHover)
                                    .border(1.dp, if (selected) colors.accentGreen else colors.border, RoundedCornerShape(10.dp))
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
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White)
            ) {
                Text("Continue", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
