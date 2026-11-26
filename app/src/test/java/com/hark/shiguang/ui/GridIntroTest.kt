package com.hark.shiguang.ui

import com.hark.shiguang.data.Photo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.7.12 照片网格首屏错落淡入：每格的「行 + 列」近似位置。 */
class GridIntroTest {
    private var seq = 0
    private fun cell(): Entry.Cell {
        val p = Photo(seq, "u$seq", false, false, "p$seq.jpg", "2024:05:01 10:00:00", 100, 100, 0, false, "", "", "", null,
            "", "", "", "", "", "", 1000L, "", "/p$seq.jpg", source = "acc", cloudPath = "/p$seq.jpg")
        return Entry.Cell(p, seq++)
    }
    private fun header(k: String) = Entry.Header(k, k, "")

    @Test fun `标题不参与，格子按行加列递增`() {
        val e = listOf(header("a"), cell(), cell(), cell(), cell(), cell())
        // 3 列：第一行 0 1 2，第二行 1 2
        assertArrayEquals(intArrayOf(-1, 0, 1, 2, 1, 2), gridIntroDiagonals(e, 3, 60))
    }

    @Test fun `第二个日期标题占一行，下面的格子从更下面一行开始`() {
        val e = listOf(header("a"), cell(), cell(), header("b"), cell())
        // 第 0 行两格；标题 b 占第 1 行；下一格在第 2 行
        assertArrayEquals(intArrayOf(-1, 0, 1, -1, 2), gridIntroDiagonals(e, 4, 60))
    }

    @Test fun `只算前 max 个条目`() {
        val e = List(100) { cell() }
        val r = gridIntroDiagonals(e, 4, 60)
        assertEquals(60, r.size)
        assertEquals(14 + 3, r[59])   // 第 59 格：第 14 行第 3 列
    }

    @Test fun `空列表`() {
        assertEquals(0, gridIntroDiagonals(emptyList(), 4, 60).size)
    }
}
