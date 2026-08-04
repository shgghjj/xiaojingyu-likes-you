package com.pockettavern.app.openclaw

/** OpenClaw 任务/连接错误。message 永不含 Token、密钥或完整消息内容。 */
sealed class OpenClawError(
    val type: String,
    override val message: String
) : Exception(message) {

    /** 未启用或 Gateway 地址为空。 */
    class ConfigIncomplete(message: String = "OpenClaw 未启用或配置不完整") :
        OpenClawError("config_incomplete", message)

    /** 网络不可达（超时/拒绝连接/DNS 失败）。 */
    class GatewayUnreachable(message: String = "无法连接 OpenClaw Gateway，请确认电脑端已启动且与手机同一网络") :
        OpenClawError("gateway_unreachable", message)

    /** Gateway 返回认证失败（Token 错误）。 */
    class AuthFailed(message: String = "认证失败：Token 不正确或已过期") :
        OpenClawError("auth_failed", message)

    /** 握手成功但协议不受支持。 */
    class ProtocolMismatch(message: String = "Gateway 协议版本不兼容") :
        OpenClawError("protocol_mismatch", message)

    /** 任务执行超过用户设置的时间限制。 */
    class Timeout(message: String = "任务超时，已停止") :
        OpenClawError("timeout", message)

    /** 任务被用户取消。 */
    class Cancelled(message: String = "任务已取消") :
        OpenClawError("cancelled", message)

    /** Gateway 返回任务执行失败。 */
    class TaskFailed(message: String = "OpenClaw 任务执行失败") :
        OpenClawError("task_failed", message)

    /** 连接在任务中途断开。 */
    class Disconnected(message: String = "与 OpenClaw 的连接已断开") :
        OpenClawError("disconnected", message)

    /** 响应无法解析。 */
    class MalformedResponse(message: String = "Gateway 返回了无法识别的响应") :
        OpenClawError("malformed_response", message)
}
