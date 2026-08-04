package com.pockettavern.app.openclaw

/**
 * 消息路由：决定一条消息是走普通聊天还是 OpenClaw。
 *
 * 第一版使用显式命令 + 规则关键词识别，不依赖模型猜测；
 * 全部逻辑集中于此，后续可整体替换为模型判断实现。
 */
object OpenClawRouter {

    /** /claw 命令前缀，强制走 OpenClaw。 */
    const val CLAW_PREFIX = "/claw"

    /** 提取 /claw 后面的任务文本；非命令返回 null。 */
    fun extractClawTask(rawText: String): String? {
        val t = rawText.trim()
        if (!t.startsWith(CLAW_PREFIX, ignoreCase = true)) return null
        return t.substring(CLAW_PREFIX.length).trim()
    }

    fun isClawCommand(rawText: String): Boolean = extractClawTask(rawText) != null

    /**
     * 核心路由入口。
     *
     * @param forcedByCommand 是否 /claw 显式命令
     * @param forcedByButton  是否用户按了"工具"按钮
     * @param enabled         OpenClaw 是否启用（未启用时一律走聊天）
     * @param confirmAll      设置中"所有操作都要确认"
     * @param autoInvoke      设置中"自动识别工具请求"（未强制时只有开启才做自动识别）
     */
    fun decide(
        rawText: String,
        forcedByCommand: Boolean = false,
        forcedByButton: Boolean = false,
        enabled: Boolean = true,
        confirmAll: Boolean = false,
        autoInvoke: Boolean = true
    ): OpenClawRouteDecision {
        val text = rawText.trim()
        if (text.isBlank()) return OpenClawRouteDecision(route = RouteTarget.CHAT, reason = "空消息")

        if (forcedByCommand) {
            return OpenClawRouteDecision(
                route = RouteTarget.OPENCLAW,
                reason = "/claw 显式命令",
                risk = guessRisk(text),
                requiresConfirmation = confirmAll || guessRisk(text) != RiskLevel.LOW
            )
        }

        if (!enabled) {
            return OpenClawRouteDecision(route = RouteTarget.CHAT, reason = "OpenClaw 未启用")
        }

        if (forcedByButton) {
            return OpenClawRouteDecision(
                route = RouteTarget.OPENCLAW,
                reason = "工具按钮强制",
                risk = guessRisk(text),
                requiresConfirmation = confirmAll || guessRisk(text) != RiskLevel.LOW
            )
        }

        if (!autoInvoke) {
            return OpenClawRouteDecision(route = RouteTarget.CHAT, reason = "自动识别未开启")
        }

        return autoDetect(text, confirmAll)
    }

    /** 自动识别：只有明确要求执行工具/设备操作时才走 OpenClaw，否则默认聊天。 */
    fun autoDetect(text: String, confirmAll: Boolean = false): OpenClawRouteDecision {
        val risk = guessRisk(text)

        // 高风险关键词直接命中 → OpenClaw + 必须确认
        if (hasAny(text, HIGH_RISK_KEYWORDS)) {
            return OpenClawRouteDecision(
                route = RouteTarget.OPENCLAW,
                reason = "高风险操作（如发送/删除/支付等）",
                risk = risk,
                requiresConfirmation = true
            )
        }

        // 工具类关键词 → OpenClaw，低风险免确认
        if (hasAny(text, TOOL_KEYWORDS)) {
            return OpenClawRouteDecision(
                route = RouteTarget.OPENCLAW,
                reason = "检测到设备/工具类请求",
                risk = risk,
                requiresConfirmation = confirmAll || risk != RiskLevel.LOW
            )
        }

        return OpenClawRouteDecision(route = RouteTarget.CHAT, reason = "未检测到工具类请求")
    }

    /** 估算风险等级。 */
    fun guessRisk(text: String): RiskLevel = when {
        hasAny(text, HIGH_RISK_KEYWORDS) -> RiskLevel.HIGH
        hasAny(text, MEDIUM_RISK_KEYWORDS) -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }

    private fun hasAny(text: String, keywords: List<String>): Boolean =
        keywords.any { text.contains(it, ignoreCase = true) }

    /** 必须确认的高风险操作。 */
    private val HIGH_RISK_KEYWORDS = listOf(
        "发送消息", "发消息", "发信息", "发微信", "微信消息", "发短信", "短信给",
        "发邮件", "邮件给", "打电话", "拨打电话", "视频通话",
        "删除", "卸载", "安装应用", "安装",
        "付款", "转账", "购买", "支付", "下单", "充值",
        "发布", "发帖", "上传", "提交表单", "提交订单",
        "修改密码", "修改我的密码", "修改账号", "改密码", "改我的密码", "把密码",
        "关闭安全", "授权", "登录", "退出登录",
        "格式化", "清空聊天", "清空数据", "恢复出厂"
    )

    /** 中风险：可能影响系统状态，但可恢复。 */
    private val MEDIUM_RISK_KEYWORDS = listOf(
        "设置", "更改设置", "修改设置", "切换", "静音", "关闭网络", "断开",
        "移动文件", "重命名", "复制文件", "截屏", "截图", "录屏",
        "锁定", "关机", "重启", "飞行模式"
    )

    /** 工具类任务关键词（低风险为主）。 */
    private val TOOL_KEYWORDS = listOf(
        "打开", "启动", "退出",
        "音量", "亮度", "电量", "剩余电量",
        "点击", "滑动", "滚动", "返回", "回到桌面", "回桌面", "回到主屏",
        "查看通知", "打开通知", "清除通知", "清理通知",
        "查看日程", "打开日程", "添加日程", "新建日程",
        "待办",
        "打开文件", "查找文件", "找文件", "搜索文件",
        "查看照片", "打开相册", "找照片", "搜索照片",
        "打开图片",
        "拍照", "摄像", "摄像头", "相机",
        "定位", "位置", "打开联系人", "查联系人", "查找联系人",
        "查询", "查看", "检查", "读取",
        "设备状态", "系统状态", "网络状态", "内存", "存储空间", "当前时间",
        "自动化", "脚本", "执行任务", "帮我操作", "帮我点", "帮我查", "帮我找",
        "下载", "提醒"
    )
}
