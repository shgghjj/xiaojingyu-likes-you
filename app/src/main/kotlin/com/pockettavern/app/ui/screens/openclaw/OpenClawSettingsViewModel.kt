package com.pockettavern.app.ui.screens.openclaw

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettavern.app.openclaw.OpenClawConfig
import com.pockettavern.app.openclaw.OpenClawDebugLog
import com.pockettavern.app.openclaw.OpenClawRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OpenClawSettingsUiState(
    val enabled: Boolean = false,
    val gatewayUrl: String = "ws://192.168.71.45:18789",
    val token: String = "",
    val timeoutSeconds: Int = 120,
    val autoInvoke: Boolean = false,
    val confirmAll: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val testResult: String? = null,
    val testOk: Boolean? = null,
    val saveMessage: String? = null,
    val debugLogs: List<OpenClawDebugLog> = emptyList()
)

@HiltViewModel
class OpenClawSettingsViewModel @Inject constructor(
    private val repository: OpenClawRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OpenClawSettingsUiState())
    val uiState: StateFlow<OpenClawSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val config = repository.getConfig()
            val token = repository.getToken()
            _uiState.update {
                it.copy(
                    enabled = config.enabled,
                    gatewayUrl = config.gatewayUrl,
                    token = token,
                    timeoutSeconds = config.timeoutSeconds,
                    autoInvoke = config.autoInvoke,
                    confirmAll = config.confirmAll,
                    loading = false,
                    debugLogs = repository.debugLogs.value
                )
            }
        }
        viewModelScope.launch {
            repository.debugLogs.collect { logs ->
                _uiState.update { it.copy(debugLogs = logs) }
            }
        }
    }

    fun onEnabledChange(v: Boolean) = _uiState.update { it.copy(enabled = v) }
    fun onGatewayUrlChange(v: String) = _uiState.update { it.copy(gatewayUrl = v) }
    fun onTokenChange(v: String) = _uiState.update { it.copy(token = v) }
    fun onTimeoutChange(v: Int) = _uiState.update { it.copy(timeoutSeconds = v.coerceIn(10, 600)) }
    fun onAutoInvokeChange(v: Boolean) = _uiState.update { it.copy(autoInvoke = v) }
    fun onConfirmAllChange(v: Boolean) = _uiState.update { it.copy(confirmAll = v) }

    fun save() {
        viewModelScope.launch {
            val s = _uiState.value
            if (s.gatewayUrl.trim().isBlank()) {
                _uiState.update { it.copy(saveMessage = "Gateway 地址不能为空") }
                return@launch
            }
            _uiState.update { it.copy(saving = true, saveMessage = null) }
            repository.saveConfig(
                OpenClawConfig(
                    enabled = s.enabled,
                    gatewayUrl = s.gatewayUrl.trim(),
                    timeoutSeconds = s.timeoutSeconds,
                    autoInvoke = s.autoInvoke,
                    confirmAll = s.confirmAll
                )
            )
            repository.saveToken(s.token)
            _uiState.update {
                it.copy(saving = false, saveMessage = "已保存", debugLogs = repository.debugLogs.value)
            }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            save()
            _uiState.update { it.copy(testing = true, testResult = null, testOk = null) }
            val (ok, msg) = repository.testConnection()
            _uiState.update {
                it.copy(
                    testing = false,
                    testOk = ok,
                    testResult = msg,
                    debugLogs = repository.debugLogs.value
                )
            }
        }
    }

    fun clearDebugLogs() {
        repository.clearDebugLogs()
        _uiState.update { it.copy(debugLogs = emptyList()) }
    }
}
