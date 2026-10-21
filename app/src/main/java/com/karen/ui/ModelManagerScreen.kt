@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.karen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.Intent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import com.karen.rememberDeviceTelemetry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

data class GgufModel(
    val name: String,
    val size: String,
    val ramRequired: String,
    val throughput: String,
    val quant: String
)

/** Cloud provider catalogue lives in CloudChatApi.kt (shared with the chat). */

@Composable
private fun ModelSourceRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceHover.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = colors.textMuted, fontSize = 11.5.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
    }
}

/** Model Manager tabs: on-device weights, cloud providers, voice engines. */
private enum class ModelTab(val label: String) {
    Device("On device"),
    Cloud("Cloud"),
    Voice("Voice")
}

@Composable
private fun MetaChip(text: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            color = tint,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

private data class ModelChipSpec(val icon: ImageVector, val label: String)

private fun modelProviderOf(name: String): String = when {
    name.contains("Qwen", true) -> "ALIBABA"
    name.contains("Gemma", true) -> "GOOGLE"
    name.contains("TinyLlama", true) -> "TINYLLAMA"
    name.contains("Llama", true) -> "META"
    name.contains("Phi", true) -> "MICROSOFT"
    name.contains("Granite", true) -> "IBM"
    name.contains("LFM", true) -> "LIQUID AI"
    name.contains("SmolLM", true) -> "HUGGING FACE"
    name.contains("Nemotron", true) || name.contains("NVIDIA", true) -> "NVIDIA"
    name.contains("DeepSeek", true) -> "DEEPSEEK"
    name.contains("Saluki", true) -> "UNDERDOG"
    name.contains("Nomic", true) -> "NOMIC"
    name.contains("BGE", true) -> "BAAI"
    name.contains("MiniLM", true) -> "SENTENCE TRANSFORMERS"
    else -> "LOCAL"
}

private fun modelMonogramOf(name: String): String = when {
    name.contains("Qwen", true) -> "Q"
    name.contains("Gemma", true) -> "G"
    name.contains("TinyLlama", true) -> "T"
    name.contains("Llama", true) -> "L"
    name.contains("Phi", true) -> "P"
    name.contains("Granite", true) -> "IBM"
    name.contains("LFM", true) -> "LFM"
    name.contains("SmolLM", true) -> "S"
    name.contains("Nemotron", true) || name.contains("NVIDIA", true) -> "N"
    name.contains("DeepSeek", true) -> "D"
    name.contains("Saluki", true) -> "S"
    name.contains("Nomic", true) -> "N"
    name.contains("BGE", true) -> "BGE"
    name.contains("MiniLM", true) -> "M"
    else -> "AI"
}

private fun modelTintOf(name: String, colors: KarenColors): Color = when {
    name.contains("Qwen", true) -> Color(0xFFA78BFA)
    name.contains("Gemma", true) -> Color(0xFF38BDF8)
    name.contains("TinyLlama", true) -> Color(0xFFFBBF24)
    name.contains("Llama", true) -> Color(0xFF818CF8)
    name.contains("Phi", true) -> Color(0xFF2DD4BF)
    name.contains("Granite", true) -> Color(0xFF94A3B8)
    name.contains("LFM", true) -> Color(0xFF22D3EE)
    name.contains("SmolLM", true) -> Color(0xFFFBBF24)
    name.contains("Nemotron", true) || name.contains("NVIDIA", true) -> Color(0xFF76B900)
    name.contains("DeepSeek", true) -> Color(0xFF60A5FA)
    name.contains("Saluki", true) -> Color(0xFFF59E0B)
    name.contains("Nomic", true) -> Color(0xFFF472B6)
    name.contains("BGE", true) -> Color(0xFF60A5FA)
    name.contains("MiniLM", true) -> Color(0xFFF97316)
    else -> colors.accentGreen
}

private fun paramsChipLabel(paramsB: Float): String {
    if (paramsB <= 0f) return "?"
    return when {
        paramsB >= 1f && paramsB % 1f == 0f -> "${paramsB.toInt()} B"
        paramsB >= 1f -> "%.1f B".format(paramsB)
        paramsB < 0.2f -> "${(paramsB * 1000).toInt()} M"
        else -> "%.2f B".format(paramsB).trimEnd('0').trimEnd('.') + " B"
    }
}

private fun speedChipLabel(paramsB: Float): String = when {
    paramsB <= 0f -> "Local"
    paramsB <= 1.5f -> "Fast"
    paramsB <= 4f -> "Balanced"
    else -> "Deep"
}

@Composable
private fun ModelIconTile(name: String) {
    val colors = LocalKarenColors.current
    val tint = modelTintOf(name, colors)
    val monogram = modelMonogramOf(name)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.24f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            monogram,
            color = tint,
            fontSize = when {
                monogram.length >= 3 -> 9.sp
                monogram.length == 2 -> 12.sp
                else -> 17.sp
            },
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = (-0.4).sp
        )
    }
}

