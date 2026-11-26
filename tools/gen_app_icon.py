#!/usr/bin/env python3
"""
一维相册 1.7.11 App 图标（v2「液态玻璃」）生成器：自适应图标前景 / 背景 / 单色 + 启动页图标，全部矢量。

身份不变（用户 2026-10-24 定的「蓝色简约 + 飞牛元素」）：飞牛蓝、照片方块、方块里横着的一道（「一」）、两侧上方一对月牙角。
v2 换的是材质：
  背景  = 飞牛蓝对角渐变 + 左上青色、右下紫色两团环境光（同 App 里的 ambientGlow）
  前景  = 方块变成一块磨砂玻璃（半透明白、上亮下暗）+ 上沿亮、下沿弱的高光边 + 上半部镜面反光 + 柔和投影；
          「一」是一道发光的白线；右上角一颗小太阳点出「照片」；月牙角实心白（微微偏冷的竖向渐变）
  单色  = 与旧版同一轮廓（方块挖出「一」+ 月牙角），Android 13 主题图标只看 alpha
  启动页 = drawable-v31/ic_splash.xml（animated-vector：玻璃方块从 0.86 弹到 1、角和太阳随后淡入；系统「移除动画」时系统自己跳过）
           drawable/ic_splash_legacy.xml（Android 8–11 的 windowBackground 用：圆角方块版完整图标）

用法：python3 tools/gen_app_icon.py [预览输出目录]   （需要 pip install cairosvg pillow 才出预览；只生成 XML 不需要）
"""
import math, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s

def rrect(x, y, w, h, r):
    return (f"M{f(x+r)},{f(y)}H{f(x+w-r)}A{f(r)},{f(r)} 0 0 1 {f(x+w)},{f(y+r)}V{f(y+h-r)}"
            f"A{f(r)},{f(r)} 0 0 1 {f(x+w-r)},{f(y+h)}H{f(x+r)}A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y+h-r)}"
            f"V{f(y+r)}A{f(r)},{f(r)} 0 0 1 {f(x+r)},{f(y)}Z")

def ellipse(cx, cy, rx, ry):
    return f"M{f(cx-rx)},{f(cy)}A{f(rx)},{f(ry)} 0 1 1 {f(cx+rx)},{f(cy)}A{f(rx)},{f(ry)} 0 1 1 {f(cx-rx)},{f(cy)}Z"

def horn_pair(bxl, bxr, by, h, w):
    # 飞牛式月牙角：根部在内下方，向外鼓出后尖端朝上，矮而宽（与 1.2.x 图标同一画法）
    def one(bx, sgn):
        tx, ty = bx - sgn*w*0.7, by - h
        return (f"M{f(bx)},{f(by)}"
                f"C{f(bx-sgn*w*1.25)},{f(by+0.5)} {f(bx-sgn*w*1.3)},{f(by-h*0.6)} {f(tx)},{f(ty)}"
                f"C{f(bx-sgn*w*0.85)},{f(by-h*0.55)} {f(bx-sgn*w*0.55)},{f(by-h*0.33)} {f(bx)},{f(by-h*0.38)}Z")
    return one(bxl, 1) + one(bxr, -1)

# ---------------------------------------------------------------- geometry（108 画布，安全区半径 33）
TX, TY, TW, TH, TR = 37, 42, 34, 32, 9          # 玻璃方块
TILE = rrect(TX, TY, TW, TH, TR)
SLOT = rrect(TX, 61, TW, 3, 0)                    # 「一」
SLOT_GLOW = rrect(TX + 3, 60.2, TW - 6, 4.6, 2.3)  # 「一」外圈柔光
SUN = ellipse(62.6, 50.6, 2.9, 2.9)
HORNS = horn_pair(36, 72, 46, 12, 8.5)
SHEEN = (f"M{f(TX+TR)},{f(TY+1.2)}H{f(TX+TW-TR)}A{f(TR-1.2)},{f(TR-1.2)} 0 0 1 {f(TX+TW-1.2)},{f(TY+TR)}"
         f"V{f(TY+14)}C{f(TX+24)},{f(TY+11)} {f(TX+11)},{f(TY+16)} {f(TX+1.2)},{f(TY+19)}V{f(TY+TR)}"
         f"A{f(TR-1.2)},{f(TR-1.2)} 0 0 1 {f(TX+TR)},{f(TY+1.2)}Z")
SHADOWS = [ellipse(54, 77.2, 19, 4.2), ellipse(54, 76.6, 15.5, 3.2), ellipse(54, 76.0, 12, 2.3)]

