package com.hark.shiguang

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 1.1.0 视频画中画：影视播放器播放时离开 App（Home 键 / 上滑回桌面）进入小窗。
 * - Android 12+ 用 setAutoEnterEnabled（手势回桌面时动画更顺），8–11 在 onUserLeaveHint 里手动进入。
 * - 小窗只显示视频（播放器根据 [inPip] 隐藏控件、顶栏），系统按钮：播放/暂停（+ 有下一集时「下一集」）。
 * - 开关：影音设置 →「离开时画中画」（默认开，[enabled]）。
 */
object VideoPip {
    const val ACTION = "com.hark.shiguang.PIP_CONTROL"
    const val EXTRA = "cmd"
    const val CMD_TOGGLE = 1
    const val CMD_NEXT = 2

    /** 影音设置「离开时画中画」，默认开。 */
    var enabled: Boolean get() = Store.getStr("play.pip", "1") == "1"; set(v) = Store.putStr("play.pip", if (v) "1" else "0")

    /** true while the activity is shown as a picture-in-picture window (Compose reads it to hide the controls). */
    var inPip by mutableStateOf(false)

    /** What the open video player reports; null when no video is playing in the built-in player. */
    class Session(val width: Int, val height: Int, val playing: Boolean, val hasNext: Boolean, val sourceRect: Rect?)
    @Volatile var session: Session? = null
        private set

    fun supported(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Called by the player whenever playing state / video size / queue position changes; null when it closes. */
    fun update(act: Activity?, s: Session?) {
        session = s
        if (act == null || !supported(act)) return
        runCatching { act.setPictureInPictureParams(params(act, s)) }
            .onFailure { Diag.log("PLAY", "pip params: ${it.javaClass.simpleName}") }
    }

    /** Android 8–11: the activity forwards onUserLeaveHint here. 12+ enters automatically via setAutoEnterEnabled. */
    fun onUserLeave(act: Activity) {
        if (Build.VERSION.SDK_INT >= 31) return
        val s = session ?: return
        if (!shouldEnter(s) || !supported(act)) return
        runCatching { act.enterPictureInPictureMode(params(act, s)) }
            .onFailure { Diag.log("PLAY", "pip enter: ${it.javaClass.simpleName}") }   // user turned PiP off for the app in system settings
    }

    private fun shouldEnter(s: Session?) = s != null && s.playing && enabled

    private fun params(ctx: Context, s: Session?): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
        if (s != null) {
            val (w, h) = PipMath.aspect(s.width, s.height)
            b.setAspectRatio(Rational(w, h))
            s.sourceRect?.takeIf { !it.isEmpty }?.let { b.setSourceRectHint(it) }
            val acts = ArrayList<RemoteAction>()
            acts += action(ctx, CMD_TOGGLE, if (s.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, if (s.playing) "暂停" else "播放")
            if (s.hasNext) acts += action(ctx, CMD_NEXT, android.R.drawable.ic_media_next, "下一集")
            b.setActions(acts)
        } else b.setActions(emptyList())
        if (Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(shouldEnter(s))
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    private fun action(ctx: Context, cmd: Int, icon: Int, label: String): RemoteAction {
        val i = Intent(ACTION).setPackage(ctx.packageName).putExtra(EXTRA, cmd)
        val pi = PendingIntent.getBroadcast(ctx, cmd, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return RemoteAction(Icon.createWithResource(ctx, icon), label, label, pi)
    }
}

/** Pure helpers (unit-tested). */
object PipMath {
    /** Android accepts aspect ratios between 1:2.39 and 2.39:1; anything outside (or unknown) is clamped / falls back to 16:9. */
    fun aspect(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 16 to 9
        val r = w.toDouble() / h
        return when {
            r > 2.39 -> 239 to 100
            r < 1 / 2.39 -> 100 to 239
            else -> w to h
        }
    }
}
