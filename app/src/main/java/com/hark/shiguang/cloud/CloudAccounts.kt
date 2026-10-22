package com.hark.shiguang.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistence for cloud accounts (credentials + tokens). Stored as one JSON array in
 * EncryptedSharedPreferences; falls back to plain private prefs if the keystore is unusable.
 * Call [init] once from Application.onCreate.
 */
object CloudAccounts {
    private const val FILE_ENC = "cloud_accounts_enc"
    private const val FILE_PLAIN = "cloud_accounts"
    private const val KEY = "accounts"

    private var prefs: SharedPreferences? = null
    /** H18: true when [prefs] is the plain file and the JSON is stored sealed by [com.hark.shiguang.SecureBox]. */
    private var sealed = false
    /** H18: true when the encrypted store failed and accounts are kept in plain prefs. */
    @Volatile var insecure: Boolean = false
        private set
    private val lock = Any()
    @Volatile private var cache: List<CloudAccount> = emptyList()

    fun init(c: Context) {
        synchronized(lock) {
            if (prefs != null) return
            val ctx = c.applicationContext
            val plain = ctx.getSharedPreferences(FILE_PLAIN, Context.MODE_PRIVATE)
            val enc = try {
                val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    ctx, FILE_ENC, key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                com.hark.shiguang.Diag.log("SEC", "encrypted cloud accounts unavailable, using Keystore-sealed plain prefs (${e.javaClass.simpleName})")
                null
            }
            if (enc != null) {
                // H18: an earlier fallback left accounts in the plain file → move them into the encrypted store and wipe the plain copy
                plain.getString(KEY, null)?.let { old ->
                    val json = com.hark.shiguang.SecureBox.open(old)
                    if (json != null && enc.getString(KEY, null) == null) enc.edit().putString(KEY, json).commit()
                    plain.edit().remove(KEY).commit()
                }
                prefs = enc
            } else {
                // H18: plain prefs, but the accounts JSON (passwords, tokens) is sealed with an Android Keystore key; old plain JSON is rewritten
                prefs = plain; sealed = true
                plain.getString(KEY, null)?.takeIf { !com.hark.shiguang.SecureBox.isSealed(it) }?.let { old ->
                    com.hark.shiguang.SecureBox.seal(old)?.let { plain.edit().putString(KEY, it).commit() }
                }
                insecure = !com.hark.shiguang.SecureBox.available || plain.getString(KEY, null)?.let { !com.hark.shiguang.SecureBox.isSealed(it) } == true
            }
            cache = load()
        }
    }

    /** Snapshot of all saved accounts, in insertion order. */
    val all: List<CloudAccount> get() = cache

    fun get(id: String): CloudAccount? = cache.firstOrNull { it.id == id }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

    /** Insert or replace (by id). */
    fun save(a: CloudAccount) {
        synchronized(lock) {
            val list = cache.toMutableList()
            val i = list.indexOfFirst { it.id == a.id }
            if (i >= 0) list[i] = a else list.add(a)
            cache = list
            persist(list)
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val list = cache.filter { it.id != id }
            cache = list
            persist(list)
        }
    }

    /** Used by sources after a token refresh: only updates an account that is already saved. */
    internal fun updateIfSaved(a: CloudAccount) {
        synchronized(lock) {
            if (prefs == null || cache.none { it.id == a.id }) return
        }
        save(a)
    }

    private fun load(): List<CloudAccount> {
        val stored = prefs?.getString(KEY, null) ?: return emptyList()
        val raw = if (sealed) com.hark.shiguang.SecureBox.open(stored) ?: return emptyList() else stored
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> fromJson(arr.optJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun persist(list: List<CloudAccount>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val json = arr.toString()
        prefs?.edit()?.putString(KEY, if (sealed) com.hark.shiguang.SecureBox.seal(json) ?: json else json)?.apply()
    }

    fun toJson(a: CloudAccount): JSONObject {
        val ex = JSONObject()
        a.extra.forEach { (k, v) -> ex.put(k, v) }
        return JSONObject()
            .put("id", a.id).put("kind", a.kind.name).put("title", a.title)
            .put("url", a.url).put("user", a.user).put("pass", a.pass)
            .put("extra", ex)
    }

    fun fromJson(o: JSONObject?): CloudAccount? {
        if (o == null) return null
        val kind = try {
            CloudKind.valueOf(o.optString("kind"))
        } catch (e: Exception) {
            return null
        }
        val ex = HashMap<String, String>()
        o.optJSONObject("extra")?.let { e ->
            val it = e.keys()
            while (it.hasNext()) {
                val k = it.next()
                ex[k] = e.optString(k)
            }
        }
        return CloudAccount(
            id = o.optString("id").ifEmpty { newId() },
            kind = kind,
            title = o.optString("title"),
            url = o.optString("url"),
            user = o.optString("user"),
            pass = o.optString("pass"),
            extra = ex
        )
    }
}
