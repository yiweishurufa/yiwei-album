package com.hark.shiguang

/**
 * 1.7.33（用户 2026-11-28 截图：歌手页出现「01张杰」…「07张杰」「08素颜」「(2002)许巍」「(未知Artist)」「-柒蕊zero-」「1376心想事成」）：
 * 歌手名 / 专辑名的规范显示。纯函数，无 Android 依赖（见 MusicArtistTest）。和 [MusicName]（歌名）一样只改显示，不改文件、
 * 不改 library.json 里存的原始标签。
 *
 * 单个名字（[clean]）：
 * 1. 全角转半角、空白合一（[MusicName.normalizeWidth]）。
 * 2. 「未知艺术家 / 未知歌手 / Unknown Artist / (未知Artist) / <unknown> / 未知」→ [UNKNOWN]。
 * 3. 年份前缀 / 后缀：「(2002)许巍」「[2002]许巍」「2002-许巍」「许巍(2002)」「许巍 - 2002」。只认 1900–2099。
 * 4. 编号前缀：「(01)」「[01]」「01-」「01.」「01、」「01 」，以及紧贴着的补零编号「01张杰」（有前导零才去，「10cc」「5566」不动）。
 *    不补零的「12张杰」「1376心想事成」这种，只有去掉数字后正好是曲库里已知的歌手才去（[resolve]），单靠一个名字判断不了。
 * 5. 首尾装饰符号：- _ ~ * # = + | · • 等（「-柒蕊zero-」→「柒蕊zero」）。单个前导点保留：「.K」是网易云上真实存在的歌手名
 *    （有《晚安（夜雨）》等歌），去掉会变成另一个人「K」；两个以上的点（「..xx」）才当装饰去掉。
 * 6. 整个名字被一对括号 / 引号包着（「(许巍)」「“许巍”」）时去掉这对括号。清完是空的就退回原文。
 *
 * 多个歌手（[split]）：按 / ; 、 & , feat. ft. featuring x 拆开，各自清洗，去重（大小写、空格、标点不同算同一个）；
 * 有真名时丢掉混在里面的「未知」。
 *
 * 整个曲库（[index]）：同名不同写法合并成一个显示名（出现最多的写法）；「NN + 名字」去编号后是已知歌手时合并过去；
 * 歌手栏写的其实是「编号 + 歌名」（文件名「08素颜 - 许嵩」被按「歌手 - 歌名」拆反了）时两边对调。
 */
object MusicArtist {

    const val UNKNOWN = "未知歌手"
    const val UNKNOWN_ALBUM = "未知专辑"

    /** 比较 / 合并用的键：小写、去空白和标点（「Jay Chou」「jay  chou」「-柒蕊zero-」「柒蕊zero」各自同键）。 */
    fun key(s: String): String = MusicName.key(s).ifEmpty { MusicName.normalizeWidth(s).lowercase() }

    private val UNKNOWN_KEYS = setOf(
        "", "未知", "未知歌手", "未知艺术家", "未知艺人", "未知演唱者", "未知歌手名", "未知artist", "未知artists",
        "unknown", "unknow", "unknownartist", "unknownartists", "artist", "null", "none", "无", "不详", "noartist",
    )

    /** 「未知艺术家」一类占位（含「(未知Artist)」「<unknown>」「Unknown Artist」）。空字符串也算。 */
    fun isUnknown(s: String): Boolean = key(s) in UNKNOWN_KEYS

    private val YEAR = "(?:19|20)\\d{2}"
    private val YEAR_BRACKET_HEAD = Regex("^[\\[(【]\\s*$YEAR\\s*[\\])】]\\s*(.+)$")
    private val YEAR_SEP_HEAD = Regex("^$YEAR\\s*[-._、:]\\s*(\\D.*)$")
    private val YEAR_BRACKET_TAIL = Regex("^(.+?)\\s*[\\[(【]\\s*$YEAR\\s*[\\])】]$")
    private val YEAR_SEP_TAIL = Regex("^(.*\\D)\\s*[-_]\\s*$YEAR$")

