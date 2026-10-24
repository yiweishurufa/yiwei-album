package com.hark.shiguang

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.C as MC
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.DefaultRenderersFactory
import org.json.JSONObject

/**
 * 1.0.9 playback helpers: quality badges (from the file name, refined by real track info after a play),
 * device HDR / Dolby Vision capability, and per-show playback preferences.
 */
object MediaBadges {
    /** Badges guessed from a file name, e.g. ["4K", "杜比视界", "HDR10", "TrueHD Atmos"]. */
    fun fromName(name: String): List<String> {
        val n = name.lowercase().replace('_', '.').replace(' ', '.')
        val out = ArrayList<String>()
        when {
            Regex("2160p|\\b4k\\b|uhd").containsMatchIn(n) -> out += "4K"
            Regex("1080[pi]").containsMatchIn(n) -> out += "1080p"
            Regex("720p").containsMatchIn(n) -> out += "720p"
        }
        val dv = Regex("\\bdv\\b|dovi|dolby\\.?vision|杜比视界").containsMatchIn(n)
        if (dv) out += "杜比视界"
        when {
            Regex("hdr10\\+|hdr10plus|hdr10p\\b").containsMatchIn(n) -> out += "HDR10+"
            Regex("hdr10|\\bhdr\\b").containsMatchIn(n) -> out += "HDR10"
            Regex("\\bhlg\\b").containsMatchIn(n) -> out += "HLG"
        }
        audioFromName(n)?.let { out += it }
        return out
    }

    private fun audioFromName(n: String): String? {
        val atmos = n.contains("atmos")
        return when {
            n.contains("truehd") -> if (atmos) "TrueHD Atmos" else "TrueHD"
            Regex("dts[-.]?x\\b").containsMatchIn(n) -> "DTS:X"
            Regex("dts[-.]?hd").containsMatchIn(n) -> "DTS-HD"
            n.contains("dts") -> "DTS"
            Regex("ddp|eac3|e-ac-3|dd\\+").containsMatchIn(n) -> if (atmos) "DD+ Atmos" else "DD+"
            Regex("\\bac3\\b|\\bdd5|\\bdd\\.?5\\.1").containsMatchIn(n) -> "AC3"
            n.contains("flac") -> "FLAC"
            n.contains("aac") -> "AAC"
            atmos -> "Atmos"
            else -> null
        }
    }

    /** Badges from the selected / first tracks after the player loaded the file. */
    fun fromTracks(t: Tracks): List<String> {
        val out = ArrayList<String>()
        var v: Format? = null; var a: Format? = null
        for (g in t.groups) {
            val f = (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } ?: g.getTrackFormat(0)
            if (g.type == MC.TRACK_TYPE_VIDEO && v == null) v = f
            if (g.type == MC.TRACK_TYPE_AUDIO && (a == null || g.isSelected)) a = f
        }
        v?.let { f ->
            val h = minOf(f.width.takeIf { it > 0 } ?: 0, f.height.takeIf { it > 0 } ?: 0).let { s -> if (s == 0) f.height else s }
            val w = maxOf(f.width, f.height)
            when { w >= 3200 || h >= 2000 -> out += "4K"; w >= 1800 || h >= 1000 -> out += "1080p"; w >= 1200 || h >= 700 -> out += "720p" }
            if (f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dv") == true) out += "杜比视界" + (dvProfile(f)?.let { " P$it" } ?: "")
            when (f.colorInfo?.colorTransfer) {
                MC.COLOR_TRANSFER_ST2084 -> out += "HDR10"
                MC.COLOR_TRANSFER_HLG -> out += "HLG"
            }
        }
        a?.let { f ->
            out += when (f.sampleMimeType) {
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
                MimeTypes.AUDIO_DTS_X -> "DTS:X"
                MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
                MimeTypes.AUDIO_E_AC3_JOC -> "DD+ Atmos"
                MimeTypes.AUDIO_E_AC3 -> "DD+"
                MimeTypes.AUDIO_AC3 -> "AC3"
                MimeTypes.AUDIO_AC4 -> "AC4"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_MPEG -> "MP3"
                else -> f.sampleMimeType?.substringAfter('/')?.uppercase() ?: ""
            }.let { if (it.isNotEmpty() && f.channelCount > 2) "$it ${channels(f.channelCount)}" else it }
        }
        return out.filter { it.isNotBlank() }
    }

    private fun channels(c: Int) = when (c) { 6 -> "5.1"; 8 -> "7.1"; else -> "${c}ch" }

    /** "dvhe.05.06" → 5. */
    fun dvProfile(f: Format): Int? = f.codecs?.let { Regex("dv[a-z0-9]{2}\\.(\\d{2})").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    // ------------------------------------------------ cache of track badges per file (filled after a play)
    private fun key(path: String) = "badge." + path.hashCode().toString(16)
    fun remember(path: String, badges: List<String>) { if (badges.isNotEmpty()) Store.putStr(key(path), badges.joinToString("|")) }
    /** Best known badges for a file: real track info when it was played before, otherwise name parsing. */
    fun of(path: String, name: String): List<String> = Store.getStr(key(path)).takeIf { it.isNotEmpty() }?.split('|') ?: fromName(name)
}

object DeviceCaps {
    val dolbyVision: Boolean by lazy {
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci -> !ci.isEncoder && ci.supportedTypes.any { it.equals(MimeTypes.VIDEO_DOLBY_VISION, true) } } }.getOrDefault(false)
    }

