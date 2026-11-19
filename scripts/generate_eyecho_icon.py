"""从用户提供的方形爱科插图生成双端桌面和开屏图标。"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageOps

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
ASSETS = ROOT / 'iosApp/Sources/Assets.xcassets'
SOURCE = ROOT / 'scripts/assets/eyecho_icon_source.png'
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}


def round_icon(image: Image.Image) -> Image.Image:
    mask = Image.new('L', (512, 512))
    ImageDraw.Draw(mask).ellipse((0, 0, 511, 511), fill=255)
    result = image.copy()
    result.putalpha(mask.resize(image.size, Image.Resampling.LANCZOS))
    return result


def main() -> None:
    source = Image.open(SOURCE).convert('RGB')
    if source.width != source.height:
        raise ValueError('爱科图标原图须为正方形')

    # 以边缘像素填充安卓自适应图标的系统遮罩余量。
    inset = source.width // 4
    expanded = ImageOps.expand(source, border=inset)
    width, height = source.size
    for box, at in [
        ((0, 0, width, 1), (inset, 0)),
        ((0, height - 1, width, height), (inset, height + inset)),
        ((0, 0, 1, height), (0, inset)),
        ((width - 1, 0, width, height), (width + inset, inset)),
    ]:
        edge = source.crop(box)
        expanded.paste(edge.resize((max(inset, edge.width), max(inset, edge.height))), at)
    for x, px in [(0, 0), (width + inset, width - 1)]:
        for y, py in [(0, 0), (height + inset, height - 1)]:
            expanded.paste(source.getpixel((px, py)), (x, y, x + inset, y + inset))

    for density, scale in DENSITIES.items():
        folder = RES / f'mipmap-{density}'
        size = round(108 * scale)
        expanded.resize((size, size), Image.Resampling.LANCZOS).save(folder / 'ic_launcher_foreground.png', optimize=True)
        size = round(48 * scale)
        legacy = source.resize((size, size), Image.Resampling.LANCZOS)
        legacy.save(folder / 'ic_launcher.webp', lossless=True)
        round_icon(legacy).save(folder / 'ic_launcher_round.webp', lossless=True)

    source.resize((1024, 1024), Image.Resampling.LANCZOS).save(ASSETS / 'AppIcon.appiconset/AppIcon.png', optimize=True)
    in_app = source.resize((512, 512), Image.Resampling.LANCZOS)
    in_app.save(ASSETS / 'EyechoAppIcon.imageset/eyecho_app_icon.png', optimize=True)
    in_app.save(ROOT / 'core/designsystem/src/main/res/drawable-nodpi/eyecho_app_icon.png', optimize=True)


if __name__ == '__main__':
    main()
