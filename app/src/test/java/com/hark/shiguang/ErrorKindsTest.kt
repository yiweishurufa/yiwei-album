package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorKindsTest {
    @Test fun `各类错误分对`() {
        assertEquals(ErrorKind.NETWORK, ErrorKind.of("连接超时"))
        assertEquals(ErrorKind.NETWORK, ErrorKind.of("无法解析服务器地址"))
        assertEquals(ErrorKind.NETWORK, ErrorKind.of("网络错误：Connection refused"))
        assertEquals(ErrorKind.AUTH, ErrorKind.of("WebDAV 用户名或密码错误"))
        assertEquals(ErrorKind.AUTH, ErrorKind.of("WebDAV 无权限列目录"))
        assertEquals(ErrorKind.CERT, ErrorKind.of(TlsHintForTest))
        assertEquals(ErrorKind.CERT, ErrorKind.of("网络错误：Trust anchor for certification path not found."))
        assertEquals(ErrorKind.SPACE, ErrorKind.of("服务器空间不足"))
        assertEquals(ErrorKind.SPACE, ErrorKind.of("write failed: ENOSPC (No space left on device)"))
        assertEquals(ErrorKind.NOT_FOUND, ErrorKind.of("路径不存在"))
        assertEquals(ErrorKind.OTHER, ErrorKind.of(null))
        assertEquals(ErrorKind.OTHER, ErrorKind.of("  "))
        assertEquals(ErrorKind.OTHER, ErrorKind.of("WebDAV 响应解析失败"))
    }

    @Test fun `操作失败提示说人话`() {
        assertEquals("删除失败：连不上服务器", FriendlyError.message("Unable to resolve host \"nas.local\": No address associated with hostname", "删除失败"))
        assertEquals("操作失败：连不上服务器", FriendlyError.message("timeout", "失败"))
        assertEquals("创建失败：用户名或密码不对", FriendlyError.message("HTTP 401 Unauthorized", "创建失败"))
        assertEquals("网盘账户已移除", FriendlyError.message("网盘账户已移除", "删除失败"))
        assertEquals("下载失败，可以重试一次", FriendlyError.message("java.lang.IllegalStateException: closed", "下载失败"))
        assertEquals("下载失败，可以重试一次", FriendlyError.message(null, "下载失败"))
        assertEquals("操作失败，可以重试一次", FriendlyError.message("  ", ""))
        assertEquals("保存失败，可以重试一次", FriendlyError.message("HTTP 500 内部错误", "保存失败"))
    }

    private val TlsHintForTest = "SSL 连接失败：证书不受信任或已过期。"
}
