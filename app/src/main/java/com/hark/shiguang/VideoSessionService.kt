@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.hark.shiguang

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 用户 2026-11-02（优化 6）：影视息屏 / 切到后台继续播放声音。
 *
 * 影视播放器（MoviePlayerScreen 里的 ExoPlayer）不搬家：播放器页打开时用它建一个 MediaSession（id "video"，和音乐的
 * 默认会话不冲突），再启动这个 MediaSessionService。media3 在播放时自动把服务提成前台服务（mediaPlayback）并显示
 * 媒体通知 / 锁屏控制，进程不会因为息屏被系统收走；暂停后通知可划掉。回到前台时 SurfaceView 重建，画面自动接上。
 * 播放器页关闭时 [stop] 释放会话并停掉服务。
 */
class VideoSessionService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).setChannelName(R.string.music_channel).build().apply { setSmallIcon(R.drawable.ic_notify) })
        VideoSession.session?.let { addSession(it) }
        VideoSession.service = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = VideoSession.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // the app was swiped away: a video has nothing to come back to
        VideoSession.session?.player?.pause()
        stopSelf()
    }

    override fun onDestroy() {
        if (VideoSession.service === this) VideoSession.service = null
        super.onDestroy()
    }
}

object VideoSession {
    @Volatile var session: MediaSession? = null; internal set
    @Volatile internal var service: VideoSessionService? = null

    /** Called when the video player opens (app in the foreground, so starting the service is allowed). */
    fun start(ctx: Context, player: Player, title: String) {
        stop(ctx)
        val open = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = runCatching { MediaSession.Builder(ctx, player).setId("video").setSessionActivity(open).build() }
            .onFailure { Diag.log("PLAYER", "video session failed: ${it.javaClass.simpleName} ${it.message}") }.getOrNull() ?: return
        runCatching { ctx.startService(Intent(ctx, VideoSessionService::class.java)) }
            .onFailure { Diag.log("PLAYER", "video service start failed: ${it.javaClass.simpleName}") }
        Diag.log("PLAYER", "video session on ($title)")
    }

    fun stop(ctx: Context) {
        val s = session ?: return
        session = null
        service?.let { svc -> runCatching { svc.removeSession(s) } }
        runCatching { s.release() }
        runCatching { ctx.stopService(Intent(ctx, VideoSessionService::class.java)) }
    }
}
