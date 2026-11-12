# -*- coding: utf-8 -*-
"""生成 DAILY-UX-5 用的虚构笔记数据（40 条，18 条含同一关键词 LIME7）。

全部内容为编造：没有真实笔记、真实标题或真实用户信息。
关键词取 ASCII 串而不是中文：`adb shell input text` 只能注入 ASCII，
用中文关键词就得依赖第三方输入法，会引入与本轮无关的变量。
updatedAt 每条相差 37 分钟，保证顺序唯一、可判断。
"""
import json
import os

BASE = 1762000000000  # 2026-11-01 12:26:40 +08:00 附近的固定基准，不用当前时间
STEP = 37 * 60 * 1000  # 37 分钟

KEYWORD = "LIME7"

# 关键词笔记（18 条）：01,03,05,...,35
KEYWORD_INDEXES = set(range(1, 36, 2))

FILLER = [
    "河堤边的风把纸鹤吹翻了两只，顺手捡起来夹进本子。",
    "蓝伞忘在长椅上，回头找的时候已经不见了。",
    "午后光线偏黄，窗台上的玻璃瓶投出一条细长的影子。",
    "巷口的小店换了招牌，字体比原来圆一点。",
    "把旧车票按月份排开，才发现有几张日期重复了。",
    "雨停之后路面反光，脚下像多了一层浅浅的水面。",
]


def build():
    notes = []
    for i in range(1, 41):
        has_kw = i in KEYWORD_INDEXES
        title = "UX5-%02d 编号笔记" % i
        lines = []

        if has_kw:
            lines.append("主题：LIME7 记录（第 %d 条）" % i)
        else:
            lines.append("主题：普通记录（第 %d 条）" % i)

        # 每条 3 行正文，来自固定素材轮换，纯虚构
        for k in range(3):
            lines.append(FILLER[(i + k) % len(FILLER)])

        lines.append("编号 %02d 结束。" % i)
        content = "\n".join(lines)

        ts = BASE - (i - 1) * STEP
        notes.append({
            "id": "ux5-note-%02d" % i,
            "title": title,
            "content": content,
            "createdAt": ts - 5 * 60 * 1000,
            "updatedAt": ts,
            "colorIndex": i % 5,
            "pinned": False,
            "tags": [],
            "images": [],
            "audio": [],
            "checklist": False,
            "todos": [],
        })
    return notes


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    data = build()
    out = os.path.join(here, "notes.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    kw = [n["id"] for n in data if KEYWORD in n["content"]]
    print("notes:", len(data))
    print("with keyword:", len(kw))
    print("keyword %s count" % KEYWORD, len(kw))
    st = [n["updatedAt"] for n in data]
    print("updatedAt unique:", len(set(st)) == len(st), "| newest", st[0], "| oldest", st[-1])
    print("written:", out, os.path.getsize(out), "bytes")
