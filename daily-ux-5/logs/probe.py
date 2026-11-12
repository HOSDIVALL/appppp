# -*- coding: utf-8 -*-
"""DAILY-UX-5 取证探针：UI dump / 可见笔记项与坐标 / 键盘状态 / 截图。

用法：
  python probe.py dump  <name.xml>        导出当前界面层级到 logs/ux5/<name.xml>
  python probe.py list  <name.xml>        列出该 dump 中所有 UX5- 开头的可见文字与 bounds
  python probe.py ime                     打印 mInputShown、IME 顶边、搜索框内文字
  python probe.py shot  <name.png>        截图到 logs/ux5/<name.png>
"""
import os
import re
import subprocess
import sys

ADB = os.environ.get("ADB", "adb")
SERIAL = os.environ.get("SERIAL", "emulator-5554")
HERE = os.path.dirname(os.path.abspath(__file__))


def adb(*args, **kw):
    cmd = [ADB, "-s", SERIAL] + list(args)
    return subprocess.run(cmd, capture_output=True, **kw)


def dump(name):
    adb("shell", "uiautomator", "dump", "/sdcard/ux5.xml")
    p = adb("shell", "cat", "/sdcard/ux5.xml")
    path = os.path.join(HERE, name)
    with open(path, "wb") as f:
        f.write(p.stdout)
    print("dumped", path, len(p.stdout), "bytes")
    return path


def parse(path):
    xml = open(path, encoding="utf-8", errors="replace").read()
    out = []
    for m in re.finditer(r'<node[^>]*>', xml):
        tag = m.group(0)
        t = re.search(r'text="([^"]*)"', tag)
        b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', tag)
        if not t or not b:
            continue
        txt = t.group(1)
        if not txt.strip():
            continue
        x1, y1, x2, y2 = (int(b.group(i)) for i in range(1, 5))
        out.append((txt, x1, y1, x2, y2))
    return out


def cmd_list(path):
    rows = [r for r in parse(path) if r[0].startswith("UX5-")]
    rows.sort(key=lambda r: r[2])
    print("可见 UX5- 项 %d 个（按 y 排序）：" % len(rows))
    for txt, x1, y1, x2, y2 in rows:
        print("  %-22s bounds=(%d,%d)-(%d,%d) h=%d" % (txt[:22], x1, y1, x2, y2, y2 - y1))
    if rows:
        print("顶部项: %s  y=%d" % (rows[0][0], rows[0][2]))
        print("底部项: %s  y=%d" % (rows[-1][0], rows[-1][2]))


def cmd_ime():
    p = adb("shell", "dumpsys", "input_method")
    txt = p.stdout.decode("utf-8", "replace")
    m = re.search(r"mInputShown=(\w+)", txt)
    print("mInputShown=", m.group(1) if m else "?", sep="")
    w = adb("shell", "dumpsys", "window").stdout.decode("utf-8", "replace")
    for blk in re.split(r"\n  Window #", w):
        head = blk.split("\n")[0]
        if "InputMethod" in head or "type=ime" in blk[:400]:
            vf = re.search(r"visibleFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", blk)
            fr = re.search(r"mFrame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", blk)
            if vf:
                print("IME_VISIBLE_TOP_Y=", vf.group(2), sep="")
            if fr:
                print("IME_FRAME_TOP_Y=", fr.group(2), sep="")
            break


def cmd_shot(name):
    p = adb("exec-out", "screencap", "-p")
    path = os.path.join(HERE, name)
    with open(path, "wb") as f:
        f.write(p.stdout)
    print("shot", path, len(p.stdout), "bytes")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    c = sys.argv[1]
    if c == "dump":
        dump(sys.argv[2])
    elif c == "list":
        cmd_list(sys.argv[2])
    elif c == "ime":
        cmd_ime()
    elif c == "shot":
        cmd_shot(sys.argv[2])
    else:
        print("unknown", c)
