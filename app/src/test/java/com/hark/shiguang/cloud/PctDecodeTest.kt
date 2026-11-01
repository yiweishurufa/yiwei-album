package com.hark.shiguang.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

/** 用户 2026-11-02 反馈（音乐中文乱码）一并检查 WebDAV / 123云盘文件名的 URL 解码。 */
class PctDecodeTest {
    @Test fun `UTF-8 百分号编码`() = assertEquals("/音乐/晴天.wav", CloudHttp.pctDecode("/%E9%9F%B3%E4%B9%90/%E6%99%B4%E5%A4%A9.wav"))
    @Test fun `GBK 百分号编码回退`() = assertEquals("/晴天.wav", CloudHttp.pctDecode("/%C7%E7%CC%EC.wav"))
    @Test fun `加号不变空格`() = assertEquals("a+b c.mp3", CloudHttp.pctDecode("a+b%20c.mp3"))
    @Test fun `坏的转义原样保留`() = assertEquals("100%.mp3", CloudHttp.pctDecode("100%.mp3"))
    @Test fun `没有转义直接返回`() = assertEquals("周杰伦", CloudHttp.pctDecode("周杰伦"))
}
