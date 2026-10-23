package com.hark.shiguang

import java.util.Locale

/**
 * 用户 2026-10-23 反馈：音轨列表出现 4 个一模一样的「中文，5.1 环绕声」，字幕 4 个「中文」。
 * media3 自带的轨道名只在没有语言时才用 mkv 的 title（国语 / 粤语 / 简体 / 导评都被藏掉了），
 * 也不合并真正重复的轨。这里是纯函数：轨道描述 → 列表里显示的选项（无 Android 依赖，见 TrackNamesTest）。
 *
 * 规则：
 * - 标题依次取：轨道 label（mkv title）→ 语言代码映射 → 「未知语言」。
 * - 语言、label、编码、声道、强制/导评标记全都相同才合并成一项（选中时用第一条）。
 * - 合并后标题仍重名的，在标题后用（编码 声道）区分；还分不开再加序号。
 * - 次要信息（编码、声道、语言）放 detail，界面用小一号字显示。
 */
object TrackNames {

    /** One audio or subtitle track as read from media3 Format (only what naming needs). */
    data class Track(
        val id: Int,
        val language: String? = null,
        val label: String? = null,
        val mime: String? = null,
        /** Format.codecs — for media3 transcoded subtitle cues it carries the original mime. */
        val codecs: String? = null,
        val channels: Int = 0,
        val forced: Boolean = false,
        val commentary: Boolean = false,
    )

    /** One row of the picker. [ids] are the tracks merged into it; the first is the one to select. */
    data class Option(val title: String, val detail: String?, val ids: List<Int>)

    // ---- language codes

    private val SIMPLE = mapOf(
        "zh" to "中文", "chi" to "中文", "zho" to "中文", "cmn" to "中文", "chinese" to "中文",
        "yue" to "粤语", "cantonese" to "粤语",
        "chs" to "简体中文", "cht" to "繁体中文",
        "en" to "英语", "eng" to "英语", "english" to "英语",
        "ja" to "日语", "jpn" to "日语", "jp" to "日语",
        "ko" to "韩语", "kor" to "韩语",
        "fr" to "法语", "fra" to "法语", "fre" to "法语",
        "de" to "德语", "deu" to "德语", "ger" to "德语",
        "es" to "西班牙语", "spa" to "西班牙语",
        "it" to "意大利语", "ita" to "意大利语",
        "ru" to "俄语", "rus" to "俄语",
        "pt" to "葡萄牙语", "por" to "葡萄牙语",
        "th" to "泰语", "tha" to "泰语",
        "vi" to "越南语", "vie" to "越南语",
        "ar" to "阿拉伯语", "ara" to "阿拉伯语",
        "hi" to "印地语", "hin" to "印地语",
        "id" to "印尼语", "ind" to "印尼语",
        "ms" to "马来语", "msa" to "马来语", "may" to "马来语",
        "nl" to "荷兰语", "nld" to "荷兰语", "dut" to "荷兰语",
        "sv" to "瑞典语", "swe" to "瑞典语",
        "pl" to "波兰语", "pol" to "波兰语",
        "tr" to "土耳其语", "tur" to "土耳其语",
    )
    private val UNKNOWN = setOf("", "und", "unk", "mul", "zxx", "mis", "null")

