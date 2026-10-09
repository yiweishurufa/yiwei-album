package com.hark.shiguang.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Unified thin line icons: 1.6 stroke, 2.2 when selected. 24x24 viewport, SVG path data. */
object LI {
    private val cache = HashMap<String, ImageVector>()
    private fun icon(name: String, bold: Boolean, vararg paths: String): ImageVector = cache.getOrPut("$name$bold") {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(addPathNodes(d), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = if (bold) 2.2f else 1.6f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        b.build()
    }
    private const val CIRC = "a%s %s 0 1 0 %s 0 a%s %s 0 1 0 -%s 0"
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} $cy " + CIRC.format(java.util.Locale.US, r, r, 2 * r, r, r, 2 * r)

    fun photos(b: Boolean = false) = icon("photos", b, "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", circle(8.5f, 9f, 1.6f), "M21 15l-5-5L5 20")
    fun videos(b: Boolean = false) = icon("videos", b, "M4 6h11a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M17 10.5l5-3v9l-5-3")
    fun calendar(b: Boolean = false) = icon("calendar", b, "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3 10h18M8 3v4M16 3v4")
    fun edit(b: Boolean = false) = icon("edit", b, "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    fun albums(b: Boolean = false) = icon("albums", b, "M9 3h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M3 8v11a2 2 0 0 0 2 2h11")
    fun discover(b: Boolean = false) = icon("discover", b, "M11 4l1.7 5.3L18 11l-5.3 1.7L11 18l-1.7-5.3L4 11l5.3-1.7z", "M18.5 3v3.5M16.75 4.75h3.5")
    fun settings(b: Boolean = false) = icon("settings", b, circle(12f, 12f, 3f),
        "M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7", circle(12f, 12f, 7f))
    fun search(b: Boolean = false) = icon("search", b, circle(11f, 11f, 6.5f), "M16 16l4.5 4.5")
    fun folder(b: Boolean = false) = icon("folder", b, "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    fun pin(b: Boolean = false) = icon("pin", b, "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z", circle(12f, 9.5f, 2.4f))
    fun copy(b: Boolean = false) = icon("copy", b, "M10 8h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3")
    fun layers(b: Boolean = false) = icon("layers", b, "M12 4l9 5-9 5-9-5z", "M3 14l9 5 9-5")
    fun sparkle(b: Boolean = false) = discover(b)
    fun upload(b: Boolean = false) = icon("upload", b, "M12 15V4", "M7 9l5-5 5 5", "M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3")
    fun lock(b: Boolean = false) = icon("lock", b, "M6 11h12a1 1 0 0 1 1 1v7a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1z", "M8 11V8a4 4 0 0 1 8 0v3")
    fun drive(b: Boolean = false) = icon("drive", b, "M3 13l3-8h12l3 8v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z", "M3 13h18", "M16.5 16h.01")
    fun info(b: Boolean = false) = icon("info", b, circle(12f, 12f, 9f), "M12 11v5", "M12 7.8v.01")
    fun back(b: Boolean = false) = icon("back", b, "M15 5l-7 7 7 7")
    fun chevron(b: Boolean = false) = icon("chev", b, "M9 6l6 6-6 6")
    fun down(b: Boolean = false) = icon("down", b, "M6 9l6 6 6-6")
    fun plus(b: Boolean = false) = icon("plus", b, "M12 5v14M5 12h14")
    fun close(b: Boolean = false) = icon("close", b, "M6 6l12 12M18 6L6 18")
    fun nas(b: Boolean = false) = icon("nas", b, "M6 3h12a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M8 7h8M8 11h8", "M12 17h.01")
    fun backup(b: Boolean = false) = icon("backup", b, "M7 18a4.5 4.5 0 0 1-.6-8.96A6 6 0 0 1 18 9.5a4 4 0 0 1-.5 8.5", "M12 12v8", "M9 15l3-3 3 3")
    fun trash(b: Boolean = false) = icon("trash", b, "M4 7h16", "M9 7V4h6v3", "M6 7l1 13h10l1-13")
    fun person(b: Boolean = false) = icon("person", b, circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0")
    fun swap(b: Boolean = false) = icon("swap", b, "M4 8h14l-3-3", "M20 16H6l3 3")
    fun heart(b: Boolean = false) = icon("heart", b, "M12 20s-7.5-4.6-7.5-10A4.5 4.5 0 0 1 12 7a4.5 4.5 0 0 1 7.5 3c0 5.4-7.5 10-7.5 10z")
    fun clock(b: Boolean = false) = icon("clock", b, circle(12f, 12f, 9f), "M12 7v5l3 2")
    fun film(b: Boolean = false) = icon("film", b, "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4")
    fun tv(b: Boolean = false) = icon("tv", b, "M4 7h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M8 3l4 4 4-4")
    fun play(b: Boolean = false) = icon("play", b, "M7 5l12 7-12 7z")
    fun sort(b: Boolean = false) = icon("sort", b, "M4 7h12M4 12h9M4 17h6", "M18 10v9M15.5 16.5L18 19l2.5-2.5")
    fun refresh(b: Boolean = false) = icon("refresh", b, "M20 11a8 8 0 1 0-2.3 5.7", "M20 5v6h-6")
    fun music(b: Boolean = false) = icon("music", b, "M9 18V5l11-2v13", circle(6f, 18f, 3f), circle(17f, 16f, 3f))
    fun map(b: Boolean = false) = icon("map", b, "M9 4L3 6v14l6-2 6 2 6-2V4l-6 2z", "M9 4v14M15 6v14")
}
