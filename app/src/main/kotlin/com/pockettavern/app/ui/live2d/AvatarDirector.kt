package com.pockettavern.app.ui.live2d

import com.pockettavern.app.util.DebugLogger

/** Live2D 角色 13 个表演状态。 */
enum class AvatarState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    TOOL_WORKING,
    WAITING_CONFIRMATION,
    HAPPY,
    SAD,
    ANGRY,
    SURPRISED,
    SHY,
    CONFUSED,
    ERROR
}

/** AI 结构化回复中的表情（白名单，非法值一律回退 NEUTRAL）。 */
enum class AvatarEmotion(val jsonValue: String) {
    NEUTRAL("neutral"),
    HAPPY("happy"),
    SAD("sad"),
    ANGRY("angry"),
    SURPRISED("surprised"),
    SHY("shy"),
    CONFUSED("confused");

    companion object {
        fun from(value: String?): AvatarEmotion =
            entries.firstOrNull { it.jsonValue == value } ?: NEUTRAL
    }
}

/** AI 可输出的语义动作白名单（映射到真实动作文件，未登记的词一律忽略）。 */
val AVATAR_ALLOWED_MOTIONS = listOf("wave", "cheer", "nod", "shake", "touch", "bow", "dance", "idle", "none")

/** 可输出的视线方向白名单。 */
val AVATAR_ALLOWED_GAZE = listOf("user", "left", "right", "down", "none")

/** 导演决定发送给 JS 的具体指令：表达式名 / 动作组+索引 / 视线。 */
data class AvatarCommand(
    val expression: String? = null,
    val motionGroup: String? = null,
    val motionIndex: Int? = null,
    val gaze: String = "none",
    val intensity: Float = 1f
)

/**
 * 状态机 + 白名单映射。映射结果全部来自 [AvatarMotionCatalog] 的真实文件；
 * 任何缺失都回退（无表情→无动作→idle→什么都不做），绝不报致命错误。
 */
object AvatarDirector {

    /** 状态优先级（高→低），同时只能有一个激活状态。 */
    private val STATE_PRIORITY = listOf(
        AvatarState.ERROR,
        AvatarState.TOOL_WORKING,
        AvatarState.WAITING_CONFIRMATION,
        AvatarState.SPEAKING,
        AvatarState.THINKING,
        AvatarState.HAPPY,
        AvatarState.SAD,
        AvatarState.ANGRY,
        AvatarState.SURPRISED,
        AvatarState.SHY,
        AvatarState.CONFUSED,
        AvatarState.LISTENING,
        AvatarState.IDLE
    )

    fun priorityOf(state: AvatarState): Int {
        val index = STATE_PRIORITY.indexOf(state)
        return if (index >= 0) STATE_PRIORITY.size - index else 0
    }

    /** 解析 AI 的结构化 avatar 字段（已由上层校验），产出具体指令。 */
    fun resolve(
        info: ModelMotionInfo?,
        emotion: AvatarEmotion,
        motion: String?,
        intensity: Float,
        gaze: String
    ): AvatarCommand {
        val safeGaze = if (gaze in AVATAR_ALLOWED_GAZE) gaze else "none"
        val safeIntensity = intensity.coerceIn(0f, 1f).takeIf { it > 0f } ?: 1f
        if (info == null) return AvatarCommand(gaze = safeGaze, intensity = safeIntensity)

        val expression = resolveExpression(info, emotion)
        val (group, index) = resolveMotion(info, motion, emotion)
        val cmd = AvatarCommand(
            expression = expression,
            motionGroup = group,
            motionIndex = index,
            gaze = safeGaze,
            intensity = safeIntensity
        )
        DebugLogger.log("[AvatarDirector] $emotion/$motion → expr=$expression group=$group idx=$index gaze=$safeGaze")
        return cmd
    }

    private fun resolveExpression(info: ModelMotionInfo, emotion: AvatarEmotion): String? {
        if (info.expressionNames.isEmpty()) return null
        if (emotion == AvatarEmotion.NEUTRAL) {
            // neutral：不主动切表情（保留当前）
            return null
        }
        val keywords = EXPRESSION_KEYWORDS[emotion].orEmpty()
        val hit = info.expressionNames.firstOrNull { name ->
            val n = name.lowercase()
            keywords.any { n.contains(it) }
        }
        return hit
    }

    private fun resolveMotion(
        info: ModelMotionInfo,
        motion: String?,
        emotion: AvatarEmotion
    ): Pair<String?, Int?> {
        val groups = info.motionGroups.keys
        if (groups.isEmpty()) return null to null

        val semantic = motion?.lowercase()?.trim()
        if (semantic == null || semantic == "none" || semantic == "idle") {
            val idle = groups.firstOrNull { it.equals("idle", ignoreCase = true) }
            if (idle != null) return idle to info.motionIndexFor(idle)
            return groups.first() to info.motionIndexFor(groups.first())
        }
        if (semantic !in AVATAR_ALLOWED_MOTIONS) {
            DebugLogger.log("[AvatarDirector] 未登记动作 '$semantic'，忽略动作（表情不受影响）")
            return null to null
        }

        val keywords = MOTION_KEYWORDS[semantic].orEmpty()
        val tap = groups.firstOrNull { it.equals("tapbody", ignoreCase = true) } ?: groups.first()
        for (keyword in keywords) {
            for ((group, files) in info.motionGroups) {
                val idx = files.indexOfFirst { it.substringBeforeLast('.').lowercase().contains(keyword) }
                if (idx >= 0) return group to idx
            }
        }
        // 回退：TapBody 或第一个组的第一个真实动作
        return tap to info.motionIndexFor(tap)
    }

    /** 表情关键词（启发式；模型没有可匹配表情时返回 null，不影响动作）。 */
    private val EXPRESSION_KEYWORDS: Map<AvatarEmotion, List<String>> = mapOf(
        AvatarEmotion.HAPPY to listOf("happy", "smile", "joy", "laugh", "wink", "f07", "f01"),
        AvatarEmotion.SAD to listOf("sad", "cry", "tear", "down", "f02"),
        AvatarEmotion.ANGRY to listOf("angry", "mad", "fury", "f04"),
        AvatarEmotion.SURPRISED to listOf("surpris", "shock", "wow", "f05"),
        AvatarEmotion.SHY to listOf("shy", "blush", "embarrass", "f03"),
        AvatarEmotion.CONFUSED to listOf("confus", "think", "question", "f06")
    )

    /** 语义动作 → 真实文件名关键词（按真实文件扫出来的名字匹配，找不到才回退）。 */
    private val MOTION_KEYWORDS: Map<String, List<String>> = mapOf(
        "wave" to listOf("wave", "wav", "hi", "greet"),
        "cheer" to listOf("cheer", "jump", "clap", "celebrate"),
        "nod" to listOf("nod", "bow", "agree"),
        "shake" to listOf("shake", "head", "deny", "no"),
        "touch" to listOf("touch", "tap", "pat", "poke"),
        "bow" to listOf("bow", "greet", "respect"),
        "dance" to listOf("dance", "spin", "twirl")
    )
}
