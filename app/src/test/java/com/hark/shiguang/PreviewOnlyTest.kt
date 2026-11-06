package com.hark.shiguang

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewOnlyTest {
    @Test fun `飞牛 1点2点1000 新增的序列帧格式只看预览`() {
        listOf("shot_0001.EXR", "a.dpx", "frame.j2c", "x.J2K", "y.jp2").forEach { assertTrue(it, PreviewOnly.of(it)) }
    }

    @Test fun `RAW 和 TIFF 只看预览`() {
        listOf("IMG_1.DNG", "a.arw", "b.CR3", "c.nef", "d.raf", "e.tif", "f.TIFF", "g.psd").forEach { assertTrue(it, PreviewOnly.of(it)) }
    }

    @Test fun `普通照片照常下载原图`() {
        listOf("IMG_1.JPG", "a.heic", "b.png", "c.webp", "d.gif", "noext", "dir.exr/photo.jpg", "").forEach { assertFalse(it, PreviewOnly.of(it)) }
    }
}
