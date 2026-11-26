package com.hark.shiguang.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C as MC
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.hark.shiguang.MediaBadges
import com.hark.shiguang.TrackNames

/** 用户 2026-10-23 反馈：播放器右侧面板（音轨 / 字幕 / 清晰度 / 详细信息），对标腾讯视频的侧边面板。 */
internal enum class PlayerPanel { AUDIO, TEXT, QUALITY, INFO, EPISODES, SETTINGS }

/** One media3 track behind a picker row. */
internal data class TrackRef(val group: Tracks.Group, val index: Int) {
    val format: Format get() = group.getTrackFormat(index)
    val selected: Boolean get() = group.isTrackSelected(index)
}

/** A picker row and the tracks behind it (the first one is selected when the row is tapped). */
internal data class TrackRow(val option: TrackNames.Option, val refs: List<TrackRef>) {
    val selected: Boolean get() = refs.any { it.selected }
}

/** Supported tracks of [type] in player order (all of them when none is supported, so the list is never empty by mistake). */
private fun refsOf(tracks: Tracks, type: Int): List<TrackRef> {
    val all = tracks.groups.filter { it.type == type }.flatMap { g -> (0 until g.length).map { TrackRef(g, it) } }
    return all.filter { it.group.isTrackSupported(it.index) }.ifEmpty { all }
}

private fun describe(id: Int, f: Format) = TrackNames.Track(
    id = id, language = f.language, label = f.label, mime = f.sampleMimeType, codecs = f.codecs,
    channels = f.channelCount.coerceAtLeast(0),
    forced = f.selectionFlags and MC.SELECTION_FLAG_FORCED != 0,
    commentary = f.roleFlags and MC.ROLE_FLAG_COMMENTARY != 0,
)

/** Audio (TRACK_TYPE_AUDIO) or subtitle (TRACK_TYPE_TEXT) rows, duplicates merged (see [TrackNames.options]). */
internal fun trackRows(tracks: Tracks, type: Int): List<TrackRow> {
    val refs = refsOf(tracks, type)
    val opts = TrackNames.options(refs.mapIndexed { i, r -> describe(i, r.format) }, audio = type == MC.TRACK_TYPE_AUDIO)
    return opts.map { o -> TrackRow(o, o.ids.map { refs[it] }) }
}

/**
 * Quality rows. Only a real ladder counts: an adaptive group, or several video streams when none is Dolby Vision
 * (a DV profile 7 file has a base and an enhancement layer — two video groups that are not two qualities).
 */
@androidx.annotation.OptIn(UnstableApi::class)   // Format.bitrate
internal fun qualityRows(tracks: Tracks): List<TrackRow> {
    val groups = tracks.groups.filter { it.type == MC.TRACK_TYPE_VIDEO }
    val dv = groups.any { g -> (0 until g.length).any { MediaBadges.dvProfile(g.getTrackFormat(it)) != null } }
    val refs = refsOf(tracks, MC.TRACK_TYPE_VIDEO).let { r -> if (dv) r.filter { it.selected }.ifEmpty { r.take(1) } else r }
    val opts = TrackNames.qualities(refs.mapIndexed { i, r -> TrackNames.Video(i, r.format.width, r.format.height, r.format.bitrate.coerceAtLeast(0)) })
    return opts.map { o -> TrackRow(o, o.ids.map { refs[it] }) }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun selectTrack(player: ExoPlayer, type: Int, ref: TrackRef) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setTrackTypeDisabled(type, false)
        .setOverrideForType(TrackSelectionOverride(ref.group.mediaTrackGroup, ref.index))
        .build()
}

internal fun subtitlesOff(player: ExoPlayer) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(MC.TRACK_TYPE_TEXT).setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build()
}

/** 清晰度「自动」: drop the manual video pick (only offered when there is a real ladder, so no DV override is lost). */
internal fun qualityAuto(player: ExoPlayer) {
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(MC.TRACK_TYPE_VIDEO).build()
}

