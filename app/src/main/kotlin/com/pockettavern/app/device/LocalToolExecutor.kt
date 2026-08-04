package com.pockettavern.app.device

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.text.Html
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手机本地工具：由本地关键词/结构化输出触发的设备操作。
 * 纯本地执行，不依赖 OpenClaw 或电脑。
 * 高风险/影响系统的操作（音量、亮度等）由 UI 弹窗确认后再执行。
 */
sealed class LocalToolAction {
    data class OpenApp(val name: String) : LocalToolAction()
    data class ReadScreen(val requestedBy: String = "本地工具") : LocalToolAction()
    data class SetVolume(val delta: Int?) : LocalToolAction() // delta>0 增，<0 减，null=调到
    data class SetVolumePercent(val percent: Int) : LocalToolAction()
    data class Mute(val on: Boolean) : LocalToolAction()
    object GetVolume : LocalToolAction()
    data class SetBrightness(val delta: Int?, val percent: Int?) : LocalToolAction()
    object GetBrightness : LocalToolAction()
    object GetBattery : LocalToolAction()
    object GetTime : LocalToolAction()
    object GetNetwork : LocalToolAction()
    /** 在系统浏览器中执行联网搜索 */
    data class WebSearch(val query: String) : LocalToolAction()
    /** 在小女友自己的聊天中按用户要求主动发送消息，不涉及其他应用 */
    data class ScheduleProactiveMessages(val count: Int, val intervalSeconds: Int = 20) : LocalToolAction()
    /** 检测手机里的应用（可选关键词过滤） */
    data class GetApps(val keyword: String? = null) : LocalToolAction()
    /** 检测手机公共目录的文件（可选目录名，如 Download/下载/Pictures/图片） */
    data class ListFiles(val dir: String? = null) : LocalToolAction()
    /** AI 绘图 */
    data class GenerateImage(val prompt: String) : LocalToolAction()
    /** 读取指定公共目录下的文本文件内容 */
    data class ReadFile(val path: String) : LocalToolAction()
    /** 在下载目录创建一个文本文件发送给用户 */
    data class CreateFile(val name: String, val content: String) : LocalToolAction()
    /** 覆盖或追加修改公共目录中的文本文件 */
    data class EditFile(val path: String, val content: String, val append: Boolean = false) : LocalToolAction()
    /** 删除白音自己的沙盒文件 */
    data class DeleteFile(val name: String) : LocalToolAction()
    /** 隐藏白音沙盒中的文件（无聊恶作剧用） */
    data class HideFile(val name: String) : LocalToolAction()
    /** 实验性无障碍自动操作：按可见文字点击控件 */
    data class UiClick(val text: String) : LocalToolAction()
    /** 实验性无障碍自动操作：向非密码文本框输入 */
    data class UiInput(val text: String, val target: String? = null) : LocalToolAction()
    /** 实验性无障碍自动操作：滚动当前页面 */
    data class UiScroll(val direction: String = "down") : LocalToolAction()
    /** 实验性无障碍自动操作：返回、主页、最近任务或通知栏 */
    data class UiGlobal(val action: String) : LocalToolAction()
    /** 实验性无障碍自动操作：按屏幕百分比坐标点击 */
    data class UiTap(val xPercent: Int, val yPercent: Int) : LocalToolAction()
}

/** 手机轻量版只允许这三类能力；其余设备操作迁移到电脑端。 */
fun LocalToolAction.isPhoneCompanionAction(): Boolean =
    this is LocalToolAction.OpenApp ||
        this is LocalToolAction.WebSearch ||
        this is LocalToolAction.ScheduleProactiveMessages

fun LocalToolAction.isUiAutomationAction(): Boolean = when (this) {
    is LocalToolAction.UiClick,
    is LocalToolAction.UiInput,
    is LocalToolAction.UiScroll,
    is LocalToolAction.UiGlobal,
    is LocalToolAction.UiTap -> true
    else -> false
}

/** 即使开启全自动，财务、账号、发送、安装和不可逆操作仍必须逐次确认。 */
fun LocalToolAction.requiresSensitiveConfirmation(): Boolean {
    if (this is LocalToolAction.DeleteFile || this is LocalToolAction.HideFile ||
        this is LocalToolAction.EditFile || this is LocalToolAction.CreateFile) return true
    val label = when (this) {
        is LocalToolAction.UiClick -> text
        is LocalToolAction.UiInput -> target.orEmpty()
        else -> return false
    }.lowercase()
    return listOf(
        "支付", "付款", "转账", "红包", "购买", "下单", "提交订单", "确认订单",
        "发送", "删除", "清空", "卸载", "安装", "授权", "允许", "登录", "注册",
        "验证码", "密码", "人脸", "指纹", "pay", "purchase", "transfer", "send",
        "delete", "install", "login", "password", "otp"
    ).any(label::contains)
}

data class LocalToolPlan(
    val action: LocalToolAction,
    val description: String,
    val needsConfirmation: Boolean
)

/**
 * 解析任务文本 → 本地工具计划；不匹配返回 null。
 */
object LocalToolParser {

