#!/usr/bin/env python3
"""生成模块图标：粉底圆形 + 白色扫码角括号（和扫码页配色一致）。"""
import os
from PIL import Image, ImageDraw

ACCENT = (251, 114, 153, 255)
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "res")


def render(px):
    ss = 4  # 超采样，缩出来边缘更干净
    size = px * ss
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.22, fill=ACCENT)

    # 扫码框
    pad = size * 0.30
    x0, y0, x1, y1 = pad, pad * 1.05, size - pad, size - pad * 0.95
    arm = (x1 - x0) * 0.42
    w = max(2, int(size * 0.055))
    white = (255, 255, 255, 255)

    def line(a, b):
        d.line([a, b], fill=white, width=w)

    line((x0, y0 + arm), (x0, y0))
    line((x0, y0), (x0 + arm, y0))
    line((x1 - arm, y0), (x1, y0))
    line((x1, y0), (x1, y0 + arm))
    line((x1, y1 - arm), (x1, y1))
    line((x1, y1), (x1 - arm, y1))
    line((x0 + arm, y1), (x0, y1))
    line((x0, y1), (x0, y1 - arm))

    cx = size / 2
    my = size * 0.52
    line((cx - (x1 - x0) * 0.20, my), (cx + (x1 - x0) * 0.20, my))

    return img.resize((px, px), Image.LANCZOS)


for dpi, px in SIZES.items():
    path = os.path.join(OUT, "mipmap-" + dpi)
    os.makedirs(path, exist_ok=True)
    render(px).save(os.path.join(path, "ic_launcher.png"))
    print("wrote", os.path.join(path, "ic_launcher.png"))
