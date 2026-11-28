package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.32 联网匹配打分（「标题 + 歌手 + 时长」，翻唱不错配原唱封面）。候选数据取自 2026-11-28 实测的网易云 / QQ 音乐搜索结果。 */
class MusicMatchTest {
    private val q = MusicMatch.Query("寂寞的门", listOf("许飞"), "许飞60首翻唱物料", 220_000)
    private val qqXufei = MusicMatch.Cand("qq", "003MMhNp2GvTXj", "寂寞的门", listOf("许飞"), "许飞翻唱电台", 220_000, "https://y.gtimg.cn/x.jpg")
    private val original = MusicMatch.Cand("netease", "34045132", "寂寞的门", listOf("姜育恒"), "两个永恒 电视主题曲全集", 276_285, "https://p1.music.126.net/a.jpg")
    private val original2 = MusicMatch.Cand("qq", "0047eB5J1Xk4Hp", "寂寞的门", listOf("姜育恒"), "两个永恒 电视主题曲全集", 276_000, "https://y.gtimg.cn/o.jpg")
    private val threshold = MusicMatch.Cand("netease", "1884728615", "寂寞的门槛", listOf("文心尖兵"), "错过的遗憾", 63_201, "https://p1.music.126.net/b.jpg")
    private val noise = MusicMatch.Cand("netease", "327922", "替身", listOf("周蕙"), "周蕙精选2", 345_933, "https://p1.music.126.net/c.jpg")

    @Test fun `同歌手同名同时长是最好的`() {
        val s = MusicMatch.score(q, qqXufei)!!
        assertTrue(s.artistMatch); assertTrue(s.titleExact); assertEquals(0L, s.durationDiffMs)
        assertTrue(s.score >= MusicMatch.META_MIN)
        assertEquals(qqXufei, MusicMatch.bestForMeta(q, listOf(noise, threshold, original, qqXufei))!!.cand)
    }

    @Test fun `歌名对不上的直接不要`() {
        assertNull(MusicMatch.score(q, noise))
        // 「寂寞的门槛」不是「寂寞的门」：就算歌手和时长都对也不采用
        val fake = threshold.copy(artists = listOf("许飞"), durationMs = 220_000, album = "许飞60首翻唱物料")
        assertNull(MusicMatch.bestForMeta(q, listOf(threshold, fake)))
        assertNull(MusicMatch.bestForLyrics(q, listOf(threshold, fake)))
    }

    @Test fun `只有原唱时不拿来补信息和封面`() {
        assertNull(MusicMatch.bestForMeta(q, listOf(original, original2, noise)))
    }

    @Test fun `翻唱的歌词可以用原唱的但时长差太多不滚动`() {
        val s = MusicMatch.bestForLyrics(q, listOf(original, noise))!!
        assertEquals(original, s.cand)
        assertFalse(s.artistMatch)
        assertFalse(MusicMatch.keepSync(s))       // 差 56 秒：只给纯文本
        val same = MusicMatch.bestForLyrics(q, listOf(original, qqXufei))!!
        assertEquals(qqXufei, same.cand)          // 有本人版本优先
        assertTrue(MusicMatch.keepSync(same))
        val close = original.copy(durationMs = 221_500)
        assertTrue(MusicMatch.keepSync(MusicMatch.bestForLyrics(q, listOf(close))!!))   // 别人唱的，但时长差 1.5 秒：保留时间轴
    }

    @Test fun `时长差超过 5 秒降权`() {
        val near = MusicMatch.score(q, qqXufei.copy(durationMs = 224_000))!!
        val far = MusicMatch.score(q, qqXufei.copy(durationMs = 240_000))!!
        val exact = MusicMatch.score(q, qqXufei)!!
        assertTrue(exact.score > near.score); assertTrue(near.score > far.score)
        assertTrue(MusicMatch.durationScore(220_000, 226_000) < 0)
        assertEquals(0.0, MusicMatch.durationScore(0, 226_000), 0.0)
    }

