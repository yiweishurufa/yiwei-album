package com.hark.shiguang.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.7.16：用户真机反馈播放影视视频闪退（无崩溃日志）。两类静态 / 运行检查：
 * 1. 每个 Kubai 图标（KubaiIcons.kt 生成的路径字符串在运行时才由 addPathNodes 解析）都能建出来——生成脚本出一个坏路径，
 *    用到它的页面一打开就会崩，编译期发现不了。
 * 2. 播放器文件里不再出现玻璃材质（Modifier.glass / glassPress / GlassSurface / RenderEffect）——视频在 SurfaceView 上，
 *    1.7.16 起播放器回到 1.7.5 的纯色半透明底，防止以后又被改回去。
 */
class PlayerSafetyTest {
    @Test fun everyKubaiIconBuilds() {
        var n = 0
        val failures = ArrayList<String>()
        for (m in KI::class.java.declaredMethods) {
            if (!java.lang.reflect.Modifier.isPublic(m.modifiers) || m.returnType.name != "androidx.compose.ui.graphics.vector.ImageVector") continue
            val variants: List<Array<Any?>> = when {
                m.parameterCount == 0 -> listOf(emptyArray())
                m.parameterTypes.all { it == java.lang.Boolean.TYPE } -> listOf(arrayOfNulls<Any?>(m.parameterCount).map { false }.toTypedArray(), arrayOfNulls<Any?>(m.parameterCount).map { true }.toTypedArray())
                else -> continue
            }
            for (args in variants) {
                runCatching { m.invoke(KI, *args) }.onFailure { failures += "${m.name}(${args.joinToString()}): ${(it.cause ?: it).message}" }
                n++
            }
        }
        assertTrue("no icons found", n > 50)
        assertEquals("icons that fail to build: $failures", 0, failures.size)
    }

    @Test fun playerFilesDrawNoGlassOverTheVideo() {
        val root = listOf(File("src/main/java/com/hark/shiguang/ui"), File("app/src/main/java/com/hark/shiguang/ui")).first { it.isDirectory }
        val banned = listOf(".glass(", "glassPress(", "GlassSurface(", "RenderEffect", "glassSource(")
        for (name in listOf("MoviePlayer.kt", "PlayerPanels.kt")) {
            val text = File(root, name).readLines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
            for (b in banned) assertTrue("$name 里不该再用 $b（播放器视频层上不叠玻璃，见 1.7.16）", !text.contains(b))
        }
    }

    /** 1.7.37：播放器按钮点击里抛出的异常被接住（记诊断日志 + 提示），不再让 App 退出；取消异常照常往外抛。 */
    @Test fun playerClickSwallowsExceptions() {
        var ran = false
        playerSafe("测试") { ran = true; throw IllegalStateException("boom") }()
        assertTrue(ran)
        val cancel = runCatching { playerSafe("取消") { throw kotlinx.coroutines.CancellationException("c") }() }.exceptionOrNull()
        assertTrue(cancel is kotlinx.coroutines.CancellationException)
    }

    /** 1.7.37：播放器文件里每个点击处理都要经过 playerSafe（面包屑 + 接住异常），新加按钮时别漏掉。 */
    @Test fun everyPlayerClickGoesThroughPlayerSafe() {
        val root = listOf(File("src/main/java/com/hark/shiguang/ui"), File("app/src/main/java/com/hark/shiguang/ui")).first { it.isDirectory }
        val bad = ArrayList<String>()
        for (name in listOf("MoviePlayer.kt", "PlayerPanels.kt")) {
            File(root, name).readLines().forEachIndexed { i, raw ->
                val l = raw.trim()
                if (l.startsWith("//") || l.startsWith("*") || l.startsWith("import ")) return@forEachIndexed
                val clicky = l.contains("onClick = {") || l.contains(".clickable {") || (l.contains(".clickable(") && !l.contains("{ }"))
                if (clicky && !l.contains("playerSafe(")) bad += "$name:${i + 1}"
            }
        }
        assertEquals("这些点击没有经过 playerSafe：$bad", 0, bad.size)
    }
}
