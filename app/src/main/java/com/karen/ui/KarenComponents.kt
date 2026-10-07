package com.karen.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared primitives for the Obsidian Cybernetic Workspace (docs/DESIGN.md).
 *
 * Everything here is built from tonal surface tiers plus 1dp hairlines.
 * Height, radius and type sizes follow the spec: 24dp pills, 8/16/24dp
 * containers, 44dp minimum tap targets.
 */

/** Corner radii named so call sites read like the spec. */
object KR {
    val chip = RoundedCornerShape(9999.dp)
    val field = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(16.dp)
    val sheet = RoundedCornerShape(24.dp)
}

/** Semantic tone so chips and meters stay consistent across screens. */
enum class KTone { Primary, Success, Amber, Danger, Neutral, Muted }

@Composable
fun KTone.color(colors: KarenColors = LocalKarenColors.current): Color = when (this) {
    KTone.Primary -> colors.accentGreen
    KTone.Success -> colors.accentSuccess
    KTone.Amber -> colors.accentAmber
    KTone.Danger -> colors.accentRed
    KTone.Neutral -> colors.textSecondary
    KTone.Muted -> colors.textMuted
}

/** 1dp structural divider. The spec's substitute for elevation shadows. */
@Composable
fun KHairline(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalKarenColors.current.border)
    )
}

/**
 * Diagnostic & status chip — 24dp tall pill, monospaced 11sp label.
 * `led = true` renders the static state indicator dot used for live status.
 */
@Composable
fun KStatusChip(
    label: String,
    tone: KTone = KTone.Neutral,
    led: Boolean = false,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    val tint = tone.color(colors)
    Row(
        modifier = modifier
            .clip(KR.chip)
            .background(tint.copy(alpha = if (colors.isDark) 0.12f else 0.16f))
            .border(1.dp, tint.copy(alpha = 0.30f), KR.chip)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (led) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            color = tint,
            fontFamily = LocalKarenType.current.mono,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

/**
 * Level 2 telemetry panel. Used for every grouped block in the screenshots:
 * title, optional monospaced subtitle on the right, then arbitrary content.
 */
@Composable
fun KPanel(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = LocalKarenColors.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(KR.card)
            .background(colors.cardBackground)
            .border(1.dp, colors.cardBorder, KR.card)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 20.sp
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = colors.textMuted,
                        fontFamily = LocalKarenType.current.mono,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

/** Small heading above a cluster of panels. Monospaced, muted, uppercase-ish. */
@Composable
fun KSectionLabel(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        color = LocalKarenColors.current.textMuted,
        fontFamily = LocalKarenType.current.mono,
        fontSize = 10.5.sp,
        letterSpacing = 0.6.sp,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

/**
 * Key/value telemetry row. Label in sans, value in mono — the spec's rule for
 * aligning readings down a right edge.
 */
@Composable
fun KTelemetryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color? = null,
    mono: Boolean = true
) {
    val colors = LocalKarenColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = colors.textSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            value,
            color = valueColor ?: colors.textPrimary,
            fontFamily = if (mono) LocalKarenType.current.mono else LocalKarenType.current.sans,
            fontSize = if (mono) 12.sp else 13.sp,
            fontWeight = if (mono) FontWeight.Medium else FontWeight.Normal
        )
    }
}

/**
 * Flat progress meter. `track` defaults to a hairline so the bar reads as a
 * structural division rather than a decorative element.
 */
@Composable
fun KProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: KTone = KTone.Primary,
    height: Dp = 6.dp,
    animate: Boolean = true
) {
    val colors = LocalKarenColors.current
    val tint = tone.color(colors)
    val target = fraction.coerceIn(0f, 1f)
    val progress by animateFloatAsState(
        targetValue = if (animate) target else target,
        animationSpec = tween(420),
        label = "kProgress"
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(KR.chip)
            .background(colors.surface)
            .border(1.dp, colors.border, KR.chip)
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .height(height)
                .clip(KR.chip)
                .background(tint)
        )
    }
}

