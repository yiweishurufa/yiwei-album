package com.hark.shiguang

import org.junit.Assert.*
import org.junit.Test

/** 1.7.35 歌词 v2：普通 LRC 行为不变；增强 LRC / 行内方括号 / YRC 逐字；空白行给结束时间；间奏与逐字进度。 */
class LrcTest {
    @Test fun `普通 LRC 按时间排序、多时间标签展开、偏移生效`() {
        val l = Lrc.parse("[offset:+500]\n[00:10.00]第二句\n[00:05.50][00:20.00]第一句\n")
        assertEquals(listOf(5000L, 9500L, 19500L), l.map { it.ms })
        assertEquals(listOf("第一句", "第二句", "第一句"), l.map { it.text })
        assertTrue(l.all { it.words.isEmpty() })
    }

    @Test fun `同时间两行合并成原文 + 翻译`() {
        val l = Lrc.parse("[00:01.00]Hello\n[00:01.00]你好\n[00:03.00]World\n[00:05.00]世界之外")
        assertEquals("Hello", l[0].text); assertEquals("你好", l[0].sub)
    }

    @Test fun `增强 LRC 尖括号逐字`() {
        val l = Lrc.parse("[00:12.00]<00:12.00>还<00:12.40>要<00:13.00>多久<00:14.00>\n[00:15.00]下一句\n[00:16.00]再一句")
        val w = l[0].words
        assertEquals("还要多久", l[0].text)
        assertEquals(listOf("还", "要", "多久"), w.map { it.text })
        assertEquals(12000L, w[0].startMs); assertEquals(12400L, w[0].endMs)
        assertEquals(14000L, w[2].endMs)
        assertEquals(14000L, l[0].end)
    }

    @Test fun `行内方括号逐字，最后一个字没有结束时用下一句开始补`() {
        val l = Lrc.parse("[00:01.00]A[00:01.50]B[00:02.00]C\n[00:03.00]D\n[00:04.00]E")
        assertEquals("ABC", l[0].text)
        assertEquals(listOf(1000L, 1500L, 2000L), l[0].words.map { it.startMs })
        assertEquals(3000L, l[0].words.last().endMs)
        assertEquals(3, l.size)
    }

    @Test fun `网易 YRC 逐字`() {
        val l = Lrc.parse("{\"t\":0,\"c\":[{\"tx\":\"作词\"}]}\n[16210,3460](16210,670,0)还(16880,410,0)没(17290,2380,0)好\n[20000,1000](20000,1000,0)下\n[22000,1000](22000,1000,0)再")
        assertEquals("还没好", l[0].text)
        assertEquals(16210L, l[0].ms); assertEquals(19670L, l[0].endMs)
        assertEquals(listOf(16210L, 16880L, 17290L), l[0].words.map { it.startMs })
    }

    @Test fun `空白行当作上一句的结束，间奏超过 6 秒才算`() {
        val l = Lrc.parse("[00:08.00]第一句\n[00:11.00]\n[00:20.00]第二句\n[00:22.00]第三句\n[00:25.00]")
        assertEquals(3, l.size)
        assertEquals(11000L, l[0].endMs)
        val g = Lrc.gaps(l)
        // 前奏 0–8 s、第一句后 11–20 s；第二句没有结束时间不算
        assertEquals(listOf(-1, 0), g.map { it.afterIndex })
        assertEquals(11000L, g[1].startMs); assertEquals(20000L, g[1].endMs)
    }

    @Test fun `当前句提前 300 毫秒亮起，无时间轴为 null`() {
        val l = Lrc.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertEquals(0, Lrc.current(l, 0))
        assertEquals(1, Lrc.current(l, 4800))
        assertEquals(2, Lrc.current(l, 99999))
        assertNull(Lrc.current(Lrc.plain("没有\n时间"), 1000))
    }

    @Test fun `逐字进度按时间比例`() {
        val line = Lrc.parse("[00:00.00]<00:00.00>ab<00:01.00>cd<00:03.00>\n[00:04.00]x\n[00:05.00]y")[0]
        assertEquals(0f, Lrc.sungChars(line, 0)!!, 0.001f)
        assertEquals(1f, Lrc.sungChars(line, 500)!!, 0.001f)
        assertEquals(3f, Lrc.sungChars(line, 2000)!!, 0.001f)
        assertEquals(4f, Lrc.sungChars(line, 9000)!!, 0.001f)
        assertNull(Lrc.sungChars(Lrc.Line(0, "x"), 100))
    }

    @Test fun `纯文本歌词`() {
        val l = Lrc.plain(" 一 \n\n二\n")
        assertEquals(listOf("一", "二"), l.map { it.text })
        assertTrue(l.all { it.ms < 0 })
    }
}
