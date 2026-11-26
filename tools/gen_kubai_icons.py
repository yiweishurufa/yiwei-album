#!/usr/bin/env python3
"""Kubai Icons (icons-line, MIT) -> Compose ImageVector source (ui/KubaiIcons.kt).
Usage: python3 tools/gen_kubai_icons.py > app/src/main/java/com/hark/shiguang/ui/KubaiIcons.kt
Outline = every part stroked (solid parts filled). Duo (selected) = same + 'tint' shells filled at DUO_ALPHA,
so Icon(tint = …) colours both; the fill keeps its alpha under the SrcIn tint filter."""
import json, os, sys
# icons-line folder of a clone of https://github.com/kubai087/kubai-icons (MIT)
SRC = os.environ.get('KUBAI_ICONS_LINE', '/workspace/tools/agent-skills/kubai087_kubai-icons/icons-line')
STROKE = '1.5'
ICONS = [  # kotlin name, kubai file
    ('photos', 'image'), ('film', 'film'), ('music', 'music'), ('discover', 'sparkles'), ('settings', 'settings'),
    ('search', 'search'), ('calendar', 'calendar'), ('back', 'chevron-left'), ('down', 'chevron-down'),
    ('chevron', 'chevron-right'), ('close', 'x'), ('check', 'check'), ('grid', 'grid'), ('feed', 'list-view'),
    ('drive', 'hard-drive'), ('heart', 'heart'), ('play', 'play'), ('more', 'more-vertical'),
    # 1.6.0 第 1 阶段第 2 批：LI.* 全部改由 KI 提供
    ('videos', 'video'), ('edit', 'pencil'), ('albums', 'images'), ('folder', 'folder'), ('pin', 'map-pin'),
    ('copy', 'copy'), ('layers', 'layers'), ('upload', 'upload'), ('lock', 'lock'), ('info', 'info'),
    ('nas', 'server'), ('backup', 'cloud-upload'), ('trash', 'trash'), ('person', 'user'), ('swap', 'arrow-left-right'),
    ('clock', 'clock'), ('tv', 'tv'), ('sort', 'sort'), ('refresh', 'rotate-cw'), ('map', 'map'), ('plus', 'plus'),
    # 1.6.2 阶段 2：大图工具栏与「⋯」面板、合集 / 发现入口
    ('share', 'share'), ('download', 'download'), ('eye', 'eye'), ('sliders', 'sliders'), ('live', 'live'),
    ('moreH', 'more-horizontal'), ('album', 'album'), ('similar', 'scan-face'), ('star', 'star'), ('users', 'users-round'),
    ('history', 'history'), ('archive', 'archive'), ('camera', 'camera'), ('sparkle', 'wand'), ('upDown', 'arrow-up-down'),
    ('crop', 'crop'),
    # 1.6.6 阶段 4 / 5：影视、音乐、播放器控件
    ('pause', 'pause'), ('skipBack', 'skip-back'), ('skipFwd', 'skip-forward'), ('shuffle', 'shuffle'), ('repeat', 'repeat'),
    ('rewind', 'rewind'), ('fastFwd', 'fast-forward'), ('subtitles', 'subtitles'), ('cast', 'cast'), ('playlist', 'playlist'),
    ('maximize', 'maximize'), ('minimize', 'minimize'), ('lockOpen', 'lock-open'), ('rotate', 'rotate-ccw'), ('audio', 'audio-lines'),
    ('moon', 'moon'), ('menu', 'menu'), ('undo', 'undo'), ('redo', 'redo'), ('gauge', 'gauge'), ('volume', 'volume'),
    ('pip', 'picture-in-picture'), ('sun', 'sun'), ('alert', 'circle-alert'),
    # 1.7.6：替换剩余的 Material 图标
    ('up', 'chevron-up'), ('bell', 'bell'), ('battery', 'battery-charging'), ('rocket', 'rocket'), ('fileImage', 'file-image'),
    ('aperture', 'aperture'), ('mountain', 'mountain'), ('phone', 'smartphone'), ('cloud', 'cloud'), ('shield', 'shield'),
    ('minusCircle', 'circle-minus'), ('plusCircle', 'circle-plus'), ('checkCircle', 'circle-check'), ('wifi', 'wifi'),
    ('file', 'file'), ('clear', 'circle-x'), ('mail', 'mail'), ('bug', 'bug'),
]
# 另外生成整块填实版本（xxxSolid）的图标：播放器主控件
SOLID = {'play', 'pause', 'skipBack', 'skipFwd', 'heart'}
# 1.7.0 v2「液态玻璃」：底栏选中态用「实心感」版本（外形填 FILL_ALPHA 同色，再描线，线条仍看得清）
FILLED = {'photos', 'film', 'music', 'discover', 'settings'}
GAP = 0.5 * float(STROKE) + 0.75   # kit.mjs gapOf(s)

def _rings(d, closed_only):
    """Flatten an SVG path into point lists, one per subpath."""
    from svgpathtools import parse_path
    out = []
    for sp in parse_path(d).continuous_subpaths():
        pts = []
        for seg in sp:
            n = 1 if seg.__class__.__name__ == 'Line' else 16
            pts += [(seg.point(t / n).real, seg.point(t / n).imag) for t in range(n)]
        pts.append((sp[-1].end.real, sp[-1].end.imag))
        if closed_only and not sp.isclosed(): continue
        out.append((pts, sp.isclosed()))
    return out

