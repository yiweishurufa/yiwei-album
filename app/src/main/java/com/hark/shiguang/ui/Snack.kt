package com.hark.shiguang.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * 1.6.6 阶段 7「Toast → Snackbar」（docs/DESIGN.md §7 / §10）。
 *
 * 评估结论：系统 Toast 的样式跟着厂商 ROM 走（MIUI / ColorOS 各一套，深浅色也不跟 App），位置固定，
 * 不能带操作，Android 12 起还会被截成两行、带上 App 图标。改成 App 自己画的一条提示：
 * - 样式：反色胶囊（深色主题里是浅底深字，浅色主题相反），圆角 14，一行到两行，在底栏 / 迷你播放条上方；
 * - 动效：从下方滑入（面板弹簧）、淡出；「移除动画」时只淡入淡出；
 * - 读屏：liveRegion = Polite，和 Toast 一样会被读出来；
 * - 可撤销：[toastUndo] 带「撤销」按钮，停留 4 秒（普通提示 2.5 秒），点提示本身可提前关掉。
 *
 * 所有旧的 [toast] 调用不用改：App 在前台（这条提示的宿主 [SnackHost] 处于 RESUMED）时走这里，
 * 退到后台（例如后台服务、画中画外的通知）时仍用系统 Toast，信息不会丢。
 */
object Snack {
    class Msg(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null, val id: Long = System.nanoTime())

    var current by mutableStateOf<Msg?>(null)
        private set
    /** [SnackHost] 处于前台时为 true。 */
    @Volatile var hostActive = false
        internal set
    private val main = Handler(Looper.getMainLooper())

    /** 线程安全：总是切到主线程再改状态。 */
    fun show(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        main.post { current = Msg(text, action, onAction) }
    }

    fun dismiss(m: Msg) { main.post { if (current?.id == m.id) current = null } }
}

/** 带「撤销」的提示：可逆操作（收藏、标为已看、加入我喜欢……）做完后给一次反悔的机会。 */
fun toastUndo(ctx: android.content.Context, msg: String, undo: () -> Unit) {
    if (Snack.hostActive) Snack.show(msg, "撤销", undo) else toast(ctx, msg)
}

/**
 * 放在 Activity 根部、所有页面之上。[bottom] 是它离屏幕底部的距离（首页要让过底栏和迷你播放条）。
 */
@Composable
fun SnackHost(bottom: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> Snack.hostActive = true
                Lifecycle.Event.ON_PAUSE -> Snack.hostActive = false
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        Snack.hostActive = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        onDispose { owner.lifecycle.removeObserver(obs); Snack.hostActive = false }
    }
    val cur = Snack.current
    // 自动消失：普通 2.5 秒，带操作 4 秒；新提示进来时重新计时
    LaunchedEffect(cur?.id) { val m = cur ?: return@LaunchedEffect; delay(if (m.action != null) 4000 else 2500); Snack.dismiss(m) }
    var last by remember { mutableStateOf<Snack.Msg?>(null) }
    if (cur != null) last = cur
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(cur != null, Modifier.navigationBarsPadding().padding(bottom = bottom, start = Space.page, end = Space.page),
            enter = slideInVertically(Motion.sheet()) { if (Motion.reduced) 0 else it } + fadeIn(Motion.enter()),
            exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { if (Motion.reduced) 0 else it / 3 }) {
            val m = last ?: return@AnimatedVisibility
            // 反色：深色主题浅底深字，浅色主题深底浅字
            val bg = C.Text; val fg = C.Bg
            Row(Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(Radius.m)).background(bg)
                .clickable(onClickLabel = "关闭提示") { Snack.dismiss(m) }
                .semantics { liveRegion = LiveRegionMode.Polite }
                .heightIn(min = 48.dp).padding(start = Space.l, end = if (m.action != null) Space.xs else Space.l), verticalAlignment = Alignment.CenterVertically) {
                Text(m.text, color = fg, style = MaterialTheme.typography.bodyMedium.copy(color = fg), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(vertical = Space.m))
                if (m.action != null) {
                    // 操作文字用强调色的另一档（Accent2）：深色主题下浅底配深一档的强调色，浅色主题下深底配浅一档，对比度都 ≥ 4.5
                    val ac = C.Accent2
                    Box(Modifier.padding(start = Space.s).heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.s))
                        .clickable(role = Role.Button) { Snack.dismiss(m); m.onAction?.invoke() }.padding(horizontal = Space.m), contentAlignment = Alignment.Center) {
                        Text(m.action, color = ac, style = MaterialTheme.typography.labelLarge.copy(color = ac))
                    }
                }
            }
        }
    }
}
