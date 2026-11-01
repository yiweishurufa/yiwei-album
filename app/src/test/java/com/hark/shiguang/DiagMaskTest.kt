package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 优化 10（用户 2026-11-02）：导出的诊断日志不含密码 / token / 链接参数。 */
class DiagMaskTest {
    @Test fun `链接查询参数打码`() =
        assertEquals("open https://cdn.123pan.cn/a/b.wav?*** ok", Diag.mask("open https://cdn.123pan.cn/a/b.wav?sign=abc&t=123&uid=9 ok"))
    @Test fun `用户名密码打码`() = assertFalse(Diag.mask("http://user:pw@nas.local/dav").contains("pw"))
    @Test fun `token 打码`() = assertEquals("accesstoken=***", Diag.mask("accesstoken=abcdef"))
    @Test fun `普通文字不变`() = assertEquals("[PLAYER] first frame after 120 ms", Diag.mask("[PLAYER] first frame after 120 ms"))
}
