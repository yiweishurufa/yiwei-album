package com.hark.shiguang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChangelogTest {
    private val md = """
# 更新日志

只写用户能感受到的变化。

## 1.7.18（2026-11-28）

### 修复

- **甲**：一
  续行

## 1.7.17（2026-11-27）

### 新增

- **更新内容**：设置 → 关于
- 没有加粗的一条

### 移除

- 旧开关

## 1.7.16（2026-11-27）

### 修复

- **闪退**：回退
""".trimIndent()

    @Test fun parsesVersionsGroupsItems() {
        val all = Changelog.parse(md)
        assertEquals(listOf("1.7.18", "1.7.17", "1.7.16"), all.map { it.version })
        assertEquals("2026-11-27", all[1].date)
        assertEquals(listOf("新增", "移除"), all[1].groups.map { it.title })
        assertEquals(2, all[1].groups[0].items.size)
        assertEquals("**甲**：一续行", all[0].groups[0].items[0])
    }

    @Test fun betweenInstalledAndTarget() {
        val all = Changelog.parse(md)
        assertEquals(listOf("1.7.18", "1.7.17"), Changelog.between(all, "1.7.16", "1.7.18").map { it.version })
        assertEquals(listOf("1.7.17"), Changelog.between(all, "1.7.16", "1.7.17").map { it.version })
        assertEquals(listOf("1.7.18", "1.7.17"), Changelog.between(all, "1.7.16", null).map { it.version })
        assertTrue(Changelog.between(all, "1.7.18", "1.7.18").isEmpty())
    }

    @Test fun leadAndPlain() {
        assertEquals("更新内容" to "：设置 → 关于", Changelog.lead("**更新内容**：设置 → 关于"))
        assertEquals("白胶囊" to "（浅色）：说明", Changelog.lead("**白胶囊**（浅色）：说明"))
        assertNull(Changelog.lead("没有加粗的一条"))
        assertEquals("a b", Changelog.plain("**a** `b`"))
    }

    @Test fun legacyNotes() {
        assertTrue(Changelog.isLegacyNotes("CI #128：版本号升至 1.7.16（versionCode 99）"))
        assertTrue(Changelog.isLegacyNotes("  "))
        assertFalse(Changelog.isLegacyNotes("1.7.17 更新内容\n【新增】"))
    }

    /** 仓库里真正的 CHANGELOG.md 必须能解析出当前版本那一节（App 内「更新内容」和 CI 发布说明都靠它）。 */
    @Test fun repoChangelogHasCurrentVersion() {
        val f = listOf(File("../CHANGELOG.md"), File("CHANGELOG.md")).firstOrNull { it.exists() } ?: return
        val all = Changelog.parse(f.readText())
        assertTrue("CHANGELOG.md 解析不出任何版本", all.isNotEmpty())
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).firstOrNull { it.exists() && it.readText().contains("versionName") } ?: return
        val v = Regex("versionName = \"([^\"]+)\"").find(gradle.readText())?.groupValues?.get(1) ?: return
        // 版本提交在最后：CHANGELOG 顶部那一节可以比 build.gradle.kts 新一版，但不能更旧
        assertTrue("CHANGELOG.md 顶部 ${all[0].version} 比 versionName $v 旧", UpdateRules.cmp(all[0].version, v) >= 0)
    }
}
