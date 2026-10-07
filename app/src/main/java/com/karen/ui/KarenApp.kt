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
        val context = androidx.compose.ui.platform.LocalContext.current
        var onboarded by remember { mutableStateOf(UserPrefs.isOnboarded(context)) }

        var currentScreen by remember { mutableStateOf("Home") }
        var showModelSheet by remember { mutableStateOf(false) }
        var showProfileSheet by remember { mutableStateOf(false) }
        // Chat history plumbing: bump to refresh the drawer list, openChatId to
        // load a saved conversation, newChatSignal for drawer "New chat" in Chat.
        var historyVersion by remember { mutableStateOf(0) }
        var openChatId by remember { mutableStateOf<String?>(null) }
        var newChatSignal by remember { mutableStateOf(0) }

        // Section eyebrow shown under the wordmark, mirroring the design comps.
        val headerEyebrow = when (currentScreen) {
            "Home" -> "Dashboard"
            "Chat" -> "Dialogue"
            "Workspace" -> "Canvas"
            "Files" -> "Vault"
            "Memory" -> "Memory"
            "ModelManager" -> "Models"
            "Performance" -> "Performance"
            "Hardware" -> "Performance"
            "StudyBook" -> "Study"
            "Settings" -> "Settings"
            else -> "Dashboard"
        }

        if (!onboarded) {
            OnboardingScreen(onDone = { onboarded = true })
            return@KarenTheme
        }

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
                        historyVersion = historyVersion,
                        onNewChat = {
                            newChatSignal++
                            currentScreen = "Chat"
                        },
                        onOpenConversation = { id ->
                            openChatId = id
                            currentScreen = "Chat"
                        },
                        onDeleteConversation = { id ->
                            ChatHistoryStore.deleteConversation(context, id)
                            historyVersion++
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
                            KNavigationDock(
                                currentScreen = currentScreen,
                                onSelectScreen = { currentScreen = it },
                                onOpenVoice = { currentScreen = "Voice" }
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
                                onNavigate = { currentScreen = it },
                                onOpenSettings = { currentScreen = "Settings" }
                            )
                            "Settings" -> SettingsScreen(onBack = { currentScreen = "Home" })
                            "Chat" -> ChatScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToVoice = { currentScreen = "Voice" },
                                onNavigateToHome = { currentScreen = "Home" },
                                onNavigateToModelManager = { currentScreen = "ModelManager" },
                                openConversationId = openChatId,
                                newChatSignal = newChatSignal,
                                onConversationOpened = { openChatId = null },
                                onHistoryChanged = { historyVersion++ }
                            )
                            "Workspace" -> WorkspaceScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "Files" -> FilesScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigate = { currentScreen = it },
                                onNavigateToHome = { currentScreen = "Home" }
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
                            "ModelImport" -> ModelImportScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "ModelExport" -> ModelExportScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "MemoryTransfer" -> MemoryTransferScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "BackupPackage" -> BackupPackageScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "StorageManager" -> StorageManagerScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            "Migration" -> MigrationScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } }
                            )
                            else -> ChatScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToVoice = { currentScreen = "Voice" },
                                onNavigateToHome = { currentScreen = "Home" },
                                onNavigateToModelManager = { currentScreen = "ModelManager" },
                                openConversationId = openChatId,
                                newChatSignal = newChatSignal,
                                onConversationOpened = { openChatId = null },
                                onHistoryChanged = { historyVersion++ }
                            )
                        }
                    }
                }
            }
        }
    }
}


