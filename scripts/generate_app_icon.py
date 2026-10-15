"""生成双端应用图标：安卓自适应图标矢量（背景 / 前景 / 单色）、旧版回退位图与 iOS 1024 px 不透明图标。

图形是吉祥物「小瞳」：白色团子头 + 豆豆眼 + 腮红微笑 + 头顶双叶嫩芽，几何与 core:ui EyeBrand.kt 的
drawBrandMark 相同（108 单位视口），在图标里放大 1.1 倍并下移 2 单位，整体仍在自适应图标的安全区
（中心半径 33）内。底色为两色渐变加左上柔光，头像下方有一层淡淡的投影增加立体感。
用法：
  python scripts/generate_app_icon.py [配色名]          写入工程（默认配色见 DEFAULT_PALETTE）
  python scripts/generate_app_icon.py --preview 目录     只输出全部配色预览，不改动工程文件
"""
from pathlib import Path
import math
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
IOS_ICON = ROOT / 'iosApp/Sources/Assets.xcassets/AppIcon.appiconset/AppIcon.png'

# 配色：底色渐变起止、投影色、微笑色、腮红色。
PALETTES = {
    'mint-sky': dict(bg=((0x3E, 0xD3, 0xC0), (0x3A, 0x7B, 0xF2)), shadow=(0x1B, 0x4F, 0x9E), smile=(0x12, 0xA3, 0x9A), blush=(0xFF, 0x9E, 0x7A)),
    'fresh-mint': dict(bg=((0x5A, 0xE0, 0xC6), (0x0E, 0x93, 0x9A)), shadow=(0x0A, 0x5E, 0x63), smile=(0x0E, 0x93, 0x9A), blush=(0xFF, 0x9E, 0x7A)),
    'brand-bright': dict(bg=((0x1F, 0xC0, 0xB0), (0x3D, 0x6E, 0xF0)), shadow=(0x1E, 0x3F, 0x9E), smile=(0x12, 0xA3, 0x9A), blush=(0xF2, 0xA3, 0x3A)),
    'sunrise': dict(bg=((0xFF, 0xC2, 0x7A), (0xFF, 0x7E, 0x8A)), shadow=(0xB8, 0x4A, 0x52), smile=(0xE8, 0x6A, 0x5A), blush=(0xFF, 0x8A, 0x80)),
}
DEFAULT_PALETTE = 'mint-sky'
INK = (0x13, 0x20, 0x1F)

# 小瞳几何（108 单位，与 drawBrandMark 一致）及图标内的放大与下移。
SCALE, PIVOT, SHIFT_Y = 1.1, (54.0, 52.0), 2.0
HEAD = (29.0, 38.0, 79.0, 80.0, 20.0)  # 左、上、右、下、圆角
EYES = [(44.5, 58.0), (63.5, 58.0)]
EYE_R = (2.75, 3.75)
BLUSH = [(38.5, 64.5), (69.5, 64.5)]
SMILE = ((49.0, 64.5), (54.0, 68.5), (59.0, 64.5))
STEM = ((54.0, 38.0), (54.5, 33.0), (55.0, 29.0))
LEAVES = [((50.0, 28.0), 4.5, 2.4, 25.0), ((59.5, 26.5), 5.0, 2.6, -30.0)]
SHADOW_DY = 2.5


def place(x, y):
    """头像坐标 → 图标坐标（与矢量 group 的缩放、平移一致）。"""
    return PIVOT[0] + (x - PIVOT[0]) * SCALE, PIVOT[1] + (y - PIVOT[1]) * SCALE + SHIFT_Y


def quad(p0, p1, p2, n=32):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in np.linspace(0, 1, n)]


def ellipse(c, rx, ry, deg=0.0, n=96):
    a = math.radians(deg)
    return [(c[0] + rx * math.cos(t) * math.cos(a) - ry * math.sin(t) * math.sin(a),
             c[1] + rx * math.cos(t) * math.sin(a) + ry * math.sin(t) * math.cos(a)) for t in np.linspace(0, 2 * math.pi, n)]


def head_outline(dy=0.0, n=24):
    l, t, r, b, rad = HEAD
    pts = []
    for cx, cy, a0 in ((r - rad, t + rad, -90), (r - rad, b - rad, 0), (l + rad, b - rad, 90), (l + rad, t + rad, 180)):
        pts += [(cx + rad * math.cos(math.radians(a0 + 90 * i / n)), cy + dy + rad * math.sin(math.radians(a0 + 90 * i / n)))
                for i in range(n + 1)]
    return pts


