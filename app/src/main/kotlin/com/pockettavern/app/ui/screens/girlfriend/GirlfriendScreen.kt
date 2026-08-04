package com.pockettavern.app.ui.screens.girlfriend

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettavern.app.ui.screens.chat.ChatScreen

/**
 * 小女友（M 计划）：独立的陪伴模块。
 * 复用酒馆聊天管线（生成/语音/Live2D/口型/相机），设置入口为独立全屏页面。
 * 左侧悬浮"心形"按钮打开 Live2D 舞台提示，右侧"齿轮"进入小女友设置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GirlfriendScreen(
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebugLog: () -> Unit,
    viewModel: GirlfriendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.ensureReady() }

    Box(modifier = Modifier.fillMaxSize()) {
        ChatScreen(
            characterAvatar = viewModel.girlfriendCard,
            onBack = onBack,
            onNavigateToDebugLog = onNavigateToDebugLog,
            forceHideThinking = true,
            isGirlfriendSurface = true,
            viewModel = viewModel.chat
        )

        if (uiState.ready) {
            // 设置入口：右侧中部悬浮齿轮
            FloatingActionButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(44.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "小女友设置")
            }
        } else {
            FloatingActionButton(
                onClick = {},
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(44.dp)
            ) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = "加载中",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