# ---------------------------------------------------------------- paint model
# fill / stroke: "#RRGGBB" | ("#RRGGBB", alpha) | ("lin", x1,y1,x2,y2, [(off, "#RRGGBB", a)]) | ("rad", cx,cy,r, [...])
def argb(c, a=1.0):
    return "#%02X%s" % (round(a * 255), c[1:].upper())

def vd_color_attr(name, paint, ind="    "):
    """Returns (inline attrs, child aapt:attr xml indented under [ind])."""
    if isinstance(paint, str):
        return f'android:{name}="{paint}"', ""
    if paint[0] not in ("lin", "rad"):
        return f'android:{name}="{argb(*paint)}"', ""
    stops = paint[-1]
    items = "\n".join(f'{ind}            <item android:offset="{f(o)}" android:color="{argb(c, a)}"/>' for o, c, a in stops)
    if paint[0] == "lin":
        _, x1, y1, x2, y2, _ = paint
        g = f'android:type="linear" android:startX="{f(x1)}" android:startY="{f(y1)}" android:endX="{f(x2)}" android:endY="{f(y2)}"'
    else:
        _, cx, cy, r, _ = paint
        g = f'android:type="radial" android:centerX="{f(cx)}" android:centerY="{f(cy)}" android:gradientRadius="{f(r)}"'
    return "", (f'{ind}    <aapt:attr name="android:{name}">\n{ind}        <gradient {g}>\n{items}\n'
                f'{ind}        </gradient>\n{ind}    </aapt:attr>\n')

def vd_path(s, indent="    "):
    attrs = [f'android:pathData="{s["d"]}"']
    kids = ""
    if s.get("fill") is not None:
        a, k = vd_color_attr("fillColor", s["fill"], indent); kids += k
        if a: attrs.append(a)
    if s.get("stroke") is not None:
        a, k = vd_color_attr("strokeColor", s["stroke"], indent); kids += k
        if a: attrs.append(a)
        attrs.append(f'android:strokeWidth="{f(s["sw"])}"')
    if s.get("evenodd"): attrs.append('android:fillType="evenOdd"')
    if s.get("name"): attrs.insert(0, f'android:name="{s["name"]}"')
    if not kids:
        return f'{indent}<path ' + ("\n" + indent + "    ").join(attrs) + "/>\n"
    return f'{indent}<path ' + ("\n" + indent + "    ").join(attrs) + ">\n" + kids + f"{indent}</path>\n"

def vd_group(shapes, name=None, pivot=(54, 54), indent="    "):
    head = f'{indent}<group' + (f' android:name="{name}" android:pivotX="{pivot[0]}" android:pivotY="{pivot[1]}"' if name else "") + ">\n"
    return head + "".join(vd_path(s, indent + "    ") for s in shapes) + f"{indent}</group>\n"

VD_HEAD = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<!-- 由 tools/gen_app_icon.py 生成，请改脚本再重新生成 -->\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    xmlns:aapt="http://schemas.android.com/aapt"\n'
           '    android:width="{w}dp" android:height="{w}dp"\n'
           '    android:viewportWidth="108" android:viewportHeight="108">\n')

def vd_doc(body, w=108, name_attr=""):
    head = VD_HEAD.format(w=w)
    if name_attr:
        head = head.replace("<vector ", f"<vector android:name=\"{name_attr}\" ", 1)
    return head + body + "</vector>\n"

# ---------------------------------------------------------------- SVG（只为预览）
_gid = [0]
def svg_paint(p, defs):
    if isinstance(p, str):
        return p, 1.0
    if p[0] not in ("lin", "rad"):
        return p[0], p[1]
    _gid[0] += 1; gid = f"g{_gid[0]}"
    stops = "".join(f'<stop offset="{o}" stop-color="{c}" stop-opacity="{a}"/>' for o, c, a in p[-1])
    if p[0] == "lin":
        _, x1, y1, x2, y2, _ = p
        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>')
    else:
        _, cx, cy, r, _ = p
        defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{stops}</radialGradient>')
    return f"url(#{gid})", 1.0

def svg_shapes(shapes, defs):
    out = []
    for s in shapes:
        a = [f'd="{s["d"]}"']
        if s.get("fill") is not None:
            c, op = svg_paint(s["fill"], defs); a.append(f'fill="{c}" fill-opacity="{op}"')
        else:
            a.append('fill="none"')
        if s.get("evenodd"): a.append('fill-rule="evenodd"')
        if s.get("stroke") is not None:
            c, op = svg_paint(s["stroke"], defs); a.append(f'stroke="{c}" stroke-opacity="{op}" stroke-width="{f(s["sw"])}"')
        out.append(f'<path {" ".join(a)}/>')
    return "\n".join(out)