    private val NO_BRACKET = Regex("^[\\[(【]\\s*\\d{1,3}\\s*[\\])】]\\s*(.+)$")
    private val NO_SEP = Regex("^\\d{1,3}\\s*[-._、:)]\\s*(\\D.*)$")
    private val NO_ZERO = Regex("^0\\d{1,2}\\s*(\\D.*)$")
    /** 任意 1–4 位数字前缀（含分隔符），只在 [resolve] 里配合已知歌手用。 */
    private val NO_ANY = Regex("^\\d{1,4}\\s*[-._、:)]?\\s*(\\D.*)$")
    /** 3–4 位数字紧贴汉字（「1376心想事成」）：几乎不会是歌手名，多半是「编号 / 数字开头的歌名」。 */
    private val DIGITS_HAN = Regex("^\\d{3,4}\\p{IsHan}")

    private const val DECOR = " -_~*#=+|·•・—–"
    private val WRAP = listOf("(" to ")", "[" to "]", "【" to "】", "“" to "”", "\"" to "\"", "'" to "'", "《" to "》", "「" to "」", "<" to ">")

    private fun stripYear(s: String): String {
        YEAR_BRACKET_HEAD.find(s)?.let { return it.groupValues[1].trim() }
        YEAR_SEP_HEAD.find(s)?.let { return it.groupValues[1].trim() }
        YEAR_BRACKET_TAIL.find(s)?.let { return it.groupValues[1].trim() }
        YEAR_SEP_TAIL.find(s)?.let { return it.groupValues[1].trim() }
        return s
    }

    /** 去明确的编号前缀（括号、带分隔符、补零紧贴）。 */
    private fun stripNo(s: String): String {
        NO_BRACKET.find(s)?.let { return it.groupValues[1].trim() }
        NO_SEP.find(s)?.let { return it.groupValues[1].trim() }
        NO_ZERO.find(s)?.let { return it.groupValues[1].trim() }
        return s
    }

    private fun stripDecor(s0: String): String {
        var s = s0.trim { it in DECOR }
        if (s.startsWith("..")) s = s.trimStart('.', ' ')
        for ((a, b) in WRAP) if (s.length > a.length + b.length && s.startsWith(a) && s.endsWith(b)) {
            val inner = s.substring(a.length, s.length - b.length)
            // 只去「整体包着」的一对：「(未知Artist)」→ 里面；「(A)B(C)」这种不是一对，不动
            if (!inner.contains(a) && !inner.contains(b)) s = inner.trim { it in DECOR }
        }
        return s
    }

    /** 单个歌手名的规范显示名（规则见文件头）。原文为空返回空串；占位返回 [UNKNOWN]。 */
    fun clean(raw: String): String {
        val orig = MusicName.normalizeWidth(raw)
        if (orig.isEmpty()) return ""
        if (isUnknown(orig)) return UNKNOWN
        var s = orig
        repeat(3) {
            val before = s
            s = stripDecor(stripNo(stripYear(stripDecor(s))))
            if (s.isEmpty()) return if (isUnknown(before)) UNKNOWN else stripDecor(orig).ifEmpty { orig }
            if (s == before) return@repeat
        }
        return if (isUnknown(s)) UNKNOWN else s
    }

    /** 专辑名：同样去年份、编号、首尾装饰；「未知专辑 / Unknown Album」→ 空串（调用方显示「未知专辑」或按文件夹归）。 */
    fun cleanAlbum(raw: String): String {
        val orig = MusicName.normalizeWidth(raw)
        if (orig.isEmpty()) return ""
        val k = key(orig)
        if (k in setOf("未知专辑", "未知唱片", "unknownalbum", "unknown", "未知", "null", "none", "album", "<unknown>")) return ""
        var s = orig
        repeat(3) {
            val before = s
            s = stripDecor(stripNo(stripYear(stripDecor(s))))
            if (s.isEmpty()) return stripDecor(orig).ifEmpty { orig }
            if (s == before) return@repeat
        }
        return s
    }

    private val SPLIT = Regex("\\s*(?:/|;|、|&|,|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s+x\\s+|\\s+×\\s+)\\s*", RegexOption.IGNORE_CASE)