@Composable
private fun ModelModalityChip(entry: CatalogModel?) {
    val colors = LocalKarenColors.current
    val spec = when {
        entry?.projector == true -> Triple("PROJECTOR", Icons.Default.Visibility, colors.accentAmber)
        entry?.embedding == true -> Triple("EMBED", Icons.Default.Hub, colors.accentBlue)
        entry?.vision == true -> Triple("VISION", Icons.Default.Visibility, colors.accentBlue)
        else -> Triple("TEXT", Icons.Default.TextFields, colors.accentGreen)
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, spec.third.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(spec.second, contentDescription = null, tint = spec.third, modifier = Modifier.size(9.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            spec.first,
            color = spec.third,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun ModelInfoChip(spec: ModelChipSpec) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(colors.surfaceHover.copy(alpha = 0.35f))
            .border(1.dp, colors.border, RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(spec.icon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            spec.label,
            color = colors.textSecondary,
            fontSize = 9.5.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

@Composable
private fun InstalledPill() {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accentGreen.copy(alpha = 0.12f))
            .border(1.dp, colors.accentGreen.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = colors.accentGreen,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text("Installed", color = colors.accentGreen, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DownloadPill(onClick: () -> Unit) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accentGreen)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text("Download", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CancelPill(onClick: () -> Unit) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .heightIn(min = 34.dp)
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, colors.accentGreen.copy(alpha = 0.6f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Cancel", color = colors.accentGreen, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatDivider() {
    val colors = LocalKarenColors.current
    Box(
        Modifier
            .width(1.dp)
            .height(32.dp)
            .background(colors.border.copy(alpha = 0.7f))
    )
}

@Composable
private fun ManagerStat(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = tint, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(label, color = colors.textMuted, fontSize = 10.5.sp, maxLines = 1)
    }
}

@Composable
private fun ManagerHero(
    localCount: Int,
    cloudCount: Int,
    voiceCount: Int,
    deviceModel: String,
    threads: Int,
    online: Boolean,
    status: String,
    statusTint: Color,
    onDevice: () -> Unit,
    onCloud: () -> Unit,
    onVoice: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(colors.surface, colors.cardBackground)
                )
            )
            .border(1.dp, colors.border.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.accentGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Memory,
                    contentDescription = null,
                    tint = colors.accentGreen,
                    modifier = Modifier.size(19.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Your models",
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Weights, providers and voice engines",
                    color = colors.textMuted,
                    fontSize = 11.sp
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(statusTint.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    status,
                    color = statusTint,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.6.sp
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ManagerStat("On device", "$localCount", colors.accentGreen, Modifier.weight(1f), onDevice)
            StatDivider()
            ManagerStat("Cloud", "$cloudCount", colors.accentBlue, Modifier.weight(1f), onCloud)
            StatDivider()
            ManagerStat("Voice", "$voiceCount", colors.accentAmber, Modifier.weight(1f), onVoice)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "$deviceModel · $threads threads · ${if (online) "Network up" else "Offline"}",
            color = colors.textMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun QuickActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(tint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = colors.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ManagerTabBar(
    selected: ModelTab,
    localCount: Int,
    cloudCount: Int,
    voiceCount: Int,
    onSelect: (ModelTab) -> Unit
) {
    val colors = LocalKarenColors.current
    val counts = mapOf(
        ModelTab.Device to localCount,
        ModelTab.Cloud to cloudCount,
        ModelTab.Voice to voiceCount
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(4.dp)
    ) {
        ModelTab.entries.forEach { t ->
            val active = t == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (active) colors.accentGreen else Color.Transparent)
                    .clickable { onSelect(t) }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    t.label,
                    color = if (active) Color.White else colors.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "${counts[t] ?: 0}",
                    color = if (active) Color.White.copy(alpha = 0.75f) else colors.textMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(13.dp))
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(placeholder, color = colors.textMuted, fontSize = 12.5.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = colors.textPrimary, fontSize = 12.5.sp),
                cursorBrush = SolidColor(colors.accentGreen),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear search",
                    tint = colors.textMuted,
                    modifier = Modifier.size(15.dp)
                )
            }
        } else {
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(),
            color = colors.textMuted,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        if (!trailing.isNullOrBlank()) {
            Text(
                trailing,
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceHover),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(title, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(
            body,
            color = colors.textMuted,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAction, shape = RoundedCornerShape(10.dp)) {
                Text(actionLabel, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.accentRed.copy(alpha = 0.1f))
            .border(1.dp, colors.accentRed.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = colors.accentRed,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(message, color = colors.accentRed, fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun TransferCard(
    kicker: String,
    title: String,
    progress: Float,
    status: String,
    onCancel: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.accentGreen.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(13.dp)
    ) {
        Text(
            kicker.uppercase(),
            color = colors.accentGreen,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            title,
            color = colors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
            color = colors.accentGreen,
            trackColor = colors.surfaceHover
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                status,
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            TextButton(onClick = onCancel) {
                Text("Cancel", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InstalledWeightCard(
    model: GgufModel,
    expanded: Boolean,
    contextLabel: String,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalKarenColors.current
    val catalog = catalogEntryFor(model.name)
    var menuOpen by remember { mutableStateOf(false) }
    val isProjector = catalog?.projector == true
    val isEmbedding = catalog?.embedding == true
    val paramsB = catalog?.paramsB?.takeIf { it > 0f } ?: paramsOf(model.name)
    val chips = when {
        isProjector -> listOf(
            ModelChipSpec(Icons.Default.Storage, model.size),
            ModelChipSpec(Icons.Default.Memory, model.quant),
            ModelChipSpec(Icons.Default.Visibility, "Projector")
        )
        isEmbedding -> listOf(
            ModelChipSpec(Icons.Default.Bolt, paramsChipLabel(paramsB)),
            ModelChipSpec(Icons.Default.Memory, model.quant),
            ModelChipSpec(Icons.Default.Hub, "Embedding")
        )
        else -> listOf(
            ModelChipSpec(Icons.Default.Bolt, paramsChipLabel(paramsB)),
            ModelChipSpec(Icons.AutoMirrored.Filled.MenuBook, "$contextLabel ctx"),
            ModelChipSpec(Icons.Default.Speed, speedChipLabel(paramsB))
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(
                1.dp,
                if (expanded) colors.accentGreen.copy(alpha = 0.8f) else colors.border,
                RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onToggle)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModelIconTile(model.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modelProviderOf(model.name),
                        color = colors.textMuted,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    ModelModalityChip(catalog)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    model.name,
                    color = colors.textPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Actions for ${model.name}",
                        tint = colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
                KDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (expanded) "Hide details" else "Show details",
                                color = colors.textPrimary,
                                fontSize = 13.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = colors.textMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onToggle()
                        }
                    )
                    KMenuDivider()
                    DropdownMenuItem(
                        text = { Text("Delete weight", color = colors.accentRed, fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = colors.accentRed,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                chips.forEach { ModelInfoChip(it) }
            }
            Spacer(Modifier.width(8.dp))
            InstalledPill()
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border.copy(alpha = 0.7f))
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    isProjector ->
                        "Vision projector companion · ${model.quant} · loads with its base model · not a chat model"
                    isEmbedding ->
                        "Embedding weight for vault/RAG search · ${model.quant} · not a chat model"
                    else ->
                        "File-backed GGUF runtime · ${model.quant} · needs about ${model.ramRequired} RAM · ctx $contextLabel · Vulkan / ARM NEON"
                },
                color = colors.textSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun CatalogCard(
    entry: CatalogModel,
    installed: Boolean,
    downloading: Boolean,
    contextLabel: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = LocalKarenColors.current
    val isProjector = entry.projector
    val isEmbedding = entry.embedding
    val paramsB = entry.paramsB.takeIf { it > 0f } ?: paramsOf(entry.name)
    val chips = when {
        isProjector -> listOf(
            ModelChipSpec(Icons.Default.Storage, entry.sizeLabel),
            ModelChipSpec(Icons.Default.Memory, entry.quant),
            ModelChipSpec(Icons.Default.Visibility, "Projector")
        )
        isEmbedding -> listOf(
            ModelChipSpec(Icons.Default.Bolt, paramsChipLabel(paramsB)),
            ModelChipSpec(Icons.Default.Memory, entry.quant),
            ModelChipSpec(Icons.Default.Hub, "Embedding")
        )
        else -> listOf(
            ModelChipSpec(Icons.Default.Bolt, paramsChipLabel(paramsB)),
            ModelChipSpec(Icons.AutoMirrored.Filled.MenuBook, "$contextLabel ctx"),
            ModelChipSpec(Icons.Default.Speed, speedChipLabel(paramsB))
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ModelIconTile(entry.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        modelProviderOf(entry.name),
                        color = colors.textMuted,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(6.dp))
                    ModelModalityChip(entry)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    entry.name,
                    color = colors.textPrimary,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                chips.forEach { ModelInfoChip(it) }
            }
            Spacer(Modifier.width(8.dp))
            when {
                installed -> InstalledPill()
                downloading -> CancelPill(onClick = onCancel)
                else -> DownloadPill(onClick = onDownload)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            when {
                entry.projector -> "Companion projector · not a chat model"
                entry.embedding -> "Embedding weight · not a chat model"
                entry.vision -> "${entry.quant} · needs ${entry.ram} RAM · pairs with a vision projector"
                else -> "${entry.quant} · needs ${entry.ram} RAM"
            },
            color = colors.textMuted,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        if (downloading) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { ModelDownloader.progress },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accentGreen,
                trackColor = colors.surfaceHover
            )
            Spacer(Modifier.height(4.dp))
            Text(
                ModelDownloader.status,
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun CloudProviderCard(
    title: String,
    subtitle: String,
    connected: Boolean,
    onClick: () -> Unit
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (connected) colors.accentGreen.copy(alpha = 0.12f)
                    else colors.accentBlue.copy(alpha = 0.12f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (connected) Icons.Default.CheckCircle else Icons.Default.Cloud,
                contentDescription = null,
                tint = if (connected) colors.accentGreen else colors.accentBlue,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                color = if (connected) colors.accentGreen else colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
        Text(
            if (connected) "Configure" else "Connect",
            color = if (connected) colors.accentGreen else colors.accentBlue,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun KeyBackupCard(
    keyCount: Int,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = colors.accentAmber,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "Key backup",
                color = colors.textPrimary,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "$keyCount stored",
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            "Password-encrypted .kbk file in local storage — restores every provider key after a reinstall.",
            color = colors.textMuted,
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport, shape = RoundedCornerShape(10.dp)) {
                Text("Export keys", fontSize = 12.sp)
            }
            OutlinedButton(onClick = onImport, shape = RoundedCornerShape(10.dp)) {
                Text("Import keys", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun VoiceWeightCard(
    model: ModelDownloader.VoiceModel,
    installed: Boolean,
    downloading: Boolean,
    engineStatus: String,
    progress: Float,
    progressLabel: String,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accentAmber.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.RecordVoiceOver,
                    contentDescription = null,
                    tint = colors.accentAmber,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    model.name,
                    color = colors.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    MetaChip(model.sizeLabel, colors.accentBlue)
                    MetaChip(model.engine, colors.textMuted)
                }
            }
            when {
                installed -> IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete ${model.name}",
                        tint = colors.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                downloading -> OutlinedButton(onClick = onCancel) {
                    Text("Cancel", fontSize = 12.sp)
                }
                else -> TextButton(onClick = onDownload) {
                    Text("Download", color = colors.accentGreen, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(model.note, color = colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                engineStatus,
                color = if (installed) colors.accentGreen else colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (installed) {
                Text("Installed", color = colors.accentGreen, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (downloading) {
            Spacer(Modifier.height(7.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = colors.accentGreen,
                trackColor = colors.surfaceHover
            )
            Spacer(Modifier.height(4.dp))
            Text(
                progressLabel,
                color = colors.textMuted,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun CatalogSourceCard(
    value: String,
    onValueChange: (String) -> Unit,
    busy: Boolean,
    message: String?,
    remoteCount: Int,
    onRefresh: () -> Unit
) {
    val colors = LocalKarenColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Tune,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Catalog source",
                    color = colors.textPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Bundled${if (remoteCount > 0) " + $remoteCount remote" else ""}",
                    color = colors.textMuted,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                if (expanded) "Hide" else "Change",
                color = colors.accentGreen,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.textMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(9.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text("Remote catalog URL (blank = bundled)", color = colors.textMuted) },
                placeholder = { Text("https://…/models.json", color = colors.textMuted) },
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.textPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                colors = karenFieldColors(colors),
                modifier = Modifier.fillMaxWidth()
            )
            if (message != null) {
                Spacer(Modifier.height(4.dp))
                Text(message, color = colors.textMuted, fontSize = 11.sp)
            }
            TextButton(
                onClick = onRefresh,
                enabled = !busy,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    if (busy) "Refreshing…" else "Save & refresh",
                    color = if (busy) colors.textMuted else colors.accentGreen,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun ModelManagerScreen(
    onOpenDrawer: () -> Unit = {},
    onNavigateToHome: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val device = rememberDeviceTelemetry()

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    /** Real size/quant/RAM info for an installed weight (no fake values). */
    fun describeInstalled(name: String): GgufModel {
        val file = ModelDownloader.weightFileFor(ctx, name)
        val bytes = file?.length() ?: 0L
        val sizeLabel = when {
            bytes >= 1073741824L -> "%.2f GB".format(bytes / 1073741824.0)
            bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
            bytes > 0 -> "${bytes / 1024} KB"
            else -> "file missing"
        }
        val ramLabel = if (bytes > 0) "≈%.1f GB".format(bytes * 1.25 / 1073741824.0) else "-"
        val quant = file?.name?.let { ModelDownloader.quantOf(it) } ?: ModelDownloader.quantOf(name)
        return GgufModel(name, sizeLabel, ramLabel, "-", quant)
    }

    val models = remember {
        mutableStateListOf<GgufModel>().apply {
            // Heal prefs: cloud names must never live in installed weights.
            val stored = UserPrefs.models(ctx)
            val cleaned = stored.filter { !isCloudProviderName(it) }
            if (cleaned.size != stored.size) UserPrefs.saveModels(ctx, cleaned)
            cleaned.forEach { n -> add(describeInstalled(n)) }
        }
    }

    var activeModel by remember { mutableStateOf<GgufModel?>(null) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showApiDialog by remember { mutableStateOf(false) }
    var apiError by remember { mutableStateOf<String?>(null) }
    // Restored from persisted keys so connections survive screen revisits.
    val connectedApis = remember {
        mutableStateListOf<String>().apply {
            allCloudProviders(ctx)
                .filter { UserPrefs.apiKey(ctx, it.id).isNotBlank() }
                .forEach { add(it.name) }
        }
    }
    /** Built-ins plus user-added custom API providers (recomputed each pass). */
    fun allProviders(): List<CloudProvider> = allCloudProviders(ctx)

    /** Re-reads installed weights from prefs (after downloads/imports). */
    fun reloadModels() {
        models.clear()
        UserPrefs.models(ctx).filter { !isCloudProviderName(it) }
            .forEach { n -> models.add(describeInstalled(n)) }
        if (activeModel != null && models.none { it.name == activeModel?.name }) activeModel = null
    }

    /** Installed voice weights (whistle.cact, sherpa-onnx files). */
    val voiceModels = remember {
        mutableStateListOf<String>().apply { addAll(UserPrefs.voiceModels(ctx)) }
    }

    fun reloadVoices() {
        voiceModels.clear()
        voiceModels.addAll(UserPrefs.voiceModels(ctx))
    }

    fun deleteVoice(name: String, fileName: String) {
        try {
            ModelDownloader.voiceFile(ctx, fileName).delete()
        } catch (_: Exception) {
        }
        // Drop any live ORT session for the deleted file.
        try {
            OrtEngine.closeVad()
        } catch (_: Exception) {
        }
        UserPrefs.saveVoiceModels(ctx, UserPrefs.voiceModels(ctx).filter { it != name })
        reloadVoices()
    }

    // Console state: active tab, search, catalog source, system voice probe.
    var tab by remember { mutableStateOf(ModelTab.Device) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(tab) { listState.scrollToItem(0) }
    var catalogUrlText by remember { mutableStateOf(UserPrefs.catalogUrl(ctx)) }
    var catalogBusy by remember { mutableStateOf(false) }
    var catalogMsg by remember { mutableStateOf<String?>(null) }
    val sttOk = remember { SpeechRecognizer.isRecognitionAvailable(ctx) }
    var ttsStatus by remember { mutableStateOf("Checking…") }
    LaunchedEffect(Unit) {
        try {
            var tts: TextToSpeech? = null
            tts = TextToSpeech(ctx) { status ->
                ttsStatus = if (status == TextToSpeech.SUCCESS) {
                    val n = try { tts?.voices?.size ?: 0 } catch (_: Exception) { 0 }
                    if (n > 0) "$n voices" else "Engine ready"
                } else "Unavailable"
                try { tts?.shutdown() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            ttsStatus = "Unavailable"
        }
    }

    /** Manual refresh: weights, connections, and one downloader poll. */
    fun refreshAll() {
        reloadModels()
        connectedApis.clear()
        allCloudProviders(ctx)
            .filter { UserPrefs.apiKey(ctx, it.id).isNotBlank() }
            .forEach { connectedApis.add(it.name) }
        when (val ev = ModelDownloader.refresh(ctx)) {
            is DownloadEvent.Completed -> {
                reloadModels()
                android.widget.Toast.makeText(ctx, "${ev.name} installed", android.widget.Toast.LENGTH_SHORT).show()
            }
            is DownloadEvent.Failed -> {
                android.widget.Toast.makeText(ctx, "Download failed: ${ev.reason}", android.widget.Toast.LENGTH_LONG).show()
            }
            else -> {
                val downloading = ModelDownloader.activeName
                android.widget.Toast.makeText(
                    ctx,
                    if (downloading != null) "Refreshing… $downloading ${ModelDownloader.status}" else "Lists refreshed",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    // API-key entry flow: step 0 = provider list, step 1 = key entry.
    var apiStep by remember { mutableStateOf(0) }
    var selectedProvider by remember { mutableStateOf<CloudProvider?>(null) }
    var apiKeyInput by remember { mutableStateOf("") }
    var apiModelInput by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    var modelCustom by remember { mutableStateOf(false) }
    var liveModels by remember { mutableStateOf<List<LiveModel>?>(null) }
    var liveLoading by remember { mutableStateOf(false) }
    var liveError by remember { mutableStateOf(false) }
    // Custom API form (dialog step 2): name, base URL, model, key.
    var customName by remember { mutableStateOf("") }
    var customBase by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    // Secured key backup: password dialogs + file pickers.
    var showExportPw by remember { mutableStateOf(false) }
    var exportPw by remember { mutableStateOf("") }
    var showImportPw by remember { mutableStateOf(false) }
    var importPw by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var keyVisible by remember { mutableStateOf(false) }
    // Download catalog collapse: 3 cards by default.
    var catalogExpanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<GgufModel?>(null) }
    // URL / Hugging Face dialog state (reset every time the dialog opens).
    var urlInput by remember { mutableStateOf("") }
    var hfBusy by remember { mutableStateOf(false) }
    var hfRepo by remember { mutableStateOf<String?>(null) }
    var hfFiles by remember { mutableStateOf<List<ModelDownloader.HfFile>?>(null) }
    var hfError by remember { mutableStateOf<String?>(null) }

    fun resetUrlDialog() {
        urlInput = ""
        hfBusy = false
        hfRepo = null
        hfFiles = null
        hfError = null
    }

    // System back: dismiss dialogs first, otherwise route to Home.
    KarenHomeBackHandler(onNavigateToHome = onNavigateToHome) {
        when {
            showUrlDialog -> { showUrlDialog = false; true }
            showApiDialog -> { showApiDialog = false; apiStep = 0; true }
            showExportPw -> { showExportPw = false; true }
            showImportPw -> { showImportPw = false; true }
            pendingDelete != null -> { pendingDelete = null; true }
            tab != ModelTab.Device -> { tab = ModelTab.Device; query = ""; true }
            else -> false
        }
    }

    /** Removes a model: deletes its weight file(s) and unregisters it. */
    fun deleteModel(m: GgufModel) {
        try {
            ModelDownloader.modelsDir(ctx).listFiles()?.forEach { f ->
                if (f.name == m.name || f.nameWithoutExtension == m.name) f.delete()
            }
        } catch (_: Exception) {
        }
        UserPrefs.saveModels(ctx, UserPrefs.models(ctx).filter { it != m.name })
        if (activeModel?.name == m.name) activeModel = null
        reloadModels()
    }

    // Poll the downloader so progress/completion lands in this screen.
    LaunchedEffect(Unit) {
        while (true) {
            when (val ev = ModelDownloader.refresh(ctx)) {
                is DownloadEvent.Completed -> {
                    reloadModels()
                    android.widget.Toast.makeText(ctx, "${ev.name} installed", android.widget.Toast.LENGTH_SHORT).show()
                }
                is DownloadEvent.Failed -> {
                    android.widget.Toast.makeText(ctx, "Download failed: ${ev.reason}", android.widget.Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
            when (val vev = ModelDownloader.refreshVoice(ctx)) {
                is DownloadEvent.Completed -> {
                    reloadVoices()
                    android.widget.Toast.makeText(ctx, "${vev.name} installed", android.widget.Toast.LENGTH_SHORT).show()
                }
                is DownloadEvent.Failed -> {
                    android.widget.Toast.makeText(ctx, "Voice download failed: ${vev.reason}", android.widget.Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    // Real local import: copy + validate on IO with progress on the card below.
    var importingName by remember { mutableStateOf<String?>(null) }
    var importProgress by remember { mutableStateOf(0f) }
    var importStatus by remember { mutableStateOf("") }
    var importCancelled by remember { mutableStateOf(false) }
    val managerScope = rememberCoroutineScope()

    val exportKeysLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pw = exportPw
        exportPw = ""
        managerScope.launch {
            val msg = withContext(Dispatchers.IO) { ApiKeyVault.exportKeys(ctx, uri, pw) }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }

    val importKeysLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImportUri = uri
        importPw = ""
        showImportPw = true
    }

    /** Pulls the provider's live model list (typed or stored key). */
    fun fetchLiveModels() {
        val p = selectedProvider ?: return
        val key = apiKeyInput.trim()
        if (key.isBlank() && p.id != "openrouter") {
            liveModels = null
            liveError = false
            liveLoading = false
            return
        }
        if (liveLoading) return
        liveLoading = true
        liveError = false
        managerScope.launch {
            try {
                val ids = p.listModelsLive(key)
                liveModels = ids
                liveModelCache[p.id] = ids
                liveError = false
            } catch (_: Exception) {
                liveModels = null
                liveError = true
            } finally {
                liveLoading = false
            }
        }
    }

    /** Opens the key dialog: step 0 = provider list, otherwise the provider form. */
    fun openApiFlow(provider: CloudProvider?) {
        apiError = null
        if (provider == null) {
            apiStep = 0
            selectedProvider = null
            showApiDialog = true
            return
        }
        selectedProvider = provider
        apiKeyInput = UserPrefs.apiKey(ctx, provider.id)
        apiModelInput = UserPrefs.apiModel(ctx, provider.id).ifBlank { provider.defaultModel }
        modelCustom = false
        modelMenu = false
        liveModels = liveModelCache[provider.id]
        liveError = false
        keyVisible = false
        apiStep = 1
        fetchLiveModels()
        showApiDialog = true
    }

    /** Opens a system settings page, falling back to a toast. */
    fun openSystemSettings(intent: Intent, fallback: String) {
        try {
            ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, fallback, Toast.LENGTH_SHORT).show()
        }
    }

    fun fileNameOf(uri: Uri): String =
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "imported.gguf"

    fun fileSizeOf(uri: Uri): Long =
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.SIZE)
            if (i >= 0 && c.moveToFirst()) c.getLong(i) else -1L
        } ?: -1L

    /** Copies the picked file into app storage. GGUF is header-checked; ONNX /
        PyTorch are stored as-is (listed, but on-device runs GGUF only). Null = ok. */
    fun copyImport(uri: Uri, dest: File, total: Long): String? {
        val runnable = dest.extension.lowercase() == "gguf"
        try {
            if (runnable) {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    val magic = ByteArray(4)
                    var read = 0
                    while (read < 4) {
                        val n = input.read(magic, read, 4 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read < 4 || String(magic, Charsets.US_ASCII) != "GGUF") {
                        return "not a GGUF file (bad header)"
                    }
                } ?: return "cannot open file"
            }
            var copied = 0L
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (!importCancelled) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        if (total > 0) importProgress = (copied.toDouble() / total).toFloat().coerceIn(0f, 1f)
                        importStatus = "Copying %.1f MB".format(copied / 1048576.0)
                    }
                }
            } ?: return "cannot open file"
            if (importCancelled) return "CANCELLED"
            if (dest.length() <= 0) return "empty file"
            return null
        } catch (e: Exception) {
            return e.message ?: "copy failed"
        }
    }

    val importModelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        managerScope.launch {
            val name = fileNameOf(uri)
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in setOf("gguf", "onnx", "pth", "pt")) {
                Toast.makeText(ctx, "Only .gguf, .onnx, .pth files can be imported", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (name in UserPrefs.models(ctx)) {
                Toast.makeText(ctx, "$name is already imported", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val dest = File(ModelDownloader.modelsDir(ctx), name)
            if (dest.exists()) {
                Toast.makeText(ctx, "$name is already imported", Toast.LENGTH_SHORT).show()
                return@launch
            }
            importingName = name
            importProgress = 0f
            importStatus = "Starting…"
            importCancelled = false
            tab = ModelTab.Device
            query = ""
            val err = withContext(Dispatchers.IO) { copyImport(uri, dest, fileSizeOf(uri)) }
            importingName = null
            when {
                err == null -> {
                    reloadModels()
                    val note = if (ModelDownloader.formatOf(name) == "GGUF") "select it in the model picker"
                    else "listed (${ModelDownloader.formatOf(name)} — on-device runs GGUF only)"
                    Toast.makeText(ctx, "Imported $name — $note", Toast.LENGTH_LONG).show()
                }
                err == "CANCELLED" -> {
                    dest.delete()
                    Toast.makeText(ctx, "Import cancelled", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    dest.delete()
                    Toast.makeText(ctx, "Import failed: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Starts a download from a direct file URL (dialog "Find files" path). */
    fun startDirectDownload(clean: String) {
        val fileName = clean.substringAfterLast('/').substringBefore('?')
        val isOnnx = fileName.lowercase().endsWith(".onnx")
        val safeName = fileName.ifBlank { if (isOnnx) "remote.onnx" else "remote.gguf" }
        val displayName = safeName.removeSuffix(".gguf").removeSuffix(".onnx").ifBlank { safeName }
        when {
            ModelDownloader.activeName != null ->
                Toast.makeText(ctx, "One download at a time", Toast.LENGTH_SHORT).show()
            isOnnx -> {
                if (ModelDownloader.startVoiceUrl(ctx, displayName, safeName, clean)) {
                    Toast.makeText(ctx, "Downloading $displayName (voice model)", Toast.LENGTH_SHORT).show()
                    showUrlDialog = false
                    resetUrlDialog()
                } else {
                    Toast.makeText(ctx, "Could not start download", Toast.LENGTH_SHORT).show()
                }
            }
            ModelDownloader.start(ctx, displayName, safeName, clean) -> {
                Toast.makeText(ctx, "Downloading $displayName", Toast.LENGTH_SHORT).show()
                showUrlDialog = false
                resetUrlDialog()
            }
            else -> Toast.makeText(ctx, "Could not start download", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        ChatGPTTopAppBar(
            selectedModel = "Model Manager",
            onMenuClick = onOpenDrawer,
            onBackClick = onNavigateToHome,
            moreActions = listOf(
                Triple("Refresh lists", Icons.Default.Refresh) { refreshAll() },
                Triple("Import model", Icons.Default.Upload) { importModelLauncher.launch(arrayOf("*/*")) },
                Triple("Download model", Icons.Default.Download) { resetUrlDialog(); showUrlDialog = true }
            )
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                val activeDownload = ModelDownloader.activeName
                val transferring = activeDownload != null ||
                    importingName != null || ModelDownloader.activeVoiceName != null
                val ready = models.isNotEmpty() || connectedApis.isNotEmpty()
                ManagerHero(
                    localCount = models.size,
                    cloudCount = connectedApis.size,
                    voiceCount = voiceModels.size,
                    deviceModel = device.deviceModel,
                    threads = device.cpuCores,
                    online = device.networkUp,
                    status = when {
                        importingName != null -> "IMPORTING"
                        activeDownload != null -> "DOWNLOADING"
                        ModelDownloader.activeVoiceName != null -> "DOWNLOADING"
                        ready -> "READY"
                        else -> "SET UP"
                    },
                    statusTint = when {
                        transferring -> colors.accentBlue
                        ready -> colors.accentGreen
                        else -> colors.accentAmber
                    },
                    onDevice = { tab = ModelTab.Device; query = "" },
                    onCloud = { tab = ModelTab.Cloud; query = "" },
                    onVoice = { tab = ModelTab.Voice; query = "" }
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickActionTile(
                        icon = Icons.Default.FolderOpen,
                        tint = colors.accentGreen,
                        label = "Import",
                        modifier = Modifier.weight(1f)
                    ) { importModelLauncher.launch(arrayOf("*/*")) }
                    QuickActionTile(
                        icon = Icons.Default.Download,
                        tint = colors.accentBlue,
                        label = "Download",
                        modifier = Modifier.weight(1f)
                    ) {
                        resetUrlDialog()
                        showUrlDialog = true
                    }
                    QuickActionTile(
                        icon = Icons.Default.Cloud,
                        tint = colors.accentAmber,
                        label = "Connect",
                        modifier = Modifier.weight(1f)
                    ) {
                        if (device.networkUp) {
                            openApiFlow(null)
                        } else {
                            apiError = "No internet connection. Cloud API models require connectivity."
                            tab = ModelTab.Cloud
                        }
                    }
                }
            }
            item {
                ManagerTabBar(
                    selected = tab,
                    localCount = models.size,
                    cloudCount = connectedApis.size,
                    voiceCount = voiceModels.size,
                    onSelect = { tab = it; query = "" }
                )
            }
            if (tab != ModelTab.Voice) {
                item {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = if (tab == ModelTab.Device)
                            "Search installed or catalog models"
                        else
                            "Search cloud providers"
                    )
                }
            }

            when (tab) {
                ModelTab.Device -> {
                    val installing = importingName
                    if (installing != null) {
                        item {
                            TransferCard(
                                kicker = "Local import",
                                title = installing,
                                progress = importProgress,
                                status = importStatus.ifBlank { "Starting…" },
                                onCancel = { importCancelled = true }
                            )
                        }
                    }
                    val downloading = ModelDownloader.activeName
                    if (downloading != null) {
                        item {
                            TransferCard(
                                kicker = "Downloading",
                                title = downloading,
                                progress = ModelDownloader.progress,
                                status = ModelDownloader.status,
                                onCancel = { ModelDownloader.cancel(ctx) }
                            )
                        }
                    }
                    val q = query.trim().lowercase()
                    val installed = models.filter { q.isEmpty() || it.name.lowercase().contains(q) }
                    val installedFiles = models.mapNotNull { m ->
                        ModelDownloader.weightFileFor(ctx, m.name)?.name?.lowercase()
                    }.toSet()
                    val allCatalog = ModelDownloader.catalogModels()
                    val catalog = allCatalog.filter {
                        q.isEmpty() || it.name.lowercase().contains(q) ||
                            it.quant.lowercase().contains(q) || it.fileName.lowercase().contains(q)
                    }
                    item { SectionHeader("Installed weights", "${models.size} on device") }
                    when {
                        models.isEmpty() -> item {
                            EmptyState(
                                icon = Icons.Default.Memory,
                                title = "No local weights yet",
                                body = "Import a .gguf from storage or pull one from the catalog. Weights run fully on this phone — nothing leaves the device.",
                                actionLabel = "Import a file",
                                onAction = { importModelLauncher.launch(arrayOf("*/*")) }
                            )
                        }
                        installed.isEmpty() -> item {
                            EmptyState(
                                icon = Icons.Default.Search,
                                title = "No match",
                                body = "Nothing installed matches “$query”."
                            )
                        }
                        else -> items(installed, key = { "installed:${it.name}" }) { m ->
                            InstalledWeightCard(
                                model = m,
                                expanded = activeModel?.name == m.name,
                                contextLabel = windowLabel(UserPrefs.contextTokens(ctx)),
                                onToggle = {
                                    activeModel = if (activeModel?.name == m.name) null else m
                                },
                                onDelete = { pendingDelete = m }
                            )
                        }
                    }
                    item {
                        SectionHeader(
                            "Catalog",
                            "${allCatalog.count { !it.embedding && !it.projector }} chat · " +
                                "${allCatalog.count { it.embedding }} embed · " +
                                "${allCatalog.count { it.projector }} projector"
                        )
                    }
                    if (catalog.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Default.Search,
                                title = "No catalog match",
                                body = "No downloadable model matches “$query”."
                            )
                        }
                    } else {
                        val shown = if (catalogExpanded || q.isNotEmpty()) catalog else catalog.take(3)
                        items(shown, key = { "catalog:${it.name}" }) { entry ->
                            val entryInstalled = models.any { it.name == entry.name } ||
                                entry.fileName.lowercase() in installedFiles
                            val entryDownloading = ModelDownloader.activeName == entry.name
                            CatalogCard(
                                entry = entry,
                                installed = entryInstalled,
                                downloading = entryDownloading,
                                contextLabel = windowLabel(UserPrefs.contextTokens(ctx)),
                                onDownload = {
                                    when {
                                        ModelDownloader.activeName != null ->
                                            Toast.makeText(ctx, "One download at a time", Toast.LENGTH_SHORT).show()
                                        ModelDownloader.startEntry(ctx, entry) ->
                                            Toast.makeText(ctx, "Downloading ${entry.name}", Toast.LENGTH_SHORT).show()
                                        else ->
                                            Toast.makeText(ctx, "Could not start download", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onCancel = { ModelDownloader.cancel(ctx) }
                            )
                        }
                        if (catalog.size > 3 && q.isEmpty()) {
                            item {
                                TextButton(
                                    onClick = { catalogExpanded = !catalogExpanded },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        if (catalogExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = colors.accentGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (catalogExpanded) "Show fewer" else "Show all ${catalog.size} models",
                                        color = colors.accentGreen,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                    item {
                        CatalogSourceCard(
                            value = catalogUrlText,
                            onValueChange = { catalogUrlText = it.take(300) },
                            busy = catalogBusy,
                            message = catalogMsg,
                            remoteCount = ModelDownloader.remoteCatalog.size,
                            onRefresh = {
                                if (!catalogBusy) {
                                    catalogBusy = true
                                    catalogMsg = null
                                    UserPrefs.setCatalogUrl(ctx, catalogUrlText)
                                    managerScope.launch {
                                        try {
                                            catalogMsg = ModelDownloader.refreshRemoteCatalog(ctx)
                                        } finally {
                                            catalogBusy = false
                                        }
                                    }
                                }
                            }
                        )
                    }
                }

                ModelTab.Cloud -> {
                    val providers = allProviders()
                    val q = query.trim().lowercase()
                    val connectedNames = connectedApis.toList()
                    val available = providers
                        .filter { p -> connectedNames.none { it == p.name } }
                        .filter { p ->
                            q.isEmpty() || p.name.lowercase().contains(q) ||
                                p.note.lowercase().contains(q) || p.id.lowercase().contains(q)
                        }
                    if (apiError != null) {
                        item { ErrorBanner(apiError!!) }
                    }
                    item { SectionHeader("Connected", "${connectedNames.size} of ${providers.size}") }
                    if (connectedNames.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Default.Cloud,
                                title = "No cloud provider connected",
                                body = "Add a key to route chat through OpenAI, Claude, Gemini, Groq and more. Keys stay in this phone's private storage.",
                                actionLabel = "Add a provider",
                                onAction = {
                                    if (device.networkUp) openApiFlow(null)
                                    else apiError = "No internet connection. Cloud API models require connectivity."
                                }
                            )
                        }
                    } else {
                        items(connectedNames, key = { "connected:$it" }) { apiName ->
                            val provider = providers.firstOrNull { it.name == apiName }
                            val modelName = if (provider != null) {
                                UserPrefs.apiModel(ctx, provider.id).ifBlank { provider.defaultModel }
                            } else {
                                "Custom provider"
                            }
                            CloudProviderCard(
                                title = apiName,
                                subtitle = modelName,
                                connected = true,
                                onClick = { if (provider != null) openApiFlow(provider) }
                            )
                        }
                    }
                    if (available.isNotEmpty()) {
                        item { SectionHeader("Available providers", "${available.size}") }
                        items(available, key = { "provider:${it.id}" }) { p ->
                            CloudProviderCard(
                                title = p.name,
                                subtitle = p.note,
                                connected = false,
                                onClick = {
                                    if (device.networkUp) openApiFlow(p)
                                    else apiError = "No internet connection. Cloud API models require connectivity."
                                }
                            )
                        }
                    }
                    if (available.isEmpty() && q.isNotEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Default.Search,
                                title = "No provider match",
                                body = "No cloud provider matches “$query”."
                            )
                        }
                    }
                    item {
                        KeyBackupCard(
                            keyCount = providers.count { UserPrefs.apiKey(ctx, it.id).isNotBlank() },
                            onExport = {
                                val n = providers.count { UserPrefs.apiKey(ctx, it.id).isNotBlank() }
                                if (n == 0) {
                                    Toast.makeText(ctx, "No API keys stored to export", Toast.LENGTH_SHORT).show()
                                } else {
                                    exportPw = ""
                                    showExportPw = true
                                }
                            },
                            onImport = { importKeysLauncher.launch(arrayOf("*/*")) }
                        )
                    }
                }

                ModelTab.Voice -> {
                    item { SectionHeader("System engines", "0 MB") }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModelSourceRow(
                                icon = Icons.Default.Mic,
                                tint = colors.accentGreen,
                                title = "On-device speech recognition",
                                subtitle = if (sttOk) "System STT · 0 MB · tap for voice input settings"
                                else "Not available on this device",
                                onClick = {
                                    openSystemSettings(
                                        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
                                        "Voice input settings not found"
                                    )
                                }
                            )
                            ModelSourceRow(
                                icon = Icons.Default.RecordVoiceOver,
                                tint = colors.accentBlue,
                                title = "System TTS voices",
                                subtitle = "$ttsStatus · 0 MB · tap for TTS settings & voice data",
                                onClick = {
                                    openSystemSettings(
                                        Intent("com.android.settings.TTS_SETTINGS"),
                                        "TTS settings not found"
                                    )
                                }
                            )
                        }
                    }
                    item { SectionHeader("Downloadable weights", "${ModelDownloader.voiceCatalog.size}") }
                    items(ModelDownloader.voiceCatalog, key = { "voice:${it.fileName}" }) { v ->
                        VoiceWeightCard(
                            model = v,
                            installed = v.name in voiceModels,
                            downloading = ModelDownloader.activeVoiceName == v.name,
                            engineStatus = VoiceEngine.status(ctx, v),
                            progress = ModelDownloader.voiceProgress,
                            progressLabel = ModelDownloader.voiceStatus,
                            onDownload = {
                                if (!ModelDownloader.startVoice(ctx, v)) {
                                    Toast.makeText(ctx, "Another download is running", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onCancel = { ModelDownloader.cancelVoice(ctx) },
                            onDelete = { deleteVoice(v.name, v.fileName) }
                        )
                    }
                    item {
                        Text(
                            "Whistle needs the Cactus Needle engine (arm64). Silero VAD runs on the bundled ONNX Runtime; both stay in app-private storage.",
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
        // Delete confirmation — explicit per §96 ownership rules.
        val doomed = pendingDelete
        if (doomed != null) {
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("Delete model?") },
                text = {
                    Text(
                        "“${doomed.name}” will be removed from this device, including its weight file. This cannot be undone.",
                        fontSize = 13.sp
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        deleteModel(doomed)
                        pendingDelete = null
                        android.widget.Toast.makeText(ctx, "${doomed.name} deleted", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Delete", color = colors.accentRed) }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
            )
        }

        // External URL / Hugging Face download dialog. Direct .gguf/.onnx links
        // start straight away; `org/name` repo ids are resolved through the
        // public HF API so its GGUF files can be picked and pulled.
        if (showUrlDialog) {
            AlertDialog(
                onDismissRequest = { showUrlDialog = false },
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = { Text("Download Model") },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Direct .gguf / .onnx file URL, or a Hugging Face repo id — Karen lists the repo's GGUF files for you.",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = {
                                urlInput = it.trim()
                                hfFiles = null
                                hfRepo = null
                                hfError = null
                            },
                            placeholder = { Text("ConwayResearch/Underdog-Saluki-27B-1.0", fontSize = 12.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = colors.textPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            ),
                            colors = karenFieldColors(colors),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Examples: hf.co/unsloth/Qwen2.5-0.5B-Instruct-GGUF · https://example.com/model.gguf",
                            color = colors.textMuted,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp
                        )
                        if (hfBusy) {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    color = colors.accentGreen,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Looking up files on huggingface.co…", color = colors.textSecondary, fontSize = 12.sp)
                            }
                        }
                        if (hfError != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(hfError!!, color = colors.accentAmber, fontSize = 12.sp)
                        }
                        val files = hfFiles
                        if (files != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "${hfRepo ?: ""} · ${files.size} GGUF file${if (files.size == 1) "" else "s"}",
                                color = colors.textMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            files.forEach { f ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 6.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.surfaceHover.copy(alpha = 0.6f))
                                        .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            f.name,
                                            color = colors.textPrimary,
                                            fontSize = 12.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(f.sizeLabel, color = colors.textMuted, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            if (ModelDownloader.activeName != null) {
                                                Toast.makeText(ctx, "One download at a time", Toast.LENGTH_SHORT).show()
                                            } else if (ModelDownloader.start(ctx, f.displayName, f.name, f.url)) {
                                                Toast.makeText(ctx, "Downloading ${f.displayName}", Toast.LENGTH_SHORT).show()
                                                showUrlDialog = false
                                                resetUrlDialog()
                                            } else {
                                                Toast.makeText(ctx, "Could not start download", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.accentGreen)
                                    ) { Text("Download", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    if (hfFiles == null) {
                        TextButton(
                            onClick = {
                                val parsed = ModelDownloader.parseModelInput(urlInput)
                                when (parsed.kind) {
                                    ModelDownloader.ModelInput.Kind.DIRECT_URL ->
                                        startDirectDownload(parsed.value)
                                    ModelDownloader.ModelInput.Kind.HF_REPO -> {
                                        if (!hfBusy) {
                                            hfBusy = true
                                            hfError = null
                                            managerScope.launch {
                                                when (val res = ModelDownloader.resolveHuggingFace(parsed.value, parsed.revision)) {
                                                    is ModelDownloader.HfResolveResult.Ok -> {
                                                        hfRepo = res.repoId
                                                        hfFiles = res.files
                                                    }
                                                    is ModelDownloader.HfResolveResult.Failed -> {
                                                        hfError = res.message
                                                    }
                                                }
                                                hfBusy = false
                                            }
                                        }
                                    }
                                    ModelDownloader.ModelInput.Kind.INVALID ->
                                        hfError = "Not a link or repo id — enter org/name or a direct file URL"
                                }
                            }
                        ) { Text(if (hfBusy) "Checking…" else "Find files") }
                    } else {
                        TextButton(onClick = { showUrlDialog = false }) { Text("Close") }
                    }
                },
                dismissButton = {
                    if (hfFiles != null) {
                        TextButton(onClick = { resetUrlDialog() }) { Text("Back") }
                    } else {
                        TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
                    }
                }
            )
        }

        // Cloud API connect dialog: step 0 = pick provider, step 1 = enter key.
        // Keys persist in on-device SharedPreferences via UserPrefs.
        // Export password: picked file is encrypted with this (never stored).
        if (showExportPw) {
            AlertDialog(
                onDismissRequest = { showExportPw = false; exportPw = "" },
                title = { Text("Backup password") },
                text = {
                    Column {
                        Text("Encrypts every stored API key into one .kbk file.", fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = exportPw,
                            onValueChange = { exportPw = it },
                            label = { Text("Password (min 4 chars)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = karenFieldColors(colors),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (exportPw.length < 4) {
                            Toast.makeText(ctx, "Password needs at least 4 characters", Toast.LENGTH_SHORT).show()
                        } else {
                            showExportPw = false
                            exportKeysLauncher.launch("karen-api-keys.kbk")
                        }
                    }) { Text("Choose file") }
                },
                dismissButton = {
                    TextButton(onClick = { showExportPw = false; exportPw = "" }) { Text("Cancel") }
                }
            )
        }

        // Import password: decrypts the picked .kbk file into stored keys.
        if (showImportPw) {
            AlertDialog(
                onDismissRequest = { showImportPw = false; importPw = ""; pendingImportUri = null },
                title = { Text("Import backup") },
                text = {
                    Column {
                        Text("Enter the password this backup was encrypted with.", fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = importPw,
                            onValueChange = { importPw = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = karenFieldColors(colors),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val uri = pendingImportUri
                        val pw = importPw
                        showImportPw = false
                        importPw = ""
                        pendingImportUri = null
                        if (uri != null) {
                            managerScope.launch {
                                val msg = withContext(Dispatchers.IO) { ApiKeyVault.importKeys(ctx, uri, pw) }
                                refreshAll()
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }) { Text("Decrypt & Import") }
                },
                dismissButton = {
                    TextButton(onClick = { showImportPw = false; importPw = ""; pendingImportUri = null }) { Text("Cancel") }
                }
            )
        }

        if (showApiDialog) {
            val provider = selectedProvider
            AlertDialog(
                onDismissRequest = { showApiDialog = false; apiStep = 0 },
                containerColor = colors.surface,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = { Text(if (apiStep == 0) "Connect Cloud API" else if (apiStep == 2) "Custom API" else (provider?.name ?: "API key")) },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (apiStep == 0) {
                            Text("Select a provider, then paste its API key (stored only on this device):", fontSize = 13.sp)
                            Spacer(Modifier.height(10.dp))
                            allProviders().forEach { p ->
                                val keySet = UserPrefs.apiKey(ctx, p.id).isNotBlank()
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedProvider = p
                                            apiKeyInput = UserPrefs.apiKey(ctx, p.id)
                                            apiModelInput = UserPrefs.apiModel(ctx, p.id).ifBlank { p.defaultModel }
                                            modelCustom = false
                                            modelMenu = false
                                            liveModels = null
                                            liveError = false
                                            keyVisible = false
                                            apiStep = 1
                                            fetchLiveModels()
                                        }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (keySet) Icons.Default.CheckCircle else Icons.Default.Cloud,
                                        contentDescription = null,
                                        tint = if (keySet) colors.accentGreen else colors.accentBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(p.name, color = colors.textPrimary, fontSize = 13.5.sp)
                                        Text(
                                            if (keySet) "key stored · ${UserPrefs.apiModel(ctx, p.id).ifBlank { p.defaultModel }}" else p.note,
                                            color = if (keySet) colors.accentGreen else colors.textMuted,
                                            fontSize = 11.sp,
                                            fontFamily = if (keySet) FontFamily.Monospace else null
                                        )
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedProvider = null
                                        customName = ""
                                        customBase = ""
                                        customModel = ""
                                        apiKeyInput = ""
                                        apiStep = 2
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = colors.accentGreen, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Custom API…", color = colors.textPrimary, fontSize = 13.5.sp)
                                    Text(
                                        "Any OpenAI-compatible endpoint (Ollama, vLLM, proxy)",
                                        color = colors.textMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                            }
                        } else if (apiStep == 2) {
                            Text("Add any OpenAI-compatible endpoint. Base URL, model id, and key are stored only on this device.", fontSize = 13.sp)
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = customName,
                                onValueChange = { customName = it.take(40) },
                                label = { Text("Name") },
                                placeholder = { Text("My Server") },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(color = colors.textPrimary),
                                colors = karenFieldColors(colors),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customBase,
                                onValueChange = { customBase = it.trim().take(200) },
                                label = { Text("Base URL") },
                                placeholder = { Text("https://my-host:8000/v1") },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = colors.textPrimary,
                                    fontFamily = FontFamily.Monospace
                                ),
                                colors = karenFieldColors(colors),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = customModel,
                                onValueChange = { customModel = it.trim().take(120) },
                                label = { Text("Model id") },
                                placeholder = { Text("llama-3.3-70b-versatile") },
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    color = colors.textPrimary,
                                    fontFamily = FontFamily.Monospace
                                ),
                                colors = karenFieldColors(colors),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("API key") },
                                placeholder = { Text("sk-…") },
                                singleLine = true,
                                colors = karenFieldColors(colors),
                                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(
                                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (keyVisible) "Hide key" else "Show key"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (provider != null) {
                            val stored = UserPrefs.apiKey(ctx, provider.id)
                            Text(
                                "Paste your ${provider.name} key. It stays in on-device storage — only sent to ${provider.name} when you invoke it.",
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            if (provider.keyUrl.isNotBlank()) {
                                val keyPage = remember(provider.keyUrl) {
                                    val url = if (provider.keyUrl.startsWith("http")) provider.keyUrl
                                    else "https://${provider.keyUrl}"
                                    androidx.compose.ui.text.AnnotatedString.Builder("Get a key: ${provider.keyUrl}").apply {
                                        val start = "Get a key: ".length
                                        addStyle(
                                            androidx.compose.ui.text.SpanStyle(
                                                color = colors.accentBlue,
                                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            start,
                                            length
                                        )
                                        addStringAnnotation("url", url, start, length)
                                    }.toAnnotatedString()
                                }
                                ClickableText(
                                    text = keyPage,
                                    style = androidx.compose.ui.text.TextStyle(
                                        color = colors.textSecondary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    onClick = { offset ->
                                        keyPage.getStringAnnotations("url", offset, offset)
                                            .firstOrNull()?.let { runCatching { uriHandler.openUri(it.item) } }
                                    }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(
                                "Calls: ${apiModelInput.ifBlank { provider.defaultModel }} (live)",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.accentGreen
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Model", color = colors.textMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                if (liveLoading) {
                                    CircularProgressIndicator(color = colors.accentGreen, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                } else {
                                    IconButton(onClick = { fetchLiveModels() }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh live models", tint = colors.textMuted, modifier = Modifier.size(15.dp))
                                    }
                                }
                            }
                            val live = liveModels
                            if (liveError) {
                                Text("Live list unreachable — showing known models", color = colors.accentAmber, fontSize = 11.sp)
                            } else if (live != null) {
                                Text("${live.size} live models", color = colors.accentGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                            Spacer(Modifier.height(4.dp))
                            val liveIds = live?.map { it.id }
                            val liveFree: Map<String, Boolean> = live?.associate { it.id to it.free } ?: emptyMap()
                            val liveCtx = live?.associate { it.id to it.context } ?: emptyMap()
                            val modelChoices = if (liveIds != null) {
                                val cur = apiModelInput.trim()
                                // Free tier first, api order kept inside each tier.
                                val freeFirst = liveIds.sortedWith(
                                    compareBy<String>({ liveFree[it] != true }, { it })
                                )
                                (if (cur.isNotBlank() && cur != CUSTOM_MODEL && cur !in freeFirst) listOf(cur) + freeFirst else freeFirst) + CUSTOM_MODEL
                            } else providerModelChoices(provider, apiModelInput)
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(colors.surfaceHover.copy(alpha = 0.6f))
                                        .border(1.dp, colors.border.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                                        .clickable { modelMenu = true }
                                        .padding(horizontal = 12.dp, vertical = 11.dp)
                                ) {
                                    Text(
                                        text = apiModelInput.ifBlank { provider.defaultModel },
                                        color = colors.textPrimary,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose model", tint = colors.textMuted, modifier = Modifier.size(18.dp))
                                }
                                KDropdownMenu(
                                    expanded = modelMenu,
                                    onDismissRequest = { modelMenu = false }
                                ) {
                                    modelChoices.forEachIndexed { idx, choice ->
                                        // Separate the live list from the "custom id" action.
                                        if (idx > 0 && choice == CUSTOM_MODEL) KMenuDivider()
                                        // Tier flag on every row: true only on live-priced
                                        // free; everything else bills the key.
                                        val tier = liveFree[choice] == true
                                        val ctxTag = formatContextWindow(
                                            liveCtx[choice] ?: liveContextOf(provider.id, choice)
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = choice,
                                                        color = if (choice == apiModelInput.ifBlank { provider.defaultModel }) colors.accentGreen else colors.textPrimary,
                                                        fontSize = 13.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (ctxTag.isNotBlank()) {
                                                        Text(
                                                            text = ctxTag,
                                                            color = colors.textMuted,
                                                            fontSize = 10.5.sp,
                                                            fontFamily = FontFamily.Monospace
                                                        )
                                                        Spacer(Modifier.width(8.dp))
                                                    }
                                                    if (choice != CUSTOM_MODEL) {
                                                        Spacer(Modifier.width(8.dp))
                                                        Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(
                                                                if (tier) colors.accentGreen.copy(alpha = 0.18f)
                                                                else colors.accentAmber.copy(alpha = 0.16f)
                                                            )
                                                            .padding(horizontal = 7.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = if (tier) "FREE" else "PAID",
                                                            color = if (tier) colors.accentGreen else colors.accentAmber,
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                    }
                                                }
                                            },
                                            onClick = {
                                                if (choice == CUSTOM_MODEL) {
                                                    modelCustom = true
                                                    apiModelInput = ""
                                                } else {
                                                    modelCustom = false
                                                    apiModelInput = choice
                                                }
                                                modelMenu = false
                                            },
                                            modifier = Modifier
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                    }
                                }
                            }
                            if (modelCustom) {
                                Spacer(Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = apiModelInput,
                                    onValueChange = { apiModelInput = it },
                                    label = { Text("Custom model id") },
                                    placeholder = { Text(provider.defaultModel) },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(
                                        color = colors.textPrimary,
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    colors = karenFieldColors(colors),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                // Ids absent from the live list 404 at call time:
                                // warn before the user saves a broken override.
                                val typedCustom = apiModelInput.trim()
                                val knownLive = live?.map { it.id }
                                if (typedCustom.isNotBlank() && knownLive != null && typedCustom !in knownLive) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "Not in ${provider.name}'s live list — requests will fail with a 404. Check spelling or tap the refresh icon.",
                                        color = colors.accentAmber,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = apiKeyInput,
                                onValueChange = { apiKeyInput = it },
                                label = { Text("API key") },
                                placeholder = { Text("sk-…") },
                                singleLine = true,
                                colors = karenFieldColors(colors),
                                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { keyVisible = !keyVisible }) {
                                        Icon(
                                            if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            contentDescription = if (keyVisible) "Hide key" else "Show key"
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (stored.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Current: ••••${stored.takeLast(4)}",
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textMuted
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (apiStep == 0) {
                        TextButton(onClick = { showApiDialog = false }) { Text("Done") }
                    } else {
                        TextButton(onClick = { apiStep = 0 }) { Text("Back") }
                    }
                },
                dismissButton = {
                    if (apiStep == 1 && provider != null) {
                        Row {
                            val stored = UserPrefs.apiKey(ctx, provider.id)
                            if (stored.isNotBlank() || provider.name in connectedApis) {
                                TextButton(onClick = {
                                    UserPrefs.clearApiKey(ctx, provider.id)
                                    UserPrefs.setApiModel(ctx, provider.id, "")
                                    connectedApis.remove(provider.name)
                                    if (provider.id.startsWith("custom_")) {
                                        UserPrefs.deleteCustomProvider(ctx, provider.id)
                                    }
                                    apiKeyInput = ""
                                    apiModelInput = ""
                                    showApiDialog = false
                                    apiStep = 0
                                    android.widget.Toast.makeText(ctx, "${provider.name} removed", android.widget.Toast.LENGTH_SHORT).show()
                                }) { Text("Remove", color = colors.accentRed) }
                            }
                            TextButton(onClick = {
                                val key = apiKeyInput.trim()
                                if (key.isNotBlank()) {
                                    UserPrefs.saveApiKey(ctx, provider.id, key)
                                    UserPrefs.setApiModel(ctx, provider.id, apiModelInput.trim())
                                    if (provider.name !in connectedApis) {
                                        connectedApis.add(provider.name)
                                    }
                                    showApiDialog = false
                                    apiStep = 0
                                    android.widget.Toast.makeText(ctx, "${provider.name} connected", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(ctx, "Paste a key first", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }) { Text("Save & Connect") }
                        }
                    } else if (apiStep == 2) {
                        TextButton(onClick = {
                            val name = customName.trim()
                            val base = customBase.trim().trimEnd('/')
                            val model = customModel.trim()
                            val key = apiKeyInput.trim()
                            if (name.isBlank() || base.isBlank() || model.isBlank() || key.isBlank()) {
                                android.widget.Toast.makeText(ctx, "Fill name, base URL, model, and key", android.widget.Toast.LENGTH_SHORT).show()
                            } else if (!base.startsWith("http://") && !base.startsWith("https://")) {
                                android.widget.Toast.makeText(ctx, "Base URL must start with http(s)://", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                var finalName = name
                                var n = 2
                                val taken = allProviders().map { it.name }.toMutableSet()
                                while (finalName in taken) {
                                    finalName = "$name ($n)"
                                    n++
                                }
                                val created = UserPrefs.saveCustomProvider(ctx, finalName, base, model)
                                UserPrefs.saveApiKey(ctx, created.id, key)
                                UserPrefs.setApiModel(ctx, created.id, model)
                                if (created.name !in connectedApis) {
                                    connectedApis.add(created.name)
                                }
                                apiKeyInput = ""
                                showApiDialog = false
                                apiStep = 0
                                android.widget.Toast.makeText(ctx, "${created.name} connected", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Save & Connect") }
                    }
                }
            )
        }
    }
}
