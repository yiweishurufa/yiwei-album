package com.hark.shiguang

import kotlin.math.abs

/**
 * 1.7.32：联网匹配的打分与取舍规则。纯函数，无 Android / 网络依赖（见 MusicMatchTest）。
 *
 * 分数 = 歌名（0–1，对不上直接不要）+ 歌手（对上 +0.35 / 对不上 −0.3 / 不知道 0）+ 时长（差 ≤2 秒 +0.25，≤5 秒 +0.1，
 * 超过 5 秒开始扣，30 秒以上扣 0.45）+ 专辑同名 +0.1 − 版本不一致（Live / 伴奏 / Remix 一边有一边没有）。
 *
 * 用途不同门槛不同：
 * - 补信息和封面（[META_MIN]）：歌名 + 歌手都要对上（或者不知道歌手时歌名 + 时长都很准）。
 * - 歌词（[LYRIC_MIN]）：允许是别的歌手唱的同一首歌（翻唱用原唱的歌词），但歌名必须完全一致、版本一致；
 *   时长差超过 [SYNC_TOLERANCE_MS] 时只用纯文本（去掉时间轴），不然滚动歌词会和这首翻唱对不上拍。
 */
object MusicMatch {

    data class Query(val title: String, val artists: List<String>, val album: String = "", val durationMs: Long = 0)

    data class Cand(
        val source: String,        // "netease" / "qq" / "lrclib"
        val id: String,            // 网易云歌曲 id / QQ songmid / LRCLIB id
        val title: String,
        val artists: List<String>,
        val album: String = "",
        val durationMs: Long = 0,
        val cover: String? = null, // 专辑封面地址（已经是大图尺寸）
        val year: Int = 0,
    )

    data class Scored(val cand: Cand, val score: Double, val titleExact: Boolean, val artistMatch: Boolean, val durationDiffMs: Long)

    const val META_MIN = 1.3
    const val LYRIC_MIN = 0.95
    const val SYNC_TOLERANCE_MS = 3000L

    private val VERSION_TAGS = listOf(
        Regex("live|现场|演唱会", RegexOption.IGNORE_CASE),
        Regex("伴奏|纯音乐|inst|instrumental|karaoke|off ?vocal", RegexOption.IGNORE_CASE),
        Regex("remix|dj|mix\\b", RegexOption.IGNORE_CASE),
        Regex("demo", RegexOption.IGNORE_CASE),
    )

    private val BRACKETS = Regex("[(（\\[【].*?[)）\\]】]")

    /** 去掉括注后的比较键（「寂寞的门 (Live)」→「寂寞的门」）。 */
    fun core(s: String): String = MusicName.key(BRACKETS.replace(MusicName.normalizeWidth(s), ""))

    /** 字符二元组 Dice 相似度 0–1。 */
    fun dice(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val x = a.windowed(2).groupingBy { it }.eachCount().toMutableMap()
        var hit = 0
        b.windowed(2).forEach { g -> val n = x[g] ?: 0; if (n > 0) { hit++; x[g] = n - 1 } }
        return 2.0 * hit / (a.length - 1 + b.length - 1)
    }

    /** 歌名得分：完全一致 1；去括注一致 0.9；一方包含另一方 0.5；相似度 ≥ 0.75 按比例；否则 0（不要）。 */
    fun titleScore(q: String, c: String): Double {
        val a = MusicName.key(q); val b = MusicName.key(c)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val ca = core(q); val cb = core(c)
        if (ca.isNotEmpty() && ca == cb) return 0.9
        if (ca.length >= 2 && cb.length >= 2 && (ca.contains(cb) || cb.contains(ca))) {
            // 「寂寞」不能匹配「寂寞的门槛」：短的那个至少占长的 60%
            val r = minOf(ca.length, cb.length).toDouble() / maxOf(ca.length, cb.length)
            // 包含关系（「寂寞的门」/「寂寞的门槛」）只给 0.5：就算歌手、时长、专辑全对也到不了补信息的门槛，歌词也要求歌名完全一致
            return if (r >= 0.6) 0.5 else 0.0
        }
        val d = dice(ca, cb)
        return if (d >= 0.75) d * 0.7 else 0.0
    }

    fun artistMatch(q: List<String>, c: List<String>): Boolean {
        val qs = q.flatMap { MusicName.splitArtists(it) }.map { MusicName.key(it) }.filter { it.isNotEmpty() && it != MusicName.key("未知歌手") }
        val cs = c.flatMap { MusicName.splitArtists(it) }.map { MusicName.key(it) }.filter { it.isNotEmpty() }
        return qs.any { a -> cs.any { b -> a == b || (a.length >= 2 && b.length >= 2 && (a.contains(b) || b.contains(a))) } }
    }

    private fun knownArtists(q: Query) = q.artists.filter { it.isNotBlank() && it != "未知歌手" }

    private fun versionMismatch(a: String, b: String): Int = VERSION_TAGS.count { it.containsMatchIn(a) != it.containsMatchIn(b) }

