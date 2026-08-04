package com.pockettavern.app.ui.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.audiofx.Visualizer
import com.pockettavern.app.data.local.SettingsDataStore
import com.pockettavern.app.data.local.TtsVoiceStorage
import com.pockettavern.app.util.DebugLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sin

/**
 * 语音输出抽象：开始/停止/打断 + 播放事件 + 真实音频电平（口型同步用）。
 * 第一版实现包装现有 OpenAI TTS 与系统 TTS；网络失败时自动降级系统 TTS。
 */
interface VoiceOutputProvider {
    val speakingState: StateFlow<Boolean>
    /** 实时音频电平 0..1（RMS 归一化），静音为 0。 */
    val levelState: StateFlow<Float>

    /** 朗读一段文字。voiceId/speed/volume/stylePrompt 传 null 时用全局设置。 */
    suspend fun speak(
        text: String,
        characterFile: String? = null,
        voiceId: String? = null,
        speed: Float? = null,
        volume: Float? = null,
        stylePrompt: String? = null
    ): Boolean

    fun stop()
    fun isSpeaking(): Boolean
}

/** 音频电平源：把 MediaPlayer 的真实 RMS 转成 0..1 回调。 */
private class MediaPlayerLevelSource : AutoCloseable {
    private var visualizer: Visualizer? = null
    private var listener: ((Float) -> Unit)? = null

    fun setListener(l: (Float) -> Unit) {
        listener = l
    }

    fun attach(player: MediaPlayer) {
        close()
        try {
            val v = Visualizer(player.audioSessionId)
            v.setCaptureSize(Visualizer.getCaptureSizeRange()[1] / 2)
            v.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer,
                        waveform: ByteArray,
                        samplingRate: Int
                    ) {
                        if (waveform.isEmpty()) return
                        var sum = 0f
                        for (b in waveform) sum += (b.toInt() and 0xFF) / 255f
                        listener?.invoke(sum / waveform.size)
                    }

                    override fun onFftDataCapture(
                        visualizer: Visualizer,
                        fft: ByteArray,
                        samplingRate: Int
                    ) {}
                },
                Visualizer.getMaxCaptureRate() / 2,
                false,
                true
            )
            v.enabled = true
            visualizer = v
        } catch (e: Exception) {
            DebugLogger.log("[VoiceOutput] Visualizer attach failed: ${e.message}")
        }
    }

    override fun close() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {}
        visualizer = null
    }
}

