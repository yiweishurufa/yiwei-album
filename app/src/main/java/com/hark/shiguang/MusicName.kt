package com.hark.shiguang

/**
 * 1.7.32（用户 2026-11-28「自动匹配规范名，去掉『歌曲–歌手』」）：歌名的规范显示名。纯函数，无 Android 依赖（见 MusicNameTest）。
 *
 * 只改「显示用」的名字，不改文件、不改曲库里存的原始标签（Track.title 原样保留，界面用 [Track.displayTitle]）。
 *
 * 规则（按顺序）：
 * 1. 全角 ASCII（Ａ－Ｚ、０－９、（）－ 等）转半角，全角空格转半角，连续空白合一，去首尾空白；去掉误留的扩展名（.flac / .mp3）。
 * 2. 去无用括注：[FLAC] (320k) 【无损】 (官方版) (MV) (高清) (Official Video) (动态歌词) 等。
 *    有意义的括注保留：Live / 现场 / Remix / 伴奏 / Inst / Demo / Acoustic / 翻唱 / Cover / 电影原声 / feat. ……（不认识的一律保留）。
 * 3. 去音轨号前缀：「07-」「07.」「07、」「07_」「(07)」「[07]」「Track 07 -」；「07 歌名」这种只用空格分隔的，
 *    只在有前导零（07）或等于标签里的音轨号时才去（「7 Rings」「17岁」不动）；文件名（[clean] 的 fromFileName）沿用旧规则，「1 歌名」也去。数字后面紧跟数字的不动（「1999-2009」）。
 * 4. 「歌手 - 歌名」「歌名 - 歌手」拆分：哪边是已知歌手名（标签里的歌手、专辑歌手）哪边就是歌手；
 *    两边都不认识时：[fromFileName]（文件名猜的）按最常见的「歌手 - 歌名」取右边，标签里的标题保持原样（不乱拆）。
 * 5. 清掉首尾残留的分隔符、引号、书名号外的空括号。清完是空的就退回原文。
 */
object MusicName {

    data class Parsed(val title: String, val artist: String = "", val trackNo: Int = 0)

    /** 有用的版本标记（括注里出现就整段保留）。 */
    private val KEEP = Regex(
        "live|现场|演唱会|remix|mix\\b|伴奏|纯音乐|inst|instrumental|karaoke|demo|acoustic|unplugged|翻唱|cover|原唱|合唱|对唱|" +
            "feat|ft\\.|with |version|ver\\.|粤语|国语|英文|日文|韩文|电影|电视剧|主题曲|插曲|片尾|片头|ost|女声|男声|童声|钢琴|吉他|dj|remaster|重制",
        RegexOption.IGNORE_CASE)

    /** 整段括注只由这些「技术 / 来源 / 宣传」词组成时去掉。 */
    private val JUNK_WORD = Regex(
        "(?:flac|ape|wav|mp3|m4a|aac|alac|dsd|dsf|hi-?res|hires|24-?bit|16-?bit|\\d{2,3}\\s*k(?:bps)?|\\d{2,3}\\s*khz|lossless|无损|无损音质|高音质|高品质|" +
            "超高品质|母带|hq|sq|官方版|官方|正式版|完整版|高清|超清|蓝光|1080p|720p|4k|mv|mv版|official(?:\\s+(?:music\\s+)?(?:video|audio|mv|lyrics?(?:\\s+video)?))?|" +
            "music\\s+video|music|video|audio|lyrics?(?:\\s+video)?|lyric\\s+video|动态歌词|歌词版|歌词|字幕版|视频|单曲|新歌|抖音|热歌|www\\.[^\\s]+|[a-z0-9-]+\\.(?:com|cn|net))",
        RegexOption.IGNORE_CASE)
    private val JUNK_SEP = Regex("[\\s_+|/·,，、&-]+")

