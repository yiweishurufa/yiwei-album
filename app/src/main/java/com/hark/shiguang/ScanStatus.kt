package com.hark.shiguang

/**
 * 1.0.2: the ONE source of scan progress. The background notification and the in-app cards / headers read the same
 * numbers from here, so they always agree (previously the notification showed folder counts the app never showed).
 */
object ScanStatus {
    data class S(val title: String, val text: String, val done: Int, val total: Int)

    /** WebDAV listing: folders listed / folders known so far (grows while new sub-folders are discovered). */
    fun listing(lib: DavLib): S? = if (!lib.scanning) null else {
        val t = maxOf(lib.scanKnown, lib.scanListed)
        S("扫描中 ${lib.scanListed} / $t 个文件夹", "WebDAV · 已找到 ${lib.scanFound} 个照片和视频", lib.scanListed, t)
    }

    /** Whatever is running now, in priority order; null when idle. */
    fun current(): Triple<String, String, Pair<Int, Int>>? {
        Dav.lib()?.let { lib ->
            listing(lib)?.let { return Triple(it.title, it.text, it.done to it.total) }
            val fl = Faces.lib(lib.accountId)
            if (Analyzer.running) return Triple("本地分析 ${lib.analyzed} / ${lib.imagesCount()}", "地点、重复、相似、实况", lib.analyzed to lib.imagesCount())
            if (Faces.running) return Triple("人脸识别 ${fl.done} / ${fl.total}", "只在本机进行", fl.done to fl.total)
        }
        if (AiRunner.running) return Triple("AI 整理 ${AiRunner.done} / ${AiRunner.total}", "${AiRunner.status.ifEmpty { "整理中" }} · 只发送缩略图", AiRunner.done to AiRunner.total)
        Movies.lib()?.takeIf { it.scanning }?.let { m -> return Triple("正在扫描影音", m.phase.ifEmpty { "整理海报和简介" }, m.done to m.total) }
        // 1.0.3 #4
        Music.lib()?.takeIf { it.scanning }?.let { m -> return Triple("正在扫描音乐" + (if (m.total > 0) " ${m.done} / ${m.total}" else ""), m.phase.ifEmpty { "读取歌曲信息" }, m.done to m.total) }
        return null
    }
}
