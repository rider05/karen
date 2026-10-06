package com.karen.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
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
import com.karen.rememberDeviceTelemetry

data class FlashcardItem(
    val question: String,
    val answer: String,
    val citation: String
)

@Composable
fun StudyBookScreen(
    onOpenDrawer: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()

    val flashcards = remember {
        mutableStateListOf<FlashcardItem>()
    }

    var currentCardIndex by remember { mutableStateOf(0) }
    val card = flashcards.getOrNull(currentCardIndex)
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "DBMS Study Companion",
            onMenuClick = onOpenDrawer,
            moreActions = listOf(
                Triple("Flip card", Icons.Default.Refresh) { android.widget.Toast.makeText(ctx, "Flip card", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Mark known", Icons.Default.Done) { android.widget.Toast.makeText(ctx, "Marked known", android.widget.Toast.LENGTH_SHORT).show() },
                Triple("Reset progress", Icons.Default.Delete) { android.widget.Toast.makeText(ctx, "Progress reset", android.widget.Toast.LENGTH_SHORT).show() }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 0.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Document context badge
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = colors.accentAmber, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("DBMS_Normalization.pdf", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Text("${if (device.networkUp) "Network Up" else "Air-gapped"} · Android ${device.androidVersion} · ${device.deviceModel}", color = colors.textMuted, fontSize = 11.5.sp)
                    }
                }
            }

            // Interactive Flashcard Card
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Active Flashcard · ${if (flashcards.isEmpty()) 0 else currentCardIndex + 1} of ${flashcards.size}", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tap to flip/next", color = colors.accentGreen, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                if (card == null) {
                    Text("No data found", color = colors.textMuted, fontSize = 12.sp)
                } else Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.cardBackground)
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        card.question,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        card.answer,
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        card.citation,
                        color = colors.accentGreen,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (flashcards.isNotEmpty()) currentCardIndex = (currentCardIndex + 1) % flashcards.size
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("I Knew This (${currentCardIndex + 1}/${flashcards.size})", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                if (flashcards.isNotEmpty()) currentCardIndex = (currentCardIndex + 1) % flashcards.size
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Next Card", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Normal Forms Breakdown
            item {
                Text("Extracted Key Concepts", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No data found", color = colors.textMuted, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ConceptCard(title: String, desc: String) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(desc, color = colors.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}
