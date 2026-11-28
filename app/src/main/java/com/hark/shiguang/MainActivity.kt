package com.hark.shiguang

import android.os.Bundle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
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
    /** 优化 18：发现页的全局搜索（照片 + 影视 + 歌曲）。 */
    data object GlobalSearch : Route()
    /** 审查 1 / 用户口述 1：影音页的搜索（搜影片，不是照片）。 */
    data object MovieSearch : Route()
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
    /** 1.1.0: WebDAV 大文件（按大小，Paging 3 从 Room 读）。 */
    data class DavBigFiles(val accountId: String) : Route()
    data class Folders(val path: String, val title: String) : Route()
    data object Recycle : Route()
    /** 优化 5：WebDAV 账号的「最近删除」。 */
    data class DavRecycle(val accountId: String) : Route()
    data object DavAccounts : Route()
    data object AiSettings : Route()
    /** 用户 2026-10-23：设置 → 智能整理（WebDAV：AI / 人脸 / 图中文字 / 旅行相册）。 */
    data object SmartSettings : Route()
    /** 用户 2026-10-23：设置 → 隐私与存储 → 高级（证书、彻底删除）。 */
    data object AdvancedSettings : Route()
    data class Upload(val uris: List<android.net.Uri>) : Route()
    data class MovieDetail(val sourceKey: String, val itemId: String) : Route()
    /** 全面审查 A9：库里某位演员的作品。 */
    data class MovieActor(val sourceKey: String, val name: String) : Route()
    /** [fromStart] 全面审查 A1：详情页「从头播放」，不接着上次的进度。 */
    data class MoviePlay(val sourceKey: String, val itemId: String, val path: String, val fromStart: Boolean = false) : Route()
    data object MovieLibrarySettings : Route()
    /** 1.1.0: 影音设置 — one entry for TMDB / 音乐刮削 / 影视文件夹 / 音乐文件夹. */
    data object MediaSettings : Route()
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
    /**
     * 用户 2026-10-24 要求继续优化：每个栈条目一个稳定的 key（与 [stack] 一一对应），页面切走（打开大图 / 播放器）时
     * 用它把页面的 rememberSaveable 状态（分段、勾选、滚动位置）存进 SaveableStateHolder，返回时原样恢复。
     * key 带页面类型名：进程被杀重建后计数从头开始，同位置同类型的页面恢复旧状态是期望行为，不同类型绝不会串。
     */
    private val keys = ArrayList<String>()
    private var nextKey = 0L
    /** 条目出栈时通知 UI 丢掉它保存的状态（MainActivity 里接到 SaveableStateHolder.removeState）。 */
    var onRemoved: ((String) -> Unit)? = null
    /** 栈顶条目的 key；栈空时为空串。 */
    fun topKey(): String = keys.lastOrNull() ?: ""
    private fun newKey(r: Route) = "${nextKey++}:${r::class.java.simpleName}"
    fun push(r: Route) { keys.add(newKey(r)); stack.add(r); save() }
    fun pop() {
        // 审查 5：离开页面时清掉多选（系统返回和左上角返回结果一致）
        com.hark.shiguang.ui.Selection.clear()
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); keys.removeLastOrNull()?.let { onRemoved?.invoke(it) } }
        save()
    }
    fun reset(r: Route) {
        com.hark.shiguang.ui.Selection.clear()
        val old = keys.toList(); keys.clear(); keys.add(newKey(r))
        stack.clear(); stack.add(r); save()
        old.forEach { onRemoved?.invoke(it) }
    }

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
            is Route.SmartSettings -> "smartSettings"
            is Route.AdvancedSettings -> "advSettings"
            is Route.Search -> "search"
            is Route.MovieSearch -> "movieSearch"
            is Route.MusicSettings -> "musicSettings"
            is Route.MusicNowPlaying -> "musicNowPlaying"
            is Route.MovieLibrarySettings -> "movieLib"
            is Route.MediaSettings -> "mediaSettings"
            else -> "home"
        }
        Store.putStr("nav.top", key)
        Store.putStr("nav.tab", com.hark.shiguang.ui.HomeState.tab.toString())
    }

    /** Rebuilds the persisted top route (or Home). Returns true when the saved route itself was restored. */
    fun restore(savedTop: String): Boolean {
        val tab = Store.getStr("nav.tab").toIntOrNull() ?: 0
        // 1.4.0：「影音」拆成「影视」「音乐」；上次停在影音 → 音乐的，回到新的「音乐」tab（只迁移一次）
        val migrated = if (tab == 5 && com.hark.shiguang.ui.MoviesState.legacyMusicArea) 6 else tab
        if (com.hark.shiguang.ui.MoviesState.legacyMusicArea) Store.putStr("movies.area", "0")
        com.hark.shiguang.ui.HomeState.openTab(migrated)   // 1.1.0: old index 1 (视频) → 照片 + 视频筛选
        val r = when (savedTop) {
            "settings" -> Route.Settings
            "backup" -> Route.Backup
            "transfers" -> Route.Transfers
            "smart" -> Route.Smart
            "duplicates" -> Route.Duplicates
            "recycle" -> Route.Recycle
            "davAccounts" -> Route.DavAccounts
            "aiSettings" -> Route.AiSettings
            "smartSettings" -> Route.SmartSettings
            "advSettings" -> Route.AdvancedSettings
            "search" -> Route.Search
            "movieSearch" -> Route.MovieSearch
            "musicSettings" -> Route.MusicSettings
            "musicNowPlaying" -> Route.MusicNowPlaying
            "movieLib" -> Route.MovieLibrarySettings
            "mediaSettings" -> Route.MediaSettings
            else -> null
        }
        if (r == null) { reset(Route.Home); restoredFallback = savedTop != "home"; return savedTop == "home" }
        reset(Route.Home)
        if (r != Route.Home) push(r)
        return true
    }
}

