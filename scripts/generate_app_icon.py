"""生成双端应用图标：安卓自适应图标矢量（背景 / 前景 / 单色）、旧版回退位图与 iOS 1024 px 不透明图标。

图形是彩色吉祥物「小瞳」：渐变团子头、顶部柔光、双叶嫩芽、豆豆眼高光、腮红与微笑，
几何与 core:ui EyeBrand.kt 的 drawMascot 完全相同（以头宽 s 为单位），放在浅薄荷绿底（primarySoft #E3F4F2）上。
在 108 单位视口中头宽 54、头部中心 (54, 57)，整体落在自适应图标的安全区（中心半径 33）内。
用法：
  python scripts/generate_app_icon.py             写入工程
  python scripts/generate_app_icon.py --preview 目录   只输出预览，不改动工程文件
"""
from pathlib import Path
import math
import sys

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
IOS_ICON = ROOT / 'iosApp/Sources/Assets.xcassets/AppIcon.appiconset/AppIcon.png'

# 设计令牌（core/designsystem 亮色）
BACKGROUND = (0xE3, 0xF4, 0xF2)   # primarySoft
BRAND_START = (0x12, 0xA3, 0x9A)
BRAND_END = (0x2A, 0x5B, 0xD7)
ACCENT = (0xF2, 0xA3, 0x3A)
INK = (0x13, 0x20, 0x1F)
WHITE = (0xFF, 0xFF, 0xFF)

# drawMascot 几何：头宽 S、头部中心 C（108 单位）
S = 54.0
C = (54.0, 57.0)
TOP = C[1] - S * 0.42
HEAD = (C[0] - S / 2, TOP, C[0] + S / 2, TOP + S * 0.84, S * 0.4)   # 左、上、右、下、圆角
STEM = ((C[0], TOP + S * 0.02), (C[0] + S * 0.005, TOP - S * 0.05), (C[0] + S * 0.015, TOP - S * 0.1))
STEM_W = S * 0.035
LEAVES = [((C[0] - S * 0.06, TOP - S * 0.11), S * 0.09, S * 0.045, 25.0),
          ((C[0] + S * 0.09, TOP - S * 0.14), S * 0.10, S * 0.05, -30.0)]
SHINE = ((C[0] - S * 0.2, TOP + S * 0.14), S * 0.13, S * 0.05, -18.0)
EYES = [(C[0] + side * S * 0.19, C[1] - S * 0.02) for side in (-1, 1)]
EYE_R = (S * 0.052, S * 0.072)
GLINT_R = S * 0.02
BLUSH = [(C[0] + side * S * 0.31, C[1] + S * 0.11) for side in (-1, 1)]
BLUSH_R = (S * 0.055, S * 0.032)
SMILE = ((C[0] - S * 0.05, C[1] + S * 0.1), (C[0], C[1] + S * 0.15), (C[0] + S * 0.05, C[1] + S * 0.1))
SMILE_W = S * 0.024


def glint(eye):
    return eye[0] + EYE_R[0] * 0.4, eye[1] - EYE_R[1] * 0.45


def quad(p0, p1, p2, n=32):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1]) for t in np.linspace(0, 1, n)]


def ellipse(c, rx, ry, deg=0.0, n=96):
    a = math.radians(deg)
    return [(c[0] + rx * math.cos(t) * math.cos(a) - ry * math.sin(t) * math.sin(a),
             c[1] + rx * math.cos(t) * math.sin(a) + ry * math.sin(t) * math.cos(a)) for t in np.linspace(0, 2 * math.pi, n)]


def head_outline(n=24):
    l, t, r, b, rad = HEAD
    pts = []
    for cx, cy, a0 in ((r - rad, t + rad, -90), (r - rad, b - rad, 0), (l + rad, b - rad, 90), (l + rad, t + rad, 180)):
        pts += [(cx + rad * math.cos(math.radians(a0 + 90 * i / n)), cy + rad * math.sin(math.radians(a0 + 90 * i / n)))
                for i in range(n + 1)]
    return pts


