package com.hark.shiguang.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration

/** Small shared helpers for the 1.0.9 polish round. */

/**
 * 按下时轻微缩小（1.6.0 动效规范：按下快、松手稍慢，见 [Motion]）；不消费触摸，点击 / 长按照常工作。
 * 大面积元素（封面卡、海报）用 [Motion.PRESS_SCALE_LARGE]。
 */
fun Modifier.pressScale(pressed: Float = Motion.PRESS_SCALE): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (down) pressed else 1f, if (down) Motion.pressIn() else Motion.pressOut(), label = "press")
    this.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            down = true
            waitForUpOrCancellation(PointerEventPass.Initial)
            down = false
        }
    }.graphicsLayer { scaleX = s; scaleY = s }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(remember { MutableInteractionSource() }, null, onClick = onClick)
}

/** Window width ≥ 600 dp: tablets, unfolded foldables, phones in landscape. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** Grid columns for the current window: phones keep [base]; wide windows get denser grids. */
@Composable
fun adaptiveCols(base: Int): Int {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w >= 1000 -> base + 4
        w >= 600 -> base + 2
        else -> base
    }
}

/**
 * 触感反馈（docs/DESIGN.md §8）：只在「状态真的变了」的时刻轻轻震一下——切换底栏、开关、长按进入多选、
 * 拖动选择跨过一格、下拉刷新到位。系统关闭触感时 View.performHapticFeedback 自己会不震。
 */
object Haptics {
    /** 切换 tab / 分段 / 胶囊：最轻的一下。 */
    fun tick(view: android.view.View) { view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
    /** 开关打开 / 关闭。API 34 有专门的 TOGGLE_ON/OFF，之前用 CLOCK_TICK。 */
    fun toggle(view: android.view.View, on: Boolean) {
        val c = if (android.os.Build.VERSION.SDK_INT >= 34) (if (on) android.view.HapticFeedbackConstants.TOGGLE_ON else android.view.HapticFeedbackConstants.TOGGLE_OFF)
            else android.view.HapticFeedbackConstants.CLOCK_TICK
        view.performHapticFeedback(c)
    }
    /** 操作完成（保存、删除确认）。 */
    fun confirm(view: android.view.View) {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }
    /** 操作被拒（不可用、失败）。 */
    fun reject(view: android.view.View) {
        view.performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.REJECT else android.view.HapticFeedbackConstants.LONG_PRESS)
    }
}
