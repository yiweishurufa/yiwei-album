package com.hark.shiguang.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavParseTest {
    private val src = WebDavSource(CloudAccount(id = "t", kind = CloudKind.WEBDAV, title = "t", url = "https://dav.example.com/dav/我的 网盘/"))

    private val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
 <d:response><d:href>/dav/%E6%88%91%E7%9A%84%20%E7%BD%91%E7%9B%98/%E7%85%A7%E7%89%87/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/dav/%E6%88%91%E7%9A%84%20%E7%BD%91%E7%9B%98/%E7%85%A7%E7%89%87/%E6%97%85%E8%A1%8C%202026/</d:href>
  <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>https://dav.example.com/dav/%E6%88%91%E7%9A%84%20%E7%BD%91%E7%9B%98/%E7%85%A7%E7%89%87/IMG%201.JPG</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>2048</d:getcontentlength><d:getetag>"abc"</d:getetag>
   <d:getcontenttype>image/jpeg</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  <d:propstat><d:prop><d:getcontentlength>999</d:getcontentlength></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
 <d:response><d:href>/dav/%E6%88%91%E7%9A%84%20%E7%BD%91%E7%9B%98/%E7%85%A7%E7%89%87/a+b.mp4</d:href>
  <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>10</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>"""

    @Test fun `解析目录列表：跳过自身、中文和空格路径解码、404 propstat 不覆盖`() {
        val out = src.parseMultiStatus(xml, "/照片")
        assertEquals(listOf("旅行 2026", "a+b.mp4", "IMG 1.JPG").sorted(), out.map { it.name }.sorted())
        val dir = out.first { it.name == "旅行 2026" }
        assertTrue(dir.isDir); assertEquals("/照片/旅行 2026", dir.path)
        val img = out.first { it.name == "IMG 1.JPG" }
        assertFalse(img.isDir); assertTrue(img.isImage); assertEquals(2048L, img.size); assertEquals("etag:abc", img.tag)
        val vid = out.first { it.name == "a+b.mp4" }
        assertTrue(vid.isVideo); assertEquals("/照片/a+b.mp4", vid.path) // '+' 不能被解码成空格
        assertTrue(out.first().isDir) // 文件夹排在前面
    }

    @Test(expected = Exception::class) fun `坏 XML 给出可读错误`() {
        src.parseMultiStatus("<d:multistatus xmlns:d=\"DAV:\"><d:response></d:href></d:multistatus>", "/")
    }

    @Test fun `中文和空格路径编码`() {
        assertEquals("%E7%85%A7%E7%89%87/%E6%97%85%E8%A1%8C%202026/IMG%201.JPG", CloudHttp.encPath("照片/旅行 2026/IMG 1.JPG"))
        assertEquals("a%2Bb.mp4", CloudHttp.encPath("a+b.mp4"))
        assertEquals("照片/a+b 1.jpg", CloudHttp.pctDecode("%E7%85%A7%E7%89%87/a+b%201.jpg"))
        val p = "相册/2026 春节/#1 & 2%.jpg"
        assertEquals(p, CloudHttp.pctDecode(CloudHttp.encPath(p)))
    }
}