    /** 不清洗、只按分隔符拆（全角已转半角）。 */
    fun rawParts(raw: String): List<String> = MusicName.normalizeWidth(raw).replace('；', ';').replace('，', ',').split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

    /** 多歌手拆开并各自清洗、去重；没有名字时是 [UNKNOWN]。 */
    fun split(raw: String): List<String> = dedupe(rawParts(raw).map { clean(it) })

    private fun dedupe(l: List<String>): List<String> {
        val real = l.filter { it.isNotEmpty() && it != UNKNOWN }.distinctBy { key(it) }
        return real.ifEmpty { listOf(UNKNOWN) }
    }

    /** 名字开头像「编号」（括号 / 分隔符 / 补零紧贴 / 3–4 位数字紧贴汉字）。年份前缀不算。 */
    fun numberPrefixed(raw: String): Boolean {
        val s = stripDecor(MusicName.normalizeWidth(raw))
        if (YEAR_BRACKET_HEAD.matches(s) || YEAR_SEP_HEAD.matches(s)) return false
        return NO_BRACKET.matches(s) || NO_SEP.matches(s) || NO_ZERO.matches(s) || DIGITS_HAN.containsMatchIn(s)
    }

    /** 以 1–4 位数字开头、后面还有别的（「12张杰」「01张杰」「1376心想事成」；「5566」「1976」不算）。年份前缀不算。 */
    fun digitLed(raw: String): Boolean {
        val s = stripDecor(MusicName.normalizeWidth(raw))
        if (YEAR_BRACKET_HEAD.matches(s) || YEAR_SEP_HEAD.matches(s)) return false
        return numberPrefixed(s) || NO_ANY.matches(s)
    }

    /** 去掉任意 1–4 位数字前缀（「12张杰」→「张杰」）后清洗。 */
    fun loose(raw: String): String {
        val s = stripDecor(MusicName.normalizeWidth(raw))
        val m = NO_ANY.find(s) ?: return clean(s)
        return clean(m.groupValues[1])
    }

    /** 对调时，原来歌手栏里的「08素颜」变回歌名：只去补零编号 / 括号 / 带分隔符的编号，「1376心想事成」整个保留（这就是歌名）。 */
    fun titleFromArtistSlot(raw: String): String = stripDecor(stripNo(stripDecor(MusicName.normalizeWidth(raw)))).ifEmpty { raw.trim() }

    // ------------------------------------------------------------------ 排序

    /** 0 = 汉字 / 字母开头（按拼音 / 字母排）；1 = 只有数字 / 符号开头；2 = 未知歌手（永远最后）。 */
    fun sortRank(name: String): Int = when {
        name == UNKNOWN || name == UNKNOWN_ALBUM || isUnknown(name) -> 2
        sortText(name).firstOrNull()?.let { Character.isLetter(it) } == true -> 0
        else -> 1
    }

    /** 排序用的文字：去掉开头的符号（「.K」按 K 排、「-柒蕊zero-」按「柒」排），数字留着。 */
    fun sortText(name: String): String = name.trimStart { !Character.isLetterOrDigit(it) }.ifEmpty { name }

    // ------------------------------------------------------------------ 整个曲库

    /** 一首歌的原始信息（[Track] 的子集，便于单测）。[onlineArtist]：高分联网匹配给的歌手（可空）。 */
    data class In(
        val id: String,
        val artist: String,
        val albumArtist: String = "",
        val parsedArtist: String = "",
        val title: String = "",
        val tagged: Boolean = false,
        val onlineArtist: String = "",
    ) {
        val rawArtist: String get() = artist.ifBlank { albumArtist }.ifBlank { parsedArtist }
        /** 用来判断缓存的结果是否还对应这首歌现在的信息。 */
        val sig: Int by lazy { (artist + "\u0001" + albumArtist + "\u0001" + parsedArtist + "\u0001" + title + "\u0001" + onlineArtist).hashCode() }
    }

    /** 一首歌的显示结果：[artists] 显示歌手（至少一个）；[title] 非空时表示歌名也要换（歌手栏和歌名栏对调了）。 */
    data class Fix(val artists: List<String>, val title: String? = null, val sig: Int = 0) {
        val primary: String get() = artists.first()
    }

