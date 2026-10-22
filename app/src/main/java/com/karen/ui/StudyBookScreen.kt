package com.karen.ui

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.rememberDeviceTelemetry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FlashcardItem(
    val question: String,
    val answer: String,
    val citation: String
)

/** Text-like vault documents a study deck can be built from. */
private val STUDY_TEXT_EXTS = setOf(
    "txt", "md", "markdown", "csv", "log", "json", "xml", "yaml", "yml",
    "ini", "cfg", "properties", "toml", "html"
)

private fun studyDir(ctx: Context): File = File(ctx.filesDir, "study").apply { mkdirs() }

private fun loadStudyCards(ctx: Context): MutableList<FlashcardItem> {
    return try {
        val f = File(studyDir(ctx), "flashcards.json")
        if (!f.exists()) return mutableListOf()
        val arr = org.json.JSONArray(f.readText())
        MutableList(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            FlashcardItem(
                question = o.optString("q"),
                answer = o.optString("a"),
                citation = o.optString("src")
            )
        }
    } catch (_: Exception) {
        mutableListOf()
    }
}

private fun saveStudyCards(ctx: Context, cards: List<FlashcardItem>) {
    try {
        val arr = org.json.JSONArray()
        cards.forEach { c ->
            arr.put(org.json.JSONObject().put("q", c.question).put("a", c.answer).put("src", c.citation))
        }
        File(studyDir(ctx), "flashcards.json").writeText(arr.toString())
    } catch (_: Exception) {
    }
}

/** Extracts "Q: … | A: …" rows from a model reply (case-tolerant). */
private fun parseFlashcards(reply: String, source: String): List<FlashcardItem> {
    val out = mutableListOf<FlashcardItem>()
    reply.lines().forEach { line ->
        val t = line.trim().removePrefix("- ").trim()
        if (!t.startsWith("Q:", ignoreCase = true)) return@forEach
        val body = t.substring(2).trim()
        val idx = body.indexOf("| a:", ignoreCase = true)
        val q = if (idx >= 0) body.substring(0, idx).trim() else body.substringBefore('|').trim()
        val a = if (idx >= 0) {
            body.substring(idx + 4).trim()
        } else {
            body.substringAfter('|', "").removePrefix("A:").removePrefix("a:").trim()
        }
        if (q.isNotBlank() && a.isNotBlank()) out.add(FlashcardItem(q, a, source))
    }
    return out.take(12)
}

