package com.karen.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
    "ini", "cfg", "properties", "toml", "html",
    "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h",
    "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "swift", "sh",
    "bash", "ps1", "sql", "gradle"
)

private val CODE_EXTS = setOf(
    "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h",
    "cpp", "hpp", "cc", "cs", "go", "rs", "rb", "php", "swift", "sh",
    "bash", "ps1", "sql", "gradle"
)

private val DOC_EXTS = setOf("md", "markdown", "txt", "html")

private val CODE_WORDS = listOf(
    "code", "bug", "fix", "error", "api", "kotlin", "python", "java",
    "javascript", "typescript", "build", "debug", "test", "function",
    "stack", "gradle", "android"
)

private val RESEARCH_WORDS = listOf(
    "research", "paper", "study", "source", "cite", "citation", "analysis",
    "literature", "evidence", "review", "hypothesis", "data"
)

private data class ChecklistEntry(
    val id: Long,
    val text: String,
    val done: Boolean
)

/** Small persistent checklist store for study planning surfaces. */
private object StudyChecklistStore {
    private const val PREFS = "karen_study_checklists"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(listKey: String) = "items_$listKey"

    fun load(ctx: Context, listKey: String, defaults: List<String>): List<ChecklistEntry> {
        val raw = prefs(ctx).getString(key(listKey), null)
        if (raw == null) {
            val seeded = defaults.mapIndexed { i, text -> ChecklistEntry(i.toLong(), text, false) }
            save(ctx, listKey, seeded)
            return seeded
        }
        return try {
            val arr = org.json.JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ChecklistEntry(
                    id = o.optLong("id", i.toLong()),
                    text = o.optString("text"),
                    done = o.optBoolean("done", false)
                )
            }.filter { it.text.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, listKey: String, items: List<ChecklistEntry>) {
        try {
            val arr = org.json.JSONArray()
            items.forEach { e ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", e.id)
                        .put("text", e.text)
                        .put("done", e.done)
                )
            }
            prefs(ctx).edit().putString(key(listKey), arr.toString()).apply()
        } catch (_: Exception) {
        }
    }
}

private enum class StudyProfile(
    val label: String,
    val heroTitle: String,
    val heroSubtitle: String,
    val icon: ImageVector
) {
    Student(
        "Student",
        "Student study hub",
        "Progress, due cards, subject sources and exam prep — generated on this phone.",
        Icons.Default.School
    ),
    Personal(
        "Personal",
        "Personal learning hub",
        "A daily card, your reading list, saved notes and recent conversations.",
        Icons.Default.Person
    ),
    Developer(
        "Developer",
        "Developer workspace",
        "Code Q&A, project sources, technical docs and debugging shortcuts.",
        Icons.Default.Code
    ),
    Researcher(
        "Researcher",
        "Research desk",
        "Query your sources, trace citations and keep research notes on-device.",
        Icons.Default.Science
    ),
    Professional(
        "Professional",
        "Professional desk",
        "Quick briefs, meeting prep, certification review and action items.",
        Icons.Default.Work
    );

    companion object {
        fun from(raw: String): StudyProfile = entries.firstOrNull {
            it.label.equals(raw.trim(), ignoreCase = true)
        } ?: Personal
    }
}

private fun StudyProfile.tone(colors: KarenColors): Color = when (this) {
    StudyProfile.Student -> colors.accentGreen
    StudyProfile.Personal -> colors.accentBlue
    StudyProfile.Developer -> colors.accentBlue
    StudyProfile.Researcher -> colors.accentAmber
    StudyProfile.Professional -> colors.accentGreen
}

private fun relativeTime(ts: Long): String {
    if (ts <= 0L) return "recent"
    val diff = (System.currentTimeMillis() - ts).coerceAtLeast(0L)
    return when {
        diff < 60_000L -> "just now"
        diff < 3_600_000L -> "${diff / 60_000L} min ago"
        diff < 86_400_000L -> "${diff / 3_600_000L} h ago"
        else -> "${diff / 86_400_000L} d ago"
    }
}

private fun docMeta(f: File): String {
    val kb = (f.length() / 1024).coerceAtLeast(1)
    return "$kb KB · ${f.extension.uppercase()}"
}

