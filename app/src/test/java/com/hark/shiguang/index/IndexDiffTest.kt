package com.hark.shiguang.index

import com.hark.shiguang.cloud.CloudEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexDiffTest {
    private fun e(p: String, size: Long = 1, m: Long = 1000, v: Boolean = false) =
        CloudEntry(p.substringAfterLast('/'), p, false, size, m, null, !v, v)

    private fun keys(vararg es: CloudEntry) = es.associate { it.path to IndexDiff.keyOf(it) }

    @Test fun `没变化就不写`() {
        val a = e("/a.jpg"); val b = e("/b.mp4", v = true)
        assertTrue(IndexDiff.diff(keys(a, b), listOf(b, a)).isEmpty)
    }

    @Test fun `新增_改动_删除各自分开`() {
        val a = e("/a.jpg"); val b = e("/b.jpg"); val c = e("/c.jpg")
        val r = IndexDiff.diff(keys(a, b), listOf(a, b.copy(size = 99), c))
        assertEquals(setOf("/b.jpg", "/c.jpg"), r.upserts.map { it.path }.toSet())
        assertEquals(emptyList<String>(), r.deletes)
        val r2 = IndexDiff.diff(keys(a, b), listOf(a))
        assertEquals(listOf("/b.jpg"), r2.deletes)
        assertTrue(r2.upserts.isEmpty())
    }

    @Test fun `新列表里重复路径只留最后一个`() {
        val r = IndexDiff.diff(emptyMap(), listOf(e("/a.jpg", size = 1), e("/a.jpg", size = 2)))
        assertEquals(1, r.upserts.size)
        assertEquals(2L, r.upserts[0].size)
    }

    @Test fun `空旧索引全部新增_空新索引全部删除`() {
        val a = e("/a.jpg")
        assertEquals(1, IndexDiff.diff(emptyMap(), listOf(a)).upserts.size)
        assertEquals(listOf("/a.jpg"), IndexDiff.diff(keys(a), emptyList()).deletes)
    }

    @Test fun `月份分段与旧版一致`() {
        assertEquals(IndexDiff.NO_MONTH, IndexDiff.monthOf(0))
        assertEquals(7, IndexDiff.monthOf(1_700_000_000_000L).length)
    }
}