    class Index(val fixes: Map<String, Fix>, val names: Map<String, String>)

    /**
     * 单首解析。[known]：曲库里可信的歌手键（见 [index]）。
     * - 歌手栏是占位 / 空：用联网歌手，没有就是 [UNKNOWN]。
     * - 某一段带编号前缀：去编号后是已知歌手 → 用它（「12张杰」→「张杰」）；
     *   只有这一段、歌名栏不带编号且不像占位、去编号后的名字不是已知歌手 → 两边对调（「08素颜」/「许嵩」→ 歌手「许嵩」、歌名「素颜」）。
     */
    fun resolve(i: In, known: Set<String> = emptySet()): Fix {
        val raw = i.rawArtist
        val online = i.onlineArtist.takeIf { it.isNotBlank() && !isUnknown(it) }
        if (raw.isBlank() || isUnknown(raw)) return Fix(online?.let { split(it) } ?: listOf(UNKNOWN), null, i.sig)
        val parts = rawParts(raw)
        if (parts.size == 1 && digitLed(parts[0])) {
            val p = parts[0]
            val l = loose(p)
            val title = MusicName.normalizeWidth(i.title)
            if (l.isNotEmpty() && key(l) in known) return Fix(listOf(l), null, i.sig)
            if (online != null && numberPrefixed(p)) return Fix(split(online), null, i.sig)
            if (numberPrefixed(p) && title.isNotEmpty() && !isUnknown(title) && !numberPrefixed(title) && split(title).first() != UNKNOWN) {
                return Fix(split(title), titleFromArtistSlot(p), i.sig)
            }
        }
        val out = parts.map { p ->
            val c = clean(p)
            // 「12张杰」这种不补零的：去掉数字后正好是已知歌手才合并过去
            if (digitLed(p)) loose(p).takeIf { it.isNotEmpty() && key(it) in known } ?: c else c
        }
        return Fix(dedupe(out), null, i.sig)
    }

    /**
     * 整个曲库一起解析：
     * 1. 可信歌手键 = 有标签且不带编号的歌手名 + 联网歌手 + 去编号后出现两次以上的名字（「01张杰」…「07张杰」→「张杰」）。
     * 2. 逐首 [resolve]。
     * 3. 同键的各种写法选出现最多的一个作显示名（一样多时取先出现的），所有歌都用它。
     */
    fun index(list: List<In>): Index {
        val known = HashSet<String>()
        val looseCount = HashMap<String, Int>()
        for (i in list) {
            if (i.onlineArtist.isNotBlank()) split(i.onlineArtist).forEach { if (it != UNKNOWN) known.add(key(it)) }
            val raw = i.rawArtist
            if (raw.isBlank() || isUnknown(raw)) continue
            for (p in rawParts(raw)) {
                if (digitLed(p)) { val l = loose(p); if (l.isNotEmpty() && l != UNKNOWN) looseCount.merge(key(l), 1, Int::plus) }
                else {
                    val c = clean(p)
                    if (c.isEmpty() || c == UNKNOWN) continue
                    looseCount.merge(key(c), 1, Int::plus)
                    if (i.tagged || i.artist.isNotBlank()) known.add(key(c))
                }
            }
        }
        looseCount.forEach { (k, n) -> if (n >= 2) known.add(k) }
        val fixes = HashMap<String, Fix>(list.size * 2)
        val spell = HashMap<String, LinkedHashMap<String, Int>>()
        for (i in list) {
            val f = resolve(i, known)
            fixes[i.id] = f
            f.artists.forEach { a -> spell.getOrPut(key(a)) { LinkedHashMap() }.merge(a, 1, Int::plus) }
        }
        val names = spell.mapValues { (_, m) -> m.entries.maxByOrNull { it.value }!!.key }
        // 统一写法（「Jay Chou」「jay chou」→ 出现多的那个）
        val unified = fixes.mapValues { (_, f) -> f.copy(artists = f.artists.map { names[key(it)] ?: it }.distinctBy { key(it) }) }
        return Index(unified, names)
    }
}
