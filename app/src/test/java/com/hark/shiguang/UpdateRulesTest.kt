package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UpdateRulesTest {
    @Test fun `只信任 yiweishurufa 下的地址`() {
        assertTrue(UpdateRules.trusted("https://github.com/yiweishurufa/yiwei-album/releases/download/android-nightly/yiwei-album.apk"))
        assertFalse(UpdateRules.trusted("https://github.com/evil/yiwei-album.apk"))
        assertFalse(UpdateRules.trusted("https://github.com/yiweishurufa/../evil/x.apk"))
        assertFalse(UpdateRules.trusted("https://github.com/yiweishurufa@evil.com/x.apk"))
        assertFalse(UpdateRules.trusted("http://github.com/yiweishurufa/x.apk"))
    }

    @Test fun `版本号比较`() {
        assertTrue(UpdateRules.cmp("1.0.10", "1.0.9") > 0)
        assertTrue(UpdateRules.cmp("1.1.0", "1.0.10") > 0)
        assertEquals(0, UpdateRules.cmp("1.0", "1.0.0"))
        assertTrue(UpdateRules.cmp("1.0.3-beta", "1.0.4") < 0)
    }

    @Test fun `同 versionCode 看 CI 运行号`() {
        assertTrue(UpdateRules.isNewer(15, 0, 14, 99))
        assertTrue(UpdateRules.isNewer(14, 100, 14, 99))
        assertFalse(UpdateRules.isNewer(14, 99, 14, 99))
        assertFalse(UpdateRules.isNewer(13, 500, 14, 1))
    }

    @Test fun `安装包文件校验`() {
        val f = File.createTempFile("apk", ".apk")
        try {
            f.writeBytes("PK\u0003\u0004hello".toByteArray())
            val sha = f.inputStream().use { UpdateRules.sha256Hex(it) }
            assertTrue(UpdateRules.isSha256Hex(sha))
            assertNull(UpdateRules.checkFile(f, f.length(), sha))
            assertNull(UpdateRules.checkFile(f, 0, sha.uppercase()))
            assertTrue(UpdateRules.checkFile(f, f.length() + 1, sha)!!.contains("不完整"))
            assertTrue(UpdateRules.checkFile(f, f.length(), "0".repeat(64))!!.contains("SHA-256"))
            f.writeBytes("<html>".toByteArray())
            assertEquals("下载的不是安装包", UpdateRules.checkFile(f, 0, sha))
            f.writeBytes(ByteArray(0))
            assertEquals("安装包不存在", UpdateRules.checkFile(f, 0, sha))
        } finally { f.delete() }
    }

    @Test fun `签名分类：读不出一律拒绝`() {
        val a = "aa".repeat(32); val b = "bb".repeat(32); val c = "cc".repeat(32)
        assertEquals(UpdateRules.Signer.SAME, UpdateRules.classifySigner(a, a.uppercase(), setOf(b)))
        assertEquals(UpdateRules.Signer.TRUSTED_OTHER, UpdateRules.classifySigner(a, b, setOf(a, b)))
        assertEquals(UpdateRules.Signer.UNKNOWN, UpdateRules.classifySigner(a, c, setOf(a, b)))
        assertEquals(UpdateRules.Signer.UNKNOWN, UpdateRules.classifySigner(null, a, setOf(a)))
        assertEquals(UpdateRules.Signer.UNKNOWN, UpdateRules.classifySigner(a, null, setOf(a)))
    }
}