def render(size, palette, oversample=4):
    s = size * oversample
    k = s / 108.0
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32) / k
    start, end = palette['bg']
    t = np.clip((xx + yy) / 216.0, 0, 1)[..., None]
    img = np.array(start, np.float32) * (1 - t) + np.array(end, np.float32) * t
    glow = np.clip(1 - np.hypot(xx - 30, yy - 22) / 70.0, 0, 1) ** 2 * 0.22
    img = img * (1 - glow[..., None]) + 255 * glow[..., None]

    def px(points):
        return [(x * k, y * k) for x, y in (place(*p) for p in points)]

    def mask(draw_fn):
        m = Image.new('L', (s, s), 0)
        draw_fn(ImageDraw.Draw(m))
        return np.asarray(m, dtype=np.float32) / 255.0

    def paint(color, alpha):
        nonlocal img
        a = alpha[..., None]
        img = img * (1 - a) + np.asarray(color, np.float32) * a

    def poly(points):
        return mask(lambda d: d.polygon(px(points), fill=255))

    def stroke(points, width):
        w = max(1, round(width * SCALE * k))
        def draw(d):
            pts = px(points)
            d.line(pts, fill=255, width=w, joint='curve')
            for x, y in (pts[0], pts[-1]):  # 圆头
                d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=255)
        return mask(draw)

    white = (255, 255, 255)
    paint(palette['shadow'], poly(head_outline(SHADOW_DY)) * 0.25)
    paint(white, stroke(quad(*STEM), 2.2))
    for c, rx, ry, deg in LEAVES:
        paint(white, poly(ellipse(c, rx, ry, deg)))
    paint(white, poly(head_outline()))
    for x, y in EYES:
        paint(INK, poly(ellipse((x, y), *EYE_R)))
        paint(white, poly(ellipse((x + 1.1, 56.25), 1.25, 1.25)))
    for c in BLUSH:
        paint(palette['blush'], poly(ellipse(c, 2.6, 1.6)) * 0.75)
    paint(palette['smile'], stroke(quad(*SMILE), 1.8))
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')
    return out.resize((size, size), Image.LANCZOS)


def hexa(rgb, alpha=255):
    return '#%02X%02X%02X%02X' % ((alpha,) + tuple(rgb))


def oval_path(c, rx, ry):
    return f'M{c[0] - rx:g},{c[1]:g} a{rx:g},{ry:g} 0 1,0 {2 * rx:g},0 a{rx:g},{ry:g} 0 1,0 {-2 * rx:g},0'


def head_path(dy=0.0):
    l, t, r, b, rad = HEAD
    return (f'M{l + rad:g},{t + dy:g} H{r - rad:g} A{rad:g},{rad:g} 0 0 1 {r:g},{t + rad + dy:g} V{b - rad + dy:g} '
            f'A{rad:g},{rad:g} 0 0 1 {r - rad:g},{b + dy:g} H{l + rad:g} A{rad:g},{rad:g} 0 0 1 {l:g},{b - rad + dy:g} '
            f'V{t + rad + dy:g} A{rad:g},{rad:g} 0 0 1 {l + rad:g},{t + dy:g} Z')