    /** HDR types the screen can show: "HDR10", "HDR10+", "HLG", "杜比视界". */
    fun hdrTypes(c: Context): List<String> = runCatching {
        @Suppress("DEPRECATION")
        val d = if (Build.VERSION.SDK_INT >= 30) c.display else (c.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        val types = d?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        types.map {
            when (it) {
                android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "杜比视界"
                android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                android.view.Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                4 -> "HDR10+"
                else -> "HDR"
            }
        }
    }.getOrDefault(emptyList())

    /**
     * B35: the P7 dual-layer (BL + EL) track choice on a non-DV phone. In a video group with several tracks
     * where one is DV P7, the non-selected P7 tracks are the EL (enhancement) layer — keeping them lets the
     * decoder try to decode EL and fail / garble. Returns overrides that keep the selected (BL) track plus
     * any non-P7 track. P8 is single-layer and HDR10-compatible (nothing to drop); P5 has no HDR10 layer
     * at all, so it can only be warned about via [hint].
     */
    fun elDisabledOverrides(t: Tracks): List<TrackSelectionOverride> {
        if (dolbyVision) return emptyList()
        val out = ArrayList<TrackSelectionOverride>()
        for (g in t.groups) {
            if (g.type != MC.TRACK_TYPE_VIDEO || g.length < 2) continue
            val formats = (0 until g.length).map { g.getTrackFormat(it) }
            if (formats.none { MediaBadges.dvProfile(it) == 7 }) continue
            val sel = (0 until g.length).firstOrNull { g.isTrackSelected(it) } ?: 0
            val keep = (0 until g.length).filter { it == sel || MediaBadges.dvProfile(formats[it]) != 7 }
            if (keep.size < g.length) out += TrackSelectionOverride(g.mediaTrackGroup, keep)
        }
        return out
    }

    /** A one-line hint when the file's HDR flavour will not look right here, else null. */
    fun hint(c: Context, badges: List<String>, dvProfile: Int? = null): String? {
        val screen = hdrTypes(c)
        val isDv = badges.any { it.startsWith("杜比视界") }
        val hasHdrLayer = badges.any { it.startsWith("HDR") || it == "HLG" }
        return when {
            // B35: P5 / P7 / P8 get their own copy — the old single line hid that P8 plays fine as HDR10.
            isDv && !dolbyVision && dvProfile == 5 ->
                "这台手机不支持杜比视界，这个版本（Profile 5）颜色可能偏紫偏绿，建议选 HDR10 或普通版本"
            isDv && !dolbyVision && dvProfile == 7 ->
                "这个版本是杜比视界 Profile 7（双层），手机不支持杜比视界时只播基础层，画面约等于 HDR10；想看完整效果建议选 HDR10 版本"
            isDv && !dolbyVision && dvProfile == 8 ->
                "这个版本是杜比视界 Profile 8，与 HDR10 兼容，手机会按 HDR10 播放，颜色正常"
            isDv && !dolbyVision && dvProfile == null && !hasHdrLayer ->
                "这台手机不支持杜比视界，这个版本颜色可能偏紫偏绿，建议选 HDR10 或普通版本"
            isDv && !dolbyVision -> "手机不支持杜比视界，会按 HDR10 / 普通画面播放"
            (hasHdrLayer || isDv) && screen.isEmpty() -> "屏幕不支持 HDR，会按普通亮度显示，颜色可能偏淡"
            else -> null
        }
    }
}

/** Per-show (MovieItem id) playback choices + global subtitle look. */
object PlayPrefs {
    private fun obj(id: String): JSONObject = runCatching { JSONObject(Store.getStr("play.$id")) }.getOrElse { JSONObject() }
    private fun put(id: String, f: (JSONObject) -> Unit) { val o = obj(id); f(o); Store.putStr("play.$id", o.toString()) }

