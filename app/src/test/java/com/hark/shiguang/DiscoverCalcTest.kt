package com.hark.shiguang

import com.hark.shiguang.data.Photo
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** 用户 2026-10-24 要求继续优化：发现页统计改成后台纯函数计算，口径必须与原来主线程版本一致。 */
class DiscoverCalcTest {
    private var seq = 0
    private fun photo(path: String, time: String = "2024:05:01 10:00:00", video: Boolean = false) = Photo(
        seq++, "u$seq", video, false, path.substringAfterLast('/'), time, 100, 100, 0, false, "", "", "", null,
        "", "", "", "", "", "", 1000L, "", path, source = "acc", cloudPath = path,
    )

    private val today = LocalDate.of(2026, 10, 24)
    private val a = photo("/a.jpg", "2024:10:24 09:00:00")
    private val b = photo("/b.jpg", "2025:10:24 09:00:00")
    private val c = photo("/c.jpg", "2025:03:01 09:00:00")
    private val v = photo("/v.mp4", "2025:10:24 09:00:00", video = true)
    private val all = listOf(a, b, c, v)
    private val meta = mapOf(
        "/a.jpg" to Meta(city = "杭州", aiCat = "人像", done = true, aiFaces = 2),
        "/b.jpg" to Meta(city = "杭州", aiCat = "风景", done = true, aiFaces = 1),
        "/c.jpg" to Meta(city = "青岛", done = false, aiFaces = 0),
        "/v.mp4" to Meta(city = "青岛", done = true),
    )

    @Test fun `地点按城市分组，照片多的在前，空城市不算`() {
        val r = DiscoverCalc.places(all + photo("/x.jpg"), meta::get)
        assertEquals(listOf("杭州", "青岛"), r.map { it.first })
        assertEquals(listOf(a, b), r[0].second)
        assertEquals(listOf(c, v), r[1].second)
    }

    @Test fun `分类只算有 AI 分类的`() {
        val r = DiscoverCalc.categories(all, meta::get)
        assertEquals(setOf("人像", "风景"), r.map { it.first }.toSet())
        assertEquals(2, DiscoverCalc.aiDone(all, meta::get))
    }

    @Test fun `人脸待识别：不含视频、已识别、AI 判定无人脸或无人类别`() {
        // a 已识别；b 是风景（跳过）；c aiFaces=0（跳过）；v 是视频 → 0
        assertEquals(0, DiscoverCalc.facePending(all, meta::get) { it == "/a.jpg" })
        // 没有分析结果的照片不跳过
        assertEquals(2, DiscoverCalc.facePending(all + photo("/n.jpg"), meta::get) { false })
        assertFalse(DiscoverCalc.skipFaceByAi(null))
        assertTrue(DiscoverCalc.skipFaceByAi(Meta(aiCat = "截图")))
    }

    @Test fun `compute 一次给出全部统计，隐藏照片不进地点和回忆`() {
        val s = DiscoverCalc.compute(all, { l -> l.filter { it !== b } }, meta::get, meta.values, { false }, today)
        assertEquals(3, s.imgs)
        assertEquals(3, s.analyzedN)     // 沿用原口径：数 meta 里 done 的条目（含视频）
        assertEquals(2, s.aiN)
        assertEquals(listOf("青岛", "杭州"), s.places.map { it.first })
        assertEquals(1, s.memories.size)  // b 被隐藏，只剩 2024 年的 a；视频 v 不进回忆
        assertEquals(2, s.memories[0].yearsAgo)
        assertEquals(1, s.faceTodo)       // 只有 a（b 风景、c 无人脸、v 视频）
    }

    @Test fun `空图库不出错`() {
        val s = DiscoverCalc.compute(emptyList(), { it }, { null }, emptyList(), { false }, today)
        assertEquals(DiscoverStats(), s)
    }
}
