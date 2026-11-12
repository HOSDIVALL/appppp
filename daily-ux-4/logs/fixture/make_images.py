#!/usr/bin/env python3
"""生成 DAILY-UX-4 用的虚构测试图片（无任何真实素材）。"""
from PIL import Image, ImageDraw, ImageFont
import sys

try:
    BIG = ImageFont.truetype("C:/Windows/Fonts/arial.ttf", 64)
except Exception:
    BIG = ImageFont.load_default()

W, H = 900, 620


def make(path, label, color):
    img = Image.new("RGB", (W, H), color)
    d = ImageDraw.Draw(img)
    # 简单的虚构纹理：斜线 + 大号标签，便于在截图里一眼认出是哪张图
    for x in range(-H, W, 48):
        d.line([(x, 0), (x + H, H)], fill=(255, 255, 255), width=6)
    d.rectangle([40, 40, W - 40, 150], fill=(255, 255, 255))
    d.text((60, 70), label, fill=(20, 20, 20), font=BIG)
    d.rectangle([40, H - 150, W - 40, H - 40], fill=(255, 255, 255))
    d.text((60, H - 125), "UX4 TEST IMAGE", fill=(20, 20, 20), font=BIG)
    img.save(path, "PNG")
    print("wrote", path, img.size)


make(sys.argv[1] + "/ux4_img1.png", "UX4 IMAGE 1", (210, 120, 90))
make(sys.argv[1] + "/ux4_img2.png", "UX4 IMAGE 2", (90, 140, 190))
