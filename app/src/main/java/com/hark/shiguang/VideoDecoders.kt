package com.hark.shiguang

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * 用户 2026-11-02 反馈「影音播放仍会黑屏」（1.3.7 的 71cdcb0 推测修复无效）。播放器画面黑的几种原因和这里的对策：
 *
 * 1. 手机根本没有能解这个画面的解码器（典型：杜比视界 Profile 5 在非杜比视界手机上，media3 1.3.1 只给 P8/P9 找 HEVC/AVC
 *    替代解码器）。media3 这时会把视频轨判成「不支持」、只选音轨 —— 有声无画，也不报错。
 *    → [selector]：杜比视界没有原生解码器时，交给 HEVC（再 AVC）解码器解基础层（P5 颜色会偏，但有画面），
 *      仍然选不上视频轨时 MoviePlayer 明确提示「只有声音」并给出编码。
 * 2. 解码器起来了但一帧都不出（个别机型的硬解遇到 10bit / 高码率 / 特殊 profile 时静默失败）。
 *    → MoviePlayer 里的首帧看门狗按 [FirstFrameWatch] 的步骤：先重新挂一次画面（Surface），再把这个解码器拉黑、
 *      重新准备（media3 会挑下一个解码器，通常是软解），最后仍无画面就明确提示。
 * 3. 解码器初始化直接失败：DefaultRenderersFactory.setEnableDecoderFallback(true) 已经会换下一个。
 *
 * [blocked] 只在一次播放里有效（播放器关闭时清空），不影响下一次、也不影响音乐播放。
 */
@UnstableApi
object VideoDecoders {
    /** Decoder names that produced no picture in the current playback. */
    val blocked: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    val selector = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        var list: List<MediaCodecInfo> = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
        if (list.isEmpty() && mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            list = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, requiresSecure, requiresTunneling) +
                MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H264, requiresSecure, requiresTunneling)
            if (list.isNotEmpty()) Diag.log("PLAYER", "no Dolby Vision decoder: decoding the base layer with ${list.first().name}")
        }
        if (blocked.isEmpty() || !MimeTypes.isVideo(mimeType)) return@MediaCodecSelector list
        val left = list.filter { it.name !in blocked }
        // every decoder blocked: keep the list (no picture is still better than dropping the video track silently)
        left.ifEmpty { list }
    }

    /** Short codec description for messages: "HEVC 10bit", "杜比视界 P5", "AVC"… */
    fun describe(mime: String?, codecs: String?, dvProfile: Int?, width: Int, height: Int): String {
        val c = codecs.orEmpty().lowercase()
        val name = when {
            dvProfile != null || mime == MimeTypes.VIDEO_DOLBY_VISION -> "杜比视界" + (dvProfile?.let { " P$it" } ?: "")
            mime == MimeTypes.VIDEO_H265 -> "HEVC" + if (c.startsWith("hvc1.2") || c.startsWith("hev1.2")) " 10bit" else ""
            mime == MimeTypes.VIDEO_H264 -> "AVC" + if (c.startsWith("avc1.6e")) " 10bit" else ""
            mime == MimeTypes.VIDEO_AV1 -> "AV1"
            mime == MimeTypes.VIDEO_VP9 -> "VP9"
            mime == MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            mime == MimeTypes.VIDEO_VC1 -> "VC-1"
            mime.isNullOrEmpty() -> "未知编码"
            else -> mime.removePrefix("video/").uppercase()
        }
        val res = when {
            width >= 3800 || height >= 2100 -> " 4K"
            width >= 1900 || height >= 1060 -> " 1080p"
            else -> ""
        }
        return name + res
    }
}

/**
 * 首帧看门狗（纯逻辑，时间由调用方传入，见 FirstFrameWatchTest）。
 * 只在「正在播放、选中了视频轨、还没出过一帧」时计时；暂停 / 缓冲不计。每一步对每个媒体项只做一次。
 */
class FirstFrameWatch(val reattachMs: Long = 4_000, val nextDecoderMs: Long = 9_000, val giveUpMs: Long = 16_000) {
    enum class Action { NONE, REATTACH, NEXT_DECODER, GIVE_UP }

    private var item = ""
    private var rendered = false
    private var waited = 0L
    private var lastTick = -1L
    private var step = 0

    fun reset(itemId: String) { item = itemId; rendered = false; waited = 0; lastTick = -1; step = 0 }
    fun onFirstFrame() { rendered = true }
    val hasPicture: Boolean get() = rendered

    /** Called about every 500 ms. [playingWithVideo] = isPlaying and a video track is selected. */
    fun tick(itemId: String, now: Long, playingWithVideo: Boolean): Action {
        if (itemId != item) reset(itemId)
        if (rendered || !playingWithVideo) { lastTick = -1; return Action.NONE }
        if (lastTick >= 0) waited += (now - lastTick).coerceIn(0, 2_000)
        lastTick = now
        return when {
            step == 0 && waited >= reattachMs -> { step = 1; Action.REATTACH }
            step == 1 && waited >= nextDecoderMs -> { step = 2; Action.NEXT_DECODER }
            step == 2 && waited >= giveUpMs -> { step = 3; Action.GIVE_UP }
            else -> Action.NONE
        }
    }
}
