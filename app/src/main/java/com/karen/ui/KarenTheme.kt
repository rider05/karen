package com.karen.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

enum class KarenThemeMode {
    OLED,
    CHARCOAL,
    LIGHT
}

data class KarenColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHover: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val userBubble: Color,
    val userBubbleText: Color,
    val composerBackground: Color,
    val composerBorder: Color,
    val cardBackground: Color,
    val cardBorder: Color,
    val accentGreen: Color = Color(0xFF2563EB),
    val accentBlue: Color = Color(0xFF38BDF8),
    val accentAmber: Color = Color(0xFFF59E0B),
    val accentRed: Color = Color(0xFFEF4444),
    val accentSuccess: Color = Color(0xFF059669),
    val drawerBackground: Color,
    val drawerItemHover: Color
)

// Palette follows docs/DESIGN.md ("Professional Workspace"): slate neutrals,
// one confident blue accent, success/warning/error status tints. accentGreen
// carries the primary accent (most call sites use it for primary actions).
val OledThemeColors = KarenColors(
    isDark = true,
    background = Color(0xFF0F1115),
    surface = Color(0xFF161A20),
    surfaceHover = Color(0xFF1F2530),
    border = Color(0xFF2B3342),
    textPrimary = Color(0xFFE5E9F0),
    textSecondary = Color(0xFFA3AEBF),
    textMuted = Color(0xFF64708A),
    userBubble = Color(0xFF2563EB),
    userBubbleText = Color(0xFFFFFFFF),
    composerBackground = Color(0xFF161A20),
    composerBorder = Color(0xFF2B3342),
    cardBackground = Color(0xFF161A20),
    cardBorder = Color(0xFF2B3342),
    accentGreen = Color(0xFF2563EB),
    accentBlue = Color(0xFF60A5FA),
    accentAmber = Color(0xFFFBBF24),
    accentRed = Color(0xFFF87171),
    accentSuccess = Color(0xFF34D399),
    drawerBackground = Color(0xFF13161C),
    drawerItemHover = Color(0xFF1F2530)
)

val CharcoalThemeColors = KarenColors(
    isDark = true,
    background = Color(0xFF1A1F27),
    surface = Color(0xFF1F2530),
    surfaceHover = Color(0xFF262D3A),
    border = Color(0xFF2B3342),
    textPrimary = Color(0xFFE5E9F0),
    textSecondary = Color(0xFFA3AEBF),
    textMuted = Color(0xFF64708A),
    userBubble = Color(0xFF2563EB),
    userBubbleText = Color(0xFFFFFFFF),
    composerBackground = Color(0xFF1F2530),
    composerBorder = Color(0xFF2B3342),
    cardBackground = Color(0xFF1F2530),
    cardBorder = Color(0xFF2B3342),
    accentGreen = Color(0xFF2563EB),
    accentBlue = Color(0xFF60A5FA),
    accentAmber = Color(0xFFFBBF24),
    accentRed = Color(0xFFF87171),
    accentSuccess = Color(0xFF34D399),
    drawerBackground = Color(0xFF161A20),
    drawerItemHover = Color(0xFF262D3A)
)

val LightThemeColors = KarenColors(
    isDark = false,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceHover = Color(0xFFF1F5F9),
    border = Color(0xFFE2E8F0),
    textPrimary = Color(0xFF0F172A),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF64748B),
    userBubble = Color(0xFF2563EB),
    userBubbleText = Color(0xFFFFFFFF),
    composerBackground = Color(0xFFFFFFFF),
    composerBorder = Color(0xFFCBD5E1),
    cardBackground = Color(0xFFFFFFFF),
    cardBorder = Color(0xFFE2E8F0),
    accentGreen = Color(0xFF2563EB),
    accentBlue = Color(0xFF1D4ED8),
    accentAmber = Color(0xFFD97706),
    accentRed = Color(0xFFDC2626),
    accentSuccess = Color(0xFF059669),
    drawerBackground = Color(0xFFFFFFFF),
    drawerItemHover = Color(0xFFF1F5F9)
)

object KarenThemeState {
    var currentMode by mutableStateOf(KarenThemeMode.OLED)
        private set

    val colors: KarenColors
        get() = when (currentMode) {
            KarenThemeMode.OLED -> OledThemeColors
            KarenThemeMode.CHARCOAL -> CharcoalThemeColors
            KarenThemeMode.LIGHT -> LightThemeColors
        }

    fun toggleTheme() {
        currentMode = when (currentMode) {
            KarenThemeMode.OLED -> KarenThemeMode.LIGHT
            KarenThemeMode.CHARCOAL -> KarenThemeMode.LIGHT
            KarenThemeMode.LIGHT -> KarenThemeMode.OLED
        }
    }

    fun setMode(mode: KarenThemeMode) {
        currentMode = mode
    }
}

data class KarenType(
    val sans: FontFamily = FontFamily.SansSerif,
    val mono: FontFamily = FontFamily.Monospace,
    val headlineLg: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 26.sp, lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp
    ),
    val headlineSm: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Medium, letterSpacing = (-0.25).sp
    ),
    val titleMd: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.Medium
    ),
    val bodyLg: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp
    ),
    val bodyMd: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp
    ),
    val monoMd: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.Medium
    ),
    val monoSm: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    )
)

val LocalKarenColors = compositionLocalOf { OledThemeColors }
val LocalKarenThemeMode = compositionLocalOf { KarenThemeMode.OLED }
val LocalKarenType = compositionLocalOf { KarenType() }

/**
 * Maps Karen tokens onto M3 so every mini-window (AlertDialog, DropdownMenu,
 * ModalBottomSheet, Checkbox, DatePicker…) follows the active dark/light
 * theme instead of M3's built-in default scheme.
 */
private fun KarenColors.toColorScheme(): androidx.compose.material3.ColorScheme {
    val primary = accentGreen
    return if (isDark) {
        androidx.compose.material3.darkColorScheme(
            primary = primary,
            onPrimary = Color.White,
            secondary = accentBlue,
            onSecondary = Color.White,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceHover,
            onSurfaceVariant = textSecondary,
            outline = border,
            outlineVariant = border,
            error = accentRed,
            onError = Color.White
        )
    } else {
        androidx.compose.material3.lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            secondary = accentBlue,
            onSecondary = Color.White,
            background = background,
            onBackground = textPrimary,
            surface = surface,
            onSurface = textPrimary,
            surfaceVariant = surfaceHover,
            onSurfaceVariant = textSecondary,
            outline = border,
            outlineVariant = border,
            error = accentRed,
            onError = Color.White
        )
    }
}

@Composable
fun KarenTheme(
    mode: KarenThemeMode = KarenThemeState.currentMode,
    content: @Composable () -> Unit
) {
    val colors = when (mode) {
        KarenThemeMode.OLED -> OledThemeColors
        KarenThemeMode.CHARCOAL -> CharcoalThemeColors
        KarenThemeMode.LIGHT -> LightThemeColors
    }

    CompositionLocalProvider(
        LocalKarenColors provides colors,
        LocalKarenThemeMode provides mode,
        LocalKarenType provides KarenType()
    ) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = colors.toColorScheme()
        ) {
            content()
        }
    }
}
