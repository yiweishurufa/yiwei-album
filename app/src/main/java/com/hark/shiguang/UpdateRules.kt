package com.hark.shiguang

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * 在线更新里不依赖 Android 的判断逻辑（单元测试覆盖：app/src/test/.../UpdateRulesTest.kt）。
 * AppUpdate 负责网络、通知和安装；这里只回答「地址可不可信」「是不是更新的版本」「文件对不对」。
 */
object UpdateRules {
    const val TRUSTED_PREFIX = "https://github.com/yiweishurufa/"

    /** 只信任 github.com/yiweishurufa/ 下的地址，拒绝 ../ 和带用户名的地址。 */
    fun trusted(u: String): Boolean = u.startsWith(TRUSTED_PREFIX) && !u.contains("..") && !u.contains('@')

    /** "1.0.10" > "1.0.9"；非数字字符忽略。 */
    fun cmp(a: String, b: String): Int {
        val x = a.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.', '-', '_').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** 更新 = versionCode 更高，或 versionCode 相同但 CI 运行号更大（测试版共用 versionCode）。 */
    fun isNewer(code: Int, build: Int, curCode: Int, curBuild: Int): Boolean = code > curCode || (code == curCode && build > curBuild)

    fun isSha256Hex(s: String): Boolean = s.matches(Regex("[0-9a-f]{64}"))

    fun sha256Hex(ins: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 安装包文件本身的检查（ZIP 头、大小、SHA-256）。包名和 versionCode 需要 PackageManager，留在 AppUpdate。
     * 返回 null 表示通过，否则是给用户看的原因。
     */
    fun checkFile(f: File, wantSize: Long, wantSha256: String): String? {
        if (!f.exists() || f.length() == 0L) return "安装包不存在"
        val head = f.inputStream().use { val b = ByteArray(4); val n = it.read(b); if (n < 2) null else b } ?: return "下载的不是安装包"
        if (!(head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte())) return "下载的不是安装包"
        if (wantSize > 0 && f.length() != wantSize) return "文件不完整（${f.length()} / $wantSize）"
        val sha = runCatching { f.inputStream().use { sha256Hex(it) } }.getOrNull()
        if (sha == null || sha != wantSha256.lowercase().trim()) return "SHA-256 不一致（文件可能被代理改动或下载损坏）"
        return null
    }

    /** 签名判断结果。 */
    enum class Signer {
        /** 与已安装版本同一签名，可以直接覆盖安装。 */
        SAME,
        /** 是一维相册的另一把可信签名（例如测试签名 → 正式签名），系统不允许直接覆盖，需先卸载旧版。 */
        TRUSTED_OTHER,
        /** 读不出或不认识的签名：拒绝，不打开安装器。 */
        UNKNOWN,
    }

    /**
     * [installed]、[apk] 是签名证书的 SHA-256（小写十六进制），读不出时为 null（一律按 UNKNOWN 处理，失败即拒绝）。
     * [trustedSigners] 是一维相册发布用过的签名证书指纹。
     */
    fun classifySigner(installed: String?, apk: String?, trustedSigners: Set<String>): Signer {
        if (installed == null || apk == null) return Signer.UNKNOWN
        val a = apk.lowercase(); val i = installed.lowercase()
        if (a == i) return Signer.SAME
        return if (a in trustedSigners.map { it.lowercase() }) Signer.TRUSTED_OTHER else Signer.UNKNOWN
    }
}
