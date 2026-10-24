package com.hark.shiguang

/**
 * 用户 2026-10-24 反馈「观看视频也有卡顿」（WebDAV 影音）。影视播放器的缓冲策略（纯函数，见 PlayerBufferTest）。
 *
 * 旧值 20 s / 60 s / 起播 1.2 s / 卡顿后 2.5 s：卡顿后只攒 2.5 s 就继续播，网速稍低于码率（WebDAV 走外网、
 * 高码率原盘）时会「播两秒、转一圈」反复卡；字节上限用 media3 默认（视频约 125 MB + 音频 12.5 MB，按选中轨道相加），
 * 这块内存在 Java 堆里，不看手机堆大小。推测是卡顿和高码率片快进时内存吃紧的原因之一。
 *
 * 新值参考 media3 默认（起播 2.5 s / 卡顿后 5 s）和同类网盘播放器：
 * - 至少缓冲 30 s、最多 90 s：码率低的片子能多攒一段，网速波动时不卡。
 * - 起播 / 跳转后 1.5 s 就开始播（跳转后不会更慢）；中途卡住后攒满 4 s 再播，避免反复卡。
 * - 字节上限 = 应用最大堆的 1/4，夹在 32～160 MB：大堆手机和以前差不多，小堆手机不会因为缓冲把内存吃光。
 *   高码率（如 80 Mbps 原盘）实际能攒的秒数由字节上限决定，不保证 30 s。
 */
object PlayerBuffer {
    data class Params(val minMs: Int, val maxMs: Int, val playMs: Int, val rebufferMs: Int, val targetBytes: Int)

    const val MIN_MS = 30_000
    const val MAX_MS = 90_000
    const val PLAY_MS = 1_500
    const val REBUFFER_MS = 4_000
    const val MIN_BYTES = 32 * 1024 * 1024
    const val MAX_BYTES = 160 * 1024 * 1024

    /** [maxHeapBytes] = Runtime.maxMemory()（manifest 开了 largeHeap）。 */
    fun forHeap(maxHeapBytes: Long): Params {
        val bytes = (maxHeapBytes / 4).coerceIn(MIN_BYTES.toLong(), MAX_BYTES.toLong()).toInt()
        return Params(MIN_MS, MAX_MS, PLAY_MS, REBUFFER_MS, bytes)
    }

    /** 码率 [bitsPerSecond] 下 [p] 的字节上限大约能缓冲多少秒（日志用；码率未知为 -1）。 */
    fun secondsFor(p: Params, bitsPerSecond: Long): Int =
        if (bitsPerSecond <= 0) -1 else minOf(p.maxMs / 1000L, p.targetBytes * 8L / bitsPerSecond).toInt()
}
