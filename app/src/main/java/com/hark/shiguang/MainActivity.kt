package com.hark.shiguang

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.hark.shiguang.data.FnClient
import com.hark.shiguang.data.Photo
import com.hark.shiguang.ui.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

sealed class Route {
    data object Login : Route()
    data object Home : Route()
    data class Collection(val title: String, val subtitle: String, val source: PhotoSource, val album: AlbumRef? = null) : Route()
    data object Search : Route()
    data class Viewer(val photos: List<Photo>, val start: Int, val slideshow: Boolean = false) : Route()
    data object Settings : Route()
    data object ConnectionSettings : Route()
    data class CloudBrowse(val accountId: String, val path: String, val title: String) : Route()
    data class CloudTimeline(val accountId: String) : Route()
    data object Backup : Route()
    data object Transfers : Route()
    data object Smart : Route()
    data object Duplicates : Route()
    data class DavGroups(val title: String, val accountId: String, val kind: String) : Route()
    data class Folders(val path: String, val title: String) : Route()
    data object Recycle : Route()
    data object DavAccounts : Route()
    data object AiSettings : Route()
    data class Upload(val uris: List<android.net.Uri>) : Route()
    data class MovieDetail(val sourceKey: String, val itemId: String) : Route()
    data class MoviePlay(val sourceKey: String, val itemId: String, val path: String) : Route()
    data object MovieLibrarySettings : Route()
    // 1.0.3 #4 音乐
    data object MusicSettings : Route()
    data class MusicList(val kind: String, val key: String, val title: String) : Route()
    data object MusicNowPlaying : Route()
    /** 1.0.9 photo features (人物/隐藏相册/地图/编辑…): screens live in ui/Ext.kt. */
    data class X(val screen: ExtScreen) : Route()
}