/** Compact labelled statistic — big mono value over a slate caption. */
@Composable
fun KMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tone: KTone = KTone.Neutral,
    caption: String? = null
) {
    val colors = LocalKarenColors.current
    Column(modifier) {
        Text(
            label.uppercase(),
            color = colors.textMuted,
            fontFamily = LocalKarenType.current.mono,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = tone.color(colors),
            fontFamily = LocalKarenType.current.mono,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
        if (caption != null) {
            Text(
                caption,
                color = colors.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Selectable pill used for filters, quant levels and context toggles. */
@Composable
fun KChip(
    selectableLabel: String = "",
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: KTone = KTone.Primary
) {
    val colors = LocalKarenColors.current
    val tint = tone.color(colors)
    val bg by animateColorAsState(
        if (selected) tint.copy(alpha = 0.14f) else colors.surface,
        tween(200), label = "kChipBg"
    )
    val border by animateColorAsState(
        if (selected) tint.copy(alpha = 0.55f) else colors.border,
        tween(200), label = "kChipBorder"
    )
    val fg by animateColorAsState(
        if (selected) tint else colors.textSecondary,
        tween(200), label = "kChipFg"
    )
    Text(
        selectableLabel,
        color = fg,
        fontFamily = LocalKarenType.current.mono,
        fontSize = 11.sp,
        modifier = modifier
            .clip(KR.chip)
            .background(bg)
            .border(1.dp, border, KR.chip)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}

/** Primary action — solid `#0284c7`, 44dp tall, 8dp radius, no shadow. */
@Composable
fun KPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    val colors = LocalKarenColors.current
    val active = colors.accentGreen
    val bg by animateColorAsState(
        if (enabled) active else colors.surface,
        tween(180), label = "kPrimaryBg"
    )
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            color = if (enabled) Color.White else colors.textMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** Secondary / utilitarian — transparent fill, hairline border. */
@Composable
fun KSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    tone: KTone = KTone.Neutral
) {
    val colors = LocalKarenColors.current
    val tint = tone.color(colors)
    val border by animateColorAsState(
        if (enabled) colors.border else colors.border.copy(alpha = 0.4f),
        tween(180), label = "kSecondaryBorder"
    )
    val bg by animateColorAsState(
        if (enabled) Color.Transparent else colors.surface.copy(alpha = 0.5f),
        tween(180), label = "kSecondaryBg"
    )
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            color = if (enabled) tint else colors.textMuted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** Destructive / abort — rose glyphs on a faint rose wash. */
@Composable
fun KDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null
) {
    val colors = LocalKarenColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(colors.accentRed.copy(alpha = 0.08f))
            .border(1.dp, colors.accentRed.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            color = colors.accentRed,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Switch row matching the spec: 20dp track in Level 2, azure thumb, hairline
 * outline, caption beneath the title when supplied.
 */
@Composable
fun KSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val colors = LocalKarenColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = colors.textMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        KSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Bare switch, styled per spec rather than defaulting to M3's pill. */
@Composable
fun KSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    val track by animateColorAsState(
        if (checked) colors.accentGreen.copy(alpha = 0.28f) else colors.surface,
        tween(180), label = "kSwitchTrack"
    )
    val outline by animateColorAsState(
        if (checked) colors.accentGreen.copy(alpha = 0.70f) else colors.border,
        tween(180), label = "kSwitchOutline"
    )
    val thumb by animateColorAsState(
        if (checked) colors.accentGreen else colors.textMuted,
        tween(180), label = "kSwitchThumb"
    )
    Box(
        modifier
            .size(width = 44.dp, height = 24.dp)
            .clip(KR.chip)
            .background(track)
            .border(1.dp, outline, KR.chip)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(thumb)
        )
    }
}

/**
 * Inline confirmation prompt — amber left rail, monospaced parameter echo, and
 * a Confirm button. Used where an action needs explicit consent.
 */
@Composable
fun KConfirmBar(
    message: String,
    detail: String,
    confirmLabel: String = "Confirm",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKarenColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(KR.card)
            .background(colors.accentAmber.copy(alpha = 0.07f))
            .border(1.dp, colors.accentAmber.copy(alpha = 0.32f), KR.card)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(34.dp)
                .clip(KR.chip)
                .background(colors.accentAmber)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(message, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                detail,
                color = colors.textMuted,
                fontFamily = LocalKarenType.current.mono,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accentAmber)
                .clickable { onConfirm() }
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF1A1206), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(confirmLabel, color = Color(0xFF1A1206), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            "Cancel",
            color = colors.textMuted,
            fontSize = 13.sp,
            modifier = Modifier.clickable { onDismiss() }.padding(6.dp)
        )
    }
}

/** Screen header: title with a monospaced eyebrow, optional back affordance. */
@Composable
fun KScreenHeader(
    title: String,
    eyebrow: String = "",
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = LocalKarenColors.current
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            KIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            if (eyebrow.isNotEmpty()) {
                Text(
                    eyebrow.uppercase(),
                    color = colors.textMuted,
                    fontFamily = LocalKarenType.current.mono,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp
                )
            }
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.4).sp,
                lineHeight = 28.sp
            )
        }
        if (trailing != null) trailing()
    }
}

/** Hairline-outlined icon button sized to the 44dp minimum tap target. */
@Composable
fun KIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** Outlined square container used for the logo mark and avatar. */
@Composable
fun KLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    icon: @Composable () -> Unit
) {
    val colors = LocalKarenColors.current
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size / 3))
            .background(colors.accentGreen.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, colors.accentGreen.copy(alpha = 0.45f)), RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center
    ) { icon() }
}
