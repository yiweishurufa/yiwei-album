package com.hark.shiguang

/**
 * 1.7.23：EXIF 的 Make + Model 拼成一个设备名，去掉重复的厂商名。
 * 很多手机 / 相机的 Model 里已经带厂商（Make「Xiaomi」+ Model「Xiaomi 13」、Make「NIKON CORPORATION」+ Model「NIKON Z 6」），
 * 以前直接拼成「Xiaomi Xiaomi 13」。厂商名末尾的公司后缀（CORPORATION / CO., LTD. / IMAGING CORP. …）也去掉。
 * 只影响显示和新写入的「相机」字段；已存的值不改（存储格式不变）。
 */
object CameraName {
    private val suffix = setOf("CORPORATION", "CORP", "CO", "LTD", "INC", "COMPANY", "IMAGING", "COLTD", "LIMITED", "GMBH", "AG")

    /** 厂商名去掉公司后缀：「OLYMPUS IMAGING CORP.」→「OLYMPUS」；全是后缀时原样返回。 */
    fun brand(make: String): String {
        val words = make.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.last().uppercase().replace(".", "") in suffix) words.removeAt(words.size - 1)
        return words.joinToString(" ")
    }

    fun of(make: String?, model: String?): String {
        val mk = make?.trim().orEmpty(); val md = model?.trim().orEmpty()
        if (md.isEmpty()) return brand(mk)
        if (mk.isEmpty()) return md
        val b = brand(mk)
        val first = b.substringBefore(' ')
        return when {
            startsWord(md, mk) || startsWord(md, b) || (first.length >= 2 && startsWord(md, first)) -> md
            else -> "$b $md"
        }
    }

    /** md 以 w 开头且 w 后面是词边界（「LGE-X」不算以「LG」开头）。 */
    private fun startsWord(md: String, w: String): Boolean =
        w.isNotEmpty() && md.startsWith(w, true) && (md.length == w.length || !md[w.length].isLetterOrDigit())
}
