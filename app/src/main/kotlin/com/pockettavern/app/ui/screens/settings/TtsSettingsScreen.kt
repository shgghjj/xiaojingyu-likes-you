package com.pockettavern.app.ui.screens.settings

import com.pockettavern.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import com.pockettavern.app.ui.audio.TtsVoice
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(
    onBack: () -> Unit,
    viewModel: TtsSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config = uiState.config

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.text_to_speech)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Enable TTS ──────────────────────────────────────────────
            item {
                SectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stringResource(R.string.enable_tts), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.speak_chat_messages_aloud),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = config.enabled,
                            onCheckedChange = { viewModel.updateEnabled(it) }
                        )
                    }
                }
            }

            if (config.enabled) {
                // ── Provider ────────────────────────────────────────────
                item {
                    SectionCard {
                        Text(stringResource(R.string.provider),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = config.provider == "system",
                                onClick = { viewModel.updateProvider("system") },
                                label = { Text(stringResource(R.string.system_tts)) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = config.provider == "openai",
                                onClick = { viewModel.updateProvider("openai") },
                                label = { Text(stringResource(R.string.openai_compatible)) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ── System TTS Engine & Voice Selector ─────────────────
                if (config.provider == "system") {
                    item {
                        SectionCard {
                            Text(stringResource(R.string.system_tts_engine),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.choose_which_installed_tts_engine_to_use),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            EngineSelector(
                                selectedEngine = config.systemEngine,
                                engines = uiState.availableEngines,
                                onEngineSelected = { viewModel.updateSystemEngine(it) }
                            )

                            if (config.systemEngine.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                VoiceSelector(
                                    label = "默认语音",
                                    selectedVoice = config.systemVoice,
                                    voices = uiState.systemVoices,
                                    onVoiceSelected = { viewModel.updateSystemVoice(it) },
                                    onRefresh = {
                                        viewModel.updateSystemEngine(config.systemEngine)
                                    }
                                )
                            }
                        }
                    }
                }

                // ── OpenAI Settings ─────────────────────────────────────
                if (config.provider == "openai") {
                    item {
                        SectionCard {
                            Text(stringResource(R.string.openai_compatible_api),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(stringResource(R.string.works_with_openai_alltalk_xtts_kokoro_and_oth),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = config.openAiUrl,
                                onValueChange = { viewModel.updateOpenAiUrl(it) },
                                label = { Text(stringResource(R.string.api_url)) },
                                placeholder = { Text(stringResource(R.string.http_192_168_1_100_8000)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = config.openAiKey,
                                onValueChange = { viewModel.updateOpenAiKey(it) },
                                label = { Text(stringResource(R.string.api_key_optional)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation()
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = config.openAiModel,
                                onValueChange = { viewModel.updateOpenAiModel(it) },
                                label = { Text(stringResource(R.string.model)) },
                                placeholder = { Text(stringResource(R.string.tts_1)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            // Voice selector — dynamically fetched from server
                            VoiceSelector(
                                label = "默认语音",
                                selectedVoice = config.openAiVoice,
                                voices = uiState.voices,
                                onVoiceSelected = { viewModel.updateOpenAiVoice(it) },
                                onRefresh = { viewModel.refreshVoices() }
                            )
                        }
                    }
                }

                // ── Playback Settings ───────────────────────────────────
                item {
                    SectionCard {
                        Text(stringResource(R.string.playback),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Auto-play toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(stringResource(R.string.auto_play), style = MaterialTheme.typography.bodyMedium)
                                Text(stringResource(R.string.automatically_speak_new_ai_messages),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.autoPlay,
                                onCheckedChange = { viewModel.updateAutoPlay(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Speed slider
                        Text(stringResource(R.string.speed_x, "%.1f".format(config.speed)), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = config.speed,
                            onValueChange = { viewModel.updateSpeed(it) },
                            valueRange = 0.5f..2.0f,
                            steps = 5
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Volume slider（新：OpenAI TTS 音量）
                        Text("音量 ${"%.0f".format(config.volume * 100)}%", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = config.volume,
                            onValueChange = { viewModel.updateVolume(it) },
                            valueRange = 0.2f..1.0f,
                            steps = 7
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Tap-to-interrupt（点击角色打断朗读）
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("点击打断", style = MaterialTheme.typography.bodyMedium)
                                Text("朗读时点击 Live2D 角色立即停止声音与口型",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.tapToInterrupt,
                                onCheckedChange = { viewModel.updateTapToInterrupt(it) }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // OpenAI 风格提示词（gpt-4o-mini-tts）
                        OutlinedTextField(
                            value = config.stylePrompt,
                            onValueChange = { viewModel.updateStylePrompt(it) },
                            label = { Text("风格提示词（可选）") },
                            placeholder = { Text("例如：温柔体贴，语速偏慢，带一点俏皮") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("仅 OpenAI 兼容 TTS 生效；回复自带 voice.style 时优先使用回复指定的风格。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 系统 TTS 降级
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("在线 TTS 失败时回退系统 TTS", style = MaterialTheme.typography.bodyMedium)
                                Text("网络不可用时仍能朗读，避免静默失败",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = config.systemTtsFallback,
                                onCheckedChange = { viewModel.updateSystemTtsFallback(it) }
                            )
                        }
                    }
                }

                // ── 语音输入 ─────────────────────────────────────────────
                item {
                    SectionCard {
                        Text("语音输入",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("按住输入框旁的麦克风说话，松开发送文字（需要录音权限）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("启用语音输入", style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(
                                checked = config.voiceInputEnabled,
                                onCheckedChange = { viewModel.updateVoiceInputEnabled(it) }
                            )
                        }
                    }
                }

                // ── Text Filter ─────────────────────────────────────────
                item {
                    SectionCard {
                        Text(stringResource(R.string.text_filter),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.choose_what_text_to_speak),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        val filters = listOf(
                                "all" to "全部文字",
                                "quotes_only" to "仅朗读引号内对话",
                                "no_asterisks" to "不朗读星号内动作"
                        )
                        filters.forEach { (mode, label) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = config.filterMode == mode,
                                    onClick = { viewModel.updateFilterMode(mode) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                // ── Test ────────────────────────────────────────────────
                item {
                    SectionCard {
                        Text(stringResource(R.string.test),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.testVoice() },
                                enabled = !uiState.isTesting
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.test_voice))
                            }
                            if (uiState.isTesting) {
                                OutlinedButton(
                                    onClick = { viewModel.stopTest() }
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(R.string.stop))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSelector(
    label: String,
    selectedVoice: String,
    voices: List<TtsVoice>,
    onVoiceSelected: (String) -> Unit,
    onRefresh: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Text(label, style = MaterialTheme.typography.bodyMedium)
    Spacer(modifier = Modifier.height(4.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = if (selectedVoice.isEmpty()) "默认（跟随引擎）" 
                       else voices.find { it.id == selectedVoice }?.name ?: selectedVoice,
                onValueChange = { },
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                label = { Text(stringResource(R.string.voice)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                singleLine = true
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (voices.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(R.string.no_voices_available_check_api_url),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { expanded = false }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.default_engine_default)) },
                    onClick = {
                        onVoiceSelected("")
                        expanded = false
                    }
                )
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            if (voice.name != voice.id) {
                                Column {
                                    Text(voice.name)
                                    Text(
                                        voice.id,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(voice.name)
                            }
                        },
                        onClick = {
                            onVoiceSelected(voice.id)
                            expanded = false
                        }
                    )
                }
            }
        }

        // Refresh button to re-fetch voices from server
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(R.string.refresh_voices),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineSelector(
    selectedEngine: String,
    engines: List<com.pockettavern.app.ui.audio.TtsEngineInfo>,
    onEngineSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val selectedLabel = if (selectedEngine.isEmpty()) {
                    "系统默认"
    } else {
        engines.find { it.packageName == selectedEngine }?.label ?: selectedEngine
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = { },
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            label = { Text(stringResource(R.string.engine)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.system_default)) },
                onClick = {
                    onEngineSelected("")
                    expanded = false
                }
            )
            engines.forEach { engine ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(engine.label)
                            Text(
                                engine.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onEngineSelected(engine.packageName)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}
