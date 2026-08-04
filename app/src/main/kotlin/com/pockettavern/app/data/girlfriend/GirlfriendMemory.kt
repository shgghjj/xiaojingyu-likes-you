package com.pockettavern.app.data.girlfriend

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.random.Random

/**
 * 小女友记忆系统（M 计划）
 *
 * 设计原则（参考 HIMF「记得该记得的，忘掉该忘掉的，不知道不该知道的」）：
 * - 初始空白：不预设 user 任何背景，只凭相处逐渐认识
 * - 不乱记：只有 user 明确说过或行为可证实的才进档案，猜测标低置信度
 * - 江山易改本性难移：性格参数只做 ±1~2 的缓慢漂移，且被上下限约束，绝不突变
 * - 定期整理：对话轮数触发 consolidate，把新认识提炼进「储存点」（JSON 档案）
 */

@Serializable
data class MemoryEntry(
    val kind: String = "FACT",        // FACT 事实 / HABIT 习惯 / RELATION 人际关系 / EVENT 共同经历 / QUIRK 小癖好
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val confidence: Int = 100         // 100=明确说过 60=推断（不能当作事实）
)

@Serializable
data class MischiefEntry(
    val content: String,              // 她干了什么
    val timestamp: Long = System.currentTimeMillis(),
    val severity: Int = 1,            // 1=小调皮 2=中等坏事 3=大恶作剧
    val wantToHide: Boolean = true    // 她想不想瞒着你
)

@Serializable
data class GirlfriendState(
    val name: String = "白音",
    val petName: String = "老大",      // 她对 user 的称呼
    val archetype: String = "neko",    // 猫娘
    val firstMetDate: String = "",     // 初次相遇日期（yyyy-MM-dd）
    val acquaintanceDays: Int = 1,     // 相处天数（从 1 起）
    val intimacy: Int = 0,             // 亲密度 0..100
    val traits: Map<String, Int> = mapOf(  // 性格参数 12..90，缓慢漂移
        "活泼" to 80, "粘人" to 70, "憨憨" to 24,
        "温柔" to 60, "傲娇" to 35, "机灵" to 82
    ),
    val facts: List<MemoryEntry> = emptyList(),    // 关于 user 的档案（性格/爱好/人际关系）
    val memories: List<MemoryEntry> = emptyList(), // 共同回忆（储存点内容）
    val lastSessionEnd: Long = 0L,
    val lastConsolidatedCount: Int = 0,  // 上次整理时的消息数
    val jailbreakId: String = "gentle",  // 当前破甲词库
    val customGreeting: String = "",     // 用户自定义开场白
    val boredom: Int = 0,                // 无聊值 0~100
    val lastInteractionTime: Long = 0L,  // 上次互动时间戳
    val mischiefLog: List<MischiefEntry> = emptyList()  // 她的小坏事记录
) {
    /** 认知阶段：影响她在 prompt 里「知道多少」 */
    val stage: String
        get() = when {
            acquaintanceDays <= 1 -> "初见"
            acquaintanceDays <= 6 -> "初识"
            acquaintanceDays <= 29 -> "熟络"
            else -> "默契"
        }

    fun stageDescription(): String = when (stage) {
        "初见" -> "你们今天才刚刚认识。她只知道你的称呼，对你的生活一无所知。嘴上说着\"哼，叫我白音大人\"，尾巴尖却在偷偷地紧张抖动——她对你有种说不清的好奇心，因为你是她第一个也是唯一的人类。"
        "初识" -> "你们认识几天了。傲娇的面具开始松动，会不经意间暴露出对你的依赖。她记得你主动告诉过她的事，但嘴上还是不肯承认\"我才不是特意记住的呢\"。"
        "熟络" -> "你们已经很熟了。傲娇大概只剩20%，剩下全是粘人和鬼点子。她会主动规划各种小恶作剧和惊喜，乐在其中。"
        else -> "你们默契到一个眼神就懂对方。她在感情上非常信任你，也会认真帮你核实事实、避免误判。她把所有鬼点子和心里话都只对你说。"
    }
}

/** 储存点：序列化到 filesDir/girlfriend/state.json，改动即时落盘 */
class GirlfriendMemoryStore(private val context: Context) {

