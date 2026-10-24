package com.hark.shiguang.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C as MC
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.hark.shiguang.Diag
import com.hark.shiguang.SeekCoalescer

/**
 * 用户 2026-10-24 反馈：连续快进时闪退 / 卡顿。所有「同一集内跳转」都经过这里合并（规则见 [SeekCoalescer]）：
 * 手势横滑松手、控制栏快进 / 快退按钮、拖进度条。切集、上一集 / 下一集不合并，并丢弃未执行的跳转。
 *
 * [forUi] 交给 PlayerView：它的快进 / 快退 / 进度条都走合并，进度条和时间也立刻显示合并后的目标位置。
 * 只在主线程使用。
 */
@UnstableApi
class PlayerSeekGate(private val player: Player) {
    private val co = SeekCoalescer()
    private val main = Handler(Looper.getMainLooper())
    private var released = false
    private val check = Runnable { tick() }
    /** 用户 2026-10-24 要求继续优化：一暂停（任何入口：按钮、双击、PiP、耳机）就把还没执行的跳转立刻执行。 */
    private val pauseListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) flushNow("pause") }
    }
    init { player.addListener(pauseListener) }

    /** 当前位置；有还没执行的跳转时返回它的目标（继续快进时在它上面累加）。 */
    fun position(): Long = co.pendingPosition(player.currentMediaItemIndex) ?: player.currentPosition

    /** 同一集内跳到 [positionMs]。 */
    fun seekTo(positionMs: Long, from: String) {
        if (released) return
        val idx = player.currentMediaItemIndex
        // 暂停中（playWhenReady = false）立刻跳：没有边播边 flush 的风险，进度条和画面马上到位
        val s = co.request(idx, positionMs, SystemClock.uptimeMillis(), immediate = !player.playWhenReady)
        if (s != null) { main.removeCallbacks(check); apply(s, from) } else schedule()
    }

    /** 快进 / 快退 [deltaMs]（按钮用）。 */
    fun step(deltaMs: Long, from: String) {
        val d = player.duration.takeIf { it != MC.TIME_UNSET } ?: 0L
        seekTo(SeekCoalescer.step(position(), deltaMs, d), from)
    }

    /** 切集 / 换片源之前：丢掉还没执行的跳转。 */
    fun cancel() { co.clear(); main.removeCallbacks(check) }

    /** 播放器释放前调用：之后的请求全部忽略，不会在释放后的播放器上 seek。 */
    fun release() { released = true; cancel(); player.removeListener(pauseListener) }

    private fun flushNow(from: String) {
        if (released) return
        main.removeCallbacks(check)
        co.flush(SystemClock.uptimeMillis(), player.currentMediaItemIndex)?.let { apply(it, from) }
    }

    private fun schedule() {
        main.removeCallbacks(check)
        val at = co.nextCheckAt() ?: return
        main.postAtTime(check, at)
    }

    private fun tick() {
        if (released) return
        val s = co.due(SystemClock.uptimeMillis(), player.currentMediaItemIndex)
        if (s != null) apply(s, "merged") else schedule()
    }

    private fun apply(s: SeekCoalescer.Seek, from: String) {
        if (released || s.index != player.currentMediaItemIndex) return
        if (s.merged > 1) Diag.log("PLAYER", "seek $from → ${s.positionMs / 1000}s (合并 ${s.merged} 次)")
        player.seekTo(s.positionMs)
    }

    /** PlayerView 用的包装：快进、快退、进度条走合并；上一集 / 下一集等先丢弃待跳转再照常执行。 */
    val forUi: Player = object : ForwardingPlayer(player) {
        override fun seekForward() = step(player.seekForwardIncrement, "button+")
        override fun seekBack() = step(-player.seekBackIncrement, "button-")
        override fun seekTo(positionMs: Long) = this@PlayerSeekGate.seekTo(positionMs, "bar")
        override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
            if (mediaItemIndex == player.currentMediaItemIndex) this@PlayerSeekGate.seekTo(positionMs, "bar")
            else { cancel(); super.seekTo(mediaItemIndex, positionMs) }
        }
        override fun seekToNext() { cancel(); super.seekToNext() }
        override fun seekToPrevious() { cancel(); super.seekToPrevious() }
        override fun seekToNextMediaItem() { cancel(); super.seekToNextMediaItem() }
        override fun seekToPreviousMediaItem() { cancel(); super.seekToPreviousMediaItem() }
        override fun seekToDefaultPosition() { cancel(); super.seekToDefaultPosition() }
        override fun seekToDefaultPosition(mediaItemIndex: Int) { cancel(); super.seekToDefaultPosition(mediaItemIndex) }
        override fun getCurrentPosition(): Long = co.pendingPosition(player.currentMediaItemIndex) ?: player.currentPosition
        override fun getContentPosition(): Long = co.pendingPosition(player.currentMediaItemIndex) ?: player.contentPosition
    }
}
