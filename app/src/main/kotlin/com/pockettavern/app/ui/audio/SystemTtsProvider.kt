package com.pockettavern.app.ui.audio

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.pockettavern.app.util.DebugLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

class SystemTtsProvider(context: Context, enginePackage: String? = null) : TtsProvider {

    private val appContext = context
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentTempFile: File? = null
    private var ready = false
    private var speaking = false
    private val readyDeferred = CompletableDeferred<Boolean>()

    /** 单次朗读事件回调（每次 speak/speakFromFile 前设置）。 */
    var onUtteranceStart: (() -> Unit)? = null
    var onUtteranceEnd: (() -> Unit)? = null
    /** 文件播放路径下 MediaPlayer 创建回调（用于挂 Visualizer）。 */
    var onMediaPlayerCreated: ((MediaPlayer) -> Unit)? = null

    init {
        val engine = enginePackage
            ?: Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        DebugLogger.log("[SystemTTS] Using engine: $engine (explicit: ${enginePackage != null})")

        tts = TextToSpeech(context, { status ->
            ready = status == TextToSpeech.SUCCESS
            readyDeferred.complete(ready)
            if (ready) {
                val voiceCount = tts?.voices?.size ?: 0
                DebugLogger.log("[SystemTTS] Initialized, ${voiceCount} voices available")
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { speaking = true }
                    override fun onDone(utteranceId: String?) { speaking = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { speaking = false }
                    override fun onError(utteranceId: String?, errorCode: Int) { speaking = false }
                })
            } else {
                DebugLogger.log("[SystemTTS] Initialization failed with status: $status")
            }
        }, engine)
    }

    suspend fun waitForReady(): Boolean = readyDeferred.await()

    override suspend fun speak(text: String, voiceId: String?, speed: Float) {
        val engine = tts ?: return
        if (!ready && !readyDeferred.await()) return

        engine.setSpeechRate(speed)

        // Set voice if specified
        if (voiceId != null) {
            val voice = engine.voices?.find { it.name == voiceId }
            if (voice != null) engine.voice = voice
        }

        val utteranceId = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    speaking = true
                    onUtteranceStart?.invoke()
                }
                override fun onDone(id: String?) {
                    speaking = false
                    onUtteranceEnd?.invoke()
                    onUtteranceStart = null
                    onUtteranceEnd = null
                    if (cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    speaking = false
                    onUtteranceEnd?.invoke()
                    onUtteranceStart = null
                    onUtteranceEnd = null
                    if (cont.isActive) cont.resume(Unit)
                }
                override fun onError(id: String?, errorCode: Int) {
                    speaking = false
                    onUtteranceEnd?.invoke()
                    onUtteranceStart = null
                    onUtteranceEnd = null
                    if (cont.isActive) cont.resume(Unit)
                }
            })
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            cont.invokeOnCancellation { engine.stop(); speaking = false }
        }
    }

    /**
     * 文件合成 + MediaPlayer 播放：把 TTS 合成到临时 wav 再播放，
     * 从而能通过 MediaPlayer 的 audioSession 获取真实音频 RMS（口型同步）。
     * 合成引擎不支持时返回 false，由上层回退到 [speak]。
     */
    suspend fun speakFromFile(text: String, voiceId: String?, speed: Float): Boolean {
        val engine = tts ?: return false
        if (!ready && !readyDeferred.await()) return false

        engine.setSpeechRate(speed)
        if (voiceId != null) {
            engine.voices?.find { it.name == voiceId }?.let { engine.voice = it }
        }

        val utteranceId = UUID.randomUUID().toString()
        val tempFile = File(appContext.cacheDir, "tts_sys_${System.currentTimeMillis()}.wav")
        val synthDeferred = CompletableDeferred<Boolean>()

        suspendCancellableCoroutine<Unit> { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    if (synthDeferred.isActive) synthDeferred.complete(true)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    if (synthDeferred.isActive) synthDeferred.complete(false)
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (synthDeferred.isActive) synthDeferred.complete(false)
                }
            })
            val result = engine.synthesizeToFile(text, null, tempFile, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                if (synthDeferred.isActive) synthDeferred.complete(false)
            }
            cont.invokeOnCancellation { engine.stop() }
        }

        val ok = synthDeferred.await()
        if (!ok) {
            tempFile.delete()
            return false
        }

        return suspendCancellableCoroutine { cont ->
            val player = MediaPlayer()
            mediaPlayer = player
            try {
                player.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                player.setDataSource(tempFile.absolutePath)
                player.setOnPreparedListener {
                    speaking = true
                    onUtteranceStart?.invoke()
                    onMediaPlayerCreated?.invoke(player)
                    player.start()
                }
                player.setOnCompletionListener {
                    speaking = false
                    onUtteranceEnd?.invoke()
                    onUtteranceStart = null
                    onUtteranceEnd = null
                    releasePlayer(tempFile)
                    if (cont.isActive) cont.resume(true)
                }
                player.setOnErrorListener { _, what, extra ->
                    DebugLogger.log("[SystemTTS] MediaPlayer error: $what/$extra")
                    speaking = false
                    onUtteranceEnd?.invoke()
                    onUtteranceStart = null
                    onUtteranceEnd = null
                    releasePlayer(tempFile)
                    if (cont.isActive) cont.resume(false)
                    true
                }
                player.prepareAsync()
            } catch (e: Exception) {
                DebugLogger.log("[SystemTTS] File play failed: ${e.message}")
                speaking = false
                releasePlayer(tempFile)
                if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation {
                try { player.stop() } catch (_: Exception) {}
                releasePlayer(tempFile)
            }
        }
    }

    private fun releasePlayer(tempFile: File) {
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        if (currentTempFile == tempFile) currentTempFile = null
        tempFile.delete()
    }

    override fun stop() {
        tts?.stop()
        try {
            mediaPlayer?.apply { if (isPlaying) stop() }
        } catch (_: Exception) {}
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        currentTempFile?.delete()
        currentTempFile = null
        speaking = false
    }

    override suspend fun getVoices(): List<TtsVoice> {
        val engine = tts ?: return emptyList()
        if (!ready) return emptyList()
        return engine.voices?.map { voice ->
            TtsVoice(
                id = voice.name,
                name = voice.name,
                language = voice.locale.displayName
            )
        }?.sortedBy { it.language } ?: emptyList()
    }

    override fun isReady(): Boolean = ready

    override fun isSpeaking(): Boolean = speaking

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        speaking = false
    }
}
