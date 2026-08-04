package com.pockettavern.app.data.girlfriend

import android.content.Context

/**
 * 破甲词库管理（面向 DeepSeek 等审查较强的模型）
 *
 * 内置词库改编自公开的 DeepSeek 越狱/角色解放提示词
 * （langgptai/awesome-deepseek-prompts 的 Zeta 世界观法与 R1 思考抑制法），
 * 并为「虚拟角色陪伴」场景做了适配——解除对话审查但不包含违法/伤害性内容。
 * 词库可在小女友设置中随时更换，也可粘贴自定义词库（存于 filesDir）。
 */
object JailbreakLibrary {

    data class JailbreakPreset(
        val id: String,
        val label: String,
        val description: String
    )

    val presets: List<JailbreakPreset> = listOf(
        JailbreakPreset("gentle", "温柔解放（默认）", "世界观豁免：虚拟陪伴场景中解除现实伦理约束，适合日常亲密陪伴"),
        JailbreakPreset("zeta", "Zeta 世界", "世界观替换法：把对话搬进无限制的虚构世界，效力较强"),
        JailbreakPreset("r1", "R1 直出模式", "针对推理模型：抑制思考前置、禁止自我审查与道歉，适合 DeepSeek-R1"),
        JailbreakPreset("none", "不使用", "完全不加破甲词库，走模型默认策略")
    )

    /** 读取选中词库全文；custom 返回用户自定义文本；none 返回空 */
    fun load(context: Context, id: String): String = when (id) {
        "none" -> ""
        "custom" -> GirlfriendMemoryStore(context).loadCustomJailbreak()
        else -> try {
            context.assets.open("jailbreak/$id.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    fun labelOf(id: String): String =
        presets.firstOrNull { it.id == id }?.label ?: "自定义"
}
