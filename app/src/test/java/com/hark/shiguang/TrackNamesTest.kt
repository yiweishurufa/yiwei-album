package com.hark.shiguang

import com.hark.shiguang.TrackNames.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackNamesTest {
    @Test fun `语言代码映射成中文名`() {
        assertEquals("中文", TrackNames.languageName("zh"))
        assertEquals("中文", TrackNames.languageName("chi"))
        assertEquals("中文", TrackNames.languageName("zho"))
        assertEquals("粤语", TrackNames.languageName("yue"))
        assertEquals("粤语", TrackNames.languageName("zh-HK"))
        assertEquals("粤语", TrackNames.languageName("zh-yue"))
        assertEquals("繁体中文", TrackNames.languageName("zh-Hant"))
        assertEquals("繁体中文", TrackNames.languageName("zh_TW"))
        assertEquals("简体中文", TrackNames.languageName("zh-Hans"))
        assertEquals("简体中文", TrackNames.languageName("zh-CN"))
        assertEquals("英语", TrackNames.languageName("en"))
        assertEquals("英语", TrackNames.languageName("eng"))
        assertEquals("日语", TrackNames.languageName("jpn"))
        assertNull(TrackNames.languageName("und"))
        assertNull(TrackNames.languageName(null))
        assertNull(TrackNames.languageName(" "))
    }

    @Test fun `label 优先，纯技术信息的 label 不算名字`() {
        assertEquals("国语", TrackNames.labelName("国语"))
        assertEquals("导评", TrackNames.labelName("Commentary"))
        assertEquals("粤语", TrackNames.labelName("Cantonese"))
        assertNull(TrackNames.labelName("AC3 5.1"))
        assertNull(TrackNames.labelName("DTS-HD MA 7.1"))
        assertNull(TrackNames.labelName("Stereo"))
        assertNull(TrackNames.labelName(""))
        assertNull(TrackNames.labelName(null))
    }

    @Test fun `截图里 4 条一模一样的中文 5_1 合并成一项`() {
        val t = (0 until 4).map { Track(it, "chi", null, "audio/ac3", channels = 6) }
        val o = TrackNames.options(t, audio = true)
        assertEquals(1, o.size)
        assertEquals("中文", o[0].title)
        assertEquals("AC3 5.1", o[0].detail)
        assertEquals(listOf(0, 1, 2, 3), o[0].ids)
    }

    @Test fun `mkv title 不同的轨按 title 显示`() {
        val o = TrackNames.options(listOf(
            Track(0, "chi", "国语", "audio/ac3", channels = 6),
            Track(1, "chi", "粤语", "audio/ac3", channels = 6),
            Track(2, "chi", "导评", "audio/aac", channels = 2),
        ), audio = true)
        assertEquals(listOf("国语", "粤语", "导评"), o.map { it.title })
    }

    @Test fun `同名不同编码或声道用括号区分`() {
        val o = TrackNames.options(listOf(
            Track(0, "zh", null, "audio/ac3", channels = 6),
            Track(1, "zh", null, "audio/mp4a-latm", channels = 2),
            Track(2, "en", null, "audio/ac3", channels = 6),
        ), audio = true)
        assertEquals(listOf("中文（AC3 5.1）", "中文（AAC 2.0）", "英语"), o.map { it.title })
        assertNull(o[0].detail)
        assertEquals("AC3 5.1", o[2].detail)
    }

    @Test fun `语言区域区分国语粤语简繁`() {
        val o = TrackNames.options(listOf(
            Track(0, "zh-Hans", null, "application/x-subrip"),
            Track(1, "zh-Hant", null, "application/x-subrip"),
            Track(2, "yue", null, "application/x-subrip"),
            Track(3, "en", null, "application/x-subrip"),
        ), audio = false)
        assertEquals(listOf("简体中文", "繁体中文", "粤语", "英语"), o.map { it.title })
    }

    @Test fun `字幕 4 个中文 + 英语：真正相同的合并，格式不同的区分`() {
        val o = TrackNames.options(listOf(
            Track(0, "chi", null, "text/x-ssa"),
            Track(1, "chi", null, "text/x-ssa"),
            Track(2, "chi", null, "application/pgs"),
            Track(3, "chi", null, "application/pgs"),
            Track(4, "eng", null, "application/x-subrip"),
        ), audio = false)
        assertEquals(listOf("中文（ASS）", "中文（PGS）", "英语"), o.map { it.title })
        assertEquals(listOf(0, 1), o[0].ids)
    }

    @Test fun `media3 转换后的字幕从 codecs 里取原格式`() {
        assertEquals("ASS", TrackNames.codecName("application/x-media3-cues", "text/x-ssa"))
        assertEquals("SRT", TrackNames.codecName("application/x-media3-cues", "application/x-subrip"))
    }

    @Test fun `什么都分不开时加序号，强制字幕单独一项`() {
        val o = TrackNames.options(listOf(
            Track(0, "chi", null, "text/x-ssa"),
            Track(1, "chi", null, "text/x-ssa", forced = true),
        ), audio = false)
        assertEquals(2, o.size)
        assertEquals(listOf("中文（ASS）", "中文（ASS） 2"), o.map { it.title })
        assertEquals("强制", o[1].detail)
    }

    @Test fun `没有语言也没有 label 显示未知语言`() {
        val o = TrackNames.options(listOf(Track(0, null, null, "audio/ac3", channels = 2)), audio = true)
        assertEquals("未知语言", o[0].title)
    }

    @Test fun `label 没说语言时语言放小字`() {
        val o = TrackNames.options(listOf(Track(0, "chi", "国语", "audio/ac3", channels = 6)), audio = true)
        assertEquals("国语", o[0].title)
        assertEquals("中文 · AC3 5.1", o[0].detail)
    }

    @Test fun `清晰度：单轨只有原画并标分辨率`() {
        val q = TrackNames.qualities(listOf(TrackNames.Video(0, 1920, 800)))
        assertEquals(1, q.size)
        assertEquals("原画", q[0].title)
        assertEquals("1920×800", q[0].detail)
    }

    @Test fun `清晰度：多轨按档位从高到低，同档取高码率`() {
        val q = TrackNames.qualities(listOf(
            TrackNames.Video(0, 1280, 720, 2_000_000),
            TrackNames.Video(1, 1920, 1080, 5_000_000),
            TrackNames.Video(2, 1920, 1080, 8_000_000),
            TrackNames.Video(3, 3840, 2160, 20_000_000),
        ))
        assertEquals(listOf("4K", "1080P", "720P"), q.map { it.title })
        assertEquals(listOf(2), q[1].ids)
    }

    @Test fun `格式化`() {
        assertEquals("8.2 Mbps", TrackNames.bitrateName(8_200_000))
        assertEquals("640 kbps", TrackNames.bitrateName(640_000))
        assertNull(TrackNames.bitrateName(-1))
        assertEquals("23.976 fps", TrackNames.frameRateName(23.976f))
        assertEquals("25 fps", TrackNames.frameRateName(25f))
        assertEquals("48 kHz", TrackNames.sampleRateName(48_000))
        assertEquals("44.1 kHz", TrackNames.sampleRateName(44_100))
        assertEquals("MKV", TrackNames.containerName("video/x-matroska", "a.mkv"))
        assertEquals("MP4", TrackNames.containerName(null, "a.mp4"))
        assertEquals("1.50 GB", TrackNames.sizeName(1_610_612_736))
        assertEquals("1080P", TrackNames.qualityName(1920, 1080))
        assertEquals("720P", TrackNames.qualityName(1280, 534))
    }
}
