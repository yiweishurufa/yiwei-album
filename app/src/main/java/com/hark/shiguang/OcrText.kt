package com.hark.shiguang

import java.util.Locale

/**
 * 1.1.0「图中文字」搜索的纯逻辑（不依赖 Android，单元测试见 OcrTextTest）：文本归一化、搜索词拆分、匹配，
 * 以及一张照片要不要（重新）识别的判断。
 *
 * 归一化：全角 → 半角（Ａ→A、１→1、（→(、全角空格→空格），大小写不敏感，去掉所有空白
 * （OCR 常在汉字之间插空格、按行断开，用户输入时也不会照着打）。繁简转换：仓库里没有现成的转换表，暂不做。
 */
object OcrText {
    /** 识别结果最多存这么多字（一张海报/截图很少超过；防止一张长图把数据库撑大）。 */
    const val MAX_TEXT = 2000

    const val STATE_DONE = 1
    /** 没有可用的小图（非 JPEG 且网格缩略图还没缓存，或 EXIF 缩略图太小）：不下原图，等以后有缩略图再试。 */
    const val STATE_NO_IMAGE = 2
    /** [STATE_NO_IMAGE] 的照片隔多久没有缓存缩略图也重试一次。 */
    const val NO_IMAGE_RETRY_MS = 7L * 24 * 3600 * 1000

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch) {
                '\u3000' -> ' '
                in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue
            sb.append(c)
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    /** 搜索框里的一句话 → 归一化后的词（空格、逗号、顿号分隔，全部都要出现）。 */
    fun terms(query: String): List<String> =
        query.split(Regex("[\\s\\u3000,，、;；]+")).map(::normalize).filter { it.isNotEmpty() }.distinct()

    /** [norm] 是 [normalize] 过的图中文字；每个词都出现才算匹配。没有词时不匹配。 */
    fun matches(norm: String, terms: List<String>): Boolean = terms.isNotEmpty() && norm.isNotEmpty() && terms.all { norm.contains(it) }

    /** 数据库先用最长的一个词粗筛（LIKE），其余在内存里用 [matches] 精确判断。 */
    fun prefilterTerm(terms: List<String>): String? = terms.maxByOrNull { it.length }

    /** LIKE 模式（配合 ESCAPE '\'）：转义 % _ \，前后加 %。 */
    fun likePattern(term: String): String {
        val sb = StringBuilder("%")
        for (c in term) { if (c == '%' || c == '_' || c == '\\') sb.append('\\'); sb.append(c) }
        return sb.append('%').toString()
    }

    /** 识别出的原文：去掉首尾空白，超过 [MAX_TEXT] 截断。 */
    fun clip(text: String): String = text.trim().let { if (it.length > MAX_TEXT) it.take(MAX_TEXT) else it }

    /**
     * 这张照片要不要识别。[state]/[size]/[modified]/[at] 是库里已有的记录（[state] = 0 表示没有记录）；
     * [curSize]/[curModified] 是照片现在的大小和修改时间（文件被替换就重新识别）；[hasThumb] = 网格缩略图已经缓存。
     */
    fun needsOcr(state: Int, size: Long, modified: Long, at: Long, curSize: Long, curModified: Long, hasThumb: Boolean, now: Long): Boolean {
        if (state == 0) return true
        if (size != curSize || (curModified > 0 && modified > 0 && modified != curModified)) return true
        return when (state) {
            STATE_DONE -> false
            STATE_NO_IMAGE -> hasThumb || now - at >= NO_IMAGE_RETRY_MS
            else -> true
        }
    }
}
