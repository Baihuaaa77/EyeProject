"""从仓库中的参考 SVG 再现双端透明眼睛素材；依赖 resvg-py==0.5.0。"""
from pathlib import Path
import resvg_py

root = Path(__file__).resolve().parents[1]
svg = (root / 'shared/artwork/eye-source.svg').read_text(encoding='utf-8')
palettes = {
    'amber': ('F6A267', 'D17647', '573022', 'FFDEC3'),
    'sage': ('B6C6B3', '7B9F91', '263C36', 'E0EFDB'),
    'rose': ('E99A86', 'CD594C', '53212B', 'FFE4C4'),
    'empty': ('929B96', '607475', '263738', 'D9E3E3'),
}
for name, tones in palettes.items():
    markup = svg
    for key, value in zip(('signal', 'edge', 'dark', 'glint'), tones):
        markup = markup.replace('var(--' + key + ')', '#' + value)
    markup = markup.replace('<svg ', '<svg width="1024" height="552" ', 1)
    output = root / 'shared/artwork/res/drawable-nodpi' / ('load_eye_' + name + '.png')
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(resvg_py.svg_to_bytes(svg_string=markup))
