"""生成双端应用图标：爱尔眼科数字人「爱科 Eyecho」头像（取代 generate_app_icon.py 的小瞳图标）。

输入：scripts/assets/eyecho_icon_source.png（真人数字人照片 docs/素材/Aike.png 抠绿幕后裁出的头到胸口，
见 generate_eyecho_portrait.py；下沿在胸前「EYE」标志之上）。
构图仿豆包图标：白底，中间一个浅灰蓝圆盘（#C5D0E2，低饱和），头肩放在圆里，
肩膀被圆的下沿裁掉，头顶稍稍探出圆外，显得立体。
输出：
  - 安卓自适应图标前景位图 mipmap-*/ic_launcher_foreground.png（108dp 画布，圆盘直径 58 居中，探出的头顶仍在可见区内）；
    背景 drawable/ic_launcher_background.xml 为白色；
    旧版回退位图 mipmap-*/ic_launcher(_round).webp（minSdk 26 实际用不到，保持一致）。
  - iOS 1024 px 不透明图标（同一构图）。
  - 应用内展示用的同款 512 px 图标（安卓 drawable-nodpi/eyecho_app_icon.png、iOS EyechoAppIcon）。
用法：
  python scripts/generate_eyecho_icon.py              写入工程
  python scripts/generate_eyecho_icon.py --preview 目录  只输出预览（圆形 / 圆角方形遮罩），不改动工程文件
"""
from pathlib import Path
import sys

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
IOS_ICON = ROOT / 'iosApp/Sources/Assets.xcassets/AppIcon.appiconset/AppIcon.png'
# 应用内「关于」处展示的同款图标（安卓 EyeAppIcon / iOS AppIconView），512 px
IN_APP_ANDROID = ROOT / 'core/designsystem/src/main/res/drawable-nodpi/eyecho_app_icon.png'
IN_APP_IOS = ROOT / 'iosApp/Sources/Assets.xcassets/EyechoAppIcon.imageset/eyecho_app_icon.png'
SOURCE = ROOT / 'scripts/assets/eyecho_icon_source.png'
BACKGROUND = (0xFF, 0xFF, 0xFF, 255)
DISC = (0xC5, 0xD0, 0xE2, 255)  # 浅灰蓝（低饱和），白大褂与圆盘仍看得出边界
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

# 头像相对圆盘直径 D：宽 AV_SCALE·D，底边比圆盘下沿再低 AV_DROP·D（肩膀被裁），于是头顶探出圆外约 (AV_SCALE−1−AV_DROP)·D
AV_SCALE, AV_DROP = 0.96, 0.03
# 安卓 108 单位画布：可见区是中间 72（18–90），圆盘直径 58、圆心略低于中心，探出的头顶约到 21
FG_DISC, FG_CENTER_Y = 58.0, 56.0
# iOS：圆盘直径占 78%，圆心略低于中心
IOS_DISC, IOS_CENTER_Y, IOS_SIZE = 0.78, 0.53, 1024


def badge(canvas: Image.Image, src: Image.Image, diameter: float, cy: float) -> None:
    """在 canvas 上画圆盘与头像：圆盘下半只露圆内的部分，上半允许头发探出。"""
    cx, r = canvas.width / 2, diameter / 2
    ImageDraw.Draw(canvas).ellipse((cx - r, cy - r, cx + r, cy + r), fill=DISC)
    w = round(diameter * AV_SCALE)
    head = src.resize((w, w * src.height // src.width), Image.LANCZOS)
    layer = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    layer.alpha_composite(head, (round(cx - w / 2), round(cy + r + diameter * AV_DROP) - head.height))
    clip = Image.new('L', canvas.size, 0)
    d = ImageDraw.Draw(clip)
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=255)
    d.rectangle((0, 0, canvas.width, cy), fill=255)
    layer.putalpha(Image.composite(layer.getchannel('A'), Image.new('L', canvas.size, 0), clip))
    canvas.alpha_composite(layer)


def foreground(px: int, src: Image.Image) -> Image.Image:
    k = px / 108
    canvas = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    badge(canvas, src, FG_DISC * k, FG_CENTER_Y * k)
    return canvas


def flattened(px: int, src: Image.Image) -> Image.Image:
    """背景 + 前景叠好，再裁掉自适应图标被遮住的外圈（旧版回退位图用）。"""
    big = Image.new('RGBA', (px * 108 // 72, px * 108 // 72), BACKGROUND)
    big.alpha_composite(foreground(big.width, src))
    m = (big.width - px) // 2
    return big.crop((m, m, m + px, m + px))


def ios(src: Image.Image) -> Image.Image:
    canvas = Image.new('RGBA', (IOS_SIZE, IOS_SIZE), BACKGROUND)
    badge(canvas, src, IOS_SIZE * IOS_DISC, IOS_SIZE * IOS_CENTER_Y)
    return canvas.convert('RGB')


def masked(img: Image.Image, radius: float) -> Image.Image:
    mask = Image.new('L', img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius=radius * img.width, fill=255)
    out = Image.new('RGBA', img.size, (255, 255, 255, 0))
    out.paste(img.convert('RGBA'), (0, 0), mask)
    return out


def main() -> None:
    src = Image.open(SOURCE).convert('RGBA')
    if len(sys.argv) > 2 and sys.argv[1] == '--preview':
        out = Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
        icon = flattened(432, src)
        sheet = Image.new('RGBA', (432 * 3 + 80, 432), (40, 44, 52, 255))
        sheet.alpha_composite(masked(icon, 0.5), (0, 0))
        sheet.alpha_composite(masked(icon, 0.22), (432 + 40, 0))
        sheet.alpha_composite(masked(ios(src).resize((432, 432), Image.LANCZOS), 0.2237), (864 + 80, 0))
        sheet.save(out / 'icon_preview.png')
        return
    for name, k in DENSITIES.items():
        d = RES / f'mipmap-{name}'
        foreground(round(108 * k), src).save(d / 'ic_launcher_foreground.png', optimize=True)
        legacy = flattened(round(48 * k), src)
        legacy.save(d / 'ic_launcher.webp', lossless=True)
        masked(legacy, 0.5).save(d / 'ic_launcher_round.webp', lossless=True)
    ios(src).save(IOS_ICON, optimize=True)
    in_app = ios(src).resize((512, 512), Image.LANCZOS)
    in_app.save(IN_APP_ANDROID, optimize=True)
    IN_APP_IOS.parent.mkdir(parents=True, exist_ok=True)
    in_app.save(IN_APP_IOS, optimize=True)


if __name__ == '__main__':
    main()
