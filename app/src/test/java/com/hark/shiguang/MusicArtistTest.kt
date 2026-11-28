package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.33（用户 2026-11-28 截图，音乐页「歌手」）：歌手名 / 专辑名规范显示。截图里的每个样例都在这里。 */
class MusicArtistTest {

    private fun i(id: String, artist: String, title: String = "歌", tagged: Boolean = false, online: String = "", albumArtist: String = "") =
        MusicArtist.In(id, artist, albumArtist, "", title, tagged, online)

    // ---------------------------------------------------------------- 截图里的样例

    @Test fun `01张杰 到 07张杰 合并成一个张杰`() {
        val l = (1..7).map { i("z$it", "0${it}张杰", "歌$it") }
        val idx = MusicArtist.index(l)
        (1..7).forEach { assertEquals(listOf("张杰"), idx.fixes["z$it"]!!.artists) }
        assertNull(idx.fixes["z1"]!!.title)   // 歌名不动
        // 单独一首也能去掉补零编号
        assertEquals("张杰", MusicArtist.clean("01张杰"))
    }

    @Test fun `08素颜 是歌名被当成歌手 对调`() {
        val f = MusicArtist.resolve(i("s", "08素颜", "许嵩"))
        assertEquals(listOf("许嵩"), f.artists)
        assertEquals("素颜", f.title)
    }

    @Test fun `08素颜 和张杰的歌在同一个曲库 仍然对调`() {
        val l = (1..7).map { i("z$it", "0${it}张杰", "歌$it") } + i("s", "08素颜", "许嵩")
        val f = MusicArtist.index(l).fixes["s"]!!
        assertEquals(listOf("许嵩"), f.artists)
        assertEquals("素颜", f.title)
    }

    @Test fun `1376心想事成 对调 歌名保留数字`() {
        // 网易云上这首歌就叫「1376心想事成」（理塘丁真 / ANU），数字是歌名的一部分
        val f = MusicArtist.resolve(i("x", "1376心想事成", "理塘丁真"))
        assertEquals(listOf("理塘丁真"), f.artists)
        assertEquals("1376心想事成", f.title)
        assertTrue(MusicArtist.numberPrefixed("1376心想事成"))
    }

    @Test fun `年份前缀 2002 许巍`() {
        assertEquals("许巍", MusicArtist.clean("(2002)许巍"))
        assertEquals("许巍", MusicArtist.clean("（2002）许巍"))   // 全角括号
        assertEquals("许巍", MusicArtist.clean("[2002]许巍"))
        assertEquals("许巍", MusicArtist.clean("2002-许巍"))
        assertEquals("许巍", MusicArtist.clean("许巍(2002)"))
        assertEquals("许巍", MusicArtist.clean("许巍 - 2002"))
        assertFalse(MusicArtist.numberPrefixed("(2002)许巍"))   // 年份不是编号，不会触发对调
        assertEquals(listOf("许巍"), MusicArtist.resolve(i("w", "(2002)许巍", "曾经的你")).artists)
    }

    @Test fun `未知Artist 统一为未知歌手`() {
        listOf("(未知Artist)", "未知艺术家", "Unknown Artist", "unknown", "<unknown>", "未知", "未知歌手", "UNKNOWN ARTIST", "  ").forEach {
            assertTrue(it, MusicArtist.isUnknown(it))
        }
        assertEquals(MusicArtist.UNKNOWN, MusicArtist.clean("(未知Artist)"))
        assertEquals(listOf(MusicArtist.UNKNOWN), MusicArtist.resolve(i("u", "(未知Artist)", "某首歌")).artists)
        assertFalse(MusicArtist.isUnknown("许巍"))
        assertFalse(MusicArtist.isUnknown("群星"))
    }

    @Test fun `首尾装饰符 柒蕊zero`() {
        assertEquals("柒蕊zero", MusicArtist.clean("-柒蕊zero-"))
        assertEquals("柒蕊zero", MusicArtist.clean("~~柒蕊zero~~"))
        assertEquals("柒蕊zero", MusicArtist.clean("_柒蕊zero_"))
        // 合并键相同：网易云上的写法「-柒蕊zero-」仍能对上同一位
        assertEquals(MusicArtist.key("-柒蕊zero-"), MusicArtist.key("柒蕊zero"))
    }

    @Test fun `点K 保留前导点`() {
        // 「.K」是网易云上真实存在的歌手，去掉点会变成另一位「K」
        assertEquals(".K", MusicArtist.clean(".K"))
        assertEquals(listOf(".K"), MusicArtist.resolve(i("k", ".K", "晚安（夜雨）")).artists)
        assertEquals("xx", MusicArtist.clean("..xx"))
    }

    // ---------------------------------------------------------------- 其它规则

    @Test fun `各种编号前缀`() {
        assertEquals("张杰", MusicArtist.clean("01-张杰"))
        assertEquals("张杰", MusicArtist.clean("01.张杰"))
        assertEquals("张杰", MusicArtist.clean("(01)张杰"))
        assertEquals("张杰", MusicArtist.clean("[01] 张杰"))
        assertEquals("张杰", MusicArtist.clean("01 张杰"))
        assertEquals("张杰", MusicArtist.clean("01、张杰"))
    }

