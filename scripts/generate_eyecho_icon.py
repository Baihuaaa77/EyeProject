"""生成双端应用图标：爱尔眼科数字人「爱科 Eyecho」头像（取代 generate_app_icon.py 的小瞳图标）。

输入：scripts/assets/eyecho_icon_source.png（真人数字人照片 docs/素材/Aike.png 抠绿幕后裁出的头到胸口，
见 generate_eyecho_portrait.py；下沿在胸前「EYE」标志之上）。
采用 App 问诊卡的纯深棕底，保留爱科头肩与白大褂原色。
输出：
  - 安卓自适应图标前景位图 mipmap-*/ic_launcher_foreground.png（108dp 画布，头部位于平台遮罩安全区内）；
    背景 drawable/ic_launcher_background.xml 读取暖棕令牌；
    旧版回退位图 mipmap-*/ic_launcher(_round).webp（minSdk 26 实际用不到，保持一致）。
  - iOS 1024 px 不透明图标（同一构图）。
  - 应用内展示用的同款 512 px 图标（安卓 drawable-nodpi/eyecho_app_icon.png、iOS EyechoAppIcon）。
用法：
  python scripts/generate_eyecho_icon.py              写入工程
  python scripts/generate_eyecho_icon.py --preview 目录  只输出预览（圆形 / 圆角方形遮罩），不改动工程文件
"""
from pathlib import Path
import sys
from xml.etree import ElementTree

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
IOS_ICON = ROOT / 'iosApp/Sources/Assets.xcassets/AppIcon.appiconset/AppIcon.png'
# 应用内「关于」处展示的同款图标（安卓 EyeAppIcon / iOS AppIconView），512 px
IN_APP_ANDROID = ROOT / 'core/designsystem/src/main/res/drawable-nodpi/eyecho_app_icon.png'
IN_APP_IOS = ROOT / 'iosApp/Sources/Assets.xcassets/EyechoAppIcon.imageset/eyecho_app_icon.png'
SOURCE = ROOT / 'scripts/assets/eyecho_icon_source.png'
PALETTE = ElementTree.parse(ROOT / 'core/designsystem/src/main/res/values/colors.xml').getroot()


def color(name: str) -> tuple[int, int, int, int]:
    value = PALETTE.findtext(f"color[@name='{name}']")
    if value is None:
        raise ValueError(f"缺少图标颜色令牌 {name}")
    alpha, red, green, blue = bytes.fromhex(value.removeprefix('#'))
    return red, green, blue, alpha


BACKGROUND = color('eye_icon_background')
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
IOS_SIZE = 1024
PORTRAIT_WIDTH = 0.92
PORTRAIT_BOTTOM = 1.02


def portrait(canvas: Image.Image, src: Image.Image, width: float, bottom: float) -> None:
    w = round(width)
    head = src.resize((w, round(w * src.height / src.width)), Image.LANCZOS)
    canvas.alpha_composite(head, ((canvas.width - w) // 2, round(bottom) - head.height))


def foreground(px: int, src: Image.Image) -> Image.Image:
    k = px / 108
    canvas = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    # 肩部延伸过系统遮罩下沿，头顶留在自适应图标安全区。
    portrait(canvas, src, 72 * PORTRAIT_WIDTH * k, (18 + 72 * PORTRAIT_BOTTOM) * k)
    return canvas


def flattened(px: int, src: Image.Image) -> Image.Image:
    """背景 + 前景叠好，再裁掉自适应图标被遮住的外圈（旧版回退位图用）。"""
    big = Image.new('RGBA', (px * 108 // 72, px * 108 // 72), BACKGROUND)
    big.alpha_composite(foreground(big.width, src))
    m = (big.width - px) // 2
    return big.crop((m, m, m + px, m + px))


def ios(src: Image.Image) -> Image.Image:
    canvas = Image.new('RGBA', (IOS_SIZE, IOS_SIZE), BACKGROUND)
    portrait(canvas, src, IOS_SIZE * PORTRAIT_WIDTH, IOS_SIZE * PORTRAIT_BOTTOM)
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
