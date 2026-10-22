package com.hark.shiguang

import androidx.compose.runtime.*
import com.hark.shiguang.cloud.CloudEntry
import com.hark.shiguang.cloud.WebDavSource
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.NasX
import com.hark.shiguang.ui.CloudPhotos
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// 1.1.0 拆分自 Movies.kt（只移动代码，行为不变）：文件名解析与 NFO 读取。
// ============================================================ file name parsing

object NameParser {
    val VIDEO = setOf("mkv", "mp4", "avi", "mov", "ts", "m2ts", "rmvb", "rm", "wmv", "asf", "flv", "webm", "m4v", "iso")
    val SUBS = setOf("srt", "ass", "ssa", "vtt")
    val IMG = setOf("jpg", "jpeg", "png", "webp")
    /** Containers ExoPlayer can not play: listed, not played. */
    /** 1.1.0: rmvb/rm/wmv/asf are listed and offered 「用其他播放器打开」 (libVLC removed); only disc images are not playable. */
    val UNPLAYABLE = setOf("iso")

    // 1.0.1 #6: release-name cleaner. \b does not work around CJK, so Latin tags and Chinese tags are separate regexes.
    private val NOISE = Regex(
        "(?i)(?<![a-z0-9])(2160p|1440p|1080p|1080i|720p|576p|480p|4k|8k|uhd|hdr10\\+?|hdr|hlg|dv|dovi|sdr|bluray|blu-ray|bdrip|brrip|bdremux|bdmv|remux|web-?dl|webrip|hdtv|hdrip|dvdrip|dvdscr|dvd|hdcam|camrip|tc|ts|hd|fhd|bd|" +
            "x264|x265|h\\.?264|h\\.?265|hevc|avc|av1|vp9|10bit|8bit|hi10p|60fps|120fps|aac(2\\.0)?|ac3|eac3|dts(-hd)?(\\.ma)?|dts-x|truehd|atmos|flac|opus|lpcm|ddp?\\d?\\.?\\d?|" +
            "5\\.1|7\\.1|2\\.0|proper|repack|extended|unrated|uncut|directors?\\.?cut|imax|internal|limited|complete|multi|dual|dubbed|subbed|chs|cht|chi|eng|jpn|gb|big5|" +
            "nf|amzn|dsnp|hmax|atvp|itunes|iqiyi|youku|tencent|mgtv|bilibili|60帧|120帧)(?![a-z0-9])"
    )
    private val CN_NOISE = Regex(
        "(中英双字|中英字幕|中日双语|中英双语|国粤双语|国英双语|国日双语|双语字幕|双语|简繁英字幕|简繁英|简繁字幕|简繁|简英|繁英|简日|简中|繁中|简体|繁体|" +
            "内封字幕|内嵌字幕|内封|内嵌|外挂字幕|特效字幕|硬字幕|官方字幕|中文字幕|字幕|中字|英字|国语中字|国语|粤语|台配|国配|日语|英语|韩语|原声|" +
            "无删减|未删减|删减版|完整版|加长版|导演剪辑版|剪辑版|修复版|重制版|收藏版|珍藏版|特别版|高清版|高清|超清|标清|蓝光原盘|蓝光|原盘|杜比视界|杜比|全景声|" +
            "无水印|去水印|首发|网络版|抢先版|枪版|电影版|剧场版合集|全集|合集|完结|全\\d+集|更新至?第?\\d+集|第?\\d+集全|\\d+帧)"
    )
    /** Words that mark a bracket or a chunk as a site watermark / release group, not a title. */
    private val SITE_WORDS = Regex("(?i)(电影天堂|阳光电影|飘花|高清影视之家|影视之家|人人影视|字幕组|字幕社|压制|发布|下载|论坛|bt之家|btbt|迅雷|种子|www|\\.com|\\.cn|\\.net|\\.cc|\\.xyz|\\.top|\\.vip|\\.la)")
    private val SITE_NAMES = Regex("(?i)(电影天堂|阳光电影|飘花电影|飘花|高清影视之家|影视之家|人人影视|BT之家|龙部落|迅雷电影天堂|6v电影|首发于|发布于)(发布|首发)?")
    private val DOMAIN = Regex("(?i)(?:https?://)?(?:www\\.)[a-z0-9-]+(?:\\.[a-z0-9-]+)+|(?<![a-z0-9])[a-z0-9-]{2,}\\.(?:com|cn|cc|xyz|top|vip|la)(?:\\.[a-z]{2})?(?![a-z0-9])")
    private val TMDB_TAG = Regex("(?i)[\\[\\{(【]?\\s*tmdb(?:id)?\\s*[=\\-:_ ]\\s*(\\d{1,8})\\s*[\\]\\})】]?")
    private val JUNK = setOf("movie", "video", "film", "main", "feature", "bdmv", "stream", "cd1", "cd2", "disc1", "disc2", "part1", "part2", "sample", "index", "output", "未命名")

    fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    fun stem(n: String) = n.substringBeforeLast('.')

    /** `{tmdb-12345}`, `[tmdbid=12345]`, `tmdbid-12345` (Jellyfin / Emby naming) → 12345. */
    fun tmdbIdIn(name: String): Int? = TMDB_TAG.find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }

    /** Title-ish text with tags removed. Brackets: groups, sites and pure tags are dropped; a bracket is kept as the title only when nothing else is left (anime style "[Group][Title][01]"). */
    fun clean(raw: String): String {
        var s = TMDB_TAG.replace(raw, " ")
        s = DOMAIN.replace(s, " ")
        val segs = ArrayList<String>()
        s = Regex("\\[([^\\]]*)\\]|【([^】]*)】|\\{([^\\}]*)\\}").replace(s) { m -> segs.add(m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()); " " }
        s = SITE_NAMES.replace(s, " ")
        var out = tidy(s)
        if (out.isEmpty() || NoiseOnly(out) && segs.any { tidy(it).isNotEmpty() && !NoiseOnly(tidy(it)) }) {
            val keep = segs.withIndex().filter { (i, t) ->
                val c = tidy(t)
                c.isNotEmpty() && !SITE_WORDS.containsMatchIn(t) && !c.all { it.isDigit() || it == ' ' } && !(i == 0 && segs.size >= 3)
            }
            out = keep.map { tidy(it.value) }.maxByOrNull { it.length } ?: out
        }
        return out
    }
    private fun NoiseOnly(s: String) = s.all { it.isDigit() || it == ' ' || it == '-' }

    private fun tidy(s: String): String {
        var t = s.replace('.', ' ').replace('_', ' ').replace('+', ' ')
        t = SITE_WORDS.let { w -> if (w.containsMatchIn(t) && t.length < 12) "" else t }
        t = NOISE.replace(t, " ")
        t = CN_NOISE.replace(t, " ")
        t = t.replace(Regex("(?<![a-zA-Z])-\\s*[A-Za-z0-9]+$"), "")       // trailing -GROUP
        t = t.replace(Regex("[()（）\\[\\]【】\\{\\}《》]"), " ").replace(Regex("\\s+"), " ").trim(' ', '-', '.', '&', ',', '，')
        return t
    }

    /** title + year; [alt] is an English title found after the year in "中文名.2019.English.Name.1080p". */
    data class MovieName(val title: String, val year: Int?, val alt: String = "")

    private val maxYear: Int get() = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1

    fun movie(fileOrFolder: String): MovieName {
        val s = stem(fileOrFolder).let { if (ext(fileOrFolder) in VIDEO) it else fileOrFolder }.let { DOMAIN.replace(TMDB_TAG.replace(it, " "), " ") }
        // the last plausible year that has a title in front of it ("Blade.Runner.2049.2017" → 2017, "2012.2009" → 2009)
        val ys = Regex("(?<![0-9])((?:19|20)\\d{2})(?![0-9]|[pP](?![a-zA-Z]))").findAll(s).toList()
        for (m in ys.asReversed()) {
            val y = m.groupValues[1].toInt()
            if (y > maxYear) continue
            val title = clean(s.substring(0, m.range.first))
            if (title.isEmpty()) continue
            val tail = clean(s.substring(m.range.last + 1))
            val alt = if (Regex("[\\u3400-\\u9fff]").containsMatchIn(title) && !Regex("[\\u3400-\\u9fff]").containsMatchIn(tail) &&
                Regex("[A-Za-z]{3,}").containsMatchIn(tail) && episodeIn(tail) == null) tail else ""
            return MovieName(title, y, alt)
        }
        return MovieName(clean(s), null)
    }

    /** File names that carry no title ("movie.mkv", "CD1", "01.mp4") — use the folder instead. */
    fun junk(title: String): Boolean = title.length < 2 || norm(title) in JUNK || (title.all { it.isDigit() || it == ' ' } && title.trim().length <= 2)

    data class Ep(val season: Int?, val episode: Int)

    private val CN = mapOf('一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10, '零' to 0, '〇' to 0)
    private fun cnNum(s: String): Int? = s.toIntOrNull() ?: run {
        if (s.isEmpty()) return null
        if (s.contains('百')) { val h = s.substringBefore('百'); val r = s.substringAfter('百').trimStart('零'); return (CN[h.firstOrNull() ?: '一'] ?: 1) * 100 + (cnNum(r) ?: 0) }
        if (s == "十") return 10
        if (s.startsWith("十")) return 10 + (CN[s[1]] ?: 0)
        if (s.length == 2 && s[1] == '十') return (CN[s[0]] ?: 0) * 10
        if (s.length == 3 && s[1] == '十') return (CN[s[0]] ?: 0) * 10 + (CN[s[2]] ?: 0)
        CN[s[0]]
    }

    private fun episodeIn(s: String): Ep? {
        Regex("(?i)s(\\d{1,2})[ ._-]?e[p]?(\\d{1,4})(?![0-9])").find(s)?.let { return Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("(?i)season[ ._-]?(\\d{1,2})[ ._-]*episode[ ._-]?(\\d{1,4})").find(s)?.let { return Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("第\\s*([0-9一二三四五六七八九十]+)\\s*季.*?第\\s*([0-9零〇一二三四五六七八九十百]+)\\s*[集话話期]").find(s)?.let { return Ep(cnNum(it.groupValues[1]), cnNum(it.groupValues[2]) ?: return null) }
        Regex("(?i)(?<![0-9a-z])(\\d{1,2})x(\\d{2,3})(?![0-9])").find(s)?.let { return Ep(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("第\\s*([0-9零〇一二三四五六七八九十百]+)\\s*[集话話期]").find(s)?.let { return Ep(null, cnNum(it.groupValues[1]) ?: return null) }
        Regex("(?i)(?:^|[ ._\\-\\[(])(?:ep?|episode)[ ._-]?(\\d{1,4})(?![\\dpP])").find(s)?.let { return Ep(null, it.groupValues[1].toInt()) }
        Regex("\\s-\\s(\\d{1,4})(?:v\\d)?(?=[\\s\\[(.]|$)").find(s)?.let { return Ep(null, it.groupValues[1].toInt()) }        // "[Group] Title - 01 [1080p]"
        Regex("[\\[【](\\d{2,4})(?:v\\d)?(?:end|完)?[\\]】]", RegexOption.IGNORE_CASE).findAll(s).firstOrNull { it.groupValues[1].toInt() !in setOf(480, 576, 720, 1080, 2160) && !(it.groupValues[1].length == 4 && it.groupValues[1].startsWith("19") || it.groupValues[1].length == 4 && it.groupValues[1].startsWith("20")) }
            ?.let { return Ep(null, it.groupValues[1].toInt()) }
        return null
    }

    fun episode(name: String): Ep? {
        val s = DOMAIN.replace(stem(name), " ")
        episodeIn(s)?.let { return it }
        Regex("(?:^|[ ._-])(\\d{2,3})(?:[ ._-]|$)").find(s)?.takeIf { s.length <= 12 }?.let { return Ep(null, it.groupValues[1].toInt()) }
        Regex("^(\\d{1,3})$").find(s.trim())?.let { return Ep(null, it.groupValues[1].toInt()) }
        return null
    }

    /** "Season 2" / "S02" / "第二季" / "Specials" → 2 / 0. */
    fun seasonFolder(name: String): Int? {
        val n = name.trim()
        Regex("(?i)^(?:season|series)[ ._-]?(\\d{1,2})$").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("(?i)^s(\\d{1,2})$").find(n)?.let { return it.groupValues[1].toInt() }
        Regex("^第\\s*([0-9一二三四五六七八九十]+)\\s*季$").find(n)?.let { return cnNum(it.groupValues[1]) }
        if (n.equals("specials", true) || n.equals("sp", true) || n == "特别篇") return 0
        return null
    }

    /** Season number carried by a show folder name ("权力的游戏 第二季", "Show.S02.1080p") — null when none. */
    fun seasonInName(name: String): Int? {
        Regex("(?i)(?:^|[ ._\\-\\[(])s(\\d{1,2})(?![0-9e])").find(name)?.let { return it.groupValues[1].toInt() }
        Regex("(?i)season[ ._-]?(\\d{1,2})").find(name)?.let { return it.groupValues[1].toInt() }
        Regex("第\\s*([0-9一二三四五六七八九十]+)\\s*季").find(name)?.let { return cnNum(it.groupValues[1]) }
        return null
    }

    fun showTitle(folderName: String): MovieName {
        val cleaned = folderName
            .replace(Regex("(?i)[ ._\\-\\[(]*(season|series)[ ._-]?\\d{1,2}.*$"), "")
            .replace(Regex("第\\s*[0-9一二三四五六七八九十]+\\s*季.*$"), "")
            .replace(Regex("(?i)[ ._\\-\\[(]s\\d{1,2}(?:e\\d{1,4})?(?![0-9]).*$"), "")
            .replace(Regex("(?i)[ ._\\-\\[(]e[p]?\\d{1,4}(?![0-9pP]).*$"), "")
        return movie(cleaned.ifBlank { folderName })
    }

    /** "奥本海默 Oppenheimer" → ["奥本海默", "Oppenheimer", whole]: release names often carry both. */
    fun queries(t: String, alt: String = ""): List<String> {
        val cjk = Regex("[\\u3400-\\u9fff][\\u3400-\\u9fff0-9：:·\\s]*").findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val latin = t.replace(Regex("[\\u3400-\\u9fff：·]+"), " ").replace(Regex("\\s+"), " ").trim()
        // "复仇者联盟4：终局之战" → also "复仇者联盟4" (the part before a subtitle colon) as a looser query
        val head = cjk.split('：', ':').first().trim()
        return listOf(cjk, latin, alt, t, head).map { it.trim() }.filter { it.length >= 2 && !(it.length < 3 && it.all { c -> c.isDigit() }) }.distinct()
    }

    /** Normalized for similarity: lowercase, no punctuation / spaces. */
    fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}·：，。！？、“”‘’《》]"), "")

    fun similarity(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val d = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..y.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (x[i - 1] == y[j - 1]) 0 else 1)
                prev = t
            }
        }
        val lev = 1.0 - d[y.length].toDouble() / maxOf(x.length, y.length)
        val contains = if (x.contains(y) || y.contains(x)) 0.85 * minOf(x.length, y.length) / maxOf(x.length, y.length) + 0.15 else 0.0
        return maxOf(lev, contains)
    }
}

