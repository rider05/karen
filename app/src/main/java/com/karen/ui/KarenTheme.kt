package com.karen.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

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
    val accentGreen: Color = Color(0xFF10A37F),
    val accentBlue: Color = Color(0xFF38BDF8),
    val accentAmber: Color = Color(0xFFF59E0B),
    val accentRed: Color = Color(0xFFEF4444),
    val drawerBackground: Color,
    val drawerItemHover: Color
)

val OledThemeColors = KarenColors(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF121212),
    surfaceHover = Color(0xFF1C1C1E),
    border = Color(0xFF262626),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFC4C4C8),
    textMuted = Color(0xFF8E8E93),
    userBubble = Color(0xFF1C1C1E),
    userBubbleText = Color(0xFFFFFFFF),
    composerBackground = Color(0xFF141416),
    composerBorder = Color(0xFF2C2C2E),
    cardBackground = Color(0xFF121212),
    cardBorder = Color(0xFF262626),
    drawerBackground = Color(0xFF0A0A0A),
    drawerItemHover = Color(0xFF1C1C1E)
)

val CharcoalThemeColors = KarenColors(
    isDark = true,
    background = Color(0xFF212121),
    surface = Color(0xFF2F2F2F),
    surfaceHover = Color(0xFF3A3A3A),
    border = Color(0xFF3D3D3D),
    textPrimary = Color(0xFFECECEC),
    textSecondary = Color(0xFFB4B4B4),
    textMuted = Color(0xFF8E8EA0),
    userBubble = Color(0xFF2F2F2F),
    userBubbleText = Color(0xFFECECEC),
    composerBackground = Color(0xFF2F2F2F),
    composerBorder = Color(0xFF3D3D3D),
    cardBackground = Color(0xFF2A2A2A),
    cardBorder = Color(0xFF3D3D3D),
    drawerBackground = Color(0xFF1A1A1A),
    drawerItemHover = Color(0xFF2F2F2F)
)

val LightThemeColors = KarenColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F7F8),
    surfaceHover = Color(0xFFEFEFF0),
    border = Color(0xFFE5E5E5),
    textPrimary = Color(0xFF0D0D0D),
    textSecondary = Color(0xFF5D5D62),
    textMuted = Color(0xFF8E8E93),
    userBubble = Color(0xFFE9E9EB),
    userBubbleText = Color(0xFF0D0D0D),
    composerBackground = Color(0xFFF4F4F4),
    composerBorder = Color(0xFFE0E0E0),
    cardBackground = Color(0xFFF9F9FB),
    cardBorder = Color(0xFFE5E5E5),
    drawerBackground = Color(0xFFFFFFFF),
    drawerItemHover = Color(0xFFF4F4F4)
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

val LocalKarenColors = compositionLocalOf { OledThemeColors }
val LocalKarenThemeMode = compositionLocalOf { KarenThemeMode.OLED }

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
        LocalKarenThemeMode provides mode
    ) {
        content()
    }
}
