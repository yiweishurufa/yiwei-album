package com.hark.shiguang.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch

/*
 * 1.6.2 阶段 7：对话框、底部面板、下拉菜单统一外观与弹出动效（DESIGN §5 圆角、§7「底部面板 / 对话框」）。
 * 页面里不直接用 Material 的 AlertDialog / ModalBottomSheet / DropdownMenu，改用这里的 App* 版本：
 * 参数与 Material 版一一对应（调用处只换名字），默认值换成本 App 的 token。
 */

/** 对话框弹出：从 0.94 放大到 1 + 淡入（Motion.sheet，带一点点余量）；「移除动画」时不做。 */
private fun Modifier.popIn(): Modifier = composed {
    if (Motion.reduced) return@composed this
    val s = remember { Animatable(0.94f) }
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { a.animateTo(1f, Motion.enter()) }
        s.animateTo(1f, Motion.sheet())
    }
    graphicsLayer { scaleX = s.value; scaleY = s.value; alpha = a.value }
}

/**
 * 统一的对话框：圆角 32（1.7.0）、卡片色底、无色调叠加；标题 titleLarge（DESIGN §3「对话框标题」）、正文次要色。
 * 调用处给了 containerColor / 颜色的照旧生效。
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = RoundedCornerShape(Radius.sheet),
    containerColor: Color = C.Surface,
    iconContentColor: Color = C.Sub,
    titleContentColor: Color = C.Text,
    textContentColor: Color = C.Sub,
    tonalElevation: Dp = 0.dp,
    properties: DialogProperties = DialogProperties(),
) {
    // 1.7.0 v2：玻璃对话框——窗口背后模糊（API 31+）+ 半透明卡片色 + 上沿高光边；圆角 32
    var blur by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { blur = WindowBlurBehind(); confirmButton() },
        modifier = modifier.popIn().glassRim(shape),
        dismissButton = dismissButton,
        icon = icon,
        title = title?.let { t -> { ProvideTextStyle(MaterialTheme.typography.titleLarge) { t() } } },
        text = text,
        shape = shape,
        containerColor = glassOverlayColor(containerColor, blur),
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = properties,
    )
}

/** 底部面板的拖动条：36×4 的弱色胶囊，上下各 10dp（触摸仍是整块面板）。 */
@Composable
fun SheetHandle() {
    Box(Modifier.padding(vertical = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(C.Faint.copy(alpha = 0.6f)))
}

/**
 * 统一的底部面板：上沿圆角 28、卡片色底、无色调叠加、统一拖动条。Material 的面板本身按松手速度判断收起 / 弹回，
 * 弹出用它自带的弹簧，这里不再叠加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = C.Surface,
    dragHandle: (@Composable () -> Unit)? = { SheetHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    // 1.7.0 v2：玻璃面板——窗口背后模糊（API 31+）+ 半透明卡片色 + 上沿高光边；上沿圆角 32
    var blur by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.glassRim(shape),
        sheetState = sheetState,
        shape = shape,
        containerColor = glassOverlayColor(containerColor, blur),
        tonalElevation = 0.dp,
        dragHandle = { blur = WindowBlurBehind(); dragHandle?.invoke() },
        content = content,
    )
}

/**
 * 统一的下拉菜单：圆角 14（Material 1.2 的菜单形状取 shapes.extraSmall，这里只在菜单内换掉它）、卡片色底。
 * 菜单项高度沿用 Material 的 48dp；弹出用 Material 自带的缩放淡入（从锚点展开）。
 */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    scrollState: ScrollState = rememberScrollState(),
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    // 1.7.0 v2：玻璃菜单——几乎不透明的卡片色（菜单很小，不开窗口模糊）+ 上沿高光边，圆角 18
    val shape = RoundedCornerShape(18.dp)
    MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = shape),
        colorScheme = MaterialTheme.colorScheme.copy(surfaceContainer = glassOverlayColor(C.Surface, false))) {
        DropdownMenu(expanded, onDismissRequest, modifier.glassRim(shape, fadeAt = 40.dp), offset, scrollState, properties, content)
    }
}
