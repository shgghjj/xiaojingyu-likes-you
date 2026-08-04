package com.pockettavern.app.data.girlfriend

import com.pockettavern.app.data.repository.LlmRepository
import com.pockettavern.app.domain.model.ApiConfiguration
import com.pockettavern.app.domain.model.ChatMessage
import com.pockettavern.app.domain.model.PromptMessage
import com.pockettavern.app.domain.model.StreamEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * 记忆整理服务（M 计划）
 *
 * 每攒够 N 条新消息，把这段对话交给 LLM 提炼（低成本、低温、短输出），
 * 更新「关于老大的档案」与「共同回忆」，写入储存点。
 * 只对 chat-completions 后端生效（小女友主要面向 DeepSeek）；失败静默，绝不影响聊天。
 */
class GirlfriendMemoryService(
    private val llmRepository: LlmRepository
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 每多少条新消息整理一次 */
    companion object {
        const val CONSOLIDATE_INTERVAL = 15
        const val MAX_FACTS = 50
        const val MAX_MEMORIES = 50
        const val WINDOW_MESSAGES = 40
    }

    private val extractPrompt = """
        你是档案管理员。下面是小女友与用户的一段聊天记录。
        请从中提取两类内容：

        1. facts：关于"老大"（用户）的新信息。kind 取值：
           FACT（性格特点/职业/爱好等事实）、HABIT（生活习惯）、RELATION（人际关系）、QUIRK（小癖好/口头禅）。
        2. memories：值得两人珍藏的共同经历或感动瞬间。

        规则：
        - 只提取老大明确说过、或能从对话明确推断的内容；拿不准的 confidence 填 60。
        - 绝不编造对话里没有的信息。
        - 内容用一句话概括，口语化，30 字以内。
        - 空结果时输出 {"facts":[],"memories":[]}。

        只输出 JSON，不要输出任何其他文字：
        {"facts":[{"kind":"FACT","content":"...","confidence":100}],"memories":[{"content":"..."}]}
    """.trimIndent()

    /** 整理最近对话；成功返回更新后的状态，失败/不支持返回 null */
    suspend fun consolidate(
        messages: List<ChatMessage>,
        config: ApiConfiguration,
        state: GirlfriendState
    ): GirlfriendState? {
        if (!config.usesChatCompletions) return null
        val window = messages
            .filter { !it.isNarrator && it.content.isNotBlank() }
            .takeLast(WINDOW_MESSAGES)
        if (window.size < 2) return null

        val promptMessages = buildList {
            add(PromptMessage("system", extractPrompt))
            window.forEach { m ->
                val cleaned = m.content
                    .replace(Regex("\\{.*?\"avatar\".*?\\}", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("<\\[device_action\\].*?</\\[device_action\\]>", RegexOption.DOT_MATCHES_ALL), "")
                    .trim()
                if (cleaned.isNotBlank()) {
                    add(PromptMessage(if (m.isUser) "user" else "assistant", cleaned))
                }
            }
        }
        if (promptMessages.size < 2) return null

        val raw = runCatching {
            var result: String? = null
            llmRepository.generate(
                prompt = "请从聊天记录中提取档案信息。",
                config = config,
                preset = null,
                messages = promptMessages,
                showThoughts = false
            ).collect { e ->
                when (e) {
                    is StreamEvent.Complete -> result = e.fullText
                    is StreamEvent.Error -> throw IllegalStateException(e.message)
                    else -> {}
                }
            }
            result
        }.getOrNull() ?: return null

        return parseExtraction(raw)?.let { (newFacts, newMemories) ->
            mergeInto(state, newFacts, newMemories)
        }
    }

    private data class Extraction(val facts: List<MemoryEntry>, val memories: List<MemoryEntry>)

    private fun parseExtraction(raw: String): Extraction? = runCatching {
        val text = raw
            .substringBefore("```")
            .substringAfter("```")
        val candidates = mutableListOf<String>()
        var idx = text.lastIndexOf("{")
        while (idx >= 0 && candidates.size < 3) {
            candidates.add(text.substring(idx))
            idx = text.lastIndexOf("{", idx - 1)
        }
        var extraction: Extraction? = null
        for (c in candidates) {
            val end = c.indexOf("}")
            if (end < 0) continue
            val obj = json.parseToJsonElement(c.substring(0, end + 1)).jsonObject
            val facts = obj["facts"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val content = o["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MemoryEntry(
                    kind = o["kind"]?.jsonPrimitive?.contentOrNull ?: "FACT",
                    content = content,
                    confidence = (o["confidence"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100).coerceIn(1, 100)
                )
            } ?: emptyList()
            val memories = obj["memories"]?.jsonArray?.mapNotNull { el ->
                el.jsonObject["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            } ?: emptyList()
            extraction = Extraction(facts, memories.map { MemoryEntry("EVENT", it) })
            break
        }
        extraction
    }.getOrNull()

    private fun mergeInto(state: GirlfriendState, newFacts: List<MemoryEntry>, newMemories: List<MemoryEntry>): GirlfriendState {
        val existingFacts = state.facts.toMutableList()
        val existingMemories = state.memories.toMutableList()
        var added = 0
        newFacts.forEach { f ->
            val dup = existingFacts.any { it.content.contains(f.content) || f.content.contains(it.content) }
            if (!dup) {
                existingFacts.add(f)
                added++
            }
        }
        newMemories.forEach { m ->
            val dup = existingMemories.any { it.content.contains(m.content) || m.content.contains(it.content) }
            if (!dup) {
                existingMemories.add(m)
                added++
            }
        }
        if (added == 0) return state
        val updated = state.copy(
            facts = existingFacts.takeLast(MAX_FACTS),
            memories = existingMemories.takeLast(MAX_MEMORIES),
            intimacy = (state.intimacy + 2).coerceIn(0, 100)
        )
        return updated
    }
}
