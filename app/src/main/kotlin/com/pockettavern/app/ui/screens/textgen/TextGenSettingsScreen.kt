package com.pockettavern.app.ui.screens.textgen

import com.pockettavern.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pockettavern.app.ui.components.ConfirmDialog
import com.pockettavern.app.ui.components.ErrorDialog
import com.pockettavern.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextGenSettingsScreen(
    onBack: () -> Unit,
    viewModel: TextGenSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.saveSuccess) {
        if (uiState.saveSuccess) {
            viewModel.resetSaveSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.text_generation)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Preset Selector
                SectionHeader(stringResource(R.string.preset))
                PresetDropdown(
                    presets = uiState.presets.map { it.name },
                    selectedIndex = uiState.selectedPresetIndex,
                    onSelect = { viewModel.selectPreset(it) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // Basic Settings
                SectionHeader(stringResource(R.string.basic_settings))

                SliderSetting("温度", uiState.temperature, 0f..2f, "%.2f") { viewModel.updateTemperature(it) }
                SliderSetting("Top P（核采样）", uiState.topP, 0f..1f, "%.2f") { viewModel.updateTopP(it) }
                SliderSetting("Min P（最低概率）", uiState.minP, 0f..1f, "%.2f") { viewModel.updateMinP(it) }
                SliderSetting("重复惩罚", uiState.repPen, 1f..2f, "%.2f") { viewModel.updateRepPen(it) }

                IntInputField("最大生成 Token 数", uiState.maxTokens) { viewModel.updateMaxTokens(it) }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // Advanced Settings Toggle
                OutlinedButton(
                    onClick = { viewModel.toggleAdvanced() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (uiState.showAdvanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (uiState.showAdvanced) "收起高级设置" else "显示高级设置")
                }

                // Advanced Settings
                AnimatedVisibility(visible = uiState.showAdvanced) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Token Limits
                        SectionHeader(stringResource(R.string.token_limits))
                        IntInputField("最小 Token 数", uiState.minTokens) { viewModel.updateMinTokens(it) }
                        IntInputField("上下文长度", uiState.truncationLength) { viewModel.updateTruncationLength(it) }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Sampling
                        SectionHeader(stringResource(R.string.sampling))
                        IntInputField("Top K（候选数量）", uiState.topK) { viewModel.updateTopK(it) }
                        SliderSetting("Top A（动态采样）", uiState.topA, 0f..1f, "%.2f") { viewModel.updateTopA(it) }
                        SliderSetting("典型采样 P", uiState.typicalP, 0f..1f, "%.2f") { viewModel.updateTypicalP(it) }
                        SliderSetting("TFS", uiState.tfs, 0f..1f, "%.2f") { viewModel.updateTfs(it) }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Repetition
                        SectionHeader(stringResource(R.string.repetition_penalty))
                        IntInputField("重复惩罚范围", uiState.repPenRange) { viewModel.updateRepPenRange(it) }
                        SliderSetting("重复惩罚斜率", uiState.repPenSlope, 0f..10f, "%.1f") { viewModel.updateRepPenSlope(it) }
                        SliderSetting("频率惩罚", uiState.frequencyPenalty, 0f..2f, "%.2f") { viewModel.updateFrequencyPenalty(it) }
                        SliderSetting("存在惩罚", uiState.presencePenalty, 0f..2f, "%.2f") { viewModel.updatePresencePenalty(it) }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // DRY Sampler
                        SectionHeader(stringResource(R.string.dry_sampler))
                        SliderSetting("DRY 倍率", uiState.dryMultiplier, 0f..2f, "%.2f") { viewModel.updateDryMultiplier(it) }
                        SliderSetting("DRY 基数", uiState.dryBase, 1f..4f, "%.2f") { viewModel.updateDryBase(it) }
                        IntInputField("DRY 允许长度", uiState.dryAllowedLength) { viewModel.updateDryAllowedLength(it) }
                        IntInputField("DRY 惩罚回溯范围", uiState.dryPenaltyLastN) { viewModel.updateDryPenaltyLastN(it) }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Mirostat
                        SectionHeader(stringResource(R.string.mirostat))
                        IntInputField("Mirostat 模式（0—2）", uiState.mirostatMode) { viewModel.updateMirostatMode(it) }
                        SliderSetting("Mirostat Tau 参数", uiState.mirostatTau, 0f..10f, "%.1f") { viewModel.updateMirostatTau(it) }
                        SliderSetting("Mirostat Eta 参数", uiState.mirostatEta, 0f..1f, "%.2f") { viewModel.updateMirostatEta(it) }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // XTC
                        SectionHeader(stringResource(R.string.xtc))
                        SliderSetting("XTC 阈值", uiState.xtcThreshold, 0f..1f, "%.2f") { viewModel.updateXtcThreshold(it) }
                        SliderSetting("XTC 概率", uiState.xtcProbability, 0f..1f, "%.2f") { viewModel.updateXtcProbability(it) }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Other
                        SectionHeader(stringResource(R.string.other))
                        SliderSetting("偏斜", uiState.skew, -5f..5f, "%.2f") { viewModel.updateSkew(it) }
                        SliderSetting("平滑系数", uiState.smoothingFactor, 0f..10f, "%.2f") { viewModel.updateSmoothingFactor(it) }
                        SliderSetting("平滑曲线", uiState.smoothingCurve, 0f..10f, "%.2f") { viewModel.updateSmoothingCurve(it) }
                        SliderSetting("引导强度（CFG）", uiState.guidanceScale, 0f..3f, "%.2f") { viewModel.updateGuidanceScale(it) }

                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                        // Token Handling
                        SectionHeader(stringResource(R.string.token_handling))
                        SwitchSetting("添加句首 Token", uiState.addBosToken) { viewModel.updateAddBosToken(it) }
                        SwitchSetting("禁用句末 Token", uiState.banEosToken) { viewModel.updateBanEosToken(it) }
                        SwitchSetting("跳过特殊 Token", uiState.skipSpecialTokens) { viewModel.updateSkipSpecialTokens(it) }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.showSavePresetDialog() },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isSaving
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.save_preset))
                    }

                    OutlinedButton(
                        onClick = { viewModel.showDeleteConfirm() },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isSaving && uiState.presets.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.delete))
                    }
                }

                Button(
                    onClick = { viewModel.applySettings() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isSaving
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.apply_settings))
                }

                if (uiState.saveSuccess) {
                    Text(text = stringResource(R.string.settings_applied_successfully),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Save Preset Dialog
    if (uiState.showSavePresetDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.hideSavePresetDialog() },
            title = { Text(stringResource(R.string.save_preset)) },
            text = {
                OutlinedTextField(
                    value = uiState.newPresetName,
                    onValueChange = { viewModel.updateNewPresetName(it) },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                    colors = textFieldColors()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.savePreset() },
                    enabled = uiState.newPresetName.isNotBlank() && !uiState.isSaving
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideSavePresetDialog() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete Confirmation
    if (uiState.showDeleteConfirm) {
        val presetName = uiState.presets.getOrNull(uiState.selectedPresetIndex)?.name ?: ""
        ConfirmDialog(
            title = "删除预设",
            message = "确定删除预设“$presetName”吗？此操作无法撤销。",
            confirmText = "删除",
            onConfirm = { viewModel.deleteCurrentPreset() },
            onDismiss = { viewModel.hideDeleteConfirm() },
            isDestructive = true
        )
    }

    // Error Dialog
    uiState.error?.let { error ->
        ErrorDialog(
            message = error,
            onDismiss = { viewModel.clearError() }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetDropdown(
    presets: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = presets.getOrNull(selectedIndex) ?: "选择预设",
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            colors = dropdownColors()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            presets.forEachIndexed { index, preset ->
                DropdownMenuItem(
                    text = { Text(preset) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(format.format(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun IntInputField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit
) {
    // Local text state so the user can clear and retype freely.
    // remember(value) resets text when an external change occurs (e.g. preset switch).
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }
            text = digits
            digits.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = textFieldColors()
    )
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            )
        )
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
)

@Composable
private fun dropdownColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
    unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
)