    @Test fun `版本不一致扣分 Live 不配录音室`() {
        val ql = q.copy(title = "寂寞的门 (Live)")
        val studio = MusicMatch.score(ql, qqXufei)!!
        val live = MusicMatch.score(ql, qqXufei.copy(title = "寂寞的门 (Live)"))!!
        assertTrue(live.score > studio.score + 0.3)
        assertNull(MusicMatch.bestForLyrics(ql, listOf(qqXufei)))     // 歌词不跨版本
        assertNull(MusicMatch.bestForLyrics(q.copy(title = "寂寞的门 (伴奏)"), listOf(qqXufei)))
    }

    @Test fun `封面取舍 翻唱集按单曲 真专辑要同名 原唱不用`() {
        val hit = MusicMatch.score(q, qqXufei)!!
        assertEquals(MusicMatch.CoverUse.TRACK, MusicMatch.coverUse(q, hit))          // 本地是「翻唱物料」合集
        val realAlbum = q.copy(album = "爱如空气")
        assertEquals(MusicMatch.CoverUse.NONE, MusicMatch.coverUse(realAlbum, MusicMatch.score(realAlbum, qqXufei)!!))   // 不把别的专辑封面给这张
        val sameAlbum = q.copy(album = "许飞翻唱电台")
        assertEquals(MusicMatch.CoverUse.ALBUM, MusicMatch.coverUse(sameAlbum, MusicMatch.score(sameAlbum, qqXufei)!!))
        assertEquals(MusicMatch.CoverUse.NONE, MusicMatch.coverUse(q, MusicMatch.score(q, original)!!))   // 原唱的专辑图绝不用
    }

    @Test fun `不知道歌手时要歌名一致且时长很准`() {
        val qa = MusicMatch.Query("寂寞的门", emptyList(), "", 276_000)
        assertNotNull(MusicMatch.bestForMeta(qa, listOf(original)))
        assertNull(MusicMatch.bestForMeta(qa.copy(durationMs = 220_000), listOf(original)))
        assertNull(MusicMatch.bestForMeta(qa.copy(durationMs = 0), listOf(original)))
    }

    @Test fun `清洗过的歌名才能对上`() {
        // 修之前用「07-寂寞的门」去搜：去标点后成了「07寂寞的门」，对不上
        val dirty = q.copy(title = "07-寂寞的门")
        assertNull(MusicMatch.bestForMeta(dirty, listOf(qqXufei)))
        val clean = q.copy(title = MusicName.clean("07-寂寞的门", listOf("许飞")).title)
        assertEquals(qqXufei, MusicMatch.bestForMeta(clean, listOf(qqXufei))!!.cand)
    }

    @Test fun `歌手写法不同也算`() {
        assertTrue(MusicMatch.artistMatch(listOf("周杰伦"), listOf("周杰伦", "方文山")))
        assertTrue(MusicMatch.artistMatch(listOf("许飞/郁可唯"), listOf("郁可唯")))
        assertTrue(MusicMatch.artistMatch(listOf("Ｊａｙ Ｃｈｏｕ"), listOf("Jay Chou")))
        assertFalse(MusicMatch.artistMatch(listOf("许飞"), listOf("姜育恒")))
        assertFalse(MusicMatch.artistMatch(listOf("未知歌手"), listOf("未知歌手")))
    }

    @Test fun `纯文本歌词去时间轴和标签`() {
        val lrc = "[ti:寂寞的门]\n[ar:许飞]\n[00:19.14]世人都道朱门深\n[00:26.28]\n[00:28.15]一抹斜阳暗销魂"
        assertEquals("世人都道朱门深\n\n一抹斜阳暗销魂", MusicMatch.toPlain(lrc))
        assertTrue(MusicMatch.isPlaceholderLyrics("[00:00.00]纯音乐，请欣赏"))
        assertFalse(MusicMatch.isPlaceholderLyrics(lrc))
    }

    @Test fun `QQ 音乐歌词实体还原`() {
        assertEquals("[00:01.00]A&B 'c'", MusicOnline.unescape("&#91;00&#58;01&#46;00&#93;A&amp;B &apos;c&apos;"))
    }
}