// ============================================================ NFO

data class Nfo(
    val title: String?, val original: String?, val year: Int?, val plot: String?, val rating: Float?, val tmdbId: Int?,
    val genres: List<String>, val poster: String?, val fanart: String?, val cast: List<Cast>, val runtime: Int,
    val season: Int? = null, val episode: Int? = null,
)

object NfoParser {
    fun parse(xml: String): Nfo? = runCatching {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml.substring(xml.indexOf('<').coerceAtLeast(0))))
        var title: String? = null; var orig: String? = null; var year: Int? = null; var plot: String? = null
        var rating: Float? = null; var tmdb: Int? = null; var poster: String? = null; var fanart: String? = null
        var runtime = 0; var season: Int? = null; var ep: Int? = null
        val genres = ArrayList<String>(); val cast = ArrayList<Cast>()
        val stack = ArrayList<String>()
        var uniqueType: String? = null
        var thumbAspect: String? = null
        var actorName = ""; var actorRole = ""; var actorThumb: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name.lowercase(); stack.add(n)
                    if (n == "uniqueid") uniqueType = p.getAttributeValue(null, "type")?.lowercase()
                    if (n == "thumb") thumbAspect = p.getAttributeValue(null, "aspect")?.lowercase()
                    if (n == "actor") { actorName = ""; actorRole = ""; actorThumb = null }
                }
                XmlPullParser.TEXT -> {
                    val t = p.text?.trim().orEmpty()
                    if (t.isNotEmpty() && stack.isNotEmpty()) {
                        val cur = stack.last(); val parent = stack.getOrNull(stack.size - 2)
                        when {
                            parent == "actor" -> when (cur) { "name" -> actorName = t; "role" -> actorRole = t; "thumb" -> actorThumb = t }
                            stack.size == 2 -> when (cur) {
                                "title" -> title = t
                                "originaltitle" -> orig = t
                                "year" -> year = t.take(4).toIntOrNull()
                                "premiered", "aired", "releasedate" -> if (year == null) year = t.take(4).toIntOrNull()
                                "plot", "outline" -> if (plot.isNullOrEmpty()) plot = t
                                "rating" -> rating = t.toFloatOrNull()
                                "tmdbid" -> tmdb = t.toIntOrNull()
                                "uniqueid" -> if (uniqueType == "tmdb") tmdb = t.toIntOrNull()
                                "genre" -> genres.add(t)
                                "runtime" -> runtime = t.filter { it.isDigit() }.toIntOrNull() ?: 0
                                "season" -> season = t.toIntOrNull()
                                "episode" -> ep = t.toIntOrNull()
                                "thumb" -> if (thumbAspect == null || thumbAspect == "poster") { if (poster == null) poster = t } else if (thumbAspect == "fanart" && fanart == null) fanart = t
                            }
                            cur == "value" && stack.contains("ratings") -> if (rating == null) rating = t.toFloatOrNull()
                            cur == "thumb" && parent == "fanart" -> if (fanart == null) fanart = t
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (p.name.lowercase() == "actor" && actorName.isNotEmpty() && cast.size < 20) cast.add(Cast(actorName, actorRole, actorThumb))
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                }
            }
            ev = p.next()
        }
        if (title == null && tmdb == null && plot == null) null
        else Nfo(title, orig, year, plot, rating?.takeIf { it > 0f }, tmdb, genres, poster, fanart, cast, runtime, season, ep)
    }.getOrNull()
}
