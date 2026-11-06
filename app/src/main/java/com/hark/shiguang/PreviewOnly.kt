package com.hark.shiguang

/**
 * 原图手机解不出来的格式：大图只显示 NAS 生成的预览图，不再去下载原图。
 *
 * - 相机 RAW（dng / arw / cr2 / nef …）：原来就只看预览，这里把列表补全。
 * - 飞牛 fnOS 1.2.1000（2026-11-04）缩略图服务新增 DPX、EXR、J2C、J2K 等序列帧格式：NAS 能出缩略图了，
 *   这些文件就会出现在时间线里，但 Android 的 BitmapFactory 解不了，原来点开会白白下载几十 MB 原图再解码失败。
 * - TIFF / PSD 同理（BitmapFactory 不支持）。
 * 纯逻辑（单元测试：PreviewOnlyTest）。
 */
object PreviewOnly {
    private val EXT = setOf(
        // camera RAW
        "dng", "arw", "cr2", "cr3", "nef", "nrw", "raf", "orf", "rw2", "pef", "srw", "x3f", "3fr", "iiq", "erf", "mrw", "kdc",
        // fnOS 1.2.1000 sequence-frame / cinema formats
        "dpx", "exr", "j2c", "j2k", "jp2", "jpf", "jpx",
        // other formats Android cannot decode
        "tif", "tiff", "psd", "psb",
    )

    fun ext(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    /** True when the viewer / editor must not fetch the original and should stay on the NAS preview. */
    fun of(fileName: String): Boolean = ext(fileName) in EXT
}
