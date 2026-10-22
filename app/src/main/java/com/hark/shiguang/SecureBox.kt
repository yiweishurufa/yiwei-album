package com.hark.shiguang

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * H18：EncryptedSharedPreferences 打不开时（部分国产 ROM 的 Tink 密钥集损坏）的后备加密。
 * 密钥是 Android Keystore 里的 AES-256-GCM（不可导出），值存成 "ks1:" + Base64(IV ‖ 密文)。
 * 读到不带 "ks1:" 前缀的旧明文时照常返回，并由 [SecretPrefs.migratePlain] 改写成密文。
 */
object SecureBox {
    private const val ALIAS = "yiwei.box.v1"
    const val PREFIX = "ks1:"
    @Volatile private var key: SecretKey? = null
    @Volatile var available: Boolean = true
        private set
    @Volatile var error: String = ""
        private set

    @Synchronized private fun key(): SecretKey? {
        key?.let { return it }
        return runCatching {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
                generateKey()
            }
        }.onFailure { available = false; error = it.javaClass.simpleName }.getOrNull()?.also { key = it }
    }

    fun isSealed(v: String?): Boolean = v != null && v.startsWith(PREFIX)

    /** Encrypted form, or null when the Keystore is unusable (the caller then keeps the plain value and stays "insecure"). */
    fun seal(plain: String): String? {
        val k = key() ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k)
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
        }.onFailure { available = false; error = it.javaClass.simpleName }.getOrNull()
    }

    /** Plain value of a sealed string; a non-sealed (legacy plain) string is returned as is; null when decryption fails. */
    fun open(v: String): String? {
        if (!isSealed(v)) return v
        val k = key() ?: return null
        return runCatching {
            val raw = Base64.decode(v.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }.onFailure { Diag.log("SEC", "SecureBox open failed: ${it.javaClass.simpleName}") }.getOrNull()
    }
}

/**
 * 普通 SharedPreferences 上的「敏感键自动加解密」。只在 EncryptedSharedPreferences 不可用时使用；
 * [isSecret] 决定哪些键（密码、token、API key…）要加密。
 */
class SecretPrefs(private val sp: SharedPreferences, private val isSecret: (String) -> Boolean) {
    fun get(k: String, d: String): String {
        val v = sp.getString(k, null) ?: return d
        return if (isSecret(k)) SecureBox.open(v) ?: d else v
    }
    fun put(e: SharedPreferences.Editor, k: String, v: String): SharedPreferences.Editor =
        e.putString(k, if (isSecret(k) && v.isNotEmpty()) SecureBox.seal(v) ?: v else v)

    /** Rewrites legacy plain secrets as sealed values. Returns how many keys are still plain (0 = all protected). */
    fun migratePlain(): Int {
        var left = 0
        val e = sp.edit()
        for ((k, v) in sp.all) {
            if (v !is String || v.isEmpty() || !isSecret(k) || SecureBox.isSealed(v)) continue
            val s = SecureBox.seal(v)
            if (s != null) e.putString(k, s) else left++
        }
        e.apply()
        return left
    }
}
