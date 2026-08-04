package com.pockettavern.app.ui.screens.chat

import com.pockettavern.app.ui.live2d.AVATAR_ALLOWED_GAZE
import com.pockettavern.app.ui.live2d.AVATAR_ALLOWED_MOTIONS
import com.pockettavern.app.ui.live2d.AvatarEmotion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** AI 结构化回复解析结果。任何解析失败都回退纯文本 + neutral，绝不闪退。 */
data class StructuredReply(
    val text: String,
    val emotion: AvatarEmotion = AvatarEmotion.NEUTRAL,
    val motion: String? = null,
    val intensity: Float = 1f,
    val gaze: String = "none",
    val voiceStyle: String? = null,
    val voiceSpeed: Float? = null
) {
    companion object {
        val TEXT_ONLY = StructuredReply(text = "")
    }
}

object StructuredReplyParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val ALLOWED_VOICE_STYLES = listOf("normal", "gentle", "cheerful", "sad", "serious", "whisper")

    fun parse(fullText: String): StructuredReply {
        if (fullText.isBlank()) return StructuredReply(fullText)
        val candidates = findJsonCandidates(fullText)
        for (candidate in candidates) {
            val parsed = tryParse(fullText, candidate)
            if (parsed != null) return parsed
        }
        return StructuredReply(stripThinkingPrefix(fullText.trim()))
    }

    /**
     * 兜底清洗：模型在正文开头写思考过程时，剥掉「好的，让我想想…」这类开场废话。
     * 只作用于纯文本回退路径；只删开头第一句，避免误伤正常内容。
     */
    private val THINK_PREFIXES = listOf(
        "好的，让我想想", "好的让我想想", "好的，我来", "好的我来",
        "让我想想", "让我想一下", "我来想想", "我想想", "嗯，让我", "嗯，我想", "嗯，首先",
        "明白了，", "明白了", "收到，", "收到", "首先，", "首先", "让我考虑一下",
        "嗯，这个问题", "关于这个问题", "这个问题让我",
        "让我思考一下", "让我好好想想", "好的，那我", "好的，那我们",
        "好的，我", "好的我", "好的，", "嗯，"
    )

    /**
     * 女友模式流式预览：把累积到一半的原文转成一个可展示的"干净"版本。
     * - 若能解析出完整结构化文本 → 直接显示 text；
     * - 若还没解析完（JSON 骨架在途中）→ 返回空串（界面会显示"正在输入…"，绝不外露 JSON/思考骨架）；
     * - 若整段不是 JSON（模型直接说人话）→ 走清理后显示。
     */
    fun streamPreview(accumulated: String): String {
        if (accumulated.isBlank()) return ""
        val parsed = parse(accumulated)
        if (parsed.text.isNotBlank()) return parsed.text
        return if (accumulated.indexOf('{') >= 0) "" else stripThinkingPrefix(accumulated)
    }

    /**
     * 女友模式最终清洗：兜住 JSON 解析失败/模型画蛇添足时的残留。
     * 抽取 text 成功后也再扫一遍：剥掉分析思考、场景构建等会让"住在手机里的猫娘"人设崩塌的杂质。
     */
    fun sanitizeGirlfriendText(text: String): String {
        if (text.isBlank()) return text
        var result = text
            .replace(Regex("```(?:json|JSON)?\\s*"), "")
            .replace("```", "")
            .replace("**", "")
        // 剥角色卡文件名前缀（如 "girlfriend_card.png: xxx"）
        result = result.replace(Regex("^girlfriend_card\\.png\\s*[:：]\\s*"), "")
        result = result.replace(Regex("^\\w+\\.png\\s*[:：]\\s*"), "")
        // 可能残余的"我是…/思考："等前置开场白整句剥掉（不破坏正文）
        result = result
            .replace(Regex("^[（(][^）()]{2,24}[）)]\\s*"), "")
            .trim()
        // 多余的场景构建：第三人称叙述句剥掉，但保留"打开"等设备操作指令
        result = result
            .replace(Regex("你(?:推开|走进|推门|站在|坐进|看向|望着|轻声|一把|抬头|低头|转身|放下|拿起|走到|来到|靠在|凑到|摸了摸|拍了拍|缓缓|慢慢|突然|忽然|悄悄|轻轻)门?[^，。！？]{0,25}"), "")
            .trim()
        // 思考/分析残留：整行"我觉得应该…/我先…/总结…"剥掉（保守：只剥单独成行的）
        result = result
            .lineSequence()
            .mapNotNull { line ->
                val l = line.trim()
                if (l.length in 3..60 &&
                    (l.startsWith("我觉得") || l.startsWith("我想") || l.startsWith("让我")
                        || l.startsWith("首先") || l.startsWith("其次") || l.startsWith("总结")
                        || l.startsWith("分析") || l.startsWith("所以") || l.startsWith("综上")
                        || l.contains("思考良久") || l.contains("考虑了"))
                ) null else line
            }
            .joinToString("\n")
            .trim()
        return if (result.isNotBlank()) result else text
    }

    fun stripThinkingPrefix(text: String): String {
        if (text.isBlank()) return text
        var result = text
        for (prefix in THINK_PREFIXES) {
            if (result.startsWith(prefix)) {
                result = result.removePrefix(prefix).trimStart('，', '：', ',', '。', ' ')
                if (result.isNotBlank()) break
            }
        }
        // 推理首段检测：第一个换行前的段落若像「嗯，用户说…首先我需要…」这种分析腔（特征词密集），整段剥掉。
        // 保守策略：只剪首段，只剪特征词 ≥3 的段落，避免误伤正常对话。
        val firstPara = result.lineSequence().firstOrNull() ?: ""
        val isReasoningPara = firstPara.length > 30 &&
            REASONING_HINTS.count { it in firstPara } >= 3
        if (isReasoningPara) {
            val rest = result.substringAfter('\n', "")
            if (rest.isNotBlank()) result = rest.trimStart('\n', ' ', '\r')
        }
        // 带括号的内心独白/思考注释整句剥掉（如 "（让我想想）…"）
        val paren = Regex("^（[^）]{0,30}）\\s*")
        val cleaned = paren.replace(result, "")
        if (cleaned.isNotBlank()) result = cleaned
        // 场景构建标签：全文剥掉 （场景：…）（画面：…）（背景：…）（设定：…） 这类导演旁白
        result = result
            .replace(SCENE_LABEL_REGEX, "")
            .replace(Regex("[（(](?:场景|画面|背景|设定|想象)[：:][^）)]{0,60}[）)]"), "")
            .trim()
        // 开头的第三人称场景叙述块（长括号动作/场景描写）剥掉；短动作注释（（晃尾巴））保留
        val longParen = Regex("^[（(][^（()）]{0,80}[）)]\\s*")
        val opener = longParen.find(result)
        if (opener != null && opener.value.trim().length > 14) {
            val rest = result.substring(opener.range.last + 1)
            if (rest.isNotBlank()) result = rest.trimStart()
        }
        return if (result.isNotBlank()) result else text
    }

    private val SCENE_LABEL_REGEX = Regex("""[（(]\s*(?:场景|画面|背景|设定|旁白)\s*[：:][^）)]{0,60}[）)]""")

    private val REASONING_HINTS = listOf(
        "用户", "老大", "需要", "首先", "其次", "然后", "所以", "因此", "分析",
        "考虑", "应该", "问题", "回答", "想法", "觉得", "感觉他", "好像",
        "设计", "构建", "步骤", "回顾"
    )

    /**
     * 找完整的顶层 JSON 对象区间（含起止位置）。
     * 旧实现用 lastIndexOf('{') 会先命中 avatar/voice 的内层对象，导致合法的嵌套回复永远漏掉外层 JSON。
     * 这里按字符串/转义规则做括号配对，既能处理嵌套对象，也不会把 text 里的花括号误算进去。
     */
    private fun findJsonCandidates(text: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { index, ch ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> {
                        if (depth == 0) start = index
                        depth++
                    }
                    '}' -> if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            result += start to index
                            start = -1
                        }
                    }
                }
            }
        }
        return result.asReversed().take(5)
    }

    private fun tryParse(fullText: String, range: Pair<Int, Int>): StructuredReply? {
        val (start, end) = range
        val jsonText = fullText.substring(start, end + 1)
        val root: JsonElement = try {
            json.parseToJsonElement(jsonText)
        } catch (_: Exception) {
            return null
        }
        if (root !is kotlinx.serialization.json.JsonObject) return null

        val hasControlData = root.containsKey("avatar") || root.containsKey("voice")
        val textField = root["text"]?.jsonPrimitive?.contentOrNull
        if (!hasControlData && textField == null) return null

        val cleanText = textField?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { stripThinkingPrefix(it) }
            ?: fullText.substring(0, start).trim().takeIf { it.isNotBlank() }
            ?: return null

        val avatar = root["avatar"]?.jsonObject
        val emotion = AvatarEmotion.from(avatar?.get("emotion")?.jsonPrimitive?.contentOrNull)
        val rawMotion = avatar?.get("motion")?.jsonPrimitive?.contentOrNull
        val motion = rawMotion?.lowercase()?.takeIf { it in AVATAR_ALLOWED_MOTIONS }
        val intensity = (avatar?.get("intensity")?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 1f)
            .coerceIn(0f, 1f)
        val rawGaze = avatar?.get("gaze")?.jsonPrimitive?.contentOrNull
        val gaze = rawGaze?.takeIf { it in AVATAR_ALLOWED_GAZE } ?: "none"

        val voice = root["voice"]?.jsonObject
        val rawStyle = voice?.get("style")?.jsonPrimitive?.contentOrNull
        val style = rawStyle?.lowercase()?.takeIf { it in ALLOWED_VOICE_STYLES }
        val rawSpeed = voice?.get("speed")?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
        val speed = rawSpeed?.takeIf { it in 0.8f..1.2f }

        return StructuredReply(
            text = cleanText,
            emotion = emotion,
            motion = motion,
            intensity = intensity,
            gaze = gaze,
            voiceStyle = style,
            voiceSpeed = speed
        )
    }
}

/** voice.style → OpenAI TTS instructions 提示词片段。 */
fun voiceStyleInstructions(style: String?): String = when (style) {
    "gentle" -> "用温柔体贴、轻声细语的语气说话"
    "cheerful" -> "用欢快活泼、元气满满的语气说话"
    "sad" -> "用低落伤感、轻声哽咽的语气说话"
    "serious" -> "用认真严肃、沉稳的语气说话"
    "whisper" -> "用耳语般的轻柔声音说话"
    else -> ""
}
