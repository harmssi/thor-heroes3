#!/usr/bin/env python3
"""Thor Edition launcher icon (own art, no game artwork): near-black tile, amber hexagon, "H3" over "THOR" (D3 port uses "III")
in Inter Bold (OFL). Writes android/vcmi-app/src/main/res/mipmap-*/ic_launcher.png. Needs Pillow."""
import math, os
from PIL import Image, ImageDraw, ImageFont
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
FONT = os.path.join(ROOT, 'android/res/font/inter_bold.ttf')
S = 512
img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle((16, 16, S - 16, S - 16), 96, fill=(8, 9, 11, 255), outline=(50, 56, 68, 255), width=4)
amber, amber2 = (232, 176, 74, 255), (243, 210, 126, 255)
hexagon = [(S / 2 + 190 * math.cos(math.radians(60 * i - 90)), S / 2 + 190 * math.sin(math.radians(60 * i - 90))) for i in range(6)]
d.polygon(hexagon, outline=(232, 176, 74, 150), width=10)
d.text((S / 2, 236), 'H3', font=ImageFont.truetype(FONT, 180), anchor='mm', fill=amber)
d.text((S / 2, 370), 'THOR', font=ImageFont.truetype(FONT, 74), anchor='mm', fill=amber2)
for name, px in (('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)):
    img.resize((px, px), Image.LANCZOS).save(os.path.join(ROOT, f'android/vcmi-app/src/main/res/mipmap-{name}/ic_launcher.png'))
img.save(os.path.join(ROOT, 'thor/icon-512.png'))
print('ok')
