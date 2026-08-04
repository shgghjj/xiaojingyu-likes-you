package com.pockettavern.app.ui.screens.openclaw

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenClawSettingsScreen(
    onBack: () -> Unit,
    viewModel: OpenClawSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenClaw 设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("什么是 OpenClaw？", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "OpenClaw 是在电脑/服务器上运行的智能体 Gateway。启用后，你可以让白夜把任务（打开应用、查日程、操作文件等）交给它执行，手机端只负责发任务和展示结果。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (uiState.loading) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator() }
                }
            } else {

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用 OpenClaw", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "关闭时聊天消息一律走普通 AI，不会尝试连接 Gateway",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.enabled,
                        onCheckedChange = viewModel::onEnabledChange
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = uiState.gatewayUrl,
                    onValueChange = viewModel::onGatewayUrlChange,
                    label = { Text("Gateway 地址") },
                    placeholder = { Text("ws://192.168.71.45:18789") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.enabled
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = uiState.token,
                    onValueChange = viewModel::onTokenChange,
                    label = { Text("Gateway Token") },
                    placeholder = { Text("在 Gateway 配对/设置中生成") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.enabled
                )
                Text(
                    "Token 仅保存在本机加密存储中，不会写入聊天记录或日志。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("任务超时（秒）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = uiState.timeoutSeconds.toString(),
                        onValueChange = { viewModel.onTimeoutChange(it.toIntOrNull() ?: uiState.timeoutSeconds) },
                        singleLine = true,
                        enabled = uiState.enabled,
                        modifier = Modifier
                            .fillMaxWidth(0.35f)
                            .height(60.dp)
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动识别工具请求", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "聊天中出现“打开/音量/查询…”等关键词时自动交给 OpenClaw",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.autoInvoke,
                        onCheckedChange = viewModel::onAutoInvokeChange,
                        enabled = uiState.enabled
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("所有任务都要确认", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "开启后每次执行前弹窗确认（高风险操作始终确认）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.confirmAll,
                        onCheckedChange = viewModel::onConfirmAllChange,
                        enabled = uiState.enabled
                    )
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = viewModel::save,
                        enabled = uiState.enabled && !uiState.saving,
                        modifier = Modifier.weight(1f)
                    ) { Text("保存") }
                    OutlinedButton(
                        onClick = viewModel::testConnection,
                        enabled = uiState.enabled && !uiState.testing,
                        modifier = Modifier.weight(1f)
                    ) { Text("测试连接") }
                }
                uiState.saveMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                if (uiState.testing) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp),
                            strokeWidth = 2.dp
                        )
                        Text("  测试中…")
                    }
                }
                uiState.testResult?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = if (uiState.testOk == true) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("调试日志（无敏感信息）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearDebugLogs) { Text("清空") }
                }
            }

            if (uiState.debugLogs.isEmpty()) {
                item {
                    Text(
                        "暂无记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.debugLogs.reversed()) { log ->
                    Text(
                        "[${log.status}] task=${log.taskId.ifBlank { "-" }}" +
                            (log.durationMs?.let { "  ${it}ms" } ?: "") +
                            (log.errorType?.let { "  err=$it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
            }
        }
    }
}
