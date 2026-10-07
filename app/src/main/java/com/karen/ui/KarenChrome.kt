package com.karen.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App chrome for the Obsidian Cybernetic Workspace: the console header and the
 * navigation dock. Both are shared so every screen reads as one instrument.
 */

/**
 * Console header — menu toggle, logo mark, wordmark with a monospaced eyebrow,
 * a live model-status pill, then avatar and overflow actions on the trailing end.
 */
@Composable
fun KConsoleHeader(
    eyebrow: String,
    modifier: Modifier = Modifier,
    modelLabel: String = "Karen 4B Q4",
    modelState: String = "Offline",
    modelOnline: Boolean = true,
    onMenuClick: () -> Unit = {},
    onModelClick: () -> Unit = {},
    onAvatarClick: () -> Unit = {},
    onMoreClick: () -> Unit = {}
) {
    val colors = LocalKarenColors.current
    val type = LocalKarenType.current

    Column(
        modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KIconButton(onClick = onMenuClick) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Open navigation",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(
                        1.dp,
                        if (modelOnline) colors.accentSuccess.copy(alpha = 0.35f) else colors.border,
                        CircleShape
                    )
                    .clickable { onModelClick() }
                    .padding(start = 9.dp, end = 7.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (modelOnline) colors.accentSuccess else colors.textMuted)
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        modelLabel,
                        color = colors.textPrimary,
                        fontFamily = type.mono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    Text(
                        modelState,
                        color = if (modelOnline) colors.accentSuccess else colors.textMuted,
                        fontFamily = type.mono,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = "Select model",
                    tint = colors.textMuted,
                    modifier = Modifier.size(15.dp)
                )
            }

            Spacer(Modifier.width(4.dp))

            KIconButton(onClick = onAvatarClick) {
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceHover)
                        .border(1.dp, colors.border, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = "Profile",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            KIconButton(onClick = onMoreClick) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Hairline under the header, brightened at the centre to read as a rule.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            colors.accentGreen.copy(alpha = 0.35f),
                            colors.accentGreen.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

/**
 * Navigation dock — Home / Chat / Workspace / Files, with Voice promoted to a
 * floating mic action above the rail so it never competes with destinations.
 */
@Composable
fun KNavigationDock(
    currentScreen: String,
    onSelectScreen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenVoice: () -> Unit = {},
    workspaceBadge: Int = 0,
    showVoiceAction: Boolean = true
) {
    val colors = LocalKarenColors.current
    val type = LocalKarenType.current

    val destinations = remember {
        listOf(
            NavDestination("Home", "Home", Icons.Default.Menu),
            NavDestination("Chat", "Chat", Icons.Default.GraphicEq),
            NavDestination("Workspace", "Workspace", Icons.AutoMirrored.Filled.KeyboardArrowRight),
            NavDestination("Files", "Files", Icons.Default.Menu)
        )
    }

    Box(modifier.fillMaxWidth()) {
        if (showVoiceAction) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 16.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                colors.accentGreen.copy(alpha = 0.28f),
                                colors.accentGreen.copy(alpha = 0.04f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.accentGreen)
                        .clickable { onOpenVoice() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Voice mode",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.background)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                colors.border,
                                Color.Transparent
                            )
                        )
                    )
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(58.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                destinations.forEach { destination ->
                    val selected = currentScreen == destination.route
                    val tint = if (selected) colors.accentGreen else colors.textMuted
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { onSelectScreen(destination.route) }
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                destination.icon,
                                contentDescription = destination.label,
                                tint = tint,
                                modifier = Modifier.size(19.dp)
                            )
                            if (destination.route == "Workspace" && workspaceBadge > 0) {
                                Box(
                                    Modifier
                                        .padding(start = 10.dp)
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(colors.accentSuccess),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        workspaceBadge.toString(),
                                        color = Color.White,
                                        fontFamily = type.mono,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            destination.label,
                            color = tint,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

private data class NavDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

/**
 * Themed overflow menu matching the new surface tokens.
 */
@Composable
fun KOverflowMenu(
    actions: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val colors = LocalKarenColors.current
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(colors.cardBackground)
    ) {
        actions.forEach { (label, icon, onClick) ->
            DropdownMenuItem(
                text = {
                    Text(
                        label,
                        color = colors.textSecondary,
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(icon, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                },
                onClick = {
                    onDismiss()
                    onClick()
                }
            )
        }
    }
}

/**
 * Section used by the navigation drawer: monospaced eyebrow then rows.
 */
@Composable
fun KDrawerSection(label: String, content: @Composable () -> Unit) {
    val colors = LocalKarenColors.current
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(
            label.uppercase(),
            color = colors.textMuted,
            fontFamily = LocalKarenType.current.mono,
            fontSize = 9.5.sp,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/** Drawer row: icon, title, optional chevron, azure rail when active. */
@Composable
fun KDrawerRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val colors = LocalKarenColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(CircleShape)
            .background(if (selected) colors.accentGreen.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = title,
            tint = if (selected) colors.accentGreen else colors.textMuted,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (selected) colors.textPrimary else colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = colors.textMuted,
                    fontFamily = LocalKarenType.current.mono,
                    fontSize = 10.sp,
                    maxLines = 1
                )
            }
        }
        AnimatedVisibility(visible = selected, enter = fadeIn(), exit = fadeOut()) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.accentGreen,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** Divider variant used inside sheets and popovers. */
@Composable
fun KSheetDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = LocalKarenColors.current.border,
        thickness = 1.dp
    )
}

/** Fills available space inside a Column without pulling in extra imports. */
@Composable
fun KSpacerFill() {
    Spacer(Modifier.fillMaxSize())
}
