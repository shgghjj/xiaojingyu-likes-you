package com.pockettavern.app.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ScreenTextSnapshot(
    val packageName: String,
    val text: String,
    val capturedAt: Long
)

object ScreenContextRepository {
    @Volatile
    private var latest: ScreenTextSnapshot? = null

    fun update(snapshot: ScreenTextSnapshot) {
        latest = snapshot
    }

    fun latest(): ScreenTextSnapshot? = latest

    fun clear() {
        latest = null
    }
}

object ScreenAccessManager {
    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo.serviceInfo.packageName == context.packageName &&
                    info.resolveInfo.serviceInfo.name == ScreenContextService::class.java.name
            }
    }

    fun disable(): Boolean {
        val service = ScreenContextService.activeInstance ?: return false
        service.disableSelf()
        return true
    }

    fun clickText(text: String): String =
        ScreenContextService.activeInstance?.runOnServiceThread { clickVisibleText(text) }
            ?: "无障碍服务未开启"

    fun inputText(text: String, target: String?): String =
        ScreenContextService.activeInstance?.runOnServiceThread { inputVisibleText(text, target) }
            ?: "无障碍服务未开启"

    fun scroll(direction: String): String =
        ScreenContextService.activeInstance?.runOnServiceThread { scrollVisibleWindow(direction) }
            ?: "无障碍服务未开启"

    fun globalAction(action: String): String =
        ScreenContextService.activeInstance?.runOnServiceThread { performNamedGlobalAction(action) }
            ?: "无障碍服务未开启"

    fun tap(xPercent: Int, yPercent: Int): String =
        ScreenContextService.activeInstance?.runOnServiceThread { tapPercent(xPercent, yPercent) }
            ?: "无障碍服务未开启"
}

/**
 * Accessibility bridge used by the optional girlfriend automation mode. Screen text stays
 * in memory; this service never performs network calls. Actions are accepted only through
 * explicit app-side commands after the user enables the experimental mode.
 */
class ScreenContextService : AccessibilityService() {
    companion object {
        @Volatile
        internal var activeInstance: ScreenContextService? = null
    }

    private var lastCaptureElapsed = 0L
    private val serviceHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeInstance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val sourcePackage = event?.packageName?.toString().orEmpty()
        if (sourcePackage.isBlank() || sourcePackage == packageName) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureElapsed < 350L) return
        lastCaptureElapsed = now

        val root = rootInActiveWindow ?: return
        val values = LinkedHashSet<String>()
        collectVisibleText(root, values, depth = 0)
        root.recycle()
        val text = values.joinToString("\n").take(8_000).trim()
        if (text.isNotBlank()) {
            ScreenContextRepository.update(
                ScreenTextSnapshot(sourcePackage, text, System.currentTimeMillis())
            )
        }
    }

    internal fun runOnServiceThread(block: ScreenContextService.() -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var result = "操作超时"
        serviceHandler.post {
            result = runCatching { block() }.getOrElse { "操作失败：${it.message ?: "未知错误"}" }
            latch.countDown()
        }
        latch.await(2500, TimeUnit.MILLISECONDS)
        return result
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        depth: Int = 0,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (depth > 28 || !node.isVisibleToUser) return null
        if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findNode(child, depth + 1, predicate)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        listOf(node.text, node.contentDescription, node.hintText)
            .mapNotNull { it?.toString()?.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()

    internal fun clickVisibleText(target: String): String {
        val wanted = target.trim().take(120)
        if (wanted.isBlank()) return "缺少要点击的文字"
        val root = rootInActiveWindow ?: return "当前页面不可读取"
        val node = findNode(root) {
            val label = nodeLabel(it)
            label.equals(wanted, ignoreCase = true) || label.contains(wanted, ignoreCase = true)
        }
        root.recycle()
        if (node == null) return "没有找到“$wanted”"
        if (node.isPassword) {
            node.recycle()
            return "拒绝操作密码输入区域"
        }
        var clickable: AccessibilityNodeInfo = node
        while (!clickable.isClickable) {
            val parent = clickable.parent ?: break
            if (clickable !== node) clickable.recycle()
            clickable = parent
        }
        val success = clickable.isClickable && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clickable !== node) clickable.recycle()
        node.recycle()
        return if (success) "已点击“$wanted”" else "找到“$wanted”，但页面拒绝点击"
    }

    internal fun inputVisibleText(value: String, target: String?): String {
        val input = value.take(2000)
        val wanted = target?.trim().orEmpty()
        if (input.isBlank()) return "输入内容为空"
        val root = rootInActiveWindow ?: return "当前页面不可读取"
        val node = findNode(root) {
            it.isEditable && !it.isPassword &&
                (wanted.isBlank() || nodeLabel(it).contains(wanted, ignoreCase = true))
        } ?: findNode(root) { it.isEditable && !it.isPassword && it.isFocused }
            ?: findNode(root) { it.isEditable && !it.isPassword }
        root.recycle()
        if (node == null) return "没有找到可输入的非密码文本框"
        if (node.isPassword) {
            node.recycle()
            return "拒绝向密码输入框写入内容"
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, input)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        node.recycle()
        return if (success) "已填写文字" else "页面拒绝自动填写"
    }

    internal fun scrollVisibleWindow(direction: String): String {
        val normalized = direction.lowercase()
        val forward = normalized !in setOf("up", "left", "backward", "上", "向上")
        val root = rootInActiveWindow ?: return "当前页面不可读取"
        val node = findNode(root) { it.isScrollable }
        root.recycle()
        if (node == null) return "当前页面没有可滚动区域"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val success = node.performAction(action)
        node.recycle()
        return if (success) "已滚动页面" else "页面拒绝滚动"
    }

    internal fun performNamedGlobalAction(action: String): String {
        val code = when (action.lowercase()) {
            "back", "返回" -> GLOBAL_ACTION_BACK
            "home", "主页", "桌面" -> GLOBAL_ACTION_HOME
            "recents", "recent", "最近任务" -> GLOBAL_ACTION_RECENTS
            "notifications", "通知栏" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "不支持的系统动作：$action"
        }
        return if (performGlobalAction(code)) "已执行系统动作：$action" else "系统拒绝执行：$action"
    }

    internal fun tapPercent(xPercent: Int, yPercent: Int): String {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * xPercent.coerceIn(0, 100) / 100f
        val y = metrics.heightPixels * yPercent.coerceIn(0, 100) / 100f
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return if (dispatchGesture(gesture, null, null)) {
            "已点击屏幕位置 ${xPercent.coerceIn(0, 100)}%, ${yPercent.coerceIn(0, 100)}%"
        } else {
            "系统拒绝坐标点击"
        }
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        output: LinkedHashSet<String>,
        depth: Int
    ) {
        if (depth > 24 || output.size >= 240 || !node.isVisibleToUser) return
        if (!node.isPassword) {
            listOf(node.text, node.contentDescription, node.hintText)
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotBlank() && it.length <= 500 }
                .forEach(output::add)
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectVisibleText(child, output, depth + 1)
            child.recycle()
            if (output.size >= 240) break
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        ScreenContextRepository.clear()
        super.onDestroy()
    }
}