def svg_doc(*layers):
    defs = []
    body = "\n".join(svg_shapes(l, defs) for l in layers)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">\n'
            f'<defs>{"".join(defs)}</defs>\n{body}\n</svg>\n')

# ---------------------------------------------------------------- layers
W = "#FFFFFF"
BG = [
    dict(d="M0,0h108v108h-108z", fill=("lin", 0, 0, 108, 108, [(0, "#5C9DFF", 1), (0.48, "#3375DE", 1), (1, "#1C3E9E", 1)])),
    dict(d="M0,0h108v108h-108z", fill=("rad", 26, 22, 52, [(0, "#9BE3FF", 0.62), (0.55, "#9BE3FF", 0.16), (1, "#9BE3FF", 0)])),
    dict(d="M0,0h108v108h-108z", fill=("rad", 90, 94, 56, [(0, "#8B5CF6", 0.55), (0.6, "#8B5CF6", 0.14), (1, "#8B5CF6", 0)])),
]
SHADOW = [dict(d=d, fill=("#0A1A4A", a)) for d, a in zip(SHADOWS, (0.06, 0.08, 0.10))]
TILE_L = [
    dict(d=TILE, fill=("lin", 54, TY, 54, TY + TH, [(0, W, 0.56), (0.55, W, 0.28), (1, W, 0.18)])),
    dict(d=SHEEN, fill=("lin", 54, TY, 54, TY + 19, [(0, W, 0.42), (1, W, 0)])),
    dict(d=TILE, stroke=("lin", 54, TY, 54, TY + TH, [(0, W, 0.95), (0.5, W, 0.22), (1, W, 0.45)]), sw=1.2),
]
MARK = [
    dict(d=SLOT_GLOW, fill=(W, 0.28)),
    dict(d=SLOT, fill=(W, 0.96)),
]
SUN_L = [dict(d=SUN, fill=(W, 0.92))]
HORNS_L = [dict(d=HORNS, fill=("lin", 54, 34, 54, 46, [(0, W, 1), (1, "#DCE8FF", 1)]))]
FG = SHADOW + TILE_L + MARK + SUN_L + HORNS_L
# 单色图标（Android 13 主题图标只看 alpha）：与旧版同一轮廓；太阳不画（单色下 48dp 时只是噪点）
MONO = [dict(d=TILE + SLOT, fill=W, evenodd=True), dict(d=HORNS, fill=W)]

