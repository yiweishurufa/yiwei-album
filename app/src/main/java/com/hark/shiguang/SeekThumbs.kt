package com.hark.shiguang

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 优化 13（用户 2026-11-02）：拖进度条时显示那个时间点的画面。
 *
 * 一个后台线程、一个 MediaMetadataRetriever（打开一次，后续只取帧）。拖动时只处理最新的请求：
 * 新请求来了，排队中的旧请求直接丢掉；同一个 [BUCKET_MS] 区间的帧缓存起来（最多 [MAX_CACHE] 张，160 px 宽）。
 * 网络文件取帧要读不少数据，所以：
 *  - 只有本地文件和 http(s) 原文件（WebDAV / 飞牛原画）才取帧；服务器转码的 HLS 不取（只显示时间）；
 *  - 打开失败或连续两次取帧超过 [SLOW_MS] 就放弃，这次播放只显示时间（[available] = false）。
 */
class SeekThumbs(private val url: String, private val headers: Map<String, String>) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "yw-seekthumb").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val latest = AtomicLong(-1)
    private val cache = object : LinkedHashMap<Long, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > MAX_CACHE
    }
    private var mmr: MediaMetadataRetriever? = null
    private var opened = false
    private var slow = 0
    @Volatile var available = url.startsWith("http", true) || url.startsWith("file:", true) || url.startsWith("/")
        private set

    fun cached(posMs: Long): Bitmap? = synchronized(cache) { cache[bucket(posMs)] }

    /** Asks for the frame near [posMs]; [onFrame] runs on the worker thread (post it to the UI yourself). */
    fun request(posMs: Long, onFrame: (Long, Bitmap) -> Unit) {
        if (!available) return
        val b = bucket(posMs)
        cached(b)?.let { onFrame(b, it); return }
        latest.set(b)
        exec.execute {
            if (latest.get() != b || !available) return@execute   // a newer drag position came in
            val bm = frame(b) ?: return@execute
            synchronized(cache) { cache[b] = bm }
            onFrame(b, bm)
        }
    }

    private fun frame(at: Long): Bitmap? {
        val r = open() ?: return null
        val t0 = System.currentTimeMillis()
        val bm = runCatching {
            if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, WIDTH * 9 / 16)
            else r.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { scale(it) }
        }.getOrNull()
        val ms = System.currentTimeMillis() - t0
        if (ms > SLOW_MS) { slow++; Diag.log("PLAYER", "seek thumb slow ${ms}ms (#$slow)") }
        if (slow >= 2 || bm == null && ms > SLOW_MS) { available = false; Diag.log("PLAYER", "seek thumbs off for this playback (time only)") }
        return bm
    }

    private fun open(): MediaMetadataRetriever? {
        if (opened) return mmr
        opened = true
        mmr = runCatching {
            MediaMetadataRetriever().apply { if (url.startsWith("http", true)) setDataSource(url, headers) else setDataSource(url.removePrefix("file://")) }
        }.onFailure { available = false; Diag.log("PLAYER", "seek thumbs unavailable: ${it.javaClass.simpleName}") }.getOrNull()
        return mmr
    }

    private fun scale(b: Bitmap): Bitmap {
        if (b.width <= WIDTH) return b
        val h = (b.height * WIDTH.toFloat() / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, WIDTH, h, true).also { if (it !== b) b.recycle() }
    }

    fun release() {
        available = false
        exec.execute { runCatching { mmr?.release() }; mmr = null; synchronized(cache) { cache.clear() } }
        exec.shutdown()
    }

    companion object {
        const val BUCKET_MS = 10_000L
        const val MAX_CACHE = 40
        const val WIDTH = 240
        const val SLOW_MS = 4_000L
        fun bucket(posMs: Long): Long = (posMs.coerceAtLeast(0) / BUCKET_MS) * BUCKET_MS
    }
}
