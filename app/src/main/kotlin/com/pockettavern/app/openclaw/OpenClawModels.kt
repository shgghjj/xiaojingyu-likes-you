package com.pockettavern.app.openclaw

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 用户配置的 OpenClaw 连接参数（Token 单独存加密区，不在此类）。 */
data class OpenClawConfig(
    val enabled: Boolean = false,
    val gatewayUrl: String = "ws://192.168.71.45:18789",
    val timeoutSeconds: Int = 120,
    val autoInvoke: Boolean = false,
    val confirmAll: Boolean = false
)

/** 路由决策的结构化输出。 */
@Serializable
data class OpenClawRouteDecision(
    val route: RouteTarget = RouteTarget.CHAT,
    val reason: String = "",
    val risk: RiskLevel = RiskLevel.LOW,
    val requiresConfirmation: Boolean = false
)

enum class RouteTarget { CHAT, OPENCLAW }

enum class RiskLevel { LOW, MEDIUM, HIGH }

/** 任务状态（聊天界面展示用）。 */
enum class OpenClawTaskStatus { CONNECTING, WORKING, WAITING_CONFIRMATION, SUCCESS, CANCELLED, FAILED }

/** 单个 OpenClaw 任务的执行结果。 */
data class OpenClawTaskResult(
    val taskId: String,
    val runId: String?,
    val status: OpenClawTaskStatus,
    val message: String = "",
    val raw: String? = null,
    val error: OpenClawError? = null,
    val durationMs: Long = 0L
)

// ── Gateway WebSocket 协议帧（JSON-RPC 2.0，协议 v4） ───────────────────

@Serializable
data class ConnectRequest(
    val minProtocol: Int = 4,
    val maxProtocol: Int = 4,
    val client: ConnectClient = ConnectClient(),
    val role: String = "operator",
    val scopes: List<String> = listOf("operator.read", "operator.write"),
    val caps: List<String> = listOf("tool-events"),
    val commands: List<String> = emptyList(),
    val permissions: Map<String, String> = emptyMap(),
    val auth: ConnectAuth? = null,
    val locale: String = "zh-CN",
    val userAgent: String = "yueyu-companion/0.4.2"
)

@Serializable
data class ConnectClient(
    val id: String = "yueyu-android",
    val version: String = "0.4.2",
    val platform: String = "android",
    val mode: String = "operator"
)

@Serializable
data class ConnectAuth(val token: String)

@Serializable
data class AgentRequest(
    val message: String,
    val sessionKey: String = "chat:yueyu",
    val runId: String,
    val agentId: String = "default",
    val thinking: String = "medium",
    val deliver: Boolean = false
)

@Serializable
data class AbortRequest(
    val runId: String,
    val key: String = "chat:yueyu"
)

// ── 响应与事件（只声明需要用的字段，忽略未知键） ─────────────────────

@Serializable
data class Frame(
    val type: String? = null,
    val id: JsonElement? = null,
    val jsonrpc: String? = null,
    val method: String? = null,
    val ok: Boolean? = null,
    val error: FrameError? = null,
    val params: JsonParams? = null,
    val payload: JsonPayload? = null,
    val event: String? = null
)

@Serializable
data class JsonParams(val message: String? = null)

@Serializable
data class FrameError(val code: String? = null, val message: String? = null)

/** payload 的宽松视图：只读取需要的嵌套字段。 */
@Serializable
data class JsonPayload(
    val type: String? = null,
    val protocol: Int? = null,
    val runId: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val sessionKey: String? = null,
    val stream: String? = null,
    val data: JsonEventData? = null,
    val result: JsonPayload? = null,
    val policy: JsonPolicy? = null,
    val error: String? = null
)

@Serializable
data class JsonPolicy(val tickIntervalMs: Long? = null)

@Serializable
data class JsonEventData(
    val type: String? = null,
    val text: String? = null,
    val phase: String? = null,
    val name: String? = null
)

// ── 调试日志条目（不含任何敏感内容） ───────────────────────────────────

data class OpenClawDebugLog(
    val taskId: String,
    val routeReason: String? = null,
    val status: String,
    val durationMs: Long? = null,
    val errorType: String? = null,
    val retried: Boolean = false,
    val connected: Boolean = false
)