def write(rel, text):
    p = os.path.join(RES, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(text); print("wrote", os.path.relpath(p, ROOT))

def main():
    body_bg = "".join(vd_path(s) for s in BG)
    body_fg = "".join(vd_path(s) for s in FG)
    body_mono = "".join(vd_path(s) for s in MONO)
    write("drawable/ic_bg.xml", vd_doc(body_bg))
    write("drawable/ic_fg.xml", vd_doc(body_fg))
    write("drawable/ic_mono.xml", vd_doc(body_mono))

    # Android 8–11 启动窗口：圆角方块版完整图标（windowBackground 的 layer-list 里居中，见 values/themes.xml）
    clip = f'    <clip-path android:pathData="{rrect(18, 18, 72, 72, 19)}"/>\n'
    write("drawable/ic_splash_legacy.xml", vd_doc(clip + body_bg + body_fg))

    # Android 12+ 启动页：没有 iconBackground 时系统按 2/3 圆形遮罩显示——背景渐变 + 前景一起画；前景分组做进场动画
    sv = (vd_group(BG) + vd_group(SHADOW + TILE_L + MARK, name="tile") +
          vd_group(SUN_L, name="sun", pivot=(62.6, 50.6)) + vd_group(HORNS_L, name="horns", pivot=(54, 46)))
    static = vd_doc(sv)
    av = ('<?xml version="1.0" encoding="utf-8"?>\n'
          '<!-- 由 tools/gen_app_icon.py 生成。启动页进场：玻璃方块弹到位，太阳和角随后出现（共 600ms，系统「移除动画」时不播）。 -->\n'
          '<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
          '    xmlns:aapt="http://schemas.android.com/aapt">\n'
          '    <aapt:attr name="android:drawable">\n' +
          "".join("        " + l + "\n" for l in static.split("\n")[2:] if l) +
          '    </aapt:attr>\n' + anim_targets() + '</animated-vector>\n')
    write("drawable-v31/ic_splash.xml", av)

    out = sys.argv[1] if len(sys.argv) > 1 else None
    if out: preview(out)

def anim_targets():
    def target(name, items):
        objs = "\n".join(
            f'                <objectAnimator android:propertyName="{p}" android:valueFrom="{a}" android:valueTo="{b}"\n'
            f'                    android:duration="{d}" android:startOffset="{o}" android:interpolator="{i}"/>'
            for p, a, b, d, o, i in items)
        return (f'    <target android:name="{name}">\n        <aapt:attr name="android:animation">\n'
                f'            <set android:ordering="together">\n{objs}\n            </set>\n        </aapt:attr>\n    </target>\n')
    over = "@android:interpolator/overshoot"
    dec = "@android:interpolator/decelerate_cubic"
    return (target("tile", [("scaleX", 0.86, 1, 520, 0, over), ("scaleY", 0.86, 1, 520, 0, over)]) +
            target("horns", [("scaleX", 0.7, 1, 420, 160, over), ("scaleY", 0.7, 1, 420, 160, over), ("translateY", 5, 0, 420, 160, dec)]) +
            target("sun", [("scaleX", 0, 1, 360, 240, over), ("scaleY", 0, 1, 360, 240, over)]))

# ---------------------------------------------------------------- preview
def preview(out):
    import io
    import cairosvg
    from PIL import Image, ImageDraw
    os.makedirs(out, exist_ok=True)
    def render(svg, px):
        return Image.open(io.BytesIO(cairosvg.svg2png(bytestring=svg.encode(), output_width=px, output_height=px))).convert("RGBA")
    full = svg_doc(BG, FG)
    open(os.path.join(out, "ic_full.svg"), "w").write(full)
    def launcher(size, mask):
        ss = 4; big = size * ss
        img = render(full, round(big * 108 / 72)); o = (img.width - big) // 2
        img = img.crop((o, o, o + big, o + big))
        m = Image.new("L", (big, big), 0); d = ImageDraw.Draw(m)
        if mask == "circle": d.ellipse((0, 0, big - 1, big - 1), fill=255)
        else: d.rounded_rectangle((0, 0, big - 1, big - 1), radius=int(big * 0.26), fill=255)
        r = Image.new("RGBA", (big, big), (0, 0, 0, 0)); r.paste(img, (0, 0), m)
        return r.resize((size, size), Image.LANCZOS)
    def themed(size, bg, fg):
        ss = 4; big = size * ss
        mono = svg_doc([dict(d=TILE + SLOT, fill=W, evenodd=True), dict(d=HORNS, fill=W)])
        img = render(mono, round(big * 108 / 72)); o = (img.width - big) // 2
        a = img.crop((o, o, o + big, o + big)).split()[3]
        t = Image.new("RGBA", (big, big), bg); t.paste(Image.new("RGBA", (big, big), fg), (0, 0), a)
        m = Image.new("L", (big, big), 0); ImageDraw.Draw(m).ellipse((0, 0, big - 1, big - 1), fill=255)
        r = Image.new("RGBA", (big, big), (0, 0, 0, 0)); r.paste(t, (0, 0), m)
        return r.resize((size, size), Image.LANCZOS)
    sizes = [192, 96, 48]
    rows = [("circle", lambda s: launcher(s, "circle"), (255, 255, 255)), ("square", lambda s: launcher(s, "square"), (238, 242, 248)),
            ("dark", lambda s: launcher(s, "square"), (11, 14, 21)),
            ("mono-light", lambda s: themed(s, (219, 232, 254, 255), (30, 58, 138, 255)), (255, 255, 255)),
            ("mono-dark", lambda s: themed(s, (30, 36, 48, 255), (191, 219, 254, 255)), (18, 20, 26))]
    pad = 24; Wd = pad + sum(s + pad for s in sizes); H = len(rows) * (192 + pad) + pad
    sheet = Image.new("RGBA", (Wd, H), (255, 255, 255, 255)); dr = ImageDraw.Draw(sheet)
    for i, (name, fn, bgc) in enumerate(rows):
        y = pad + i * (192 + pad)
        dr.rectangle((0, y - pad // 2, Wd, y + 192 + pad // 2), fill=bgc + (255,))
        x = pad
        for s in sizes:
            sheet.alpha_composite(fn(s), (x, y + (192 - s) // 2)); x += s + pad
    sheet.convert("RGB").save(os.path.join(out, "preview.png"))
    render(full, 512).save(os.path.join(out, "full_512.png"))
    print("preview:", os.path.join(out, "preview.png"))

if __name__ == "__main__":
    main()
