package com.hark.shiguang.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户 2026-10-23 反馈（清晰度）：飞牛影视（trim.media，/v/api）服务器转码档位。
 *
 * 只放纯函数（JSON → 数据、标签、路径匹配），网络在 [FnTv]。字段名的依据：
 *  - jimboo7339/fntv_danmu_all doc/飞牛影视API文档.md（v0.9.7 抓包）：POST /v/api/v1/stream 返回 qualities
 *    [{bitrate, resolution:"4k"/"1080"/"720", progressive, is_m3u8}]、file_stream、video_stream、audio_streams。
 *  - FNOSP/FlyNarwhal（飞牛开放平台的影视客户端）：stream/list 的 files / video_streams / audio_streams，
 *    play/play 返回 play_link，qualities 的第一项当作原画（直连），is_m3u8 的档位不可选。
 */
data class FnTvTier(
    /** Raw value sent back to play/play ("4k", "1080", "720" …). */
    val resolution: String,
    val bitrate: Long,
    /** 「4K」「1080P 高码率」「720P」. */
    val label: String,
    /** 「5 Mbps」. */
    val detail: String,
) {
    /** What 「记住飞牛清晰度」 stores. */
    val key: String get() = "$resolution|$bitrate"
}

/** One file of a 飞牛影视 item (stream/list `files`). */
data class FnTvFile(val guid: String, val path: String, val name: String, val size: Long)

/** A video / audio / subtitle stream of a 飞牛影视 file. */
data class FnTvStream(
    val guid: String,
    val mediaGuid: String,
    val codec: String = "",
    val language: String = "",
    val title: String = "",
    val index: Int = 0,
    val bps: Long = 0,
    val resolutionType: String = "",
    val profile: String = "",
    val colorRange: String = "",
    val external: Boolean = false,
)

/** A search / season / episode list row. */
data class FnTvHit(val guid: String, val type: String, val title: String, val number: Int)

object FnTvParse {
    /** Order used when sorting tiers (higher first). */
    private fun rank(res: String): Int {
        val r = res.trim().lowercase()
        return when {
            r == "8k" -> 4320
            r == "4k" -> 2160
            r == "2k" -> 1440
            else -> r.removeSuffix("p").toIntOrNull() ?: 0
        }
    }

    /** "4k" → 4K, "1080" → 1080P, "720p" → 720P, "原画" stays. */
    fun resolutionName(res: String): String {
        val r = res.trim()
        val l = r.lowercase()
        return when {
            r.isEmpty() -> "未知"
            l.endsWith("k") && l.dropLast(1).toIntOrNull() != null -> l.uppercase()
            l.removeSuffix("p").toIntOrNull() != null -> l.removeSuffix("p") + "P"
            else -> r
        }
    }

    /** 6255343 → "6.3 Mbps", 5000000 → "5 Mbps", 800000 → "800 kbps". */
    fun bitrateName(bps: Long): String = when {
        bps <= 0 -> ""
        bps >= 1_000_000 -> {
            val m = Math.round(bps / 100_000.0) / 10.0
            (if (m == Math.floor(m)) m.toLong().toString() else m.toString()) + " Mbps"
        }
        else -> "${Math.round(bps / 1000.0)} kbps"
    }

    private fun JSONObject.str(k: String): String = optString(k).takeIf { it != "null" }.orEmpty()
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    /** `data` of a response, which can be an object or a list. */
    fun dataObj(resp: JSONObject?): JSONObject? = resp?.optJSONObject("data")
    fun dataArr(resp: JSONObject?): JSONArray? = resp?.optJSONArray("data")

    /**
     * Transcoding tiers from the `data` of POST /v/api/v1/stream (an object with `qualities`), or the bare list
     * POST /v/api/v1/play/quality returns. The first entry is the original file (FlyNarwhal / the web player play it
     * directly), so it is left out: 原画 is always our own direct-play row. m3u8 entries (cloud drives) are not
     * offered, same as the web player. Duplicates go, highest first.
     */
    fun tiers(qualities: JSONArray?): List<FnTvTier> {
        val raw = qualities.objects().map { Triple(it.str("resolution"), it.optLong("bitrate"), it.optBoolean("is_m3u8")) }
        val list = raw.drop(1).filter { !it.third && it.first.isNotBlank() && it.first != "原画" }
            .map { it.first to it.second }.distinct()
            .sortedWith(compareByDescending<Pair<String, Long>> { rank(it.first) }.thenByDescending { it.second })
        val perRes = list.groupBy { resolutionName(it.first) }
        return list.map { (res, br) ->
            val name = resolutionName(res)
            val group = perRes[name].orEmpty()
            val label = if (group.size > 1 && group.first().second == br) "$name 高码率" else name
            FnTvTier(res, br, label, bitrateName(br))
        }
    }

    /** [tiers] from either response shape. */
    fun tiersOf(resp: JSONObject?): List<FnTvTier> =
        tiers(dataObj(resp)?.optJSONArray("qualities") ?: dataArr(resp))

    /** The login token (`data.token`), or null. */
    fun token(resp: JSONObject?): String? = dataObj(resp)?.str("token")?.takeIf { it.isNotBlank() }

    /** play/play → `data.play_link` ("/v/media/<id>/preset.m3u8"), or null. */
    fun playLink(resp: JSONObject?): String? = dataObj(resp)?.str("play_link")?.takeIf { it.isNotBlank() }

