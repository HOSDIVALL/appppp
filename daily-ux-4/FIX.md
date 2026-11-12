# DAILY-UX-4 修复说明

## 改了什么

`app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt`，+29 / −3，blob `82879f2`（基线 blob `22e1471`）。

1. `rememberEditorFocusVisibility` 增加可选参数 `cursorRect: () -> Rect? = { null }`。
   焦点变化或键盘高度变化时，**如果给了光标矩形，就只请求这一行**；没给就退回原来的整块请求。
2. `EditableBlock` 通过 `onTextLayout` 记录最近一次 `TextLayoutResult`，请求时用
   `layout.getCursorRect(value.selection.end)` 换算出光标行矩形（偏移夹到文本长度内）。
   布局对象存在一个普通数组里，不进 `mutableStateOf`：它只在请求那一刻读一次，不该因为每次布局触发重组。

标题栏输入框、清单行这些**单行**输入框不传 `cursorRect`，仍是整块请求，行为与改动前一致。

## 为什么是这一处

复现出来的问题只有一个（长正文点正文下方空白继续输入时光标不可见），但它有两个表现：

- 块比可视区高时，整块 `bringIntoView()` 只能按块边界对齐 → 要么把块顶贴到视口顶部（视图跳到第 01 行），
  要么在键盘 inset 动画期间不产生有效滚动；
- 两种表现的结果相同：**正在输入的那一行在键盘下方**，用户是在看不见的位置打字。

整块请求与「光标行」请求的差别只有落点，不牵扯输入模型、不牵扯数据与保存，也不需要动布局结构。

## 明确没做

- 没有加重试、没有固定延时、没有无限请求循环，也没有「每次重组都滚到底」。
- 没有动数据模型、保存/防抖、返回逻辑、块模型与正文序列化。
- 没有改 UX-1（新建自动聚焦 / 已有笔记不抢焦点）与 UX-2（点正文下方空白继续输入）的判定函数。
- 没有改清单模式、图片/录音块、依赖与 Gradle 配置。

## 应用与复核

基线的这个文件是 **CRLF** 行尾，所以复验要用 `--binary`，否则 patch 会先把 CR 剥掉、产物变成 LF：

```sh
git -C <repo> archive 04f400fec16dfdd01be9cb83296a9d83181c3353 | tar -x -C /tmp/base
cd /tmp/base
patch --binary -p1 --fuzz=0 < daily-ux-4/diff/daily-ux-4.diff
cmp app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt <repo>/app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt
```

本轮已按上面这条命令在 `04f400f` 的干净导出上实跑：`patch` 无 fuzz，`cmp` 逐字节相同（与 `files/NoteEditorScreen.kt` 一致）。