    fun audioLang(id: String): String = obj(id).optString("a")
    fun subLang(id: String): String = obj(id).optString("s")
    /** "off" when the user turned subtitles off for this show. */
    fun setAudioLang(id: String, v: String) = put(id) { it.put("a", v) }
    fun setSubLang(id: String, v: String) = put(id) { it.put("s", v) }
    fun intro(id: String): Int = obj(id).optInt("in", 0)
    fun outro(id: String): Int = obj(id).optInt("out", 0)
    fun setIntro(id: String, s: Int) = put(id) { it.put("in", s) }
    fun setOutro(id: String, s: Int) = put(id) { it.put("out", s) }
    /** Subtitle delay in ms (positive = later), per show. */
    fun subOffset(id: String): Long = obj(id).optLong("off", 0L)
    fun setSubOffset(id: String, ms: Long) = put(id) { it.put("off", ms) }

    /** 1.1.0 影音设置「自动播放下一集」（全局，默认开）：剧集播完后倒数 5 秒自动播下一集。 */
    var autoNext: Boolean get() = Store.getStr("play.autoNext", "1") == "1"; set(v) = Store.putStr("play.autoNext", if (v) "1" else "0")

    /** 用户 2026-10-23 反馈（清晰度）：影音设置「记住飞牛清晰度」（全局，默认关 = 每次从原画开始）。 */
    var fnQualityRemember: Boolean get() = Store.getStr("play.fnQRemember", "0") == "1"; set(v) = Store.putStr("play.fnQRemember", if (v) "1" else "0")
    /** Last 清晰度 picked on a 飞牛 source: FnTvTier.key ("1080|5000000"), "" = 原画. */
    var fnQualityLast: String get() = Store.getStr("play.fnQLast", ""); set(v) = Store.putStr("play.fnQLast", v)

    /** Subtitle text size as a fraction of the view height (media3 default 0.0533). */
    var subSize: Float get() = Store.getStr("play.subSize", "0.0533").toFloatOrNull() ?: 0.0533f; set(v) = Store.putStr("play.subSize", v.toString())
    /** Bottom padding fraction (media3 default 0.08). */
    var subBottom: Float get() = Store.getStr("play.subBottom", "0.08").toFloatOrNull() ?: 0.08f; set(v) = Store.putStr("play.subBottom", v.toString())
}

/** Shifts SRT / ASS / VTT timestamps by [ms] (used for the per-show subtitle offset on external subtitles). */
object SubShift {
    /** S14: only a real timeline row ("00:01:02,000 --> 00:01:04,000") is shifted — a timestamp that
     * appears inside subtitle TEXT (e.g. "约在 00:01:02 见面") used to be shifted too by the old global regex. */
    private val SRT_LINE = Regex("(?m)^(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})(\\s*-->\\s*)(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")
    private val ASS = Regex("^(Dialogue:\\s*[^,]*,)(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),", RegexOption.MULTILINE)