/**
 * AnimatedContent 的目标：按栈条目 key 判等（不比较 Route 本身，Viewer 带整份照片列表，比较很贵；
 * 同一条目重入时 AnimatedContent 复用正在退出的那份内容，不会出现同一 key 被组合两次）。
 */
class NavEntry(val route: Route, val key: String) {
    override fun equals(other: Any?) = other is NavEntry && other.key == key
    override fun hashCode() = key.hashCode()
}

/** 系统重建或从最近任务重开时带来的启动 intent 是旧的，不应再执行其中的分享 / 深链动作。 */
internal fun isStaleLaunchIntent(restored: Boolean, flags: Int): Boolean =
    restored || (flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

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
        // 1.1.0 deep link: intent extra "tab" = photos / videos / albums / discover / settings / media(movies) / music
        i.getStringExtra("tab")?.let { t -> i.removeExtra("tab"); com.hark.shiguang.ui.HomeState.openTab(t); Nav.reset(Route.Home) }
        if (i.getStringExtra("open") == "music") {
            i.removeExtra("open")
            if (Nav.stack.firstOrNull() == Route.Home && Nav.stack.lastOrNull() != Route.MusicNowPlaying) Nav.push(Route.MusicNowPlaying)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 1.7.11：清单里是启动窗口主题（玻璃图标），窗口建出来之前换回正常主题
        setTheme(R.style.Theme_ShiGuang)
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
        // 用户 2026-10-24 要求排查：进程被杀后系统重建（savedInstanceState != null），或从最近任务重新打开分享进来的任务时，
        // 系统会把当初那条「分享 / 深链」intent 原样再发一次，以前会再弹一次上传页（可能重复上传、URI 授权已失效），
        // 或者把恢复好的页面重置回首页。这两种情况都不再处理旧 intent。
        if (!isStaleLaunchIntent(savedInstanceState != null, intent?.flags ?: 0)) handleShare(intent)
        lifecycleScope.launch { runCatching { com.hark.shiguang.data.Endpoint.autoSelect() } }
        BackupScheduler.schedule(this)
        BgRun.maybeAskNotifications(this) // 1.0.1: Android 13+ notification permission for the scan / AI progress notification
        // 1.7.18：冷启动首帧时间进诊断日志（StartTrace）。onDraw 里不能移除自己，post 出去再移除
        if (savedInstanceState == null) {
            val dv = window.decorView
            dv.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    StartTrace.firstFrame(android.os.SystemClock.elapsedRealtime())
                    val self = this
                    dv.post { runCatching { dv.viewTreeObserver.removeOnDrawListener(self) } }
                }
            })
        }
        setContent {
            ShiGuangTheme {
                val dark = C.P.dark
                LaunchedEffect(dark) {
                    val st = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = st, navigationBarStyle = st)
                }
                // B24: the persisted page could not be rebuilt — say so instead of silently landing on Home.
                LaunchedEffect(Nav.restoredFallback) {
                    if (Nav.restoredFallback) {
                        Nav.restoredFallback = false
                        com.hark.shiguang.ui.toast(this@MainActivity, "上次页面无法恢复，已回到首页")
                    }
                }
                val top = Nav.stack.lastOrNull() ?: Route.Login
                // 用户 2026-10-24 要求继续优化：页面被大图 / 播放器盖住时会离开组合，以前 rememberSaveable 状态随之丢失
                // （「重复与相似」切到「相似」看大图回来跳回「重复」、勾选没了；设置子页面滚动回顶）。
                // 每个栈条目包一层 SaveableStateProvider，离开时保存、返回时恢复；出栈时丢掉。只存小状态（id、下标），不存照片列表。
                val pageStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                DisposableEffect(pageStates) {
                    Nav.onRemoved = { k -> pageStates.removeState(k) }
                    onDispose { Nav.onRemoved = null }
                }
                val entry = NavEntry(top, Nav.topKey())
                BackHandler(enabled = Nav.stack.size > 1) { Nav.pop() }
                // Home always stays composed underneath so scroll position survives.
                val home = Nav.stack.firstOrNull() == Route.Home
                // 1.7.12：普通页面压上来时首页跟着放大到 1.04（「往里走」），返回时缩回；大图 / 播放器 / 正在播放不动首页
                // （大图的共享元素转场要按网格原位置算落点）。移除动画时不缩放。
                val depthOn = !Motion.reduced && Nav.stack.size > 1 && top !is Route.Viewer && top !is Route.MoviePlay && top != Route.MusicNowPlaying
                val homeScale by androidx.compose.animation.core.animateFloatAsState(if (depthOn) Motion.PAGE_SCALE_OUT else 1f, Motion.page(), label = "homeDepth")
                if (home) androidx.compose.foundation.layout.Box(Modifier.graphicsLayer { scaleX = homeScale; scaleY = homeScale }) { HomeScreen() }
                // 转场规格集中在 Motion（docs/DESIGN.md §7）；系统「移除动画」时只做 90ms 淡入淡出。
                val depth = Nav.stack.size
                val prevDepth = remember { mutableIntStateOf(depth) }
                val forward = depth >= prevDepth.intValue
                SideEffect { prevDepth.intValue = depth }
                AnimatedContent(
                    targetState = entry,
                    transitionSpec = {
                        val media = { r: Route -> r is Route.Viewer || r is Route.MoviePlay }
                        when {
                            // 1.6.2：从缩略图打开的大图自己做共享元素放大（ViewerScreen），页面层不再叠一层缩放；
                            // 大图已缩回缩略图位置时（Hero.closed）直接移除，网格就在原处
                            !Motion.reduced && targetState.route is Route.Viewer && Hero.from != null -> EnterTransition.None togetherWith fadeOut(Motion.exit())
                            !Motion.reduced && initialState.route is Route.Viewer && Hero.closed -> EnterTransition.None togetherWith ExitTransition.None
                            // 1.7.36：从迷你播放条打开 / 缩回迷你条时，正在播放页自己做容器展开 / 收起（MiniHero），页面层不再叠转场
                            !Motion.reduced && targetState.route == Route.MusicNowPlaying && com.hark.shiguang.ui.MiniHero.pending != null -> EnterTransition.None togetherWith ExitTransition.None
                            !Motion.reduced && initialState.route == Route.MusicNowPlaying && com.hark.shiguang.ui.MiniHero.closed -> EnterTransition.None togetherWith ExitTransition.None
                            // 1.6.6：正在播放页像从迷你播放条展开的面板——从下往上滑入（面板弹簧），收起时向下滑出并压在上层
                            !Motion.reduced && targetState.route == Route.MusicNowPlaying -> (slideInVertically(Motion.sheet()) { it / 2 } + fadeIn(Motion.enter())) togetherWith fadeOut(Motion.exit())
                            !Motion.reduced && initialState.route == Route.MusicNowPlaying -> (fadeIn(Motion.enter()) togetherWith (slideOutVertically(Motion.page()) { it / 2 } + fadeOut(Motion.exit()))).apply { targetContentZIndex = -1f }
                            // 1.7.16 安全回退：播放器（SurfaceView 视频层）进出回到 1.7.5 的转场——进入放大淡入、下层只淡出，
                            // 退出时播放器略缩淡出、下层只淡入（不再让下层同时做 1.04 缩放、也不调 zIndex）。大图仍用 1.7.12 的。
                            targetState.route is Route.MoviePlay -> (fadeIn(Motion.enter()) + scaleIn(Motion.zoom(), initialScale = if (Motion.reduced) 1f else 0.94f)) togetherWith fadeOut(Motion.exit())
                            initialState.route is Route.MoviePlay -> fadeIn(Motion.enter()) togetherWith (fadeOut(Motion.exit()) + scaleOut(Motion.exit(), targetScale = if (Motion.reduced) 1f else 0.96f))
                            media(targetState.route) -> Motion.mediaEnter() togetherWith Motion.pageExit(true)
                            media(initialState.route) -> (Motion.pageEnter(false) togetherWith Motion.mediaExit()).apply { targetContentZIndex = -1f }
                            // 1.7.12 统一转场（Motion.pageEnter / pageExit）：进栈时新页面从 0.92 放大淡入、下层放大到 1.04 淡出（往里走）；
                            // 出栈反过来，被弹出的页面压在上层缩小淡出。移除动画时只做淡入淡出。
                            else -> (Motion.pageEnter(forward) togetherWith Motion.pageExit(forward)).apply { if (!forward) targetContentZIndex = -1f }
                        }
                    },
                    label = "nav",
                ) { e ->
                    val r = e.route
                    if (r == Route.Home) return@AnimatedContent
                    pageStates.SaveableStateProvider(e.key) {
                    androidx.compose.runtime.CompositionLocalProvider(com.hark.shiguang.ui.LocalPageKey provides e.key) {
                    // 1.6.2：直接压在首页上的大图用透明底（ViewerScreen 自己画随下拉变淡的黑底），下拉时能看见下面的网格
                    // 1.7.34：直接压在首页上的正在播放页也用透明底（页面自己画底色），往下拉收起时能看见下面的首页
                    val overHome = remember(e.key) { (r is Route.Viewer || r == Route.MusicNowPlaying) && Nav.stack.size == 2 && Nav.stack.firstOrNull() == Route.Home }
                    Surface(color = if (overHome) Color.Transparent else if (r is Route.Viewer || r is Route.MoviePlay || (r is Route.X && r.screen.dark)) Color.Black else C.Bg, modifier = Modifier.fillMaxSize()) {
                        when (r) {
                            Route.Login -> LoginScreen()
                            is Route.Collection -> CollectionScreen(r)
                            Route.Search -> SearchScreen()
                            Route.MovieSearch -> MovieSearchScreen()
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
                            is Route.DavBigFiles -> DavBigFilesScreen(r)
                            is Route.Folders -> FoldersScreen(r)
                            Route.Recycle -> RecycleScreen()
                            Route.GlobalSearch -> com.hark.shiguang.ui.GlobalSearchScreen()
                            is Route.DavRecycle -> com.hark.shiguang.ui.DavRecycleScreen(r)
                            Route.DavAccounts -> DavAccountsScreen()
                            Route.AiSettings -> AiSettingsScreen()
                            Route.SmartSettings -> SmartSettingsScreen()
                            Route.AdvancedSettings -> AdvancedSettingsScreen()
                            is Route.Upload -> UploadScreen(r)
                            is Route.MovieDetail -> MovieDetailScreen(r)
                            is Route.MoviePlay -> MoviePlayerScreen(r)
                            is Route.MovieActor -> MovieActorScreen(r.sourceKey, r.name)
                            Route.MovieLibrarySettings -> MovieLibrarySettingsScreen()
                            Route.MediaSettings -> MediaSettingsScreen()
                            Route.MusicSettings -> MusicSettingsScreen()
                            is Route.MusicList -> MusicListScreen(r)
                            Route.MusicNowPlaying -> MusicNowPlayingScreen()
                            is Route.X -> ExtHost(r.screen)
                            Route.Home -> {}
                        }
                    }
                    }
                    }
                }
                AppOverlays() // 1.0.9: crash report + update dialogs
                // 1.6.6 阶段 7：App 内提示条（代替前台 Toast）。首页要让过底栏（约 72dp）和迷你播放条（64dp）
                val onHome = Nav.stack.size == 1 && Nav.stack.firstOrNull() == Route.Home
                com.hark.shiguang.ui.SnackHost(if (onHome) 76.dp + com.hark.shiguang.ui.miniPlayerPad else 24.dp)
                if (AppLock.locked) LockOverlay { unlock() }
            }
        }
    }

    // 1.1.0 画中画：8–11 在这里手动进入（12+ 由 setAutoEnterEnabled 自动进入），见 VideoPip
    override fun onUserLeaveHint() { super.onUserLeaveHint(); VideoPip.onUserLeave(this) }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        VideoPip.inPip = isInPictureInPictureMode
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
        showBiometricPrompt()
    }

    /** 1.7.23：拆出来加 @RequiresApi(28)（unlock() 开头已对 SDK < 28 return，lint 看不穿 runCatching 报 NewApi；行为不变）。 */
    @androidx.annotation.RequiresApi(28)
    private fun showBiometricPrompt() {
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
