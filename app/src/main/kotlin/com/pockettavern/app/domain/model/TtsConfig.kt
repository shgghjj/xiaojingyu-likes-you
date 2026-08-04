package com.pockettavern.app.domain.model

data class TtsConfig(
    val enabled: Boolean = false,
    val provider: String = "system",      // "system" | "openai"
    val autoPlay: Boolean = true,
    val openAiUrl: String = "",
    val openAiKey: String = "",
    val openAiVoice: String = "alloy",
    val openAiModel: String = "tts-1",
    val speed: Float = 1.0f,
    val filterMode: String = "all",       // "all" | "quotes_only" | "no_asterisks"
    val systemEngine: String = "",          // empty = use system default
    val systemVoice: String = "",           // empty = use engine default
    // ── 语音（A 计划新增，独立于 OpenClaw 设置）──
    val voiceInputEnabled: Boolean = false, // 语音输入（按住说话）开关
    val volume: Float = 1.0f,               // 朗读音量 0..1
    val stylePrompt: String = "",           // 角色声音风格提示词（OpenAI instructions）
    val tapToInterrupt: Boolean = true,     // 点击 Live2D / 消息即可打断朗读
    val systemTtsFallback: Boolean = true   // 在线 TTS 失败时降级系统 TTS
)