    fun shift(text: String, ext: String, ms: Long): String {
        if (ms == 0L) return text
        return if (ext == "ass" || ext == "ssa") ASS.replace(text) { m ->
            val g = m.groupValues
            val a = (g[2].toLong() * 3600_000 + g[3].toLong() * 60_000 + g[4].toLong() * 1000 + g[5].toLong() * 10 + ms).coerceAtLeast(0)
            val b = (g[6].toLong() * 3600_000 + g[7].toLong() * 60_000 + g[8].toLong() * 1000 + g[9].toLong() * 10 + ms).coerceAtLeast(0)
            g[1] + ass(a) + "," + ass(b) + ","
        } else SRT_LINE.replace(text) { m ->
            val g = m.groupValues
            fun shiftAt(h: Long, mi: Long, s: Long, f: Long): String {
                val t = (h * 3600_000 + mi * 60_000 + s * 1000 + f + ms).coerceAtLeast(0)
                val sep = if (ext == "vtt") "." else ","
                return "%02d:%02d:%02d%s%03d".format(t / 3600_000, t / 60_000 % 60, t / 1000 % 60, sep, t % 1000)
            }
            shiftAt(g[1].toLong(), g[2].toLong(), g[3].toLong(), g[4].toLong()) + g[5] +
                shiftAt(g[6].toLong(), g[7].toLong(), g[8].toLong(), g[9].toLong())
        }
    }
    private fun ass(t: Long) = "%d:%02d:%02d.%02d".format(t / 3600_000, t / 60_000 % 60, t / 1000 % 60, t % 1000 / 10)
}

/**
 * Shared player construction. The three ExoPlayer sites keep their own DataSource / load-control /
 * audio-attribute setup (lifecycles differ), but the FFmpeg-first renderer config is identical in the
 * movie and music players — the photo viewer intentionally stays on the default renderers (no FFmpeg).
 */
fun ffmpegRenderersFactory(ctx: Context): DefaultRenderersFactory =
    DefaultRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        .setEnableDecoderFallback(true)

/**
 * 1.1.0：libVLC 已删除。Media3 放不了的格式（rmvb / rm / wmv / asf，ape / wma / DSD 等）改为「用其他播放器打开」。
 * 需要鉴权的地址（WebDAV 密码、飞牛 token）不直接交给别的应用，而是经 [CastProxy.serveLocal] 的本机回环代理转发：
 * 只有本机能访问（127.0.0.1），不会把密码写进链接；一维相册在后台被系统关掉后链接就失效。
 */
object ExternalPlayer {
    /** Video containers media3 1.3.1 cannot open at all: the player offers 「用其他播放器打开」 right away. */
    val VIDEO_EXT = setOf("rmvb", "rm", "wmv", "asf")

    /** Opens [url] in another app. Returns a short message for a toast (null = opened without anything to say). */
    fun open(ctx: Context, url: String, headers: Map<String, String>, name: String, audio: Boolean): String? {
        val needsAuth = headers.isNotEmpty() && url.startsWith("http")
        val target = if (needsAuth) runCatching { CastProxy.serveLocal(url, headers, name) }.getOrElse { return "无法生成本机临时地址：${it.message}" } else url
        val mime = (if (audio) "audio/*" else "video/*")
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(target), mime)
            .putExtra("title", name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(android.content.Intent.createChooser(i, "用其他播放器打开").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            Diag.log("PLAY", "external player: ${NameParser.ext(name)} auth=${needsAuth}")
            if (needsAuth) "这个文件需要登录，已通过本机临时地址转发；播放时请不要彻底关闭一维相册" else null
        } catch (e: android.content.ActivityNotFoundException) {
            if (needsAuth) runCatching { CastProxy.revoke(target) }
            "手机上没有能播放这个格式的应用，可以安装 VLC 或 MX Player 后再试"
        }
    }
}
