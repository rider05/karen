package com.karen.ui

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Human-readable title for a Fish Audio reference id. */
fun fishVoiceDisplayName(id: String, savedName: String): String = when {
    id.isBlank() -> "Energetic Male"
    savedName.isNotBlank() -> savedName
    else -> CloudTts.KNOWN_VOICES.firstOrNull { it.id == id }?.title ?: "Custom voice"
}

/**
 * Fish Audio voice picker: known voices, the user's own models, public API
 * search, and a custom reference ID. Search is debounced and works without a
 * key; listing the user's own cloned voices needs a saved Fish Audio key.
 */
@Composable
fun FishVoicePickerDialog(
    colors: KarenColors,
    hasKey: Boolean,
    selectedId: String,
    selectedName: String,
    onDismiss: () -> Unit,
    onPick: (id: String, title: String) -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var mine by remember { mutableStateOf<List<CloudTts.FishVoice>>(emptyList()) }
    var results by remember { mutableStateOf<List<CloudTts.FishVoice>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var customId by remember {
        mutableStateOf(if (selectedName.isBlank()) selectedId else "")
    }

    LaunchedEffect(hasKey, query) {
        val searching = query.isNotBlank()
        if (!searching && !hasKey) {
            mine = emptyList()
            loading = false
            error = ""
            return@LaunchedEffect
        }
        if (searching) delay(350)
        loading = true
        error = ""
        try {
            if (searching) {
                results = CloudTts.searchVoices(context, query)
            } else {
                mine = CloudTts.myVoices(context)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message?.take(160) ?: "Couldn't reach Fish Audio."
        } finally {
            loading = false
        }
    }

    val effectiveSelectedId = selectedId.ifBlank { CloudTts.DEFAULT_FISH_VOICE }
    val shown = if (query.isBlank()) {
        (CloudTts.KNOWN_VOICES + mine).distinctBy { it.id }
    } else {
        results.distinctBy { it.id }
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Choose voice", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                "Voices come from api.fish.audio. The picked reference ID is used for cloud speech.",
                color = colors.textMuted,
                fontSize = 11.5.sp
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(60) },
                label = {
                    Text("Search Fish Audio voices", color = colors.textMuted)
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.accentBlue
                        )
                    }
                },
                singleLine = true,
                colors = karenFieldColors(colors),
                modifier = Modifier.fillMaxWidth()
            )
            if (!hasKey) {
                Text(
                    "The public library is searchable without a key. Save a Fish Audio key to list your own cloned voices.",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }
            if (error.isNotBlank()) {
                Text(error, color = colors.accentAmber, fontSize = 11.5.sp)
            }
            Text(
                when {
                    query.isBlank() -> "Known voices"
                    loading -> "Searching…"
                    else -> "${shown.size} result${if (shown.size == 1) "" else "s"}"
                },
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp, max = 200.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(shown, key = { it.id }) { voice ->
                    val selected = voice.id == effectiveSelectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) colors.accentGreen.copy(alpha = 0.12f)
                                else Color.Transparent
                            )
                            .clickable { onPick(voice.id, voice.title) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                voice.title,
                                color = if (selected) colors.accentGreen else colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    if (voice.author.isNotBlank()) append(voice.author).append(" · ")
                                    append(voice.id.take(10)).append('…')
                                },
                                color = colors.textMuted,
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = colors.accentGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                if (shown.isEmpty() && !loading) {
                    item {
                        Text(
                            if (query.isBlank()) "No saved voices yet — the known voices above still work."
                            else "No voices found. Try another spelling.",
                            color = colors.textMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border.copy(alpha = 0.6f))
            )
            Text(
                "Custom reference ID",
                color = colors.textMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = customId,
                    onValueChange = { customId = it.trim().take(64) },
                    placeholder = { Text("Paste a voice ID", color = colors.textMuted) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = colors.textPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    colors = karenFieldColors(colors),
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = customId.isNotBlank(),
                    onClick = { onPick(customId.trim(), "") }
                ) {
                    Text(
                        "Use",
                        color = if (customId.isNotBlank()) colors.accentGreen else colors.textMuted,
                        fontSize = 12.sp
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = colors.textMuted)
                }
            }
        }
    }
}
