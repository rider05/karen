package com.karen.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.widget.Toast
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Short line synthesized in the tapped voice so the tone can be compared. */
private const val VOICE_PREVIEW_TEXT = "Hi, this is how I sound."

/** Human-readable title for a Fish Audio reference id. */
fun fishVoiceDisplayName(id: String, savedName: String): String = when {
    id.isBlank() -> "Energetic Male"
    savedName.isNotBlank() -> savedName
    else -> CloudTts.KNOWN_VOICES.firstOrNull { it.id == id }?.title ?: "Custom voice"
}

/**
 * Fish Audio voice picker: known voices, the user's own models, public API
 * search, a custom reference ID, and a short synthesized sample per voice.
 * Search is debounced and works without a key; listing the user's own cloned
 * voices needs a saved Fish Audio key, and previews need a Fish Audio or
 * OpenRouter key.
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

    // Voice preview: one sample at a time, synthesized in the tapped voice.
    val scope = rememberCoroutineScope()
    val previewPlayer = remember { VoicePreviewPlayer() }
    var previewJob by remember { mutableStateOf<Job?>(null) }
    var previewLoadingId by remember { mutableStateOf<String?>(null) }
    var previewPlayingId by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf("") }
    val canPreview = CloudTts.route(context) != CloudTts.Route.NONE

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewPlayer.stop()
        previewLoadingId = null
        previewPlayingId = null
    }

    fun togglePreview(voice: CloudTts.FishVoice) {
        if (previewLoadingId == voice.id || previewPlayingId == voice.id) {
            stopPreview()
            return
        }
        if (!canPreview) {
            Toast.makeText(
                context,
                "Add a Fish Audio or OpenRouter key to preview voices",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        stopPreview()
        previewError = ""
        previewLoadingId = voice.id
        // Preview replaces any reply that is currently being spoken.
        TtsManager.stop()
        previewJob = scope.launch {
            try {
                val file = CloudTts.synthesize(context, VOICE_PREVIEW_TEXT, voice.id)
                if (!isActive) {
                    file.delete()
                    return@launch
                }
                previewLoadingId = null
                previewPlayingId = voice.id
                previewPlayer.play(file) {
                    previewPlayer.stop()
                    if (previewPlayingId == voice.id) previewPlayingId = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                previewLoadingId = null
                previewPlayingId = null
                previewError = e.message?.take(160) ?: "Couldn't preview this voice."
            }
        }
    }

    fun dismiss() {
        stopPreview()
        onDismiss()
    }

    DisposableEffect(Unit) {
        onDispose {
            previewJob?.cancel()
            previewPlayer.stop()
        }
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

    androidx.compose.ui.window.Dialog(onDismissRequest = { dismiss() }) {
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
                IconButton(onClick = { dismiss() }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                "Voices come from api.fish.audio. Tap play to hear a short sample; the picked reference ID is used for cloud speech.",
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
                    "The public library is searchable without a key. Save a Fish Audio or OpenRouter key to preview voices; a Fish key also lists your cloned voices.",
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
                            .clickable {
                                stopPreview()
                                onPick(voice.id, voice.title)
                            }
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
                        VoicePreviewButton(
                            colors = colors,
                            loading = previewLoadingId == voice.id,
                            playing = previewPlayingId == voice.id,
                            canPreview = canPreview,
                            onToggle = { togglePreview(voice) }
                        )
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
            if (previewError.isNotBlank()) {
                Text(
                    previewError,
                    color = colors.accentAmber,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
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
                Spacer(Modifier.width(4.dp))
                VoicePreviewButton(
                    colors = colors,
                    loading = previewLoadingId == customId.trim(),
                    playing = previewPlayingId == customId.trim(),
                    canPreview = customId.isNotBlank() && canPreview,
                    onToggle = {
                        if (customId.isNotBlank()) {
                            togglePreview(CloudTts.FishVoice(customId.trim(), "Custom voice"))
                        }
                    }
                )
                Spacer(Modifier.width(4.dp))
                TextButton(
                    enabled = customId.isNotBlank(),
                    onClick = {
                        stopPreview()
                        onPick(customId.trim(), "")
                    }
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
                TextButton(onClick = { dismiss() }) {
                    Text("Close", color = colors.textMuted)
                }
            }
        }
    }
}

/** Play/stop control for one voice; a spinner shows while the sample is synthesized. */
@Composable
private fun VoicePreviewButton(
    colors: KarenColors,
    loading: Boolean,
    playing: Boolean,
    canPreview: Boolean,
    onToggle: () -> Unit
) {
    IconButton(onClick = onToggle, modifier = Modifier.size(34.dp)) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = colors.accentBlue
            )
        } else {
            Icon(
                imageVector = if (playing) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Stop preview" else "Preview voice",
                tint = when {
                    playing -> colors.accentGreen
                    canPreview -> colors.accentBlue
                    else -> colors.textMuted
                },
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** Plays a single synthesized preview and deletes its temp file. */
private class VoicePreviewPlayer {
    private var player: MediaPlayer? = null
    private var file: File? = null

    fun play(file: File, onFinished: () -> Unit) {
        stop()
        this.file = file
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { onFinished() }
            mp.setOnErrorListener { _, _, _ ->
                onFinished()
                true
            }
            mp.prepare()
            mp.start()
        } catch (_: Exception) {
            onFinished()
        }
    }

    fun stop() {
        val mp = player
        player = null
        try {
            mp?.setOnCompletionListener(null)
        } catch (_: Exception) {
        }
        try {
            mp?.setOnErrorListener(null)
        } catch (_: Exception) {
        }
        try {
            mp?.stop()
        } catch (_: Exception) {
        }
        try {
            mp?.release()
        } catch (_: Exception) {
        }
        file?.delete()
        file = null
    }
}
