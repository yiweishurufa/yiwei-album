package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageKeysTest {
    private val acc = listOf("a1" to listOf("http://192.168.1.5:5666", "http://192.168.1.5:5666/", "https://home.fnos.net"))

    @Test fun lanAndWanShareOneKey() {
        val lan = ImageKeys.stable("http://192.168.1.5:5666/p/api/v1/stream/p/t/12/s/abc", acc)
        val wan = ImageKeys.stable("https://home.fnos.net/p/api/v1/stream/p/t/12/s/abc", acc)
        val wanPort = ImageKeys.stable("https://HOME.fnos.net:443/p/api/v1/stream/p/t/12/s/abc", acc)
        assertEquals("fnimg://a1/p/api/v1/stream/p/t/12/s/abc", lan)
        assertEquals(lan, wan)
        assertEquals(lan, wanPort)
    }

    @Test fun keepsQuery() {
        assertEquals("fnimg://a1/p/api/x?id=3&s=1", ImageKeys.stable("http://192.168.1.5:5666/p/api/x?id=3&s=1", acc))
    }

    @Test fun othersUnchanged() {
        val tmdb = "https://image.tmdb.org/t/p/w342/x.jpg"
        assertEquals(tmdb, ImageKeys.stable(tmdb, acc))
        // 同一台 NAS 上不是 /p/ 相册接口的地址（如影视海报）不动
        assertEquals("http://192.168.1.5:5666/v/api/poster/1", ImageKeys.stable("http://192.168.1.5:5666/v/api/poster/1", acc))
        // 别的主机 / 端口不动
        assertEquals("http://192.168.1.6:5666/p/api/a", ImageKeys.stable("http://192.168.1.6:5666/p/api/a", acc))
        assertEquals("http://192.168.1.5:8000/p/api/a", ImageKeys.stable("http://192.168.1.5:8000/p/api/a", acc))
        assertEquals("file:///x.jpg", ImageKeys.stable("file:///x.jpg", acc))
        assertEquals("not a url", ImageKeys.stable("not a url", acc))
    }

    @Test fun twoAccountsStaySeparate() {
        val two = acc + ("b2" to listOf("http://10.0.0.2:5666"))
        assertEquals("fnimg://b2/p/s/1", ImageKeys.stable("http://10.0.0.2:5666/p/s/1", two))
        assertEquals("fnimg://a1/p/s/1", ImageKeys.stable("http://192.168.1.5:5666/p/s/1", two))
    }
}
