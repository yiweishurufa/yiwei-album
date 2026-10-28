package com.hark.shiguang

import android.media.audiofx.LoudnessEnhancer

/**
 * 迭代第 1 轮 #60：影视播放器 ⋯ → 音量增强。片子录得太小声时，在系统音量之上再加 3 / 6 / 9 dB。
 * 默认「关」（行为不变），只在本次播放有效，不记住——避免下次放别的片子被吓到。
 */
object VolumeBoost {
    /** 各档增益（单位 mB = 0.01 dB）：0 关、1 = +3 dB、2 = +6 dB、3 = +9 dB。 */
    val gainsMb = intArrayOf(0, 300, 600, 900)

    fun next(level: Int): Int = (level.coerceIn(0, gainsMb.size - 1) + 1) % gainsMb.size
    fun gainMb(level: Int): Int = gainsMb[level.coerceIn(0, gainsMb.size - 1)]
    fun label(level: Int): String = when (level.coerceIn(0, gainsMb.size - 1)) { 0 -> "关"; else -> "+${gainMb(level) / 100} dB" }
}

/** 挂在播放器音频会话上的 LoudnessEnhancer；会话号变了（换音轨 / 重建解码器）要重新挂。设备不支持时静默不增强。 */
class VolumeBoostFx {
    private var fx: LoudnessEnhancer? = null
    private var session = 0

    /** 返回 false = 设备不支持或会话未就绪（调用方可提示「这台手机不支持」）。 */
    fun apply(sessionId: Int, level: Int): Boolean {
        val gain = VolumeBoost.gainMb(level)
        if (gain == 0 && fx == null) return true
        if (sessionId == 0) return gain == 0
        return runCatching {
            if (fx == null || session != sessionId) {
                release()
                fx = LoudnessEnhancer(sessionId); session = sessionId
            }
            fx?.setTargetGain(gain); fx?.enabled = gain > 0
            true
        }.getOrElse { Diag.log("PLAY", "volume boost failed: ${it.javaClass.simpleName}"); release(); false }
    }

    fun release() { runCatching { fx?.release() }; fx = null; session = 0 }
}
