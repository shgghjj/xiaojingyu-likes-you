package com.pockettavern.app.openclaw

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * OpenClaw Gateway WebSocket 客户端（协议 v4）。
 *
 * 只负责一条连接的完整生命周期：
 * connect 握手 → hello-ok → agent 请求 → 流式事件收集 → agent.wait 终态 → 关闭。
 * 取消：发送 sessions.abort 后关闭；意外断线时自动重连一次（仅重发 agent.wait）。
 * 不持有任何 Token，token 仅作为 connect.auth 参数传入。
 */
@Singleton
class OpenClawClient @Inject constructor(
    @Named("OpenClaw") private val okHttp: OkHttpClient
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val requestId = AtomicLong(0)

    private val sendLock = Mutex()

    /**
     * 执行一个 OpenClaw 任务，直到终态（成功/失败/取消/超时）。
     */
    suspend fun runTask(
        config: OpenClawConfig,
        token: String,
        taskText: String,
        runId: String,
        timeoutSeconds: Int,
        onStatus: (OpenClawTaskStatus) -> Unit,
        isCancelled: () -> Boolean = { false }
    ): OpenClawTaskResult {
        val startedAt = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L
        return try {
            withTimeout(timeoutMs) {
                execute(config, token, taskText, runId, timeoutMs, startedAt, onStatus, isCancelled)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            OpenClawTaskResult(
                taskId = runId,
                runId = runId,
                status = OpenClawTaskStatus.FAILED,
                message = "任务超时（${timeoutSeconds} 秒）",
                error = OpenClawError.Timeout(),
                durationMs = System.currentTimeMillis() - startedAt
            )
        } catch (e: OpenClawError) {
            OpenClawTaskResult(
                taskId = runId,
                runId = runId,
                status = OpenClawTaskStatus.FAILED,
                message = e.message,
                error = e,
                durationMs = System.currentTimeMillis() - startedAt
            )
        } catch (e: Exception) {
            OpenClawTaskResult(
                taskId = runId,
                runId = runId,
                status = OpenClawTaskStatus.FAILED,
                message = "连接异常",
                error = OpenClawError.GatewayUnreachable(e.message ?: "连接异常"),
                durationMs = System.currentTimeMillis() - startedAt
            )
        }
    }

    /**
     * 测试连接：握手成功即返回服务器信息，不做任何任务。
     */
    suspend fun testConnection(config: OpenClawConfig, token: String): Result<String> {
        return try {
            withTimeout(15_000) {
                val outcome = establishConnection(config, token)
                when (outcome) {
                    is HandshakeResult.Ok -> Result.success(
                        "连接成功（Gateway 协议 v${outcome.protocolVersion ?: "4"}）"
                    )
                    is HandshakeResult.Failed -> Result.failure(outcome.error)
                    is HandshakeResult.Cancelled -> Result.failure(OpenClawError.Cancelled())
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Result.failure(OpenClawError.Timeout("连接测试超时"))
        } catch (e: Exception) {
            Result.failure(OpenClawError.GatewayUnreachable(e.message ?: "连接异常"))
        }
    }

    // ── 主流程 ────────────────────────────────────────────────────────────

    private suspend fun execute(
        config: OpenClawConfig,
        token: String,
        taskText: String,
        runId: String,
        timeoutMs: Long,
        startedAt: Long,
        onStatus: (OpenClawTaskStatus) -> Unit,
        isCancelled: () -> Boolean
    ): OpenClawTaskResult {
        onStatus(OpenClawTaskStatus.CONNECTING)
        val handshake = establishConnection(config, token)
        val session = when (handshake) {
            is HandshakeResult.Ok -> handshake
            is HandshakeResult.Failed -> {
                if (isCancelled()) return cancelledResult(runId, startedAt)
                return OpenClawTaskResult(
                    taskId = runId,
                    runId = runId,
                    status = OpenClawTaskStatus.FAILED,
                    message = handshake.error.message,
                    error = handshake.error,
                    durationMs = System.currentTimeMillis() - startedAt
                )
            }
            is HandshakeResult.Cancelled -> return cancelledResult(runId, startedAt)
        }

        try {
            onStatus(OpenClawTaskStatus.WORKING)

            val agentReqId = requestId.incrementAndGet()
            sendJson(session.webSocket, buildAgentRequest(agentReqId, taskText, runId))
            val accepted = awaitResForId(session, agentReqId, 10_000, "任务未受理")
            if (accepted == null) {
                return if (isCancelled()) cancelledResult(runId, startedAt)
                else OpenClawTaskResult(
                    taskId = runId,
                    runId = runId,
                    status = OpenClawTaskStatus.FAILED,
                    message = "任务未受理",
                    error = OpenClawError.TaskFailed("任务未受理"),
                    durationMs = System.currentTimeMillis() - startedAt
                )
            }
            if (accepted.ok == false && accepted.error != null) {
                return failWith(accepted, runId, startedAt)
            }

            val waitId = requestId.incrementAndGet()
            sendJson(
                session.webSocket,
                buildWaitRequest(waitId, runId, remainingMs(timeoutMs, startedAt))
            )

            var assistantText = StringBuilder()
            var reconnectAttempted = false

            while (true) {
                if (isCancelled()) {
                    sendAbort(session, runId)
                    return cancelledResult(runId, startedAt)
                }
                val remaining = remainingMs(timeoutMs, startedAt)
                if (remaining <= 0) {
                    return OpenClawTaskResult(
                        taskId = runId,
                        runId = runId,
                        status = OpenClawTaskStatus.FAILED,
                        message = "任务超时",
                        error = OpenClawError.Timeout(),
                        durationMs = System.currentTimeMillis() - startedAt
                    )
                }
                val frame = withTimeout(remaining) {
                    session.frames.receiveCatching().getOrNull()
                } ?: break

                when (frame.type) {
                    "res" -> {
                        if (frame.id?.asLong == waitId) {
                            val payload = frame.payload
                            when {
                                frame.ok == false || frame.error != null -> {
                                    return failWith(frame, runId, startedAt)
                                }
                                payload?.status == "ok" -> {
                                    val summary = payload.summary
                                        ?: payload.result?.summary
                                        ?: assistantText.toString().trim()
                                    return OpenClawTaskResult(
                                        taskId = runId,
                                        runId = runId,
                                        status = OpenClawTaskStatus.SUCCESS,
                                        message = summary.ifBlank { "任务已完成" },
                                        raw = summary,
                                        durationMs = System.currentTimeMillis() - startedAt
                                    )
                                }
                                else -> {
                                    return failWith(frame, runId, startedAt)
                                }
                            }
                        }
                    }
                    "event" -> {
                        if (frame.event == "agent" || frame.method == "agent") {
                            val payload = frame.payload
                            when (payload?.stream) {
                                "assistant" -> {
                                    payload.data?.text?.let { assistantText.append(it) }
                                }
                                "lifecycle" -> {
                                    // phase start/thinking/end/error — 目前只需等待 agent.wait 终态
                                }
                                else -> {}
                            }
                        }
                    }
                    else -> { /* hello-ok / tick / 其它事件忽略 */ }
                }
            }

            // 连接中断：尝试重连一次，只补发 agent.wait
            if (!reconnectAttempted && !isCancelled()) {
                reconnectAttempted = true
                onStatus(OpenClawTaskStatus.CONNECTING)
                val reHandshake = establishConnection(config, token) as? HandshakeResult.Ok
                if (reHandshake != null) {
                    val reWaitId = requestId.incrementAndGet()
                    sendJson(
                        reHandshake.webSocket,
                        buildWaitRequest(reWaitId, runId, remainingMs(timeoutMs, startedAt))
                    )
                    val reFrame = withTimeout(remainingMs(timeoutMs, startedAt)) {
                        reHandshake.frames.receiveCatching().getOrNull()
                    }
                    if (reFrame != null && reFrame.id?.asLong == reWaitId && reFrame.payload?.status == "ok") {
                        val summary = reFrame.payload.summary
                            ?: reFrame.payload.result?.summary
                            ?: assistantText.toString().trim()
                        return OpenClawTaskResult(
                            taskId = runId,
                            runId = runId,
                            status = OpenClawTaskStatus.SUCCESS,
                            message = summary.ifBlank { "任务已完成" },
                            raw = summary,
                            durationMs = System.currentTimeMillis() - startedAt
                        )
                    }
                }
            }

            return OpenClawTaskResult(
                taskId = runId,
                runId = runId,
                status = OpenClawTaskStatus.FAILED,
                message = "与 Gateway 的连接中断",
                error = OpenClawError.Disconnected(),
                durationMs = System.currentTimeMillis() - startedAt
            )
        } finally {
            session.webSocket.close(1000, "task done")
        }
    }

    // ── 握手 ──────────────────────────────────────────────────────────────

    private sealed class HandshakeResult {
        data class Ok(
            val webSocket: WebSocket,
            val frames: Channel<Frame>,
            val protocolVersion: Int?
        ) : HandshakeResult()

        data class Failed(val error: OpenClawError) : HandshakeResult()
        object Cancelled : HandshakeResult()
    }

    private suspend fun establishConnection(
        config: OpenClawConfig,
        token: String
    ): HandshakeResult {
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val ws = openWebSocket(config.gatewayUrl, frames)

        // 可选：等待 connect.challenge（部分网关需要先发）
        withTimeoutOrNullSafe(2_000) {
            frames.receiveCatching().getOrNull()
        }

        // 发送 connect
        val connectId = requestId.incrementAndGet()
        sendJson(ws, buildConnectRequest(connectId, config, token))

        // 等待 hello-ok（或错误帧）
        val deadline = System.currentTimeMillis() + 10_000
        var lastError: OpenClawError? = null
        while (System.currentTimeMillis() < deadline) {
            val frame = withTimeoutOrNullSafe(deadline - System.currentTimeMillis()) {
                frames.receiveCatching().getOrNull()
            } ?: break

            if (frame.type == "hello-ok" ||
                (frame.type == "event" && frame.event == "hello-ok")
            ) {
                return HandshakeResult.Ok(
                    webSocket = ws,
                    frames = frames,
                    protocolVersion = frame.payload?.protocol
                )
            }
            if (frame.type == "res" && frame.id?.asLong == connectId) {
                if (frame.ok == false || frame.error != null) {
                    lastError = mapErrorFrame(frame, "握手失败")
                    break
                }
            }
        }

        ws.close(1000, "handshake done")
        return HandshakeResult.Failed(
            lastError ?: OpenClawError.GatewayUnreachable("无法连接 Gateway（${config.gatewayUrl}）")
        )
    }

    /** 等待某请求 id 的 res 帧（其余帧暂存不消费）。 */
    private suspend fun awaitResForId(
        session: HandshakeResult.Ok,
        id: Long,
        timeoutMs: Long,
        fallback: String
    ): Frame? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = withTimeoutOrNullSafe(deadline - System.currentTimeMillis()) {
                session.frames.receiveCatching().getOrNull()
            } ?: break
            if (frame.type == "res") return frame
        }
        return null
    }

    private suspend fun openWebSocket(
        gatewayUrl: String,
        frames: Channel<Frame>
    ): WebSocket {
        val request = Request.Builder()
            .url(gatewayUrl)
            .build()
        return okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val frame = json.decodeFromString<Frame>(text)
                    frames.trySend(frame)
                } catch (_: Exception) {
                    // 忽略无法解析的帧
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                frames.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                frames.close()
            }
        })
    }

    // ── 请求构造 ──────────────────────────────────────────────────────────

    private suspend fun sendJson(ws: WebSocket, text: String) {
        sendLock.withLock { ws.send(text) }
    }

    private fun buildConnectRequest(id: Long, config: OpenClawConfig, token: String): String =
        json.encodeToString(
            buildJsonObject {
                put("type", "req")
                put("id", id)
                put("method", "connect")
                putJsonObject("params") {
                    put("minProtocol", 4)
                    put("maxProtocol", 4)
                    putJsonObject("client") {
                        put("id", "yueyu-android")
                        put("version", "0.4.2")
                        put("platform", "android")
                        put("mode", "operator")
                    }
                    put("role", "operator")
                    put("scopes", buildJsonArray {
                        add(JsonPrimitive("operator.read"))
                        add(JsonPrimitive("operator.write"))
                    })
                    put("caps", buildJsonArray { add(JsonPrimitive("tool-events")) })
                    put("commands", buildJsonArray {})
                    put("permissions", buildJsonObject {})
                    put("locale", "zh-CN")
                    put("userAgent", "yueyu-companion/0.4.2")
                    if (token.isNotBlank()) {
                        putJsonObject("auth") { put("token", token) }
                    }
                }
            }
        )

    private fun buildAgentRequest(id: Long, taskText: String, runId: String): String =
        json.encodeToString(
            buildJsonObject {
                put("type", "req")
                put("id", id)
                put("method", "agent")
                putJsonObject("params") {
                    put("message", taskText)
                    put("sessionKey", "chat:yueyu")
                    put("runId", runId)
                    put("agentId", "default")
                    put("thinking", "medium")
                    put("deliver", false)
                }
            }
        )

    private fun buildWaitRequest(id: Long, runId: String, timeoutMs: Long): String =
        json.encodeToString(
            buildJsonObject {
                put("type", "req")
                put("id", id)
                put("method", "agent.wait")
                putJsonObject("params") {
                    put("runId", runId)
                    put("timeoutMs", timeoutMs.coerceAtLeast(1_000))
                }
            }
        )

    private suspend fun sendAbort(session: HandshakeResult.Ok, runId: String) {
        try {
            val id = requestId.incrementAndGet()
            sendJson(
                session.webSocket,
                json.encodeToString(
                    buildJsonObject {
                        put("type", "req")
                        put("id", id)
                        put("method", "sessions.abort")
                        putJsonObject("params") {
                            put("runId", runId)
                            put("key", "chat:yueyu")
                        }
                    }
                )
            )
        } catch (_: Exception) {
            // 尽力而为
        }
    }

    // ── 结果映射 ──────────────────────────────────────────────────────────

    private fun cancelledResult(runId: String, startedAt: Long): OpenClawTaskResult =
        OpenClawTaskResult(
            taskId = runId,
            runId = runId,
            status = OpenClawTaskStatus.CANCELLED,
            message = "任务已取消",
            error = OpenClawError.Cancelled(),
            durationMs = System.currentTimeMillis() - startedAt
        )

    private fun failWith(frame: Frame, runId: String, startedAt: Long): OpenClawTaskResult {
        val error = mapErrorFrame(frame, "任务执行失败")
        return OpenClawTaskResult(
            taskId = runId,
            runId = runId,
            status = OpenClawTaskStatus.FAILED,
            message = error.message,
            error = error,
            durationMs = System.currentTimeMillis() - startedAt
        )
    }

    private fun mapErrorFrame(frame: Frame, fallback: String): OpenClawError {
        val code = frame.error?.code ?: frame.payload?.error ?: ""
        val detail = frame.error?.message ?: frame.payload?.status ?: ""
        return when {
            code.contains("AUTH", ignoreCase = true) ||
                code.contains("UNAUTHORIZED", ignoreCase = true) ||
                code.contains("PAIRING", ignoreCase = true) -> OpenClawError.AuthFailed()
            code.contains("PROTOCOL", ignoreCase = true) -> OpenClawError.ProtocolMismatch()
            code.contains("TIMEOUT", ignoreCase = true) -> OpenClawError.Timeout()
            else -> OpenClawError.TaskFailed(detail.ifBlank { fallback })
        }
    }

    private fun remainingMs(timeoutMs: Long, startedAt: Long): Long =
        (timeoutMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(1L)

    private suspend fun <T> withTimeoutOrNullSafe(timeoutMs: Long, block: suspend () -> T?): T? =
        if (timeoutMs <= 0) null else kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { block() }

    /** JSON-RPC id 可能是数字或字符串，统一取整数值。 */
    private val JsonElement?.asLong: Long?
        get() = (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}
