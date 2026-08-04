package com.pockettavern.app.ui.screens.chat

import com.pockettavern.app.ui.live2d.AvatarEmotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredReplyParserTest {

    @Test
    fun parsesNestedAvatarAndVoiceObject() {
        val raw = """{"text":"老大，我在呢~","avatar":{"emotion":"happy","motion":"wave","intensity":0.8,"gaze":"user"},"voice":{"style":"cheerful","speed":1.1}}"""

        val result = StructuredReplyParser.parse(raw)

        assertEquals("老大，我在呢~", result.text)
        assertEquals(AvatarEmotion.HAPPY, result.emotion)
        assertEquals("wave", result.motion)
        assertEquals("user", result.gaze)
        assertEquals("cheerful", result.voiceStyle)
    }

    @Test
    fun keepsEscapedDeviceActionInsideText() {
        val raw = """{"text":"我来改~ <device_action>{\"type\":\"edit_file\",\"path\":\"测试.txt\",\"content\":\"新内容\"}</device_action>","avatar":{"emotion":"happy"},"voice":{"style":"normal"}}"""

        val result = StructuredReplyParser.parse(raw)

        assertTrue(result.text.contains("<device_action>{\"type\":\"edit_file\""))
    }
}