    /** A play_link made absolute on the NAS address. */
    fun absUrl(base: String, link: String): String = when {
        link.startsWith("http://") || link.startsWith("https://") -> link
        else -> base.trimEnd('/') + "/" + link.trimStart('/')
    }

    /** GET search/list, season/list, episode/list rows (`data` is a list). */
    fun hits(resp: JSONObject?): List<FnTvHit> = (dataArr(resp) ?: dataObj(resp)?.optJSONArray("list")).objects().map {
        FnTvHit(it.str("guid"), it.str("type"), it.str("title"),
            if (it.has("episode_number")) it.optInt("episode_number") else it.optInt("season_number"))
    }.filter { it.guid.isNotBlank() }

    fun files(data: JSONObject?): List<FnTvFile> = data?.optJSONArray("files").objects().map {
        FnTvFile(it.str("guid"), it.str("path"), it.str("file_name"), it.optLong("size"))
    }.filter { it.guid.isNotBlank() }

    fun streams(data: JSONObject?, key: String): List<FnTvStream> = data?.optJSONArray(key).objects().map {
        FnTvStream(
            guid = it.str("guid"), mediaGuid = it.str("media_guid"), codec = it.str("codec_name"),
            language = it.str("language"), title = it.str("title"), index = it.optInt("index"), bps = it.optLong("bps"),
            resolutionType = it.str("resolution_type"), profile = it.str("profile"), colorRange = it.str("color_range_type"),
            external = it.optInt("is_external") == 1,
        )
    }

    /** "fm:vol1/1000/影视/a.mkv" and "/vol1/1000/影视/a.mkv/" both → "/vol1/1000/影视/a.mkv". */
    fun normPath(p: String): String {
        val s = p.removePrefix("fm:").replace('\\', '/').replace(Regex("/{2,}"), "/").trimEnd('/')
        return if (s.startsWith("/")) s else "/$s"
    }

    /**
     * The 飞牛影视 file that is the same file as ours: same NAS path, else same name and size (a share can be seen
     * under another mount path). Never a guess by name alone.
     */
    fun matchFile(files: List<FnTvFile>, path: String, name: String, size: Long): FnTvFile? {
        val want = normPath(path)
        files.firstOrNull { it.path.isNotBlank() && normPath(it.path) == want }?.let { return it }
        if (size <= 0) return null
        return files.firstOrNull { f ->
            val fn = f.name.ifBlank { f.path.substringAfterLast('/') }
            fn.equals(name, ignoreCase = true) && f.size == size
        }
    }

    /** The search hits worth opening, in order: playable items first, then shows. */
    fun candidates(hits: List<FnTvHit>, isShow: Boolean): List<FnTvHit> {
        val playable = setOf("Movie", "Video", "Episode")
        val first = if (isShow) hits.filter { it.type == "TV" || it.type == "Episode" } else hits.filter { it.type in playable }
        return (first + hits.filter { it.type in playable || it.type == "TV" }).distinctBy { it.guid }
    }

    /** Audio stream for play/play: the one in the language the user is listening to, else the [index]-th, else the first. */
    fun pickAudio(streams: List<FnTvStream>, language: String?, index: Int?): FnTvStream? {
        if (streams.isEmpty()) return null
        val l = language?.lowercase()?.takeIf { it.isNotBlank() && it != "und" }
        if (l != null) streams.firstOrNull { lang3(it.language) == lang3(l) }?.let { return it }
        return index?.let { streams.getOrNull(it) } ?: streams.first()
    }

    /** Embedded subtitle to burn / carry in the transcode, by language; null = none. */
    fun pickSubtitle(streams: List<FnTvStream>, language: String?): FnTvStream? {
        val l = language?.lowercase()?.takeIf { it.isNotBlank() && it != "und" } ?: return null
        return streams.filter { !it.external }.firstOrNull { lang3(it.language) == lang3(l) }
    }

    /** zh / zho / chi / cmn → zho; en / eng → eng; … so "zh" matches "chi". */
    fun lang3(l: String): String = when (val s = l.lowercase().substringBefore('-').substringBefore('_')) {
        "zh", "zho", "chi", "cmn", "chs", "cht", "yue" -> "zho"
        "en", "eng" -> "eng"
        "ja", "jpn" -> "jpn"
        "ko", "kor" -> "kor"
        "fr", "fra", "fre" -> "fra"
        "de", "deu", "ger" -> "deu"
        else -> s
    }

    /**
     * Target encoder for play/play. FlyNarwhal (mirroring the web player): never the source codec; hevc / av1 / vp8 /
     * vp9 → hevc, HEVC "rext" profile and everything else → h264 (asking for av1 gets ParameterError 8192).
     */
    fun videoEncoder(codec: String, profile: String): String {
        if (profile.equals("rext", ignoreCase = true)) return "h264"
        return if (codec.lowercase() in setOf("hevc", "h265", "av1", "vp8", "vp9")) "hevc" else "h264"
    }

    /** The remembered tier ([key] "res|bitrate"): exact, else the highest of the same resolution, else null. */
    fun pickTier(tiers: List<FnTvTier>, key: String): FnTvTier? {
        val res = key.substringBefore('|', "").ifBlank { return null }
        val br = key.substringAfter('|', "").toLongOrNull()
        return tiers.firstOrNull { it.resolution == res && it.bitrate == br }
            ?: tiers.filter { it.resolution == res }.maxByOrNull { it.bitrate }
    }
}
