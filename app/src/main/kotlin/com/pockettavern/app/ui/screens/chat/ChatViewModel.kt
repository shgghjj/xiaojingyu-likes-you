package com.pockettavern.app.ui.screens.chat

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import java.io.ByteArrayOutputStream
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pockettavern.app.GenerationService
import com.pockettavern.app.data.repository.BackgroundRepository
import com.pockettavern.app.data.repository.ForgeRepository
import com.pockettavern.app.data.repository.ImageGenRepository
import com.pockettavern.app.data.repository.LocalRepository
import com.pockettavern.app.data.repository.LlmRepository
import com.pockettavern.app.extensions.ExtensionEvent
import com.pockettavern.app.extensions.ExtensionManager
import com.pockettavern.app.extensions.JsExtensionHost
import com.pockettavern.app.domain.model.ApiConfiguration
import com.pockettavern.app.domain.model.Chat
import com.pockettavern.app.domain.model.Character
import com.pockettavern.app.domain.model.ChatContext
import com.pockettavern.app.domain.model.ChatInfo
import com.pockettavern.app.domain.model.ChatMessage
import com.pockettavern.app.domain.model.PromptMessage
import com.pockettavern.app.domain.model.UserPersona
import com.pockettavern.app.domain.model.ChatMessageMetadata
import com.pockettavern.app.domain.model.MessageHeaderEntry
import com.pockettavern.app.domain.model.ForgeGenerationParams
import com.pockettavern.app.domain.model.GenerationState
import com.pockettavern.app.domain.model.QuickReplyButton
import com.pockettavern.app.domain.model.Result
import com.pockettavern.app.domain.model.StreamEvent
import com.pockettavern.app.data.local.GroupStorage
import com.pockettavern.app.data.local.SettingsDataStore
import com.pockettavern.app.device.DEVICE_TOOL_PROMPT
import com.pockettavern.app.device.DeviceActionParser
import com.pockettavern.app.device.ParsedDeviceAction
import com.pockettavern.app.ui.audio.CompanionVoiceOutputProvider
import com.pockettavern.app.ui.live2d.AvatarEmotion
import com.pockettavern.app.ui.live2d.AvatarState
import com.pockettavern.app.ui.live2d.LipSyncController
import com.pockettavern.app.device.DeviceAppLauncher
import com.pockettavern.app.device.LocalToolAction
import com.pockettavern.app.device.LocalToolExecutor
import com.pockettavern.app.device.LocalToolParser
import com.pockettavern.app.device.LocalToolPlan
import com.pockettavern.app.device.PendingDeviceAction
import com.pockettavern.app.device.ScreenAccessManager
import com.pockettavern.app.device.ScreenContextRepository
import com.pockettavern.app.device.isUiAutomationAction
import com.pockettavern.app.device.isPhoneCompanionAction
import com.pockettavern.app.device.requiresSensitiveConfirmation
import com.pockettavern.app.openclaw.OpenClawRepository
import com.pockettavern.app.openclaw.OpenClawRouter
import com.pockettavern.app.openclaw.OpenClawRouteDecision
import com.pockettavern.app.openclaw.OpenClawTaskResult
import com.pockettavern.app.openclaw.OpenClawTaskStatus
import com.pockettavern.app.openclaw.RouteTarget
import com.pockettavern.app.domain.prompt.PromptBuilder
import com.pockettavern.app.domain.usecase.SummarizeHistoryUseCase
import com.pockettavern.app.ui.audio.TtsManager
import com.pockettavern.app.util.PngCharacterCard
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import com.pockettavern.app.util.DebugLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.abs

data class ChatUiState(
    val character: Character? = null,
    val characterAvatarUrl: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = true,
    val isGenerating: Boolean = false,
    val streamingContent: String = "",
    val streamingThinking: String = "",
    val showReasoningBubbles: Boolean = false,
    val apiShowThoughtsEnabled: Boolean = false,
    val currentChatFileName: String? = null,
    val availableChats: List<ChatInfo> = emptyList(),
    val showChatSelector: Boolean = false,
    val error: String? = null,
    val showDeleteDialog: Boolean = false,
    // Chat rename dialog
    val showRenameChatDialog: Boolean = false,
    val renameChatTargetFileName: String? = null,
    val renameChatInput: String = "",
    // Message action menu state
    val selectedMessageIndex: Int? = null,
    val showMessageActions: Boolean = false,
    val imageSaved: Boolean = false,
    // API indicator
    val currentApiName: String = "",
    val currentModelName: String = "",
    // Message editing
    val editingMessageIndex: Int? = null,
    val editingMessageText: String = "",
    // Swipes (alternate responses) - map of message index to list of alternates
    val messageSwipes: Map<Int, List<String>> = emptyMap(),
    val currentSwipeIndex: Map<Int, Int> = emptyMap(),
    // Chat background
    val backgroundPath: String? = null,
    // Greeting selection for new chat
    val showGreetingPicker: Boolean = false,
    val availableGreetings: List<String> = emptyList(),
    // Quick reply buttons from enabled presets + JS-registered buttons
    val quickReplyButtons: List<QuickReplyButton> = emptyList(),
    // Token counter (shown when extension is enabled)
    val tokenCount: Int = 0,
    val showTokenCount: Boolean = false,
    // Message headers set by JS extensions via PT.setMessageHeader(index, text, extensionId)
    val messageHeaders: Map<Int, List<MessageHeaderEntry>> = emptyMap(),
    // Inline header buttons registered by extensions (extensionId → actions)
    val headerButtons: Map<String, List<JsExtensionHost.HeaderAction>> = emptyMap(),
    // Header context menus registered by extensions (extensionId → actions)
    val headerMenus: Map<String, List<JsExtensionHost.HeaderAction>> = emptyMap(),
    // Which (messageIndex, extensionId) pairs have visible inline buttons
    val visibleHeaderButtons: Set<Pair<Int, String>> = emptySet(),
    // Edit dialog requested by JS extension via PT.showEditDialog()
    val editDialogRequest: JsExtensionHost.EditDialogRequest? = null,
    // TTS
    val isTtsSpeaking: Boolean = false,
    val isTtsEnabled: Boolean = false,
    val voiceInputEnabled: Boolean = false,
    // Message context menu actions from JS extensions
    val messageActions: List<JsExtensionHost.HeaderAction> = emptyList(),
    // Image gallery
    val showGallery: Boolean = false,
    val galleryImages: List<GalleryImage> = emptyList(),
    // Message search
    val isSearching: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Int> = emptyList(),
    val currentSearchResultIndex: Int = 0,
    // Context window usage (estimated tokens)
    val contextUsedTokens: Int = 0,
    // Shared world book (from linked group)
    val linkedGroupName: String? = null,
    val hasWorldBook: Boolean = false,
    // Scanlore dialog
    val showScanloreDialog: Boolean = false,
    val scanloreEntries: List<String> = emptyList(),
    val scanloreLoading: Boolean = false,
    val scanloreError: String? = null,
    // Model picker
    val showModelPicker: Boolean = false,
    val availableModels: List<String> = emptyList(),
    val modelPickerLoading: Boolean = false,
    // Generate first message dialog
    val showGenerateGreetingPrompt: Boolean = false,
    val generatingFirstMessage: Boolean = false,
    val generatedFirstMessage: String = "",
    val generateFirstMessageError: String? = null,
    // Narrow, user-confirmed phone tool. The MVP can only open a launchable app.
    val pendingDeviceAction: PendingDeviceAction? = null,
    val pendingScreenRead: Boolean = false,
    // 小女友主动提醒的权限引导（未开启时她提到某权限 → 弹窗引导去设置）
    val pendingPermissionPrompt: PermissionPrompt? = null,
    // 本地工具（音量/亮度等，确认后执行）
    val pendingLocalTool: LocalToolPlan? = null,
    // 小女友实验性手机自动操作
    val automationEnabled: Boolean = false,
    val automationActive: Boolean = false,
    val automationStep: Int = 0,
    // OpenClaw（按需外部工具任务）
    val openclawTaskStatus: OpenClawTaskStatus? = null,
    val openclawStatusText: String = "",
    val pendingOpenClawTaskText: String? = null,
    val pendingOpenClawDecision: OpenClawRouteDecision? = null,
    // A 计划：Live2D 导演状态（状态机统一计算）
    val avatarState: AvatarState = AvatarState.IDLE,
    val avatarEmotion: AvatarEmotion = AvatarEmotion.NEUTRAL,
    val avatarMotion: String? = null,
    val avatarGaze: String = "none",
    val avatarIntensity: Float = 1f,
    val avatarRequestToken: Int = 0,
    // 真实音频电平（口型同步 0..1）
    val lipSyncLevel: Float = 0f,
    // 语音输入（按住说话，partial 实时上屏）
    val isListening: Boolean = false,
    val listeningPartial: String = "",
    val voiceInputError: String? = null,
    // B 计划：相机视觉（true 时由界面拉起相机）
    val cameraRequest: Boolean = false,
)

data class GalleryImage(
    val imagePath: String,
    val chatFileName: String,
    val timestamp: Long,
    val messageIndex: Int
)

/** 小女友提到的设备权限（未开启时由界面弹窗引导去系统设置） */
enum class PermissionKind { SCREEN_ACCESS, CAMERA, WRITE_SETTINGS }

data class PermissionPrompt(val kind: PermissionKind)

private const val CONTINUE_PROMPT =
    "(OOC: Please continue the story from where it left off, maintaining the current tone and situation.)"