def render(size, zoom=1.0, oversample=4):
    """整张 108 单位画布；zoom > 1 时以 (54, 54) 为中心放大（iOS 无安全区裁切时使用）。"""
    s = size * oversample
    k = s / 108.0 * zoom
    offset = 54 - 54 / zoom
    yy, xx = np.mgrid[0:s, 0:s].astype(np.float32) / k + offset
    img = np.empty((s, s, 3), np.float32)
    img[:] = BACKGROUND

    def px(points):
        return [((x - offset) * k, (y - offset) * k) for x, y in points]

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
        w = max(1, round(width * k))
        def draw(d):
            # 沿曲线密集铺圆点，得到粗细均匀、两端圆头的描边（直接画折线在细分处会出现条纹）。
            pts = px(points)
            for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
                steps = max(1, int(math.hypot(x1 - x0, y1 - y0) / max(1, w / 6)))
                for i in range(steps + 1):
                    x, y = x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps
                    d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=255)
        return mask(draw)

    paint(BRAND_START, stroke(quad(*STEM), STEM_W))
    for c, rx, ry, deg in LEAVES:
        paint(BRAND_START, poly(ellipse(c, rx, ry, deg)))
    # 团子头：左上 → 右下的品牌渐变
    l, t, r, b, _ = HEAD
    grad = np.clip(((xx - l) * (r - l) + (yy - t) * (b - t)) / ((r - l) ** 2 + (b - t) ** 2), 0, 1)[..., None]
    head_color = np.array(BRAND_START, np.float32) * (1 - grad) + np.array(BRAND_END, np.float32) * grad
    head_alpha = poly(head_outline())[..., None]
    img = img * (1 - head_alpha) + head_color * head_alpha
    paint(WHITE, poly(ellipse(*SHINE)) * 0.16)
    for eye in EYES:
        paint(INK, poly(ellipse(eye, *EYE_R)))
        paint(WHITE, poly(ellipse(glint(eye), GLINT_R, GLINT_R)))
    for c in BLUSH:
        paint(ACCENT, poly(ellipse(c, *BLUSH_R)) * 0.75)
    paint(INK, stroke(quad(*SMILE), SMILE_W))
    out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), 'RGB')
    return out.resize((size, size), Image.LANCZOS)


def hexa(rgb, alpha=255):
    return '#%02X%02X%02X%02X' % ((alpha,) + tuple(rgb))


def oval_path(c, rx, ry):
    return f'M{c[0] - rx:.2f},{c[1]:.2f} a{rx:.2f},{ry:.2f} 0 1,0 {2 * rx:.2f},0 a{rx:.2f},{ry:.2f} 0 1,0 {-2 * rx:.2f},0'


def head_path():
    l, t, r, b, rad = HEAD
    return (f'M{l + rad:.2f},{t:.2f} H{r - rad:.2f} A{rad:.2f},{rad:.2f} 0 0 1 {r:.2f},{t + rad:.2f} V{b - rad:.2f} '
            f'A{rad:.2f},{rad:.2f} 0 0 1 {r - rad:.2f},{b:.2f} H{l + rad:.2f} A{rad:.2f},{rad:.2f} 0 0 1 {l:.2f},{b - rad:.2f} '
            f'V{t + rad:.2f} A{rad:.2f},{rad:.2f} 0 0 1 {l + rad:.2f},{t:.2f} Z')


def quad_path(points):
    (ax, ay), (bx, by), (zx, zy) = points
    return f'M{ax:.2f},{ay:.2f} Q{bx:.2f},{by:.2f} {zx:.2f},{zy:.2f}'


def rotated(c, deg, body):
    return (f'    <group android:pivotX="{c[0]:.2f}" android:pivotY="{c[1]:.2f}" android:rotation="{deg:g}">\n'
            f'{body}    </group>\n')


