package com.pockettavern.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolParserTest {

    @Test
    fun `open app strips following task text`() {
        val action = LocalToolParser.parse("你可以打开b站给我放一个视频吗？")?.action
        assertTrue(action is LocalToolAction.OpenApp)
        assertEquals("b站", (action as LocalToolAction.OpenApp).name)
    }

    @Test
    fun `open app keeps full Chinese app label`() {
        val action = LocalToolParser.parse("帮我打开哔哩哔哩。")?.action
        assertEquals("哔哩哔哩", (action as LocalToolAction.OpenApp).name)
    }

    @Test
    fun `awareness state report is not parsed as app launch`() {
        assertNull(LocalToolParser.parse("我给你开启主动感知了。"))
    }

    @Test
    fun `maximum volume maps to one hundred percent`() {
        val action = LocalToolParser.parse("把音量调到最大声")?.action
        assertEquals(100, (action as LocalToolAction.SetVolumePercent).percent)
    }

    @Test
    fun `direct request schedules proactive girlfriend messages`() {
        val action = LocalToolParser.parse("你能全自动给我主动发两条信息吗")?.action
        assertTrue(action is LocalToolAction.ScheduleProactiveMessages)
        assertEquals(2, (action as LocalToolAction.ScheduleProactiveMessages).count)
    }

    @Test
    fun `weather question always uses real web search`() {
        val action = LocalToolParser.parse("上海今天天气怎么样？")?.action
        assertTrue(action is LocalToolAction.WebSearch)
        assertTrue((action as LocalToolAction.WebSearch).isPhoneCompanionAction())
    }

    @Test
    fun `phone companion whitelist excludes file and system controls`() {
        assertTrue(LocalToolAction.OpenApp("相机").isPhoneCompanionAction())
        assertTrue(LocalToolAction.WebSearch("今日新闻").isPhoneCompanionAction())
        assertTrue(LocalToolAction.ScheduleProactiveMessages(2).isPhoneCompanionAction())
        assertFalse(LocalToolAction.ReadFile("测试.txt").isPhoneCompanionAction())
        assertFalse(LocalToolAction.SetVolumePercent(80).isPhoneCompanionAction())
        assertFalse(LocalToolAction.ReadScreen().isPhoneCompanionAction())
    }
}
