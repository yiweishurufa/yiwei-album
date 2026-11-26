package com.hark.shiguang

/**
 * 1.7.17 更新内容：解析仓库根目录的 CHANGELOG.md（纯函数，单元测试 ChangelogTest）。
 *
 * 格式（CHANGELOG.md 顶部说明里约定）：
 * ```
 * ## 1.7.17（2026-11-27）
 * ### 新增
 * - **标题**：说明
 * ```
 * 用处：设置 → 关于 → 更新内容（随包附带的 assets/CHANGELOG.md，构建时从仓库根目录拷贝）；
 * 发现新版本时的更新窗口（从 GitHub 发布里下载的 CHANGELOG.md，取「当前版本之后 ~ 新版本」之间的各节）。
 */
object Changelog {
    data class Group(val title: String, val items: List<String>)
    data class Version(val version: String, val date: String, val groups: List<Group>)

    private val HEAD = Regex("^##\\s+([0-9][0-9A-Za-z.\\-]*)\\s*(?:[（(]([^）)]*)[）)])?\\s*$")

    /** 解析全部版本节，按文件中的顺序（新的在前）。不认识的行忽略；条目可跨行（缩进续行并入上一条）。 */
    fun parse(md: String): List<Version> {
        val out = ArrayList<Version>()
        var ver: String? = null; var date = ""
        val groups = ArrayList<Group>()
        var gTitle: String? = null
        val items = ArrayList<String>()
        fun flushGroup() {
            if (gTitle != null || items.isNotEmpty()) groups += Group(gTitle ?: "更新", items.toList())
            gTitle = null; items.clear()
        }
        fun flushVersion() {
            flushGroup()
            ver?.let { v -> if (groups.isNotEmpty()) out += Version(v, date, groups.filter { it.items.isNotEmpty() }) }
            ver = null; date = ""; groups.clear()
        }
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            val h = HEAD.find(line.trim())
            when {
                line.startsWith("## ") && h != null -> { flushVersion(); ver = h.groupValues[1]; date = h.groupValues[2].trim() }
                line.startsWith("## ") -> flushVersion()   // 不是版本号的二级标题：结束当前节
                ver == null -> {}
                line.startsWith("### ") -> { flushGroup(); gTitle = line.removePrefix("### ").trim() }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> items += line.trimStart().drop(2).trim()
                line.isNotBlank() && items.isNotEmpty() && raw.startsWith(" ") -> items[items.size - 1] = items.last() + line.trim()
                else -> {}
            }
        }
        flushVersion()
        return out.filter { it.groups.isNotEmpty() }
    }

    /** 比 [installed] 新、且不比 [target] 新的版本节（新的在前）。[target] 为空时不设上限。 */
    fun between(all: List<Version>, installed: String, target: String?): List<Version> =
        all.filter { UpdateRules.cmp(it.version, installed) > 0 && (target.isNullOrBlank() || UpdateRules.cmp(it.version, target) <= 0) }

    /** 去掉 Markdown 的加粗 / 行内代码标记，只留文字。 */
    fun plain(s: String): String = s.replace("**", "").replace("`", "")

    /** 一条里「**标题**：说明」拆成（标题，标题后面的原文，含冒号或括号）；没有加粗开头时为 null。给界面把标题加粗用。 */
    fun lead(s: String): Pair<String, String>? {
        val m = Regex("^\\*\\*(.+?)\\*\\*(.*)$").find(s) ?: return null
        return m.groupValues[1] to plain(m.groupValues[2])
    }

    /** 旧版 CI 写的 notes 只是「CI #128：版本号升至 1.7.16（versionCode 99）」——没有用户能看的内容，不显示。 */
    fun isLegacyNotes(notes: String): Boolean {
        val t = notes.trim()
        return t.isEmpty() || Regex("^CI #\\d+[：:]\\s*版本号升至").containsMatchIn(t)
    }
}