def write_vectors():
    def head(comment, aapt=False):
        ns = '\n    xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ''
        return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- {comment} -->\n<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
                '    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n')
    background = head('由 scripts/generate_app_icon.py 生成：浅薄荷绿底（primarySoft）') + (
        f'    <path android:pathData="M0,0h108v108h-108z" android:fillColor="{hexa(BACKGROUND)}" />\n</vector>\n')
    l, t, r, b, _ = HEAD
    leaves = ''.join(rotated(c, deg, f'        <path android:pathData="{oval_path(c, rx, ry)}" android:fillColor="{hexa(BRAND_START)}" />\n')
                     for c, rx, ry, deg in LEAVES)
    stem = (f'    <path android:pathData="{quad_path(STEM)}" android:strokeColor="{hexa(BRAND_START)}" '
            f'android:strokeWidth="{STEM_W:.2f}" android:strokeLineCap="round" android:fillColor="#00000000" />\n')
    foreground = head('由 scripts/generate_app_icon.py 生成：彩色吉祥物「小瞳」（几何同 EyeBrand.drawMascot），全部位于 66dp 安全区内', aapt=True) + (
        stem + leaves +
        f'    <path android:pathData="{head_path()}">\n        <aapt:attr name="android:fillColor">\n'
        f'            <gradient android:type="linear" android:startX="{l:.2f}" android:startY="{t:.2f}" android:endX="{r:.2f}" android:endY="{b:.2f}">\n'
        f'                <item android:offset="0" android:color="{hexa(BRAND_START)}" />\n'
        f'                <item android:offset="1" android:color="{hexa(BRAND_END)}" />\n'
        '            </gradient>\n        </aapt:attr>\n    </path>\n'
        + rotated(SHINE[0], SHINE[3], f'        <path android:pathData="{oval_path(SHINE[0], SHINE[1], SHINE[2])}" android:fillColor="#29FFFFFF" />\n')
        + ''.join(f'    <path android:pathData="{oval_path(eye, *EYE_R)}" android:fillColor="{hexa(INK)}" />\n'
                  f'    <path android:pathData="{oval_path(glint(eye), GLINT_R, GLINT_R)}" android:fillColor="#FFFFFFFF" />\n' for eye in EYES)
        + ''.join(f'    <path android:pathData="{oval_path(c, *BLUSH_R)}" android:fillColor="{hexa(ACCENT, 0xBF)}" />\n' for c in BLUSH)
        + f'    <path android:pathData="{quad_path(SMILE)}" android:strokeColor="{hexa(INK)}" android:strokeWidth="{SMILE_W:.2f}" '
          'android:strokeLineCap="round" android:fillColor="#00000000" />\n</vector>\n')
    monochrome = head('由 scripts/generate_app_icon.py 生成：主题图标（Android 13+ 单色），小瞳线稿 + 豆豆眼 + 嫩芽，系统负责着色') + (
        stem.replace(hexa(BRAND_START), '#FFFFFFFF')
        + ''.join(rotated(c, deg, f'        <path android:pathData="{oval_path(c, rx, ry)}" android:fillColor="#FFFFFFFF" />\n')
                  for c, rx, ry, deg in LEAVES)
        + f'    <path android:pathData="{head_path()}" android:strokeColor="#FFFFFFFF" android:strokeWidth="3.4" android:fillColor="#00000000" />\n'
        + ''.join(f'    <path android:pathData="{oval_path(eye, *EYE_R)}" android:fillColor="#FFFFFFFF" />\n' for eye in EYES)
        + f'    <path android:pathData="{quad_path(SMILE)}" android:strokeColor="#FFFFFFFF" android:strokeWidth="2" '
          'android:strokeLineCap="round" android:fillColor="#00000000" />\n</vector>\n')
    (RES / 'drawable/ic_launcher_background.xml').write_text(background, encoding='utf-8')
    (RES / 'drawable/ic_launcher_foreground.xml').write_text(foreground, encoding='utf-8')
    (RES / 'drawable/ic_launcher_monochrome.xml').write_text(monochrome, encoding='utf-8')


def ios_icon():
    # iOS 没有自适应图标的安全区裁切：放大到中心约 80 单位，让小瞳占比与参考图一致。
    return render(1024, zoom=108 / 80)


def write_bitmaps():
    ios_icon().save(IOS_ICON, optimize=True)
    # 旧版启动器回退位图（minSdk 26 实际走自适应矢量）：取安全区内的可见部分，圆角方形与圆形各一套。
    for folder, size in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
        full = render(round(size * 108 / 72) * 2)
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
        render(1024).save(out / 'android-adaptive-full.png')
        ios_icon().save(out / 'ios.png')
    else:
        write_vectors()
        write_bitmaps()
