package com.pockettavern.app.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONObject

data class PendingDeviceAction(
    val appName: String,
    val requestedBy: String = "AI"
)

data class DeviceLaunchResult(
    val success: Boolean,
    val displayName: String,
    val error: String? = null
)

data class ParsedDeviceAction(
    val visibleText: String,
    val appName: String?,
    val readScreenRequested: Boolean = false,
    val localTool: LocalToolAction? = null
)

/**
 * Parses a deliberately narrow tool tag emitted by the model. The tag is never
 * executed immediately: ChatScreen always asks the user to confirm first.
 */
object DeviceActionParser {
    private val xmlTag = Regex(
        pattern = """<device_action>([\s\S]*?)</device_action>""",
        option = RegexOption.IGNORE_CASE
    )
    private val compactTag = Regex(
        pattern = """\[\[OPEN_APP:([^\]]{1,80})]]""",
        option = RegexOption.IGNORE_CASE
    )
    private val compactScreenTag = Regex(
        pattern = """\[\[READ_SCREEN]]""",
        option = RegexOption.IGNORE_CASE
    )

    fun parse(text: String): ParsedDeviceAction {
        var requestedApp: String? = null
        var readScreenRequested = false
        var localTool: LocalToolAction? = null
        var cleaned = xmlTag.replace(text) { match ->
            runCatching {
                    val body = JSONObject(match.groupValues[1].trim())
                    val type = body.optString("type", body.optString("action"))
                    when (type.lowercase()) {
                        "open_app" -> {
                            if (requestedApp == null) {
                                requestedApp = body.optString("app", body.optString("name"))
                                    .trim()
                                    .takeIf { it.isNotBlank() && it.length <= 80 && '\n' !in it && '\r' !in it }
                            }
                        }
                        "read_screen" -> readScreenRequested = true
                        "set_volume" -> {
                            if (localTool == null) {
                                val value = body.optString("value").trim()
                                localTool = when {
                                    value == "mute" || value == "off" -> LocalToolAction.Mute(true)
                                    value == "unmute" || value == "on" -> LocalToolAction.Mute(false)
                                    value == "up" -> LocalToolAction.SetVolume(1)
                                    value == "down" -> LocalToolAction.SetVolume(-1)
                                    else -> value.toIntOrNull()?.let {
                                        LocalToolAction.SetVolumePercent(it.coerceIn(0, 100))
                                    }
                                }
                            }
                        }
                        "query_volume" -> if (localTool == null) localTool = LocalToolAction.GetVolume
                        "query_battery" -> if (localTool == null) localTool = LocalToolAction.GetBattery
                        "query_time" -> if (localTool == null) localTool = LocalToolAction.GetTime
                        "query_network" -> if (localTool == null) localTool = LocalToolAction.GetNetwork
                        "web_search", "search_web", "internet_search" -> {
                            if (localTool == null) {
                                val query = body.optString("query", body.optString("q")).trim()
                                    .takeIf { it.isNotBlank() && it.length <= 200 }
                                if (query != null) localTool = LocalToolAction.WebSearch(query)
                            }
                        }
                        "proactive_messages", "schedule_messages" -> {
                            if (localTool == null) {
                                localTool = LocalToolAction.ScheduleProactiveMessages(
                                    count = body.optInt("count", 1).coerceIn(1, 5),
                                    intervalSeconds = body.optInt("interval_seconds", 20).coerceIn(10, 300)
                                )
                            }
                        }
                        "query_brightness" -> if (localTool == null) localTool = LocalToolAction.GetBrightness
                        "query_apps", "list_apps", "search_apps" ->
                            if (localTool == null) {
                                localTool = LocalToolAction.GetApps(body.optString("keyword").trim().takeIf { it.isNotBlank() })
                            }
                        "query_files", "list_files", "list_dir" ->
                            if (localTool == null) {
                                localTool = LocalToolAction.ListFiles(body.optString("dir").trim().takeIf { it.isNotBlank() })
                            }
                        "image_gen" -> {
                            if (localTool == null) {
                                val prompt = body.optString("prompt").trim().takeIf { it.isNotBlank() && it.length <= 500 }
                                if (prompt != null) {
                                    localTool = LocalToolAction.GenerateImage(prompt)
                                }
                            }
                        }
                        "read_file" -> {
                            if (localTool == null) {
                                val path = body.optString("path").trim().takeIf { it.isNotBlank() }
                                if (path != null) localTool = LocalToolAction.ReadFile(path)
                            }
                        }
                        "create_file" -> {
                            if (localTool == null) {
                                val fname = body.optString("name").trim().takeIf { it.isNotBlank() }
                                val fcontent = body.optString("content").trim().takeIf { it.isNotBlank() }
                                if (fname != null && fcontent != null)
                                    localTool = LocalToolAction.CreateFile(fname, fcontent)
                            }
                        }
                        "edit_file", "update_file", "write_file" -> {
                            if (localTool == null) {
                                val path = body.optString("path", body.optString("name")).trim().takeIf { it.isNotBlank() }
                                val content = body.optString("content").takeIf { it.isNotEmpty() }
                                val append = body.optBoolean("append", false) ||
                                    body.optString("mode").equals("append", ignoreCase = true)
                                if (path != null && content != null) {
                                    localTool = LocalToolAction.EditFile(path, content, append)
                                }
                            }
                        }
                        "delete_file" -> {
                            if (localTool == null) {
                                val fname = body.optString("name").trim().takeIf { it.isNotBlank() }
                                if (fname != null) localTool = LocalToolAction.DeleteFile(fname)
                            }
                        }
                        "hide_file" -> {
                            if (localTool == null) {
                                val fname = body.optString("name").trim().takeIf { it.isNotBlank() }
                                if (fname != null) localTool = LocalToolAction.HideFile(fname)
                            }
                        }
                        "ui_click", "click_text" -> {
                            if (localTool == null) {
                                val target = body.optString("text", body.optString("target")).trim()
                                    .takeIf { it.isNotBlank() && it.length <= 120 }
                                if (target != null) localTool = LocalToolAction.UiClick(target)
                            }
                        }
                        "ui_input", "input_text", "type_text" -> {
                            if (localTool == null) {
                                val value = body.optString("text", body.optString("value"))
                                    .takeIf { it.isNotBlank() && it.length <= 2000 }
                                val target = body.optString("target", body.optString("field")).trim()
                                    .takeIf { it.isNotBlank() && it.length <= 120 }
                                if (value != null) localTool = LocalToolAction.UiInput(value, target)
                            }
                        }
                        "ui_scroll", "scroll" -> {
                            if (localTool == null) {
                                val direction = body.optString("direction", "down").trim().take(20)
                                localTool = LocalToolAction.UiScroll(direction.ifBlank { "down" })
                            }
                        }
                        "ui_global", "system_action" -> {
                            if (localTool == null) {
                                val action = body.optString("value", body.optString("name", body.optString("target"))).trim()
                                    .takeIf { it.isNotBlank() && it.length <= 30 }
                                if (action != null) localTool = LocalToolAction.UiGlobal(action)
                            }
                        }
                        "ui_tap", "tap" -> {
                            if (localTool == null) {
                                val x = body.optInt("x", -1)
                                val y = body.optInt("y", -1)
                                if (x in 0..100 && y in 0..100) {
                                    localTool = LocalToolAction.UiTap(x, y)
                                }
                            }
                        }
                    }
                }
            ""
        }
        cleaned = compactTag.replace(cleaned) { match ->
            if (requestedApp == null) requestedApp = match.groupValues[1].trim().take(80)
            ""
        }
        cleaned = compactScreenTag.replace(cleaned) {
            readScreenRequested = true
            ""
        }
        return ParsedDeviceAction(cleaned.trim(), requestedApp, readScreenRequested, localTool)
    }
}

