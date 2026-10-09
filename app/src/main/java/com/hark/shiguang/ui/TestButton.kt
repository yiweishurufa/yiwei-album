package com.hark.shiguang.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1.0.0: every 「测试连接」/「测速」 shows its result on the button itself.
 * Idle → 「测试中…」 with a progress bar → 「成功」(accent) / 「失败」(danger) with the detail in small text below.
 * The button text goes back to [label] after [holdMs]; the detail line stays (dimmed) until the inputs ([resetKey]) change.
 *
 * [run] returns the success detail (may be empty) or throws; it can report 0..1 progress, otherwise the bar is indeterminate.
 * [okLabel] lets a speed test show its number on the button ("320 ms") instead of 「成功」.
 */
class TestState {
    var phase by mutableIntStateOf(0)        // 0 idle, 1 running, 2 ok, 3 fail
    var progress by mutableFloatStateOf(-1f) // <0 indeterminate
    var detail by mutableStateOf("")
    var okText by mutableStateOf("成功")
    var stale by mutableStateOf(false)       // detail kept after the button reverted
    fun reset() { phase = 0; progress = -1f; detail = ""; stale = false }
}

@Composable
fun TestButton(
    label: String = "测试连接",
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    height: Dp = 50.dp,
    filled: Boolean = false,
    holdMs: Long = 4000,
    enabled: Boolean = true,
    state: TestState = remember { TestState() },
    okLabel: (String) -> String = { "成功" },
    run: suspend (progress: (Float) -> Unit) -> String,
) {
    val scope = rememberCoroutineScope()
    // inputs changed → forget the old result
    LaunchedEffect(resetKey) { if (state.phase != 1) state.reset() }
    LaunchedEffect(state.phase) {
        if (state.phase == 2 || state.phase == 3) { delay(holdMs); state.phase = 0; state.stale = state.detail.isNotEmpty() }
    }
    val bg by animateColorAsState(when (state.phase) {
        2 -> C.Accent.copy(alpha = if (filled) 1f else 0.18f)
        3 -> C.Danger.copy(alpha = if (filled) 1f else 0.16f)
        else -> if (filled) C.Accent else C.Surface2
    }, label = "testbg")
    val fg = when (state.phase) {
        2 -> if (filled) C.OnAccent else C.Accent
        3 -> if (filled) Color.White else C.Danger
        else -> if (filled) C.OnAccent else C.Text
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(enabled = enabled && state.phase != 1) {
                state.phase = 1; state.progress = -1f; state.detail = ""; state.stale = false
                scope.launch {
                    try {
                        val d = run { p -> state.progress = p.coerceIn(0f, 1f) }
                        state.detail = d; state.okText = okLabel(d); state.phase = 2
                    } catch (e: CancellationException) { state.phase = 0; throw e
                    } catch (e: Throwable) {
                        state.detail = (e.message ?: e.javaClass.simpleName).take(240); state.phase = 3
                    }
                }
            }, contentAlignment = Alignment.Center) {
            Text(when (state.phase) { 1 -> "测试中…"; 2 -> state.okText; 3 -> "失败"; else -> label }, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (state.phase == 1) {
                val m = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)
                if (state.progress >= 0f) LinearProgressIndicator(progress = { state.progress }, modifier = m, color = C.Accent, trackColor = Color.Transparent)
                else LinearProgressIndicator(modifier = m, color = C.Accent, trackColor = Color.Transparent)
            }
        }
        if (state.detail.isNotEmpty() && state.phase != 1) {
            val c = when { state.stale -> C.Sub; state.phase == 3 -> C.Danger; else -> C.Sub }
            Text(state.detail, color = c, fontSize = 11.sp, lineHeight = 15.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 4.dp, end = 4.dp))
        }
    }
}
