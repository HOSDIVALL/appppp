# DAILY-UX-4 · 长笔记输入过程的光标可见性

- 时间：2026-11-12
- 基线：`04f400fec16dfdd01be9cb83296a9d83181c3353`（`ux/daily-ux-2` 固化点）
- 工作分支：`ux/daily-ux-4`（从基线新建的独立工作区；**源码未合并、未建 PR、暂不固化**）
- 设备：`emulator-5554`（API 37，1080×2400 @420dpi）。真机零访问。
- 数据：全部为编造文本与自绘 PNG；`pm clear` 后注入 `files/notes.json` + `files/attachments/*.png`。

## 结论

四个动作在两个场景下都跑过：**只有「长正文 → 点正文下方空白继续输入」复现了光标不可见**，其余通过。
按「一批只修一个最明显的问题」，本轮只改这一处。

| 动作 | 场景 A（单块长正文） | 场景 B（多块夹图） |
| --- | --- | --- |
| 1 末尾持续输入并换行（5–10 行） | 通过（光标稳定停在键盘上沿 1455–1516） | 通过 |
| 2 中部点击定位 → 输入 → 回末尾继续 | 通过 | 通过 |
| 3 收键盘 → 滚到末尾 → 点空白继续输入 | **失败 → 已修 → 复测通过** | 通过（末块比可视区矮） |
| 4 键盘显示时上滑阅读 → 点另一行编辑 | 通过（1.5 s 帧差 0，未被拉回） | 通过 |

失败时的两种表现（同一原因）：

- 视图**跳到块顶**（第 01 行对齐视口顶部），光标在键盘下方很远 → `shots/before/02-…png`；
- 视图**不动**，末屏停在「第 40 行」被键盘切掉一半，`ux4-41…tail` 全在键盘下 → `shots/before/03-…png`；
- 头几个字符输入在一个看不见的位置上，直到文本变化后视图才补跳过去。

原因：焦点/键盘高度变化时请求的是**整个文字块**的 `bringIntoView()`；块比可视区高时只能按块边界对齐。
修复：改成请求**光标所在行**的矩形（`TextLayoutResult.getCursorRect`），单行输入框仍走整块请求。

## 目录

```
daily-ux-4/
├── INDEX.md                 本文件
├── CHECKLOG.md              逐项检查记录：步骤、键盘顶边、光标矩形、结论、未覆盖项
├── FIX.md                   改动说明、为什么是这一处、明确没做什么、如何应用补丁
├── diff/daily-ux-4.diff     完整补丁（相对 04f400f，1 个文件，+29 / −3）
├── files/NoteEditorScreen.kt  改后源码副本（blob 82879f2）
├── logs/
│   ├── adb-commands.md      已脱敏的命令序列
│   ├── probe.py             取证工具（连拍帧差找光标 / 读 IME 边界）
│   ├── unit-test.txt        JVM 单测：3 suite / 9 条 / 0 失败
│   └── fixture/             虚构数据：notes.json、两张自绘 PNG、两个生成脚本
├── shots/before/            改动前 8 张（01–08）
├── shots/after/             改动后 9 张（01–09）
└── SHA256SUMS               本目录全部文件（不含自身）
```

## 复验补丁

基线文件是 **CRLF** 行尾，必须带 `--binary`，否则 patch 会剥掉 CR、产物变 LF：

```sh
git -C <repo> archive 04f400fec16dfdd01be9cb83296a9d83181c3353 | tar -x -C /tmp/base
cd /tmp/base && patch --binary -p1 --fuzz=0 < <evidence>/daily-ux-4/diff/daily-ux-4.diff
cmp app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt <workspace>/app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt
```

本轮已实跑：`patch` 无 fuzz，`cmp` 逐字节相同。

## 口径

- 判据量是**光标矩形**与**键盘上边界**；`uiautomator dump` 只给整块 EditText 的 bounds，未拿它当光标证据。
- `mInputShown=true` 只用于说明键盘确实弹出，不用于说明光标可见。
- 「整个文字块都在可视区内」不作为要求；要求仅是当前光标与输入行可见。
- 改动只落在「请求滚进可视区的落点」，未触数据模型、保存/防抖、返回逻辑、块模型；UX-1 / UX-2 的判定函数与行为均未改。
- 未跑 `connectedAndroidTest`；JVM 单测只覆盖既有策略函数，不作为 UI 行为验证。

## 边界

- 历史证据（`daily-ux-1/2/3`、`backup-safe-1a`）未改动，本轮只新增 `daily-ux-4/`。
- `app/` 下的业务源码未提交到任何分支；本轮只提交本目录的评审材料。
- 未合并 `main`、未建 PR、未推送功能分支。
- 未上真机，未验证键盘弹出动画期间（约 200 ms）的中间帧。
