package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 用户 2026-11-01 反馈：相似第 1 组 8700+ 张不相干照片。这些用例防止回归。 */
class SimilarClusterTest {
    /** 一个「信息量正常」的基准哈希：每行既有 0 也有 1。 */
    private val base = 0x5A3C_96E1_2B7D_C418L

    private fun flip(h: Long, vararg bits: Int): Long { var x = h; bits.forEach { x = x xor (1L shl it) }; return x }

    private fun run(hs: List<Long>, times: List<Long> = hs.indices.map { 1_700_000_000_000L + it * 1000L }, maxGroup: Int = SimilarCluster.MAX_GROUP) =
        SimilarCluster.cluster(hs.toLongArray(), times.toLongArray(), IntArray(hs.size), IntArray(hs.size), hs.indices.map { "k$it" }, 6, maxGroup)

    @Test fun chainOfSmallStepsDoesNotMergeIntoOneBigGroup() {
        // 每张只比上一张多翻 2 位：相邻距离 2（≤6），但首尾相距 2*N。旧并查集会把整条链并成一组。
        val chain = ArrayList<Long>()
        var h = base
        for (i in 0 until 30) { chain.add(h); h = flip(h, (i * 2) % 64, (i * 2 + 1) % 64) }
        val groups = run(chain)
        val biggest = groups.maxOfOrNull { it.size } ?: 0
        assertTrue("链式相似不应合并成大组，实际最大组 $biggest", biggest <= 4)
        // 组内任意两张都必须足够像（完全链接）
        groups.forEach { g -> for (a in g) for (b in g) assertTrue(java.lang.Long.bitCount(chain[a] xor chain[b]) <= 6) }
    }

    @Test fun allBlackImagesAreNotGrouped() {
        val blacks = List(50) { 0L }
        assertTrue(run(blacks).isEmpty())
        assertTrue(SimilarCluster.excluded(0L))
        assertTrue(SimilarCluster.excluded(-1L))
    }

    @Test fun blackImagesDoNotAttractNormalPhotos() {
        // 一张正常照片 + 一堆几乎全黑（只有 1~3 位为 1）的图：旧算法会以黑图为桥把它们串到一起
        val hs = ArrayList<Long>()
        hs.add(base)
        for (i in 0 until 40) hs.add(flip(0L, i % 64, (i + 7) % 64))
        val groups = run(hs)
        assertTrue(groups.none { 0 in it })
        assertTrue(groups.isEmpty())
    }

    @Test fun halfDecodedImagesAreLowInfo() {
        // 只解码出上半截：下面 5 行 dHash 全为 0
        val half = 0x0000_0000_00A5_3C96L
        assertTrue(SimilarCluster.excluded(half))
        val threeRows = 0x0000_005A_C3A5_3C96L
        assertTrue(SimilarCluster.lowInfo(threeRows))
    }

    @Test fun realBurstStillGroups() {
        val hs = listOf(base, flip(base, 3), flip(base, 3, 17), flip(base, 40))
        val groups = run(hs)
        assertEquals(1, groups.size)
        assertEquals(4, groups[0].size)
    }

    @Test fun groupSizeIsCapped() {
        val hs = List(100) { if (it % 2 == 0) base else flip(base, 5) }
        val groups = run(hs)
        assertTrue(groups.all { it.size <= SimilarCluster.MAX_GROUP })
        assertEquals(100, groups.sumOf { it.size })
    }

    @Test fun farApartInTimeIsNotSimilar() {
        val day = 24 * 3600_000L
        val groups = run(listOf(base, flip(base, 1)), listOf(1_700_000_000_000L, 1_700_000_000_000L + 10 * day))
        assertTrue(groups.isEmpty())
    }

    @Test fun exactDuplicatesAreNotSimilar() {
        val g = SimilarCluster.cluster(longArrayOf(base, base), longArrayOf(1, 2), IntArray(2), IntArray(2), listOf("same", "same"))
        assertTrue(g.isEmpty())
    }

    @Test fun portraitAndLandscapeAreNotSimilar() {
        val g = SimilarCluster.cluster(longArrayOf(base, base), longArrayOf(1, 2), intArrayOf(4000, 3000), intArrayOf(3000, 4000), listOf("a", "b"))
        assertTrue(g.isEmpty())
    }

    @Test fun bigGroupsAreNotAutoSelected() {
        assertTrue(SimilarCluster.autoSelect(2))
        assertTrue(SimilarCluster.autoSelect(SimilarCluster.AUTO_SELECT_MAX))
        assertFalse(SimilarCluster.autoSelect(SimilarCluster.AUTO_SELECT_MAX + 1))
        assertFalse(SimilarCluster.autoSelect(8747))
    }
}