private fun firstPreview(f: File, maxChars: Int = 220): String {
    return try {
        java.io.InputStreamReader(f.inputStream(), Charsets.UTF_8).use { reader ->
            val buf = CharArray(maxChars * 2)
            val n = reader.read(buf)
            if (n <= 0) "" else String(buf, 0, n).replace(Regex("\\s+"), " ").trim().take(maxChars)
        }
    } catch (_: Exception) {
        ""
    }
}

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
    onNavigate: (String) -> Unit = {},
    onOpenConversation: (String) -> Unit = {},
    onAsk: (String) -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile = StudyProfile.from(UserPrefs.type(ctx))
    val listState = rememberLazyListState()

    val flashcards = remember { mutableStateListOf<FlashcardItem>().apply { addAll(loadStudyCards(ctx)) } }
    var currentCardIndex by remember { mutableStateOf(0) }
    var showAnswer by remember { mutableStateOf(false) }
    var known by remember { mutableStateOf(setOf<Int>()) }
    var selectedDoc by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var quizIndex by remember { mutableStateOf(0) }
    var quizRevealed by remember { mutableStateOf(false) }

    val docs = remember(refreshKey) {
        vaultFiles(ctx)
            .filter { it.isFile && it.extension.lowercase() in STUDY_TEXT_EXTS && it.length() in 1..2_000_000L }
            .sortedBy { it.name }
    }
    val conversations = remember(refreshKey) { ChatHistoryStore.loadConversations(ctx) }
    val facts = remember(refreshKey) { MemoryFacts.verified(ctx) }
    val codeDocs = remember(docs) { docs.filter { it.extension.lowercase() in CODE_EXTS } }
    val card = flashcards.getOrNull(currentCardIndex)
    val safeQuizIndex = if (flashcards.isEmpty()) 0 else quizIndex.coerceIn(0, flashcards.lastIndex)
    val dueCount = flashcards.indices.count { it !in known }
    val citationCount = flashcards.map { it.citation.ifBlank { "Untitled source" } }.distinct().size

    fun openDeck() {
        scope.launch { listState.animateScrollToItem(2) }
    }

    fun advanceQuiz(markKnown: Boolean) {
        if (flashcards.isEmpty()) return
        if (markKnown) known = known + safeQuizIndex
        quizIndex = (safeQuizIndex + 1) % flashcards.size
        quizRevealed = false
    }

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
            status = "No study document — add a .txt, .md or code file in the Knowledge Vault first."
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
            quizIndex = 0
            quizRevealed = false
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
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column {
                    KHero(
                        kicker = "Study // ${profile.label}",
                        title = profile.heroTitle,
                        subtitle = profile.heroSubtitle,
                        icon = profile.icon,
                        tone = profile.tone(colors),
                        status = if (busy) "GENERATING" else if (device.networkUp) "NETWORK UP" else "OFFLINE",
                        statusTone = if (busy) KTone.Blue else if (device.networkUp) KTone.Amber else KTone.Success
                    ) {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (profile) {
                            StudyProfile.Student -> {
                                KHeroStat("Deck", "${flashcards.size}", "cards", colors.accentGreen, Modifier.weight(1f))
                                KHeroStat("Known", "${known.size}", "session", colors.accentBlue, Modifier.weight(1f))
                                KHeroStat("Due", "$dueCount", "cards", colors.accentAmber, Modifier.weight(1f))
                            }
                            StudyProfile.Personal -> {
                                KHeroStat("Cards", "${flashcards.size}", "deck", colors.accentGreen, Modifier.weight(1f))
                                KHeroStat("Notes", "${facts.size}", "saved", colors.accentBlue, Modifier.weight(1f))
                                KHeroStat("Chats", "${conversations.size}", "recent", colors.accentAmber, Modifier.weight(1f))
                            }
                            StudyProfile.Developer -> {
                                KHeroStat("Code", "${codeDocs.size}", "files", colors.accentBlue, Modifier.weight(1f))
                                KHeroStat("Docs", "${docs.size}", "sources", colors.accentGreen, Modifier.weight(1f))
                                KHeroStat("Chats", "${conversations.size}", "sessions", colors.accentAmber, Modifier.weight(1f))
                            }
                            StudyProfile.Researcher -> {
                                KHeroStat("Sources", "${docs.size}", "docs", colors.accentAmber, Modifier.weight(1f))
                                KHeroStat("Cite", "$citationCount", "sources", colors.accentGreen, Modifier.weight(1f))
                                KHeroStat("Notes", "${facts.size}", "saved", colors.accentBlue, Modifier.weight(1f))
                            }
                            StudyProfile.Professional -> {
                                KHeroStat("Docs", "${docs.size}", "vault", colors.accentAmber, Modifier.weight(1f))
                                KHeroStat("Cards", "${flashcards.size}", "deck", colors.accentGreen, Modifier.weight(1f))
                                KHeroStat("Due", "$dueCount", "cards", colors.accentBlue, Modifier.weight(1f))
                            }
                        }
                    }
                }
                status?.let { s ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s,
                        color = colors.textMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                }
            }

            item {
                when (profile) {
                    StudyProfile.Student -> StudentDashboard(
                        docs = docs,
                        flashcards = flashcards,
                        known = known,
                        quizIndex = safeQuizIndex,
                        quizRevealed = quizRevealed,
                        selectedDoc = selectedDoc,
                        busy = busy,
                        onSelectDoc = { selectedDoc = it },
                        onOpenDeck = { openDeck() },
                        onGenerate = { generate(); openDeck() },
                        onQuizReveal = { quizRevealed = !quizRevealed },
                        onQuizNext = { advanceQuiz(false) },
                        onQuizKnown = { advanceQuiz(true) },
                        onReviewDue = {
                            val due = flashcards.indices.firstOrNull { it !in known }
                            if (due == null) {
                                status = "All cards are marked known this session."
                            } else {
                                quizIndex = due
                                quizRevealed = false
                            }
                        },
                        onNavigate = onNavigate
                    )
                    StudyProfile.Personal -> PersonalDashboard(
                        docs = docs,
                        flashcards = flashcards,
                        facts = facts,
                        conversations = conversations,
                        selectedDoc = selectedDoc,
                        onSelectDoc = { selectedDoc = it },
                        onOpenDeck = { openDeck() },
                        onNavigate = onNavigate,
                        onOpenConversation = onOpenConversation
                    )
                    StudyProfile.Developer -> DeveloperDashboard(
                        docs = docs,
                        codeDocs = codeDocs,
                        conversations = conversations,
                        onSelectDoc = { selectedDoc = it },
                        onOpenDeck = { openDeck() },
                        onNavigate = onNavigate,
                        onOpenConversation = onOpenConversation,
                        onAsk = onAsk
                    )
                    StudyProfile.Researcher -> ResearcherDashboard(
                        docs = docs,
                        flashcards = flashcards,
                        facts = facts,
                        conversations = conversations,
                        onSelectDoc = { selectedDoc = it },
                        onOpenDeck = { openDeck() },
                        onOpenCard = { i ->
                            currentCardIndex = i.coerceIn(0, (flashcards.size - 1).coerceAtLeast(0))
                            showAnswer = false
                            openDeck()
                        },
                        onNavigate = onNavigate,
                        onOpenConversation = onOpenConversation,
                        onAsk = onAsk
                    )
                    StudyProfile.Professional -> ProfessionalDashboard(
                        docs = docs,
                        flashcards = flashcards,
                        known = known,
                        dueCount = dueCount,
                        busy = busy,
                        onSelectDoc = { selectedDoc = it },
                        onOpenDeck = { openDeck() },
                        onGenerate = { generate(); openDeck() },
                        onStartDeck = {
                            val target = flashcards.indices.firstOrNull { it !in known }
                                ?: flashcards.indices.firstOrNull()
                            if (target != null) {
                                currentCardIndex = target
                                showAnswer = false
                            }
                            openDeck()
                        },
                        onNavigate = onNavigate,
                        onAsk = onAsk
                    )
                }
            }

            item {
                StudyDeckSection(
                    docs = docs,
                    selectedDoc = selectedDoc,
                    onSelectDoc = { selectedDoc = it },
                    onRescan = { refreshKey++ },
                    flashcards = flashcards,
                    currentCardIndex = currentCardIndex,
                    showAnswer = showAnswer,
                    known = known,
                    busy = busy,
                    status = status,
                    deviceNetworkUp = device.networkUp,
                    deviceModel = device.deviceModel,
                    onGenerate = { generate() },
                    onToggleReveal = { if (card != null) showAnswer = !showAnswer },
                    onKnown = {
                        if (flashcards.isNotEmpty()) {
                            known = known + currentCardIndex
                            showAnswer = false
                            currentCardIndex = (currentCardIndex + 1) % flashcards.size
                        }
                    },
                    onNext = {
                        if (flashcards.isNotEmpty()) {
                            showAnswer = false
                            currentCardIndex = (currentCardIndex + 1) % flashcards.size
                        }
                    },
                    onSelectCard = { i ->
                        currentCardIndex = i.coerceIn(0, (flashcards.size - 1).coerceAtLeast(0))
                        showAnswer = false
                    },
                    onClearRequest = { if (flashcards.isNotEmpty()) confirmClear = true }
                )
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
                    quizIndex = 0
                    quizRevealed = false
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

@Composable
private fun StudentDashboard(
    docs: List<File>,
    flashcards: List<FlashcardItem>,
    known: Set<Int>,
    quizIndex: Int,
    quizRevealed: Boolean,
    selectedDoc: String?,
    busy: Boolean,
    onSelectDoc: (String) -> Unit,
    onOpenDeck: () -> Unit,
    onGenerate: () -> Unit,
    onQuizReveal: () -> Unit,
    onQuizNext: () -> Unit,
    onQuizKnown: () -> Unit,
    onReviewDue: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val colors = LocalKarenColors.current
    val dueCount = flashcards.indices.count { it !in known }
    val progress = if (flashcards.isEmpty()) 0f else (known.size.toFloat() / flashcards.size).coerceIn(0f, 1f)
    val dueProgress = if (flashcards.isEmpty()) 0f else (dueCount.toFloat() / flashcards.size).coerceIn(0f, 1f)
    val activeDoc = docs.firstOrNull { it.name == selectedDoc } ?: docs.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileCard(
            kicker = "Study Progress",
            icon = Icons.AutoMirrored.Filled.TrendingUp,
            tone = colors.accentGreen,
            trailing = "${(progress * 100).toInt()}%"
        ) {
            Text(
                "${known.size} of ${flashcards.size} cards marked known this session",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(9.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = colors.accentGreen,
                trackColor = colors.surfaceHover
            )
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Deck ${flashcards.size}", color = colors.textMuted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(
                    "$dueCount due",
                    color = if (dueCount > 0) colors.accentAmber else colors.accentGreen,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfilePillButton("Open deck", colors.accentGreen) { onOpenDeck() }
                ProfileOutlineButton("Open Files") { onNavigate("Files") }
            }
        }

        ProfileCard(
            kicker = "Continue Learning",
            icon = Icons.AutoMirrored.Filled.MenuBook,
            tone = colors.accentBlue,
            trailing = if (activeDoc != null) "ready" else "no source"
        ) {
            if (activeDoc == null) {
                ProfileEmpty(
                    "Add a .txt, .md or code file in the Knowledge Vault to start a deck.",
                    "Open Files"
                ) { onNavigate("Files") }
            } else {
                Text(
                    activeDoc.name,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(docMeta(activeDoc), color = colors.textMuted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(7.dp))
                Text(
                    remember(activeDoc.path, activeDoc.lastModified()) { firstPreview(activeDoc) }
                        .ifBlank { "Text preview unavailable for this file." },
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePillButton("Open study deck", colors.accentBlue) {
                        onSelectDoc(activeDoc.name)
                        onOpenDeck()
                    }
                    ProfileOutlineButton("Open Files") { onNavigate("Files") }
                }
            }
        }

        ProfileCard(
            kicker = "Daily Quiz",
            icon = Icons.Default.Quiz,
            tone = colors.accentAmber,
            trailing = if (flashcards.isEmpty()) "no deck" else "${quizIndex + 1}/${flashcards.size}"
        ) {
            StudyQuizCard(
                flashcards = flashcards,
                index = quizIndex,
                revealed = quizRevealed,
                onReveal = onQuizReveal,
                onNext = onQuizNext,
                onKnown = onQuizKnown,
                onGenerate = onGenerate
            )
        }

        ProfileCard(
            kicker = "Flashcards Due",
            icon = Icons.Default.Schedule,
            tone = colors.accentAmber,
            trailing = "$dueCount due"
        ) {
            Text(
                "$dueCount of ${flashcards.size} cards are still due this session.",
                color = colors.textSecondary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(9.dp))
            LinearProgressIndicator(
                progress = { dueProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = colors.accentAmber,
                trackColor = colors.surfaceHover
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfilePillButton("Review due", colors.accentAmber, enabled = dueCount > 0) { onReviewDue() }
                ProfileOutlineButton("Open deck") { onOpenDeck() }
            }
        }

        ProfileCard(
            kicker = "Subject Cards",
            icon = Icons.Default.FolderOpen,
            tone = colors.accentBlue,
            trailing = "${docs.size} sources"
        ) {
            if (docs.isEmpty()) {
                ProfileEmpty("No subjects in the vault yet.", "Open Files") { onNavigate("Files") }
            } else {
                docs.take(6).chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { d ->
                            SubjectTile(
                                doc = d,
                                selected = d.name == selectedDoc,
                                onClick = { onSelectDoc(d.name) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (docs.size > 6) {
                    Text(
                        "+${docs.size - 6} more sources in the vault",
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                ProfileOutlineButton("Open Files") { onNavigate("Files") }
            }
        }

        ChecklistSection(
            listKey = "student_exam",
            kicker = "Exam Preparation",
            icon = Icons.AutoMirrored.Filled.Assignment,
            tone = colors.accentGreen,
            defaults = listOf(
                "Review today's notes",
                "Refresh the flashcard deck",
                "Take the daily quiz",
                "Mark weak cards for review"
            ),
            allowAdd = false
        ) {
            Spacer(Modifier.height(8.dp))
            ProfilePillButton(
                text = "Generate exam deck",
                tone = colors.accentGreen,
                icon = Icons.Default.AutoAwesome,
                enabled = !busy
            ) { onGenerate() }
        }
    }
}

@Composable
private fun PersonalDashboard(
    docs: List<File>,
    flashcards: List<FlashcardItem>,
    facts: List<MemoryFacts.Fact>,
    conversations: List<ChatConversation>,
    selectedDoc: String?,
    onSelectDoc: (String) -> Unit,
    onOpenDeck: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenConversation: (String) -> Unit
) {
    val colors = LocalKarenColors.current
    var dailyIndex by remember { mutableStateOf(0) }
    var dailyRevealed by remember { mutableStateOf(false) }
    val activeDoc = docs.firstOrNull { it.name == selectedDoc } ?: docs.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileCard(
            kicker = "Daily Knowledge Card",
            icon = Icons.Default.Lightbulb,
            tone = colors.accentAmber,
            trailing = "daily"
        ) {
            DailyKnowledgeCard(
                flashcards = flashcards,
                facts = facts,
                index = dailyIndex,
                revealed = dailyRevealed,
                onReveal = { dailyRevealed = !dailyRevealed },
                onNext = {
                    dailyIndex++
                    dailyRevealed = false
                },
                onOpenDeck = onOpenDeck,
                onNavigate = onNavigate
            )
        }

        ProfileCard(
            kicker = "Continue Reading",
            icon = Icons.AutoMirrored.Filled.MenuBook,
            tone = colors.accentGreen,
            trailing = if (activeDoc != null) "ready" else "no source"
        ) {
            if (activeDoc == null) {
                ProfileEmpty(
                    "Add a text document or code file to the vault to keep a reading list.",
                    "Open Files"
                ) { onNavigate("Files") }
            } else {
                Text(
                    activeDoc.name,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(docMeta(activeDoc), color = colors.textMuted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(7.dp))
                Text(
                    remember(activeDoc.path, activeDoc.lastModified()) { firstPreview(activeDoc) }
                        .ifBlank { "Text preview unavailable for this file." },
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePillButton("Study this doc", colors.accentGreen) {
                        onSelectDoc(activeDoc.name)
                        onOpenDeck()
                    }
                    ProfileOutlineButton("Open Files") { onNavigate("Files") }
                }
            }
        }

        ProfileCard(
            kicker = "Saved Notes",
            icon = Icons.Default.Bookmark,
            tone = colors.accentBlue,
            trailing = "${facts.size} saved"
        ) {
            if (facts.isEmpty()) {
                ProfileEmpty(
                    "No confirmed notes yet. Add a fact in Memory to keep it here.",
                    "Open Memory"
                ) { onNavigate("Memory") }
            } else {
                facts.take(3).forEach { f ->
                    ProfileActionRow(
                        icon = Icons.Default.Bookmark,
                        tint = colors.accentBlue,
                        title = f.text,
                        subtitle = "${f.category} · confirmed",
                        titleMaxLines = 2
                    ) { onNavigate("Memory") }
                }
                if (facts.size > 3) {
                    Text("+${facts.size - 3} more in Memory", color = colors.textMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                ProfilePillButton("Open Memory", colors.accentBlue) { onNavigate("Memory") }
            }
        }

        ProfileCard(
            kicker = "Recent Conversations",
            icon = Icons.AutoMirrored.Filled.Chat,
            tone = colors.accentGreen,
            trailing = "${conversations.size} chats"
        ) {
            if (conversations.isEmpty()) {
                ProfileEmpty("No chats yet. Start one when a topic needs a deeper answer.", "Open Chat") {
                    onNavigate("Chat")
                }
            } else {
                conversations.take(3).forEach { c ->
                    ProfileActionRow(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        tint = colors.accentGreen,
                        title = c.title,
                        subtitle = relativeTime(c.updatedAt)
                    ) { onOpenConversation(c.id) }
                }
                Spacer(Modifier.height(8.dp))
                ProfilePillButton("Open Chat", colors.accentGreen) { onNavigate("Chat") }
            }
        }

        ProfileCard(
            kicker = "Explore Topics",
            icon = Icons.Default.Explore,
            tone = colors.accentAmber,
            trailing = "${docs.size} sources"
        ) {
            if (docs.isEmpty()) {
                ProfileEmpty("No sources yet — add notes, articles, or code to the vault.", "Open Files") {
                    onNavigate("Files")
                }
            } else {
                docs.take(6).chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { d ->
                            SubjectTile(
                                doc = d,
                                selected = d.name == selectedDoc,
                                onClick = { onSelectDoc(d.name) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                ProfileOutlineButton("Open Files") { onNavigate("Files") }
            }
        }
    }
}

@Composable
private fun StudyQuizCard(
    flashcards: List<FlashcardItem>,
    index: Int,
    revealed: Boolean,
    onReveal: () -> Unit,
    onNext: () -> Unit,
    onKnown: () -> Unit,
    onGenerate: () -> Unit
) {
    val colors = LocalKarenColors.current
    val card = flashcards.getOrNull(index)
    if (card == null) {
        ProfileEmpty(
            "No deck yet — generate cards from a vault document to start the daily quiz.",
            "Build from a source"
        ) { onGenerate() }
        return
    }
    Text(
        "QUESTION ${index + 1} / ${flashcards.size}",
        color = colors.accentAmber,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(8.dp))
    Text(
        card.question,
        color = colors.textPrimary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 21.sp
    )
    Spacer(Modifier.height(8.dp))
    if (revealed) {
        Text(card.answer, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
    } else {
        Text("Answer hidden — reveal when you are ready.", color = colors.textMuted, fontSize = 12.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text(card.citation, color = colors.accentGreen, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProfilePillButton(if (revealed) "Hide" else "Reveal", colors.accentAmber) { onReveal() }
        ProfileOutlineButton("Knew this") { onKnown() }
        ProfileOutlineButton("Next") { onNext() }
    }
}

@Composable
private fun DailyKnowledgeCard(
    flashcards: List<FlashcardItem>,
    facts: List<MemoryFacts.Fact>,
    index: Int,
    revealed: Boolean,
    onReveal: () -> Unit,
    onNext: () -> Unit,
    onOpenDeck: () -> Unit,
    onNavigate: (String) -> Unit
) {
    val colors = LocalKarenColors.current
    val card = if (flashcards.isEmpty()) null else flashcards[index % flashcards.size]
    if (card != null) {
        Text(
            "CARD ${index % flashcards.size + 1} / ${flashcards.size}",
            color = colors.accentAmber,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        Text(
            card.question,
            color = colors.textPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(8.dp))
        if (revealed) {
            Text(card.answer, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
        } else {
            Text("Answer hidden — reveal when you are ready.", color = colors.textMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(card.citation, color = colors.accentGreen, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfilePillButton(if (revealed) "Hide" else "Reveal", colors.accentAmber) { onReveal() }
            ProfileOutlineButton("Next") { onNext() }
            ProfileOutlineButton("Open deck") { onOpenDeck() }
        }
        return
    }
    val fact = if (facts.isEmpty()) null else facts[index % facts.size]
    if (fact != null) {
        Text(
            "SAVED NOTE ${index % facts.size + 1} / ${facts.size}",
            color = colors.accentBlue,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        Text(fact.text, color = colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "${fact.category} · confirmed memory",
            color = colors.accentBlue,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfilePillButton("Next note", colors.accentBlue) { onNext() }
            ProfileOutlineButton("Open Memory") { onNavigate("Memory") }
        }
        return
    }
    ProfileEmpty(
        "No daily card yet — generate a deck or save a note in Memory.",
        "Open Files"
    ) { onNavigate("Files") }
}

@Composable
private fun DeveloperDashboard(
    docs: List<File>,
    codeDocs: List<File>,
    conversations: List<ChatConversation>,
    onSelectDoc: (String) -> Unit,
    onOpenDeck: () -> Unit,
    onNavigate: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onAsk: (String) -> Unit
) {
    val colors = LocalKarenColors.current
    val techDocs = docs.filter { it.extension.lowercase() in DOC_EXTS }
    val recentCode = codeDocs.sortedByDescending { it.lastModified() }
    val sessions = conversations.filter { c ->
        CODE_WORDS.any { c.title.contains(it, ignoreCase = true) }
    }.ifEmpty { conversations }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileCard(
            kicker = "Code Q&A",
            icon = Icons.Default.Code,
            tone = colors.accentBlue,
            trailing = "ask"
        ) {
            Text(
                "Ask about a project file, stack trace, or API. The answer can be grounded in a code document from your vault.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "Explain code" to "Explain this code and its key decisions:\n\n",
                    "Find a bug" to "Find the bug in this code and suggest a fix:\n\n",
                    "Write a test" to "Write a focused test for this code:\n\n"
                ).forEach { (label, prompt) ->
                    OutlinedButton(
                        onClick = { onAsk(prompt) },
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(label, fontSize = 11.5.sp, color = colors.textSecondary)
                    }
                }
            }
            if (codeDocs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "CODE IN THE VAULT",
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                recentCode.take(3).forEach { d ->
                    ProfileActionRow(
                        icon = Icons.Default.Code,
                        tint = colors.accentBlue,
                        title = d.name,
                        subtitle = docMeta(d),
                        trailing = "SELECT"
                    ) { onSelectDoc(d.name) }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                ProfileEmpty("No code files in the vault yet.", "Open Files") { onNavigate("Files") }
            }
        }

        ProfileCard(
            kicker = "Recent Projects",
            icon = Icons.Default.FolderOpen,
            tone = colors.accentGreen,
            trailing = "${codeDocs.size} files"
        ) {
            if (codeDocs.isEmpty()) {
                ProfileEmpty("Add a source file to the Knowledge Vault to see projects here.", "Open Files") {
                    onNavigate("Files")
                }
            } else {
                recentCode.take(4).forEach { d ->
                    ProfileActionRow(
                        icon = Icons.Default.Code,
                        tint = colors.accentGreen,
                        title = d.name,
                        subtitle = docMeta(d),
                        trailing = "SELECT"
                    ) { onSelectDoc(d.name) }
                }
                Spacer(Modifier.height(8.dp))
                ProfilePillButton("Open study deck", colors.accentGreen) { onOpenDeck() }
            }
        }

        ProfileCard(
            kicker = "Technical Documentation",
            icon = Icons.Default.Description,
            tone = colors.accentAmber,
            trailing = "${techDocs.size} docs"
        ) {
            if (techDocs.isEmpty()) {
                ProfileEmpty("No markdown or text documentation in the vault yet.", "Open Files") {
                    onNavigate("Files")
                }
            } else {
                techDocs.take(4).forEach { d ->
                    ProfileActionRow(
                        icon = Icons.Default.Description,
                        tint = colors.accentAmber,
                        title = d.name,
                        subtitle = docMeta(d),
                        trailing = "SELECT"
                    ) { onSelectDoc(d.name) }
                }
                Spacer(Modifier.height(8.dp))
                ProfileOutlineButton("Open Files") { onNavigate("Files") }
            }
        }

        ProfileCard(
            kicker = "Debugging Shortcuts",
            icon = Icons.Default.BugReport,
            tone = colors.accentRed,
            trailing = "4 prompts"
        ) {
            listOf(
                "Explain a stack trace" to "Explain this stack trace and identify the root cause:\n\n",
                "Find the bug" to "Find the bug in this code and propose a minimal fix:\n\n",
                "Write a failing test" to "Write a failing test that reproduces this bug:\n\n",
                "Review a diff" to "Review this diff for bugs, regressions, and missing tests:\n\n"
            ).forEach { (label, prompt) ->
                ProfileActionRow(
                    icon = Icons.Default.Terminal,
                    tint = colors.accentRed,
                    title = label,
                    subtitle = "Prefill in Chat"
                ) { onAsk(prompt) }
            }
        }

        ProfileCard(
            kicker = "Recent Code Sessions",
            icon = Icons.Default.History,
            tone = colors.accentBlue,
            trailing = "${sessions.size}"
        ) {
            if (sessions.isEmpty()) {
                ProfileEmpty("No code sessions yet. Start a chat with a stack trace or a file.", "Open Chat") {
                    onNavigate("Chat")
                }
            } else {
                sessions.take(4).forEach { c ->
                    ProfileActionRow(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        tint = colors.accentBlue,
                        title = c.title,
                        subtitle = relativeTime(c.updatedAt)
                    ) { onOpenConversation(c.id) }
                }
                Spacer(Modifier.height(8.dp))
                ProfilePillButton("Open Chat", colors.accentBlue) { onNavigate("Chat") }
            }
        }
    }
}

@Composable
private fun ResearcherDashboard(
    docs: List<File>,
    flashcards: List<FlashcardItem>,
    facts: List<MemoryFacts.Fact>,
    conversations: List<ChatConversation>,
    onSelectDoc: (String) -> Unit,
    onOpenDeck: () -> Unit,
    onOpenCard: (Int) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onAsk: (String) -> Unit
) {
    val colors = LocalKarenColors.current
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val docHits = if (q.length < 2) emptyList() else docs.filter { it.name.contains(q, ignoreCase = true) }
    val cardHits = if (q.length < 2) emptyList() else flashcards.filter {
        it.question.contains(q, true) || it.answer.contains(q, true) || it.citation.contains(q, true)
    }
    val factHits = if (q.length < 2) emptyList() else facts.filter {
        it.text.contains(q, true) || it.category.contains(q, true)
    }
    val citations = flashcards.groupingBy { it.citation.ifBlank { "Untitled source" } }
        .eachCount()
        .toList()
        .sortedByDescending { it.second }
    val researchSessions = conversations.filter { c ->
        RESEARCH_WORDS.any { c.title.contains(it, ignoreCase = true) }
    }.ifEmpty { conversations }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileCard(
            kicker = "Research Query",
            icon = Icons.Default.Search,
            tone = colors.accentAmber,
            trailing = "local"
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                placeholder = { Text("Search titles, cards, and notes…", color = colors.textMuted, fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp),
                colors = karenFieldColors(colors),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(9.dp))
            if (q.length < 2) {
                Text(
                    "Type at least two characters to search your vault, deck, and confirmed notes.",
                    color = colors.textMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            } else {
                Text(
                    "${docHits.size} sources · ${cardHits.size} cards · ${factHits.size} notes",
                    color = colors.accentAmber,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(6.dp))
                if (docHits.isEmpty() && cardHits.isEmpty() && factHits.isEmpty()) {
                    Text(
                        "No local matches. Ask in Chat to research the web or reason from what you know.",
                        color = colors.textMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                } else {
                    docHits.take(3).forEach { d ->
                        ProfileActionRow(
                            icon = Icons.Default.Description,
                            tint = colors.accentAmber,
                            title = d.name,
                            subtitle = docMeta(d),
                            trailing = "SELECT"
                        ) { onSelectDoc(d.name) }
                    }
                    cardHits.take(2).forEach { c ->
                        val cardIndex = flashcards.indexOf(c)
                        ProfileActionRow(
                            icon = Icons.Default.Style,
                            tint = colors.accentGreen,
                            title = c.question,
                            subtitle = c.citation,
                            trailing = "OPEN",
                            titleMaxLines = 2
                        ) { if (cardIndex >= 0) onOpenCard(cardIndex) }
                    }
                    factHits.take(2).forEach { f ->
                        ProfileActionRow(
                            icon = Icons.Default.Bookmark,
                            tint = colors.accentBlue,
                            title = f.text,
                            subtitle = "${f.category} · confirmed",
                            titleMaxLines = 2
                        ) { onNavigate("Memory") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePillButton(
                        text = "Research in Chat",
                        tone = colors.accentAmber,
                        icon = Icons.Default.Search
                    ) {
                        onAsk(
                            if (q.isBlank()) "Research this topic and cite the sources: "
                            else "Research this topic and cite the sources: $q"
                        )
                    }
                    if (q.isNotBlank()) ProfileOutlineButton("Clear") { query = "" }
                }
            }
        }

        ProfileCard(
            kicker = "Recent Research",
            icon = Icons.Default.History,
            tone = colors.accentBlue,
            trailing = "${researchSessions.size}"
        ) {
            if (researchSessions.isEmpty()) {
                ProfileEmpty("No research sessions yet.", "Open Chat") { onNavigate("Chat") }
            } else {
                researchSessions.take(4).forEach { c ->
                    ProfileActionRow(
                        icon = Icons.AutoMirrored.Filled.Chat,
                        tint = colors.accentBlue,
                        title = c.title,
                        subtitle = relativeTime(c.updatedAt)
                    ) { onOpenConversation(c.id) }
                }
                Spacer(Modifier.height(8.dp))
                ProfilePillButton("Open Chat", colors.accentBlue) { onNavigate("Chat") }
            }
        }

        ProfileCard(
            kicker = "Source Library",
            icon = Icons.Default.FolderOpen,
            tone = colors.accentGreen,
            trailing = "${docs.size} docs"
        ) {
            if (docs.isEmpty()) {
                ProfileEmpty("No sources in the vault yet.", "Open Files") { onNavigate("Files") }
            } else {
                docs.take(5).forEach { d ->
                    ProfileActionRow(
                        icon = Icons.Default.Description,
                        tint = colors.accentGreen,
                        title = d.name,
                        subtitle = docMeta(d),
                        trailing = "SELECT"
                    ) { onSelectDoc(d.name) }
                }
                if (docs.size > 5) {
                    Text("+${docs.size - 5} more sources", color = colors.textMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePillButton("Open study deck", colors.accentGreen) { onOpenDeck() }
                    ProfileOutlineButton("Open Files") { onNavigate("Files") }
                }
            }
        }

        ProfileCard(
            kicker = "Evidence & Citations",
            icon = Icons.Default.FormatQuote,
            tone = colors.accentAmber,
            trailing = "${citations.size} sources"
        ) {
            if (citations.isEmpty()) {
                ProfileEmpty("Generate a deck to attach citations to every card.", "Open study deck") {
                    onOpenDeck()
                }
            } else {
                citations.take(5).forEach { (source, count) ->
                    val first = flashcards.indexOfFirst {
                        it.citation.ifBlank { "Untitled source" } == source
                    }
                    ProfileActionRow(
                        icon = Icons.Default.FormatQuote,
                        tint = colors.accentAmber,
                        title = source.ifBlank { "Untitled source" },
                        subtitle = "$count card${if (count == 1) "" else "s"}",
                        trailing = "OPEN"
                    ) { if (first >= 0) onOpenCard(first) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Citations come from the source document named when each card is generated.",
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        ProfileCard(
            kicker = "Research Notes",
            icon = Icons.Default.EditNote,
            tone = colors.accentBlue,
            trailing = "${facts.size} notes"
        ) {
            if (facts.isEmpty()) {
                ProfileEmpty("No confirmed research notes yet. Add facts in Memory.", "Open Memory") {
                    onNavigate("Memory")
                }
            } else {
                facts.take(4).forEach { f ->
                    ProfileActionRow(
                        icon = Icons.Default.Bookmark,
                        tint = colors.accentBlue,
                        title = f.text,
                        subtitle = "${f.category} · confirmed",
                        titleMaxLines = 2
                    ) { onNavigate("Memory") }
                }
                if (facts.size > 4) {
                    Text("+${facts.size - 4} more notes in Memory", color = colors.textMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                ProfilePillButton("Open Memory", colors.accentBlue) { onNavigate("Memory") }
            }
        }
    }
}

@Composable
private fun ProfessionalDashboard(
    docs: List<File>,
    flashcards: List<FlashcardItem>,
    known: Set<Int>,
    dueCount: Int,
    busy: Boolean,
    onSelectDoc: (String) -> Unit,
    onOpenDeck: () -> Unit,
    onGenerate: () -> Unit,
    onStartDeck: () -> Unit,
    onNavigate: (String) -> Unit,
    onAsk: (String) -> Unit
) {
    val colors = LocalKarenColors.current
    val recentDocs = docs.sortedByDescending { it.lastModified() }
    val latest = recentDocs.firstOrNull()
    val progress = if (flashcards.isEmpty()) 0f else (known.size.toFloat() / flashcards.size).coerceIn(0f, 1f)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileCard(
            kicker = "Quick Brief",
            icon = Icons.Default.Bolt,
            tone = colors.accentAmber,
            trailing = "latest"
        ) {
            if (latest == null) {
                ProfileEmpty("Add a document to the vault to get a quick brief.", "Open Files") {
                    onNavigate("Files")
                }
            } else {
                Text(
                    "LATEST SOURCE // SNAPSHOT",
                    color = colors.accentAmber,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    latest.name,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(docMeta(latest), color = colors.textMuted, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(8.dp))
                Text(
                    remember(latest.path, latest.lastModified()) { firstPreview(latest, 260) }
                        .ifBlank { "Text preview unavailable for this file." },
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePillButton("Open Files", colors.accentAmber) { onNavigate("Files") }
                    ProfileOutlineButton("Study this doc") {
                        onSelectDoc(latest.name)
                        onOpenDeck()
                    }
                }
                Spacer(Modifier.height(6.dp))
                ProfileOutlineButton("Draft a brief in Chat") {
                    onAsk("Draft a concise executive brief. I'll paste the source material next:\n\n")
                }
            }
        }

        ChecklistSection(
            listKey = "professional_meeting",
            kicker = "Meeting Preparation",
            icon = Icons.Default.People,
            tone = colors.accentBlue,
            defaults = listOf(
                "Goal and agenda confirmed",
                "Attendees and roles reviewed",
                "Key decisions captured",
                "Follow-up actions assigned"
            )
        ) {
            Spacer(Modifier.height(8.dp))
            ProfilePillButton("Draft agenda in Chat", colors.accentBlue) {
                onAsk("Draft a concise meeting agenda with goals, attendees, decisions, and follow-up actions:\n\n")
            }
        }

        ProfileCard(
            kicker = "Certification Prep",
            icon = Icons.Default.Verified,
            tone = colors.accentGreen,
            trailing = if (flashcards.isEmpty()) "no deck" else "$dueCount due"
        ) {
            if (flashcards.isEmpty()) {
                ProfileEmpty(
                    "No certification deck yet. Build one from a study guide in the vault.",
                    "Generate deck"
                ) { onGenerate() }
            } else {
                Text(
                    "${known.size} of ${flashcards.size} cards marked known this session",
                    color = colors.textSecondary,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(9.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = colors.accentGreen,
                    trackColor = colors.surfaceHover
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePillButton("Start deck", colors.accentGreen, enabled = !busy) { onStartDeck() }
                    ProfileOutlineButton("Refresh deck") { onGenerate() }
                }
            }
        }

        ProfileCard(
            kicker = "Recent Documents",
            icon = Icons.Default.Description,
            tone = colors.accentAmber,
            trailing = "${docs.size} docs"
        ) {
            if (recentDocs.isEmpty()) {
                ProfileEmpty("No documents in the vault yet.", "Open Files") { onNavigate("Files") }
            } else {
                recentDocs.take(5).forEach { d ->
                    ProfileActionRow(
                        icon = Icons.Default.Description,
                        tint = colors.accentAmber,
                        title = d.name,
                        subtitle = docMeta(d),
                        trailing = "SELECT"
                    ) { onSelectDoc(d.name) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfilePillButton("Open study deck", colors.accentAmber) { onOpenDeck() }
                    ProfileOutlineButton("Open Files") { onNavigate("Files") }
                }
            }
        }

        ChecklistSection(
            listKey = "professional_actions",
            kicker = "Action Items",
            icon = Icons.Default.CheckCircle,
            tone = colors.accentGreen,
            defaults = emptyList(),
            allowAdd = true,
            addPlaceholder = "Add an action item..."
        )
    }
}

@Composable
private fun StudyDeckSection(
    docs: List<File>,
    selectedDoc: String?,
    onSelectDoc: (String) -> Unit,
    onRescan: () -> Unit,
    flashcards: List<FlashcardItem>,
    currentCardIndex: Int,
    showAnswer: Boolean,
    known: Set<Int>,
    busy: Boolean,
    status: String?,
    deviceNetworkUp: Boolean,
    deviceModel: String,
    onGenerate: () -> Unit,
    onToggleReveal: () -> Unit,
    onKnown: () -> Unit,
    onNext: () -> Unit,
    onSelectCard: (Int) -> Unit,
    onClearRequest: () -> Unit
) {
    val colors = LocalKarenColors.current
    val card = flashcards.getOrNull(currentCardIndex)
    val activeDocName = selectedDoc ?: docs.firstOrNull()?.name

    Column {
        KSectionHeader(
            kicker = "Study deck",
            icon = Icons.AutoMirrored.Filled.MenuBook,
            tone = colors.accentAmber,
            trailing = if (flashcards.isEmpty()) "empty" else "${flashcards.size} cards"
        )
        Spacer(Modifier.height(8.dp))

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
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${docs.size} text document${if (docs.size == 1) "" else "s"} in the vault · " +
                            "${if (deviceNetworkUp) "Network Up" else "Offline"} · $deviceModel",
                        color = colors.textMuted,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onRescan,
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) { Text("Rescan", fontSize = 11.5.sp) }
            }

            if (docs.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Add a .txt, .md or code document in the Knowledge Vault to study from — flashcards are generated on-device from its text.",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            } else {
                Spacer(Modifier.height(8.dp))
                docs.take(8).forEach { d ->
                    val selected = d.name == activeDocName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectDoc(d.name) }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = { onSelectDoc(d.name) })
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                d.name,
                                color = if (selected) colors.accentGreen else colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onGenerate,
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
                        Text(
                            if (busy) "Generating…" else "Generate flashcards",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (flashcards.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearRequest,
                            shape = RoundedCornerShape(999.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) { Text("Clear deck", fontSize = 11.5.sp) }
                    }
                }
            }
            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(status, color = colors.textMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
        }

        Spacer(Modifier.height(14.dp))
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
                        Brush.verticalGradient(
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
                    Text(card.answer, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp)
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
                        onClick = onToggleReveal,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accentGreen,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (showAnswer) "Hide answer" else "Show answer",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onKnown,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text("Knew this", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = onNext,
                        shape = RoundedCornerShape(999.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text("Next card", fontSize = 12.sp) }
                }
            }
        }

        if (flashcards.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            KSectionHeader(
                kicker = "Full deck",
                icon = Icons.Default.Layers,
                tone = colors.accentBlue,
                trailing = "${flashcards.size} cards"
            )
            Spacer(Modifier.height(6.dp))
            flashcards.forEachIndexed { i, c ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .border(
                            1.dp,
                            if (i == currentCardIndex) colors.accentGreen.copy(alpha = 0.7f) else colors.border,
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectCard(i) }
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            c.question,
                            color = colors.textPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (i in known) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Known this session",
                                tint = colors.accentGreen,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(c.answer, color = colors.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    kicker: String,
    icon: ImageVector,
    tone: Color,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalKarenColors.current
    Column {
        KSectionHeader(kicker = kicker, icon = icon, tone = tone, trailing = trailing)
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                .padding(14.dp),
            content = content
        )
    }
}

@Composable
private fun ProfileActionRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    trailing: String? = null,
    titleMaxLines: Int = 1,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KIconTile(icon, tint, 28.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(1.dp))
            Text(
                subtitle,
                color = colors.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing,
                color = tint,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )
        } else {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun ProfilePillButton(
    text: String,
    tone: Color,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = tone,
            contentColor = Color.White,
            disabledContainerColor = tone.copy(alpha = 0.35f),
            disabledContentColor = Color.White.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 7.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProfileOutlineButton(text: String, onClick: () -> Unit) {
    val colors = LocalKarenColors.current
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 12.sp, color = colors.textSecondary)
    }
}

@Composable
private fun ProfileEmpty(
    text: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = LocalKarenColors.current
    Text(text, color = colors.textMuted, fontSize = 12.sp, lineHeight = 16.sp)
    if (actionLabel != null && onAction != null) {
        Spacer(Modifier.height(8.dp))
        ProfileOutlineButton(actionLabel, onAction)
    }
}

@Composable
private fun SubjectTile(
    doc: File,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    val code = doc.extension.lowercase() in CODE_EXTS
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) colors.accentGreen.copy(alpha = 0.10f)
                else colors.surfaceHover.copy(alpha = 0.35f)
            )
            .border(
                1.dp,
                if (selected) colors.accentGreen.copy(alpha = 0.55f) else colors.border,
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KIconTile(
                icon = if (code) Icons.Default.Code else Icons.Default.Description,
                tint = if (selected) colors.accentGreen else colors.accentBlue,
                size = 26.dp
            )
            Spacer(Modifier.weight(1f))
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = colors.accentGreen,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            doc.name.removeSuffix(".${doc.extension}"),
            color = colors.textPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            docMeta(doc),
            color = colors.textMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun ChecklistSection(
    listKey: String,
    kicker: String,
    icon: ImageVector,
    tone: Color,
    defaults: List<String> = emptyList(),
    allowAdd: Boolean = false,
    addPlaceholder: String = "Add an item",
    trailing: String? = null,
    footer: @Composable ColumnScope.() -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val ctx = LocalContext.current
    val items = remember(listKey) {
        mutableStateListOf<ChecklistEntry>().apply {
            addAll(StudyChecklistStore.load(ctx, listKey, defaults))
        }
    }
    var input by remember { mutableStateOf("") }
    fun persist() {
        StudyChecklistStore.save(ctx, listKey, items)
    }
    val open = items.count { !it.done }

    ProfileCard(
        kicker = kicker,
        icon = icon,
        tone = tone,
        trailing = trailing ?: "$open open"
    ) {
        if (items.isEmpty()) {
            Text("No items yet.", color = colors.textMuted, fontSize = 12.sp)
        } else {
            items.forEachIndexed { index, entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable {
                            val current = items.getOrNull(index) ?: return@clickable
                            items[index] = current.copy(done = !current.done)
                            persist()
                        }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (entry.done) Icons.Default.CheckCircle
                        else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (entry.done) tone else colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        entry.text,
                        color = if (entry.done) colors.textMuted else colors.textPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textDecoration = if (entry.done) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                    if (allowAdd) {
                        IconButton(
                            onClick = {
                                items.removeAt(index)
                                persist()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = colors.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
        if (allowAdd) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(120) },
                    placeholder = { Text(addPlaceholder, color = colors.textMuted, fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp),
                    colors = karenFieldColors(colors),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            items.add(ChecklistEntry(System.currentTimeMillis(), text, false))
                            input = ""
                            persist()
                        }
                    },
                    enabled = input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tone,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(999.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) { Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            }
        }
        footer()
    }
}
