package com.karen.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.karen.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun KarenSplashScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "splash")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "logo_pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.Image(
                painter = painterResource(id = R.drawable.ic_karen),
                contentDescription = "Karen logo",
                modifier = Modifier
                    .size(120.dp)
                    .scale(pulse)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(28.dp))
            )
            Spacer(Modifier.height(20.dp))
            Text("Karen", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Sovereign on-device AI", color = Color(0xFF8E8E93), fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = Color(0xFF38BDF8), strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
fun KarenApp() {
    var showSplash by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1800)
        showSplash = false
    }
    if (showSplash) {
        KarenSplashScreen()
        return
    }
    KarenTheme {
        val colors = LocalKarenColors.current
        val coroutineScope = rememberCoroutineScope()
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

        var currentScreen by remember { mutableStateOf("Home") }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = colors.drawerBackground,
                    drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                ) {
                    ChatGPTDrawerContent(
                        currentScreen = currentScreen,
                        onNavigate = { screen ->
                            currentScreen = screen
                        },
                        onCloseDrawer = {
                            coroutineScope.launch { drawerState.close() }
                        }
                    )
                }
            }
        ) {
            // If in Voice mode, show immersive full screen without bottom bar
            if (currentScreen == "Voice") {
                VoiceScreen(
                    onClose = { currentScreen = "Chat" }
                )
            } else {
                Scaffold(
                    bottomBar = {
                        if (currentScreen != "Chat") {
                            MinimalistBottomNav(
                                currentScreen = currentScreen,
                                onSelectScreen = { currentScreen = it }
                            )
                        }
                    },
                    containerColor = colors.background
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        when (currentScreen) {
                            "Home" -> HomeScreen(
                                onNavigate = { currentScreen = it }
                            )
                            "Chat" -> ChatScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToVoice = { currentScreen = "Voice" },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "Workspace" -> WorkspaceScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "Files" -> FilesScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigate = { currentScreen = it }
                            )
                            "Memory" -> MemoryScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "ModelManager" -> ModelManagerScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "Performance" -> PerformanceScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "Hardware" -> HardwareScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "StudyBook" -> StudyBookScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            else -> ChatScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToVoice = { currentScreen = "Voice" },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Minimalist Bottom Bar with subtle pill indicators and direct Voice mode access.
 */
@Composable
private fun MinimalistBottomNav(
    currentScreen: String,
    onSelectScreen: (String) -> Unit
) {
    val colors = LocalKarenColors.current

    val navItems = listOf(
        Triple("Home", Icons.Default.Home, "Home"),
        Triple("Chat", Icons.Default.ChatBubble, "Chat"),
        Triple("Voice", Icons.Default.GraphicEq, "Voice"),
        Triple("Canvas", Icons.Default.Terminal, "Workspace"),
        Triple("Files", Icons.Default.Folder, "Files")
    )

    Surface(
        color = colors.background,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, colors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            navItems.forEach { (label, icon, route) ->
                val isSelected = currentScreen == route
                val isVoice = route == "Voice"

                if (isVoice) {
                    // Elevated pill button for voice
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(if (colors.isDark) Color(0xFF1C1C1E) else Color(0xFFE9E9EB))
                            .border(1.dp, colors.border, CircleShape)
                            .clickable { onSelectScreen(route) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectScreen(route) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (isSelected) colors.accentGreen else colors.textMuted,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = label,
                            color = if (isSelected) colors.accentGreen else colors.textMuted,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
