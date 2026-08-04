package com.pockettavern.app.openclaw

import com.pockettavern.app.data.local.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenClaw 领域门面：任务串行执行（防止并发串 taskId）、连接测试、配置读写、调试日志。
 * 所有对外方法都在调用方协程上运行。
 */
@Singleton
class OpenClawRepository @Inject constructor(
    private val settings: SettingsDataStore,
    private val client: OpenClawClient
) {

    private val taskMutex = Mutex()
    private val taskRunning = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)

    /** 当前任务状态（供聊天界面展示）。 */
    private val _currentTask = MutableStateFlow<OpenClawTaskResult?>(null)
    val currentTask: StateFlow<OpenClawTaskResult?> = _currentTask.asStateFlow()

    /** 最近 N 条调试日志（不含敏感信息）。 */
    private val _debugLogs = MutableStateFlow<List<OpenClawDebugLog>>(emptyList())
    val debugLogs: StateFlow<List<OpenClawDebugLog>> = _debugLogs.asStateFlow()

    val configFlow: Flow<OpenClawConfig> = settings.openclawConfigFlow

    val configState: StateFlow<OpenClawConfig> = settings.openclawConfigFlow
        .stateIn(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = OpenClawConfig()
        )

    suspend fun getConfig(): OpenClawConfig = settings.getOpenClawConfig()

    suspend fun saveConfig(config: OpenClawConfig) = settings.saveOpenClawConfig(config)

    suspend fun getToken(): String = settings.getOpenClawToken()

    suspend fun saveToken(token: String) = settings.saveOpenClawToken(token)

    /**
     * 执行 OpenClaw 任务（在调用方协程上运行）。
     * 同一时刻只允许一个任务；已有任务运行时立即返回 false。
     */
    suspend fun runTask(
        taskText: String,
        onStatus: (OpenClawTaskStatus) -> Unit
    ): Boolean {
        if (!taskRunning.compareAndSet(false, true)) return false
        try {
            return taskMutex.withLock { executeTask(taskText, onStatus) }
        } finally {
            taskRunning.set(false)
        }
    }

    private suspend fun executeTask(
        taskText: String,
        onStatus: (OpenClawTaskStatus) -> Unit
    ): Boolean {
        val config = settings.getOpenClawConfig()
        if (!config.enabled) {
            appendLog(taskId = "", status = "disabled")
            return false
        }
        val token = settings.getOpenClawToken()
        if (token.isBlank()) {
            appendLog(taskId = "", status = "no-token")
            return false
        }

        val taskId = UUID.randomUUID().toString().substring(0, 8)
        val startedAt = System.currentTimeMillis()
        cancelRequested.set(false)

        appendLog(taskId = taskId, status = "started")
        val result = client.runTask(
            config = config,
            token = token,
            taskText = taskText,
            runId = "yueyu-$taskId",
            timeoutSeconds = config.timeoutSeconds,
            onStatus = onStatus,
            isCancelled = { cancelRequested.get() }
        )

        _currentTask.value = result
        appendLog(
            taskId = taskId,
            status = result.status.name.lowercase(),
            durationMs = result.durationMs,
            errorType = result.error?.type
        )
        return true
    }

    /** 请求取消当前任务（由客户端先发 sessions.abort）。 */
    fun cancelTask() {
        cancelRequested.set(true)
    }

    /** 测试与 Gateway 的连接（握手成功即返回，不做任务）。 */
    suspend fun testConnection(): Pair<Boolean, String> {
        val config = settings.getOpenClawConfig()
        val token = settings.getOpenClawToken()
        return try {
            val r = client.testConnection(config, token)
            if (r.isSuccess) {
                appendLog(taskId = "", status = "test-ok")
                true to r.getOrThrow()
            } else {
                val err = (r.exceptionOrNull() as? OpenClawError)
                    ?: OpenClawError.GatewayUnreachable()
                appendLog(taskId = "", status = "test-fail", errorType = err.type)
                false to err.message
            }
        } catch (e: Exception) {
            appendLog(taskId = "", status = "test-fail", errorType = "exception")
            false to (e.message ?: "测试失败")
        }
    }

    fun clearDebugLogs() {
        _debugLogs.value = emptyList()
    }

    /** 调试日志仅记录：taskId / 状态 / 耗时 / 错误类型，绝不记录 Token 与消息内容。 */
    private fun appendLog(
        taskId: String,
        status: String,
        durationMs: Long? = null,
        errorType: String? = null
    ) {
        val entry = OpenClawDebugLog(
            taskId = taskId,
            status = status,
            durationMs = durationMs,
            errorType = errorType
        )
        _debugLogs.value = (_debugLogs.value + entry).takeLast(50)
    }
}