/** Opens only launchable apps. It cannot click inside apps or read their screens. */
class DeviceAppLauncher(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager

    private val aliases: Map<String, List<String>> = mapOf(
        "微信" to listOf("com.tencent.mm"),
        "wechat" to listOf("com.tencent.mm"),
        "qq" to listOf("com.tencent.mobileqq"),
        "哔哩哔哩" to listOf("tv.danmaku.bili"),
        "b站" to listOf("tv.danmaku.bili"),
        "bilibili" to listOf("tv.danmaku.bili"),
        "小红书" to listOf("com.xingin.xhs"),
        "抖音" to listOf("com.ss.android.ugc.aweme"),
        "淘宝" to listOf("com.taobao.taobao"),
        "京东" to listOf("com.jingdong.app.mall"),
        "高德地图" to listOf("com.autonavi.minimap"),
        "地图" to listOf("com.autonavi.minimap", "com.google.android.apps.maps"),
        "网易云音乐" to listOf("com.netease.cloudmusic"),
        "qq音乐" to listOf("com.tencent.qqmusic"),
        "相册" to listOf("com.miui.gallery", "com.google.android.apps.photos"),
        "设置" to listOf("com.android.settings"),
        "相机" to listOf("com.android.camera"),
        "chrome" to listOf("com.android.chrome"),
        "浏览器" to listOf("com.android.chrome", "com.android.browser", "com.mi.globalbrowser"),
        "chatgpt" to listOf("com.openai.chatgpt"),
        "claude" to listOf("com.anthropic.claude"),
        "gmail" to listOf("com.google.android.gm"),
        "邮箱" to listOf("com.google.android.gm", "com.android.email"),
        "计算器" to listOf("com.android.calculator2", "com.miui.calculator"),
        "日历" to listOf("com.android.calendar", "com.xiaomi.calendar"),
        "时钟" to listOf("com.android.deskclock"),
        "闹钟" to listOf("com.android.deskclock"),
        "文件管理" to listOf("com.android.fileexplorer", "com.mi.android.globalFileexplorer"),
        "微博" to listOf("com.sina.weibo"),
        "知乎" to listOf("com.zhihu.android"),
        "支付宝" to listOf("com.eg.android.AlipayGphone"),
        "美团" to listOf("com.sankuai.meituan"),
        "饿了么" to listOf("me.ele"),
        "钉钉" to listOf("com.alibaba.android.rimet"),
        "飞书" to listOf("com.ss.android.lark"),
        "telegram" to listOf("org.telegram.messenger"),
        "tg" to listOf("org.telegram.messenger"),
        "discord" to listOf("com.discord"),
        "spotify" to listOf("com.spotify.music"),
        "youtube" to listOf("com.google.android.youtube"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "twitter" to listOf("com.twitter.android"),
        "x" to listOf("com.twitter.android"),
        "instagram" to listOf("com.instagram.android"),
        "ins" to listOf("com.instagram.android"),
        "tiktok" to listOf("com.zhiliaoapp.musically")
    )

    fun launch(requestedName: String): DeviceLaunchResult {
        val request = normalize(requestedName)
        if (request.isBlank()) return DeviceLaunchResult(false, requestedName, "应用名称为空")

        val aliasPackages = aliases[request].orEmpty()
        aliasPackages.forEach { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                return start(intent, requestedName)
            }
        }

        val launcherQuery = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val candidates = packageManager.queryIntentActivities(launcherQuery, 0)
            .map { info ->
                val label = info.loadLabel(packageManager)?.toString().orEmpty()
                val packageName = info.activityInfo.packageName
                Triple(label, packageName, matchScore(request, label, packageName))
            }
            .filter { it.third < Int.MAX_VALUE }
            .sortedBy { it.third }

        val best = candidates.firstOrNull()
            ?: return DeviceLaunchResult(false, requestedName, "没有找到可启动的应用")
        val intent = packageManager.getLaunchIntentForPackage(best.second)
            ?: return DeviceLaunchResult(false, best.first, "应用没有可启动入口")
        return start(intent, best.first.ifBlank { requestedName })
    }

    private fun start(intent: Intent, displayName: String): DeviceLaunchResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        DeviceLaunchResult(true, displayName)
    } catch (e: Exception) {
        DeviceLaunchResult(false, displayName, e.message ?: "启动失败")
    }

    private fun matchScore(request: String, label: String, packageName: String): Int {
        val normalizedLabel = normalize(label)
        val normalizedPackage = normalize(packageName)
        return when {
            normalizedLabel == request -> 0
            normalizedLabel.startsWith(request) -> 1
            normalizedLabel.contains(request) -> 2
            request.contains(normalizedLabel) && normalizedLabel.length >= 2 -> 3
            normalizedPackage.contains(request) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun normalize(value: String): String = value
        .trim()
        .lowercase()
        .replace(" ", "")
        .replace("-", "")
        .replace("_", "")
        .removeSuffix("app")
        .removeSuffix("应用")
        .removeSuffix("软件")
}

const val DEVICE_TOOL_PROMPT: String = """
手机工具（必须遵守）：
手机轻量版只有用户明确要求时才可以请求以下工具：
- 打开/启动应用：open_app，格式 {"type":"open_app","app":"应用名称"}
- 手机直接联网搜索并读取摘要：web_search，格式 {"type":"web_search","query":"要搜索的内容"}
- 在小女友自己的聊天里主动发消息：proactive_messages，格式 {"type":"proactive_messages","count":2}（1~5 条）
先用符合当前角色的自然语言简短回应，再在末尾另起一行输出严格格式：
<device_action>{"type":"open_app","app":"应用名称"}</device_action>
不要为暗示、玩笑、角色扮演情节或你自己的建议输出工具标签。
不要声称已经执行；系统会显示确认窗口，只有用户确认后才执行。
禁止自行输出[工具]、[自动操作]、[手机工具]等执行记录；应用会在真实执行后生成结果。
手机端不读写文件、不读屏或自动点击、不控制音量亮度；这些功能迁移到电脑端。
proactive_messages 只会给用户的小女友聊天发消息，不能代发微信、短信或其他应用消息。
工具不能付款、登录或点击应用内部界面。
"""
