package com.hark.shiguang

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.7.29：用户 2026-11-28 同意删掉 5 项价值低的功能（docs/iteration/feature-audit.md §4）。
 * 这里防止它们被改回来，也防止旧存储键又被读：
 * - 设置 → 高级「系统定时后台整理」开关（feat.workmanager）：后台整理固定走 WorkManager；
 * - 界面风格「简洁」（ui.style）：固定玻璃，Android 12 以下照旧降级外观；
 * - 使用引导（onboard.done、Onboarding.kt）；
 * - 大图日期胶囊上飞牛实况的「（未验证）」；
 * - 发现 → 工具「文件夹 · 回收站 · 传输」折叠行（回收站入口移到 相册 页，见 [fnRecycleStillReachable]）。
 */
class RemovedFeaturesTest {
    private val root: File = listOf(File("src/main/java/com/hark/shiguang"), File("app/src/main/java/com/hark/shiguang")).first { it.isDirectory }

    /** 源码里除注释外的全部代码行。 */
    private fun code(): Map<String, String> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { f ->
        f.relativeTo(root).path to f.readLines().filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/**") }.joinToString("\n")
    }

    @Test fun removedSettingsAndKeysAreGone() {
        val banned = listOf("\"feat.workmanager\"", "\"ui.style\"", "\"onboard.done\"", "（未验证）", "Features.workManager", "\"使用引导\"",
            "listOf(\"玻璃\", \"简洁\")", "文件夹 · 回收站 · 传输", "ThemeState.skeuo", "Glass.on", "Skeuo.on")
        val hits = code().flatMap { (name, text) -> banned.filter { text.contains(it) }.map { "$name: $it" } }
        assertTrue("已删除的功能又出现了：$hits", hits.isEmpty())
        assertTrue("Onboarding.kt 应已删除", !File(root, "ui/Onboarding.kt").exists())
        assertTrue("Features.kt 应已删除", !File(root, "Features.kt").exists())
    }

    /** 回收站关系到找回误删的照片：折叠行删了以后，飞牛「相册」页必须还有入口。 */
    @Test fun fnRecycleStillReachable() {
        val albums = code().getValue("ui${File.separator}HomeAlbums.kt")
        assertTrue("相册页应有飞牛回收站入口", albums.contains("Route.Recycle"))
        assertTrue("相册页应有飞牛文件夹入口", albums.contains("Route.Folders("))
    }
}
