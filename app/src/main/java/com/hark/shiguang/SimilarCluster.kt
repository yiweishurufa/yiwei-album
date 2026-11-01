package com.hark.shiguang

/**
 * 用户 2026-11-01 反馈：「相似」第 1 组把 8700+ 张毫不相干的照片（黑图、人像、风景）归成一组并默认勾选待删。
 *
 * 根因：旧算法是并查集 + 64 位 dHash 汉明距离 ≤ 6，只要 A 像 B、B 像 C 就全部并成一组（传递串联）；
 * 而暗图、纯色图、只解码出上半截的图（无 EXIF 缩略图时用前 128KB 解码，下半截是空白）的 dHash
 * 大部分位都是 0，彼此距离很近，成了把整个相册串起来的「桥」。
 *
 * 现在的做法（纯函数，不依赖 Android，便于单测）：
 * 1. 低信息量的哈希（几乎全 0 / 全 1、或连续多行为 0）直接不参与相似；信息量偏低的只允许极小距离、且拍摄时间必须很近。
 * 2. 完全链接：一张图要和组里「每一张」都足够像才能加入，不会再链式串联。
 * 3. 组大小上限 [MAX_GROUP]；拍摄时间都已知时相差超过 [MAX_GAP_MS] 不算相似；横竖方向明显不同不算相似。
 */
object SimilarCluster {
    /** 一组最多几张；更多的相似图会另起一组。 */
    const val MAX_GROUP = 30
    /** 超过这个张数的组不默认勾选（让用户自己挑）。 */
    const val AUTO_SELECT_MAX = 20
    /** 只和按时间排序后前后这么多张比较（和旧算法一致），控制 O(n·W)。 */
    const val WINDOW = 400
    /** 拍摄时间都已知时，相差超过 2 天不算相似。 */
    const val MAX_GAP_MS = 2L * 24 * 3600_000
    /** 低信息量哈希只和 10 分钟内的照片比较（连拍）。 */
    const val LOW_INFO_GAP_MS = 10L * 60_000
    /** 低信息量哈希允许的最大距离。 */
    const val LOW_INFO_DIST = 1
    /** 算法版本：写进相似缓存，算法变了旧缓存（可能含巨型组）自动作废。 */
    const val ALGO = 2

    private fun zeroRows(h: Long): Int { var n = 0; for (r in 0 until 8) if ((h ushr (r * 8)) and 0xFF == 0L) n++; return n }
    private fun fullRows(h: Long): Int { var n = 0; for (r in 0 until 8) if ((h ushr (r * 8)) and 0xFF == 0xFFL) n++; return n }

    /** 几乎没有信息（纯色、全黑、大半张空白）：不参与相似分组。 */
    fun excluded(h: Long): Boolean {
        val b = java.lang.Long.bitCount(h)
        return b <= 6 || b >= 58 || zeroRows(h) >= 5 || fullRows(h) >= 5
    }

    /** 信息量偏低（偏暗、大片单色）：只允许 [LOW_INFO_DIST] 的距离和 [LOW_INFO_GAP_MS] 内的时间差。 */
    fun lowInfo(h: Long): Boolean {
        val b = java.lang.Long.bitCount(h)
        return b < 14 || b > 50 || zeroRows(h) >= 3 || fullRows(h) >= 3
    }

    /** 0 = 未知方向；1 横；-1 竖；接近正方形也算 0。 */
    private fun orient(w: Int, h: Int): Int {
        if (w <= 0 || h <= 0) return 0
        val r = w.toDouble() / h
        return if (r > 1.15) 1 else if (r < 1 / 1.15) -1 else 0
    }

    /**
     * 两张图是否「足够像」。[maxDist] 普通图的汉明距离上限。
     * [ti]/[tj] 拍摄时间毫秒，<= 0 表示未知。[dupI]/[dupJ] 重复判定 key，相同的是「重复」不是「相似」。
     */
    fun close(hi: Long, hj: Long, ti: Long, tj: Long, oi: Int, oj: Int, dupI: String, dupJ: String, maxDist: Int): Boolean {
        if (dupI.isNotEmpty() && dupI == dupJ) return false
        if (oi != 0 && oj != 0 && oi != oj) return false
        val known = ti > 0 && tj > 0
        val gap = if (known) kotlin.math.abs(ti - tj) else Long.MAX_VALUE
        if (known && gap > MAX_GAP_MS) return false
        val low = lowInfo(hi) || lowInfo(hj)
        if (low && gap > LOW_INFO_GAP_MS) return false   // 时间未知的低信息量图也不配对
        val d = java.lang.Long.bitCount(hi xor hj)
        return d <= if (low) minOf(maxDist, LOW_INFO_DIST) else maxDist
    }

    /**
     * 输入按时间排序。返回每组成员的下标（每组 ≥ 2 张），按组大小降序。
     * [widths]/[heights] 未知时传 0。
     */
    fun cluster(
        hashes: LongArray, times: LongArray, widths: IntArray, heights: IntArray, dupKeys: List<String>,
        maxDist: Int = 6, maxGroup: Int = MAX_GROUP, window: Int = WINDOW,
    ): List<List<Int>> {
        val n = hashes.size
        require(times.size == n && widths.size == n && heights.size == n && dupKeys.size == n)
        val ori = IntArray(n) { orient(widths[it], heights[it]) }
        val groups = ArrayList<MutableList<Int>>()
        // 仍可能接收新成员的组（最后一个成员在窗口内）
        val open = ArrayList<MutableList<Int>>()
        for (i in 0 until n) {
            if (excluded(hashes[i])) continue
            open.removeAll { g -> i - g.last() > window }
            var best: MutableList<Int>? = null
            var bestScore = Int.MAX_VALUE
            for (g in open) {
                if (g.size >= maxGroup) continue
                var worst = 0
                var ok = true
                for (j in g) {
                    if (!close(hashes[i], hashes[j], times[i], times[j], ori[i], ori[j], dupKeys[i], dupKeys[j], maxDist)) { ok = false; break }
                    worst = maxOf(worst, java.lang.Long.bitCount(hashes[i] xor hashes[j]))
                }
                if (ok && worst < bestScore) { bestScore = worst; best = g }
            }
            if (best != null) best.add(i)
            else { val g = mutableListOf(i); groups.add(g); open.add(g) }
        }
        return groups.filter { it.size > 1 }.sortedByDescending { it.size }
    }

    /** 这一组是否默认勾选「除推荐保留外都删」：太大的组不默认勾选。 */
    fun autoSelect(groupSize: Int): Boolean = groupSize in 2..AUTO_SELECT_MAX
}