@Composable
fun StudyBookScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val flashcards = remember { mutableStateListOf<FlashcardItem>().apply { addAll(loadStudyCards(ctx)) } }
    var currentCardIndex by remember { mutableStateOf(0) }
    var showAnswer by remember { mutableStateOf(false) }
    var known by remember { mutableStateOf(setOf<Int>()) }
    var selectedDoc by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    val docs = remember(refreshKey) {
        vaultFiles(ctx)
            .filter { it.isFile && it.extension.lowercase() in STUDY_TEXT_EXTS && it.length() in 1..2_000_000L }
            .sortedBy { it.name }
    }
    val card = flashcards.getOrNull(currentCardIndex)

    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
        if (confirmClear) {
            confirmClear = false
            true
        } else false
    }

    /** Builds a deck from the picked vault document with the on-device model. */
    fun generate() {
        if (busy) return
        val doc = docs.firstOrNull { it.name == selectedDoc } ?: docs.firstOrNull()
        if (doc == null) {
            status = "No study document — add a .txt or .md file in the Knowledge Vault first."
            return
        }
        val model = UserPrefs.selectedModel(ctx)
            .takeIf {
                it.isNotBlank() && !isCloudProviderName(it) && !isNonChatModel(it) &&
                    ModelDownloader.isRunnableWeight(ctx, it)
            }
            ?: UserPrefs.models(ctx).firstOrNull { !isNonChatModel(it) && ModelDownloader.isRunnableWeight(ctx, it) }
        if (model == null) {
            status = "No local model — download or import a GGUF in Model Manager to generate decks offline."
            return
        }
        val file = ModelDownloader.weightFileFor(ctx, model)
        if (file == null) {
            status = "Model file missing on disk — re-download it in Model Manager."
            return
        }
        val text = try {
            doc.readText().take(6000)
        } catch (_: Exception) {
            ""
        }
        if (text.isBlank()) {
            status = "Could not read ${doc.name} — study decks need plain-text documents."
            return
        }
        busy = true
        status = "Reading ${doc.name} with $model…"
        scope.launch {
            val reply = withContext(Dispatchers.IO) {
                try {
                    val threads = Runtime.getRuntime().availableProcessors().let { (it - 1).coerceIn(2, 8) }
                    if (!KarenLlama.ensureLoaded(file, model, UserPrefs.contextTokens(ctx), threads)) {
                        return@withContext null
                    }
                    KarenLlama.completeStream(
                        "You create study flashcards. Reply with flashcards only — no preamble, no closing notes.",
                        arrayOf("user"),
                        arrayOf(
                            "Create up to 8 flashcards from the text below.\n" +
                                "Use exactly one line per card, in this format:\n" +
                                "Q: <question> | A: <short answer>\n" +
                                "No numbering, no extra text.\n\nTEXT:\n$text"
                        ),
                        512
                    ) { }
                } catch (_: Exception) {
                    null
                }
            }
            busy = false
            if (reply == null) {
                status = "Generation failed — the model could not run. Check Model Manager."
                return@launch
            }
            val cards = parseFlashcards(reply, doc.name)
            if (cards.isEmpty()) {
                status = "The model returned no usable flashcards — try a clearer document."
                return@launch
            }
            flashcards.clear()
            flashcards.addAll(cards)
            currentCardIndex = 0
            showAnswer = false
            known = emptySet()
            saveStudyCards(ctx, flashcards)
            status = "${flashcards.size} flashcards saved from ${doc.name}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Study Companion",
            onMenuClick = onOpenDrawer,
            onBackClick = onNavigateToHome,
            moreActions = listOf(
                Triple("Generate flashcards", Icons.Default.AutoAwesome) { generate() },
                Triple("Reveal answer", Icons.Default.Visibility) { if (card != null) showAnswer = !showAnswer },
                Triple("Clear cards", Icons.Default.Delete) { if (flashcards.isNotEmpty()) confirmClear = true }
            )
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                KHero(
                    kicker = "Study // flashcards",
                    title = "Study companion",
                    subtitle = "Turn a vault document into an on-device deck. Cards, answers and progress stay on this phone.",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    tone = colors.accentAmber,
                    status = if (busy) "GENERATING" else if (device.networkUp) "NETWORK UP" else "OFFLINE",
                    statusTone = if (busy) KTone.Blue else KTone.Success
                ) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KHeroStat(
                            label = "Deck",
                            value = "${flashcards.size}",
                            unit = "cards",
                            tint = colors.accentGreen,
                            modifier = Modifier.weight(1f)
                        )
                        KHeroStat(
                            label = "Known",
                            value = "${known.size}",
                            unit = "this session",
                            tint = colors.accentBlue,
                            modifier = Modifier.weight(1f)
                        )
                        KHeroStat(
                            label = "Sources",
                            value = "${docs.size}",
                            unit = "vault docs",
                            tint = colors.accentAmber,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Document source: real vault text documents.
            item {
                KSectionHeader(
                    kicker = "Document source",
                    icon = Icons.Default.Description,
                    tone = colors.accentAmber,
                    trailing = "${docs.size} docs"
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        KIconTile(
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            tint = colors.accentAmber,
                            size = 32.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                docs.firstOrNull { it.name == selectedDoc }?.name
                                    ?: docs.firstOrNull()?.name
                                    ?: "No study document yet",
                                color = colors.textPrimary,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${docs.size} text document${if (docs.size == 1) "" else "s"} in the vault · " +
                                    "${if (device.networkUp) "Network Up" else "Offline"} · ${device.deviceModel}",
                                color = colors.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        OutlinedButton(
                            onClick = { refreshKey++ },
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                        ) { Text("Rescan", fontSize = 11.5.sp) }
                    }

                    if (docs.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Add a .txt or .md document in the Knowledge Vault to study from — flashcards are generated on-device from its text.",
                            color = colors.textMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    } else {
                        Spacer(Modifier.height(8.dp))
                        docs.take(8).forEach { d ->
                            val selected = d.name == (selectedDoc ?: docs.firstOrNull()?.name)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedDoc = d.name }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = { selectedDoc = d.name })
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        d.name,
                                        color = if (selected) colors.accentGreen else colors.textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "${(d.length() / 1024).coerceAtLeast(1)} KB · ${d.extension.uppercase()}",
                                        color = colors.textMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        if (docs.size > 8) {
                            Text("+${docs.size - 8} more documents", color = colors.textMuted, fontSize = 11.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { generate() },
                            enabled = !busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.accentGreen,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (busy) "Generating…" else "Generate flashcards", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (status != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(status!!, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
                    }
                }
            }

            // Active flashcard — flip to reveal, mark known, advance.
            item {
                KSectionHeader(
                    kicker = "Active flashcard",
                    icon = Icons.Default.Style,
                    tone = colors.accentGreen,
                    trailing = if (flashcards.isEmpty()) "0 cards" else "${currentCardIndex + 1} / ${flashcards.size}"
                )
                if (flashcards.isNotEmpty()) {
                    Text(
                        "Known this session: ${known.size}/${flashcards.size}",
                        color = colors.accentGreen,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 34.dp, top = 2.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                if (card == null) {
                    Text(
                        "No deck yet — pick a document above and tap Generate flashcards.",
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(colors.cardBackground, colors.surface)
                                )
                            )
                            .border(1.dp, colors.accentGreen.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
                            .padding(16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KIconTile(Icons.Default.Style, colors.accentGreen, 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "CARD // QUESTION",
                                color = colors.accentGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${currentCardIndex + 1} / ${flashcards.size}",
                                color = colors.textMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            card.question,
                            color = colors.textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 22.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        if (showAnswer) {
                            Text(
                                card.answer,
                                color = colors.textSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                        } else {
                            Text(
                                "Answer hidden — reveal when you are ready.",
                                color = colors.textMuted,
                                fontSize = 12.5.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(card.citation, color = colors.accentGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showAnswer = !showAnswer },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accentGreen,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(if (showAnswer) "Hide answer" else "Show answer", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = {
                                    known = known + currentCardIndex
                                    showAnswer = false
                                    currentCardIndex = (currentCardIndex + 1) % flashcards.size
                                },
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Knew this", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    showAnswer = false
                                    currentCardIndex = (currentCardIndex + 1) % flashcards.size
                                },
                                shape = RoundedCornerShape(999.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text("Next card", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Whole deck.
            if (flashcards.isNotEmpty()) {
                item {
                    KSectionHeader(
                        kicker = "Full deck",
                        icon = Icons.Default.Layers,
                        tone = colors.accentBlue,
                        trailing = "${flashcards.size} cards"
                    )
                }
                items(flashcards.size) { i ->
                    val c = flashcards[i]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .border(
                                1.dp,
                                if (i == currentCardIndex) colors.accentGreen.copy(alpha = 0.7f) else colors.border,
                                RoundedCornerShape(14.dp)
                            )
                            .clickable {
                                currentCardIndex = i
                                showAnswer = false
                            }
                            .padding(12.dp)
                    ) {
                        Text(c.question, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(c.answer, color = colors.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = colors.surface,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Clear all flashcards?") },
            text = { Text("The saved deck is deleted. You can generate it again from the same document.") },
            confirmButton = {
                TextButton(onClick = {
                    flashcards.clear()
                    saveStudyCards(ctx, emptyList())
                    currentCardIndex = 0
                    showAnswer = false
                    known = emptySet()
                    status = "Deck cleared"
                    confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        )
    }
}