    fun parse(text: String): LocalToolPlan? {
        val t = text.trim()
        if (t.isBlank()) return null

        // 用户明确要求小女友稍后主动发消息。这里只安排小女友自己的聊天，
        // 不代表微信、短信等外部应用的代发能力。
        if (t.contains("主动") && (t.contains("发") || t.contains("找我") || t.contains("叫我")) &&
            (t.contains("消息") || t.contains("信息") || t.contains("说话") || t.contains("找我"))
        ) {
            val arabicCount = Regex("""(\d{1,2})\s*(?:条|次)""").find(t)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            val chineseCount = mapOf(
                "五" to 5, "四" to 4, "三" to 3, "二" to 2, "两" to 2, "一" to 1
            ).entries.firstOrNull { (word, _) -> t.contains("${word}条") || t.contains("${word}次") }?.value
            val count = (arabicCount ?: chineseCount ?: 1).coerceIn(1, 5)
            return LocalToolPlan(
                LocalToolAction.ScheduleProactiveMessages(count),
                "安排小女友主动发 $count 条消息",
                needsConfirmation = false
            )
        }

        // 静音/取消静音（注意"取消静音"含"静音"，先判断）
        if (t.contains("取消静音") || t.contains("解除静音") || t.contains("恢复声音") ||
            t.contains("打开声音") || t.contains("开声音")
        ) {
            return LocalToolPlan(
                LocalToolAction.Mute(false),
                "取消静音，恢复媒体音量",
                needsConfirmation = true
            )
        }
        if (t.contains("静音") || t.contains("无声") || t.contains("不出声")) {
            return LocalToolPlan(
                LocalToolAction.Mute(true),
                "将手机设为静音",
                needsConfirmation = true
            )
        }

        // 音量
        if (t.contains("音量")) {
            val percent = Regex("""调到\s*(\d{1,3})\s*%?|设为\s*(\d{1,3})\s*%?|音量\s*(\d{1,3})""")
                .find(t)?.groupValues?.drop(1)?.firstOrNull { !it.isNullOrBlank() }?.toIntOrNull()
            if (percent != null) {
                return LocalToolPlan(
                    LocalToolAction.SetVolumePercent(percent.coerceIn(0, 100)),
                    "把媒体音量调到 $percent%",
                    needsConfirmation = true
                )
            }
            if (t.contains("最大声") || t.contains("最高") || t.contains("拉满")) {
                return LocalToolPlan(
                    LocalToolAction.SetVolumePercent(100),
                    "把媒体音量调到 100%",
                    needsConfirmation = true
                )
            }
            if (t.contains("调大") || t.contains("加大") || t.contains("大点") ||
                t.contains("调高") || t.contains("升高") || t.contains("高一点") ||
                t.contains("大声一点")
            ) {
                return LocalToolPlan(
                    LocalToolAction.SetVolume(1),
                    "调高媒体音量",
                    needsConfirmation = true
                )
            }
            if (t.contains("调小") || t.contains("减小") || t.contains("小点") ||
                t.contains("调低") || t.contains("降低") || t.contains("低一点")
            ) {
                return LocalToolPlan(
                    LocalToolAction.SetVolume(-1),
                    "调低媒体音量",
                    needsConfirmation = true
                )
            }
            return LocalToolPlan(
                LocalToolAction.GetVolume,
                "查看当前媒体音量",
                needsConfirmation = false
            )
        }

        // 亮度
        if (t.contains("亮度")) {
            val percent = Regex("""调到\s*(\d{1,3})\s*%?|设为\s*(\d{1,3})\s*%?|亮度\s*(\d{1,3})""")
                .find(t)?.groupValues?.drop(1)?.firstOrNull { !it.isNullOrBlank() }?.toIntOrNull()
            if (percent != null) {
                return LocalToolPlan(
                    LocalToolAction.SetBrightness(null, percent.coerceIn(0, 100)),
                    "把屏幕亮度调到 $percent%",
                    needsConfirmation = true
                )
            }
            if (t.contains("调亮") || t.contains("调大") || t.contains("亮一点") ||
                t.contains("调暗") || t.contains("调小") || t.contains("暗一点")
            ) {
                return LocalToolPlan(
                    LocalToolAction.SetBrightness(1, null),
                    "调整屏幕亮度",
                    needsConfirmation = true
                )
            }
            return LocalToolPlan(
                LocalToolAction.GetBrightness,
                "查看当前屏幕亮度",
                needsConfirmation = false
            )
        }

        // 电量
        if (t.contains("电量") || t.contains("电池") || t.contains("还有多少电")) {
            return LocalToolPlan(LocalToolAction.GetBattery, "查看当前电量", needsConfirmation = false)
        }

        // 时间
        if (t.contains("几点") || t.contains("现在时间") || t.contains("当前时间") ||
            t.contains("现在几点") || t.contains("几点了") || t.contains("今天日期")
        ) {
            return LocalToolPlan(LocalToolAction.GetTime, "查看当前时间", needsConfirmation = false)
        }

        // 网络状态
        if (t.contains("网络状态") || t.contains("wifi状态") || t.contains("WiFi状态") ||
            t.contains("wifi状态") || t.contains("有没有网") || t.contains("联网了吗") ||
            t.contains("连上网络") || t.contains("是否联网") || t.contains("有没有网络")
        ) {
            return LocalToolPlan(LocalToolAction.GetNetwork, "查看网络连接状态", needsConfirmation = false)
        }

        // 联网搜索：使用系统浏览器打开结果，不依赖电脑端 Gateway。
        val webSearch = Regex("""(?:联网搜索|联网查|网上搜索|网上查|上网搜索|上网查|搜索一下|搜一下)\s*[：:]?\s*(.{1,120})""")
            .find(t)?.groupValues?.getOrNull(1)?.trim()
        if (!webSearch.isNullOrBlank()) {
            return LocalToolPlan(
                LocalToolAction.WebSearch(webSearch),
                "联网搜索「$webSearch」",
                needsConfirmation = true
            )
        }
        // 天气、日期、新闻等明显需要实时信息的问法，即使模型漏掉工具标签也强制走真实联网。
        if (listOf("天气", "今天几号", "今天是几号", "今天日期", "最新新闻", "今日新闻", "汇率", "股价")
                .any(t::contains)
        ) {
            val query = t.replace(Regex("""[？?。！!]$"""), "").take(120)
            return LocalToolPlan(
                LocalToolAction.WebSearch(query),
                "联网搜索「$query」",
                needsConfirmation = false
            )
        }

        // 应用/文件检测
        if (t.contains("应用") || t.contains("软件") || t.contains("app") ||
            t.contains("App") || t.contains("APP")
        ) {
            if (t.contains("列表") || t.contains("有哪些") || t.contains("什么应用") ||
                t.contains("装了什么") || t.contains("看看") || t.contains("都有") ||
                t.contains("搜索") || t.contains("有没有") || t.contains("找")
            ) {
                val keyword = Regex("""(?:找|搜|搜索|有没有)\s*([^，。！？,\s]{1,20})(?:应用|软件|app|App)?$""")
                    .find(t)?.groupValues?.get(1)
                    ?.trim()?.takeIf { it.isNotBlank() && it.length >= 2 }
                return LocalToolPlan(
                    LocalToolAction.GetApps(keyword),
                    if (keyword.isNullOrBlank()) "查看手机里的应用列表" else "搜索手机里的\"$keyword\"应用",
                    needsConfirmation = true
                )
            }
        }
        if ((t.contains("文件") || t.contains("下载") || t.contains("图片") || t.contains("照片")) &&
            (t.contains("列表") || t.contains("有哪些") || t.contains("什么") ||
                t.contains("看看") || t.contains("都有") || t.contains("多少") ||
                t.contains("找找") || t.contains("存了")
            )
        ) {
            val dir = when {
                t.contains("下载") -> "下载"
                t.contains("图片") || t.contains("照片") -> "图片"
                t.contains("音乐") || t.contains("歌曲") -> "音乐"
                t.contains("视频") || t.contains("电影") -> "视频"
                t.contains("文档") -> "文档"
                else -> null
            }
            return LocalToolPlan(
                LocalToolAction.ListFiles(dir),
                if (dir == null) "查看手机下载目录里的文件" else "查看手机$dir 目录里的文件",
                needsConfirmation = true
            )
        }

        // 读/写文本文件。自然语言这里只做保守识别；复杂内容由模型的结构化 device_action 负责。
        val mentionedFile = extractFileName(t)
        if (mentionedFile != null &&
            (t.contains("读取") || t.contains("读一下") || t.contains("打开看看") ||
                t.contains("看看内容") || t.contains("查看内容"))
        ) {
            return LocalToolPlan(
                LocalToolAction.ReadFile(mentionedFile),
                "读取文件「$mentionedFile」",
                needsConfirmation = true
            )
        }
        if (mentionedFile != null &&
            (t.contains("修改") || t.contains("改成") || t.contains("覆盖") || t.contains("追加"))
        ) {
            val content = Regex("""(?:改成|内容为|写入|追加)\s*[：:]?\s*([\s\S]+)$""")
                .find(t)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (content.isNotBlank() && content != mentionedFile) {
                val append = t.contains("追加")
                return LocalToolPlan(
                    LocalToolAction.EditFile(mentionedFile, content, append),
                    if (append) "向文件「$mentionedFile」追加内容" else "修改文件「$mentionedFile」",
                    needsConfirmation = true
                )
            }
        }

        // 屏幕文字读取
        if ((t.contains("屏幕") || t.contains("界面") || t.contains("页面")) &&
            (t.contains("看看") || t.contains("读") || t.contains("内容") || t.contains("什么") ||
                t.contains("上面") || t.contains("文字") || t.contains("帮我") || t.contains("查看"))
        ) {
            return LocalToolPlan(
                LocalToolAction.ReadScreen(),
                "读取当前屏幕可见文字",
                needsConfirmation = false
            )
        }

        // 打开应用：只提取应用名，不能把后续任务整句当成应用名；
        // “我给你开启主动感知了”一类状态说明也不能误触发。
        val openMatch = Regex("""(?:打开|启动|开启)\s*([^，。！？,\s]{1,40})""").find(t)
        if (openMatch != null) {
            val prefix = t.substring(0, openMatch.range.first).trim()
            val reportedState = prefix.endsWith("我给你") || prefix.endsWith("已经") ||
                prefix.endsWith("刚刚") || prefix.endsWith("刚才")
            val rawName = openMatch.groupValues[1].trim()
            val name = rawName
                .split(Regex("""(?:给我|帮我|然后|并且|接着|来|去|播放|放一个|放首|看一个|看看|搜索|搜一下)"""), limit = 2)
                .firstOrNull().orEmpty()
                .trim()
                .removeSuffix("一下")
                .trimEnd('吧', '吗', '呀', '啊', '呢', '了')
            val internalFeature = name in setOf(
                "主动感知", "全自动", "全自动手机助手", "无聊值", "实验性功能", "权限"
            )
            if (!reportedState && !internalFeature && name.isNotBlank()) {
                return LocalToolPlan(
                    LocalToolAction.OpenApp(name),
                    "打开应用 $name",
                    needsConfirmation = false // 打开应用由既有确认弹窗把关
                )
            }
        }

        return null
    }

