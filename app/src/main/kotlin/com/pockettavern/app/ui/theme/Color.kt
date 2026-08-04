package com.pockettavern.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── 明日方舟终端主题（Arknights Terminal）──
// 暗黑基底 + 青蓝强调 + 琥珀警示。极低饱和的暗色搭配高对比度终端感。
val DarkBackground = Color(0xFF0A0A0E)       // 极近黑，终端屏底
val DarkSurface = Color(0xFF111115)           // 面板表面
val DarkSurfaceVariant = Color(0xFF1A1A20)    // 面板浅层
val DarkInputBackground = Color(0xFF1E1E25)   // 输入区
val DarkCard = Color(0xFF141418)              // 卡片底

// 强调色 — 方舟终端青
val ArkCyan = Color(0xFF6EC6F0)               // 主强调：终端青蓝
val ArkCyanDim = Color(0xFF3A7A9E)            // 暗淡青
val ArkAmber = Color(0xFFF0A050)              // 琥珀/警示
val ArkRed = Color(0xFFE54860)                // 终端红

// 灰阶文字
val TextPrimary = Color(0xFFE8E8EC)
val TextSecondary = Color(0xFF9A9AA4)
val TextTertiary = Color(0xFF5E5E6A)
val TextDim = Color(0xFF3A3A44)

// 气泡 — 保留黑白对话感但加点温度
val UserBubble = Color(0xFF2A303A)            // 用户泡：深蓝灰
val UserBubbleText = Color(0xFFE0E0E8)
val AssistantBubble = Color(0xFF1C1C22)       // 助手泡：深紫灰
val AssistantBubbleText = TextPrimary

// 边框 / 分隔
val BorderSubtle = Color(0xFF252530)
val BorderPanel = Color(0xFF34344A)

// Markdown
val QuoteTextColor = Color(0xFF6E6E7A)
val ItalicTextColor = Color.Unspecified
val CodeBackgroundColor = Color(0xFF16161C)
