package com.pockettavern.app.ui.screens.girlfriend

import android.content.Context
import com.pockettavern.app.data.girlfriend.GirlfriendMemoryStore
import com.pockettavern.app.data.girlfriend.SecureGirlfriendStorage
import com.pockettavern.app.domain.model.ChatMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 小女友主动感知 → 聊天注入桥接。
 *
 * 当前对话存在时写入当前文件；页面已离开或进程重建后，自动选择
 * girlfriend_card.png 隔离目录中最后修改的聊天，确保后台消息不会静默丢失。
 */
object GirlfriendProactiveManager {

    data class InjectedMessage(val chatFileName: String, val message: ChatMessage)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _injectedMessages = MutableSharedFlow<InjectedMessage>(extraBufferCapacity = 16)
    val injectedMessages = _injectedMessages.asSharedFlow()

    @Volatile private var currentStorageKey: String = GIRLFRIEND_STORAGE_KEY
    @Volatile private var currentChatFileName: String? = null
    @Volatile private var characterName: String = "白音"

    @Volatile var boredom: Int = 0
        private set

    @Volatile var lastInteractionTime: Long = System.currentTimeMillis()
        private set

    fun updateBoredom(b: Int, lastTime: Long) {
        boredom = b.coerceIn(0, 100)
        lastInteractionTime = lastTime.takeIf { it > 0L } ?: System.currentTimeMillis()
    }

    fun tickBoredom(context: Context): Int {
        val state = GirlfriendMemoryStore(context).refreshBoredom()
        updateBoredom(state.boredom, state.lastInteractionTime)
        return boredom
    }

    fun recordInteraction(context: Context) {
        val state = GirlfriendMemoryStore(context).recordInteraction()
        updateBoredom(state.boredom, state.lastInteractionTime)
    }

    fun resetBoredom(context: Context) = recordInteraction(context)

    fun logMischief(context: Context, description: String, severity: Int = 1) {
        GirlfriendMemoryStore(context).recordMischief(description, severity)
    }

    fun register(storageKey: String, charName: String, chatFileName: String) {
        currentStorageKey = storageKey.ifBlank { GIRLFRIEND_STORAGE_KEY }
        characterName = charName.ifBlank { "白音" }
        currentChatFileName = chatFileName
    }

    /**
     * 页面销毁后仍保留最后对话名，后台服务需要继续向该对话投递。
     * 真正的目标文件还会在每次投递时检查，不存在则自动回退到最新聊天。
     */
    fun unregister() = Unit

    /** 返回 true 表示消息已真实写入聊天文件。 */
    fun injectMessage(context: Context, text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val dir = File(context.filesDir, "chats/${sanitize(currentStorageKey)}").apply { mkdirs() }
            val preferred = currentChatFileName?.let { File(dir, it) }?.takeIf { it.exists() }
            val target = preferred ?: dir.listFiles { file -> file.extension.equals("jsonl", true) }
                ?.maxByOrNull { it.lastModified() }
                ?: File(dir, generateChatFileName(currentStorageKey))

            val now = Instant.now()
            val message = ChatMessage(content = text.trim(), isUser = false, timestamp = now)
            val messageLine = JsonObject(
                mapOf(
                    "name" to JsonPrimitive(characterName),
                    "is_user" to JsonPrimitive(false),
                    "is_system" to JsonPrimitive(false),
                    "send_date" to JsonPrimitive(formatDate(now)),
                    "mes" to JsonPrimitive(message.content),
                    "extra" to JsonObject(emptyMap())
                )
            )
            val marker = File(dir, ".encrypted")
            val existing = when {
                !target.exists() -> buildHeader(now)
                marker.exists() -> SecureGirlfriendStorage.readEncrypted(target)
                    ?: runCatching { target.readText() }.getOrDefault("")
                else -> target.readText()
            }.trimEnd()
            val updated = if (existing.isBlank()) {
                buildHeader(now) + "\n" + json.encodeToString(messageLine)
            } else {
                existing + "\n" + json.encodeToString(messageLine)
            }
            if (marker.exists()) SecureGirlfriendStorage.writeEncrypted(target, updated)
            else target.writeText(updated)
            target.setLastModified(System.currentTimeMillis())
            currentChatFileName = target.name
            _injectedMessages.tryEmit(InjectedMessage(target.name, message))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildHeader(now: Instant): String = json.encodeToString(
        JsonObject(
            mapOf(
                "user_name" to JsonPrimitive("User"),
                "character_name" to JsonPrimitive(characterName),
                "create_date" to JsonPrimitive(formatDate(now)),
                "chat" to JsonArray(emptyList())
            )
        )
    )

    private fun generateChatFileName(storageKey: String): String {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd @HH'h'mm'm'ss's'"))
        return "${sanitize(storageKey)} - $stamp.jsonl"
    }

    private fun formatDate(instant: Instant): String =
        LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim().take(64)

    private const val GIRLFRIEND_STORAGE_KEY = "girlfriend_card.png"
}
