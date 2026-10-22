package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextTest {
    @Test fun fullWidthToHalfWidth() {
        assertEquals("abc123(x)", OcrText.normalize("ＡＢＣ１２３（ｘ）"))
        assertEquals("wi-fi", OcrText.normalize("Ｗｉ－Ｆｉ"))
    }

    @Test fun caseInsensitive() {
        assertEquals("hello", OcrText.normalize("HeLLo"))
        assertTrue(OcrText.matches(OcrText.normalize("STARBUCKS COFFEE"), OcrText.terms("starbucks")))
    }

    @Test fun whitespaceRemoved() {
        // OCR 常在汉字之间插空格、换行；全角空格、不换行空格也算
        assertEquals("营业时间9:00-21:00", OcrText.normalize("营 业\n时间\u3000 9:00-21:00\u00A0"))
        assertTrue(OcrText.matches(OcrText.normalize("发 票 号 码"), OcrText.terms("发票号码")))
        assertTrue(OcrText.matches(OcrText.normalize("发票号码"), OcrText.terms("发票 号码")))
    }

    @Test fun termsSplitAndNormalize() {
        assertEquals(listOf("发票", "abc"), OcrText.terms("  发票，ＡＢＣ、 发票 "))
        assertEquals(emptyList<String>(), OcrText.terms(" \u3000 ，"))
    }

    @Test fun allTermsMustMatch() {
        val n = OcrText.normalize("上海 地铁 2号线 人民广场站")
        assertTrue(OcrText.matches(n, OcrText.terms("人民广场 2号线")))
        assertFalse(OcrText.matches(n, OcrText.terms("人民广场 3号线")))
        assertFalse(OcrText.matches(n, emptyList()))
        assertFalse(OcrText.matches("", OcrText.terms("x")))
    }

    @Test fun traditionalNotConverted() {
        // 没有现成的繁简转换：繁体只能用繁体搜到（记录当前行为，以后加转换时改这个用例）
        assertFalse(OcrText.matches(OcrText.normalize("營業時間"), OcrText.terms("营业时间")))
        assertTrue(OcrText.matches(OcrText.normalize("營業時間"), OcrText.terms("營業")))
    }

    @Test fun likePatternEscapes() {
        assertEquals("%50\\%off%", OcrText.likePattern("50%off"))
        assertEquals("%a\\_b\\\\c%", OcrText.likePattern("a_b\\c"))
        assertEquals("ab12", OcrText.prefilterTerm(listOf("a", "ab12", "x")))
        assertNull(OcrText.prefilterTerm(emptyList()))
    }

    @Test fun clipLongText() {
        assertEquals("abc", OcrText.clip("  abc \n"))
        assertEquals(OcrText.MAX_TEXT, OcrText.clip("字".repeat(OcrText.MAX_TEXT + 50)).length)
    }

    @Test fun needsOcrRules() {
        val now = 10_000_000_000L
        // 没记录 → 识别
        assertTrue(OcrText.needsOcr(0, 0, 0, 0, 100, 5, false, now))
        // 识别过、文件没变 → 不再识别
        assertFalse(OcrText.needsOcr(OcrText.STATE_DONE, 100, 5, now, 100, 5, false, now))
        // 文件大小或修改时间变了 → 重新识别
        assertTrue(OcrText.needsOcr(OcrText.STATE_DONE, 100, 5, now, 101, 5, false, now))
        assertTrue(OcrText.needsOcr(OcrText.STATE_DONE, 100, 5, now, 100, 6, false, now))
        // 修改时间未知（0）时只比大小
        assertFalse(OcrText.needsOcr(OcrText.STATE_DONE, 100, 5, now, 100, 0, false, now))
        // 没有小图：有缓存缩略图了，或过了 7 天 → 重试
        assertFalse(OcrText.needsOcr(OcrText.STATE_NO_IMAGE, 100, 5, now - 1000, 100, 5, false, now))
        assertTrue(OcrText.needsOcr(OcrText.STATE_NO_IMAGE, 100, 5, now - 1000, 100, 5, true, now))
        assertTrue(OcrText.needsOcr(OcrText.STATE_NO_IMAGE, 100, 5, now - OcrText.NO_IMAGE_RETRY_MS, 100, 5, false, now))
    }
}