    /**
     * Language code → 中文名. zh / chi / zho → 中文，yue / zh-HK / zh-yue → 粤语，zh-Hant / zh-TW → 繁体中文，
     * zh-Hans / zh-CN → 简体中文. null for empty / und.
     */
    fun languageName(code: String?): String? {
        val c = code?.trim()?.lowercase()?.replace('_', '-') ?: return null
        if (c in UNKNOWN) return null
        val parts = c.split('-').filter { it.isNotEmpty() }
        val base = parts.firstOrNull() ?: return null
        if (base in setOf("zh", "chi", "zho", "cmn")) {
            val rest = parts.drop(1)
            return when {
                rest.any { it == "yue" || it == "hk" || it == "mo" } -> "粤语"
                rest.any { it == "hant" || it == "tw" } -> "繁体中文"
                rest.any { it == "hans" || it == "cn" || it == "sg" } -> "简体中文"
                else -> "中文"
            }
        }
        SIMPLE[base]?.let { return it }
        // anything else: the JVM's own Chinese display name ("Swahili" → "斯瓦希里文"), else the raw code
        val n = runCatching { Locale.forLanguageTag(base).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) }.getOrNull()
        return n?.takeIf { it.isNotBlank() && !it.equals(base, ignoreCase = true) } ?: code.trim()
    }

    // ---- labels

    private val LABEL_ALIASES = mapOf(
        "mandarin" to "国语", "cantonese" to "粤语", "commentary" to "导评", "director's commentary" to "导评",
        "simplified" to "简体", "simplified chinese" to "简体中文", "chinese simplified" to "简体中文", "chs" to "简体",
        "traditional" to "繁体", "traditional chinese" to "繁体中文", "chinese traditional" to "繁体中文", "cht" to "繁体",
        "english" to "英语", "chinese" to "中文", "japanese" to "日语", "korean" to "韩语",
    )
    /** A label that only repeats technical info ("AC3 5.1", "Stereo", "SRT") is not a name. */
    private val TECH_ONLY = Regex("^[\\s\\-_.,/|()\\[\\]0-9]*((ac-?3|e-?ac-?3|dts(-?hd)?( ?ma)?|true-?hd|atmos|aac|flac|mp3|opus|pcm|lpcm|stereo|mono|surround|ch|channels?|srt|ass|ssa|pgs|sup|vobsub|subrip|hdmv|kbps|khz)[\\s\\-_.,/|()\\[\\]0-9]*)+$", RegexOption.IGNORE_CASE)

    /** The track's own title (mkv title) cleaned up, or null when it is empty or only technical. */
    fun labelName(label: String?): String? {
        val l = label?.trim()?.trim('"')?.trim().orEmpty()
        if (l.isEmpty() || TECH_ONLY.matches(l)) return null
        return LABEL_ALIASES[l.lowercase()] ?: l
    }

    // ---- codecs / channels

    /** Short codec name from a sample mime type ("audio/ac3" → "AC3"). null when unknown. */
    fun codecName(mime: String?, codecs: String? = null): String? {
        val m = mime?.lowercase() ?: return null
        if (m == "application/x-media3-cues") return codecName(codecs, null)
        return when (m) {
            "audio/ac3" -> "AC3"
            "audio/eac3" -> "E-AC3"
            "audio/eac3-joc" -> "E-AC3 Atmos"
            "audio/ac4" -> "AC4"
            "audio/true-hd" -> "TrueHD"
            "audio/vnd.dts" -> "DTS"
            "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr" -> "DTS-HD"
            "audio/vnd.dts.uhd;profile=p2" -> "DTS:X"
            "audio/mp4a-latm", "audio/aac" -> "AAC"
            "audio/mpeg" -> "MP3"
            "audio/mpeg-l1", "audio/mpeg-l2" -> "MP2"
            "audio/opus" -> "Opus"
            "audio/vorbis" -> "Vorbis"
            "audio/flac" -> "FLAC"
            "audio/alac" -> "ALAC"
            "audio/raw" -> "PCM"
            "application/x-subrip" -> "SRT"
            "text/x-ssa" -> "ASS"
            "text/vtt" -> "VTT"
            "application/ttml+xml" -> "TTML"
            "application/pgs" -> "PGS"
            "application/vobsub" -> "VobSub"
            "application/dvbsubs" -> "DVB"
            "application/cea-608", "application/cea-708" -> "CC"
            "application/x-quicktime-tx3g" -> "TX3G"
            else -> null
        }
    }

    /** "5.1", "7.1", "2.0", "单声道"; null when unknown. */
    fun channelName(channels: Int): String? = when {
        channels <= 0 -> null
        channels == 1 -> "单声道"
        channels == 2 -> "2.0"
        channels == 3 -> "2.1"
        channels == 6 -> "5.1"
        channels == 7 -> "6.1"
        channels == 8 -> "7.1"
        else -> "${channels}声道"
    }

    // ---- building the list

    private data class Key(val lang: String?, val label: String?, val codec: String?, val channels: Int, val forced: Boolean, val commentary: Boolean)

    private fun keyOf(t: Track) = Key(
        languageName(t.language), labelName(t.label), codecName(t.mime, t.codecs) ?: t.mime?.lowercase(),
        t.channels, t.forced, t.commentary,
    )

    /**
     * Turns tracks (in player order) into picker rows: exact duplicates merged, rows that would still read the
     * same told apart by codec / channels. [audio] = true adds channels; subtitles only use the codec.
     */
    fun options(tracks: List<Track>, audio: Boolean): List<Option> {
        // 1. merge tracks that are identical in everything we can show (keeps the first one's position)
        val groups = LinkedHashMap<Key, MutableList<Track>>()
        tracks.forEach { t -> groups.getOrPut(keyOf(t)) { mutableListOf() }.add(t) }

        data class Row(val key: Key, val ids: List<Int>, val base: String, val tech: String?, val extra: List<String>)
        val rows = groups.map { (k, ts) ->
            val label = k.label
            val base = label ?: k.lang ?: (if (k.commentary) "导评" else "未知语言")
            val tech = listOfNotNull(k.codec?.takeIf { !it.contains('/') }, if (audio) channelName(k.channels) else null)
                .joinToString(" ").ifEmpty { null }
            val extra = buildList {
                // the language is secondary info when the title came from the label and does not already say it
                if (label != null && k.lang != null && !label.contains(k.lang.removeSuffix("语").removeSuffix("文"))) add(k.lang)
                if (k.forced) add("强制")
                if (k.commentary && base != "导评") add("导评")
            }
            Row(k, ts.map { it.id }, base, tech, extra)
        }

        // 2. same title, different tracks → (codec channels) in the title; still the same → number them
        val byBase = rows.groupBy { it.base }
        val firstPass = rows.map { r ->
            val clash = byBase.getValue(r.base).size > 1
            val title = if (clash && r.tech != null) "${r.base}（${r.tech}）" else r.base
            Triple(r, title, clash && r.tech != null)
        }
        val seen = HashMap<String, Int>()
        val total = firstPass.groupingBy { it.second }.eachCount()
        return firstPass.map { (r, t, techInTitle) ->
            val title = if ((total[t] ?: 0) > 1) {
                val n = (seen[t] ?: 0) + 1; seen[t] = n
                if (n == 1) t else "$t $n"
            } else t
            val detail = (r.extra + listOfNotNull(if (techInTitle) null else r.tech)).joinToString(" · ").ifEmpty { null }
            Option(title, detail, r.ids)
        }
    }

    // ---- video quality

    data class Video(val id: Int, val width: Int, val height: Int, val bitrate: Int = 0)

    /** "4K" / "2K" / "1080P" / "720P" / "480P" / "{h}P" — by width so a 1920×800 film is still 1080P. */
    fun qualityName(width: Int, height: Int): String {
        val w = maxOf(width, height); val h = minOf(width, height)
        return when {
            w <= 0 || h <= 0 -> "原画"
            w >= 3800 || h >= 2100 -> "4K"
            w >= 2500 || h >= 1400 -> "2K"
            w >= 1900 || h >= 1000 -> "1080P"
            w >= 1260 || h >= 700 -> "720P"
            w >= 840 || h >= 470 -> "480P"
            else -> "${h}P"
        }
    }

    /**
     * Quality rows, best first, one per quality name (the higher bitrate wins). A single video track gives one
     * row「原画」with its resolution as detail; the caller adds「自动」when there is more than one row.
     */
    fun qualities(videos: List<Video>): List<Option> {
        if (videos.size <= 1) {
            val v = videos.firstOrNull()
            return listOf(Option("原画", v?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" }, listOfNotNull(v?.id)))
        }
        return videos.sortedWith(compareByDescending<Video> { it.width.toLong() * it.height }.thenByDescending { it.bitrate })
            .groupBy { qualityName(it.width, it.height) }
            .map { (name, vs) ->
                val best = vs.first()
                Option(name, listOfNotNull(
                    "${best.width}×${best.height}".takeIf { best.width > 0 },
                    best.bitrate.takeIf { it > 0 }?.let { bitrateName(it.toLong()) },
                ).joinToString(" · ").ifEmpty { null }, listOf(best.id))
            }
    }

    // ---- details panel formatting

    /** "8.2 Mbps" / "640 kbps"; null when unknown. */
    fun bitrateName(bps: Long): String? = when {
        bps <= 0 -> null
        bps >= 1_000_000 -> "%.1f Mbps".format(Locale.US, bps / 1_000_000.0)
        else -> "${(bps + 500) / 1000} kbps"
    }

    /** "23.976 fps" / "25 fps"; null when unknown. */
    fun frameRateName(fps: Float): String? {
        if (fps <= 0f || fps.isNaN()) return null
        val s = "%.3f".format(Locale.US, fps).trimEnd('0').trimEnd('.')
        return "$s fps"
    }

    /** Video codec from a sample mime type ("video/hevc" → "HEVC (H.265)"). */
    fun videoCodecName(mime: String?): String? = when (mime?.lowercase()) {
        null -> null
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "H.265 (HEVC)"
        "video/dolby-vision" -> "杜比视界"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4"
        "video/mpeg2" -> "MPEG-2"
        "video/mpeg" -> "MPEG-1"
        "video/3gpp" -> "H.263"
        "video/wvc1", "video/vc1" -> "VC-1"
        else -> mime.substringAfter('/').uppercase()
    }

    /** Container from a container mime type, falling back to the file extension. */
    fun containerName(containerMime: String?, fileName: String): String? {
        val byMime = when (containerMime?.lowercase()) {
            "video/x-matroska", "audio/x-matroska" -> "MKV"
            "video/webm", "audio/webm" -> "WebM"
            "video/mp4", "audio/mp4" -> "MP4"
            "video/mp2t" -> "TS"
            "video/mp2p" -> "MPEG-PS"
            "video/x-flv" -> "FLV"
            "video/avi", "video/x-msvideo" -> "AVI"
            "application/x-mpegurl" -> "HLS"
            "application/dash+xml" -> "DASH"
            else -> null
        }
        if (byMime != null) return byMime
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "" -> null
            "m2ts", "mts" -> "M2TS"
            else -> ext.uppercase()
        }
    }

    /** "1.23 GB" / "456 MB"; null when unknown. */
    fun sizeName(bytes: Long): String? = when {
        bytes <= 0 -> null
        bytes >= 1L shl 30 -> "%.2f GB".format(Locale.US, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(Locale.US, bytes / (1L shl 20).toDouble())
        else -> "%.0f KB".format(Locale.US, bytes / 1024.0)
    }

    /** "48 kHz" / "44.1 kHz"; null when unknown. */
    fun sampleRateName(hz: Int): String? {
        if (hz <= 0) return null
        val k = "%.1f".format(Locale.US, hz / 1000.0).removeSuffix(".0")
        return "$k kHz"
    }
}
