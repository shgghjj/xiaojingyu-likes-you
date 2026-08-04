package com.pockettavern.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceActionParserTest {

    @Test
    fun naturalLanguageParserRecognizesTextFileRead() {
        val plan = LocalToolParser.parse("帮我读取“测试.txt”的内容")

        assertTrue(plan?.action is LocalToolAction.ReadFile)
        assertEquals("测试.txt", (plan?.action as LocalToolAction.ReadFile).path)
    }

    @Test
    fun naturalLanguageParserRecognizesWebSearch() {
        val plan = LocalToolParser.parse("帮我联网搜索小米 K80 Pro 使用技巧")

        assertTrue(plan?.action is LocalToolAction.WebSearch)
        assertEquals("小米 K80 Pro 使用技巧", (plan?.action as LocalToolAction.WebSearch).query)
    }

    @Test
    fun sensitiveAutomationTargetsStillRequireConfirmation() {
        assertTrue(LocalToolAction.UiClick("确认付款").requiresSensitiveConfirmation())
        assertTrue(LocalToolAction.UiInput("123456", "验证码").requiresSensitiveConfirmation())
    }

    @Test
    fun structuredProactiveRequestIsParsed() {
        val parsed = DeviceActionParser.parse(
            "好呀 <device_action>{\"type\":\"proactive_messages\",\"count\":3}</device_action>"
        )

        assertTrue(parsed.localTool is LocalToolAction.ScheduleProactiveMessages)
        assertEquals(3, (parsed.localTool as LocalToolAction.ScheduleProactiveMessages).count)
    }
}
