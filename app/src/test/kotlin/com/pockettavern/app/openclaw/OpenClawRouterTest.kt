package com.pockettavern.app.openclaw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenClawRouter 纯逻辑单元测试（无 Android 依赖）。
 * Run: ./gradlew :app:testDebugUnitTest
 */
class OpenClawRouterTest {

    // ── /claw 显式命令 ──────────────────────────────────────────────────────

    @Test
    fun `claw command extracts task text`() {
        assertEquals("打开相机", OpenClawRouter.extractClawTask("/claw 打开相机"))
        assertEquals("打开相机", OpenClawRouter.extractClawTask("/CLaw   打开相机"))
        assertNull(OpenClawRouter.extractClawTask("打开相机"))
        assertNull(OpenClawRouter.extractClawTask("claw 打开相机"))
    }

    @Test
    fun `claw command always routes to openclaw even if disabled`() {
        val d = OpenClawRouter.decide("打开相机", forcedByCommand = true, enabled = false)
        assertEquals(RouteTarget.OPENCLAW, d.route)
    }

    @Test
    fun `blank claw task routes to chat`() {
        val d = OpenClawRouter.decide("", forcedByCommand = true)
        assertEquals(RouteTarget.CHAT, d.route)
    }

    // ── 普通聊天不触发 ──────────────────────────────────────────────────────

    @Test
    fun `plain conversation never routes to openclaw`() {
        for (text in listOf("你好呀", "今天天气不错", "讲讲你的故事吧", "晚安")) {
            val d = OpenClawRouter.decide(text, enabled = true, autoInvoke = true)
            assertEquals("should be CHAT: $text", RouteTarget.CHAT, d.route)
        }
    }

    @Test
    fun `disabled routes everything to chat`() {
        val d = OpenClawRouter.decide("打开相机", forcedByButton = true, enabled = false)
        assertEquals(RouteTarget.CHAT, d.route)
    }

    // ── 工具按钮强制 ────────────────────────────────────────────────────────

    @Test
    fun `tool button forces openclaw with low risk no confirmation`() {
        val d = OpenClawRouter.decide("打开相机", forcedByButton = true, enabled = true)
        assertEquals(RouteTarget.OPENCLAW, d.route)
        assertFalse(d.requiresConfirmation)
        assertEquals(RiskLevel.LOW, d.risk)
    }

    // ── 自动识别（autoInvoke 语义在调用方控制，Router 只判断决策）─────────────

    @Test
    fun `auto detect routes tool keywords`() {
        val d = OpenClawRouter.decide("帮我打开相机")
        assertEquals(RouteTarget.OPENCLAW, d.route)
        assertFalse(d.requiresConfirmation)
    }

    @Test
    fun `auto detect routes volume query`() {
        assertEquals(RouteTarget.OPENCLAW, OpenClawRouter.decide("当前音量是多少").route)
        assertEquals(RouteTarget.OPENCLAW, OpenClawRouter.decide("把亮度调高").route)
    }

    @Test
    fun `auto detect stays on chat for casual tool-ish words`() {
        val d = OpenClawRouter.decide("你看过我的照片吗")
        assertEquals(RouteTarget.CHAT, d.route)
    }

    // ── 高风险操作强制确认 ──────────────────────────────────────────────────

    @Test
    fun `high risk actions always require confirmation`() {
        for (text in listOf(
            "帮我发一条微信消息给妈妈",
            "删除那个文件",
            "给这个账号转账 100 元",
            "在微博发布这条内容",
            "修改我的密码",
            "卸载抖音"
        )) {
            val d = OpenClawRouter.decide(text, forcedByCommand = true)
            assertEquals("should be OPENCLAW: $text", RouteTarget.OPENCLAW, d.route)
            assertTrue("should require confirmation: $text", d.requiresConfirmation)
            assertEquals("should be HIGH risk: $text", RiskLevel.HIGH, d.risk)
        }
    }

    @Test
    fun `confirmAll forces confirmation on low risk`() {
        val d = OpenClawRouter.decide("打开相机", forcedByButton = true, confirmAll = true)
        assertTrue(d.requiresConfirmation)
    }

    @Test
    fun `medium risk flagged as medium`() {
        val d = OpenClawRouter.decide("把屏幕亮度切换为自动", forcedByButton = true)
        assertEquals(RiskLevel.MEDIUM, d.risk)
        assertTrue(d.requiresConfirmation)
    }
}
