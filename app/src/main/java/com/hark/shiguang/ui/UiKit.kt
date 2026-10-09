package com.hark.shiguang.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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

/** Subtle shrink while pressed; does not consume the touch, so clicks / long presses still work. */
fun Modifier.pressScale(pressed: Float = 0.965f): Modifier = composed {
    var down by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (down) pressed else 1f, spring(dampingRatio = 0.7f, stiffness = 700f), label = "press")
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
