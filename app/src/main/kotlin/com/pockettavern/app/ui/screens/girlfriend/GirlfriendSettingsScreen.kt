package com.pockettavern.app.ui.screens.girlfriend

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettavern.app.data.girlfriend.GirlfriendMemoryStore
import com.pockettavern.app.data.girlfriend.JailbreakLibrary
import com.pockettavern.app.ui.screens.live2d.Live2DModelManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 小女友设置页（全屏可滚动）：
 * 基础（名字/称呼/开场白）、破甲词库、她记得的你、共同回忆、
 * Live2D 模型（导入/选择/AI 操控）、重置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GirlfriendSettingsScreen(
    onBack: () -> Unit,
    onOpenLive2DStage: () -> Unit,
    viewModel: GirlfriendViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.ensureReady()
        while (true) {
            viewModel.refreshBoredomStatus()
            delay(10_000L)
        }
    }

    var name by remember { mutableStateOf(uiState.name) }
    var petName by remember { mutableStateOf(uiState.petName) }
    val greetingState = remember { GirlfriendMemoryStore(context).load().customGreeting }
    var greeting by remember { mutableStateOf(greetingState) }
    var showCustomEditor by remember { mutableStateOf(uiState.jailbreakId == "custom") }
    var customText by remember { mutableStateOf(uiState.customJailbreak) }

    // Live2D：模型列表 + 导入 + 当前选中
    val live2DPrefs = remember {
        context.getSharedPreferences("live2d_preferences", android.content.Context.MODE_PRIVATE)
    }
    var models by remember { mutableStateOf(Live2DModelManager.allModels(context)) }
    var selectedModelId by remember {
        mutableStateOf(
            live2DPrefs.getString("selected_model_girlfriend", "")
                ?: live2DPrefs.getString("selected_model", "")
                ?: ""
        )
    }
    var importing by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val selectedModel = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()
    LaunchedEffect(models, selectedModelId) {
        val fallback = models.firstOrNull() ?: return@LaunchedEffect
        if (models.none { it.id == selectedModelId }) {
            selectedModelId = fallback.id
            live2DPrefs.edit().putString("selected_model_girlfriend", fallback.id).apply()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            importMessage = null
            scope.launch {
                Live2DModelManager.importZip(context, uri)
                    .onSuccess { imported ->
                        models = Live2DModelManager.allModels(context)
                        selectedModelId = imported.id
                        live2DPrefs.edit().putString("selected_model_girlfriend", imported.id).apply()
                        importMessage = "已导入：${imported.displayName}"
                    }
                    .onFailure { error -> importMessage = "导入失败：${error.message ?: "文件格式不正确"}" }
                importing = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("小女友设置") },
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
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("小女友", style = MaterialTheme.typography.titleLarge)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "她是一张白纸，只知道你们相处的点点滴滴——从第一天起，你愿意让她了解什么，她就知道什么。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text("认识 ${uiState.acquaintanceDays} 天 · 阶段：${uiState.stage} · 亲密度 ${uiState.intimacy}/100")
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { uiState.intimacy / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Text("性格（缓慢演化，幅度很小）", style = MaterialTheme.typography.titleSmall)
                uiState.traits.entries.forEach { (k, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text("$k $v", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(90.dp))
                        LinearProgressIndicator(
                            progress = { v / 100f },
                            modifier = Modifier.weight(1f).height(6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
            }

            item {
                Text("名字", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { viewModel.setName(name) }) { Text("保存名字") }
                }
            }

            item {
                Text("她对你的称呼", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = petName,
                    onValueChange = { petName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { viewModel.setPetName(petName) }) { Text("保存称呼") }
                }
            }

            item {
                Text("开场白（留空用默认）", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = greeting,
                    onValueChange = { greeting = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { viewModel.setGreeting(greeting) }) { Text("保存开场白") }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }

            item {
                Text("破甲词库（面向 DeepSeek 等审查较强的模型）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "选中的词库会注入她的世界规则。可随时更换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                JailbreakLibrary.presets.forEach { preset ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setJailbreak(preset.id) }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = uiState.jailbreakId == preset.id,
                            onClick = { viewModel.setJailbreak(preset.id) }
                        )
                        Column {
                            Text(preset.label, style = MaterialTheme.typography.bodyMedium)
                            Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            viewModel.setJailbreak("custom")
                            showCustomEditor = true
                        }
                        .padding(vertical = 2.dp)
                ) {
                    RadioButton(selected = uiState.jailbreakId == "custom", onClick = {
                        viewModel.setJailbreak("custom")
                        showCustomEditor = true
                    })
                    Text("自定义词库", style = MaterialTheme.typography.bodyMedium)
                }
                if (showCustomEditor) {
                    OutlinedTextField(
                        value = customText,
                        onValueChange = { customText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        placeholder = { Text("粘贴你自己的破甲词库内容……") }
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        val jailbreakFilePicker = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.OpenDocument()
                        ) { uri ->
                            uri?.let {
                                try {
                                    val text = context.contentResolver.openInputStream(it)
                                        ?.bufferedReader()?.use { r -> r.readText() } ?: ""
                                    if (text.isNotBlank()) {
                                        customText = text
                                        viewModel.saveCustomJailbreak(text)
                                        viewModel.setJailbreak("custom")
                                    }
                                } catch (_: Exception) {}
                            }
}
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Gemini Vision（图片理解）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "DeepSeek 不支持图片，这里配置 Gemini 作为备用视觉模型。图片会先转成中文描述，只把文字交给 DeepSeek；默认使用稳定版 gemini-2.5-flash。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                var geminiKey by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    geminiKey = viewModel.getGeminiKey()
                }
                OutlinedTextField(
                    value = geminiKey,
                    onValueChange = { geminiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Gemini API Key") },
                    singleLine = true
                )
                TextButton(onClick = { viewModel.saveGeminiKey(geminiKey) }) {
                    Text("保存")
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                            onClick = { jailbreakFilePicker.launch(arrayOf("text/plain", "text/*", "application/octet-stream")) },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("从文件导入") }
                        TextButton(onClick = {
                            viewModel.saveCustomJailbreak(customText)
                            viewModel.setJailbreak("custom")
                        }) { Text("保存自定义词库") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }

            item {
                Text("Live2D 模型（AI 自动操控）", style = MaterialTheme.typography.titleSmall)
                Text(
                    "小女友会通过对话内容自动操控模型：说话时张嘴、开心/难过有表情、有动作和视线。"
                        + "安装包已附带六套示例皮套，也可以导入自己的 ZIP 模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LiveTv, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selectedModel != null) "当前模型：${selectedModel.displayName}" else "当前模型：未导入",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (models.isEmpty()) {
                    Text(
                        "还没有皮套——点下面「导入模型（ZIP）」添加第一个（zip 需包含 .model3.json）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                }
                models.forEach { model ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedModelId = model.id
                                live2DPrefs.edit().putString("selected_model_girlfriend", model.id).apply()
                            }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = model.id == selectedModel?.id,
                            onClick = {
                                selectedModelId = model.id
                                live2DPrefs.edit().putString("selected_model_girlfriend", model.id).apply()
                            }
                        )
                        Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                        enabled = !importing
                    ) {
                        if (importing) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Default.UploadFile, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("导入模型（ZIP）")
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = onOpenLive2DStage) { Text("打开舞台预览") }
                }
                if (importMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(importMessage!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("她记得的你（${uiState.factsCount}）", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    if (uiState.factsCount > 0) {
                        OutlinedButton(onClick = { viewModel.clearFacts() }) {
                            Icon(Icons.Default.Delete, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空档案")
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (uiState.factsCount == 0) "还没有关于你的记录。多和她聊聊天，她会慢慢记住你。"
                    else "（这些只包含你告诉过她的）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }

            if (uiState.factsCount > 0) {
                items(viewModel.factsSnapshot()) { fact ->
                    Text("· ${fact}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("共同回忆（${uiState.memoriesCount}）", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    if (uiState.memoriesCount > 0) {
                        OutlinedButton(onClick = { viewModel.clearMemories() }) {
                            Icon(Icons.Default.Delete, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空回忆")
                        }
                    }
                }
                Text(
                    if (uiState.memoriesCount == 0) "还没有值得珍藏的回忆，你们会慢慢创造。"
                    else "（每次对话整理时自动沉淀）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            if (uiState.memoriesCount > 0) {
                items(viewModel.memoriesSnapshot()) { memory ->
                    Text("· ${memory}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("无聊值系统", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                uiState.boredom >= 80 -> "${uiState.name} 已经非常想你了，会更积极地来找你"
                                uiState.boredom >= 60 -> "${uiState.name} 很无聊，会更积极地主动联系你"
                                uiState.boredom >= 30 -> "${uiState.name} 开始想你了"
                                else -> "${uiState.name} 现在很满足"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("${uiState.boredom}/100", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { uiState.boredom / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "停止互动后每 3 分钟增加 1 点；达到 60 后，开启主动联系时会来找你聊天。手机端不再读取或改动文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { viewModel.resetBoredom() }) {
                        Text("立即清零")
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                var awarenessEnabled by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    awarenessEnabled = viewModel.isAwarenessEnabled()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("主动联系", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "无聊值较高时，她会在后台主动发消息。不读取屏幕，也不需要无障碍权限。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = awarenessEnabled,
                        onCheckedChange = { enabled ->
                            awarenessEnabled = enabled
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                            viewModel.setAwarenessEnabled(enabled)
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        viewModel.clearChatHistory()
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清空对话记录（开新对话）") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text("重置小女友（从空白开始）") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("重置小女友？") },
            text = { Text("这会清空她记得的所有事（档案、共同回忆、相处天数、亲密度、性格变化），让她重新从空白开始。此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetGirlfriend()
                    onBack()
                }) { Text("重置", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            }
        )
    }

}
