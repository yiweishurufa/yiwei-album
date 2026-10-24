package com.hark.shiguang.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.hark.shiguang.Diag
import com.hark.shiguang.MediaFile
import com.hark.shiguang.data.FnTv
import com.hark.shiguang.data.FnTvStream
import com.hark.shiguang.data.FnTvTier
import com.hark.shiguang.data.FnTvUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 用户 2026-10-24 要求继续优化（回看飞牛转码 6c4271f）：服务器转码会话的「谁该 quit」账本，纯逻辑（见 FnTvSessionsTest）。
 *
 * 以前的漏洞：切清晰度时 play/play 还在路上（几百毫秒到几秒），用户就退出播放器 / 换集 / 切回原画 → stop() 时
 * 还不知道新会话的 play_link，等它回来时协程已随界面取消，结果被丢掉，服务器上的转码会话没人发 media.quit，
 * 只能等飞牛自己超时回收（期间 NAS 白白转码）。现在每次开会话拿一张票，stop() 让票作废；
 * 会话开好时票已作废，调用方必须立刻 quit 刚拿到的 link。
 */
internal class FnTvSessions {
    private var epoch = 0
    /** play_link of the session that is running now (the one stop() must quit). */
    var link: String? = null
        private set

    /** A new session is about to be opened: returns its ticket and the running link to quit first (if any). */
    fun begin(): Pair<Int, String?> { val old = link; link = null; return ++epoch to old }

    /** The session of [ticket] opened with [newLink]. false = it was stopped meanwhile: quit [newLink] now. */
    fun opened(ticket: Int, newLink: String): Boolean {
        if (ticket != epoch) return false
        link = newLink; return true
    }

    /** Ends everything: an in-flight open becomes stale; returns the running link to quit (if any). */
    fun stop(): String? { epoch++; val old = link; link = null; return old }
}

/**
 * 用户 2026-10-23 反馈（清晰度）：飞牛来源的「原画 + 飞牛影视转码档位」。只管状态（查到的档位、当前转码会话），
 * 播放器怎么换片源在 MoviePlayer 里。原画永远是直接播放原文件；转码只作用于当前这一集。
 */
internal class FnTvQuality {
    sealed interface State {
        data object Loading : State
        data class Off(val reason: String) : State
        data class Ready(val info: FnTv.Info) : State
    }

    /** Lookup result of the file in [forPath]. */
    var state by mutableStateOf<State>(State.Loading)
        private set
    var forPath by mutableStateOf("")
        private set
    /** Tier being played for [activePath]; null = 原画. */
    var tier by mutableStateOf<FnTvTier?>(null)
        private set
    /** A switch is in progress (the panel ignores taps meanwhile). */
    var busy by mutableStateOf(false)
    /** Set when a transcode starts; the player logs its first READY once (HLS duration check). */
    var pendingReadyLog = false
    var activePath: String? = null
        private set
    private var url: String? = null
    private val sessions = FnTvSessions()
    /** Streams the running transcode carries, reused when only the tier changes. */
    private var audio: FnTvStream? = null
    private var subtitle: FnTvStream? = null

    /** Transcode playlist of [path], or null when it plays the original. */
    fun urlFor(path: String?): String? = if (path != null && path == activePath) url else null
    fun transcoding(path: String?): Boolean = urlFor(path) != null && tier != null

    suspend fun lookup(path: String, file: MediaFile, titles: List<String>, season: Int?, episode: Int?) {
        forPath = path; state = State.Loading
        state = runCatching<State> { State.Ready(FnTv.lookup(file, titles, season, episode)) }
            .getOrElse { State.Off(if (it is FnTvUnavailable) it.message.orEmpty() else "查询飞牛影视失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** Info of [path] when its lookup succeeded. */
    fun info(path: String?): FnTv.Info? = (state as? State.Ready)?.info?.takeIf { path != null && path == forPath }

    /**
     * Starts [t] for [path] from [startSec] (ends the running session first). [audioPick] / [subPick] choose the
     * streams to keep from the file's lists; they are only asked when this file has no running transcode yet.
     */
    suspend fun start(path: String, t: FnTvTier, startSec: Long, audioPick: (List<FnTvStream>) -> FnTvStream?, subPick: (List<FnTvStream>) -> FnTvStream?): String {
        val i = info(path) ?: throw FnTvUnavailable("还没查到飞牛影视的转码档位")
        val keep = activePath == path
        val a = if (keep && audio != null) audio else audioPick(i.audio)
        val s = if (keep) subtitle else subPick(i.subs)
        val (ticket, old) = sessions.begin()
        old?.let { quitAsync(it) }
        url = null; activePath = null; tier = null
        // 不随界面取消：服务器可能已经开了会话，必须拿到 play_link 才能 quit 它
        val (u, l) = withContext(NonCancellable) { FnTv.play(i, t, startSec, a, s) }
        if (!sessions.opened(ticket, l)) {
            log("session opened after stop: quit")
            quitAsync(l)
            throw CancellationException("清晰度切换已取消")
        }
        url = u; activePath = path; tier = t; audio = a; subtitle = s; pendingReadyLog = true
        return u
    }

    /** Back to the original file: ends the session. */
    fun stop() {
        sessions.stop()?.let { quitAsync(it) }
        url = null; activePath = null; tier = null; audio = null; subtitle = null
    }

    private fun quitAsync(l: String) {
        // the player screen may be gone already: run outside its composition scope.
        // 进程被系统直接杀掉（划掉后台）时这里来不及发出，只能靠飞牛服务器的空闲超时回收。
        bg.launch { FnTv.quit(l) }
    }

    companion object {
        private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun log(msg: String) = Diag.log("FNTV", msg)
    }
}

/** 清晰度 panel rows for a 飞牛 source: 原画 first, then the server tiers; a one-line note while loading / when there are none. */
@Composable
internal fun ColumnScope.FnTvQualityRows(tv: FnTvQuality, path: String, originalDetail: String?, onPick: (FnTvTier?) -> Unit) {
    val playing = tv.tier.takeIf { tv.transcoding(path) }
    PanelRow("原画", originalDetail ?: "直接播放原文件", playing == null) { if (!tv.busy) onPick(null) }
    val st = tv.state.takeIf { tv.forPath == path }
    when (st) {
        is FnTvQuality.State.Ready -> {
            st.info.tiers.forEach { t -> PanelRow(t.label, listOf(t.detail, "转码").filter { it.isNotEmpty() }.joinToString(" · "), playing == t) { if (!tv.busy) onPick(t) } }
            if (st.info.tiers.isEmpty()) PanelNote("飞牛影视没有为这个文件提供转码档位")
        }
        is FnTvQuality.State.Off -> PanelNote(st.reason)
        else -> PanelNote("正在向飞牛影视查询转码档位…")
    }
    if (tv.busy) PanelNote("正在切换…")
}
