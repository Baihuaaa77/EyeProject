# -*- coding: utf-8 -*-
"""由真人数字人原图（绿幕）生成「爱科」头像与半身像，双端同一份。

用法：python scripts/generate_eyecho_portrait.py docs/素材/Aike.png
输出：
  core/designsystem/src/main/res/drawable-nodpi/eyecho_avatar.png（512×512，透明底，问诊入口与对话头像）
  core/designsystem/src/main/res/drawable-nodpi/eyecho_half.png（500×700，透明底，对话页顶部半身像）
  iosApp/Sources/Assets.xcassets/EyechoAvatar.imageset、EyechoHalf.imageset 同名文件
裁切框与标志保护区按 941×1672 原图标定，换图时需重新标定。
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
# 原图（941×1672）中胸前「EYE」标志的范围（左、上、右、下），Y 为品牌绿，必须保持原色
LOGO_BOX = (625, 745, 715, 795)


def key_green(path: Path) -> Image.Image:
    im = np.asarray(Image.open(path).convert("RGB")).astype(np.float32)
    r, g, b = im[..., 0], im[..., 1], im[..., 2]
    spill = g - np.maximum(r, b)  # 绿幕处约 50~100，人物处 ≤ 0
    alpha = 1 - np.clip((spill - 18) / 30, 0, 1)
    alpha = np.asarray(Image.fromarray((alpha * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8))).astype(np.float32)
    g = np.where(spill > 0, np.maximum(r, b), g)  # 去绿边
    # 胸前爱尔「EYE」标志：中间的 Y 是品牌绿，不能被当成绿幕抠掉或去色——此区域保留原图像素、完全不透明
    x0, y0, x1, y1 = LOGO_BOX
    g[y0:y1, x0:x1] = im[y0:y1, x0:x1, 1]
    alpha[y0:y1, x0:x1] = 255
    return Image.fromarray(np.dstack([r, g, b, alpha]).clip(0, 255).astype(np.uint8), "RGBA")


def main() -> None:
    source = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "docs" / "素材" / "Aike.png"
    full = key_green(source)
    avatar = full.crop((170, 50, 770, 650)).resize((512, 512), Image.LANCZOS)
    half = full.crop((120, 30, 820, 1010)).resize((500, 700), Image.LANCZOS)
    android = ROOT / "core" / "designsystem" / "src" / "main" / "res" / "drawable-nodpi"
    assets = ROOT / "iosApp" / "Sources" / "Assets.xcassets"
    for image, name, ios in ((avatar, "eyecho_avatar.png", "EyechoAvatar"), (half, "eyecho_half.png", "EyechoHalf")):
        image.save(android / name, optimize=True)
        image.save(assets / f"{ios}.imageset" / name, optimize=True)
    sys.stdout.buffer.write("已生成爱科头像与半身像\n".encode("utf-8"))


if __name__ == "__main__":
    main()
