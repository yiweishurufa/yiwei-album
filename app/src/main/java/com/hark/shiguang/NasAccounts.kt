package com.hark.shiguang

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hark.shiguang.data.FnClient
import org.json.JSONArray
import org.json.JSONObject

/** One fnOS login. Each account keeps its own addresses, credentials and network mode. */
data class NasAccount(
    val id: String, val name: String, val url: String, val lanUrl: String, val wanUrl: String,
    val user: String, val pass: String, val token: String, val mode: String = "auto",
) {
    val title: String get() = name.ifEmpty { user }
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("lan", lanUrl).put("wan", wanUrl)
        .put("user", user).put("pass", pass).put("token", token).put("mode", mode)
    companion object {
        fun of(o: JSONObject) = NasAccount(o.optString("id"), o.optString("name"), o.optString("url"), o.optString("lan"), o.optString("wan"),
            o.optString("user"), o.optString("pass"), o.optString("token"), o.optString("mode", "auto"))
    }
}

object NasAccounts {
    var list by mutableStateOf<List<NasAccount>>(emptyList())
        private set
    var currentId by mutableStateOf("")
        private set
    val current: NasAccount? get() = list.firstOrNull { it.id == currentId }

    fun init() {
        list = runCatching { JSONArray(Store.nasAccounts).let { a -> (0 until a.length()).map { NasAccount.of(a.getJSONObject(it)) } } }.getOrDefault(emptyList())
        currentId = Store.nasCurrent
        // migrate the single 0.x login
        if (list.isEmpty() && Store.url.isNotEmpty() && Store.user.isNotEmpty()) {
            val a = NasAccount(newId(), "家里的飞牛", Store.url, Store.lanUrl, Store.wanUrl, Store.user, Store.pass, Store.token, if (Store.autoSwitch) "auto" else "wan")
            list = listOf(a); currentId = a.id; persist()
        }
        if (current == null) currentId = list.firstOrNull()?.id ?: ""
    }

    private fun newId() = java.util.UUID.randomUUID().toString().take(8)
    private fun persist() {
        Store.nasAccounts = JSONArray().apply { list.forEach { put(it.json()) } }.toString()
        Store.nasCurrent = currentId
    }

    /** Saves a successful login (new or existing by url+user) and makes it current. */
    fun saveLogin(url: String, user: String, pass: String, token: String, name: String = ""): NasAccount {
        val ex = list.firstOrNull { it.user == user && (it.url == url || it.lanUrl == url || it.wanUrl == url) }
        val priv = com.hark.shiguang.data.Endpoint.isPrivate(url)
        val a = (ex ?: NasAccount(newId(), name.ifEmpty { if (list.isEmpty()) "家里的飞牛" else "飞牛 ${list.size + 1}" }, url, "", "", user, pass, token)).let {
            it.copy(url = url, pass = pass, token = token, lanUrl = if (priv) url else it.lanUrl, wanUrl = if (!priv) url else it.wanUrl)
        }
        list = list.filter { it.id != a.id } + a
        currentId = a.id
        persist(); apply(a)
        return a
    }

    fun update(a: NasAccount) { list = list.map { if (it.id == a.id) a else it }; persist(); if (a.id == currentId) apply(a) }

    fun remove(id: String) {
        list = list.filter { it.id != id }
        if (currentId == id) currentId = list.firstOrNull()?.id ?: ""
        persist(); current?.let { apply(it) }
    }

    /** Switches the active account: credentials, addresses and per-account caches. */
    fun switchTo(id: String) {
        val a = list.firstOrNull { it.id == id } ?: return
        currentId = id; persist(); apply(a)
    }

    fun storeToken(token: String) { current?.let { update(it.copy(token = token)) } }

    private fun apply(a: NasAccount) {
        Store.url = a.url; Store.user = a.user; Store.pass = a.pass; Store.token = a.token
        Store.lanUrl = a.lanUrl; Store.wanUrl = a.wanUrl; Store.netMode = a.mode; Store.autoSwitch = a.mode == "auto"
        FnClient.setCredentials(a.url, a.user, a.pass); FnClient.token = a.token
        com.hark.shiguang.data.NasX.signSecretB64 = listOf(a.url, a.lanUrl, a.wanUrl).filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { Store.getStr("sign.${a.user}@$it").ifEmpty { null } } ?: ""
    }
}
