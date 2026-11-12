#!/usr/bin/env python3
"""DAILY-UX-4 取证探针。

用法：
  python probe.py caret <前缀> [张数]   # 连拍并与相邻帧做差，找出闪烁的光标矩形
  python probe.py ime                   # 键盘是否显示 + IME 窗口顶边 y
  python probe.py dump <文件名>          # uiautomator dump 到本地
  python probe.py shot <文件名>          # 单张截图

只用 adb + Pillow，不引入新依赖。
"""
import os
import re
import subprocess
import sys
import time

from PIL import Image, ImageChops

# 归档版：本机 adb 绝对路径已脱敏，改用环境变量 ADB（本机运行时指向 platform-tools/adb）
ADB = os.environ.get("ADB", "adb")
SERIAL = "emulator-5554"
HERE = os.path.dirname(os.path.abspath(__file__))


def adb(*args, binary=False):
    cmd = [ADB, "-s", SERIAL, *args]
    if binary:
        return subprocess.run(cmd, stdout=subprocess.PIPE, check=True).stdout
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                          check=True, text=True, encoding="utf-8", errors="replace").stdout


def shot(path):
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(path, "wb") as f:
        f.write(data)
    return path


def regions(diff_img, thr=40):
    """把差异图切成若干行带，再在每带内切列块，返回 (x1,y1,x2,y2) 列表。

    只用 bytes 切片上的 max()（C 实现），避免逐像素 Python 循环。
    """
    w, h = diff_img.size
    data = diff_img.tobytes()

    row_hit = [max(data[y * w:(y + 1) * w]) > thr for y in range(h)]

    bands = []
    y = 0
    while y < h:
        if row_hit[y]:
            y1 = y
            while y < h and row_hit[y]:
                y += 1
            bands.append((y1, y - 1))
        else:
            y += 1

    out = []
    for y1, y2 in bands:
        col_hit = []
        for x in range(w):
            col = data[y1 * w + x: y2 * w + x + 1: w]
            col_hit.append(max(col) > thr)
        x = 0
        while x < w:
            if col_hit[x]:
                x1 = x
                while x < w and col_hit[x]:
                    x += 1
                out.append((x1, y1, x - 1, y2))
            else:
                x += 1
    return out


def cmd_caret(prefix, n=4):
    n = int(n)
    paths = []
    for i in range(n):
        p = os.path.join(HERE, f"{prefix}-{i}.png")
        shot(p)
        paths.append(p)
        time.sleep(0.18)

    all_boxes = []
    for i in range(len(paths) - 1):
        a = Image.open(paths[i]).convert("RGB")
        b = Image.open(paths[i + 1]).convert("RGB")
        diff = ImageChops.difference(a, b).convert("L")
        boxes = regions(diff)
        # 只保留像光标的：窄而高
        carets = [bx for bx in boxes
                  if (bx[2] - bx[0]) <= 18 and 24 <= (bx[3] - bx[1]) <= 90]
        print(f"[pair {i}->{i+1}] changed={len(boxes)} "
              f"caret_candidates={carets if carets else '-'}")
        if boxes and not carets:
            print("           全部差异块:", boxes[:6])
        all_boxes.extend(carets)

    if all_boxes:
        xs1 = min(b[0] for b in all_boxes)
        ys1 = min(b[1] for b in all_boxes)
        xs2 = max(b[2] for b in all_boxes)
        ys2 = max(b[3] for b in all_boxes)
        print(f"CARET_BOX x={xs1}..{xs2} y={ys1}..{ys2} "
              f"(h={ys2 - ys1 + 1}px)  frames={len(all_boxes)}")
        # 把光标框画到第一张上，便于人眼复核
        img = Image.open(paths[0]).convert("RGB")
        for x in range(max(0, xs1 - 6), min(img.width, xs2 + 7)):
            img.putpixel((x, max(0, ys1 - 6)), (255, 0, 0))
            img.putpixel((x, min(img.height - 1, ys2 + 6)), (255, 0, 0))
        for y in range(max(0, ys1 - 6), min(img.height, ys2 + 7)):
            img.putpixel((max(0, xs1 - 6), y), (255, 0, 0))
            img.putpixel((min(img.width - 1, xs2 + 6), y), (255, 0, 0))
        marked = os.path.join(HERE, f"{prefix}-caret-marked.png")
        img.save(marked)
        print("MARKED", marked)
    else:
        print("CARET_BOX none（这几帧里没有找到闪烁的竖条）")
    return 0


def cmd_ime():
    out = adb("shell", "dumpsys", "input_method")
    shown = re.search(r"mInputShown=(\w+)", out)
    vis = re.search(r"mImeWindowVis=(\S+)", out)
    print("mInputShown=", shown.group(1) if shown else "?", sep="")
    print("mImeWindowVis=", vis.group(1) if vis else "?", sep="")

    win = adb("shell", "dumpsys", "window")
    # InsetsSource id=3 type=ime frame=[0,0][0,0] visibleFrame=[0,2274][1080,2400] visible=false ...
    m = re.search(
        r"type=ime frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\] visibleFrame=\[(\d+),(\d+)\]\[(\d+),(\d+)\] visible=(\w+)",
        win)
    if m:
        print(f"IME_VISIBILITY visible={m.group(9)}")
        if m.group(9) == "true":
            print(f"IME_TOP_Y={m.group(6)}  (visibleFrame=[{m.group(5)},{m.group(6)}]"
                  f"[{m.group(7)},{m.group(8)}])")
        else:
            print("IME_TOP_Y=none（键盘未显示）")
    else:
        print("IME insets 未找到")
    return 0


def cmd_dump(name):
    adb("shell", "uiautomator", "dump", "/sdcard/ux4-dump.xml")
    xml = adb("shell", "cat", "/sdcard/ux4-dump.xml")
    path = os.path.join(HERE, name)
    with open(path, "w", encoding="utf-8") as f:
        f.write(xml)
    print("wrote", path, len(xml), "chars")
    return 0


def cmd_shot(name):
    p = shot(os.path.join(HERE, name))
    print("wrote", p)
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "caret":
        sys.exit(cmd_caret(*sys.argv[2:]))
    if cmd == "ime":
        sys.exit(cmd_ime())
    if cmd == "dump":
        sys.exit(cmd_dump(sys.argv[2]))
    if cmd == "shot":
        sys.exit(cmd_shot(sys.argv[2]))
    print(__doc__)