    @Test fun `数字开头的真名不动`() {
        assertEquals("5566", MusicArtist.clean("5566"))
        assertEquals("10cc", MusicArtist.clean("10cc"))
        assertEquals("1976", MusicArtist.clean("1976"))
        assertFalse(MusicArtist.numberPrefixed("10cc"))
        assertFalse(MusicArtist.numberPrefixed("5566"))
    }

    @Test fun `不补零的 12张杰 只在张杰已知时合并`() {
        assertEquals(listOf("张杰"), MusicArtist.resolve(i("a", "12张杰", "逆战"), setOf(MusicArtist.key("张杰"))).artists)
        assertEquals(listOf("12张杰"), MusicArtist.resolve(i("a", "12张杰", "逆战")).artists)
    }

    @Test fun `全角转半角 大小写空格不同合并`() {
        assertEquals("JAY", MusicArtist.clean("ＪＡＹ"))
        val idx = MusicArtist.index(listOf(i("1", "Jay Chou", tagged = true), i("2", "Jay Chou", tagged = true), i("3", "jay  chou", tagged = true)))
        assertEquals(setOf("Jay Chou"), idx.fixes.values.flatMap { it.artists }.toSet())
    }

    @Test fun `多歌手拆开 去重 丢掉混进来的未知`() {
        assertEquals(listOf("周杰伦", "费玉清"), MusicArtist.split("周杰伦/费玉清"))
        assertEquals(listOf("周杰伦", "费玉清"), MusicArtist.split("周杰伦 & 费玉清"))
        assertEquals(listOf("周杰伦", "费玉清"), MusicArtist.split("周杰伦、费玉清"))
        assertEquals(listOf("Lady Gaga", "Bradley Cooper"), MusicArtist.split("Lady Gaga feat. Bradley Cooper"))
        assertEquals(listOf("A", "B"), MusicArtist.split("A ft. B"))
        assertEquals(listOf("许巍"), MusicArtist.split("许巍/未知艺术家"))
        assertEquals(listOf("许巍"), MusicArtist.split("许巍/许巍"))
        assertEquals(listOf(MusicArtist.UNKNOWN), MusicArtist.split(""))
    }

    @Test fun `联网歌手只在本地是占位时用`() {
        assertEquals(listOf("周杰伦"), MusicArtist.resolve(i("u", "未知艺术家", "晴天", online = "周杰伦")).artists)
        assertEquals(listOf("许巍"), MusicArtist.resolve(i("u", "许巍", "蓝莲花", online = "周杰伦")).artists)
    }

    @Test fun `专辑名同理`() {
        assertEquals("时光·漫步", MusicArtist.cleanAlbum("(2002)时光·漫步"))
        assertEquals("时光·漫步", MusicArtist.cleanAlbum("时光·漫步 [2002]"))
        assertEquals("逆战", MusicArtist.cleanAlbum("01-逆战"))
        assertEquals("", MusicArtist.cleanAlbum("未知专辑"))
        assertEquals("", MusicArtist.cleanAlbum("Unknown Album"))
        assertEquals("1989", MusicArtist.cleanAlbum("1989"))
    }

    @Test fun `排序 未知最后 符号开头不排最前`() {
        assertEquals(2, MusicArtist.sortRank(MusicArtist.UNKNOWN))
        assertEquals(0, MusicArtist.sortRank(".K"))
        assertEquals("K", MusicArtist.sortText(".K"))
        assertEquals(0, MusicArtist.sortRank("张杰"))
        assertEquals(1, MusicArtist.sortRank("5566"))
        val names = listOf(MusicArtist.UNKNOWN, "5566", ".K", "许巍").sortedWith(compareBy({ MusicArtist.sortRank(it) }, { MusicArtist.sortText(it) }))
        assertEquals(MusicArtist.UNKNOWN, names.last())
        assertEquals("5566", names[2])
    }

    @Test fun `截图里整页一起算`() {
        val l = listOf(
            i("a", "-柒蕊zero-"), i("b1", ".K"), i("b2", ".K"), i("c", "(2002)许巍"), i("d", "(未知Artist)"),
        ) + (1..7).map { i("z$it", "0${it}张杰", "歌$it") } + listOf(i("s", "08素颜", "许嵩"), i("x", "1376心想事成", "理塘丁真"))
        val idx = MusicArtist.index(l)
        val groups = idx.fixes.values.flatMap { it.artists }.groupingBy { it }.eachCount()
        assertEquals(mapOf("柒蕊zero" to 1, ".K" to 2, "许巍" to 1, MusicArtist.UNKNOWN to 1, "张杰" to 7, "许嵩" to 1, "理塘丁真" to 1), groups)
    }

    @Test fun `升级后未知歌手的歌只再联网查一次`() {
        val t = Track("1", "/a.mp3", "a.mp3", 1, 1, "晴天", "(未知Artist)", "叶惠美", artPath = "/x.img", tagged = true, scanned = true, online = true, onVer = 2)
        assertTrue(Music.needsOnline(t))
        assertFalse(Music.needsOnline(t.copy(onVer = Music.ONLINE_VER)))
        assertFalse(Music.needsOnline(t.copy(artist = "周杰伦")))
    }
}
