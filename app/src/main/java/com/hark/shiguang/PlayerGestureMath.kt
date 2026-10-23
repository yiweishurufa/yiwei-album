package com.hark.shiguang

/**
 * 用户 2026-10-23 反馈：播放器手势对标腾讯视频 / 爱奇艺。这里是手势换算的纯函数（见 PlayerGestureMathTest）。
 */
object PlayerGestureMath {
    /** 横滑满屏宽对应多少毫秒：时长的 1/10，夹在 1~3 分钟之间；短视频不超过总时长。 */
    fun seekSpanMs(durationMs: Long): Long {
        if (durationMs <= 0) return 90_000L
        return minOf(durationMs, (durationMs / 10).coerceIn(60_000L, 180_000L))
    }

    /** 从 [startMs] 横滑 [dxPx]（右正）后的目标位置，夹在 0..时长。 */
    fun seekTarget(startMs: Long, dxPx: Float, widthPx: Int, durationMs: Long): Long {
        if (widthPx <= 0 || durationMs <= 0) return startMs.coerceAtLeast(0)
        val delta = (dxPx / widthPx * seekSpanMs(durationMs)).toLong()
        return (startMs + delta).coerceIn(0L, durationMs)
    }

    /** 竖滑调节：上滑 [upPx]（上正）后的 0..1 值；滑过 [heightPx] 的 80% 走完全程。 */
    fun level(start: Float, upPx: Float, heightPx: Int): Float {
        if (heightPx <= 0) return start.coerceIn(0f, 1f)
        return (start + upPx / (heightPx * 0.8f)).coerceIn(0f, 1f)
    }

    /** "12:00" / "1:46:44"。 */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    /** 拖动预览「+01:23 / 12:00」：跳多少 / 跳到哪。 */
    fun seekLabel(deltaMs: Long, targetMs: Long): String =
        (if (deltaMs < 0) "-" else "+") + clock(kotlin.math.abs(deltaMs)) + " / " + clock(targetMs)
}