/** 系统 TTS 直读路径（无 MediaPlayer）时的合成电平包络。 */
private class SyntheticLevelSource(
    private val scope: CoroutineScope,
    private val listener: (Float) -> Unit
) {
    private var job: Job? = null
    private var startedAt = 0L

    fun start() {
        startedAt = System.currentTimeMillis()
        job = scope.launch {
            while (true) {
                val t = System.currentTimeMillis() - startedAt
                val envelope = 0.28 + 0.18 * abs(sin(t / 150.0)) + 0.14 * abs(sin(t / 53.0))
                listener(envelope.coerceIn(0.12, 0.62).toFloat())
                delay(40)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        listener(0f)
    }
}

@Singleton
class CompanionVoiceOutputProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val voiceStorage: TtsVoiceStorage,
    @Named("LLM") private val okHttpClient: OkHttpClient
) : VoiceOutputProvider {

    private val _speakingState = MutableStateFlow(false)
    override val speakingState: StateFlow<Boolean> = _speakingState.asStateFlow()

    private val _levelState = MutableStateFlow(0f)
    override val levelState: StateFlow<Float> = _levelState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var systemProvider: SystemTtsProvider? = null
    private var systemEngine: String? = null
    private var openAiProvider: OpenAiTtsProvider? = null

    private val levelSource = MediaPlayerLevelSource()
    private var syntheticSource: SyntheticLevelSource? = null

    private fun getSystemProvider(engine: String?): SystemTtsProvider {
        if (systemProvider == null || systemEngine != engine) {
            systemProvider?.shutdown()
            systemEngine = engine
            systemProvider = SystemTtsProvider(context, engine)
        }
        return systemProvider!!
    }

    private fun getOpenAiProvider(): OpenAiTtsProvider {
        if (openAiProvider == null) {
            openAiProvider = OpenAiTtsProvider(context, okHttpClient)
        }
        return openAiProvider!!
    }

    override suspend fun speak(
        text: String,
        characterFile: String?,
        voiceId: String?,
        speed: Float?,
        volume: Float?,
        stylePrompt: String?
    ): Boolean {
        val config = settingsDataStore.getTtsConfig()
        val filteredText = TtsTextFilter.filter(text, config.filterMode)
        if (filteredText.isBlank()) return false

        val providerName = if (characterFile != null) {
            voiceStorage.getProviderOverride(characterFile) ?: config.provider
        } else {
            config.provider
        }
        val resolvedVoice = voiceId ?: if (characterFile != null) {
            voiceStorage.getVoiceId(characterFile)
                ?: if (config.systemVoice.isNotEmpty()) config.systemVoice else null
        } else {
            config.systemVoice.takeIf { it.isNotEmpty() }
        }
        val resolvedSpeed = speed ?: config.speed
        val resolvedVolume = volume ?: 1f

        _speakingState.value = true
        _levelState.value = 0f
        try {
            val style = stylePrompt ?: config.stylePrompt
            if (providerName == "openai" && config.openAiUrl.isNotBlank()) {
                val ok = speakOpenAi(filteredText, resolvedVoice, resolvedSpeed, resolvedVolume, style, config)
                if (!ok && config.systemTtsFallback) {
                    DebugLogger.log("[VoiceOutput] OpenAI TTS 失败，降级系统 TTS")
                    speakSystem(filteredText, resolvedVoice, resolvedSpeed)
                }
            } else {
                speakSystem(filteredText, resolvedVoice, resolvedSpeed)
            }
        } catch (e: Exception) {
            DebugLogger.log("[VoiceOutput] speak failed: ${e.message}")
        } finally {
            syntheticSource?.stop()
            syntheticSource = null
            levelSource.close()
            _levelState.value = 0f
            _speakingState.value = false
        }
        return true
    }

    private suspend fun speakOpenAi(
        text: String,
        voiceId: String?,
        speed: Float,
        volume: Float,
        stylePrompt: String,
        config: com.pockettavern.app.domain.model.TtsConfig
    ): Boolean {
        val provider = getOpenAiProvider()
        provider.apiUrl = config.openAiUrl
        provider.apiKey = config.openAiKey
        provider.model = config.openAiModel
        provider.volume = volume
        provider.instructions = stylePrompt
        provider.onMediaPlayerCreated = { player ->
            levelSource.setListener { _levelState.value = it.coerceIn(0f, 1f) }
            levelSource.attach(player)
        }
        provider.onUtteranceStart = {
            _levelState.value = 0.02f
        }
        provider.onUtteranceEnd = {
            _levelState.value = 0f
        }
        val voice = voiceId ?: config.openAiVoice
        provider.speak(text, voice, speed)
        return !provider.lastNetworkFailure
    }

    private suspend fun speakSystem(text: String, voiceId: String?, speed: Float) {
        val config = settingsDataStore.getTtsConfig()
        val engine = config.systemEngine.takeIf { it.isNotEmpty() }
        val provider = getSystemProvider(engine)
        provider.onUtteranceStart = {
            _levelState.value = 0.02f
        }
        provider.onUtteranceEnd = {
            _levelState.value = 0f
        }
        provider.onMediaPlayerCreated = { player ->
            levelSource.setListener { _levelState.value = it.coerceIn(0f, 1f) }
            levelSource.attach(player)
        }
        val played = provider.speakFromFile(text, voiceId, speed)
        if (!played) {
            // 引擎不支持文件合成 → 直读路径（真实 RMS 不可用，用合成包络近似）
            syntheticSource?.stop()
            syntheticSource = SyntheticLevelSource(scope) { _levelState.value = it }
            provider.onMediaPlayerCreated = null
            provider.speak(text, voiceId, speed)
        }
    }

    override fun stop() {
        syntheticSource?.stop()
        syntheticSource = null
        levelSource.close()
        systemProvider?.stop()
        openAiProvider?.stop()
        _levelState.value = 0f
        _speakingState.value = false
    }

    override fun isSpeaking(): Boolean =
        _speakingState.value || systemProvider?.isSpeaking() == true || openAiProvider?.isSpeaking() == true
}
