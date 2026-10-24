package com.hark.shiguang.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.hark.shiguang.Diag
import com.hark.shiguang.PlayerBuffer
import java.io.IOException

/**
 * 用户 2026-10-24 反馈：WebDAV 影音快进闪退、观看卡顿。播放链路分段写 [PLAYER] 诊断日志，下次用户导出就能看出断在哪：
 * - 打开：缓冲参数、堆大小；解码器名（c2.android / OMX.google / ffmpeg = 软解，其余多为硬解）、视频格式和码率。
 * - 跳转：执行了几次（合并在 PlayerSeekGate 里另记）。
 * - 卡顿：播放中进入缓冲（不是跳转引起的）记为卡顿，恢复时写持续时间和当时的堆占用。
 * - 下载出错（会自动重试的也记，限频）、丢帧、系统内存吃紧（onTrimMemory）。
 * - 关闭：本次播放的卡顿次数 / 总时长、跳转次数、丢帧总数。
 * 崩溃栈本身由 Diag 的 UncaughtExceptionHandler（Java）和 CrashLog.recordSystemExit（原生崩溃 / ANR）记录。
 * 日志不写播放地址（Diag.mask 也会再打码）。
 */
@UnstableApi
class PlayerDiag(private val ctx: Context, private val buffer: PlayerBuffer.Params) : AnalyticsListener {
    private var stallAt = 0L
    private var seekingSince = 0L
    private var stalls = 0
    private var stallMs = 0L
    private var seeks = 0
    private var dropped = 0L
    private var loadErrors = 0
    private var lastLoadErrorLog = 0L
    private var lastSeekLog = 0L
    private var underruns = 0
    private var lastUnderrunLog = 0L

    private val trim = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Diag.log("PLAYER", "system memory low (trim $level) ${heap()}")
        }
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        @Deprecated("Deprecated in Java") override fun onLowMemory() { Diag.log("PLAYER", "onLowMemory ${heap()}") }
    }

    fun start(source: String, ext: String, sizeBytes: Long) {
        runCatching { ctx.registerComponentCallbacks(trim) }
        Diag.log("PLAYER", "open $source .$ext ${sizeBytes / (1024 * 1024)}MB buffer=${buffer.minMs / 1000}-${buffer.maxMs / 1000}s " +
            "start=${buffer.playMs}ms rebuffer=${buffer.rebufferMs}ms cap=${buffer.targetBytes / (1024 * 1024)}MB ${heap()}")
    }

    fun stop() {
        runCatching { ctx.unregisterComponentCallbacks(trim) }
        Diag.log("PLAYER", "close stalls=$stalls (${stallMs / 1000}s) seeks=$seeks dropped=$dropped underruns=$underruns loadErrors=$loadErrors ${heap()}")
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> stallAt = now
            Player.STATE_READY, Player.STATE_ENDED -> if (stallAt > 0) {
                val ms = now - stallAt
                val afterSeek = seekingSince > 0 && stallAt - seekingSince < 1_500
                if (afterSeek) { if (ms > 3_000) Diag.log("PLAYER", "seek resume slow ${ms}ms ${heap()}") }
                else if (ms > 300) { stalls++; stallMs += ms; Diag.log("PLAYER", "stall #$stalls ${ms}ms at ${eventTime.currentPlaybackPositionMs / 1000}s ${heap()}") }
                stallAt = 0L; seekingSince = 0L
            }
        }
    }

    override fun onPositionDiscontinuity(eventTime: AnalyticsListener.EventTime, oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        seeks++; seekingSince = SystemClock.elapsedRealtime()
        val now = seekingSince
        if (now - lastSeekLog > 2_000) { lastSeekLog = now; Diag.log("PLAYER", "seek #$seeks ${oldPosition.positionMs / 1000}s → ${newPosition.positionMs / 1000}s") }
    }

    override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
        loadErrors++
        val now = SystemClock.elapsedRealtime()
        if (now - lastLoadErrorLog < 3_000) return
        lastLoadErrorLog = now
        // the url is not logged (it can carry credentials); the class and message say what broke (timeout / reset / 4xx)
        Diag.log("PLAYER", "load error #$loadErrors ${error.javaClass.simpleName}: ${error.message?.take(120)} cause=${error.cause?.javaClass?.simpleName} canceled=$wasCanceled")
    }

    override fun onMediaItemTransition(eventTime: AnalyticsListener.EventTime, mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        val ext = mediaItem?.mediaId?.substringAfterLast('.', "")?.take(6).orEmpty()
        Diag.log("PLAYER", "item #${eventTime.windowIndex} .$ext reason=$reason ${heap()}")
    }

    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Diag.log("PLAYER", "video decoder $decoderName (${initializationDurationMs}ms)")
    }

    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
        Diag.log("PLAYER", "audio decoder $decoderName")
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        val br = listOf(format.averageBitrate, format.bitrate, format.peakBitrate).firstOrNull { it > 0 }?.toLong() ?: -1L
        Diag.log("PLAYER", "video ${format.sampleMimeType} ${format.codecs.orEmpty()} ${format.width}x${format.height} " +
            (if (br > 0) "${br / 1000}kbps ≈${PlayerBuffer.secondsFor(buffer, br)}s buffer" else "bitrate unknown"))
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        dropped += droppedFrames
        if (droppedFrames >= 30) Diag.log("PLAYER", "dropped $droppedFrames frames in ${elapsedMs}ms (decoder too slow?)")
    }

    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
        underruns++
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnderrunLog < 5_000) return
        lastUnderrunLog = now
        Diag.log("PLAYER", "audio underrun #$underruns (${elapsedSinceLastFeedMs}ms since last feed)")
    }

    private fun heap(): String {
        val r = Runtime.getRuntime()
        return "heap=${(r.totalMemory() - r.freeMemory()) / (1024 * 1024)}/${r.maxMemory() / (1024 * 1024)}MB"
    }
}
