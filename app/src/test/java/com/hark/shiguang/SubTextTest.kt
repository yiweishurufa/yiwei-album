package com.hark.shiguang

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 优化 4（用户 2026-11-02）：外挂字幕编码识别。 */
class SubTextTest {
    private val srt = "1\n00:00:01,000 --> 00:00:02,000\n你好，世界\n"

    @Test fun `GBK 字幕转成 UTF-8`() = assertEquals(srt, String(SubText.toUtf8(srt.toByteArray(charset("GBK"))), Charsets.UTF_8))
    @Test fun `UTF-8 原样返回`() { val b = srt.toByteArray(Charsets.UTF_8); assertSame(b, SubText.toUtf8(b)) }
    @Test fun `UTF-8 BOM 去掉`() = assertArrayEquals(srt.toByteArray(Charsets.UTF_8), SubText.toUtf8(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + srt.toByteArray(Charsets.UTF_8)))
    @Test fun `UTF-16LE BOM`() = assertEquals(srt, SubText.decode(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + srt.toByteArray(Charsets.UTF_16LE)))
}
