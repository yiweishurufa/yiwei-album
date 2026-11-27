package com.hark.shiguang.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.7.27 提示条前的成功勾：只给「已…」且不含失败类字眼的提示。 */
class SnackKindTest {
    @Test fun doneMessagesGetCheck() {
        assertTrue(SnackKind.isSuccess("已收藏 3 项"))
        assertTrue(SnackKind.isSuccess("已取消隐藏"))
        assertTrue(SnackKind.isSuccess("已复制图片，可以到聊天里粘贴"))
    }

    @Test fun failuresAndOthersDoNot() {
        assertFalse(SnackKind.isSuccess("删除失败：网络错误"))
        assertFalse(SnackKind.isSuccess("已删除 2 项，3 项失败"))
        assertFalse(SnackKind.isSuccess("正在准备分享…"))
        assertFalse(SnackKind.isSuccess(""))
    }
}
