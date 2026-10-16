package com.hark.shiguang

import java.security.MessageDigest

/**
 * The one MD5 helper. Callers keep their own truncation — it is part of the key, not the hash:
 * music track ids take(16), cover-art files take(20), thumbnail / signing keys use the full hex.
 */
object HashUtil {
    fun md5(s: String): String = md5(s.toByteArray())
    fun md5(b: ByteArray): String = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }
}
