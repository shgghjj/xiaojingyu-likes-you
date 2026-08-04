package com.pockettavern.app.ui.screens.girlfriend

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettavern.app.data.girlfriend.GirlfriendDynamicContext
import com.pockettavern.app.data.girlfriend.GirlfriendMemoryService
import com.pockettavern.app.data.girlfriend.GirlfriendMemoryStore
import com.pockettavern.app.data.girlfriend.GirlfriendPromptBuilder
import com.pockettavern.app.data.girlfriend.GirlfriendState
import com.pockettavern.app.data.girlfriend.JailbreakLibrary
import com.pockettavern.app.data.girlfriend.SecureGirlfriendStorage
import com.pockettavern.app.data.local.CardExtensionSettings
import com.pockettavern.app.data.local.SpriteStorage
import com.pockettavern.app.data.local.db.dao.CharacterDao
import com.pockettavern.app.data.local.GroupStorage
import com.pockettavern.app.data.local.SettingsDataStore
import com.pockettavern.app.domain.model.ApiConfiguration
import com.pockettavern.app.data.repository.BackgroundRepository
import com.pockettavern.app.data.repository.ForgeRepository
import com.pockettavern.app.data.repository.ImageGenRepository
import com.pockettavern.app.data.repository.LlmRepository
import com.pockettavern.app.data.repository.LocalRepository
import com.pockettavern.app.extensions.ExtensionManager
import com.pockettavern.app.domain.model.Character
import com.pockettavern.app.domain.usecase.SummarizeHistoryUseCase
import com.pockettavern.app.openclaw.OpenClawRepository
import com.pockettavern.app.ui.audio.CompanionVoiceOutputProvider
import com.pockettavern.app.ui.audio.TtsManager
import com.pockettavern.app.ui.screens.chat.ChatViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 小女友（M 计划）：包装酒馆聊天管线（生成/流式/TTS/口型/Live2D/相机全复用），
 * 叠加小女友专属系统：动态提示词、渐进认知、记忆整理、成长演化、破甲词库。
 * 酒馆与小女友完全隔离：小女友的数据（档案/记忆/词库）独立存放，角色卡独立。
 */
