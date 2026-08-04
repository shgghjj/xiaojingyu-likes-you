package com.pockettavern.app.ui.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.pockettavern.app.util.DebugLogger
import java.util.Locale

/**
 * 语音输入抽象。第一版用 Android 系统 SpeechRecognizer（按住说话），
 * 未来可换成 OpenAI / ElevenLabs 等云端识别，聊天层只依赖本接口。
 */
interface VoiceInputProvider {
    var onPartialText: ((String) -> Unit)?
    var onFinalText: ((String) -> Unit)?
    var onError: ((String) -> Unit)?

    fun isListening(): Boolean

    /** 开始一次识别（按键说话：按下调用，松开调用 [stopListening]）。 */
    fun startListening()

    /** 结束本次识别并取最终结果。 */
    fun stopListening()

    /** 取消本次识别，丢弃结果。 */
    fun cancel()

    /** 页面销毁时必须调用，释放系统资源。 */
    fun destroy()
}

/** 基于 Android SpeechRecognizer 的实现。 */
class SpeechRecognizerVoiceInputProvider(
    private val context: Context
) : VoiceInputProvider {

    override var onPartialText: ((String) -> Unit)? = null
    override var onFinalText: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var destroyed = false

    override fun isListening(): Boolean = listening

    override fun startListening() {
        if (destroyed || listening) return
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError?.invoke("缺少录音权限，请到 设置 → 语音 中授权")
            return
        }
        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    listening = false
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: return
                    if (text.isNotBlank()) onPartialText?.invoke(text)
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (text.isNotBlank()) onFinalText?.invoke(text)
                }
                override fun onError(error: Int) {
                    listening = false
                    onError?.invoke(describeError(error))
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            listening = true
            sr.startListening(intent)
        } catch (e: Exception) {
            listening = false
            DebugLogger.log("[VoiceInput] start failed: ${e.message}")
            onError?.invoke("语音识别启动失败：${e.message ?: "未知错误"}")
        }
    }

    override fun stopListening() {
        val sr = recognizer
        if (!listening || sr == null) return
        try {
            sr.stopListening()
        } catch (_: Exception) {
        }
        listening = false
    }

    override fun cancel() {
        val sr = recognizer
        try {
            sr?.cancel()
        } catch (_: Exception) {
        }
        listening = false
    }

    override fun destroy() {
        destroyed = true
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        listening = false
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "录音失败，请检查麦克风"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少录音权限"
        SpeechRecognizer.ERROR_NO_MATCH -> "没有听清，请再说一次"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用"
        SpeechRecognizer.ERROR_SERVER -> "语音识别服务异常"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有检测到说话"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别正忙，请稍后再试"
        else -> "语音识别失败（$code）"
    }
}
