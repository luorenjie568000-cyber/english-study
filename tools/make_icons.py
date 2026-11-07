#!/usr/bin/env python3
"""生成启动图标：深色圆角底 + 蓝色书页 + 字母 E 的抽象折角。
用法: python3 make_icons.py <输出目录>
"""
import sys
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1] if len(sys.argv) > 1 else "."

SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BG = (11, 15, 20, 255)
BLUE = (79, 124, 255, 255)
GREEN = (52, 211, 153, 255)
WHITE = (232, 238, 247, 255)


def render(size, round_icon=False):
    ss = 8  # 超采样
    S = size * ss
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # 底板
    if round_icon:
        d.ellipse([0, 0, S - 1, S - 1], fill=BG)
    else:
        r = int(S * 0.22)
        d.rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=BG)

    # 蓝色光晕
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([S * 0.10, S * 0.06, S * 0.90, S * 0.72], fill=(79, 124, 255, 90))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.075))
    img.alpha_composite(glow)

    # 打开的书：两页梯形
    top = S * 0.30
    bot = S * 0.76
    mid = S * 0.50
    left = S * 0.20
    right = S * 0.80
    spine = S * 0.495
    d.polygon([(left, top + S * 0.045), (spine, top), (spine, bot), (left, bot - S * 0.02)],
              fill=BLUE)
    d.polygon([(spine, top), (right, top + S * 0.045), (right, bot - S * 0.02), (spine, bot)],
              fill=(96, 140, 255, 255))

    # 书页横线
    for k in range(4):
        y = top + S * 0.13 + k * S * 0.105
        d.line([(left + S * 0.055, y + S * 0.014), (spine - S * 0.035, y - S * 0.010)],
               fill=(255, 255, 255, 120), width=max(1, int(S * 0.016)))
        d.line([(spine + S * 0.035, y - S * 0.010), (right - S * 0.055, y + S * 0.014)],
               fill=(255, 255, 255, 120), width=max(1, int(S * 0.016)))

    # 书脊高光
    d.line([(spine, top), (spine, bot)], fill=(232, 238, 247, 200),
           width=max(1, int(S * 0.012)))

    # 右上角绿色对勾圆点
    cx, cy, rad = S * 0.735, S * 0.265, S * 0.115
    d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad], fill=GREEN)
    w = max(2, int(S * 0.028))
    d.line([(cx - rad * 0.42, cy + rad * 0.02),
            (cx - rad * 0.10, cy + rad * 0.36),
            (cx + rad * 0.46, cy - rad * 0.34)],
           fill=(11, 15, 20, 255), width=w, joint="curve")

    return img.resize((size, size), Image.LANCZOS)


for folder, px in SIZES.items():
    render(px, False).save(f"{OUT}/{folder}/ic_launcher.png")
    render(px, True).save(f"{OUT}/{folder}/ic_launcher_round.png")
    print(f"{folder}: {px}x{px}")