@HiltViewModel
class GirlfriendViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localRepository: LocalRepository,
    private val llmRepository: LlmRepository,
    private val forgeRepository: ForgeRepository,
    private val imageGenRepository: ImageGenRepository,
    private val backgroundRepository: BackgroundRepository,
    private val extensionManager: ExtensionManager,
    private val ttsManager: TtsManager,
    private val voiceOutputProvider: CompanionVoiceOutputProvider,
    private val settingsDataStore: SettingsDataStore,
    private val characterDao: CharacterDao,
    private val spriteStorage: SpriteStorage,
    private val summarizeHistoryUseCase: SummarizeHistoryUseCase,
    private val groupStorage: GroupStorage,
    private val cardExtensionSettings: CardExtensionSettings,
    private val openclawRepository: OpenClawRepository
) : ViewModel() {

    /** 复用酒馆聊天管线（手动构造，注入同样依赖） */
    val chat = ChatViewModel(
        context, localRepository, llmRepository, forgeRepository, imageGenRepository,
        backgroundRepository, extensionManager, ttsManager, voiceOutputProvider,
        settingsDataStore, characterDao, spriteStorage, summarizeHistoryUseCase,
        groupStorage, cardExtensionSettings, openclawRepository
    )

    val girlfriendCard: String = GIRLFRIEND_CARD

    data class GirlfriendUiState(
        val ready: Boolean = false,
        val busy: Boolean = false,
        val name: String = "白音",
        val petName: String = "老大",
        val stage: String = "初见",
        val acquaintanceDays: Int = 1,
        val intimacy: Int = 0,
        val jailbreakId: String = "gentle",
        val factsCount: Int = 0,
        val memoriesCount: Int = 0,
        val traits: Map<String, Int> = emptyMap(),
        val customJailbreak: String = "",
        val boredom: Int = 0,
        val lastInteractionTime: Long = 0L,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(GirlfriendUiState())
    val uiState: StateFlow<GirlfriendUiState> = _uiState.asStateFlow()

    private val memoryStore = GirlfriendMemoryStore(context)
    private val memoryService = GirlfriendMemoryService(llmRepository)

    private var currentConfig: ApiConfiguration? = null
    private var state: GirlfriendState = GirlfriendState()
    private var readyStarted = false
    @Volatile private var automationEnabled = false

    init {
        viewModelScope.launch {
            settingsDataStore.llmConfigFlow.collect { config -> currentConfig = config }
        }
        viewModelScope.launch {
            settingsDataStore.girlfriendAutomationEnabledFlow.collect { enabled ->
                automationEnabled = enabled
            }
        }
        // 消息数变化 → 触发记忆整理（每 CONSOLIDATE_INTERVAL 条）
        viewModelScope.launch {
            chat.uiState.map { it.messages.size }
                .distinctUntilChanged()
                .collect { count -> maybeConsolidate(count) }
        }
    }

    fun ensureReady() {
        if (readyStarted) return
        readyStarted = true
        // 小女友强制纯净模式：禁止任何全局预设（梦境思客等）/世界书/记忆块/扩展注入污染她的世界观
        chat.activateGirlfriendIsolation()
        refreshCard()
    }

    /** 每次进入小女友时调用：日期演进 → 更新角色卡（提示词/开场白） → 加载聊天 */
    fun refreshCard() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val (newState, crossedDay) = memoryStore.touchNewDay()
            state = memoryStore.refreshBoredom()
            ensureEncryptedGirlfriendChats()
            updateCardOnDisk(crossedDay)
            chat.loadCharacter(GIRLFRIEND_CARD)
            // Update dynamic context for cache-optimized prompt injection
            val jailbreak = JailbreakLibrary.load(context, state.jailbreakId)
            val perms = currentDevicePermissions()
            GirlfriendDynamicContext.update(state, perms)
            // Sync boredom state with proactive manager
            GirlfriendProactiveManager.updateBoredom(state.boredom, state.lastInteractionTime)
            _uiState.update {
                it.copy(
                    ready = true,
                    busy = false,
                    name = state.name,
                    petName = state.petName,
                    stage = state.stage,
                    acquaintanceDays = state.acquaintanceDays,
                    intimacy = state.intimacy,
                    jailbreakId = state.jailbreakId,
                    factsCount = state.facts.size,
                    memoriesCount = state.memories.size,
                    traits = state.traits,
                    customJailbreak = memoryStore.loadCustomJailbreak(),
                    boredom = state.boredom,
                    lastInteractionTime = state.lastInteractionTime
                )
            }
        }
    }

    /**
     * 小女友聊天固定使用角色卡文件名作为隔离目录。旧版误用显示名，导致
     * .encrypted 标记落在错误目录；这里先无损迁移现有明文聊天，再创建正确标记。
     */
    private suspend fun ensureEncryptedGirlfriendChats() = withContext(Dispatchers.IO) {
        runCatching {
            val safeKey = GIRLFRIEND_CARD.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim().take(64)
            val dir = File(context.filesDir, "chats/$safeKey").apply { mkdirs() }
            val marker = File(dir, ".encrypted")
            if (!marker.exists()) {
                dir.listFiles { file -> file.extension.equals("jsonl", true) }
                    ?.forEach { file ->
                        if (SecureGirlfriendStorage.readEncrypted(file) == null) {
                            val plain = runCatching { file.readText() }.getOrDefault("")
                            if (plain.isNotBlank()) SecureGirlfriendStorage.writeEncrypted(file, plain)
                        }
                    }
                marker.createNewFile()
            }
        }
    }

    /** 把最新提示词/开场白写入女友角色卡（挂起函数：确保写盘完成后再 load）
     *  注意：必须用 editCharacter（显式文件名）——createCharacter 会用角色名
     *  自动生成文件名（如"小月.png"），导致按 girlfriend_card.png 加载时找不到。 */
    private suspend fun updateCardOnDisk(crossedDay: Boolean) {
        val jailbreak = JailbreakLibrary.load(context, state.jailbreakId)
        val permissions = currentDevicePermissions()
        val systemPrompt = GirlfriendPromptBuilder.build(state, jailbreak, permissions, includeDynamic = false)
        val character = Character(
            name = state.name,
            avatar = GIRLFRIEND_CARD,
            firstMessage = greetingFor(state, crossedDay),
            systemPrompt = systemPrompt,
            tags = listOf("小女友")
        )
        try {
            localRepository.editCharacter(GIRLFRIEND_CARD, character)
        } catch (_: Exception) { /* 卡片写盘失败时保持现状 */ }
    }

    /** 读取小女友当前设备权限状态（告诉她到底能做什么） */
    private fun currentDevicePermissions(): GirlfriendPromptBuilder.DevicePermissions {
        return GirlfriendPromptBuilder.DevicePermissions(
            screenAccess = com.pockettavern.app.device.ScreenAccessManager.isEnabled(context),
            camera = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            writeSystem = android.provider.Settings.System.canWrite(context),
            automation = automationEnabled
        )
    }

    /** 开场白是直接说话，不是场景构建——只允许一个 10 字以内的短动作括号，其余都是她亲口说的话 */
    private fun greetingFor(state: GirlfriendState, crossedDay: Boolean): String {
        val custom = state.customGreeting
        if (custom.isNotBlank()) return custom
        return when {
            state.acquaintanceDays <= 1 ->
                "（耳朵微微抖动）你就是${state.petName}？哼，我叫${state.name}——记住了，是${state.name}大人！(｀・ω・´) 以后，请多指教…喵~"
            crossedDay ->
                "${state.petName}！新的一天！本天才等你等得尾巴都要打结了！(≧▽≦) 今天有什么好玩的事~"
            else ->
                "${state.petName}~你来啦 (*´▽`*) 我正好想到了三个绝妙的新点子，你想先听哪个~"
        }
    }

    // ── 记忆整理 ──────────────────────────────────────────────────────────────

    private fun maybeConsolidate(count: Int) {
        if (count == 0 || !readyStarted) return
        val threshold = state.lastConsolidatedCount + GirlfriendMemoryService.CONSOLIDATE_INTERVAL
        if (count < threshold) return
        viewModelScope.launch {
            state = memoryStore.refreshBoredom()
            val config = currentConfig ?: return@launch
            val messages = chat.uiState.value.messages
            val updated = memoryService.consolidate(messages, config, state) ?: return@launch
            state = updated.copy(lastConsolidatedCount = count)
            memoryStore.save(state)
            GirlfriendDynamicContext.update(state, currentDevicePermissions())
            // 档案变了 → 静默更新角色卡提示词（下次进入加载）
            updateCardOnDisk(crossedDay = false)
            _uiState.update {
                it.copy(
                    intimacy = state.intimacy,
                    factsCount = state.facts.size,
                    memoriesCount = state.memories.size,
                    traits = state.traits
                )
            }
        }
    }

    // ── 设置操作 ──────────────────────────────────────────────────────────────

    fun setName(name: String) {
        val n = name.trim().ifBlank { return }
        state = state.copy(name = n.take(12))
        memoryStore.save(state)
        viewModelScope.launch { updateCardOnDisk(false) }
        _uiState.update { it.copy(name = state.name) }
    }

    fun setPetName(petName: String) {
        val n = petName.trim().ifBlank { return }
        state = state.copy(petName = n.take(12))
        memoryStore.save(state)
        viewModelScope.launch { updateCardOnDisk(false) }
        _uiState.update { it.copy(petName = state.petName) }
    }

    fun setGreeting(greeting: String) {
        state = state.copy(customGreeting = greeting.trim().take(200))
        memoryStore.save(state)
        viewModelScope.launch { updateCardOnDisk(false) }
    }

    fun setJailbreak(id: String) {
        state = state.copy(jailbreakId = id)
        memoryStore.save(state)
        viewModelScope.launch { updateCardOnDisk(false) }
        _uiState.update { it.copy(jailbreakId = id) }
    }

    fun saveCustomJailbreak(text: String) {
        memoryStore.saveCustomJailbreak(text)
        if (state.jailbreakId == "custom") viewModelScope.launch { updateCardOnDisk(false) }
    }

    fun clearFacts() {        state = state.copy(facts = emptyList())
        memoryStore.save(state)
        viewModelScope.launch { updateCardOnDisk(false) }
        _uiState.update { it.copy(factsCount = 0) }
    }

    fun clearMemories() {
        state = state.copy(memories = emptyList())
        memoryStore.save(state)
        viewModelScope.launch { updateCardOnDisk(false) }
        _uiState.update { it.copy(memoriesCount = 0) }
    }

    /** 设置面板展示用：最近 20 条档案 */
    fun factsSnapshot(): List<String> = state.facts.takeLast(20).map { it.content }

    /** 设置面板展示用：最近 20 条回忆 */
    fun memoriesSnapshot(): List<String> = state.memories.takeLast(20).map { it.content }

    /** 清空聊天记录：开新对话（历史里残留的脏内容不再显示、不再喂给模型） */
    fun clearChatHistory() {
        viewModelScope.launch {
            chat.createNewChat()
        }
    }

    /** 重置小女友（删档案/记忆/角色卡/聊天），重新从空白开始 */
    fun resetGirlfriend() {
        viewModelScope.launch {
            runCatching { localRepository.deleteCharacter(GIRLFRIEND_CARD) }
            memoryStore.deleteAll()
            state = GirlfriendState(
                firstMetDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(System.currentTimeMillis()),
                lastInteractionTime = System.currentTimeMillis()
            )
            memoryStore.save(state)
            readyStarted = false
            refreshCard()
        }
    }

    // ── 主动感知 ──────────────────────────────────────────────────────────

    fun refreshBoredomStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            val refreshed = memoryStore.refreshBoredom()
            state = refreshed
            GirlfriendProactiveManager.updateBoredom(
                refreshed.boredom,
                refreshed.lastInteractionTime
            )
            _uiState.update {
                it.copy(
                    boredom = refreshed.boredom,
                    lastInteractionTime = refreshed.lastInteractionTime
                )
            }
        }
    }

    fun resetBoredom() {
        viewModelScope.launch(Dispatchers.IO) {
            val refreshed = memoryStore.recordInteraction()
            state = refreshed
            GirlfriendProactiveManager.updateBoredom(0, refreshed.lastInteractionTime)
            _uiState.update {
                it.copy(boredom = 0, lastInteractionTime = refreshed.lastInteractionTime)
            }
        }
    }

    suspend fun isAwarenessEnabled(): Boolean = settingsDataStore.isGirlfriendAwarenessEnabled()

    fun setAwarenessEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setGirlfriendAwarenessEnabled(enabled)
            if (enabled) {
                GirlfriendAwarenessService.start(context)
            } else {
                GirlfriendAwarenessService.stop(context)
            }
        }
    }

    suspend fun isAutomationEnabled(): Boolean = settingsDataStore.isGirlfriendAutomationEnabled()

    fun setAutomationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setGirlfriendAutomationEnabled(enabled)
            automationEnabled = enabled
            updateCardOnDisk(false)
        }
    }

    suspend fun getGeminiKey(): String {
        return settingsDataStore.getGeminiVisionConfig().apiKey
    }

    fun saveGeminiKey(key: String) {
        viewModelScope.launch {
            val current = settingsDataStore.getGeminiVisionConfig()
            settingsDataStore.saveGeminiVisionConfig(current.copy(apiKey = key.trim()))
        }
    }

    companion object {
        const val GIRLFRIEND_CARD = "girlfriend_card.png"
    }
}
