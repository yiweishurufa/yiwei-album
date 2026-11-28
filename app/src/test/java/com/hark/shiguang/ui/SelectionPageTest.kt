package com.hark.shiguang.ui

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 1.7.30：多选栏「全选」只认最上面那一页自己登记的网格（Nav 栈只有首页时 = 首页）。
 * 以前首页网格一直登记着：在搜索页、大文件页这类没有登记网格的页面多选时，「全选」选的是首页的照片；
 * 上层页面离开时把登记清成 null，回到首页后「全选」消失。
 */
class SelectionPageTest {
    private val home: () -> List<com.hark.shiguang.data.Photo> = { emptyList() }
    private val other: () -> List<com.hark.shiguang.data.Photo> = { emptyList() }

    @After fun tearDown() { Selection.unregister(home); Selection.unregister(other) }

    @Test fun onlyTheTopPagesGridCounts() {
        Selection.register(Selection.HOME_PAGE, home)
        Selection.register("3:Collection", other)   // 栈里没有这个页面（Nav 栈 ≤ 1 = 首页在最上面）
        assertSame(home, Selection.page)
    }

    @Test fun leavingAnUpperPageKeepsHomeRegistered() {
        Selection.register(Selection.HOME_PAGE, home)
        Selection.register("3:Collection", other)
        Selection.unregister(other)
        assertSame(home, Selection.page)
        Selection.unregister(home)
        assertNull(Selection.page)
    }

    @Test fun reRegisteringDoesNotDuplicate() {
        Selection.register(Selection.HOME_PAGE, home)
        Selection.register(Selection.HOME_PAGE, home)
        Selection.unregister(home)
        assertNull(Selection.page)
    }
}
