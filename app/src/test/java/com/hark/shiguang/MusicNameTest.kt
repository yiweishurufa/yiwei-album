package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.32 歌名规范化（用户 2026-11-28：「07-寂寞的门」这类带音轨号、「歌曲 - 歌手」的名字）。 */
class MusicNameTest {
    private fun t(raw: String, artists: List<String> = emptyList(), track: Int = 0, file: Boolean = false) = MusicName.clean(raw, artists, track, file).title

    @Test fun `去音轨号前缀`() {
        assertEquals("寂寞的门", t("07-寂寞的门", listOf("许飞")))
        assertEquals("寂寞的门", t("07.寂寞的门"))
        assertEquals("寂寞的门", t("07. 寂寞的门"))
        assertEquals("寂寞的门", t("07 寂寞的门"))
        assertEquals("寂寞的门", t("(07)寂寞的门"))
        assertEquals("寂寞的门", t("[07] 寂寞的门"))
        assertEquals("寂寞的门", t("07、寂寞的门"))
        assertEquals("寂寞的门", t("Track 07 - 寂寞的门"))
        assertEquals("寂寞的门", t("０７－寂寞的门"))   // 全角
        assertEquals(7, MusicName.clean("07-寂寞的门").trackNo)
    }

    @Test fun `不该去的数字保留`() {
        assertEquals("17岁", t("17岁"))
        assertEquals("7 Rings", t("7 Rings"))
        assertEquals("1999-2009", t("1999-2009"))
        assertEquals("2002年的第一场雪", t("2002年的第一场雪"))
        assertEquals("99次我爱他", t("99次我爱他"))
        // 标签音轨号就是 7 时「7 歌名」才去
        assertEquals("寂寞的门", t("7 寂寞的门", track = 7))
        // 文件名沿用旧规则：「1 歌名」也去
        assertEquals("寂寞的门", t("1 寂寞的门", file = true))
    }

    @Test fun `歌手 - 歌名 按已知歌手拆`() {
        assertEquals("寂寞的门", t("许飞 - 寂寞的门", listOf("许飞")))
        assertEquals("寂寞的门", t("寂寞的门 - 许飞", listOf("许飞")))
        assertEquals("寂寞的门", t("寂寞的门-许飞", listOf("许飞")))
        assertEquals("寂寞的门", t("07 - 许飞 - 寂寞的门", listOf("许飞")))
        assertEquals("寂寞的门", t("许飞 - 07 寂寞的门", listOf("许飞")))
        assertEquals("夜曲", t("周杰伦 - 夜曲", listOf("周杰伦/方文山")))
        val p = MusicName.clean("许飞 - 寂寞的门", fromFileName = true)
        assertEquals("寂寞的门", p.title); assertEquals("许飞", p.artist)
        val q = MusicName.clean("寂寞的门 - 许飞", listOf("许飞"))
        assertEquals("许飞", q.artist)
    }

    @Test fun `标签标题两边都不认识时不乱拆`() {
        assertEquals("Love - Story", t("Love - Story", listOf("Taylor Swift")))
        assertEquals("Re-Union", t("Re-Union"))
        assertEquals("一路向北", t("一路向北"))
    }

    @Test fun `去无用括注保留有意义的`() {
        assertEquals("寂寞的门", t("寂寞的门 [FLAC]"))
        assertEquals("寂寞的门", t("寂寞的门(320k)"))
        assertEquals("寂寞的门", t("寂寞的门【无损】"))
        assertEquals("寂寞的门", t("寂寞的门 (官方版)"))
        assertEquals("寂寞的门", t("寂寞的门 (MV版)"))
        assertEquals("寂寞的门", t("寂寞的门 (Official Video)"))
        assertEquals("寂寞的门", t("寂寞的门 [24bit 96kHz]"))
        assertEquals("寂寞的门", t("寂寞的门_无损"))
        assertEquals("寂寞的门", t("寂寞的门.flac"))
        assertEquals("寂寞的门 (Live)", t("寂寞的门 (Live)"))
        assertEquals("寂寞的门 (伴奏)", t("寂寞的门 (伴奏)"))
        assertEquals("寂寞的门(Remix)", t("寂寞的门（Remix）"))
        assertEquals("寂寞的门 (电视剧《两个永恒》主题曲)", t("寂寞的门 (电视剧《两个永恒》主题曲)"))
        assertEquals("寂寞的门 (Live)", t("07-寂寞的门 (Live) [FLAC]", listOf("许飞")))
    }

    @Test fun `全角半角与空白`() {
        assertEquals("ABC 123", MusicName.normalizeWidth("ＡＢＣ\u3000１２３ "))
        assertEquals("你好，世界", MusicName.normalizeWidth("你好，世界"))
        assertEquals("寂寞的门", t("  寂寞的门  "))
    }

    @Test fun `清空了就退回原文`() {
        assertEquals("07", t("07"))
        assertEquals("[FLAC]", t("[FLAC]"))
        assertEquals("", t(""))
    }

    @Test fun `合集与翻唱识别`() {
        assertTrue(MusicName.looksCompilation("许飞60首翻唱物料"))
        assertTrue(MusicName.looksCompilation(""))
        assertTrue(MusicName.looksCompilation("华语经典金曲"))
        assertFalse(MusicName.looksCompilation("许飞"))
        assertFalse(MusicName.looksCompilation("叶惠美"))
        assertTrue(MusicName.looksCover("寂寞的门", "许飞60首翻唱物料"))
        assertFalse(MusicName.looksCover("寂寞的门", "两个永恒"))
    }
}