    private val BRACKET = Regex("[\\[【(（]([^\\[\\]【】()（）]*)[\\]】)）]")
    private val EXT = Regex("\\.(?:flac|ape|wav|mp3|m4a|aac|ogg|opus|wma|dsf|dff|wv|aiff?)$", RegexOption.IGNORE_CASE)

    /** 全角 → 半角、空白合一、去首尾。中文标点（《》「」，。）不动。 */
    fun normalizeWidth(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            sb.append(when (ch) {
                '\u3000' -> ' '
                in '\uFF01'..'\uFF5E' -> {
                    val c = ch - 0xFEE0
                    // 全角括号 / 横线 / 字母数字都转；全角逗号句号等中文常用标点保持
                    if (ch == '，' || ch == '：' || ch == '；' || ch == '！' || ch == '？') ch else c
                }
                else -> ch
            })
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun isJunkBracket(inner: String): Boolean {
        val t = inner.trim()
        if (t.isEmpty()) return true
        if (KEEP.containsMatchIn(t)) return false
        if (JUNK_WORD.matches(t) || JUNK_WORD.matches(t.lowercase())) return true
        val words = t.split(JUNK_SEP).filter { it.isNotBlank() }
        return words.isNotEmpty() && words.all { JUNK_WORD.matches(it) || JUNK_WORD.matches(it.lowercase()) }
    }

    /** 去掉无用括注（保留有意义的），以及结尾不带括号的「 FLAC」「_320k」「-无损」。 */
    fun stripJunk(s: String): String {
        var r = BRACKET.replace(s) { m -> if (isJunkBracket(m.groupValues[1])) " " else m.value }
        // 结尾裸露的技术词（只去最后连续的几段）
        repeat(3) {
            val m = Regex("[\\s_\\-]+([^\\s_\\-]+)$").find(r) ?: return@repeat
            if (JUNK_WORD.matches(m.groupValues[1]) && m.range.first > 0) r = r.substring(0, m.range.first)
        }
        return r.replace(Regex("\\s+"), " ").trim()
    }

    private val NO_BRACKET = Regex("^[\\[(（【]\\s*(\\d{1,3})\\s*[\\])）】]\\s*(.+)$")
    private val NO_SEP = Regex("^(?:track\\s*)?(\\d{1,3})\\s*[.\\-_、．)）:：]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val NO_SPACE = Regex("^(?:track\\s*)?(\\d{1,3})\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** 去音轨号前缀，返回（剩下的，音轨号；0 = 没去）。[tagTrack] 标签里的音轨号，帮助判断「7 歌名」。 */
    fun stripTrackNo(s: String, tagTrack: Int = 0, lenient: Boolean = false): Pair<String, Int> {
        NO_BRACKET.find(s)?.let { m -> return m.groupValues[2].trim() to m.groupValues[1].toInt() }
        NO_SEP.find(s)?.let { m ->
            val rest = m.groupValues[2].trim()
            // 「1999-2009」「3.14」这类数字接数字的不是音轨号
            if (rest.isNotEmpty() && !rest[0].isDigit()) return rest to m.groupValues[1].toInt()
        }
        NO_SPACE.find(s)?.let { m ->
            val d = m.groupValues[1]; val rest = m.groupValues[2].trim()
            val leadingZero = d.length >= 2 && d[0] == '0'
            val sameAsTag = tagTrack > 0 && d.toInt() == tagTrack
            val explicitTrack = s.trimStart().startsWith("track", ignoreCase = true)
            if (rest.isNotEmpty() && !rest[0].isDigit() && (leadingZero || sameAsTag || explicitTrack || lenient)) return rest to d.toInt()
        }
        return s to 0
    }

    /** 比较用：小写、去空白和标点。 */
    fun key(s: String): String = normalizeWidth(s).lowercase().replace(Regex("[\\s\\p{Punct}·・，。！？、：；“”‘’《》「」]"), "")

    private fun sameArtist(side: String, artists: Collection<String>): Boolean {
        val k = key(side)
        if (k.isEmpty()) return false
        return artists.any { a ->
            val ak = key(a)
            ak.isNotEmpty() && (ak == k || splitArtists(side).any { key(it) == ak } || splitArtists(a).any { key(it) == k })
        }
    }

    private val ART_SPLIT = Regex("\\s*(?:/|;|；|、|&|＆|,|，| feat\\.? | ft\\. | x )\\s*", RegexOption.IGNORE_CASE)
    /** 和 [Music.splitArtists] 同样的拆法（这里不依赖 Music，便于单测）。 */
    fun splitArtists(s: String): List<String> = s.split(ART_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    private val DASH = Regex("\\s+[-–—－~～]\\s+|\\s*[–—－]\\s*")
    private val PLAIN_DASH = Regex("(?<=\\p{IsHan})-(?=\\p{IsHan})")

    /**
     * 规范化一个标题。[artists] = 已知歌手（标签歌手、专辑歌手，可空）；[tagTrack] = 标签音轨号；
     * [fromFileName] = 标题是从文件名猜出来的（两边都不认识时按「歌手 - 歌名」拆）。
     */
    fun clean(raw: String, artists: Collection<String> = emptyList(), tagTrack: Int = 0, fromFileName: Boolean = false): Parsed {
        val orig = raw.trim()
        if (orig.isEmpty()) return Parsed("")
        var s = normalizeWidth(orig).replace(EXT, "").replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        s = stripJunk(s)
        var (rest, no) = stripTrackNo(s, tagTrack, lenient = fromFileName)
        s = rest
        var artist = ""
        // 「歌手 - 歌名」/「歌名 - 歌手」
        val parts = s.split(DASH, limit = 3).map { it.trim() }.filter { it.isNotEmpty() }
        val split = if (parts.size >= 2) parts else s.split(PLAIN_DASH, limit = 2).map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size == 2 }
        if (split != null && split.size >= 2) {
            val a = split[0]; val b = split.drop(1).joinToString(" - ")
            val aIs = sameArtist(a, artists); val bIs = sameArtist(b, artists)
            when {
                aIs && !bIs -> { artist = a; s = b }
                bIs && !aIs -> { artist = b; s = a }
                a.all { it.isDigit() } -> { if (no == 0) no = a.toInt(); s = b }
                !aIs && !bIs && fromFileName && split.size == 2 -> { artist = a; s = b }
            }
            // 拆完右半边可能还有音轨号（「许飞 - 07 寂寞的门」）
            val (r2, n2) = stripTrackNo(s, tagTrack, lenient = fromFileName); if (n2 > 0) { s = r2; if (no == 0) no = n2 }
        }
        s = stripJunk(s).trim(' ', '-', '–', '—', '_', '.', '·', '|', '~')
            .removeSurrounding("\"").removeSurrounding("“", "”").trim()
        if (s.isEmpty()) s = normalizeWidth(orig)
        return Parsed(s, artist, no)
    }

    /** 专辑名像合集 / 翻唱集 / 文件夹物料（不是一张真正发行的专辑）：封面可以按单曲配，不会把一张别的专辑封面铺给整个文件夹。 */
    private val COMPILATION = Regex("翻唱|合集|精选|物料|歌曲|热歌|串烧|歌单|收藏|大全|金曲|经典|榜|新歌|车载|dj|collection|best|hits|mix|playlist|cover|未知专辑|^cd\\s*\\d+$|^disc", RegexOption.IGNORE_CASE)
    fun looksCompilation(album: String): Boolean = album.isBlank() || COMPILATION.containsMatchIn(album)

    /** 标题或专辑表明这是翻唱（查歌词时允许用原唱版本，查封面时不能用原唱的专辑图）。 */
    fun looksCover(title: String, album: String): Boolean = Regex("翻唱|cover|致敬|tribute", RegexOption.IGNORE_CASE).let { it.containsMatchIn(title) || it.containsMatchIn(album) }
}
