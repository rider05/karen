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
import com.karen.WakeWordNavigator
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
fun KarenApp(startScreen: String = "Home", shakeSignal: Int = 0) {
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

        var currentScreen by remember(startScreen) { mutableStateOf(startScreen) }
        var showModelSheet by remember { mutableStateOf(false) }
        var showProfileSheet by remember { mutableStateOf(false) }
        // Chat history plumbing: bump to refresh the drawer list, openChatId to
        // load a saved conversation, newChatSignal for drawer "New chat" in Chat.
        var historyVersion by remember { mutableStateOf(0) }
        var openChatId by remember { mutableStateOf<String?>(null) }
        var newChatSignal by remember { mutableStateOf(0) }

        // Shake gesture from MainActivity: close any drawer and land on Karen
        // Settings. The activity only emits a signal after three deliberate
        // shakes, so accidental bumps do nothing.
        LaunchedEffect(shakeSignal) {
            if (shakeSignal > 0 && currentScreen != "Settings") {
                coroutineScope.launch { drawerState.close() }
                currentScreen = "Settings"
            }
        }

        // Recently-opened feed (Home card). Chat and Canvas carry their own
        // entries — only other screens are recorded here.
        LaunchedEffect(currentScreen) {
            val label = when (currentScreen) {
                "Home", "Chat", "Workspace" -> null
                "Files" -> "Knowledge Vault"
                "Memory" -> "Memory"
                "ModelManager" -> "Model Manager"
                "Nodes" -> "Network Nodes"
                "Performance", "Hardware" -> "Performance"
                "StudyBook" -> "Study Book"
                "Flowchart" -> "Intelligent Flow"
                "Voice" -> "Live Mode"
                "Settings" -> "Settings"
                else -> currentScreen
            }
            if (label != null) {
                RecentActivity.record(context, RecentActivity.TYPE_SCREEN, currentScreen, label)
            }
        }

        // Wake-word trigger while the app is alive: jump to Live mode.
        LaunchedEffect(Unit) {
            var first = true
            WakeWordNavigator.signal.collect { ts ->
                if (first) {
                    first = false
                    if (ts == 0L) return@collect
                }
                if (ts > 0L) currentScreen = "Voice"
            }
        }

        // Section eyebrow shown under the wordmark, mirroring the design comps.
        val headerEyebrow = when (currentScreen) {
            "Home" -> "Dashboard"
            "Chat" -> "Dialogue"
            "Workspace" -> "Canvas"
            "Files" -> "Vault"
            "Memory" -> "Memory"
            "ModelManager" -> "Models"
            "Nodes" -> "Network"
            "Performance" -> "Performance"
            "Hardware" -> "Performance"
            "StudyBook" -> "Study"
            "Flowchart" -> "Flow"
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
            // If in Live mode, show immersive full screen without bottom bar
            if (currentScreen == "Voice") {
                VoiceScreen(
                    onClose = { currentScreen = "Home" },
                    onOpenModelManager = { currentScreen = "ModelManager" }
                )
            } else {
                Scaffold(
                    bottomBar = {
                        if (currentScreen != "Chat") {
                            KNavigationDock(
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
                            .imePadding()
                    ) {
                        when (currentScreen) {
                            "Home" -> HomeScreen(
                                onNavigate = { currentScreen = it },
                                onOpenSettings = { currentScreen = "Settings" },
                                onOpenConversation = { id ->
                                    openChatId = id
                                    currentScreen = "Chat"
                                }
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
                                onNavigateToHome = { currentScreen = "Home" },
                                onNavigateToModelManager = { currentScreen = "ModelManager" }
                            )
                            "Files" -> FilesScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigate = { currentScreen = it },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "Memory" -> MemoryScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "ModelManager" -> ModelManagerScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "Nodes" -> NodesScreen(
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "Performance" -> PerformanceScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "Hardware" -> HardwareScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "StudyBook" -> StudyBookScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "Flowchart" -> FlowchartScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "ModelImport" -> ModelImportScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "ModelExport" -> ModelExportScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "MemoryTransfer" -> MemoryTransferScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "BackupPackage" -> BackupPackageScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "StorageManager" -> StorageManagerScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
                            )
                            "Migration" -> MigrationScreen(
                                onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                                onNavigateToHome = { currentScreen = "Home" }
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


