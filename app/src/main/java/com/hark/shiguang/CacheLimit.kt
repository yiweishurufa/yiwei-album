package com.hark.shiguang

/** #94 缩略图缓存上限：可选档位与换算（纯函数，便于测试）。默认 300 MB，与 1.0.8 起的固定值一致。 */
object CacheLimit {
    const val DEFAULT_MB = 300
    val OPTIONS_MB = listOf(100, 300, 500, 1000, 2000)

    /** 非法 / 过期的存储值回落到默认档。 */
    fun normalize(mb: Int): Int = if (mb in OPTIONS_MB) mb else DEFAULT_MB

    fun bytes(mb: Int): Long = normalize(mb).toLong() * 1024 * 1024

    fun label(mb: Int): String = normalize(mb).let { if (it >= 1000) "${it / 1000.0} GB".replace(".0 GB", " GB") else "$it MB" }
}
