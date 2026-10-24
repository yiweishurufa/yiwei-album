package com.hark.shiguang

import com.hark.shiguang.data.Photo
import com.hark.shiguang.ui.MemoryGroup
import com.hark.shiguang.ui.OnThisDay
import java.time.LocalDate

/**
 * 用户 2026-10-24 要求继续优化：WebDAV「发现」页的统计（地点、分类、那年今天、本地分析 / AI 分类进度、待识别人脸数）
 * 以前在主线程按 metaVersion 重算，大图库整理时每隔几秒卡一下。现在由 [DiscoverCalc.compute] 在 Dispatchers.Default
 * 上一次算完，页面只拿结果（见 HomeDiscover 的 rememberDiscoverStats）。
 */
data class DiscoverStats(
    val places: List<Pair<String, List<Photo>>> = emptyList(),
    val cats: List<Pair<String, List<Photo>>> = emptyList(),
    val memories: List<MemoryGroup> = emptyList(),
    /** 照片（不含视频）总数。 */
    val imgs: Int = 0,
    /** 本地分析已完成的条目数（meta.done）。 */
    val analyzedN: Int = 0,
    /** AI 已给出分类的照片数。 */
    val aiN: Int = 0,
    /** 还需要做人脸识别的照片数（不含视频、不含 AI 已判定无人脸 / 无人类别的）。 */
    val faceTodo: Int = 0,
)

/** 纯函数：只依赖传进来的快照和查表函数，不碰 Compose 状态、不碰文件，可在任意线程跑、可单测。 */
object DiscoverCalc {
    /** AI 判成这些类别的照片不做人脸识别（省流量）。 */
    val FACE_SKIP_CATS = setOf("文档", "截图", "美食", "风景", "建筑", "植物", "交通")

    /** AI 整理已判定「无人脸」或属于无人类别：人脸识别跳过。没有分析结果时不跳过。 */
    fun skipFaceByAi(m: Meta?): Boolean = m != null && (m.aiFaces == 0 || m.aiCat in FACE_SKIP_CATS)

    /** 按城市分组（只看可见照片），照片多的在前。 */
    fun places(visible: List<Photo>, meta: (String) -> Meta?): List<Pair<String, List<Photo>>> =
        groupByNonEmpty(visible) { meta(it.cloudPath)?.city }

    /** 按 AI 分类分组（只看可见照片），照片多的在前。 */
    fun categories(visible: List<Photo>, meta: (String) -> Meta?): List<Pair<String, List<Photo>>> =
        groupByNonEmpty(visible) { meta(it.cloudPath)?.aiCat }

    fun aiDone(photos: List<Photo>, meta: (String) -> Meta?): Int =
        photos.count { !it.isVideo && meta(it.cloudPath)?.aiCat?.isNotEmpty() == true }

    fun facePending(photos: List<Photo>, meta: (String) -> Meta?, scanned: (String) -> Boolean): Int =
        photos.count { !it.isVideo && !scanned(it.cloudPath) && !skipFaceByAi(meta(it.cloudPath)) }

    /**
     * 一次算出发现页需要的全部统计。[photos] 是照片列表快照，[visible] 去掉隐藏照片，[metaValues] 是全部分析结果
     * （本地分析计数沿用原口径：数 meta 里 done 的条目）。
     */
    fun compute(
        photos: List<Photo>,
        visible: (List<Photo>) -> List<Photo>,
        meta: (String) -> Meta?,
        metaValues: Collection<Meta>,
        scanned: (String) -> Boolean,
        today: LocalDate = LocalDate.now(),
    ): DiscoverStats {
        val vis = visible(photos)
        return DiscoverStats(
            places = places(vis, meta),
            cats = categories(vis, meta),
            memories = OnThisDay.from(visible(photos.filter { !it.isVideo }), today),
            imgs = photos.count { !it.isVideo },
            analyzedN = metaValues.count { it.done },
            aiN = aiDone(photos, meta),
            faceTodo = facePending(photos, meta, scanned),
        )
    }

    private inline fun groupByNonEmpty(l: List<Photo>, crossinline key: (Photo) -> String?): List<Pair<String, List<Photo>>> =
        l.mapNotNull { p -> key(p)?.takeIf { it.isNotEmpty() }?.let { p to it } }
            .groupBy({ it.second }, { it.first }).toList().sortedByDescending { it.second.size }
}
