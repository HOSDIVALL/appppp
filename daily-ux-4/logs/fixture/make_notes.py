#!/usr/bin/env python3
"""生成 DAILY-UX-4 的虚构 notes.json（全部为编造文本，不含任何真实内容）。"""
import json
import time

now = int(time.time() * 1000)


def line(i, prefix="第"):
    return f"{prefix} {i:02d} 行｜虚构正文，用来检查长笔记输入时光标是否被键盘挡住。"


# 场景 A：一个 Text 块里 40 行，块高远超键盘弹出后的可视区
a_content = "\n".join(line(i) for i in range(1, 41))

# 场景 B：多个 Text 块，块之间夹虚构测试图片
b_blocks = [
    "\n".join(line(i) for i in range(1, 9)),
    "\n".join(line(i) for i in range(9, 17)),
    "\n".join(line(i) for i in range(17, 25)),
]
b_content = (
    b_blocks[0] + "\n\n![图片](ux4_img1.png)\n\n"
    + b_blocks[1] + "\n\n![图片](ux4_img2.png)\n\n"
    + b_blocks[2]
)

# 回归用：短笔记
c_content = "短笔记一行。\n短笔记第二行。"

notes = [
    {
        "id": "ux4-a-0000-0000-0000-000000000001",
        "title": "UX4-A 单块长正文",
        "content": a_content,
        "createdAt": now - 300000,
        "updatedAt": now - 300000,
        "colorIndex": 0,
        "pinned": True,
        "tags": ["ux4"],
        "images": [],
    },
    {
        "id": "ux4-b-0000-0000-0000-000000000002",
        "title": "UX4-B 多块夹图",
        "content": b_content,
        "createdAt": now - 200000,
        "updatedAt": now - 200000,
        "colorIndex": 1,
        "pinned": False,
        "tags": ["ux4"],
        "images": ["ux4_img1.png", "ux4_img2.png"],
    },
    {
        "id": "ux4-c-0000-0000-0000-000000000003",
        "title": "UX4-C 短笔记",
        "content": c_content,
        "createdAt": now - 100000,
        "updatedAt": now - 100000,
        "colorIndex": 2,
        "pinned": False,
        "tags": ["ux4"],
        "images": [],
    },
]

with open("notes.json", "w", encoding="utf-8") as f:
    json.dump(notes, f, ensure_ascii=False)

print("notes:", len(notes))
print("A lines:", a_content.count("\n") + 1, "chars:", len(a_content))
print("B blocks: 3 text + 2 image, lines:", b_content.count("\n") + 1)