@HiltViewModel
class ChatViewModel @Inject constructor(
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
    private val characterDao: com.pockettavern.app.data.local.db.dao.CharacterDao,
    private val spriteStorage: com.pockettavern.app.data.local.SpriteStorage,
    private val summarizeHistoryUseCase: SummarizeHistoryUseCase,
    private val groupStorage: GroupStorage,
    private val cardExtensionSettings: com.pockettavern.app.data.local.CardExtensionSettings,
    private val openclawRepository: OpenClawRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val deviceAppLauncher = DeviceAppLauncher(context)
    private val localToolExecutor = LocalToolExecutor(context)

    // OpenClaw 状态消息在消息流中的索引（用于原地更新，避免刷屏）
    private var openclawStatusMessageIndex: Int? = null

    private var generationJob: Job? = null

    // Auto-continue state
    private var autoContinueEnabled = false
    private var autoContinueMinLength = 200
    private var autoContinueCount = 0

    @Volatile private var girlfriendAutomationEnabled = false
    private var automationStepCount = 0
    private var automationJob: Job? = null
    private val maxAutomationSteps = 12
    private var suppressToolFallbackOnce = false

    // Long-term memory
    private var memoryEnabled = true
    private var _currentMemoryBlock: String = ""

    /** 小女友纯净模式：生成时完全跳过全局预设（system prompt preset / OAI preset / 世界书 / 记忆块 / 扩展注入），
     *  只用角色卡自己的 systemPrompt（GirlfriendPromptBuilder 生成的），防止用户全局配置（如梦境思客预设）污染小女友世界观。 */
    @Volatile var pureChatMode: Boolean = false
    private var _currentSummarizedTurnCount: Int = 0

    // TTS auto-play state
    private var ttsAutoPlay = false

    // 最新 TTS 配置（tap-to-interrupt 等需要）
    private var ttsConfig = com.pockettavern.app.domain.model.TtsConfig()

    // 口型同步（attack/release 平滑）
    private val lipSync = LipSyncController()

    // Track which card file is currently open so we can disable it on back-out
    private var currentCardFileName: String? = null

    init {
        extensionManager.load()
        // Wire JS sendMessage callback so PT.sendMessage() routes through the normal pipeline
        extensionManager.jsHost.sendMessageCallback = { text -> sendMessageText(text) }
        // Observe native quick reply buttons + JS-registered buttons combined
        viewModelScope.launch {
            extensionManager.quickReply.activeButtons.collect { nativeButtons ->
                if (pureChatMode) return@collect
                val jsButtons = extensionManager.jsButtonSets.value.values.flatten()
                _uiState.update { it.copy(quickReplyButtons = nativeButtons + jsButtons) }
            }
        }
        viewModelScope.launch {
            extensionManager.jsButtonSets.collect { jsSets ->
                if (pureChatMode) return@collect
                val nativeButtons = extensionManager.quickReply.activeButtons.value
                val jsButtons = jsSets.values.flatten()
                _uiState.update { it.copy(quickReplyButtons = nativeButtons + jsButtons) }
            }
        }
        // Observe JS message headers — persist onto messages and save to disk
        viewModelScope.launch {
            extensionManager.messageHeaders.collect { headers ->
                if (pureChatMode) return@collect
                _uiState.update { it.copy(messageHeaders = headers) }
                // Persist headers onto message objects and save so they survive chat reload
                // Only persist if we actually have messages in the current chat
                if (_uiState.value.messages.isNotEmpty() && persistExtensionHeaders()) {
                    saveCurrentChat()
                }
            }
        }
        // Observe inline header buttons registered by JS extensions
        viewModelScope.launch {
            extensionManager.headerButtons.collect { buttons ->
                if (pureChatMode) return@collect
                _uiState.update { it.copy(headerButtons = buttons) }
            }
        }
        // Observe header context menus registered by JS extensions
        viewModelScope.launch {
            extensionManager.headerMenus.collect { menus ->
                if (pureChatMode) return@collect
                _uiState.update { it.copy(headerMenus = menus) }
            }
        }
        // Observe message context menu actions from JS extensions
        viewModelScope.launch {
            extensionManager.messageActions.collect { actionsMap ->
                if (pureChatMode) return@collect
                val allActions = actionsMap.values.flatten()
                _uiState.update { it.copy(messageActions = allActions) }
            }
        }
        // Observe edit dialog requests from JS extensions
        viewModelScope.launch {
            extensionManager.jsHost.editDialogRequest.collect { request ->
                if (pureChatMode) return@collect
                _uiState.update { it.copy(editDialogRequest = request) }
            }
        }
        // Wire model list callback so PT.getAvailableModels() works
        extensionManager.jsHost.getAvailableModelsCallback = { callbackId ->
            viewModelScope.launch { doExtensionGetModels(callbackId) }
        }
        // Wire model set callback so PT.setCurrentModel() works
        extensionManager.jsHost.setCurrentModelCallback = { modelName, callbackId ->
            viewModelScope.launch { doExtensionSetModel(modelName, callbackId) }
        }
        // Wire hidden generate callback so PT.generateHidden() works
        extensionManager.jsHost.hiddenGenerateCallback = { prompt, callbackId ->
            viewModelScope.launch { doHiddenGenerate(prompt, callbackId) }
        }
        // Wire image generate callback so PT.generateImage() works
        extensionManager.jsHost.imageGenerateCallback = { prompt, optionsJson, callbackId ->
            viewModelScope.launch { doExtensionImageGenerate(prompt, optionsJson, callbackId) }
        }
        // Wire insert message callback so PT.insertMessage() works
        extensionManager.jsHost.insertMessageCallback = { content, optionsJson ->
            viewModelScope.launch { doExtensionInsertMessage(content, optionsJson) }
        }
        // Observe token counter enabled state
        _uiState.update { it.copy(showTokenCount = extensionManager.tokenCounter.enabled) }
        // Observe auto-continue settings
        viewModelScope.launch {
            localRepository.autoContinueFlow.collect { (enabled, minLength) ->
                autoContinueEnabled = enabled
                autoContinueMinLength = minLength
            }
        }
        // Observe long-term memory setting
        viewModelScope.launch {
            localRepository.memoryEnabledFlow.collect { enabled -> memoryEnabled = enabled }
        }
        viewModelScope.launch {
            settingsDataStore.girlfriendAutomationEnabledFlow.collect { enabled ->
                girlfriendAutomationEnabled = enabled
                _uiState.update {
                    it.copy(
                        automationEnabled = enabled,
                        automationActive = if (enabled) it.automationActive else false,
                        automationStep = if (enabled) it.automationStep else 0
                    )
                }
                if (!enabled) automationJob?.cancel()
            }
        }
        // Collect quick reply auto-triggers
        viewModelScope.launch {
            extensionManager.quickReply.autoTriggerFlow.collect { button ->
                if (!pureChatMode && _uiState.value.character != null && !_uiState.value.isGenerating) {
                    sendQuickReply(button)
                }
            }
        }
        // Start/stop foreground service to keep CPU alive during generation
        viewModelScope.launch {
            var serviceRunning = false
            _uiState.collect { state ->
                val needsService = state.isGenerating

                if (needsService && !serviceRunning) {
                    GenerationService.start(context, "Generating response...")
                    serviceRunning = true
                } else if (!needsService && serviceRunning) {
                    GenerationService.stop(context)
                    serviceRunning = false
                }
            }
        }
        // Reactively track API config so the indicator updates when profiles are activated
        viewModelScope.launch {
            localRepository.apiConfigFlow.collect { config ->
_currentConfig = config
        // 加载 Gemini Vision API Key
        viewModelScope.launch {
            _currentGeminiKey = settingsDataStore.getGeminiVisionConfig().apiKey
        }
                _uiState.update {
                    it.copy(
                        currentApiName = config.displayName,
                        currentModelName = config.currentModel,
                        apiShowThoughtsEnabled = config.showThoughts
                    )
                }
            }
        }
        // Observe TTS speaking state（新旧两路合并，统一驱动口型与状态机）
        viewModelScope.launch {
            combine(ttsManager.speakingState, voiceOutputProvider.speakingState) { a, b -> a || b }
                .collect { speaking ->
                    _uiState.update { it.copy(isTtsSpeaking = speaking) }
                    if (!speaking) {
                        lipSync.reset()
                        if (_uiState.value.lipSyncLevel != 0f) {
                            _uiState.update { it.copy(lipSyncLevel = 0f) }
                        }
                    }
                    updateAvatarState()
                }
        }
        // 真实音频电平 → 口型（仅朗读中生效，阈值过滤抖动）
        viewModelScope.launch {
            voiceOutputProvider.levelState.collect { level ->
                val state = _uiState.value
                if (!state.isTtsSpeaking) return@collect
                val smoothed = lipSync.process(level)
                if (abs(smoothed - state.lipSyncLevel) > 0.015f) {
                    _uiState.update { it.copy(lipSyncLevel = smoothed) }
                }
            }
        }
        // Load TTS enabled state
        viewModelScope.launch {
            settingsDataStore.ttsConfigFlow.collect { config ->
                ttsConfig = config
                _uiState.update {
                    it.copy(
                        isTtsEnabled = config.enabled,
                        voiceInputEnabled = config.voiceInputEnabled
                    )
                }
                ttsAutoPlay = config.enabled && config.autoPlay
            }
        }
        // 主动感知服务已把消息落盘后，同步刷新仍停留在小女友聊天页的界面。
        viewModelScope.launch {
            com.pockettavern.app.ui.screens.girlfriend.GirlfriendProactiveManager
                .injectedMessages.collect { injected ->
                    if (!pureChatMode || _uiState.value.currentChatFileName != injected.chatFileName) {
                        return@collect
                    }
                    val duplicate = _uiState.value.messages.lastOrNull()?.let {
                        it.content == injected.message.content && !it.isUser
                    } == true
                    if (!duplicate) {
                        _uiState.update { state ->
                            state.copy(messages = state.messages + injected.message)
                        }
                        updateAvatarState()
                    }
                }
        }
    }

    /** 当前显示的是普通酒馆聊天：重新绑定酒馆扩展回调。 */
    fun activateTavernSurface() {
        pureChatMode = false
        bindExtensionCallbacks()
        val nativeButtons = extensionManager.quickReply.activeButtons.value
        val jsButtons = extensionManager.jsButtonSets.value.values.flatten()
        _uiState.update {
            it.copy(
                quickReplyButtons = nativeButtons + jsButtons,
                messageHeaders = extensionManager.messageHeaders.value,
                headerButtons = extensionManager.headerButtons.value,
                headerMenus = extensionManager.headerMenus.value,
                messageActions = extensionManager.messageActions.value.values.flatten(),
                showTokenCount = extensionManager.tokenCounter.enabled
            )
        }
    }

    /**
     * 当前显示的是小女友：切断酒馆扩展、快捷回复、世界书和消息菜单。
     * API 连接与系统语音属于底层服务，可共用；角色、提示词、记录、记忆与扩展上下文完全隔离。
     */
    fun activateGirlfriendIsolation() {
        pureChatMode = true
        girlfriendAutomationEnabled = false
        automationJob?.cancel()
        automationJob = null
        viewModelScope.launch { settingsDataStore.setGirlfriendAutomationEnabled(false) }
        _currentGroupId = null
        _currentWorldBook = ""
        _currentMemoryBlock = ""
        extensionManager.jsHost.sendMessageCallback = null
        extensionManager.jsHost.getAvailableModelsCallback = null
        extensionManager.jsHost.setCurrentModelCallback = null
        extensionManager.jsHost.hiddenGenerateCallback = null
        extensionManager.jsHost.imageGenerateCallback = null
        extensionManager.jsHost.insertMessageCallback = null
        _uiState.update {
            it.copy(
                quickReplyButtons = emptyList(),
                messageHeaders = emptyMap(),
                headerButtons = emptyMap(),
                headerMenus = emptyMap(),
                messageActions = emptyList(),
                editDialogRequest = null,
                linkedGroupName = null,
                hasWorldBook = false,
                showTokenCount = false,
                automationEnabled = false,
                automationActive = false,
                automationStep = 0,
                pendingScreenRead = false
            )
        }
    }

    private fun bindExtensionCallbacks() {
        extensionManager.jsHost.sendMessageCallback = { text -> sendMessageText(text) }
        extensionManager.jsHost.getAvailableModelsCallback = { callbackId ->
            viewModelScope.launch { doExtensionGetModels(callbackId) }
        }
        extensionManager.jsHost.setCurrentModelCallback = { modelName, callbackId ->
            viewModelScope.launch { doExtensionSetModel(modelName, callbackId) }
        }
        extensionManager.jsHost.hiddenGenerateCallback = { prompt, callbackId ->
            viewModelScope.launch { doHiddenGenerate(prompt, callbackId) }
        }
        extensionManager.jsHost.imageGenerateCallback = { prompt, optionsJson, callbackId ->
            viewModelScope.launch { doExtensionImageGenerate(prompt, optionsJson, callbackId) }
        }
        extensionManager.jsHost.insertMessageCallback = { content, optionsJson ->
            viewModelScope.launch { doExtensionInsertMessage(content, optionsJson) }
        }
    }

    /**
     * 头像状态机：同一时刻只有一个激活状态（优先级 ERROR > 工具/确认 > 说话 > 思考 > 表情 > 听 > 待机）。
     * 在关键状态变更点调用，保证表情/动作/说话互不打架。
     */
    private fun updateAvatarState() {
        val s = _uiState.value
        val state = when {
            s.isListening -> AvatarState.LISTENING
            s.pendingDeviceAction != null || s.pendingScreenRead || s.pendingLocalTool != null
                || s.pendingOpenClawDecision != null -> AvatarState.WAITING_CONFIRMATION
            s.openclawTaskStatus == OpenClawTaskStatus.CONNECTING
                || s.openclawTaskStatus == OpenClawTaskStatus.WORKING -> AvatarState.TOOL_WORKING
            s.isTtsSpeaking -> AvatarState.SPEAKING
            s.isGenerating -> AvatarState.THINKING
            s.error != null -> AvatarState.ERROR
            else -> when (s.avatarEmotion) {
                AvatarEmotion.HAPPY -> AvatarState.HAPPY
                AvatarEmotion.SAD -> AvatarState.SAD
                AvatarEmotion.ANGRY -> AvatarState.ANGRY
                AvatarEmotion.SURPRISED -> AvatarState.SURPRISED
                AvatarEmotion.SHY -> AvatarState.SHY
                AvatarEmotion.CONFUSED -> AvatarState.CONFUSED
                AvatarEmotion.NEUTRAL -> AvatarState.IDLE
            }
        }
        if (state != s.avatarState) {
            _uiState.update { it.copy(avatarState = state) }
        }
    }

    // Last known API config — updated when generation starts, used for abort
    @Volatile private var _currentConfig: ApiConfiguration = ApiConfiguration.DEFAULT
    // Last known persona name/description — updated when generation starts
    @Volatile private var _currentUserName: String = "User"
    @Volatile private var _currentPersonaDescription: String = ""
    @Volatile private var _currentGeminiKey: String = ""
    // Shared world book from linked group (empty if character not in any group)
    @Volatile private var _currentGroupId: String? = null
    @Volatile private var _currentWorldBook: String = ""

    /**
     * Rebuild the context JSON that JS extensions see via PT.getContext().
     * Includes the current character, recent messages (with index, text, isUser),
     * persona name, and API type.  Called whenever messages or character change.
     */
    private fun pushExtensionContext() {
        if (pureChatMode) return
        val state = _uiState.value
        val character = state.character
        val messages = state.messages

        val sb = StringBuilder()
        sb.append("{")
        // character
        if (character != null) {
            sb.append("\"character\":{")
            sb.append("\"name\":").append(jsonString(character.name)).append(",")
            sb.append("\"description\":").append(jsonString(character.description)).append(",")
            sb.append("\"personality\":").append(jsonString(character.personality)).append(",")
            sb.append("\"scenario\":").append(jsonString(character.scenario))
            sb.append("},")
        }
        // recentMessages — include raw text (before output filter) so extensions can parse tags
        sb.append("\"recentMessages\":[")
        for (i in messages.indices) {
            if (i > 0) sb.append(",")
            val msg = messages[i]
            val text = msg.rawContent ?: msg.content
            sb.append("{\"index\":").append(i)
            sb.append(",\"text\":").append(jsonString(text))
            sb.append(",\"isUser\":").append(msg.isUser)
            sb.append("}")
        }
        sb.append("],")
        sb.append("\"personaName\":").append(jsonString(_currentUserName)).append(",")
        sb.append("\"apiType\":").append(jsonString(_currentConfig.displayName))
        sb.append("}")

        extensionManager.updateContext(sb.toString())
    }

    /** JSON-escape a string value (with surrounding quotes). */
    private fun jsonString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    // The PNG filename of the current character (e.g. "seraphina.png")
    private var currentAvatarUrl: String = ""

    /** Chat record storage key: defaults to character.name; overridden to avatar in pure chat mode
     *  (Girlfriend) so her chats never bleed into the Tavern even if display names match. */
    private fun storageKey(): String =
        if (pureChatMode) currentAvatarUrl.takeIf { it.isNotBlank() } ?: "girlfriend_card"
        else _uiState.value.character?.name ?: currentAvatarUrl

    fun loadCharacter(avatarUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            if (!pureChatMode) {
                // Set persona name early so extensions see it before first generation
                val personaName = settingsDataStore.getUserPersonaName()
                if (personaName.isNotBlank()) _currentUserName = personaName
            } else {
                _currentUserName = com.pockettavern.app.data.girlfriend.GirlfriendDynamicContext.userName
            }

            when (val result = localRepository.getCharacter(avatarUrl)) {
                is Result.Success -> {
                    val character = result.data
                    val avatarUri = localRepository.getAvatarUri(
                        character.avatar ?: "${character.name}.png"
                    ).toString()
                    val bgPath = backgroundRepository.getBackgroundPath(avatarUrl)

                    _uiState.update {
                        it.copy(
                            character = character,
                            characterAvatarUrl = avatarUri,
                            backgroundPath = bgPath
                        )
                    }
                    if (pureChatMode) {
                        _currentGroupId = null
                        _currentWorldBook = ""
                        _currentMemoryBlock = ""
                        _uiState.update { it.copy(linkedGroupName = null, hasWorldBook = false) }
                    } else {
                        // 酒馆角色才会更新扩展过滤、卡片脚本和群组世界书。
                        extensionManager.updateCharacterFilter(avatarUrl)
                        loadCardExtension(character)
                        val charFileName = character.avatar ?: "$avatarUrl"
                        val linkedGroup = groupStorage.getGroupsForCharacter(charFileName).firstOrNull()
                        _currentGroupId = linkedGroup?.id
                        _currentWorldBook = linkedGroup?.worldBook ?: ""
                        _uiState.update { it.copy(
                            linkedGroupName = linkedGroup?.name,
                            hasWorldBook = linkedGroup?.worldBook?.isNotBlank() == true
                        )}
                    }
                    loadChats(character, avatarUrl)
                }
                is Result.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = result.exception.message)
                    }
                }
            }
        }
    }

    private suspend fun loadCardExtension(character: Character) {
        val fileName = character.avatar ?: return
        withContext(Dispatchers.IO) {
            try {
                val bytesResult = localRepository.exportCharacterCard(fileName)
                val bytes = (bytesResult as? Result.Success)?.data ?: return@withContext
                val card = PngCharacterCard.extractCharacterData(bytes) ?: return@withContext
                val ptExtJson = card.data.extensions["pockettavern"] ?: run {
                    cardExtensionSettings.disable(fileName)
                    extensionManager.unloadCardScript()
                    return@withContext
                }
                val ptExt = org.json.JSONObject(ptExtJson.toString())
                val script = ptExt.optString("script", "")
                if (script.isBlank()) {
                    cardExtensionSettings.disable(fileName)
                    extensionManager.unloadCardScript()
                    return@withContext
                }
                // Card has a script — auto-enable and load
                cardExtensionSettings.setEnabled(fileName, true)
                currentCardFileName = fileName
                val scriptName = ptExt.optString("script_name", character.name)
                com.pockettavern.app.util.DebugLogger.log("[ChatViewModel] Card script found: '$scriptName' (${script.length} chars)")
                extensionManager.loadCardScript(script, scriptName, character.name)
            } catch (e: Exception) {
                com.pockettavern.app.util.DebugLogger.log("[ChatViewModel] Card script load error: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (pureChatMode) {
            com.pockettavern.app.ui.screens.girlfriend.GirlfriendProactiveManager.unregister()
        }
        currentCardFileName?.let { cardExtensionSettings.disable(it) }
        currentCardFileName = null
    }

    private suspend fun loadChats(character: Character, avatarUrl: String) {
        currentAvatarUrl = avatarUrl
        when (val chatsResult = localRepository.getCharacterChats(storageKey())) {
            is Result.Success -> {
                val chats = chatsResult.data
                _uiState.update { it.copy(availableChats = chats) }
                if (chats.isNotEmpty()) {
                    loadExistingChat(character, chats.first().fileName)
                } else {
                    createNewChat()
                }
            }
            is Result.Error -> createNewChat()
        }
    }

    fun refreshChatsList() {
        val character = _uiState.value.character ?: return
        viewModelScope.launch {
            when (val chatsResult = localRepository.getCharacterChats(storageKey())) {
                is Result.Success -> _uiState.update { it.copy(availableChats = chatsResult.data) }
                is Result.Error -> { /* ignore */ }
            }
        }
    }

    fun reloadCharacter() {
        if (currentAvatarUrl.isBlank()) return
        viewModelScope.launch {
            when (val result = localRepository.getCharacter(currentAvatarUrl)) {
                is Result.Success -> _uiState.update { it.copy(character = result.data) }
                is Result.Error -> { /* keep existing */ }
            }
        }
    }

    fun selectChat(fileName: String) {
        val character = _uiState.value.character ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, showChatSelector = false) }
            loadExistingChat(character, fileName)
        }
    }

    fun showChatSelector() {
        refreshChatsList()
        _uiState.update { it.copy(showChatSelector = true) }
    }

    fun dismissChatSelector() {
        _uiState.update { it.copy(showChatSelector = false) }
    }

    private suspend fun loadExistingChat(character: Character, fileName: String) {
        when (val result = localRepository.getChat(storageKey(), fileName)) {
            is Result.Success -> {
                val chat = result.data
                // 酒馆长记忆不进入小女友；小女友使用自己的事实与陪伴状态。
                _currentMemoryBlock = if (pureChatMode) "" else chat.memoryBlock
                _currentSummarizedTurnCount = if (pureChatMode) 0 else chat.summarizedTurnCount
                val messages = chat.messages
                // Restore persisted extension headers
                val restoredHeaders = mutableMapOf<Int, List<MessageHeaderEntry>>()
                messages.forEachIndexed { index, msg ->
                    if (msg.extensionHeaders.isNotEmpty()) {
                        restoredHeaders[index] = msg.extensionHeaders
                    }
                }
                // Clear stale state from previous chat before loading new one
                if (!pureChatMode) extensionManager.clearMessageHeaders()
                _uiState.update {
                    it.copy(
                        messages = messages,
                        currentChatFileName = fileName,
                        isLoading = false,
                        messageHeaders = if (pureChatMode) emptyMap() else restoredHeaders,
                        visibleHeaderButtons = emptySet()
                    )
                }
                if (pureChatMode) {
                    com.pockettavern.app.ui.screens.girlfriend.GirlfriendProactiveManager.register(
                        storageKey(),
                        character.name,
                        fileName
                    )
                }
                // Load vars for this chat (must happen before CHAT_CHANGED fires)
                if (!pureChatMode) {
                    val charName = storageKey()
                    withContext(Dispatchers.IO) {
                        extensionManager.varsLoad(charName, fileName)
                    }
                    pushExtensionContext()
                    extensionManager.emit(ExtensionEvent.CHAT_CHANGED, fileName)
                    extensionManager.restoreMessageHeaders(restoredHeaders)
                }
            }
            is Result.Error -> createNewChat()
        }
    }

    fun createNewChat() {
        val character = _uiState.value.character ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(showChatSelector = false) }
            val allGreetings = buildList {
                if (character.firstMessage.isNotBlank()) add(character.firstMessage)
                addAll(character.alternateGreetings.filter { it.isNotBlank() })
            }
            when {
                allGreetings.size > 1 -> {
                    _uiState.update {
                        it.copy(
                            showGreetingPicker = true,
                            availableGreetings = allGreetings,
                            isLoading = false
                        )
                    }
                }
                allGreetings.isEmpty() -> {
                    _uiState.update {
                        it.copy(
                            showGenerateGreetingPrompt = true,
                            generatedFirstMessage = "",
                            generateFirstMessageError = null,
                            isLoading = false
                        )
                    }
                }
                else -> startNewChatWithGreeting(allGreetings.firstOrNull())
            }
        }
    }

    fun dismissGreetingPicker() {
        _uiState.update { it.copy(showGreetingPicker = false, availableGreetings = emptyList()) }
    }

    fun selectGreeting(greeting: String?) {
        _uiState.update { it.copy(showGreetingPicker = false, availableGreetings = emptyList()) }
        startNewChatWithGreeting(greeting)
    }

    fun dismissGenerateGreetingPrompt() {
        _uiState.update {
            it.copy(
                showGenerateGreetingPrompt = false,
                generatingFirstMessage = false,
                generatedFirstMessage = "",
                generateFirstMessageError = null
            )
        }
        startNewChatWithGreeting(null)
    }

    fun confirmGeneratedGreeting() {
        val text = _uiState.value.generatedFirstMessage
        _uiState.update {
            it.copy(
                showGenerateGreetingPrompt = false,
                generatingFirstMessage = false,
                generatedFirstMessage = "",
                generateFirstMessageError = null
            )
        }
        startNewChatWithGreeting(text.ifBlank { null })
    }

    fun generateFirstMessage() {
        val character = _uiState.value.character ?: return
        val config = _currentConfig
        _uiState.update {
            it.copy(
                generatingFirstMessage = true,
                generatedFirstMessage = "",
                generateFirstMessageError = null
            )
        }
        viewModelScope.launch {
            try {
                val cardInfo = buildString {
                    appendLine("Name: ${character.name}")
                    if (character.description.isNotBlank()) appendLine("Description: ${character.description}")
                    if (character.personality.isNotBlank()) appendLine("Personality: ${character.personality}")
                    if (character.scenario.isNotBlank()) appendLine("Scenario: ${character.scenario}")
                    if (character.creatorNotes.isNotBlank()) appendLine("Creator notes: ${character.creatorNotes}")
                    if (character.systemPrompt.isNotBlank()) appendLine("System prompt: ${character.systemPrompt}")
                    if (character.messageExample.isNotBlank()) appendLine("Example dialogue:\n${character.messageExample}")
                }
                val userName = settingsDataStore.getUserPersonaName().ifBlank { "User" }
                val prompt = """You are writing the opening message for a roleplay character.
Write a first message as ${character.name} that establishes their personality, voice, and the scenario.
The user's name is $userName. Use {{user}} for the user and {{char}} for the character name.
Write only the character's opening message — no preamble, no meta-commentary, no instructions.

CHARACTER CARD:
$cardInfo""".trimIndent()

                val oaiMessages = if (config.usesChatCompletions)
                    listOf(com.pockettavern.app.domain.model.PromptMessage("user", prompt))
                else null

                llmRepository.generate(prompt, config, null, emptyList(), oaiMessages, null).collect { event ->
                    when (event) {
                        is com.pockettavern.app.domain.model.StreamEvent.Token ->
                            _uiState.update { it.copy(generatedFirstMessage = event.accumulated) }
                        is com.pockettavern.app.domain.model.StreamEvent.Complete ->
                            _uiState.update {
                                it.copy(
                                    generatedFirstMessage = event.fullText,
                                    generatingFirstMessage = false
                                )
                            }
                        is com.pockettavern.app.domain.model.StreamEvent.Error ->
                            _uiState.update {
                                it.copy(
                                    generatingFirstMessage = false,
                                    generateFirstMessageError = event.message
                                )
                            }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        generatingFirstMessage = false,
                        generateFirstMessageError = e.message ?: "Generation failed"
                    )
                }
            }
        }
    }

    private fun startNewChatWithGreeting(greeting: String?) {
        val character = _uiState.value.character ?: return
        viewModelScope.launch {
            _currentMemoryBlock = ""
            _currentSummarizedTurnCount = 0
            val fileName = localRepository.generateChatFileName(storageKey())
            val userName = if (pureChatMode) {
                com.pockettavern.app.data.girlfriend.GirlfriendDynamicContext.userName
            } else {
                settingsDataStore.getUserPersonaName().ifBlank { "User" }
            }
            val messages = if (!greeting.isNullOrBlank()) {
                val substituted = greeting
                    .replace("{{user}}", userName, ignoreCase = true)
                    .replace("{{username}}", userName, ignoreCase = true)
                    .replace("{{char}}", character.name, ignoreCase = true)
                    .replace("{{charname}}", character.name, ignoreCase = true)
                    listOf(ChatMessage(content = substituted, isUser = false))
                } else emptyList()
            // Clear stale headers from previous chat
            if (!pureChatMode) extensionManager.clearMessageHeaders()
            _uiState.update {
                it.copy(
                    messages = messages,
                    currentChatFileName = fileName,
                    isLoading = false,
                    messageHeaders = emptyMap(),
                    visibleHeaderButtons = emptySet()
                )
            }
            if (pureChatMode) {
                com.pockettavern.app.ui.screens.girlfriend.GirlfriendProactiveManager.register(
                    storageKey(),
                    character.name,
                    fileName
                )
            }
            // New chat — clear vars store (fresh state)
            if (!pureChatMode) {
                withContext(Dispatchers.IO) {
                    extensionManager.varsLoad(storageKey(), fileName)
                }
                pushExtensionContext()
                extensionManager.emit(ExtensionEvent.CHAT_CHANGED, fileName)
                extensionManager.emit(ExtensionEvent.CHAT_STARTED, fileName)
            }
            if (messages.isNotEmpty()) {
                saveCurrentChat()
                refreshChatsList()
            }
        }
    }

    fun updateInput(text: String) {
        val tokenCount = if (!pureChatMode && extensionManager.tokenCounter.enabled)
            extensionManager.tokenCounter.estimateTokens(text) else 0
        _uiState.update { it.copy(inputText = text, tokenCount = tokenCount) }
    }

    /** 会话统计：消息条数 / 总字数 / 估算 token 数（供聊天菜单右上角展示） */
    fun estimateChatStats(): Triple<Int, Int, Int> {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return Triple(0, 0, 0)
        var chars = 0
        for (m in messages) chars += m.content.length
        val tokens = if (extensionManager.tokenCounter.enabled)
            messages.sumOf { extensionManager.tokenCounter.estimateTokens(it.content) }
        else chars / 2
        return Triple(messages.size, chars, tokens)
    }

    /** Send the current input text as a message. */
    fun sendMessage() {
        val character = _uiState.value.character ?: return
        val rawMessage = _uiState.value.inputText.trim()
        if (rawMessage.isBlank()) return
        sendMessageText(rawMessage)
    }

    /** Execute only after the confirmation dialog in ChatScreen is accepted. */
    fun confirmPendingDeviceAction() {
        val action = _uiState.value.pendingDeviceAction ?: return
        _uiState.update { it.copy(pendingDeviceAction = null) }
        updateAvatarState()
        val result = deviceAppLauncher.launch(action.appName)
        val status = if (result.success) {
            "* [手机工具] 已打开 ${result.displayName} *"
        } else {
            "* [手机工具] 无法打开 ${result.displayName}：${result.error ?: "未知错误"} *"
        }
        insertNarratorMessage(status)
    }

    fun dismissPendingDeviceAction() {
        val action = _uiState.value.pendingDeviceAction ?: return
        _uiState.update { it.copy(pendingDeviceAction = null) }
        updateAvatarState()
        insertNarratorMessage("* [手机工具] 已取消打开 ${action.appName} *")
    }

    /** 小女友提到某个未开启的权限时，弹窗引导去系统设置开启（只在小女友模式启用，普通酒馆不打扰）。 */
    fun dismissPermissionPrompt() {
        _uiState.update { it.copy(pendingPermissionPrompt = null) }
        updateAvatarState()
    }

    /** 扫描小女友回复文本，若她提到未开启的权限，返回对应的引导弹窗。
     *  只在纯净模式（小女友）启用；权限已开启时返回 null，避免反复打扰。 */
    private fun detectPermissionPrompt(text: String): PermissionPrompt? {
        if (!pureChatMode) return null
        val t = text.ifBlank { return null }
        if (t.contains("相机") && t.contains("权限")) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return PermissionPrompt(PermissionKind.CAMERA)
        }
        return null
    }

    fun confirmPendingScreenRead() {
        _uiState.update { it.copy(pendingScreenRead = false) }
        updateAvatarState()
        if (!ScreenAccessManager.isEnabled(context)) {
            insertNarratorMessage("* [屏幕读取] 权限尚未开启，请先到 设置 → 屏幕文字权限 中开启。 *")
            return
        }
        val snapshot = ScreenContextRepository.latest()
        if (snapshot == null || snapshot.text.isBlank()) {
            insertNarratorMessage("* [屏幕读取] 暂时没有取得其他应用的可见文字，请先切换到目标页面，再回来重试。 *")
            return
        }
        sendHiddenUserTurn(
            """[用户已确认发送一份只读手机屏幕文字快照]
来源应用包名：${snapshot.packageName}
屏幕可见文字：
${snapshot.text}

请根据这些文字回答用户刚才要求你查看屏幕的问题。不要假装看到了截图、图标或未包含在文字里的内容。"""
        )
    }

    fun dismissPendingScreenRead() {
        _uiState.update { it.copy(pendingScreenRead = false) }
        updateAvatarState()
        insertNarratorMessage("* [屏幕读取] 已取消，本次没有向 API 发送屏幕文字。 *")
    }

    // ── 工具任务（本地优先） ─────────────────────────────────────────────────

    /** 工具按钮：把当前输入框内容作为工具任务（本地执行，OpenClaw 可选回退）。 */
    fun sendToolAction() {
        val character = _uiState.value.character ?: return
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) {
            insertNarratorMessage("* [工具] 请先输入要执行的任务，例如：帮我打开相机、调高音量 *")
            return
        }
        _uiState.update { it.copy(inputText = "") }
        routeToolTask(text)
    }

    /**
     * 手机轻量版只路由联网、打开应用和主动消息；其余自动化迁移到电脑端。
     */
    private fun routeToolTask(taskText: String) {
        val plan = LocalToolParser.parse(taskText)
        if (plan != null) {
            if (pureChatMode && !plan.action.isPhoneCompanionAction()) {
                insertNarratorMessage("* [手机工具] 这个能力已移到电脑端；手机端只保留联网、打开应用和主动消息。 *")
                return
            }
            val auto = pureChatMode && plan.action.isPhoneCompanionAction() &&
                !plan.action.requiresSensitiveConfirmation()
            if (auto) {
                executeLocalTool(plan.copy(needsConfirmation = false))
                return
            }
            when (plan.action) {
                is LocalToolAction.OpenApp -> {
                    _uiState.update {
                        it.copy(
                            pendingDeviceAction = PendingDeviceAction(
                                plan.action.name,
                                requestedBy = "本地工具"
                            )
                        )
                    }
                    updateAvatarState()
                }
                is LocalToolAction.ReadScreen -> {
                    _uiState.update { it.copy(pendingScreenRead = true) }
                    updateAvatarState()
                }
                else -> {
                    if (plan.needsConfirmation) {
                        _uiState.update { it.copy(pendingLocalTool = plan) }
                        updateAvatarState()
                    } else {
                        executeLocalTool(plan)
                    }
                }
            }
            return
        }
        // 小女友是手机独立模式：本地做不到就明确告知，绝不转发给电脑网关。
        if (pureChatMode) {
            insertNarratorMessage(
                "* [手机工具] 这个任务目前不能在手机本地完成；小女友不会连接电脑代为执行。*"
            )
            return
        }
        // 酒馆工具可按其独立设置选择 OpenClaw 兜底。
        viewModelScope.launch {
            val config = openclawRepository.getConfig()
            if (config.enabled && openclawRepository.getToken().isNotBlank()) {
                routeToOpenClaw(taskText, OpenClawRouter.decide(taskText, forcedByCommand = true))
            } else {
                insertNarratorMessage(
                    "* [工具] 这个任务手机本地暂不支持（支持：打开应用、音量、静音、亮度、电量、时间、网络、屏幕文字）。*"
                )
            }
        }
    }

    fun confirmLocalTool() {
        val plan = _uiState.value.pendingLocalTool ?: return
        _uiState.update { it.copy(pendingLocalTool = null) }
        updateAvatarState()
        executeLocalTool(
            plan,
            automationFollowUp = pureChatMode && girlfriendAutomationEnabled &&
                plan.action.isUiAutomationAction()
        )
    }

    fun dismissLocalTool() {
        val plan = _uiState.value.pendingLocalTool ?: return
        _uiState.update { it.copy(pendingLocalTool = null) }
        updateAvatarState()
        insertNarratorMessage("* [工具] 已取消${plan.description} *")
    }

    private fun executeLocalTool(plan: LocalToolPlan, automationFollowUp: Boolean = false) {
        viewModelScope.launch {
            if (plan.action is LocalToolAction.GenerateImage) {
                // AI 绘图：走 imageGenRepository，生成后自动发图
                insertNarratorMessage("* [绘图] ${plan.description}… *")
                try {
                    val result = withContext(Dispatchers.IO) {
                        forgeRepository.generateImage(
                            ForgeGenerationParams(
                                prompt = plan.action.prompt
                            )
                        )
                    }
                    when (result) {
                        is com.pockettavern.app.domain.model.Result.Success -> {
                            val imagePath = result.data
                            val msg = ChatMessage(content = "", isUser = false, imagePath = imagePath)
                            _uiState.update { it.copy(messages = it.messages + msg) }
                        }
                        else -> {
                            insertNarratorMessage("* [绘图] 生成失败，再试一次？ *")
                        }
                    }
                } catch (e: Exception) {
                    insertNarratorMessage("* [绘图] 出错了：${e.message} *")
                }
                return@launch
            }
            insertNarratorMessage("* [工具] ${plan.description}… *")
            val result = withContext(Dispatchers.IO) {
                localToolExecutor.execute(plan.action)
            }
            insertNarratorMessage("* [工具] $result *")
            if (pureChatMode && plan.action is LocalToolAction.WebSearch) {
                suppressToolFallbackOnce = true
                sendHiddenUserTurn(
                    "[真实联网搜索结果]\n$result\n请只依据以上真实结果回答用户原问题；不要伪造新的工具记录。"
                )
                return@launch
            }
            if (automationFollowUp) continueAutomation(result)
        }
    }

    private fun executeAutomationOpenApp(appName: String) {
        if (!girlfriendAutomationEnabled || !pureChatMode) return
        markAutomationStep()
        automationJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { deviceAppLauncher.launch(appName) }
            val text = if (result.success) "已打开 ${result.displayName}"
            else "无法打开 ${result.displayName}：${result.error ?: "未知错误"}"
            insertNarratorMessage("* [自动操作] $text *")
            continueAutomation(text)
        }
    }

    private fun executeAutomationAction(action: LocalToolAction, description: String) {
        if (!girlfriendAutomationEnabled || !pureChatMode) return
        if (action.requiresSensitiveConfirmation()) {
            _uiState.update {
                it.copy(
                    automationActive = false,
                    pendingLocalTool = LocalToolPlan(action, description, needsConfirmation = true)
                )
            }
            updateAvatarState()
            return
        }
        if (action.isUiAutomationAction() && !ScreenAccessManager.isEnabled(context)) {
            _uiState.update {
                it.copy(
                    automationActive = false,
                    pendingPermissionPrompt = PermissionPrompt(PermissionKind.SCREEN_ACCESS)
                )
            }
            updateAvatarState()
            return
        }
        val followUp = action.isUiAutomationAction() || action is LocalToolAction.ReadScreen ||
            action is LocalToolAction.WebSearch
        if (followUp) markAutomationStep()
        else _uiState.update { it.copy(automationActive = false) }
        executeLocalTool(
            LocalToolPlan(action, description, needsConfirmation = false),
            automationFollowUp = followUp
        )
    }

    private fun markAutomationStep() {
        automationStepCount++
        _uiState.update {
            it.copy(automationActive = true, automationStep = automationStepCount)
        }
    }

    private suspend fun continueAutomation(result: String) {
        if (!girlfriendAutomationEnabled || !pureChatMode) return
        if (automationStepCount >= maxAutomationSteps) {
            _uiState.update { it.copy(automationActive = false) }
            insertNarratorMessage("* [自动操作] 已达到 $maxAutomationSteps 步安全上限，任务已暂停。*")
            return
        }
        delay(1100)
        val snapshot = ScreenContextRepository.latest()
        val screenText = snapshot?.text?.take(5000).orEmpty()
        val packageName = snapshot?.packageName.orEmpty()
        val feedback = buildString {
            appendLine("[实验性手机自动操作反馈]")
            appendLine("第 $automationStepCount/$maxAutomationSteps 步结果：$result")
            appendLine("当前应用包名：${packageName.ifBlank { "未知" }}")
            appendLine("当前屏幕无障碍文字：")
            appendLine(screenText.ifBlank { "（没有读到文字，可尝试返回、滚动或使用百分比坐标点击）" })
            appendLine("如果原任务已完成，请正常回复且不要输出 device_action。")
            appendLine("如果未完成，只输出一个最合适的下一步 device_action；不要声称未实际执行的结果。")
        }
        suppressToolFallbackOnce = true
        sendHiddenUserTurn(feedback)
    }

    fun stopAutomation() {
        automationJob?.cancel()
        automationJob = null
        automationStepCount = 0
        generationJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { llmRepository.abortGeneration(_currentConfig) }
        _uiState.update {
            it.copy(
                automationActive = false,
                automationStep = 0,
                isGenerating = false,
                streamingContent = "",
                streamingThinking = "",
                pendingDeviceAction = null,
                pendingScreenRead = false,
                pendingLocalTool = null
            )
        }
        updateAvatarState()
        insertNarratorMessage("* [自动操作] 已由你停止。*")
    }

    private fun describeLocalTool(action: LocalToolAction): String = when (action) {
        is LocalToolAction.OpenApp -> "打开应用 ${action.name}"
        is LocalToolAction.ReadScreen -> "读取当前屏幕文字"
        is LocalToolAction.SetVolume -> if (action.delta ?: 0 > 0) "调高媒体音量" else "调低媒体音量"
        is LocalToolAction.SetVolumePercent -> "把媒体音量调到 ${action.percent}%"
        is LocalToolAction.Mute -> if (action.on) "将手机设为静音" else "取消静音"
        is LocalToolAction.GetVolume -> "查看当前音量"
        is LocalToolAction.SetBrightness -> "调整屏幕亮度"
        is LocalToolAction.GetBrightness -> "查看当前屏幕亮度"
        is LocalToolAction.GetBattery -> "查看当前电量"
        is LocalToolAction.GetTime -> "查看当前时间"
        is LocalToolAction.GetNetwork -> "查看网络连接状态"
        is LocalToolAction.WebSearch -> "联网搜索「${action.query}」"
        is LocalToolAction.ScheduleProactiveMessages -> "安排小女友主动发 ${action.count} 条消息"
        is LocalToolAction.GetApps ->
            if (action.keyword.isNullOrBlank()) "查看手机里的应用列表"
            else "搜索手机里的\"${action.keyword}\"应用"
        is LocalToolAction.ListFiles ->
            if (action.dir.isNullOrBlank()) "查看手机下载目录里的文件"
            else "查看手机\"${action.dir}\"目录里的文件"
        is LocalToolAction.GenerateImage -> "用AI画一张图"
        is LocalToolAction.ReadFile -> "读取文件内容"
        is LocalToolAction.CreateFile -> "创建并发送文件"
        is LocalToolAction.EditFile -> if (action.append) "向文件追加内容" else "修改文件内容"
        is LocalToolAction.DeleteFile -> "删除文件"
        is LocalToolAction.HideFile -> "藏文件"
        is LocalToolAction.UiClick -> "点击界面上的“${action.text}”"
        is LocalToolAction.UiInput -> if (action.target.isNullOrBlank()) "向当前文本框输入文字"
            else "向“${action.target}”输入文字"
        is LocalToolAction.UiScroll -> "向${action.direction}滚动页面"
        is LocalToolAction.UiGlobal -> "执行系统动作：${action.action}"
        is LocalToolAction.UiTap -> "点击屏幕 ${action.xPercent}%, ${action.yPercent}% 位置"
    }

    /**
     * 小女友的自然语言回复里不允许伪装成已执行的系统记录。真正的工具结果由
     * [executeLocalTool] 单独插入，因此这里删掉模型自行编写的同名行不会误伤执行结果。
     */
    private fun stripImitatedToolLogs(text: String): String {
        val fakeLog = Regex(
            pattern = """^\s*\*?\s*\[(?:工具|自动操作|手机工具|屏幕读取)]\s*[^\n]*\*?\s*$""",
            option = RegexOption.MULTILINE
        )
        return text.replace(fakeLog, "").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun routeToOpenClaw(taskText: String, decision: OpenClawRouteDecision) {
        if (decision.requiresConfirmation) {
            _uiState.update {
                it.copy(
                    pendingOpenClawTaskText = taskText,
                    pendingOpenClawDecision = decision
                )
            }
            updateAvatarState()
        } else {
            executeOpenClawTask(taskText)
        }
    }

    fun confirmPendingOpenClaw() {
        val taskText = _uiState.value.pendingOpenClawTaskText ?: return
        _uiState.update { it.copy(pendingOpenClawTaskText = null, pendingOpenClawDecision = null) }
        updateAvatarState()
        executeOpenClawTask(taskText)
    }

    fun dismissPendingOpenClaw() {
        _uiState.update { it.copy(pendingOpenClawTaskText = null, pendingOpenClawDecision = null) }
        updateAvatarState()
        insertNarratorMessage("* [OpenClaw] 已取消任务 *")
    }

    fun cancelOpenClawTask() {
        openclawRepository.cancelTask()
        updateOpenClawStatus(OpenClawTaskStatus.WORKING, "正在取消…")
    }

    private fun executeOpenClawTask(taskText: String) {
        if (_uiState.value.openclawTaskStatus == OpenClawTaskStatus.CONNECTING ||
            _uiState.value.openclawTaskStatus == OpenClawTaskStatus.WORKING
        ) {
            insertNarratorMessage("* [OpenClaw] 已有任务在执行，请先停止 *")
            return
        }
        openclawStatusMessageIndex = null
        viewModelScope.launch {
            val config = openclawRepository.getConfig()
            if (!config.enabled) {
                updateOpenClawStatus(OpenClawTaskStatus.FAILED, "OpenClaw 未启用，请到 设置 → OpenClaw 中开启")
                return@launch
            }
            if (openclawRepository.getToken().isBlank()) {
                updateOpenClawStatus(OpenClawTaskStatus.FAILED, "OpenClaw Token 未设置，请到 设置 → OpenClaw 中填写")
                return@launch
            }
            updateOpenClawStatus(OpenClawTaskStatus.CONNECTING, "正在连接 OpenClaw Gateway…")
            val accepted = openclawRepository.runTask(taskText) { status ->
                when (status) {
                    OpenClawTaskStatus.CONNECTING ->
                        updateOpenClawStatus(status, "正在连接 OpenClaw Gateway…")
                    OpenClawTaskStatus.WORKING ->
                        updateOpenClawStatus(status, "OpenClaw 正在执行任务…")
                    else -> {}
                }
            }
            if (!accepted) {
                updateOpenClawStatus(OpenClawTaskStatus.FAILED, "已有 OpenClaw 任务在执行，请先停止或等待完成")
                return@launch
            }
            openclawRepository.currentTask.value?.let { handleOpenClawResult(it) }
        }
    }

    private fun handleOpenClawResult(result: OpenClawTaskResult) {
        openclawStatusMessageIndex = null
        _uiState.update { it.copy(openclawTaskStatus = null, openclawStatusText = "") }
        updateAvatarState()
        when (result.status) {
            OpenClawTaskStatus.SUCCESS -> {
                val text = "* [OpenClaw] 任务完成：${result.message} *"
                insertNarratorMessage(text)
                // 把真实结果交给聊天模型自然化，让角色自然回应
                if (result.message.isNotBlank()) {
                    sendHiddenUserTurn(
                        "[系统] 刚才在手机上执行了一个任务，真实执行结果如下，请根据结果自然、简短地回应：\n${result.message}"
                    )
                }
            }
            OpenClawTaskStatus.CANCELLED ->
                insertNarratorMessage("* [OpenClaw] 任务已取消 *")
            OpenClawTaskStatus.FAILED ->
                insertNarratorMessage("* [OpenClaw] 任务失败：${result.message} *")
            else -> {}
        }
    }

    /** 更新任务状态：同步更新状态条与消息流中的单条状态消息（不刷屏）。 */
    private fun updateOpenClawStatus(status: OpenClawTaskStatus, text: String) {
        _uiState.update { it.copy(openclawTaskStatus = status, openclawStatusText = text) }
        updateAvatarState()
        val idx = openclawStatusMessageIndex
        if (idx != null && idx < _uiState.value.messages.size) {
            _uiState.update { st ->
                st.copy(
                    messages = st.messages.mapIndexed { i, m ->
                        if (i == idx) m.copy(content = "* [OpenClaw] $text *") else m
                    }
                )
            }
        } else {
            insertNarratorMessage("* [OpenClaw] $text *")
            openclawStatusMessageIndex = _uiState.value.messages.size - 1
        }
    }

    /** Send a quick-reply button message directly (bypasses inputText). */
    fun sendQuickReply(button: QuickReplyButton) {
        if (_uiState.value.character == null) return
        // Action buttons dispatch BUTTON_CLICKED event to JS instead of sending a message
        if (button.action.isNotBlank()) {
            val safeAction = button.action.replace("\\", "\\\\").replace("\"", "\\\"")
            val safeLabel = button.label.replace("\\", "\\\\").replace("\"", "\\\"")
            extensionManager.emitJson(
                ExtensionEvent.BUTTON_CLICKED,
                "{\"action\":\"$safeAction\",\"label\":\"$safeLabel\"}"
            )
            return
        }
        val text = button.message.trim()
        if (text.isBlank()) return
        sendMessageText(text)
    }

    fun insertNarratorMessage(text: String) {
        val narratorMessage = ChatMessage(content = text, isUser = false, isNarrator = true)
        _uiState.update { it.copy(messages = it.messages + narratorMessage) }
        viewModelScope.launch { saveCurrentChat() }
    }

    fun dismissScanlore() {
        _uiState.update { it.copy(showScanloreDialog = false, scanloreEntries = emptyList(), scanloreError = null, scanloreLoading = false) }
    }

    fun confirmScanlore(entries: List<String>) {
        val groupId = _currentGroupId ?: return
        viewModelScope.launch {
            entries.forEach { groupStorage.appendWorldBookEntry(groupId, it) }
            val updatedGroup = groupStorage.getGroupsForCharacter(
                _uiState.value.character?.avatar ?: ""
            ).firstOrNull { it.id == groupId }
            _currentWorldBook = updatedGroup?.worldBook ?: _currentWorldBook
            _uiState.update { it.copy(showScanloreDialog = false, scanloreEntries = emptyList(), hasWorldBook = _currentWorldBook.isNotBlank()) }
            val summary = if (entries.size == 1) entries[0] else "${entries.size} entries"
            insertNarratorMessage("* [World Book] Added: $summary *")
        }
    }

    private suspend fun runScanlore(messageCount: Int) {
        val character = _uiState.value.character
        val groupId = _currentGroupId
        if (groupId == null) {
            _uiState.update { it.copy(scanloreLoading = false, scanloreError = "尚未关联群组；/scanlore 需要群组世界书。") }
            return
        }
        val loreHints = character?.loreHints ?: ""
        if (loreHints.isBlank()) {
            _uiState.update { it.copy(scanloreLoading = false, scanloreError = "该角色尚未填写世界书追踪提示，请编辑角色并填写“世界书追踪”字段。") }
            return
        }

        val messages = _uiState.value.messages.takeLast(messageCount)
        val transcript = messages.joinToString("\n") { msg ->
            val role = when {
                msg.isNarrator -> "Narrator"
                msg.isUser -> _currentUserName
                else -> character?.name ?: "Character"
            }
            "$role: ${msg.content.take(500)}"
        }

        val extractionPrompt = """You are a lore extraction assistant. Read the following conversation excerpt and extract notable events worth recording in a shared world log.

TRACKING CRITERIA:
$loreHints

CONVERSATION:
$transcript

OUTPUT FORMAT:
Return ONLY a numbered list of concise lore entries, one per line, in past tense.
Only include events that actually occurred in this conversation.
If nothing notable happened, return exactly: Nothing notable to record.
No preamble, no explanation. Just the numbered list."""

        try {
            val config = _currentConfig
            var fullResponse = ""
            val oaiMessages = if (config.usesChatCompletions)
                listOf(com.pockettavern.app.domain.model.PromptMessage("user", extractionPrompt))
            else null
            llmRepository.generate(extractionPrompt, config, null, emptyList(), oaiMessages, null).collect { event ->
                when (event) {
                    is com.pockettavern.app.domain.model.StreamEvent.Token -> fullResponse = event.accumulated
                    is com.pockettavern.app.domain.model.StreamEvent.Complete -> fullResponse = event.fullText
                    else -> {}
                }
            }
            val entries = parseScanloreResponse(fullResponse)
            if (entries.isEmpty()) {
            _uiState.update { it.copy(scanloreLoading = false, scanloreEntries = emptyList(), scanloreError = "最近 $messageCount 条消息中未发现需要记录的内容。") }
            } else {
                _uiState.update { it.copy(scanloreLoading = false, scanloreEntries = entries, scanloreError = null) }
            }
        } catch (e: Exception) {
            _uiState.update { it.copy(scanloreLoading = false, scanloreError = "Scan failed: ${e.message}") }
        }
    }

    private fun parseScanloreResponse(raw: String): List<String> {
        if (raw.contains("nothing notable", ignoreCase = true)) return emptyList()
        return raw.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { line ->
                // Strip leading "1. " "- " "* " etc.
                line.replace(Regex("^[\\d]+\\.\\s*"), "")
                    .replace(Regex("^[-*•]\\s*"), "")
                    .trim()
            }
            .filter { it.isNotBlank() }
    }

    private fun sendMessageText(rawText: String) {
        val character = _uiState.value.character ?: return

        // Deterministic fallback that does not call the model. Natural-language requests
        // are handled through the model's confirmation-gated device_action tag.
        val directOpenPrefix = when {
            rawText.startsWith("/open ", ignoreCase = true) -> "/open "
            rawText.startsWith("/打开 ") -> "/打开 "
            else -> null
        }
        if (directOpenPrefix != null) {
            val appName = rawText.substring(directOpenPrefix.length).trim().take(80)
            _uiState.update {
                it.copy(
                    inputText = "",
                    pendingDeviceAction = appName.takeIf { name -> name.isNotBlank() }
                        ?.let { name -> PendingDeviceAction(name, requestedBy = "快捷命令") }
                )
            }
            if (appName.isBlank()) insertNarratorMessage("* [手机工具] 请在命令后填写应用名称，例如 /打开 微信 *")
            return
        }

        if (rawText.equals("/screen", ignoreCase = true) || rawText == "/屏幕") {
            _uiState.update { it.copy(inputText = "", pendingScreenRead = true) }
            updateAvatarState()
            return
        }

        // /看 — 相机视觉（B 计划）：拉起相机拍照后作为图片发送给模型
        if (rawText.startsWith("/看") || rawText.equals("/look", ignoreCase = true)) {
            _uiState.update { it.copy(inputText = "", cameraRequest = true) }
            return
        }

        // /claw <task> — tool command: 优先手机本地执行，本地不支持时回退 OpenClaw
        OpenClawRouter.extractClawTask(rawText)?.let { task ->
            if (task.isBlank()) {
                insertNarratorMessage("* [工具] 用法：/claw 任务描述，例如 /claw 打开相机、/claw 调高音量 *")
                _uiState.update { it.copy(inputText = "") }
                return
            }
            _uiState.update { it.copy(inputText = "") }
            routeToolTask(task)
            return
        }

        // /sys <text> — insert narrator message without sending to LLM
        if (rawText.startsWith("/sys ")) {
            val narratorText = rawText.removePrefix("/sys ").trim()
            if (narratorText.isNotBlank()) insertNarratorMessage(narratorText)
            _uiState.update { it.copy(inputText = "") }
            return
        }

        // /ooc <text> — send OOC message to LLM without showing user bubble
        if (rawText.startsWith("/ooc ")) {
            val oocText = rawText.removePrefix("/ooc ").trim()
            if (oocText.isNotBlank()) {
                _uiState.update { it.copy(inputText = "") }
                sendHiddenUserTurn("(OOC: $oocText)")
            }
            return
        }

        // /addlore <text> — append entry to linked group's shared world book
        if (rawText.startsWith("/addlore ")) {
            val entry = rawText.removePrefix("/addlore ").trim()
            _uiState.update { it.copy(inputText = "") }
            if (entry.isNotBlank()) {
                val groupId = _currentGroupId
                if (groupId != null) {
                    viewModelScope.launch {
                        groupStorage.appendWorldBookEntry(groupId, entry)
                        val updatedGroup = groupStorage.getGroupsForCharacter(
                            _uiState.value.character?.avatar ?: ""
                        ).firstOrNull { it.id == groupId }
                        _currentWorldBook = updatedGroup?.worldBook ?: _currentWorldBook
                        _uiState.update { it.copy(hasWorldBook = _currentWorldBook.isNotBlank()) }
                        insertNarratorMessage("* [World Book] Added: $entry *")
                    }
                } else {
                    insertNarratorMessage("* [World Book] No group linked to this character *")
                }
            }
            return
        }

        // /scanlore [N] — scan last N messages and extract lore entries
        if (rawText.startsWith("/scanlore")) {
            val countArg = rawText.removePrefix("/scanlore").trim().toIntOrNull() ?: 30
            _uiState.update { it.copy(inputText = "", showScanloreDialog = true, scanloreLoading = true, scanloreEntries = emptyList(), scanloreError = null) }
            viewModelScope.launch { runScanlore(countArg) }
            return
        }

        // /persona <name> — temporarily override persona name for the session
        if (rawText.startsWith("/persona ")) {
            val personaName = rawText.removePrefix("/persona ").trim()
            if (personaName.isNotBlank()) {
                _currentUserName = personaName
                insertNarratorMessage("* Persona changed to: $personaName *")
            }
            _uiState.update { it.copy(inputText = "") }
            return
        }

        autoContinueCount = 0
        automationStepCount = 0
        automationJob?.cancel()
        _uiState.update { it.copy(automationActive = false, automationStep = 0) }

        // 本地工具自动识别（仅当设置中开启；未开启时消息完全走普通聊天）
        viewModelScope.launch {
            // 小女友模式必须先自然回应，再由 device_action（或确定性兜底）弹确认。
            // 这里抢先执行会吞掉小女友回复，只剩生硬的工具结果。
            if (!pureChatMode) {
                val config = openclawRepository.getConfig()
                if (config.autoInvoke && LocalToolParser.parse(rawText) != null) {
                    routeToolTask(rawText)
                    return@launch
                }
            }
            sendUserMessage(rawText)
        }
    }

    private fun sendUserMessage(rawText: String, imageDataUrl: String? = null) {
        val character = _uiState.value.character ?: return
        if (pureChatMode) {
            com.pockettavern.app.ui.screens.girlfriend.GirlfriendProactiveManager
                .recordInteraction(context)
        }

        // Apply input regex rules, then full macro substitution
        val processed = if (pureChatMode) rawText else extensionManager.processInput(rawText)
        val macroContext = ChatContext(userPersona = UserPersona(name = _currentUserName, description = _currentPersonaDescription))
        val macroBuilder = PromptBuilder(character, macroContext, _currentUserName)
        val message = macroBuilder.applyUserMacros(processed, _uiState.value.messages)
        val displayContent = message.ifBlank {
            if (imageDataUrl != null) "（发送了一张照片）" else ""
        }
        val userMessage = ChatMessage(content = displayContent, isUser = true)
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                inputText = "",
                tokenCount = 0,
                isGenerating = true,
                streamingContent = ""
            )
        }
        updateAvatarState()
        if (!pureChatMode) {
            pushExtensionContext()
            extensionManager.emit(ExtensionEvent.MESSAGE_SENT, message)
        }

        if (_uiState.value.currentChatFileName == null) {
            val fileName = localRepository.generateChatFileName(storageKey())
            _uiState.update { it.copy(currentChatFileName = fileName) }
        }

        generateResponse(character, message, _uiState.value.messages.dropLast(1), imageDataUrl)
    }

    private fun generateResponse(
        character: Character,
        userMessage: String,
        history: List<ChatMessage>,
        imageDataUrl: String? = null,
        pureChat: Boolean = pureChatMode
    ) {
        if (!pureChat) extensionManager.emit(ExtensionEvent.GENERATION_STARTED)
        generationJob = viewModelScope.launch {
            doGenerate(history, userMessage, imageDataUrl, pureChat).collect { event ->
                when (event) {
                    is StreamEvent.Token -> {
                        // 流式首屏清洗：模型思考式开场废话（"好的，让我想想…"）只在开头剥一次；
                        // accumulated 是全量累积，剥掉前缀后后续显示即完整剩余，无 token 切碎问题。
                        val preview = if (pureChat) {
                            StructuredReplyParser.streamPreview(event.accumulated)
                        } else {
                            if (_uiState.value.streamingContent.isEmpty()) {
                                StructuredReplyParser.stripThinkingPrefix(event.accumulated)
                            } else {
                                event.accumulated
                            }
                        }
                        _uiState.update { it.copy(streamingContent = preview) }
                    }
                    is StreamEvent.ThinkingToken -> {
                        _uiState.update { it.copy(streamingThinking = event.accumulatedThinking) }
                    }
                    is StreamEvent.Complete -> {
                        // Step 0: 结构化回复解析（{text, avatar, voice}）。失败自动回退纯文本，
                        // 不影响任何后续流程；控制字段只用于表演与语音，不进入显示文本。
                        val structured = StructuredReplyParser.parse(event.fullText)
                        // 女友模式：对解析出的正文再做一轮消毒，兜住思考/场景/JSON 残留
                        val structuredText = if (pureChat) {
                            StructuredReplyParser.sanitizeGirlfriendText(structured.text)
                        } else {
                            structured.text
                        }
                        // Step 1: apply regex rules + multi-turn trim (keeps extension tags intact),
                        // then resolve {{user}}/{{char}} macros — PocketTavern's RP-tuned models emit
                        // them literally (trained that way); harmless for models that never emit them.
                        val charName = _uiState.value.character?.name ?: ""
                        val extensionProcessed = if (pureChat) structuredText else extensionManager.processOutput(structuredText)
                        val processedWithToolTag = trimMultiTurn(
                            if (pureChat) stripImitatedToolLogs(extensionProcessed) else extensionProcessed
                        )
                            .replace("{{user}}", _currentUserName, ignoreCase = true)
                            .let { if (charName.isNotBlank()) it.replace("{{char}}", charName, ignoreCase = true) else it }
                        val rawParsedDeviceAction = DeviceActionParser.parse(processedWithToolTag)
                        val parsedDeviceAction = if (pureChat) {
                            rawParsedDeviceAction.copy(
                                readScreenRequested = false,
                                localTool = rawParsedDeviceAction.localTool?.takeIf { it.isPhoneCompanionAction() }
                            )
                        } else rawParsedDeviceAction
                        val suppressFallback = suppressToolFallbackOnce
                        suppressToolFallbackOnce = false
                        // 纯模式下模型可能忘了加 device_action 标签——从用户原话确定性兜底。
                        // 兜底同样走确认窗口，不能直接执行或吞掉自然回复。
                        val effectiveAction = if (pureChat && !suppressFallback && parsedDeviceAction.appName == null && parsedDeviceAction.localTool == null && !parsedDeviceAction.readScreenRequested) {
                            val lastUserMsg = _uiState.value.messages.lastOrNull { it.isUser }?.content ?: ""
                            when (val inferred = LocalToolParser.parse(lastUserMsg)?.action) {
                                is LocalToolAction.OpenApp ->
                                    ParsedDeviceAction(parsedDeviceAction.visibleText, inferred.name, false, null)
                                null -> parsedDeviceAction
                                else -> if (inferred.isPhoneCompanionAction()) {
                                    ParsedDeviceAction(parsedDeviceAction.visibleText, null, false, inferred)
                                } else parsedDeviceAction
                            }
                        } else parsedDeviceAction
                        val autoOpenApp = pureChat && effectiveAction.appName != null
                        val autoReadScreen = false
                        val effectiveLocalTool = effectiveAction.localTool
                        val autoLocalTool = pureChat && effectiveLocalTool?.isPhoneCompanionAction() == true &&
                            !effectiveLocalTool.requiresSensitiveConfirmation()
                        val processed = effectiveAction.visibleText.ifBlank {
                            when {
                                autoOpenApp -> "我现在帮你打开${effectiveAction.appName}。"
                                autoReadScreen -> "我正在读取当前页面。"
                                autoLocalTool -> "我现在执行这个手机操作。"
                                effectiveAction.appName != null -> "我可以帮你打开${effectiveAction.appName}，请在确认窗口中选择是否执行。"
                                effectiveAction.readScreenRequested -> "我可以读取当前页面公开的文字，请在确认窗口中选择是否发送给我。"
                                effectiveAction.localTool != null -> "我可以帮你执行这个手机操作，请在确认窗口中选择是否执行。"
                                else -> processedWithToolTag
                            }
                        }
                        val reasoning = event.thinkingText.ifBlank { null }
                        // Step 2: add message with raw text so we can emit MESSAGE_RECEIVED first
                        val rawMessage = ChatMessage(content = processed, isUser = false, reasoning = reasoning)
                        _uiState.update {
                            it.copy(
                                messages = it.messages + rawMessage,
                                isGenerating = false,
                                streamingContent = "",
                                streamingThinking = "",
                                pendingDeviceAction = effectiveAction.appName
                                    .takeUnless { autoOpenApp }
                                    ?.let { name -> PendingDeviceAction(name) },
                                pendingScreenRead = effectiveAction.readScreenRequested && !autoReadScreen,
                                pendingLocalTool = effectiveAction.localTool
                                    ?.takeUnless { autoLocalTool }
                                    ?.let { tool ->
                                    LocalToolPlan(
                                        action = tool,
                                        description = describeLocalTool(tool),
                                        needsConfirmation = true
                                    )
                                },
                                automationActive = false,
                                pendingPermissionPrompt = detectPermissionPrompt(processed),
                                // 结构化表演控制（保留到下一条回复；未识别字段不影响聊天）
                                avatarEmotion = structured.emotion,
                                avatarMotion = structured.motion,
                                avatarGaze = structured.gaze,
                                avatarIntensity = structured.intensity,
                                avatarRequestToken = it.avatarRequestToken + 1,
                                error = null
                            )
                        }
                        updateAvatarState()
                        // Step 3: update extension context, then emit MESSAGE_RECEIVED
                        val msgIndex = _uiState.value.messages.lastIndex
                        val safeText = processed
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                        if (!pureChat) {
                            pushExtensionContext()
                            extensionManager.emitJson(
                                ExtensionEvent.MESSAGE_RECEIVED,
                                "{\"text\":\"$safeText\",\"index\":$msgIndex,\"isUser\":false}"
                            )
                        }
                        // Step 4: apply JS output filters to strip metadata tags from display
                        val displayText = if (pureChat) processed else extensionManager.applyOutputFilters(processed)
                        if (displayText != processed) {
                            val messages = _uiState.value.messages.toMutableList()
                            messages[msgIndex] = messages[msgIndex].copy(
                                content = displayText,
                                rawContent = processed
                            )
                            _uiState.update { it.copy(messages = messages) }
                        }
                        // Step 5: refresh context (rawContent now set), persist headers
                        if (!pureChat) {
                            pushExtensionContext()
                            persistExtensionHeaders()
                        }
                        updateContextEstimate()
                        if (!pureChat) extensionManager.emit(ExtensionEvent.GENERATION_STOPPED)
                        generationJob = null
                        saveCurrentChat()
                        when {
                            autoOpenApp -> executeLocalTool(
                                LocalToolPlan(
                                    LocalToolAction.OpenApp(effectiveAction.appName!!),
                                    "打开应用 ${effectiveAction.appName}",
                                    needsConfirmation = false
                                )
                            )
                            autoReadScreen -> executeAutomationAction(
                                LocalToolAction.ReadScreen("全自动模式"),
                                "读取当前屏幕文字"
                            )
                            autoLocalTool -> executeLocalTool(
                                LocalToolPlan(
                                    effectiveLocalTool!!,
                                    describeLocalTool(effectiveLocalTool),
                                    needsConfirmation = false
                                )
                            )
                        }
                        // Trigger long-term memory summarization if threshold exceeded (T14)
                        triggerMemorySummarizationIfNeeded()
                        // Auto-play TTS for new AI message（走新管线：真实电平口型 + 结构化语速/风格）
                        if (ttsAutoPlay) {
                            val ttsText = if (pureChat) processed else extensionManager.applyOutputFilters(processed)
                            val charFile = _uiState.value.character?.avatar
                                ?: "${_uiState.value.character?.name ?: "unknown"}.png"
                            val stylePrompt = voiceStyleInstructions(structured.voiceStyle)
                            viewModelScope.launch {
                                voiceOutputProvider.speak(
                                    ttsText,
                                    characterFile = charFile,
                                    speed = structured.voiceSpeed,
                                    stylePrompt = stylePrompt.ifBlank { null }
                                )
                            }
                        }
                        // Auto-continue: if response is shorter than min length, request more
                        val estimatedTokens = extensionManager.tokenCounter.estimateTokens(processed)
                        if (!pureChat && autoContinueEnabled && autoContinueCount < 3 && estimatedTokens < autoContinueMinLength) {
                            autoContinueCount++
                            continueGeneration()
                        }
                    }
                    is StreamEvent.Error -> {
                        _uiState.update {
                            it.copy(isGenerating = false, streamingContent = "", streamingThinking = "", error = event.message)
                        }
                        updateAvatarState()
                        if (!pureChat) extensionManager.emit(ExtensionEvent.GENERATION_STOPPED)
                        generationJob = null
                    }
                }
            }
        }
    }

    /**
     * Build and stream a generation from the given history + current user message.
     * Loads ChatContext, builds prompt via PromptBuilder, calls LlmRepository.
     */
    private fun doGenerate(
        history: List<ChatMessage>,
        userMessage: String,
        imageDataUrl: String? = null,
        pureChat: Boolean = false
    ): Flow<StreamEvent> = flow {
        val character = _uiState.value.character
        if (character == null) {
            emit(StreamEvent.Error("No character loaded"))
            return@flow
        }

        val charFileName = character.avatar ?: "${character.name}.png"
        val loadedContext = when (val r = localRepository.loadChatContext(
            characterFileName = charFileName,
            chatFileName = _uiState.value.currentChatFileName
        )) {
            is Result.Success -> r.data
            is Result.Error -> {
                emit(StreamEvent.Error("Failed to load context: ${r.exception.message}"))
                return@flow
            }
        }
        // 纯净模式：剥掉全局系统提示词预设、用户 persona 描述、世界书、作者注释，只保留角色卡自己的提示词
        val chatContext = if (pureChat) {
            loadedContext.copy(
                systemPromptPreset = "",
                userPersona = loadedContext.userPersona.copy(
                    name = com.pockettavern.app.data.girlfriend.GirlfriendDynamicContext.userName,
                    description = ""
                ),
                worldInfoEntries = emptyList(),
                authorsNote = com.pockettavern.app.domain.model.AuthorsNote()
            )
        } else loadedContext
        com.pockettavern.app.util.DebugLogger.log(
            "  [GF-PURE] pureChatMode=$pureChatMode | sysPreset='${chatContext.systemPromptPreset.take(50)}' | char='${character.name}' | systemPrompt.len=${character.systemPrompt.length}"
        )
        // 纯净模式防御：历史消息里若残留思考/场景构建内容（如"让我来设计剧情…"），发送前清洗掉，
        // 防止模型模仿脏历史或把历史里的推理继续展开。
        val sanitizedHistory = if (pureChat) {
            history.map { msg ->
                if (msg.isUser) msg
                else {
                    val cleaned = com.pockettavern.app.ui.screens.chat.StructuredReplyParser.stripThinkingPrefix(msg.content)
                    if (cleaned != msg.content) msg.copy(content = cleaned) else msg
                }
            }
        } else history

        val config = when (val r = localRepository.getApiConfiguration()) {
            is Result.Success -> r.data
            is Result.Error -> ApiConfiguration.DEFAULT
        }
        _currentConfig = config

        // 图片能力按“具体模型”判断，不能把 Chat Completions 接口误当成一定支持图片。
        // 文本模型统一先由独立视觉模型生成描述，再只发送文字。
        val nativeImageInput = imageDataUrl != null && supportsNativeImageInput(config)
        val imageDescription = if (imageDataUrl != null && !nativeImageInput) {
            val vision = settingsDataStore.getGeminiVisionConfig()
            com.pockettavern.app.data.girlfriend.GeminiVisionClient.describeDataUrl(
                imageDataUrl,
                com.pockettavern.app.data.girlfriend.GeminiVisionClient.Config(
                    apiKey = vision.apiKey,
                    model = vision.model
                )
            )
        } else ""
        if (imageDataUrl != null && !nativeImageInput && imageDescription.isBlank()) {
            emit(
                StreamEvent.Error(
                    "当前主模型不支持图片。请在小女友设置中配置 Gemini Vision，图片会先转成文字描述再交给当前模型。"
                )
            )
            return@flow
        }

        val preset = if (!config.usesChatCompletions && !pureChat) localRepository.getCurrentTextGenPreset() else null
        val oaiPreset = if (config.usesChatCompletions && !pureChat) localRepository.getCurrentOaiPreset() else null
        val userName = if (pureChat) {
            com.pockettavern.app.data.girlfriend.GirlfriendDynamicContext.userName
        } else {
            chatContext.userPersona.name.ifBlank { "User" }
        }
        _currentUserName = userName
        _currentPersonaDescription = chatContext.userPersona.description
        val mainPromptItem = oaiPreset?.promptOrder?.find { it.id == "main_prompt" }
        val mainPromptOverride = if (config.usesChatCompletions && mainPromptItem?.enabled == true && !pureChat)
            mainPromptItem.content ?: "" else ""
        val extensionInjections = if (pureChat) emptyList()
            else extensionManager.getPromptInjections() + DEVICE_TOOL_PROMPT
        // PocketTavern's own models (name starts with "pockettavern" — on-device GGUF/litertlm OR
        // a remote endpoint serving them, e.g. llama-server with --alias) have the format/rules
        // baked into the weights, so use the lean prompt (skip preset prose). All other models
        // are unaffected.
        val leanMode = config.currentModel.startsWith("pockettavern", ignoreCase = true)
        val builder = PromptBuilder(character, chatContext, userName, mainPromptOverride, extensionInjections,
            if (pureChat) "" else _currentMemoryBlock,
            if (pureChat) "" else _currentWorldBook,
            leanMode,
            pureSystemPrompt = pureChat,
            languageDirective = com.pockettavern.app.util.LocaleHelper.responseLanguageDirective(context))
        // 纯净模式：将小女友动态状态注入 user message，最大化 DeepSeek system prefix 缓存命中
        val enrichedMessage = if (pureChat) {
            val tag = com.pockettavern.app.data.girlfriend.GirlfriendDynamicContext.stateTag
            if (tag.isNotBlank()) "$userMessage\n\n$tag" else userMessage
        } else userMessage
        val finalMessage = if (imageDescription.isNotBlank()) {
            "（用户发来一张照片，视觉模型描述：$imageDescription）\n\n$enrichedMessage"
        } else enrichedMessage
        val prompt = builder.buildPrompt(sanitizedHistory, finalMessage)

        // For chat completion APIs, also build structured messages for proper role formatting.
        val messages = if (config.usesChatCompletions) {
            val promptOrder = oaiPreset?.promptOrder ?: com.pockettavern.app.domain.model.OaiPromptOrderItem.defaultOrder()
            val built = builder.buildChatCompletionMessages(sanitizedHistory, finalMessage, promptOrder)
            if (imageDataUrl != null && nativeImageInput) {
                val lastUserIdx = built.indexOfLast { it.role.equals("user", true) }
                if (lastUserIdx >= 0) {
                    built.mapIndexed { i, m -> if (i == lastUserIdx) m.copy(imageDataUrl = imageDataUrl) else m }
                } else {
                    built + PromptMessage("user", userMessage, imageDataUrl)
                }
            } else built
        } else null

        // Notify extensions that a prompt is about to be sent (T23)
        if (!pureChat) extensionManager.fireBeforePromptSend(prompt, messages?.size ?: 0)

        // Stop sequences: instruct template markers only apply to text completion backends.
        // Chat completion APIs handle turn boundaries themselves — sending [INST]/</s>/etc.
        // as stop sequences is meaningless noise and can cause premature truncation.
        val stopSequences = if (config.usesChatCompletions) {
            emptyList()
        } else {
            buildList {
                chatContext.instructTemplate?.let { t ->
                    if (t.inputSequence.isNotBlank()) add(t.inputSequence)
                    if (t.stopSequence.isNotBlank()) add(t.stopSequence)
                }
            }
        }

        llmRepository.generate(prompt, config, preset, stopSequences, messages, oaiPreset, config.showThoughts).collect { emit(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * 保守识别原生图片模型。未知模型默认按纯文本处理，避免把 image_url 数组
     * 发给 DeepSeek 等只接受字符串 content 的接口。
     */
    private fun supportsNativeImageInput(config: ApiConfiguration): Boolean {
        if (!config.usesChatCompletions || config.isAnyOnDevice) return false
        val source = config.chatCompletionSource.lowercase()
        if (source in setOf("deepseek", "cohere", "mistralai")) return false
        if (source in setOf("makersuite", "vertexai")) return true
        val model = config.currentModel.lowercase()
        return listOf(
            "gpt-4o", "gpt-4.1", "gpt-5", "gemini", "claude-3", "claude-4",
            "vision", "llava", "pixtral", "qwen-vl", "qwen2-vl", "qwen2.5-vl",
            "glm-4v", "internvl"
        ).any(model::contains)
    }

    fun stopGeneration() {
        generationJob?.cancel()
        generationJob = null
        if (!pureChatMode) extensionManager.emit(ExtensionEvent.GENERATION_STOPPED)

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            llmRepository.abortGeneration(_currentConfig)
        }

        val streamingContent = _uiState.value.streamingContent
        if (streamingContent.isNotBlank()) {
            val assistantMessage = ChatMessage(content = streamingContent, isUser = false)
            _uiState.update {
                it.copy(
                    messages = it.messages + assistantMessage,
                    isGenerating = false,
                    streamingContent = ""
                )
            }
            viewModelScope.launch { saveCurrentChat() }
        } else {
            _uiState.update { it.copy(isGenerating = false, streamingContent = "") }
        }
    }

    // ========== Message Actions ==========

    fun showMessageActions(messageIndex: Int) {
        _uiState.update {
            it.copy(selectedMessageIndex = messageIndex, showMessageActions = true)
        }
        // Dispatch MESSAGE_LONG_PRESSED so extensions can register context menu actions
        if (!pureChatMode) {
            extensionManager.emitJson(
                ExtensionEvent.MESSAGE_LONG_PRESSED,
                "{\"messageIndex\":$messageIndex}"
            )
        }
    }

    fun dismissMessageActions() {
        _uiState.update { it.copy(selectedMessageIndex = null, showMessageActions = false) }
    }

    fun saveImageMessageToGallery(messageIndex: Int) {
        val message = _uiState.value.messages.getOrNull(messageIndex) ?: return
        val imagePath = message.imagePath ?: return
        val characterName = _uiState.value.character?.name ?: "Image"

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val imageFile = File(context.filesDir, imagePath)
                    if (!imageFile.exists()) throw Exception("Image file not found")

                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        ?: throw Exception("Failed to decode image")
                    val filename = "${characterName}_scene_${System.currentTimeMillis()}.png"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketTavern")
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                        ) ?: throw Exception("Failed to create media entry")
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        } ?: throw Exception("Failed to open output stream")
                    } else {
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "PocketTavern"
                        ).also { it.mkdirs() }
                        FileOutputStream(File(dir, filename)).use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    _uiState.update { it.copy(imageSaved = true) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = "Failed to save image: ${e.message}") }
                }
            }
        }
    }

    // ── Image Gallery ────────────────────────────────────────────────────────

    fun showGallery() {
        viewModelScope.launch {
            val characterName = storageKey()
            val images = withContext(Dispatchers.IO) { collectCharacterImages(characterName) }
            _uiState.update { it.copy(showGallery = true, galleryImages = images) }
        }
    }

    fun dismissGallery() {
        _uiState.update { it.copy(showGallery = false) }
    }

    // ── LLM model picker ──────────────────────────────────────────────────

    fun showModelPicker() {
        viewModelScope.launch {
            _uiState.update { it.copy(showModelPicker = true, modelPickerLoading = true, availableModels = emptyList()) }
            val config = when (val r = localRepository.getApiConfiguration()) {
                is Result.Success -> r.data
                else -> { _uiState.update { it.copy(modelPickerLoading = false) }; return@launch }
            }
            val models = try {
                withContext(Dispatchers.IO) { llmRepository.getAvailableModels(config) }.map { it.id }
            } catch (e: Exception) {
                emptyList()
            }
            _uiState.update { it.copy(availableModels = models, modelPickerLoading = false) }
        }
    }

    fun dismissModelPicker() {
        _uiState.update { it.copy(showModelPicker = false) }
    }

    fun applyModelChange(modelName: String) {
        _uiState.update { it.copy(showModelPicker = false) }
        viewModelScope.launch {
            val config = when (val r = localRepository.getApiConfiguration()) {
                is Result.Success -> r.data
                else -> return@launch
            }
            localRepository.saveApiConfiguration(config.copy(currentModel = modelName))
            // apiConfigFlow updates currentModelName in UiState automatically
        }
    }

    private suspend fun collectCharacterImages(characterName: String): List<GalleryImage> {
        val chats = localRepository.getCharacterChats(characterName).getOrNull() ?: return emptyList()
        val images = mutableListOf<GalleryImage>()
        for (chatInfo in chats) {
            val chat = localRepository.getChat(characterName, chatInfo.fileName).getOrNull() ?: continue
            chat.messages.forEachIndexed { index, message ->
                val path = message.imagePath ?: return@forEachIndexed
                val file = File(context.filesDir, path)
                if (!file.exists()) return@forEachIndexed
                val ts = file.name.removeSuffix(".png").toLongOrNull() ?: file.lastModified()
                images.add(GalleryImage(path, chatInfo.fileName, ts, index))
            }
        }
        return images.sortedByDescending { it.timestamp }
    }

    fun saveGalleryImageToDevice(image: GalleryImage) {
        val characterName = _uiState.value.character?.name ?: "Image"
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val imageFile = File(context.filesDir, image.imagePath)
                    if (!imageFile.exists()) throw Exception("Image file not found")
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                        ?: throw Exception("Failed to decode image")
                    val filename = "${characterName}_scene_${image.timestamp}.png"

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val contentValues = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PocketTavern")
                        }
                        val uri = context.contentResolver.insert(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues
                        ) ?: throw Exception("Failed to create media entry")
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        } ?: throw Exception("Failed to open output stream")
                    } else {
                        val dir = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "PocketTavern"
                        ).also { it.mkdirs() }
                        FileOutputStream(File(dir, filename)).use { out ->
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }
                    _uiState.update { it.copy(imageSaved = true) }
                } catch (e: Exception) {
                    _uiState.update { it.copy(error = "Failed to save image: ${e.message}") }
                }
            }
        }
    }

    fun deleteGalleryImage(image: GalleryImage) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Delete image file
                val imageFile = File(context.filesDir, image.imagePath)
                imageFile.delete()
            }
            // Refresh gallery
            val characterName = storageKey()
            val images = withContext(Dispatchers.IO) { collectCharacterImages(characterName) }
            _uiState.update { it.copy(galleryImages = images) }
        }
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    fun speakMessage(index: Int) {
        val message = _uiState.value.messages.getOrNull(index) ?: return
        val charFile = _uiState.value.character?.avatar
            ?: "${_uiState.value.character?.name ?: "unknown"}.png"
        viewModelScope.launch { voiceOutputProvider.speak(message.content, characterFile = charFile) }
    }

    fun stopTts() {
        voiceOutputProvider.stop()
        ttsManager.stop()
    }

    // ── 语音输入（按住说话） ──────────────────────────────────────────────────

    fun startVoiceInput() {
        if (_uiState.value.isListening) return
        _uiState.update { it.copy(isListening = true, listeningPartial = "", voiceInputError = null) }
        updateAvatarState()
    }

    fun updateVoicePartial(text: String) {
        if (!_uiState.value.isListening) return
        _uiState.update { it.copy(listeningPartial = text) }
    }

    fun stopVoiceInput(finalText: String) {
        if (!_uiState.value.isListening) return
        _uiState.update { it.copy(isListening = false, listeningPartial = "") }
        updateAvatarState()
        if (finalText.isNotBlank()) {
            val current = _uiState.value.inputText
            _uiState.update {
                it.copy(inputText = if (current.isBlank()) finalText else "$current $finalText")
            }
        }
    }

    fun cancelVoiceInput() {
        if (!_uiState.value.isListening) return
        _uiState.update { it.copy(isListening = false, listeningPartial = "", voiceInputError = null) }
        updateAvatarState()
    }

    fun voiceInputFailed(message: String) {
        if (!_uiState.value.isListening) return
        _uiState.update { it.copy(isListening = false, listeningPartial = "", voiceInputError = message) }
        updateAvatarState()
    }

    /** 点击 Live2D 角色：朗读中时按设置打断（tap-to-interrupt）。 */
    fun handleAvatarTap() {
        if (ttsConfig.tapToInterrupt && (isTtsSpeakingNow())) {
            stopTts()
        }
    }

    private fun isTtsSpeakingNow(): Boolean = _uiState.value.isTtsSpeaking

    // ── 相机视觉（B 计划） ───────────────────────────────────────────────────

    /** 界面拉起的相机按钮 / 命令统一入口：置位后由 ChatScreen 触发拍照。 */
    fun requestCamera() {
        _uiState.update { it.copy(cameraRequest = true) }
    }

    fun dismissCameraRequest() {
        _uiState.update { it.copy(cameraRequest = false) }
    }

    /** 拍照结果（已压缩为 data URL）→ 作为带图用户消息发送。 */
    fun sendPhoto(photoDataUrl: String, caption: String) {
        _uiState.update { it.copy(cameraRequest = false) }
        val text = caption.ifBlank { "（我拍了一张照片，帮我看看周围的情况）" }
        sendUserMessage(text, photoDataUrl)
    }

    /** 拍照 Uri → 解码压缩（最长边 1280、JPEG 80）→ base64 data URL → 发送。 */
    fun sendPhotoUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: return@launch
                val maxSide = 1280
                val (w, h) = bitmap.width to bitmap.height
                val scale = if (maxOf(w, h) > maxSide) maxSide.toFloat() / maxOf(w, h) else 1f
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (w * scale).toInt().coerceAtLeast(1),
                        (h * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else bitmap
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                val dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                out.close()
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                sendPhoto(dataUrl, "")
            } catch (e: Exception) {
                DebugLogger.log("[Camera] 拍照处理失败: ${e.message}")
                _uiState.update { it.copy(error = "拍照处理失败：${e.message}") }
            }
        }
    }

    fun deleteMessage(index: Int) {
        val messages = _uiState.value.messages.toMutableList()
        if (index in messages.indices) {
            messages.removeAt(index)
            // Shift headers: remove deleted index, shift higher indices down by 1
            val oldHeaders = _uiState.value.messageHeaders
            val newHeaders = mutableMapOf<Int, List<MessageHeaderEntry>>()
            oldHeaders.forEach { (idx, entries) ->
                when {
                    idx < index -> newHeaders[idx] = entries
                    idx > index -> newHeaders[idx - 1] = entries
                    // idx == index: dropped (deleted message)
                }
            }
            // Also shift visibleHeaderButtons
            val newVisibleBtns = _uiState.value.visibleHeaderButtons
                .filter { it.first != index }
                .map { if (it.first > index) (it.first - 1) to it.second else it }
                .toSet()
            if (!pureChatMode) extensionManager.replaceMessageHeaders(newHeaders)
            _uiState.update {
                it.copy(
                    messages = messages,
                    showMessageActions = false,
                    selectedMessageIndex = null,
                    messageHeaders = newHeaders,
                    visibleHeaderButtons = newVisibleBtns
                )
            }
            viewModelScope.launch { saveCurrentChat() }
        }
    }

    fun deleteMessagesFromIndex(index: Int) {
        val messages = _uiState.value.messages.toMutableList()
        if (index !in messages.indices) return

        // Remove this message and everything after it
        val removed = messages.size - index
        while (messages.size > index) {
            messages.removeAt(messages.size - 1)
        }

        // Rebuild headers: keep only indices before the cutoff
        val oldHeaders = _uiState.value.messageHeaders
        val newHeaders = oldHeaders.filterKeys { it < index }
        val newVisibleBtns = _uiState.value.visibleHeaderButtons
            .filter { it.first < index }
            .toSet()

        if (!pureChatMode) extensionManager.replaceMessageHeaders(newHeaders)
        _uiState.update {
            it.copy(
                messages = messages,
                showMessageActions = false,
                selectedMessageIndex = null,
                messageHeaders = newHeaders,
                visibleHeaderButtons = newVisibleBtns
            )
        }
        viewModelScope.launch { saveCurrentChat() }
        // Notify extensions
        if (!pureChatMode) extensionManager.emit(ExtensionEvent.MESSAGE_DELETED, index)
    }

    fun regenerateResponse() {
        val messages = _uiState.value.messages
        val character = _uiState.value.character ?: return

        val lastAssistantIndex = messages.indexOfLast { !it.isUser }
        if (lastAssistantIndex == -1) return

        val userMessageIndex = (lastAssistantIndex - 1 downTo 0).firstOrNull { messages[it].isUser }
            ?: return

        val userMessage = messages[userMessageIndex].content
        val history = messages.subList(0, userMessageIndex)

        _uiState.update {
            it.copy(
                messages = messages.subList(0, lastAssistantIndex),
                isGenerating = true,
                streamingContent = ""
            )
        }
        generateResponse(character, userMessage, history)
    }

    // ── Chat Background ───────────────────────────────────────────────────

    fun uploadBackgroundFromUri(uri: android.net.Uri) {
        viewModelScope.launch {
            val success = backgroundRepository.saveBackgroundFromUri(currentAvatarUrl, uri)
            if (success) {
                val bgPath = backgroundRepository.getBackgroundPath(currentAvatarUrl)
                _uiState.update { it.copy(backgroundPath = bgPath) }
            } else {
            _uiState.update { it.copy(error = "无法将图片设为背景") }
            }
        }
    }

    fun clearBackground() {
        viewModelScope.launch {
            backgroundRepository.deleteBackground(currentAvatarUrl)
            _uiState.update { it.copy(backgroundPath = null) }
        }
    }

    private suspend fun saveCurrentChat() {
        val character = _uiState.value.character ?: return
        val fileName = _uiState.value.currentChatFileName ?: return
        val chat = Chat(
            fileName = fileName,
            characterName = storageKey(),
            messages = _uiState.value.messages
        )
        localRepository.saveChat(chat)
    }

    private fun triggerMemorySummarizationIfNeeded() {
        if (pureChatMode || !memoryEnabled) return
        val character = _uiState.value.character ?: return
        val fileName = _uiState.value.currentChatFileName ?: return
        val messages = _uiState.value.messages
        val unsummarized = messages.drop(_currentSummarizedTurnCount)
        val charCount = unsummarized.sumOf { it.content.length }
        if (charCount < 12_000) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = when (val r = localRepository.getApiConfiguration()) {
                    is com.pockettavern.app.domain.model.Result.Success -> r.data
                    else -> return@launch
                }
                val summary = summarizeHistoryUseCase.summarize(unsummarized, config)
                if (summary.isBlank()) return@launch
                val newBlock = if (_currentMemoryBlock.isBlank()) summary
                    else "$_currentMemoryBlock\n$summary"
                val newCount = messages.size
                _currentMemoryBlock = newBlock
                _currentSummarizedTurnCount = newCount
                localRepository.updateChatMemoryBlock(storageKey(), fileName, newBlock, newCount)
                com.pockettavern.app.util.DebugLogger.log("ChatViewModel: memory summarized $newCount turns, block=${newBlock.length} chars")
            } catch (e: Exception) {
                com.pockettavern.app.util.DebugLogger.logError("ChatViewModel", "Memory summarization failed", e)
            }
        }
    }

    /** Snapshot current extension headers onto the corresponding ChatMessage objects. Returns true if anything changed. */
    private fun persistExtensionHeaders(): Boolean {
        if (pureChatMode) return false
        val headers = _uiState.value.messageHeaders
        val messages = _uiState.value.messages.toMutableList()
        var changed = false

        // Apply current headers to messages, and clear headers from messages
        // that no longer have entries (e.g. after clearing or shifting)
        for (index in messages.indices) {
            val msg = messages[index]
            val entries = headers[index] ?: emptyList()
            if (msg.extensionHeaders != entries) {
                messages[index] = msg.copy(extensionHeaders = entries)
                changed = true
            }
        }

        if (changed) {
            _uiState.update { it.copy(messages = messages) }
        }
        return changed
    }

    // ── Header long-press ─────────────────────────────────────────────────

    /**
     * Priority: inline buttons → context menu → HEADER_LONG_PRESSED event.
     * Returns "buttons" or "menu" so ChatBubble knows which UX to show,
     * or null if neither is registered (fallback to event).
     */
    fun onHeaderLongPressed(messageIndex: Int, extensionId: String) {
        if (extensionId.isBlank()) return
        val state = _uiState.value

        // Priority 1: toggle inline buttons
        if (state.headerButtons.containsKey(extensionId)) {
            val key = messageIndex to extensionId
            val current = state.visibleHeaderButtons
            _uiState.update {
                it.copy(visibleHeaderButtons = if (key in current) current - key else current + key)
            }
            return
        }

        // Priority 2: context menu — handled in ChatBubble via headerMenus state
        if (state.headerMenus.containsKey(extensionId)) {
            // Menu popup is managed by ChatBubble's local state.
            // Returning here means we don't fire the event.
            return
        }

        // Priority 3: fallback — dispatch HEADER_LONG_PRESSED event
        val safeId = extensionId.replace("\"", "\\\"")
        val jsonData = "{\"messageIndex\":$messageIndex,\"extensionId\":\"$safeId\"}"
        extensionManager.emitJson(ExtensionEvent.HEADER_LONG_PRESSED, jsonData)
    }

    /** Dispatch BUTTON_CLICKED when an inline header button or menu item is tapped. */
    fun onHeaderActionClicked(action: String, label: String) {
        val safeAction = action.replace("\"", "\\\"")
        val safeLabel = label.replace("\"", "\\\"")
        val jsonData = "{\"action\":\"$safeAction\",\"label\":\"$safeLabel\"}"
        extensionManager.emitJson(ExtensionEvent.BUTTON_CLICKED, jsonData)
    }

    // ── Edit dialog (JS extension) ─────────────────────────────────────────

    fun submitEditDialog(results: Map<String, String>) {
        val request = _uiState.value.editDialogRequest ?: return
        extensionManager.jsHost.completeEditDialog(request.callbackId, results)
    }

    fun cancelEditDialog() {
        extensionManager.jsHost.cancelEditDialog()
    }

    // ── Hidden generation (JS extension) ─────────────────────────────────

    private suspend fun doHiddenGenerate(prompt: String, callbackId: String) {
        try {
            val config = when (val r = localRepository.getApiConfiguration()) {
                is Result.Success -> r.data
                is Result.Error -> ApiConfiguration.DEFAULT
            }
            val preset = localRepository.getCurrentTextGenPreset()

            // Build a context-aware prompt: include character info and recent chat
            // history so the LLM has full scene context for hidden generation.
            val messages = _uiState.value.messages
            val character = _uiState.value.character
            val contextPrompt = buildString {
                if (character != null) {
                    append("Character: ").append(character.name).append("\n")
                    if (character.description.isNotBlank()) {
                        append("Description: ").append(character.description.take(1000)).append("\n")
                    }
                    if (character.personality.isNotBlank()) {
                        append("Personality: ").append(character.personality.take(500)).append("\n")
                    }
                    if (character.scenario.isNotBlank()) {
                        append("Scenario: ").append(character.scenario.take(500)).append("\n")
                    }
                    append("\n")
                }
                // Include recent messages for context (up to 20, 2000 chars each)
                val recent = if (messages.size > 20) messages.takeLast(20) else messages
                if (recent.isNotEmpty()) {
                    append("Recent conversation:\n")
                    for (msg in recent) {
                        val role = if (msg.isUser) _currentUserName else (character?.name ?: "Assistant")
                        val text = msg.rawContent ?: msg.content
                        append(role).append(": ").append(text.take(2000)).append("\n")
                    }
                    append("\n")
                }
                append(prompt)
            }

            // For text completion backends (KoboldAI), wrap the prompt with the
            // instruct template so the model knows it needs to generate a response.
            // Without this, the model sees a completed document and immediately
            // outputs EOS.  Chat completion backends handle this automatically.
            val finalPrompt = if (!config.usesChatCompletions) {
                val charFileName = character?.avatar ?: "${character?.name ?: "char"}.png"
                val instructTemplate = when (val r = localRepository.loadChatContext(
                    characterFileName = charFileName,
                    chatFileName = _uiState.value.currentChatFileName
                )) {
                    is Result.Success -> r.data.instructTemplate
                    is Result.Error -> null
                }
                if (instructTemplate != null) {
                    buildString {
                        // Wrap as: [input_sequence]prompt[input_suffix][output_sequence]
                        append(instructTemplate.inputSequence)
                        append(contextPrompt)
                        append(instructTemplate.inputSuffix)
                        append(instructTemplate.outputSequence)
                    }
                } else {
                    // No instruct template — add a generic response marker
                    contextPrompt + "\n\nResponse:\n"
                }
            } else {
                contextPrompt
            }

            var resultText = ""
            llmRepository.generate(finalPrompt, config, preset).collect { event ->
                when (event) {
                    is StreamEvent.Complete -> resultText = event.fullText
                    is StreamEvent.Error -> resultText = ""
                    is StreamEvent.Token -> { /* ignore */ }
                    is StreamEvent.ThinkingToken -> { /* ignore */ }
                }
            }
            extensionManager.jsHost.completeHiddenGenerate(callbackId, resultText)
        } catch (e: Exception) {
            extensionManager.jsHost.completeHiddenGenerate(callbackId, "")
        }
    }

    // ── Image generation (JS extension) ──────────────────────────────────

    private suspend fun doExtensionImageGenerate(prompt: String, optionsJson: String, callbackId: String) {
        try {
            val imageGenConfig = settingsDataStore.getImageGenConfig()

            // Parse optional overrides from the extension
            val options = try { org.json.JSONObject(optionsJson) } catch (_: Exception) { org.json.JSONObject() }
            val width = options.optInt("width", imageGenConfig.width)
            val height = options.optInt("height", imageGenConfig.height)
            val negativePrompt = options.optString("negativePrompt", imageGenConfig.negativePrompt)
            val seed = options.optInt("seed", imageGenConfig.seed)
            val sourceImageBase64 = options.optString("sourceImageBase64").ifEmpty { null }
            val denoisingStrength = options.optDouble("denoisingStrength", 0.55).toFloat()

            val params = ForgeGenerationParams(
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = imageGenConfig.steps,
                cfgScale = imageGenConfig.cfgScale,
                sampler = imageGenConfig.sampler,
                seed = seed,
                sourceImageBase64 = sourceImageBase64,
                denoisingStrength = denoisingStrength
            )

            var resultBase64 = ""
            imageGenRepository.generateImageWithProgress(params).collect { state ->
                if (state is GenerationState.Complete) {
                    resultBase64 = state.imageBase64
                }
            }
            extensionManager.jsHost.completeImageGenerate(callbackId, resultBase64)
        } catch (e: Exception) {
            extensionManager.jsHost.completeImageGenerate(callbackId, "")
        }
    }

    // ── Model get/set (JS extension) ─────────────────────────────────────

    private suspend fun doExtensionGetModels(callbackId: String) {
        try {
            // Try active ImageGen backend first; fall back to ForgeRepository if empty/failed
            val models: List<String> = run {
                val r = imageGenRepository.getModels()
                if (r is com.pockettavern.app.domain.model.Result.Success && r.data.isNotEmpty()) return@run r.data
                val fr = forgeRepository.getModels()
                if (fr is com.pockettavern.app.domain.model.Result.Success) fr.data else emptyList()
            }
            val json = "[" + models.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" } + "]"
            extensionManager.jsHost.completeGetModels(callbackId, json)
        } catch (e: Exception) {
            extensionManager.jsHost.completeGetModels(callbackId, "[]")
        }
    }

    private suspend fun doExtensionSetModel(modelName: String, callbackId: String) {
        try {
            // Try active ImageGen backend first; fall back to ForgeRepository
            val r = imageGenRepository.setModel(modelName)
            val success = if (r is com.pockettavern.app.domain.model.Result.Success) {
                true
            } else {
                forgeRepository.setCurrentModel(modelName) is com.pockettavern.app.domain.model.Result.Success
            }
            extensionManager.jsHost.completeSetModel(callbackId, success)
        } catch (e: Exception) {
            extensionManager.jsHost.completeSetModel(callbackId, false)
        }
    }

    // ── Insert message (JS extension) ────────────────────────────────────

    private suspend fun doExtensionInsertMessage(content: String, optionsJson: String) {
        val options = try { org.json.JSONObject(optionsJson) } catch (_: Exception) { org.json.JSONObject() }
        val type = options.optString("type", "narrator")
        val imageBase64 = options.optString("imageBase64", "")

        when (type) {
            "image" -> {
                if (imageBase64.isBlank()) return
                // Save image to file, then insert a narrator message with imagePath
                val imagePath = withContext(Dispatchers.IO) {
                    saveExtensionImage(imageBase64)
                }
                if (imagePath != null) {
                    val imageMessage = ChatMessage(
                        content = content.ifBlank { "" },
                        isUser = false,
                        isNarrator = true,
                        imagePath = imagePath
                    )
                    _uiState.update { it.copy(messages = it.messages + imageMessage) }
                    saveCurrentChat()
                }
            }
            else -> {
                // Narrator text message
                if (content.isNotBlank()) {
                    insertNarratorMessage(content)
                }
            }
        }
    }

    /** Save a base64 image to the chat_images directory and return the relative path. */
    private fun saveExtensionImage(base64: String): String? {
        return try {
            val imageBytes = Base64.decode(base64, Base64.DEFAULT)
            val chatFileName = _uiState.value.currentChatFileName
            val dir = File(context.filesDir, "chat_images/$chatFileName").also { it.mkdirs() }
            val filename = "${System.currentTimeMillis()}.png"
            val file = File(dir, filename)
            file.writeBytes(imageBytes)
            "chat_images/$chatFileName/$filename"
        } catch (e: Exception) {
            null
        }
    }

    fun updateAuthorsNote(
        content: String,
        depth: Int = 4,
        interval: Int = 1,
        position: Int = 0,
        role: Int = 0
    ) {
        val messages = _uiState.value.messages.toMutableList()
        if (messages.isEmpty()) return

        val firstMessage = messages[0]
        val updatedMetadata = ChatMessageMetadata(
            notePrompt = content.ifBlank { null },
            noteInterval = interval,
            noteDepth = depth,
            notePosition = position,
            noteRole = role
        )
        messages[0] = firstMessage.copy(chatMetadata = updatedMetadata)
        _uiState.update { it.copy(messages = messages) }
        viewModelScope.launch { saveCurrentChat() }
    }

    fun getAuthorsNote(): ChatMessageMetadata? =
        _uiState.value.messages.firstOrNull()?.chatMetadata

    fun showDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun deleteCurrentChat() {
        val character = _uiState.value.character ?: return
        val fileName = _uiState.value.currentChatFileName ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteDialog = false, isLoading = true) }
            if (!pureChatMode) {
                withContext(Dispatchers.IO) {
                    extensionManager.varsDeleteForChat(storageKey(), fileName)
                }
            }
            when (localRepository.deleteChat(storageKey(), fileName)) {
                is Result.Success -> {
                    when (val chatsResult = localRepository.getCharacterChats(storageKey())) {
                        is Result.Success -> {
                            val chats = chatsResult.data
                            _uiState.update { it.copy(availableChats = chats) }
                            if (chats.isNotEmpty()) {
                                loadExistingChat(character, chats.first().fileName)
                            } else {
                                createNewChat()
                            }
                        }
                        is Result.Error -> createNewChat()
                    }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = "删除聊天失败") }
                }
            }
        }
    }

    fun deleteCharacter() {
        viewModelScope.launch {
            when (localRepository.deleteCharacter(currentAvatarUrl)) {
                is Result.Success -> { /* navigation handles going back */ }
                is Result.Error -> {
                    _uiState.update { it.copy(error = "删除角色失败") }
                }
            }
        }
    }

    // ========== Chat Rename ==========

    fun showRenameChatDialog(fileName: String) {
        _uiState.update { it.copy(showRenameChatDialog = true, renameChatTargetFileName = fileName, renameChatInput = "") }
    }

    fun dismissRenameChatDialog() {
        _uiState.update { it.copy(showRenameChatDialog = false, renameChatTargetFileName = null, renameChatInput = "") }
    }

    fun updateRenameChatInput(value: String) {
        _uiState.update { it.copy(renameChatInput = value) }
    }

    fun confirmRenameChat() {
        val character = _uiState.value.character ?: return
        val oldFileName = _uiState.value.renameChatTargetFileName ?: return
        val newName = _uiState.value.renameChatInput.trim()
        if (newName.isBlank()) return
        viewModelScope.launch {
            when (val result = localRepository.renameChat(storageKey(), oldFileName, newName)) {
                is Result.Success -> {
                    val newFileName = result.data
                    val wasCurrent = _uiState.value.currentChatFileName == oldFileName
                    dismissRenameChatDialog()
                    refreshChatsList()
                    if (wasCurrent) _uiState.update { it.copy(currentChatFileName = newFileName) }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(error = "重命名聊天失败") }
                    dismissRenameChatDialog()
                }
            }
        }
    }

    // ========== Fork / Branch Chat ==========

    fun forkChatAtMessage(messageIndex: Int) {
        val character = _uiState.value.character ?: return
        val messages = _uiState.value.messages.take(messageIndex + 1)
        viewModelScope.launch {
            _uiState.update { it.copy(showMessageActions = false, selectedMessageIndex = null, isLoading = true) }
            when (val result = localRepository.forkChat(storageKey(), messages)) {
                is Result.Success -> {
                    val newFileName = result.data
                    refreshChatsList()
                    loadExistingChat(character, newFileName)
                }
                is Result.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = "创建聊天分支失败") }
                }
            }
        }
    }

    fun exportCurrentChat(uri: android.net.Uri) {
        val charName = _uiState.value.character?.name ?: return
        val chatFileName = _uiState.value.currentChatFileName ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sanitized = charName.replace(Regex("[^a-zA-Z0-9_\\-. ]"), "_").trim().take(64)
                val chatFile = java.io.File(context.filesDir, "chats/$sanitized/$chatFileName")
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    chatFile.inputStream().use { it.copyTo(out) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Export failed: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
        updateAvatarState()
    }

    fun toggleReasoningBubbles() {
        _uiState.update { it.copy(showReasoningBubbles = !it.showReasoningBubbles) }
    }

    // ========== Message Search ==========

    fun toggleSearch() {
        val searching = _uiState.value.isSearching
        _uiState.update {
            it.copy(
                isSearching = !searching,
                searchQuery = "",
                searchResults = emptyList(),
                currentSearchResultIndex = 0
            )
        }
    }

    fun updateSearchQuery(query: String) {
        val results = if (query.isBlank()) emptyList() else {
            _uiState.value.messages.indices.filter { i ->
                _uiState.value.messages[i].content.contains(query, ignoreCase = true)
            }
        }
        val idx = if (results.isNotEmpty()) results.size - 1 else 0
        _uiState.update {
            it.copy(searchQuery = query, searchResults = results, currentSearchResultIndex = idx)
        }
    }

    fun navigateSearchResult(delta: Int) {
        val results = _uiState.value.searchResults
        if (results.isEmpty()) return
        val current = _uiState.value.currentSearchResultIndex
        val newIdx = (current + delta + results.size) % results.size
        _uiState.update { it.copy(currentSearchResultIndex = newIdx) }
    }

    // ========== Context Usage ==========

    private fun updateContextEstimate() {
        val state = _uiState.value
        val character = state.character
        val messages = state.messages

        var chars = 0
        if (character != null) {
            chars += character.description.length + character.personality.length +
                     character.scenario.length + character.systemPrompt.length
        }
        chars += messages.sumOf { it.content.length }
        _uiState.update { it.copy(contextUsedTokens = chars / 4) }
    }

    // ========== Message Editing ==========

    fun startEditingMessage(index: Int) {
        val message = _uiState.value.messages.getOrNull(index) ?: return
        _uiState.update {
            it.copy(
                editingMessageIndex = index,
                editingMessageText = message.content,
                showMessageActions = false
            )
        }
    }

    fun updateEditingText(text: String) {
        _uiState.update { it.copy(editingMessageText = text) }
    }

    fun saveEditedMessage() {
        val index = _uiState.value.editingMessageIndex ?: return
        val newText = _uiState.value.editingMessageText
        val messages = _uiState.value.messages.toMutableList()
        if (index in messages.indices) {
            messages[index] = messages[index].copy(content = newText)
            _uiState.update {
                it.copy(messages = messages, editingMessageIndex = null, editingMessageText = "")
            }
            pushExtensionContext()
            viewModelScope.launch { saveCurrentChat() }
        }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessageIndex = null, editingMessageText = "") }
    }

    // ========== Continue Generation ==========

    fun continueGeneration() {
        val character = _uiState.value.character ?: return
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return

        _uiState.update { it.copy(isGenerating = true, streamingContent = "") }
        updateAvatarState()

        generationJob = viewModelScope.launch {
            // Full history as context, hidden continue prompt as the "user" turn
            doGenerate(messages, CONTINUE_PROMPT, pureChat = pureChatMode).collect { event ->
                when (event) {
                    is StreamEvent.Token -> {
                        _uiState.update { it.copy(streamingContent = event.accumulated) }
                    }
                    is StreamEvent.Complete -> {
                        val charName = _uiState.value.character?.name ?: ""
                        val extensionProcessed = if (pureChatMode) event.fullText else extensionManager.processOutput(event.fullText)
                        val processed = trimMultiTurn(extensionProcessed)
                            .replace("{{user}}", _currentUserName, ignoreCase = true)
                            .let { if (charName.isNotBlank()) it.replace("{{char}}", charName, ignoreCase = true) else it }
                        val newMessage = ChatMessage(content = processed, isUser = false)
                        _uiState.update {
                            it.copy(
                                messages = it.messages + newMessage,
                                isGenerating = false,
                                streamingContent = ""
                            )
                        }
                        updateAvatarState()
                        // Apply JS output filters
                        val displayContent = if (pureChatMode) processed else extensionManager.applyOutputFilters(processed)
                        if (displayContent != processed) {
                            val msgs = _uiState.value.messages.toMutableList()
                            val idx = msgs.indexOfLast { !it.isUser }
                            if (idx >= 0) {
                                msgs[idx] = msgs[idx].copy(content = displayContent, rawContent = processed)
                                _uiState.update { it.copy(messages = msgs) }
                            }
                        }
                        if (!pureChatMode) {
                            pushExtensionContext()
                            persistExtensionHeaders()
                            extensionManager.emit(ExtensionEvent.GENERATION_STOPPED)
                        }
                        generationJob = null
                        saveCurrentChat()
                        val estimatedTokens = extensionManager.tokenCounter.estimateTokens(processed)
                        if (!pureChatMode && autoContinueEnabled && autoContinueCount < 3 && estimatedTokens < autoContinueMinLength) {
                            autoContinueCount++
                            continueGeneration()
                        }
                    }
                    is StreamEvent.ThinkingToken -> {
                        _uiState.update { it.copy(streamingThinking = event.accumulatedThinking) }
                    }
                    is StreamEvent.Error -> {
                        _uiState.update {
                            it.copy(isGenerating = false, streamingContent = "", streamingThinking = "", error = event.message)
                        }
                        updateAvatarState()
                        if (!pureChatMode) extensionManager.emit(ExtensionEvent.GENERATION_STOPPED)
                        generationJob = null
                    }
                }
            }
        }
    }

    // Send a hidden user turn (e.g. OOC) — no user bubble, generates a new AI message
    private fun sendHiddenUserTurn(userTurn: String) {
        val character = _uiState.value.character ?: return
        val messages = _uiState.value.messages
        _uiState.update {
            it.copy(
                isGenerating = true,
                streamingContent = "",
                streamingThinking = ""
            )
        }
        updateAvatarState()
        // 复用主生成完成管线，使隐藏的自动操作反馈也能解析下一步 device_action。
        generateResponse(character, userTurn, messages, pureChat = pureChatMode)
    }

    // ========== Swipes (Alternate Responses) ==========

    fun swipeLeft(messageIndex: Int) {
        val swipes = _uiState.value.messageSwipes[messageIndex] ?: return
        val currentIndex = _uiState.value.currentSwipeIndex[messageIndex] ?: 0
        if (currentIndex > 0) applySwipe(messageIndex, currentIndex - 1, swipes)
    }

    fun swipeRight(messageIndex: Int) {
        val swipes = _uiState.value.messageSwipes[messageIndex] ?: return
        val currentIndex = _uiState.value.currentSwipeIndex[messageIndex] ?: 0
        if (currentIndex < swipes.size - 1) applySwipe(messageIndex, currentIndex + 1, swipes)
    }

    private fun applySwipe(messageIndex: Int, swipeIndex: Int, swipes: List<String>) {
        val messages = _uiState.value.messages.toMutableList()
        if (messageIndex in messages.indices) {
            messages[messageIndex] = messages[messageIndex].copy(content = swipes[swipeIndex])
            val newSwipeIndex = _uiState.value.currentSwipeIndex.toMutableMap()
            newSwipeIndex[messageIndex] = swipeIndex
            _uiState.update { it.copy(messages = messages, currentSwipeIndex = newSwipeIndex) }
            viewModelScope.launch { saveCurrentChat() }
        }
    }

    fun regenerateWithSwipe() {
        val character = _uiState.value.character ?: return
        val messages = _uiState.value.messages

        val lastAssistantIndex = messages.indexOfLast { !it.isUser }
        if (lastAssistantIndex == -1) return

        val currentMessage = messages[lastAssistantIndex]
        val existingSwipes = _uiState.value.messageSwipes[lastAssistantIndex]?.toMutableList()
            ?: mutableListOf(currentMessage.content)
        if (existingSwipes.isEmpty() || existingSwipes.last() != currentMessage.content) {
            existingSwipes.add(currentMessage.content)
        }

        val userMessageIndex = (lastAssistantIndex - 1 downTo 0).firstOrNull { messages[it].isUser }
            ?: return

        val userMessage = messages[userMessageIndex].content
        val history = messages.subList(0, userMessageIndex).toList()

        _uiState.update {
            it.copy(
                messages = messages.subList(0, lastAssistantIndex),
                isGenerating = true,
                streamingContent = ""
            )
        }
        updateAvatarState()

        generationJob = viewModelScope.launch {
            doGenerate(history, userMessage, pureChat = pureChatMode).collect { event ->
                when (event) {
                    is StreamEvent.Token -> {
                        _uiState.update { it.copy(streamingContent = event.accumulated) }
                    }
                    is StreamEvent.Complete -> {
                        val charName = _uiState.value.character?.name ?: ""
                        val extensionProcessed = if (pureChatMode) event.fullText else extensionManager.processOutput(event.fullText)
                        val processedWithToolTag = trimMultiTurn(extensionProcessed)
                            .replace("{{user}}", _currentUserName, ignoreCase = true)
                            .let { if (charName.isNotBlank()) it.replace("{{char}}", charName, ignoreCase = true) else it }
                        val parsedDeviceAction = DeviceActionParser.parse(processedWithToolTag)
                        val newContent = parsedDeviceAction.visibleText.ifBlank {
                            when {
                                parsedDeviceAction.appName != null -> "我可以帮你打开${parsedDeviceAction.appName}，请在确认窗口中选择是否执行。"
                                parsedDeviceAction.readScreenRequested -> "我可以读取当前页面公开的文字，请在确认窗口中选择是否发送给我。"
                                parsedDeviceAction.localTool != null -> "我可以帮你执行这个手机操作，请在确认窗口中选择是否执行。"
                                else -> processedWithToolTag
                            }
                        }
                        val assistantMessage = ChatMessage(content = newContent, isUser = false)
                        existingSwipes.add(newContent)

                        val newSwipes = _uiState.value.messageSwipes.toMutableMap()
                        newSwipes[lastAssistantIndex] = existingSwipes

                        val newSwipeIndex = _uiState.value.currentSwipeIndex.toMutableMap()
                        newSwipeIndex[lastAssistantIndex] = existingSwipes.size - 1

                        _uiState.update {
                            it.copy(
                                messages = it.messages + assistantMessage,
                                isGenerating = false,
                                streamingContent = "",
                                messageSwipes = newSwipes,
                                currentSwipeIndex = newSwipeIndex,
                                pendingDeviceAction = parsedDeviceAction.appName
                                    ?.let { name -> PendingDeviceAction(name, requestedBy = "重新生成") },
                                pendingScreenRead = parsedDeviceAction.readScreenRequested,
                                pendingLocalTool = parsedDeviceAction.localTool?.let { tool ->
                                    LocalToolPlan(
                                        action = tool,
                                        description = describeLocalTool(tool),
                                        needsConfirmation = true
                                    )
                                },
                                pendingPermissionPrompt = detectPermissionPrompt(newContent)
                            )
                        }
                        generationJob = null
                        saveCurrentChat()
                    }
                    is StreamEvent.ThinkingToken -> {}
                    is StreamEvent.Error -> {
                        _uiState.update {
                            it.copy(
                                messages = messages,
                                isGenerating = false,
                                streamingContent = "",
                                error = event.message
                            )
                        }
                        updateAvatarState()
                        generationJob = null
                    }
                }
            }
        }
    }

    fun getSwipeInfo(messageIndex: Int): Pair<Int, Int>? {
        val swipes = _uiState.value.messageSwipes[messageIndex] ?: return null
        val currentIndex = _uiState.value.currentSwipeIndex[messageIndex] ?: 0
        return currentIndex + 1 to swipes.size
    }

    /**
     * Strips any multi-turn continuation the model generated past the character's first response.
     * Models sometimes write "User: ..." or "PersonaName: ..." after their response, poisoning
     * chat history. We cut at the first occurrence of a user-role marker on its own line.
     */
    private fun trimMultiTurn(text: String): String {
        val personaName = _currentUserName.trim()
        val extras = if (personaName.isNotBlank() && personaName != "User") {
            "|${Regex.escape(personaName)}"
        } else ""
        val stopPattern = Regex("""\n\s*(User|You|Human$extras)\s*:""")
        val match = stopPattern.find(text) ?: return text
        return text.substring(0, match.range.first).trimEnd()
    }

    // Extract the last <img src=(name)> sprite tag from message text
    fun getSpriteFile(spriteName: String): java.io.File? {
        val charName = _uiState.value.character?.name ?: return null
        return spriteStorage.getFile(charName, spriteName)
    }
}