    fun durationScore(qMs: Long, cMs: Long): Double {
        if (qMs <= 0 || cMs <= 0) return 0.0
        val d = abs(qMs - cMs)
        return when {
            d <= 2000 -> 0.25
            d <= 5000 -> 0.1
            d <= 15000 -> -0.15
            d <= 30000 -> -0.3
            else -> -0.45
        }
    }

    fun score(q: Query, c: Cand): Scored? {
        val ts = titleScore(q.title, c.title)
        if (ts <= 0.0) return null
        val known = knownArtists(q)
        val am = known.isNotEmpty() && artistMatch(known, c.artists)
        var s = ts
        s += when { known.isEmpty() -> 0.0; am -> 0.35; else -> -0.3 }
        s += durationScore(q.durationMs, c.durationMs)
        if (q.album.isNotBlank() && c.album.isNotBlank() && MusicName.key(q.album) == MusicName.key(c.album)) s += 0.1
        s -= 0.35 * versionMismatch(q.title, c.title)
        val dd = if (q.durationMs > 0 && c.durationMs > 0) abs(q.durationMs - c.durationMs) else -1L
        return Scored(c, s, ts >= 0.9, am, dd)
    }

    /** 补信息 / 封面用：歌手必须对上（不知道歌手时要求歌名完全一致且时长很准）。 */
    fun bestForMeta(q: Query, cands: List<Cand>): Scored? {
        val known = knownArtists(q).isNotEmpty()
        return cands.mapNotNull { score(q, it) }
            .filter { if (known) it.artistMatch && it.score >= META_MIN else it.titleExact && it.durationDiffMs in 0..2000 && it.score >= 1.1 }
            .maxByOrNull { it.score }
    }

    /**
     * 歌词用：先要同一歌手的版本；没有就接受别的歌手唱的同名同版本歌（翻唱 → 原唱歌词），但这时只有时长差在
     * [SYNC_TOLERANCE_MS] 以内才保留时间轴（见 [keepSync]）。
     */
    fun bestForLyrics(q: Query, cands: List<Cand>): Scored? {
        val all = cands.mapNotNull { score(q, it) }.filter { it.titleExact && versionMismatch(q.title, it.cand.title) == 0 }
        all.filter { it.artistMatch && it.score >= LYRIC_MIN }.maxByOrNull { it.score }?.let { return it }
        // 别人的版本：歌名一致就行，按时长接近程度排；时长差太多（> 60 秒，多半是另一首同名歌）不要
        return all.filter { it.durationDiffMs < 0 || it.durationDiffMs <= 60_000 }
            .maxByOrNull { it.score + if (it.durationDiffMs in 0..SYNC_TOLERANCE_MS) 0.3 else 0.0 }
    }

    /** 这条歌词能不能按时间轴滚动：同一歌手且时长差 ≤5 秒，或别的歌手但时长差 ≤3 秒。不知道时长时只信同一歌手。 */
    fun keepSync(s: Scored): Boolean = when {
        s.durationDiffMs < 0 -> s.artistMatch
        s.artistMatch -> s.durationDiffMs <= 5000
        else -> s.durationDiffMs <= SYNC_TOLERANCE_MS
    }

    /**
     * 封面取舍（翻唱专辑不错配原唱的专辑图）：
     * - 命中的是别的歌手的版本 → 不用它的封面（返回 false），调用方改用歌手照片或保持占位。
     * - 同一歌手，且命中的专辑和本地专辑同名 → 用，并可以给整张专辑共用。
     * - 同一歌手，本地专辑像合集 / 翻唱集 / 没有专辑 → 只给这一首用（每首配自己发行版的封面）。
     * - 同一歌手，但本地是一张真正的、名字不同的专辑 → 不用（免得把别的专辑封面当成这张的），调用方用歌手照片。
     */
    enum class CoverUse { ALBUM, TRACK, NONE }

    fun coverUse(q: Query, s: Scored): CoverUse {
        if (s.cand.cover.isNullOrBlank() || !s.artistMatch) return CoverUse.NONE
        if (q.album.isNotBlank() && s.cand.album.isNotBlank() && core(q.album) == core(s.cand.album)) return CoverUse.ALBUM
        return if (MusicName.looksCompilation(q.album)) CoverUse.TRACK else CoverUse.NONE
    }

    /** 去掉 LRC 时间轴和 [ti:] 一类标签，变成纯文本歌词。 */
    fun toPlain(lrc: String): String = lrc.lineSequence()
        .map { it.replace(Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?]"), "").replace(Regex("<\\d+:\\d+(?:\\.\\d+)?>"), "") }
        .filter { l -> !Regex("^\\s*\\[[a-zA-Z]+:.*]\\s*$").matches(l) }
        .map { it.trim() }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    /** 歌词里是不是只有「纯音乐，请欣赏」之类的占位。 */
    fun isPlaceholderLyrics(s: String): Boolean = s.isBlank() || s.contains("纯音乐，请欣赏") || s.contains("暂无歌词") || s.contains("此歌曲为没有填词的纯音乐")
}