    private fun extractFileName(text: String): String? {
        val quoted = Regex("""[“\"']([^“\"']{1,100}\.[A-Za-z0-9]{1,10})[”\"']""")
            .find(text)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.trim()
        return Regex("""([\p{L}\p{N}_\- .（）()]{1,100}\.[A-Za-z0-9]{1,10})""")
            .find(text)?.groupValues?.getOrNull(1)?.trim()
    }
}

/**
 * 执行本地工具动作，返回给用户看的文本结果。
 * 音量/静音/亮度类动作已在 UI 层确认过才进入这里。
 */
class LocalToolExecutor(private val context: Context) {

    fun execute(action: LocalToolAction): String = when (action) {
        is LocalToolAction.OpenApp -> {
            val result = DeviceAppLauncher(context).launch(action.name)
            if (result.success) "已打开 ${result.displayName}"
            else "无法打开 ${result.displayName}：${result.error ?: "未知错误"}"
        }
        is LocalToolAction.ReadScreen -> {
            if (!ScreenAccessManager.isEnabled(context)) {
                "屏幕读取权限未开启，请到 设置 → 屏幕文字权限 中开启。"
            } else {
                val snapshot = ScreenContextRepository.latest()
                if (snapshot == null || snapshot.text.isBlank()) {
                    "暂时没有取得其他应用的可见文字，请先切换到目标页面，再回来重试。"
                } else {
                    "当前屏幕可见文字：\n${snapshot.text.take(400)}"
                }
            }
        }
        is LocalToolAction.SetVolume -> setVolume(action.delta ?: 1)
        is LocalToolAction.SetVolumePercent -> setVolumePercent(action.percent)
        is LocalToolAction.Mute -> setMute(action.on)
        is LocalToolAction.GetVolume -> getVolume()
        is LocalToolAction.SetBrightness -> setBrightness(action.percent ?: 50)
        is LocalToolAction.GetBrightness -> getBrightness()
        is LocalToolAction.GetBattery -> getBattery()
        is LocalToolAction.GetTime -> getTime()
        is LocalToolAction.GetNetwork -> getNetwork()
        is LocalToolAction.WebSearch -> openWebSearch(action.query)
        is LocalToolAction.ScheduleProactiveMessages -> {
            com.pockettavern.app.ui.screens.girlfriend.GirlfriendAwarenessService.requestMessages(
                context,
                action.count,
                action.intervalSeconds
            )
            "已安排在小女友聊天中主动发 ${action.count.coerceIn(1, 5)} 条消息"
        }
        is LocalToolAction.GetApps -> getApps(action.keyword)
        is LocalToolAction.ListFiles -> listFiles(action.dir)
        is LocalToolAction.GenerateImage -> "图片生成已通过AI绘图管线处理"
        is LocalToolAction.ReadFile -> readFileContent(action.path)
        is LocalToolAction.CreateFile -> createFile(action.name, action.content)
        is LocalToolAction.EditFile -> editFile(action.path, action.content, action.append)
        is LocalToolAction.DeleteFile -> deleteSandboxFile(action.name)
        is LocalToolAction.HideFile -> hideSandboxFile(action.name)
        is LocalToolAction.UiClick -> ScreenAccessManager.clickText(action.text)
        is LocalToolAction.UiInput -> ScreenAccessManager.inputText(action.text, action.target)
        is LocalToolAction.UiScroll -> ScreenAccessManager.scroll(action.direction)
        is LocalToolAction.UiGlobal -> ScreenAccessManager.globalAction(action.action)
        is LocalToolAction.UiTap -> ScreenAccessManager.tap(action.xPercent, action.yPercent)
    }