object Nav {
    val stack = mutableStateListOf<Route>()
    /** Set when the last saved top route could not be restored (its args are gone): UI falls back to Home and says so. */
    var restoredFallback by mutableStateOf(false)
    fun push(r: Route) { stack.add(r); save() }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex); save() }
    fun reset(r: Route) { stack.clear(); stack.add(r); save() }

    /**
     * B24: only restorable top-level routes are persisted (Home/tab + a simple Route type). Anything with
     * args (Viewer / Collection / MoviePlay / …) is recorded as Home, so a restart never rebuilds a
     * half-dead page from stale arguments.
     */
    private fun save() {
        val top = stack.lastOrNull() ?: return
        val key = when (top) {
            is Route.Home -> "home"
            is Route.Settings -> "settings"
            is Route.Backup -> "backup"
            is Route.Transfers -> "transfers"
            is Route.Smart -> "smart"
            is Route.Duplicates -> "duplicates"
            is Route.Recycle -> "recycle"
            is Route.DavAccounts -> "davAccounts"
            is Route.AiSettings -> "aiSettings"
            is Route.Search -> "search"
            is Route.MusicSettings -> "musicSettings"
            is Route.MusicNowPlaying -> "musicNowPlaying"
            is Route.MovieLibrarySettings -> "movieLib"
            else -> "home"
        }
        Store.putStr("nav.top", key)
        Store.putStr("nav.tab", com.hark.shiguang.ui.HomeState.tab.toString())
    }

    /** Rebuilds the persisted top route (or Home). Returns true when the saved route itself was restored. */
    fun restore(savedTop: String): Boolean {
        val tab = Store.getStr("nav.tab").toIntOrNull() ?: 0
        com.hark.shiguang.ui.HomeState.openTab(tab)   // 1.1.0: old index 1 (视频) → 照片 + 视频筛选
        val r = when (savedTop) {
            "settings" -> Route.Settings
            "backup" -> Route.Backup
            "transfers" -> Route.Transfers
            "smart" -> Route.Smart
            "duplicates" -> Route.Duplicates
            "recycle" -> Route.Recycle
            "davAccounts" -> Route.DavAccounts
            "aiSettings" -> Route.AiSettings
            "search" -> Route.Search
            "musicSettings" -> Route.MusicSettings
            "musicNowPlaying" -> Route.MusicNowPlaying
            "movieLib" -> Route.MovieLibrarySettings
            else -> null
        }
        if (r == null) { reset(Route.Home); restoredFallback = savedTop != "home"; return savedTop == "home" }
        reset(Route.Home)
        if (r != Route.Home) push(r)
        return true
    }
}

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun handleShare(i: android.content.Intent?) {
        if (i == null) return
        val uris: List<android.net.Uri> = when (i.action) {
            android.content.Intent.ACTION_SEND -> listOfNotNull(
                if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(android.content.Intent.EXTRA_STREAM))
            android.content.Intent.ACTION_SEND_MULTIPLE -> (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") i.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) { i.action = null; Nav.push(Route.Upload(uris)) }
        // 1.0.3 #4: tap on the music notification → the full screen player
        // 1.1.0 deep link: intent extra "tab" = photos / videos / albums / discover / settings / media
        i.getStringExtra("tab")?.let { t -> i.removeExtra("tab"); com.hark.shiguang.ui.HomeState.openTab(t); Nav.reset(Route.Home) }
        if (i.getStringExtra("open") == "music") {
            i.removeExtra("open")
            if (Nav.stack.firstOrNull() == Route.Home && Nav.stack.lastOrNull() != Route.MusicNowPlaying) Nav.push(Route.MusicNowPlaying)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Nav.stack.isEmpty()) {
            val savedTop = Store.getStr("nav.top")
            if (Store.token.isNotEmpty() && Store.url.isNotEmpty()) {
                FnClient.setCredentials(Store.url, Store.user, Store.pass)
                FnClient.token = Store.token
                Nav.reset(Route.Home)
            } else if (Dav.accounts.isNotEmpty()) { Store.source = "dav"; HomeState.source = "dav"; Nav.reset(Route.Home) }
            else Nav.reset(Route.Login)
            // B24: rebuild the persisted top-level route when the process was killed; deep pages fall back to Home.
            if (Nav.stack.lastOrNull() == Route.Home && savedTop.isNotEmpty() && savedTop != "home") Nav.restore(savedTop)
        }
        if (Store.lockOn && android.os.Build.VERSION.SDK_INT >= 28 && savedInstanceState == null) AppLock.locked = true
        handleShare(intent)
        lifecycleScope.launch { runCatching { com.hark.shiguang.data.Endpoint.autoSelect() } }
        BackupScheduler.schedule(this)
        BgRun.maybeAskNotifications(this) // 1.0.1: Android 13+ notification permission for the scan / AI progress notification
        setContent {
            ShiGuangTheme {
                val dark = C.P.dark
                LaunchedEffect(dark) {
                    val st = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = st, navigationBarStyle = st)
                }
                com.hark.shiguang.ui.Onboarding.Hook()   // 1.0.1 #9 first-run wizard
                // B24: the persisted page could not be rebuilt — say so instead of silently landing on Home.
                LaunchedEffect(Nav.restoredFallback) {
                    if (Nav.restoredFallback) {
                        Nav.restoredFallback = false
                        android.widget.Toast.makeText(this@MainActivity, "上次页面无法恢复，已回到首页", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                val top = Nav.stack.lastOrNull() ?: Route.Login
                BackHandler(enabled = Nav.stack.size > 1) { Nav.pop() }
                // Home always stays composed underneath so scroll position survives.
                val home = Nav.stack.firstOrNull() == Route.Home
                if (home) HomeScreen()
                AnimatedContent(
                    targetState = top,
                    transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f)) togetherWith fadeOut(tween(160)) },
                    label = "nav",
                ) { r ->
                    if (r == Route.Home) return@AnimatedContent
                    Surface(color = if (r is Route.Viewer || r is Route.MoviePlay || (r is Route.X && r.screen.dark)) Color.Black else C.Bg, modifier = Modifier.fillMaxSize()) {
                        when (r) {
                            Route.Login -> LoginScreen()
                            is Route.Collection -> CollectionScreen(r)
                            Route.Search -> SearchScreen()
                            is Route.Viewer -> ViewerScreen(r.photos, r.start, r.slideshow)
                            Route.Settings -> SettingsScreen()
                            Route.ConnectionSettings -> ConnectionSettingsScreen()
                            is Route.CloudBrowse -> CloudBrowseScreen(r)
                            is Route.CloudTimeline -> CloudTimelineScreen(r)
                            Route.Backup -> BackupScreen()
                            Route.Transfers -> TransfersScreen()
                            Route.Smart -> SmartScreen()
                            Route.Duplicates -> DuplicatesScreen()
                            is Route.DavGroups -> DavGroupsScreen(r)
                            is Route.Folders -> FoldersScreen(r)
                            Route.Recycle -> RecycleScreen()
                            Route.DavAccounts -> DavAccountsScreen()
                            Route.AiSettings -> AiSettingsScreen()
                            is Route.Upload -> UploadScreen(r)
                            is Route.MovieDetail -> MovieDetailScreen(r)
                            is Route.MoviePlay -> MoviePlayerScreen(r)
                            Route.MovieLibrarySettings -> MovieLibrarySettingsScreen()
                            Route.MusicSettings -> MusicSettingsScreen()
                            is Route.MusicList -> MusicListScreen(r)
                            Route.MusicNowPlaying -> MusicNowPlayingScreen()
                            is Route.X -> ExtHost(r.screen)
                            Route.Home -> {}
                        }
                    }
                }
                AppOverlays() // 1.0.9: crash report + update dialogs
                if (AppLock.locked) LockOverlay { unlock() }
            }
        }
    }

    private var stoppedAt = 0L
    override fun onStop() { super.onStop(); stoppedAt = System.currentTimeMillis() }
    override fun onStart() {
        super.onStart()
        if (Store.lockOn && android.os.Build.VERSION.SDK_INT >= 28 && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 30_000) AppLock.locked = true
    }
    override fun onResume() { super.onResume(); if (AppLock.locked) unlock() }

    private fun unlock() {
        if (android.os.Build.VERSION.SDK_INT < 28) { AppLock.locked = false; return }
        if (AppLock.prompting) return
        AppLock.prompting = true
        runCatching {
            val b = android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle("解锁一维相册").setSubtitle("验证指纹或锁屏密码")
            when {
                android.os.Build.VERSION.SDK_INT >= 30 -> b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                android.os.Build.VERSION.SDK_INT == 29 -> @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
                else -> b.setNegativeButton("取消", mainExecutor) { _, _ -> AppLock.prompting = false }
            }
            b.build().authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) { AppLock.locked = false; AppLock.prompting = false }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { AppLock.prompting = false }
            })
        }.onFailure { AppLock.prompting = false; AppLock.locked = false } // no biometrics enrolled: don't lock the user out
    }
}

object AppLock {
    var locked by mutableStateOf(false)
    var prompting = false
}
