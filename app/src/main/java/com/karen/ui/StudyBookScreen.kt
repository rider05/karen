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
        listOf(
            FlashcardItem(
                question = "What distinguishes BCNF (Boyce-Codd) from standard 3NF?",
                answer = "In BCNF, for every non-trivial functional dependency X → Y, X must be a strict superkey. In 3NF, Y is allowed to be a prime attribute even if X is not a superkey.",
                citation = "Page 8 · Section 3.7 · Cosine similarity 0.98"
            ),
            FlashcardItem(
                question = "What defines a Lossless Join Decomposition?",
                answer = "A decomposition of relation R into R1 and R2 is lossless if and only if R1 ∩ R2 contains a candidate key for either R1 or R2.",
                citation = "Page 5 · Section 2.4 · Cosine similarity 0.95"
            ),
            FlashcardItem(
                question = "What is Armstrong's Axiom of Transitivity?",
                answer = "If X → Y and Y → Z, then X → Z. This rule forms the basis for computing attribute closures (X+).",
                citation = "Page 3 · Section 1.8 · Cosine similarity 0.97"
            ),
            FlashcardItem(
                question = "Why might a relation in 3NF not be in BCNF?",
                answer = "When there are multiple overlapping candidate keys with non-superkey determinants driving prime attributes.",
                citation = "Page 9 · Section 3.9 · Cosine similarity 0.96"
            )
        )
    }

    var currentCardIndex by remember { mutableStateOf(0) }
    val card = flashcards[currentCardIndex]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "DBMS Study Companion",
            onMenuClick = onOpenDrawer
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 80.dp),
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
                    Text("Active Flashcard · ${currentCardIndex + 1} of ${flashcards.size}", color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tap to flip/next", color = colors.accentGreen, fontSize = 11.sp)
                }
                Spacer(Modifier.height(4.dp))
                Column(
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
                                currentCardIndex = (currentCardIndex + 1) % flashcards.size
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen, contentColor = Color.White),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("I Knew This (${currentCardIndex + 1}/${flashcards.size})", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                currentCardIndex = (currentCardIndex + 1) % flashcards.size
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
                    ConceptCard("1. 1NF — Atomicity", "Each column contains atomic values; no repeating groups. Page 2.")
                    ConceptCard("2. 2NF — Full Dependency", "In 1NF and all non-key attributes fully depend on candidate keys. Page 4.")
                    ConceptCard("3. 3NF & BCNF", "No transitive dependencies; X → Y requires X as superkey in BCNF. Page 7.")
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
