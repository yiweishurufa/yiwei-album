package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicMarksTest {
    @Test fun `最近播放新的在前且不重复`() {
        assertEquals(listOf("a"), MusicMarks.push(emptyList(), "a"))
        assertEquals(listOf("b", "a"), MusicMarks.push(listOf("a"), "b"))
        assertEquals(listOf("a", "b"), MusicMarks.push(listOf("b", "a"), "a"))
        assertEquals(listOf("b", "a"), MusicMarks.push(listOf("b", "a"), ""))
    }

    @Test fun `最近播放超过上限丢最旧的`() {
        val l = (1..100).map { "$it" }
        val r = MusicMarks.push(l, "new")
        assertEquals(100, r.size)
        assertEquals("new", r.first())
        assertEquals("99", r.last())
    }

    @Test fun `喜欢再点一次就取消`() {
        assertEquals(listOf("x"), MusicMarks.toggle(emptyList(), "x"))
        assertEquals(listOf("y", "x"), MusicMarks.toggle(listOf("x"), "y"))
        assertEquals(listOf("x"), MusicMarks.toggle(listOf("y", "x"), "y"))
    }
}