def write_vectors(palette, name):
    def head(comment, aapt=False):
        ns = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ''
        return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
                '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n')
    start, end = palette['bg']
    background = head(f'由 scripts/generate_app_icon.py 生成（配色 {name}）：两色渐变底 + 左上柔光', aapt=True) + (
        '    <path android:pathData="M0,0h108v108h-108z">\n        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">\n'
        f'                <item android:offset="0" android:color="{hexa(start)}" />\n                <item android:offset="1" android:color="{hexa(end)}" />\n'
        '            </gradient>\n        </aapt:attr>\n    </path>\n'
        '    <path android:pathData="M0,0h108v108h-108z">\n        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="radial" android:centerX="30" android:centerY="22" android:gradientRadius="70">\n'
        '                <item android:offset="0" android:color="#38FFFFFF" />\n                <item android:offset="1" android:color="#00FFFFFF" />\n'
        '            </gradient>\n        </aapt:attr>\n    </path>\n</vector>\n')
    group = (f'    <group android:pivotX="{PIVOT[0]:g}" android:pivotY="{PIVOT[1]:g}" android:scaleX="{SCALE:g}" '
             f'android:scaleY="{SCALE:g}" android:translateY="{SHIFT_Y:g}">\n')
    (sx, sy), (cx, cy), (ex, ey) = STEM
    stem = (f'        <path android:pathData="M{sx:g},{sy:g} Q{cx:g},{cy:g} {ex:g},{ey:g}" android:strokeColor="#FFFFFFFF" '
            'android:strokeWidth="2.2" android:strokeLineCap="round" android:fillColor="#00000000" />\n')
    leaves = ''.join(f'        <group android:pivotX="{c[0]:g}" android:pivotY="{c[1]:g}" android:rotation="{deg:g}">\n'
                     f'            <path android:pathData="{oval_path(c, rx, ry)}" android:fillColor="#FFFFFFFF" />\n        </group>\n'
                     for c, rx, ry, deg in LEAVES)
    (ax, ay), (bx, by), (zx, zy) = SMILE
    smile_path = f'M{ax:g},{ay:g} Q{bx:g},{by:g} {zx:g},{zy:g}'
    foreground = head('由 scripts/generate_app_icon.py 生成：吉祥物「小瞳」（几何同 EyeBrand.drawBrandMark，放大 1.1 倍），全部位于 66dp 安全区内') + group + (
        f'        <path android:pathData="{head_path(SHADOW_DY)}" android:fillColor="{hexa(palette["shadow"], 0x40)}" />\n'
        + stem + leaves +
        f'        <path android:pathData="{head_path()}" android:fillColor="#FFFFFFFF" />\n'
        + ''.join(f'        <path android:pathData="{oval_path((x, y), *EYE_R)}" android:fillColor="{hexa(INK)}" />\n'
                  f'        <path android:pathData="{oval_path((x + 1.1, 56.25), 1.25, 1.25)}" android:fillColor="#FFFFFFFF" />\n' for x, y in EYES)
        + ''.join(f'        <path android:pathData="{oval_path(c, 2.6, 1.6)}" android:fillColor="{hexa(palette["blush"], 0xBF)}" />\n' for c in BLUSH)
        + f'        <path android:pathData="{smile_path}" android:strokeColor="{hexa(palette["smile"])}" android:strokeWidth="1.8" '
          'android:strokeLineCap="round" android:fillColor="#00000000" />\n    </group>\n</vector>\n')
    monochrome = head('由 scripts/generate_app_icon.py 生成：主题图标（Android 13+ 单色），小瞳线稿 + 豆豆眼 + 嫩芽，系统负责着色') + group + (
        stem + leaves +
        f'        <path android:pathData="{head_path()}" android:strokeColor="#FFFFFFFF" android:strokeWidth="3.4" android:fillColor="#00000000" />\n'
        + ''.join(f'        <path android:pathData="{oval_path((x, y), *EYE_R)}" android:fillColor="#FFFFFFFF" />\n' for x, y in EYES)
        + f'        <path android:pathData="{smile_path}" android:strokeColor="#FFFFFFFF" android:strokeWidth="2" '
          'android:strokeLineCap="round" android:fillColor="#00000000" />\n    </group>\n</vector>\n')
    (RES / 'drawable/ic_launcher_background.xml').write_text(background, encoding='utf-8')
    (RES / 'drawable/ic_launcher_foreground.xml').write_text(foreground, encoding='utf-8')
    (RES / 'drawable/ic_launcher_monochrome.xml').write_text(monochrome, encoding='utf-8')


def ios_icon(palette):
    # iOS 直接用整张画布（没有自适应图标的安全区裁切），放大到中心 86 单位，让头像占比与安卓接近。
    zoom = render(round(1024 * 108 / 86), palette)
    offset = (zoom.width - 1024) // 2
    return zoom.crop((offset, offset, offset + 1024, offset + 1024))


def write_bitmaps(palette):
    ios_icon(palette).save(IOS_ICON, optimize=True)
    # 旧版启动器回退位图（minSdk 26 实际走自适应矢量）：取安全区内的可见部分，圆角方形与圆形各一套。
    for folder, size in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
        full = render(round(size * 108 / 72) * 2, palette)
        inset = round(full.width * 18 / 108)
        base = full.crop((inset, inset, full.width - inset, full.width - inset)).resize((size, size), Image.LANCZOS)
        for name, shape in (('ic_launcher', 'rounded'), ('ic_launcher_round', 'circle')):
            m = Image.new('L', (size * 4, size * 4), 0)
            d = ImageDraw.Draw(m)
            if shape == 'circle': d.ellipse([0, 0, size * 4 - 1, size * 4 - 1], fill=255)
            else: d.rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=size * 4 * 0.22, fill=255)
            icon = base.convert('RGBA'); icon.putalpha(m.resize((size, size), Image.LANCZOS))
            icon.save(RES / f'mipmap-{folder}/{name}.webp', 'WEBP', lossless=True)


if __name__ == '__main__':
    if len(sys.argv) > 2 and sys.argv[1] == '--preview':
        out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
        for key, palette in PALETTES.items():
            render(1024, palette).save(out / f'{key}-android.png')
            ios_icon(palette).save(out / f'{key}-ios.png')
    else:
        name = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_PALETTE
        write_vectors(PALETTES[name], name)
        write_bitmaps(PALETTES[name])
