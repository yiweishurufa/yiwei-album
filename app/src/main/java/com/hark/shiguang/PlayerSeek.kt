package com.hark.shiguang

/**
 * 用户 2026-10-24 反馈「一直快进的时候会闪退，观看也卡顿」（WebDAV 影音）。
 *
 * 连点快进 / 快退按钮、反复横滑、拖进度条时，每一下都直接 seekTo：播放线程每次都要清空解码器（硬解 flush）、
 * 取消当前下载、给 WebDAV 重新发一个带 Range 的 HTTP 请求。一秒点五六下就是五六轮 flush + 断开重连。
 * 推测这是快进时闪退（个别机型的硬解在频繁 flush 时崩溃）和快进后长时间转圈的原因。
 *
 * 这里把一串跳转合并成一次：空闲时第一下立刻跳（单击不会变慢）；之后 [quietMs] 内的连续请求只记最后的目标，
 * 停手 [quietMs] 后跳一次；一直不停手时最多每 [maxHoldMs] 跳一次，画面不会长时间不动。
 * 纯逻辑、时间由调用方传入（见 PlayerSeekTest）；真正调用播放器的是 ui/PlayerSeekGate。
 *
 * 用户 2026-10-24 要求继续优化：暂停时连点快进，进度条要等停手 0.35 秒才动（PlayerView 暂停时只在播放器真正跳转后
 * 才刷新进度）。暂停时解码器不在连续出帧，没有「边播边 flush」的风险，所以暂停时 [request] 带 immediate = true：
 * 每一下都立刻跳（顺带把之前没执行的目标一起冲掉），画面和进度条马上到位。
 */
class SeekCoalescer(val quietMs: Long = QUIET_MS, val maxHoldMs: Long = MAX_HOLD_MS) {
    /** 一次真正要执行的跳转：第 [index] 个媒体项的 [positionMs]，合并了 [merged] 次请求。 */
    data class Seek(val index: Int, val positionMs: Long, val merged: Int)

    private var pendingIndex = -1
    private var pendingPos = 0L
    private var pendingCount = 0
    private var firstAt = 0L
    private var lastReqAt = 0L
    private var lastAppliedAt = Long.MIN_VALUE / 2

    val hasPending: Boolean get() = pendingCount > 0

    /** 还没执行的目标位置（同一媒体项时），用于让进度条 / 快进按钮立刻按新位置显示、继续累加。 */
    fun pendingPosition(index: Int): Long? = if (hasPending && pendingIndex == index) pendingPos else null

    /**
     * 新的跳转请求。返回非 null = 现在就跳（空闲时的第一下，或 [immediate] 暂停中）；null = 已记下，等 [due] 再跳。
     */
    fun request(index: Int, positionMs: Long, now: Long, immediate: Boolean = false): Seek? {
        val pos = positionMs.coerceAtLeast(0L)
        if (immediate) {
            val merged = if (hasPending && pendingIndex == index) pendingCount + 1 else 1
            clear(); lastAppliedAt = now
            return Seek(index, pos, merged)
        }
        if (!hasPending && now - lastAppliedAt >= quietMs) {
            lastAppliedAt = now
            return Seek(index, pos, 1)
        }
        if (!hasPending || pendingIndex != index) { firstAt = now; pendingCount = 0 }
        pendingIndex = index; pendingPos = pos; pendingCount++
        lastReqAt = now
        return null
    }

    /** 下一次该检查 [due] 的时间（没有待跳转时为 null）。 */
    fun nextCheckAt(): Long? = if (hasPending) minOf(lastReqAt + quietMs, firstAt + maxHoldMs) else null

    /** 到点了就取出合并后的跳转；[currentIndex] 已经变了（切集 / 自动连播）时丢弃，不把上一集的位置用到下一集。 */
    fun due(now: Long, currentIndex: Int): Seek? {
        if (!hasPending) return null
        if (pendingIndex != currentIndex) { clear(); return null }
        if (now - lastReqAt < quietMs && now - firstAt < maxHoldMs) return null
        val s = Seek(pendingIndex, pendingPos, pendingCount)
        clear(); lastAppliedAt = now
        return s
    }

    /** 立刻取出待跳转（例如松开进度条后马上按了下一集之前），没有则 null。 */
    fun flush(now: Long, currentIndex: Int): Seek? {
        if (!hasPending) return null
        if (pendingIndex != currentIndex) { clear(); return null }
        val s = Seek(pendingIndex, pendingPos, pendingCount)
        clear(); lastAppliedAt = now
        return s
    }

    fun clear() { pendingCount = 0; pendingIndex = -1 }

    companion object {
        const val QUIET_MS = 350L
        const val MAX_HOLD_MS = 1_200L

        /** 快进 / 快退按钮在 [baseMs]（已合并的目标或当前位置）上加减，夹在 0..时长（时长未知时只夹下限）。 */
        fun step(baseMs: Long, deltaMs: Long, durationMs: Long): Long {
            val t = (baseMs + deltaMs).coerceAtLeast(0L)
            return if (durationMs > 0) t.coerceAtMost(durationMs) else t
        }
    }
}
