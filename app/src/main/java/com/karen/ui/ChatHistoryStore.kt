package com.karen.ui

import android.content.Context
import android.util.Base64
import java.io.File

/**
 * File-based chat history. Each conversation is one text file under
 * `filesDir/chats/`, plus a small tab-separated index. All text fields are
 * Base64 so message content can never break the line format. No cloud, no DB.
 */
data class ChatConversation(val id: String, val title: String, val updatedAt: Long)

object ChatHistoryStore {
    private const val DIR = "chats"
    private const val INDEX = "index.txt"
    private const val MAX_TITLE = 42

    private fun dir(ctx: Context): File = File(ctx.filesDir, DIR).apply { mkdirs() }
    private fun indexFile(ctx: Context): File = File(dir(ctx), INDEX)
    private fun chatFile(ctx: Context, id: String): File = File(dir(ctx), "chat_$id.txt")

    private fun enc(s: String): String =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun dec(s: String): String = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        ""
    }

    fun loadConversations(ctx: Context): List<ChatConversation> {
        val index = indexFile(ctx)
        if (index.exists()) {
            return index.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val id = parts[0]
                if (!chatFile(ctx, id).exists()) return@mapNotNull null
                ChatConversation(id, dec(parts[1]), parts[2].toLongOrNull() ?: 0L)
            }.sortedByDescending { it.updatedAt }
        }
        // Rebuild from files when the index is missing.
        return dir(ctx).listFiles()
            ?.filter { it.name.startsWith("chat_") && it.name.endsWith(".txt") }
            ?.mapNotNull { f ->
                val id = f.name.removePrefix("chat_").removeSuffix(".txt")
                val firstUser = loadMessages(ctx, id).filterIsInstance<ChatItem.User>().firstOrNull()
                if (firstUser == null) null else ChatConversation(id, titleOf(firstUser.text), f.lastModified())
            }
            ?.sortedByDescending { it.updatedAt } ?: emptyList()
    }

    fun loadMessages(ctx: Context, id: String): List<ChatItem> {
        val file = chatFile(ctx, id)
        if (!file.exists()) return emptyList()
        return file.readLines().mapIndexedNotNull { i, line ->
            val parts = line.split('\t')
            if (parts.isEmpty()) return@mapIndexedNotNull null
            when (parts[0]) {
                "U" -> if (parts.size < 2) null else {
                    val names = if (parts.size > 2) dec(parts[2]).split('\n').filter { it.isNotBlank() } else emptyList()
                    val body = dec(parts[1])
                    ChatItem.User(
                        id = "user_${id}_$i",
                        text = body,
                        attachments = names.map { Attachment(it, 0L, null) },
                        tokens = parts.getOrNull(3)?.toIntOrNull() ?: body.length / 4
                    )
                }
                "A" -> if (parts.size < 4) null else {
                    val body = dec(parts[3])
                    ChatItem.Assistant(
                        id = "asst_${id}_$i",
                        thought = dec(parts[1]).ifEmpty { null },
                        toolCall = dec(parts[2]).ifEmpty { null },
                        text = body,
                        tookMs = parts.getOrNull(4)?.toLongOrNull() ?: 0L,
                        tokens = parts.getOrNull(5)?.toIntOrNull() ?: body.length / 4,
                        interrupted = parts.getOrNull(6) == "1",
                        model = parts.getOrNull(7)?.let { dec(it) } ?: ""
                    )
                }
                else -> null
            }
        }
    }

    /** Persists a conversation; title is derived from its first user message. */
    fun saveConversation(ctx: Context, id: String, messages: List<ChatItem>) {
        if (messages.isEmpty()) return
        val firstUser = messages.filterIsInstance<ChatItem.User>().firstOrNull() ?: return
        val lines = messages.map { item ->
            when (item) {
                is ChatItem.User -> "U\t${enc(item.text)}\t${enc(item.attachments.joinToString("\n") { it.name })}\t${item.tokens}"
                is ChatItem.Assistant -> "A\t${enc(item.thought ?: "")}\t${enc(item.toolCall ?: "")}\t${enc(item.text)}\t${item.tookMs}\t${item.tokens}\t${if (item.interrupted) 1 else 0}\t${enc(item.model)}"
            }
        }
        chatFile(ctx, id).writeText(lines.joinToString("\n"))
        val updated = loadConversations(ctx)
            .filter { it.id != id }
            .toMutableList()
        updated.add(0, ChatConversation(id, titleOf(firstUser.text), System.currentTimeMillis()))
        indexFile(ctx).writeText(
            updated.sortedByDescending { it.updatedAt }
                .joinToString("\n") { "${it.id}\t${enc(it.title)}\t${it.updatedAt}" }
        )
    }

    fun deleteConversation(ctx: Context, id: String) {
        chatFile(ctx, id).delete()
        val updated = loadConversations(ctx).filter { it.id != id }
        indexFile(ctx).writeText(
            updated.joinToString("\n") { "${it.id}\t${enc(it.title)}\t${it.updatedAt}" }
        )
    }

    /** Short summary title from the first prompt: politeness filler stripped,
        key words kept, capped at [MAX_TITLE]. Falls back to "New chat". */
    fun summarizeTitle(text: String): String {
        var s = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return "New chat"
        var changed = true
        while (changed && s.isNotEmpty()) {
            changed = false
            for (filler in TITLE_FILLERS) {
                val m = filler.find(s)
                if (m != null && m.range.first == 0 && m.range.last + 1 < s.length) {
                    s = s.substring(m.range.last + 1).trim()
                    changed = true
                    break
                }
            }
        }
        if (s.isEmpty()) return "New chat"
        var title = s.split(' ').filter { it.isNotBlank() }.take(7).joinToString(" ")
        title = title.trimEnd('?', ' ', '.', '!')
        if (title.isEmpty()) return "New chat"
        return if (title.length <= MAX_TITLE) title else title.take(MAX_TITLE).trimEnd() + "…"
    }

    private val TITLE_FILLERS = listOf(
        Regex("^please\\s+", RegexOption.IGNORE_CASE),
        Regex("^(hi|hey|hello)[,.]?\\s+", RegexOption.IGNORE_CASE),
        Regex("^(can|could|would)\\s+you\\s+", RegexOption.IGNORE_CASE),
        Regex("^(i|we)\\s+(want|need|would like)\\s+(you\\s+)?to\\s+", RegexOption.IGNORE_CASE),
        Regex("^help\\s+me\\s+(to\\s+)?", RegexOption.IGNORE_CASE),
        Regex("^(give|show|tell)\\s+me\\s+", RegexOption.IGNORE_CASE)
    )

    private fun titleOf(text: String): String = summarizeTitle(text)
}
