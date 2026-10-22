package com.hark.shiguang

import androidx.compose.runtime.mutableStateOf

/**
 * 1.1.0: one place for the switches of the features added in 1.1.0 (every new feature can be turned off).
 * Values live in [Store] under `feat.<name>`; an empty value means "use the default", so old installs get the
 * defaults below without any migration. Compose reads go through mutable state so settings toggles redraw at once.
 */
object Features {
    private class Flag(private val key: String, private val def: Boolean) {
        // lazy: first read happens after Store.init (App.onCreate); no state write during composition
        private val state by lazy { mutableStateOf(when (runCatching { Store.getStr(key) }.getOrDefault("")) { "1" -> true; "0" -> false; else -> def }) }
        fun get(): Boolean = state.value
        fun set(v: Boolean) { state.value = v; Store.putStr(key, if (v) "1" else "0") }
    }

    private val wm = Flag("feat.workmanager", true)

    /** 3c: background scan / faces / AI / backup are scheduled by WorkManager (off = 1.0.x behaviour, JobScheduler backup only). */
    var workManager: Boolean get() = wm.get(); set(v) = wm.set(v)
}
