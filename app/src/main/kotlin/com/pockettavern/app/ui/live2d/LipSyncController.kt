package com.pockettavern.app.ui.live2d

/**
 * 口型同步控制器：把真实音频 RMS（0..1）做平滑，
 * 输出稳定的嘴巴开合值，避免抖动。播放结束后必须调用 [reset] 归零。
 */
class LipSyncController {

    private var smoothed = 0f
    private var enabled = true

    /** 处理一帧原始电平（0..1，<=0 表示静音），返回平滑后的目标值。 */
    fun process(rawLevel: Float): Float {
        if (!enabled) return 0f
        val raw = rawLevel.coerceIn(0f, 1f)
        val target = if (raw < NOISE_FLOOR) 0f else raw
        val attack = if (target > smoothed) ATTACK else RELEASE
        smoothed += (target - smoothed) * attack
        if (smoothed < NOISE_FLOOR) smoothed = 0f
        return smoothed
    }

    /** 播放开始前调用。 */
    fun begin() {
        enabled = true
        smoothed = 0f
    }

    /** 播放结束 / 打断 / 停止后调用，立即归零。 */
    fun reset() {
        smoothed = 0f
        enabled = false
    }

    companion object {
        private const val NOISE_FLOOR = 0.012f
        private const val ATTACK = 0.55f
        private const val RELEASE = 0.22f
    }
}
