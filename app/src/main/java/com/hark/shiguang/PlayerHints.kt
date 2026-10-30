package com.hark.shiguang

/**
 * 迭代第 1 轮 #51 / #52：影视播放器的两条提示文字（纯函数，见 PlayerHintsTest）。
 * - 续播提示：从上次位置接着播时，顶部提示「已从 32:10 继续播放」，几秒后自动消失。
 * - 手势教学：第一次进播放器时显示一次，说明横滑 / 左右边上下滑 / 双击 / 长按。
 */
object PlayerHints {
    /** 迭代 #74 播放状态浮窗文字：分辨率 / 帧率 / 视频码率 / 已缓冲秒数；拿不到的项直接省略。 */
    fun stats(w: Int, h: Int, fps: Float, bitrate: Int, bufferedMs: Long): String {
        val parts = ArrayList<String>()
        if (w > 0 && h > 0) parts += "${w}×${h}"
        if (fps > 0f) parts += (if (Math.abs(fps - Math.round(fps)) < 0.005f) "%d fps".format(Math.round(fps)) else "%.2f fps".format(fps))
        if (bitrate > 0) parts += if (bitrate >= 1_000_000) "%.1f Mbps".format(bitrate / 1_000_000.0) else "${bitrate / 1000} kbps"
        parts += "缓冲 ${(bufferedMs.coerceAtLeast(0) / 1000)} 秒"
        return parts.joinToString(" · ")
    }

    /** 迭代 #61 画面比例：0 适应（默认，留黑边）、1 铺满裁切、2 拉伸；每点一次切到下一档。 */
    fun nextFit(cur: Int): Int = (cur.coerceIn(0, 2) + 1) % 3
    fun fitLabel(mode: Int): String = when (mode) { 1 -> "铺满（裁切）"; 2 -> "拉伸"; else -> "适应" }

    /** 只有真的是接着看（> 5 秒，和起播逻辑一致）才提示。 */
    const val RESUME_MIN_MS = 5_000L

    /** 位置的 m:ss / h:mm:ss 写法。 */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** 用户口述 2：新播放器进度条两端的 00:01:42 / 00:45:26（始终带小时）。 */
    fun longClock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) / 1000)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }

    /** 用户口述 2：底栏左边的倍速文字「1.0x」「1.25x」。 */
    fun speedLabel(s: Float): String = if (s * 10 == Math.round(s * 10).toFloat()) String.format(java.util.Locale.US, "%.1fx", s) else "${s}x"

    /** [posMs] 是起播位置；不是续播返回 null。 */
    fun resume(posMs: Long): String? = if (posMs > RESUME_MIN_MS) "已从 ${clock(posMs)} 继续播放" else null

    /** 第一次进播放器时的手势说明（每行一条）。 */
    val gestureLines = listOf(
        "左右滑动：快进 / 快退",
        "左边上下滑：调亮度　右边上下滑：调音量",
        "双击：暂停 / 播放",
        "长按：2 倍速（松手恢复）",
    )

    /** 迭代 #68 定时关闭：当前状态的文字（[minutes] > 0 = 倒计时分钟，[endOfEpisode] = 本集播完停）。 */
    fun sleepLabel(minutes: Int, endOfEpisode: Boolean): String = when {
        endOfEpisode -> "本集播完后"
        minutes > 0 -> "$minutes 分钟后"
        else -> "关"
    }

    /** 定时关闭可选的分钟数。 */
    val sleepChoices = listOf(15, 30, 60)
}