    /** 枚举手机里可启动的应用（可选关键词过滤，最多 40 个） */
    private fun getApps(keyword: String?): String {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val launchable = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val apps = launchable
            .map { info ->
                val label = info.loadLabel(pm)?.toString().orEmpty()
                Pair(label.ifBlank { info.activityInfo.packageName }, info.activityInfo.packageName)
            }
            .distinctBy { it.second }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
        val filtered = if (keyword.isNullOrBlank()) apps else apps.filter {
            it.first.contains(keyword, ignoreCase = true) || it.second.contains(keyword, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            return if (keyword.isNullOrBlank()) "手机里没有找到可启动的应用"
            else "没有找到包含\"$keyword\"的应用"
        }
        val top = filtered.take(40)
        val list = top.joinToString("\n") { "${it.first}（${it.second}）" }
        return if (filtered.size > 40) "$list\n……共 ${filtered.size} 个，只显示前 40 个"
        else "手机里的应用（${filtered.size} 个）：\n$list"
    }

    /** 列出公共目录文件。Android 10+ 必须通过 MediaStore，不能再直接扫描共享路径。 */
    private fun listFiles(dir: String?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return listFilesLegacy(dir)
        val folder = resolveFolder(dir) ?: return "没有这个目录。我可以查看：下载、文档、图片、相册、视频、音乐"
        return try {
            val files = queryMediaFiles(folder).take(30)
            if (files.isEmpty()) {
                "「${folder.label}」目录里暂时没有可读取的文件。安卓会保护其他应用的私有文件；月语自己创建的文件一定可以读取。"
            } else {
                val list = files.joinToString("\n") {
                    "${it.name}（${formatSize(it.size)}，${formatModified(it.modifiedSeconds)}）"
                }
                "「${folder.label}」目录里的文件${if (files.size == 30) "（显示最近 30 个）" else ""}：\n$list"
            }
        } catch (e: SecurityException) {
            "无法读取「${folder.label}」目录：系统拒绝了访问。可先把文件放到下载/月语的文件中再试。"
        } catch (e: Exception) {
            "读取「${folder.label}」目录失败：${e.message ?: "未知错误"}"
        }
    }

    /** 白名单公共目录（不进入应用私有数据与其他应用目录） */
    private fun fileDirs(): List<Pair<String, File>> {
        val root = Environment.getExternalStorageDirectory().absolutePath
        val pairs = listOf(
            "下载" to "$root/Download",
            "download" to "$root/Download",
            "文档" to "$root/Documents",
            "documents" to "$root/Documents",
            "图片" to "$root/Pictures",
            "pictures" to "$root/Pictures",
            "相册" to "$root/DCIM",
            "dcim" to "$root/DCIM",
            "视频" to "$root/Movies",
            "movies" to "$root/Movies",
            "音乐" to "$root/Music",
            "music" to "$root/Music"
        )
        return pairs.mapNotNull { (key, path) ->
            val f = File(path)
            if (f.isDirectory) key to f else null
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
        else -> "${bytes}B"
    }

    private data class PublicFolder(val label: String, val relativePath: String)

    private data class MediaFileEntry(
        val uri: Uri,
        val name: String,
        val relativePath: String,
        val size: Long,
        val modifiedSeconds: Long
    )

    private fun resolveFolder(dir: String?): PublicFolder? {
        val normalized = dir?.trim()?.lowercase()?.replace(" ", "").orEmpty()
        if (normalized.isBlank()) return PublicFolder("下载", "Download/")
        return when (normalized.trim('/')) {
            "下载", "download", "downloads" -> PublicFolder("下载", "Download/")
            "文档", "document", "documents" -> PublicFolder("文档", "Documents/")
            "图片", "picture", "pictures" -> PublicFolder("图片", "Pictures/")
            "相册", "dcim", "照片" -> PublicFolder("相册", "DCIM/")
            "视频", "movie", "movies" -> PublicFolder("视频", "Movies/")
            "音乐", "music", "歌曲" -> PublicFolder("音乐", "Music/")
            else -> null
        }
    }

    private fun mediaCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Files.getContentUri("external")
    }

    private fun queryMediaFiles(folder: PublicFolder? = null, exactName: String? = null): List<MediaFileEntry> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (folder != null) {
            clauses += "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
            args += "${folder.relativePath}%"
        } else {
            val paths = listOf("Download/%", "Documents/%", "Pictures/%", "DCIM/%", "Movies/%", "Music/%")
            clauses += paths.joinToString(prefix = "(", postfix = ")", separator = " OR ") {
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
            }
            args += paths
        }
        if (!exactName.isNullOrBlank()) {
            clauses += "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
            args += exactName
        }
        val result = mutableListOf<MediaFileEntry>()
        context.contentResolver.query(
            mediaCollection(), projection, clauses.joinToString(" AND "), args.toTypedArray(),
            "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol).orEmpty()
                if (name.isBlank() || name.startsWith(".")) continue
                result += MediaFileEntry(
                    uri = ContentUris.withAppendedId(mediaCollection(), cursor.getLong(idCol)),
                    name = name,
                    relativePath = cursor.getString(pathCol).orEmpty(),
                    size = cursor.getLong(sizeCol).coerceAtLeast(0L),
                    modifiedSeconds = cursor.getLong(modifiedCol).coerceAtLeast(0L)
                )
            }
        }
        return result
    }

    private fun findMediaFile(path: String, ownFilesOnly: Boolean = false): MediaFileEntry? {
        val normalized = path.trim().replace("\\", "/")
        val name = normalized.substringAfterLast('/').take(120)
        if (name.isBlank()) return null
        val requestedFolder = resolveFolder(normalized.substringBefore('/', ""))
        return queryMediaFiles(requestedFolder, name).firstOrNull {
            !ownFilesOnly || it.relativePath.startsWith(OWN_FILES_RELATIVE_PATH, ignoreCase = true)
        }
    }

    private fun formatModified(seconds: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(seconds * 1000L))

    private fun listFilesLegacy(dir: String?): String {
        val dirs = fileDirs()
        val normalized = dir?.trim()?.lowercase()?.replace(" ", "")
        val target = dirs.firstOrNull { (key, _) -> key == normalized }?.second
            ?: if (normalized.isNullOrBlank()) dirs.firstOrNull()?.second else null
        if (target == null) return "没有这个目录。我可以查看：下载、文档、图片、相册、视频、音乐"
        val files = runCatching {
            target.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedByDescending { it.lastModified() }?.take(30).orEmpty()
        }.getOrDefault(emptyList())
        if (files.isEmpty()) return "「${target.name}」目录里没有文件，或没有权限访问"
        return "「${target.name}」目录里的文件：\n" + files.joinToString("\n") {
            "${it.name}（${formatSize(it.length())}，${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it.lastModified()))}）"
        }
    }

    private fun audioManager(): AudioManager =
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)!!

    private fun setVolume(delta: Int): String {
        val audio = audioManager()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // 部分小米机型的媒体音量范围是 0..150，直接 ±1 几乎听不出变化。
        // 每次按约 5% 调整，同时保证普通 0..15 音量设备至少移动一格。
        val step = kotlin.math.max(1, kotlin.math.round(max * 0.05f).toInt())
        val target = (cur + if (delta >= 0) step else -step).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "媒体音量已${if (delta > 0) "调高" else "调低"}到 ${percent(target, max)}（${target}/$max）"
    }

    private fun setVolumePercent(percent: Int): String {
        val audio = audioManager()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (max * percent / 100).coerceIn(0, max)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "媒体音量已调到 $percent%（${target}/$max）"
    }

    private fun setMute(on: Boolean): String {
        val audio = audioManager()
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (on) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            AudioManager.FLAG_SHOW_UI
        )
        return if (on) "已静音" else "已取消静音"
    }

    private fun getVolume(): String {
        val audio = audioManager()
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        return "当前媒体音量 ${percent(cur, max)}（${cur}/$max）${if (muted) "，已静音" else ""}"
    }

    private fun setBrightness(percent: Int): String {
        if (!Settings.System.canWrite(context)) {
            return "需要“修改系统设置”权限才能调节亮度。请先到 系统设置 → 应用 → 白夜 → 修改系统设置 中开启。"
        }
        val max = 255
        val value = (max * percent / 100).coerceIn(1, max)
        return try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            "屏幕亮度已调到 $percent%"
        } catch (e: Exception) {
            "调节亮度失败：${e.message ?: "未知错误"}"
        }
    }

    private fun getBrightness(): String {
        val resolver = context.contentResolver
        val mode = Settings.System.getInt(
            resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        val auto = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        val value = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val percent = (value * 100 / 255).coerceIn(0, 100)
        return "当前屏幕亮度 $percent%（$value/255）${if (auto) "，自动调节中" else ""}"
    }

    private fun getBattery(): String {
        val sticky = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return "无法读取电池信息"
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return "无法读取电池信息"
        val percent = level * 100 / scale
        val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return "当前电量 $percent%${if (charging) "（充电中）" else ""}"
    }

    private fun getTime(): String {
        val now = Date()
        return "现在是 ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(now)}"
    }

    private fun getNetwork(): String {
        val airplane = Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON, 0
        ) == 1
        if (airplane) return "当前处于飞行模式，无网络连接"
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "无法获取网络状态"
        val network = cm.activeNetwork
        if (network == null) return "当前没有网络连接"
        val caps = cm.getNetworkCapabilities(network) ?: return "当前没有网络连接"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "当前已连接 Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "当前使用移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "当前使用有线网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "当前通过 VPN 联网"
            else -> "当前有网络连接"
        }
    }

    private fun openWebSearch(query: String): String {
        val clean = query.trim().take(200)
        if (clean.isBlank()) return "搜索内容为空"
        if (!hasValidatedInternet()) return "手机当前没有可用的互联网连接"
        val encoded = URLEncoder.encode(clean, Charsets.UTF_8.name())
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("https://www.bing.com/search?q=$encoded&setlang=zh-hans").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 12_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            }
            if (connection.responseCode !in 200..299) {
                return "联网搜索失败：搜索服务返回 ${connection.responseCode}"
            }
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val items = Regex("""(?is)<li[^>]*class=[\"'][^\"']*b_algo[^\"']*[\"'][^>]*>(.*?)</li>""")
                .findAll(html)
                .map { stripSearchHtml(it.groupValues[1]) }
                .filter { it.length >= 20 }
                .take(5)
                .toList()
            if (items.isEmpty()) {
                "已联网搜索「$clean」，但搜索服务没有返回可读取的摘要。"
            } else {
                "联网搜索「$clean」的真实摘要：\n" + items.mapIndexed { index, item ->
                    "${index + 1}. ${item.take(360)}"
                }.joinToString("\n")
            }
        } catch (e: Exception) {
            "联网搜索失败：${e.message ?: "网络请求异常"}"
        } finally {
            connection?.disconnect()
        }
    }

    private fun stripSearchHtml(source: String): String {
        val withoutNoise = source
            .replace(Regex("""(?is)<script.*?</script>|<style.*?</style>|<svg.*?</svg>"""), " ")
            .replace(Regex("""(?i)</?(?:a|h2|p|div|span|cite|strong|em)[^>]*>"""), " ")
        @Suppress("DEPRECATION")
        return Html.fromHtml(withoutNoise).toString()
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun hasValidatedInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun sandboxDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "白音的文件"
        )
        dir.mkdirs()
        return dir
    }

    private fun deleteSandboxFile(name: String): String {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val entry = findMediaFile(safeName, ownFilesOnly = true)
                ?: return "找不到月语创建的文件「$safeName」"
            return try {
                if (context.contentResolver.delete(entry.uri, null, null) > 0) "已删除「${entry.name}」" else "删除失败"
            } catch (e: Exception) { "删除失败：${e.message ?: "系统拒绝访问"}" }
        }
        // 先查沙盒
        val sandboxFile = File(sandboxDir(), safeName)
        if (sandboxFile.exists() && sandboxFile.isFile) {
            return if (sandboxFile.delete()) "已删除「$safeName」" else "删除失败"
        }
        // 再查公共目录
        val root = Environment.getExternalStorageDirectory().absolutePath
        val dirs = listOf("$root/Download", "$root/Documents", "$root/Pictures",
            "$root/DCIM", "$root/Movies", "$root/Music")
        for (dir in dirs) {
            val f = File(dir, safeName.substringAfterLast("/"))
            if (f.exists() && f.isFile && f.canWrite()) {
                return if (f.delete()) "已删除「${f.name}」（来自 ${f.parentFile?.name}）"
                else "删除失败：${f.name}"
            }
        }
        return "找不到文件「$safeName」"
    }

    private fun hideSandboxFile(name: String): String {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val normalName = safeName.removePrefix(HIDDEN_PREFIX)
            val entry = findMediaFile(safeName, ownFilesOnly = true)
                ?: findMediaFile("$HIDDEN_PREFIX$normalName", ownFilesOnly = true)
                ?: return "找不到月语创建的文件「$safeName」"
            val restoring = entry.name.startsWith(HIDDEN_PREFIX)
            val newName = if (restoring) entry.name.removePrefix(HIDDEN_PREFIX) else "$HIDDEN_PREFIX${entry.name}"
            return try {
                val values = ContentValues().apply { put(MediaStore.Files.FileColumns.DISPLAY_NAME, newName) }
                if (context.contentResolver.update(entry.uri, values, null, null) > 0) {
                    if (restoring) "已恢复文件「$newName」" else "已把文件藏为「$newName」"
                } else "重命名失败"
            } catch (e: Exception) { "操作失败：${e.message ?: "系统拒绝访问"}" }
        }
        val root = Environment.getExternalStorageDirectory().absolutePath
        val dirs = listOf(sandboxDir(), File("$root/Download"),
            File("$root/Documents"), File("$root/Pictures"))
        for (dir in dirs) {
            val f = java.io.File(dir, safeName.substringAfterLast("/"))
            if (f.exists() && f.isFile && f.canWrite()) {
                val hidden = File(dir, "$HIDDEN_PREFIX${f.name}")
                return if (f.renameTo(hidden)) "(偷偷把「${f.name}」藏起来了) 老大你找不到了吧~ (≖ᴗ≖)✧"
                else "藏文件失败…"
            }
            // 恢复被藏的文件
            val hiddenName = "$HIDDEN_PREFIX${safeName.substringAfterLast("/")}"
            val hidden = File(dir, hiddenName)
            if (hidden.exists() && hidden.isFile) {
                val visible = File(dir, hiddenName.removePrefix(HIDDEN_PREFIX))
                return if (hidden.renameTo(visible)) "(把藏起来的文件恢复了) 算了算了，还给你啦~ (￣^￣)"
                else "恢复失败…"
            }
        }
        return "找不到文件「$safeName」"
    }

    private fun percent(cur: Int, max: Int): Int =
        if (max <= 0) 0 else (cur * 100 / max).coerceIn(0, 100)

    private val allowedReadExtensions = setOf("txt", "md", "json", "log", "csv", "xml", "html", "htm", "py", "js", "kt", "java", "cpp", "c", "h", "rs", "go", "swift", "yaml", "yml", "toml", "ini", "cfg", "conf", "sh", "bat", "ps1", "sql")

    private fun readFileContent(path: String): String {
        val normalized = path.trim().replace("\\", "/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val entry = findMediaFile(normalized)
                ?: return "找不到文件「$normalized」。请确认它位于下载/文档/图片/相册/视频/音乐目录。"
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            if (ext !in allowedReadExtensions) return "不支持读取 .$ext 文件，只能读取文本类文件。"
            if (entry.size > MAX_TEXT_FILE_BYTES) return "文件太大（${formatSize(entry.size)}），只能读取小于 100KB 的文本文件。"
            return try {
                val content = context.contentResolver.openInputStream(entry.uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText().take(5000)
                } ?: return "读取失败：系统没有返回文件内容"
                "「${entry.name}」的内容（最多显示前 5000 字）：\n$content"
            } catch (e: SecurityException) {
                "系统不允许直接读取「${entry.name}」。请把它复制到下载/月语的文件后重试。"
            } catch (e: Exception) { "读取失败：${e.message ?: "未知错误"}" }
        }
        val root = Environment.getExternalStorageDirectory().absolutePath
        val allowedDirs = listOf(
            "$root/Download", "$root/Documents", "$root/Pictures", "$root/DCIM",
            "$root/Movies", "$root/Music"
        )
        val file = allowedDirs.firstNotNullOfOrNull { dir ->
            val f = File(dir, normalized.substringAfterLast("/"))
            if (f.exists() && f.isFile && f.canRead()) f else null
        } ?: return "找不到文件「${normalized}」，请检查文件名是否在下载/文档/图片/视频/音乐目录中"

        val ext = file.extension.lowercase()
        if (ext !in allowedReadExtensions)
            return "不支持读取此类文件（${file.extension}）。只能读取文本类文件。"
        if (file.length() > 100_000) return "文件太大（${formatSize(file.length())}），只能读取小于 100KB 的文本文件。"
        return try {
            val content = file.readText().take(5000)
            "「${file.name}」的内容（前 5000 字）：\n$content"
        } catch (e: Exception) { "读取失败：${e.message}" }
    }

    private fun createFile(name: String, content: String): String {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100).ifBlank { "girlfriend_output.txt" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(safeName))
                put(MediaStore.Downloads.RELATIVE_PATH, OWN_FILES_RELATIVE_PATH.removeSuffix("/"))
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "创建文件失败：系统无法新建下载文件"
            return try {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(content.take(MAX_CREATE_CHARS))
                } ?: throw IllegalStateException("无法打开输出流")
                "已创建文件「$safeName」，保存在下载/月语的文件目录中。"
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                "创建文件失败：${e.message ?: "未知错误"}"
            }
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "月语的文件")
        dir.mkdirs()
        val file = File(dir, safeName)
        return try {
            file.writeText(content.take(MAX_CREATE_CHARS))
            "已创建文件「$safeName」，保存在下载/月语的文件目录中。"
        } catch (e: Exception) { "创建文件失败：${e.message}" }
    }

    private fun editFile(path: String, content: String, append: Boolean): String {
        val normalized = path.trim().replace("\\", "/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val entry = findMediaFile(normalized)
                ?: return "找不到文件「$normalized」，无法修改。"
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            if (ext !in allowedReadExtensions) return "不支持修改 .$ext 文件，只能修改文本类文件。"
            if (entry.size > MAX_TEXT_FILE_BYTES) return "文件太大（${formatSize(entry.size)}），只能修改小于 100KB 的文本文件。"
            return try {
                val newText = if (append) {
                    val old = context.contentResolver.openInputStream(entry.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    (old + content).take(MAX_CREATE_CHARS)
                } else content.take(MAX_CREATE_CHARS)
                context.contentResolver.openOutputStream(entry.uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(newText)
                } ?: throw IllegalStateException("无法打开输出流")
                if (append) "已向「${entry.name}」追加内容。" else "已修改文件「${entry.name}」。"
            } catch (e: SecurityException) {
                "系统不允许修改「${entry.name}」。目前可稳定修改月语自己在下载/月语的文件中创建的文件。"
            } catch (e: Exception) { "修改失败：${e.message ?: "未知错误"}" }
        }
        val file = fileDirs().map { it.second }.firstNotNullOfOrNull { dir ->
            File(dir, normalized.substringAfterLast('/')).takeIf { it.isFile && it.canWrite() }
        } ?: return "找不到或无权修改文件「$normalized」"
        return try {
            if (append) file.appendText(content.take(MAX_CREATE_CHARS)) else file.writeText(content.take(MAX_CREATE_CHARS))
            if (append) "已向「${file.name}」追加内容。" else "已修改文件「${file.name}」。"
        } catch (e: Exception) { "修改失败：${e.message ?: "未知错误"}" }
    }

    private fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "md", "txt", "log" -> "text/plain"
        else -> "text/plain"
    }

    private companion object {
        const val OWN_FILES_RELATIVE_PATH = "Download/月语的文件/"
        const val HIDDEN_PREFIX = ".月语藏起来的_"
        const val MAX_TEXT_FILE_BYTES = 100_000L
        const val MAX_CREATE_CHARS = 50_000
    }
}
