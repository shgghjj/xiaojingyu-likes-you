package com.pockettavern.app.ui.screens.main

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.pockettavern.app.R
import java.io.File
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettavern.app.ui.audio.ThemeAudioManager
import com.pockettavern.app.ui.components.ConnectionStatusBar
import com.pockettavern.app.ui.components.ArknightsBackground
import com.pockettavern.app.ui.theme.ArkCyan
import com.pockettavern.app.ui.theme.BackgroundScaleMode
import com.pockettavern.app.ui.theme.LocalThemeAssets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToGirlfriend: () -> Unit = {},
    onNavigateToCharacters: () -> Unit,
    onNavigateToRecentChats: () -> Unit,
    onNavigateToCreateCharacter: () -> Unit,
    onNavigateToStories: () -> Unit = {},   // V12: always visible; empty-state prompts import
    onNavigateToCharaVault: () -> Unit,
    onNavigateToRisuRealm: () -> Unit = {},
    onNavigateToBotBooru: () -> Unit = {},
    onNavigateToSettings: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToExtensionPanel: (String) -> Unit = {},
    themeAudioManager: ThemeAudioManager? = null,
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val assets = LocalThemeAssets.current

    // Theme audio: start/stop based on audioPath
    LaunchedEffect(assets.audioPath) {
        if (assets.audioPath != null && themeAudioManager != null) {
            themeAudioManager.play(assets.audioPath, assets.audioLoop)
        } else {
            themeAudioManager?.stop()
        }
    }

    // Pause/resume on lifecycle events
    if (themeAudioManager != null && assets.audioPath != null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> themeAudioManager.pause()
                    Lifecycle.Event.ON_RESUME -> themeAudioManager.resume()
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                themeAudioManager.pause()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    // Theme background image (behind particles)
    assets.backgroundImagePath?.let { path ->
        val scaleMode = when (assets.backgroundScaleMode) {
            BackgroundScaleMode.FILL -> ContentScale.Crop
            BackgroundScaleMode.FIT -> ContentScale.Fit
            BackgroundScaleMode.STRETCH -> ContentScale.FillBounds
        }
        AsyncImage(
            model = File(path),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(assets.backgroundOpacity),
            contentScale = scaleMode
        )
    }

    // 方舟战术网格背景（替代普通粒子层，杜绝瞬时解码黑屏）
    ArknightsBackground()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // 方舟风顶栏：切角底条 + 战术标题区
            Surface(
                color = Color(0xCC0B0B0D),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Logo — 小鲸鱼喜欢你（固定大小，避免 AsyncImage 大图瞬时解码黑屏）
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF141416))
                            .border(1.dp, Color(0xFF2C2C30)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (assets.logoImagePath != null) {
                            AsyncImage(
                                model = File(assets.logoImagePath),
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.xiaojingyu_portrait),
                                contentDescription = stringResource(R.string.app_name),
                                modifier = Modifier.size(40.dp),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.ai_companion_home_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFFF2F2F4)
                        )
                        Text(
                            text = stringResource(R.string.ai_companion_home_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF9A9AA0),
                            letterSpacing = 2.sp
                        )
                    }

                    // 右侧状态徽标：本地终端 · 在线
                    Surface(
                        shape = RoundedCornerShape(2.dp),
                        color = Color(0xFF141416),
                        border = BorderStroke(1.dp, Color(0xFF3A3A3E))
                    ) {
                        Text(
                            text = "●",
                            color = Color(0xFFF2F2F4),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            // 顶部切割线：硬切黑条分隔
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.35f),
                                Color.Transparent
                            )
                        )
                    )
            )
        },
        bottomBar = {
            ConnectionStatusBar(
                isConnected = uiState.isConnected,
                statusText = stringResource(R.string.local_mode)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Centered content - bigger cards with more spacing
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "—— 连接终端 ——",
                    style = MaterialTheme.typography.labelSmall,
                    color = ArkCyan.copy(alpha = 0.6f),
                    letterSpacing = 4.sp
                )
                Spacer(modifier = Modifier.height(16.dp))

                // 小女友（M 计划）：独立陪伴模块，与酒馆完全分开
                ArkNavigationCard(
                    icon = Icons.Default.Favorite,
                    title = "小女友",
                    description = "她只记得你们之间的事，会随相处慢慢长大",
                    accent = Color(0xFFF2F2F4),
                    tag = "SECRET",
                    onClick = onNavigateToGirlfriend
                )

                Spacer(modifier = Modifier.height(14.dp))

                ArkNavigationCard(
                    icon = Icons.Default.People,
                    title = stringResource(R.string.characters),
                    description = stringResource(R.string.browse_and_chat_with_your_characters),
                    accent = Color(0xFFF2F2F4),
                    tag = "ROSTER",
                    onClick = onNavigateToCharacters
                )

                Spacer(modifier = Modifier.height(14.dp))

                ArkNavigationCard(
                    icon = Icons.Default.History,
                    title = stringResource(R.string.recent_chats),
                    description = stringResource(R.string.continue_recent_conversations),
                    accent = Color(0xFFF2F2F4),
                    tag = "LOG",
                    onClick = onNavigateToRecentChats
                )

                Spacer(modifier = Modifier.height(14.dp))

                ArkNavigationCard(
                    icon = Icons.Default.Add,
                    title = stringResource(R.string.create_character_home),
                    description = stringResource(R.string.design_new_character),
                    accent = Color(0xFFF2F2F4),
                    tag = "CREATE",
                    onClick = onNavigateToCreateCharacter
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Stories (native ensemble) — private/dev feature, gated out of the public release (STORIES_ENABLED).
                if (com.pockettavern.app.BuildConfig.STORIES_ENABLED) {
                    ArkNavigationCard(
                        icon = Icons.Default.People,
                        title = stringResource(R.string.stories),
                        description = stringResource(R.string.multi_character_ensembles),
                        accent = Color(0xFFF2F2F4),
                        tag = "ENSEMBLE",
                        onClick = onNavigateToStories
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Card search — CharaVault or RisuRealm
                ArkSearchCardsCard(
                    accent = Color(0xFFF2F2F4),
                    onNavigateToCharaVault = onNavigateToCharaVault,
                    onNavigateToRisuRealm = onNavigateToRisuRealm,
                    onNavigateToBotBooru = onNavigateToBotBooru
                )

                Spacer(modifier = Modifier.height(14.dp))

                ArkNavigationCard(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.settings),
                    description = stringResource(R.string.configure_ai_and_preferences),
                    accent = Color(0xFFF2F2F4),
                    tag = "CFG",
                    onClick = onNavigateToSettings
                )

                // Extension panels registered via PT.registerPanel() or browser.html detection
                uiState.panelRegistrations.values.forEach { panel ->
                    Spacer(modifier = Modifier.height(14.dp))
                    ArkNavigationCard(
                        icon = Icons.Default.Extension,
                        title = panel.title,
                        description = stringResource(R.string.extension_panel),
                        accent = Color(0xFFF2F2F4),
                        tag = "EXT",
                        onClick = { onNavigateToExtensionPanel(panel.extensionId) }
                    )
                }
            }
        }
    }

    } // Box
}