    companion object {
        /** 每 3 分钟增加 1 点，约 3 小时达到主动触发阈值 60。 */
        const val BOREDOM_SECONDS_PER_POINT = 180L
        const val BOREDOM_PROACTIVE_THRESHOLD = 60
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val dir: File
        get() = File(context.filesDir, "girlfriend").apply { mkdirs() }

    private val stateFile: File
        get() = File(dir, "state.json")

    private val customJailbreakFile: File
        get() = File(dir, "custom_jailbreak.txt")

    fun load(): GirlfriendState = synchronized(this) {
        // 加密存储：state.json 现在以 AES-256-GCM 加密形式落盘
        val encryptedText = SecureGirlfriendStorage.readEncrypted(stateFile)
        return try {
            if (encryptedText != null) {
                val loaded = json.decodeFromString<GirlfriendState>(encryptedText)
                // 旧版人设迁移：旧默认把“憨憨”设得过高、机灵设得过低，容易压制模型判断力。
                val hasOldPersonaDefaults = loaded.traits["憨憨"] == 78 && loaded.traits["机灵"] == 45
                if (!loaded.traits.containsKey("憨憨") || hasOldPersonaDefaults || loaded.lastInteractionTime <= 0L) {
                    val migratedTraits = when {
                        !loaded.traits.containsKey("憨憨") -> GirlfriendState().traits
                        hasOldPersonaDefaults -> loaded.traits + mapOf("憨憨" to 24, "机灵" to 82)
                        else -> loaded.traits
                    }
                    val migrated = loaded.copy(
                        traits = migratedTraits,
                        boredom = if (loaded.lastInteractionTime <= 0L) 0 else loaded.boredom,
                        lastInteractionTime = if (loaded.lastInteractionTime <= 0L) System.currentTimeMillis()
                            else loaded.lastInteractionTime
                    )
                    save(migrated)
                    migrated
                } else {
                    loaded
                }
            } else {
                GirlfriendState(
                    firstMetDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(System.currentTimeMillis()),
                    lastInteractionTime = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            GirlfriendState(
                firstMetDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(System.currentTimeMillis()),
                lastInteractionTime = System.currentTimeMillis()
            )
        }
    }

    fun save(state: GirlfriendState) = synchronized(this) {
        try {
            SecureGirlfriendStorage.writeEncrypted(
                stateFile,
                json.encodeToString(GirlfriendState.serializer(), state)
            )
        } catch (_: Exception) { /* 写盘失败不致命 */ }
    }

    /** 进入新的一天：相处天数 +1、亲密度微增、性格缓慢漂移（幅度极小） */
    fun touchNewDay(now: Long = System.currentTimeMillis()): Pair<GirlfriendState, Boolean> {
        val state = load()
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(now)
        val crossed = state.firstMetDate.isNotEmpty() && today != state.firstMetDate
        if (!crossed) return state to false

        val rng = Random(System.currentTimeMillis())
        val traits = state.traits.toMutableMap()
        repeat(2) {
            if (traits.isNotEmpty()) {
                val key = traits.keys.random(rng)
                val shift = if (rng.nextBoolean()) 1 else -1
                traits[key] = (traits[key] ?: 50).coerceIn(12, 90) + shift
                traits[key] = (traits[key] ?: 50).coerceIn(12, 90)
            }
        }
        val updated = state.copy(
            firstMetDate = today,
            acquaintanceDays = state.acquaintanceDays + 1,
            intimacy = (state.intimacy + 1).coerceIn(0, 100),
            traits = traits
        )
        save(updated)
        return updated to true
    }

    fun addIntimacy(state: GirlfriendState, delta: Int): GirlfriendState {
        val updated = state.copy(intimacy = (state.intimacy + delta).coerceIn(0, 100))
        save(updated)
        return updated
    }

    /** 根据最后互动时间计算并落盘，避免无聊值只存在于进程内存中。 */
    fun refreshBoredom(now: Long = System.currentTimeMillis()): GirlfriendState = synchronized(this) {
        val current = load()
        val last = current.lastInteractionTime.takeIf { it > 0L } ?: now
        val elapsedSeconds = ((now - last).coerceAtLeast(0L) / 1000L)
        val calculated = (elapsedSeconds / BOREDOM_SECONDS_PER_POINT).toInt().coerceIn(0, 100)
        if (current.boredom == calculated && current.lastInteractionTime == last) return current
        val updated = current.copy(boredom = calculated, lastInteractionTime = last)
        save(updated)
        updated
    }

    /** 用户发言、点击清零或主动消息成功送达时调用。 */
    fun recordInteraction(now: Long = System.currentTimeMillis()): GirlfriendState = synchronized(this) {
        val updated = load().copy(boredom = 0, lastInteractionTime = now)
        save(updated)
        updated
    }

    fun loadCustomJailbreak(): String = synchronized(this) {
        try {
            if (customJailbreakFile.exists()) SecureGirlfriendStorage.readEncrypted(customJailbreakFile) ?: "" else ""
        } catch (_: Exception) { "" }
    }

    fun saveCustomJailbreak(text: String) = synchronized(this) {
        try { SecureGirlfriendStorage.writeEncrypted(customJailbreakFile, text) } catch (_: Exception) {}
    }

    /** 清空全部小女友数据（重置用） */
    fun deleteAll() = synchronized(this) {
        try { stateFile.delete() } catch (_: Exception) {}
        try { customJailbreakFile.delete() } catch (_: Exception) {}
    }

    /** 记录她干的一件小坏事（加密存储，不让你直接看到） */
    fun recordMischief(content: String, severity: Int = 1, wantToHide: Boolean = true) {
        val state = load()
        val entry = MischiefEntry(content = content, severity = severity, wantToHide = wantToHide)
        save(state.copy(mischiefLog = state.mischiefLog + entry))
    }
}