def _cut_of(m):
    """Region a front part clears out of the parts behind it (kit.mjs: gap / occ / cutD)."""
    from shapely.geometry import Polygon, LineString
    from shapely.ops import unary_union
    if m.get('cutD'):
        return unary_union([Polygon(r).buffer(0) for r, c in _rings(m['cutD'], True)])
    filled = m.get('occ') or m['k'] == 'solid'
    geoms = []
    for r, closed in _rings(m['d'], False):
        if filled and closed: geoms.append(Polygon(r).buffer(0).buffer(GAP))
        else: geoms.append(LineString(r).buffer(GAP))
    return unary_union(geoms)

def _clip_path(cut):
    """24x24 canvas minus [cut], as path data with holes wound opposite (NonZero)."""
    from shapely.geometry import box, Polygon, MultiPolygon
    from shapely.geometry.polygon import orient
    g = box(-2, -2, 26, 26).difference(cut)
    polys = [g] if isinstance(g, Polygon) else list(g.geoms)
    d = []
    for pg in polys:
        pg = orient(pg, 1.0)
        for ring in [pg.exterior] + list(pg.interiors):
            c = list(ring.coords)[:-1]
            d.append('M' + 'L'.join('%.3f %.3f' % xy for xy in c) + 'Z')
    return ''.join(d)

def parts(name):
    j = json.load(open(os.path.join(SRC, name + '.json')))
    L = [p for p in j['w'][STROKE]['o'] if not p.get('ghost')]
    out = []
    for i, p in enumerate(L):
        cuts = [_cut_of(m) for m in L[i + 1:] if m.get('part') != p.get('part') and (m.get('gap') or m.get('occ') or m.get('cutD'))]
        clip = None
        if cuts:
            from shapely.ops import unary_union
            clip = _clip_path(unary_union(cuts))
        out.append((p['k'], bool(p.get('tint')), p.get('cap', 'round'), p['d'], clip))
    return out
w = sys.stdout.write
w('''package com.hark.shiguang.ui

// GENERATED by tools/gen_kubai_icons.py from Kubai Icons «icons-line» (stroke %s). Do not edit by hand.
// Kubai Icons — MIT License, Copyright (c) 2026 Kubai087 and the Kubai Icons contributors.
// https://github.com/kubai087/kubai-icons — full licence text: app/src/main/assets/licenses/kubai-icons-MIT.txt

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 1.6.0 图标（docs/DESIGN.md §6）：24 网格、1.5 线宽、圆头圆角。[duo] = 选中态「线性双色」：外形内填一层
 * [DUO_ALPHA] 的同色，再描线；用 Icon(tint = …) 着色。[solid] = 外形整块填实再描线（1.6.6：只给播放 / 暂停这类
 * 主控件用，小尺寸也认得出）。
 */
object KI {
    const val DUO_ALPHA = 0.22f
    /** 1.7.0：底栏选中态的「实心」填充透明度（描线不透明，内部细节可辨）。 */
    const val FILL_ALPHA = 0.5f
    /** [clip]：前面的部件在它身上留出的空隙（Kubai 的 gap / occ），画成「画布减去空隙」的裁剪路径。 */
    private class Part(val kind: Char, val tint: Boolean, val butt: Boolean, val d: String, val clip: String? = null)
    private val cache = HashMap<String, ImageVector>()
    private fun build(name: String, duo: Boolean, parts: Array<Part>, solid: Boolean = false, filled: Boolean = false): ImageVector = cache.getOrPut(name + duo + solid + filled) {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        val black = SolidColor(Color.Black)
        fun clipped(p: Part, draw: () -> Unit) {
            if (p.clip == null) draw() else { b.addGroup(clipPathData = addPathNodes(p.clip)); draw(); b.clearGroup() }
        }
        if (duo || solid || filled) parts.filter { it.tint }.forEach { p -> clipped(p) { b.addPath(addPathNodes(p.d), fill = black, fillAlpha = if (solid) 1f else if (filled) FILL_ALPHA else DUO_ALPHA) } }
        parts.forEach { p ->
            clipped(p) {
                if (p.kind == 's') b.addPath(addPathNodes(p.d), fill = black)
                else b.addPath(addPathNodes(p.d), fill = null, stroke = black, strokeLineWidth = %sf,
                    strokeLineCap = if (p.butt) StrokeCap.Butt else StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }
        b.build()
    }
''' % (STROKE, STROKE))
for kn, fn in ICONS:
    ps = parts(fn)
    arr = ', '.join('Part(%r, %s, %s, "%s"%s)' % ({'line': 'l', 'shell': 'h', 'solid': 's'}[k], 'true' if t else 'false', 'true' if c == 'butt' else 'false', d,
                                               (', "%s"' % cl) if cl else '') for k, t, c, d, cl in ps)
    arr = arr.replace("Part('", "Part('")
    w('    private val p_%s = arrayOf(%s)\n' % (kn, arr))
    w('    /** Kubai «%s» */\n    fun %s(duo: Boolean = false) = build("%s", duo, p_%s)\n' % (fn, kn, kn, kn))
    if kn in SOLID: w('    fun %sSolid() = build("%s", false, p_%s, solid = true)\n' % (kn, kn, kn))
    if kn in FILLED: w('    fun %sFilled() = build("%s", false, p_%s, filled = true)\n' % (kn, kn, kn))
w('}\n')