@Composable
private fun ArkSearchCardsCard(
    accent: Color,
    onNavigateToCharaVault: () -> Unit,
    onNavigateToRisuRealm: () -> Unit,
    onNavigateToBotBooru: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val options = listOf("CharaVault", "RisuRealm", "BotBooru")

    val onNavigate = when (selectedIndex) {
        1    -> onNavigateToRisuRealm
        2    -> onNavigateToBotBooru
        else -> onNavigateToCharaVault
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.45f),
                        Color(0xFF3A3A3E).copy(alpha = 0.8f)
                    )
                ),
                shape = RoundedCornerShape(2.dp)
            )
            .clickable(onClick = onNavigate),
        color = Color(0xE6141416),
        shape = RoundedCornerShape(2.dp)
    ) {
        // 顶部扫描高光条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 方形图标位（战术面板）
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF1D1D20))
                    .border(1.dp, accent.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.search_cards_2),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFF2F2F4)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box {
                    Row(
                        modifier = Modifier.clickable { expanded = true },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = options[selectedIndex],
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF9A9AA0),
                            letterSpacing = 1.sp
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = Color(0xFF9A9AA0),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        options.forEachIndexed { index, label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedIndex = index
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }

            // 右侧斜箭头▸
            Text(
                text = "▸",
                style = MaterialTheme.typography.headlineSmall,
                color = Color(0xFFB8B8BC),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun ArkNavigationCard(
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    val arkCyan = ArkCyan
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(2.dp))
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        arkCyan.copy(alpha = 0.35f),
                        Color(0xFF252530).copy(alpha = 0.8f)
                    )
                ),
                shape = RoundedCornerShape(2.dp)
            )
            .clickable(onClick = onClick),
        color = Color(0xE6111115),
        shape = RoundedCornerShape(2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, arkCyan.copy(alpha = 0.5f), Color.Transparent)
                    )
                )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF14141A))
                    .border(1.dp, arkCyan.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = arkCyan.copy(alpha = 0.8f),
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE8E8EC)
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF7E7E8A),
                    maxLines = 1
                )
            }

            Surface(
                shape = RoundedCornerShape(1.dp),
                color = Color(0xFF1A1A24),
                border = BorderStroke(1.dp, arkCyan.copy(alpha = 0.3f))
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = arkCyan.copy(alpha = 0.7f),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