@androidx.annotation.OptIn(UnstableApi::class)   // TrackGroup.type
internal fun hasVideoOverride(player: ExoPlayer) =
    player.trackSelectionParameters.overrides.values.any { it.mediaTrackGroup.type == MC.TRACK_TYPE_VIDEO }

// ---- UI

/*
 * 1.7.6 v2：播放器上的玻璃（DESIGN §4、§9）。播放器永远是黑底（onDark），视频画在 SurfaceView 上，RenderNode 拿不到画面，
 * 所以这里一律走降级玻璃外观（着色 + 噪点 + 高光边），不做实时模糊；简洁风格是不透明纯色面。
 * 只有图标的圆钮用较浅的着色（≈46% 黑，白色图标在纯白画面上仍 ≥ 3:1）；带文字的气泡 / 胶囊用深着色（≈67% 黑，白字 ≥ 7:1）。
 */
internal fun Modifier.playerGlass(shape: Shape, text: Boolean = false, highlight: Float = 0.7f): Modifier =
    this.glass(shape, backdrop = null, tint = Color.Black.copy(alpha = if (text) 0.9f else 0.62f), shadow = 0.dp, highlight = highlight, onDark = true)

/** 面板上的次要文字：白 78%（玻璃上不用灰色，用正文色降透明度，最差背景下 ≥ 4.5:1）。 */
private val Dim = Color(0xC7FFFFFF)
private val Note = Color(0xB3FFFFFF)

/**
 * Right-side panel over the video; tapping outside closes it. 1.7.6：浮在右侧的玻璃面板（离边 12dp、圆角 32），
 * 打开时「材质到来」——从右侧滑入一小段 + 放大 + 淡入（移除动画时只淡入）。
 */
@Composable
internal fun SidePanel(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize().background(Color(0x40000000))
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)) {
        AnimatedVisibility(shown, Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = if (Motion.reduced) fadeIn(Motion.enter()) else fadeIn(Motion.enter()) + slideInHorizontally(Motion.materialize()) { it / 5 } + scaleIn(Motion.materialize(), 0.94f)) {
            val shape = RoundedCornerShape(Radius.sheet)
            Column(Modifier.fillMaxHeight().systemBarsPadding().padding(12.dp).widthIn(min = 280.dp, max = 360.dp).fillMaxWidth(0.36f)
                .playerGlass(shape, text = true, highlight = 1f).clip(shape)
                .clickable(remember { MutableInteractionSource() }, indication = null) { }   // taps inside do not close it
                .padding(top = 18.dp, bottom = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp).semantics { heading() })
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(top = 4.dp), content = content)
            }
        }
    }
}

/**
 * One choice: main text plus optional small secondary line. 1.7.6：选中项是一块强调色淡底的圆角胶囊 + 勾 + 强调色粗字
 * （不只靠颜色：还有勾和字重），读屏报「已选中」。
 */
@Composable
internal fun PanelRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(shape)
        .background(if (selected) C.Accent.copy(alpha = 0.16f) else Color.Transparent)
        .heightIn(min = 52.dp).clickable(onClick = onClick).semantics { this.selected = selected }
        .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) { if (selected) Icon(KI.check(), null, tint = C.Accent, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, color = if (selected) C.Accent else Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
            if (!detail.isNullOrEmpty()) Text(detail, color = Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Small explanation line at the bottom of a panel. */
@Composable
internal fun PanelNote(text: String) {
    Text(text, color = Note, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
}

/** 详细信息 row: label on the left, value on the right (values can wrap, paths are long). */
@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
        Text(label, color = Dim, fontSize = 13.sp, modifier = Modifier.width(76.dp))
        Text(value, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

/** 1.7.6：分区标题改 v2 字阶（粗体、加大），分区之间留出呼吸。 */
@Composable
internal fun InfoSection(title: String) {
    Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall.copy(color = Color.White, fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 2.dp).semantics { heading() })
}
