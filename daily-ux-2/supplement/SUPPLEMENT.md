# DAILY-UX-2 补证：混排内容下的空白点击回归

- 日期：2026-11-12
- 范围：**只补直接受影响的模拟器回归**。生产源码、测试依赖、`app/` 下任何文件均**零改动**；也未合并 `main`、未建 PR。
- 结论口径先说清楚：**补证未改源码，未重跑既有测试**（单测 9 项、仪器化 87 项仍是上一轮 `bf240c4` 记录的结果，本轮没有重复执行，也不拿它们当本轮的证据）。

## 0. 环境与素材

| 项 | 值 |
|---|---|
| 设备 | `Medium_Phone_API_37.0` / `emulator-5554`，API 37，1080×2400 @420dpi |
| 应用 | debug APK，`com.example.myapplicationfirstapp` v2.0，来自工作区 `D:/SAVE-ORDER-UX-2-wt`（源码即待评审版本） |
| 真机 | **零访问**：本轮所有 adb 命令都带 `-s emulator-5554`，未向任何其他设备下发命令 |
| 数据来源 | 全部本轮现生成：`FIXTURE_IMG_01.png`（Pillow 12.3.0，900×600，虚构色块 + 文字）、`FIXTURE_AUDIO_01.m4a`（ffmpeg 8.1.1，8 秒 440Hz 正弦）。未读取任何真实笔记、附件、备份或 `files.tar` |

素材经**应用真实入口**进入笔记：系统照片选择器（`PickVisualMedia`）→「插入正文」；「插入录音」弹层 →「插入正文」→「插入录音」。不是手改 JSON 造出来的展示态。

## 1. 被验证的笔记结构

`notes/00-created.json`（应用自己写入 `files/notes.json`）：

```
alpha-A-block

![图片](img_1794488727724.jpg)

bravo-B-line

!audio(FIXTURE_AUDIO_01.m4a)

charlie-C-tail
```

按 `parseContentBlocks` 是 **5 个独立块**：`Text(A)` → `Image` → `Text(B)` → `Audio` → `Text(C)`。
不是"一段多行文字"，A/B/C 是三个分开的 `ContentBlock.Text`，中间夹着图片块与录音块。

## 2. 逐项结果

判据一律取 `uiautomator dump` 的 `text` / `bounds` / `focused` 与 `dumpsys input_method` 的 `mInputShown`，不看"截图看起来对不对"。截图只作旁证。

| # | 检查项 | 操作 | 观测 | 结论 | 证据 |
|---|---|---|---|---|---|
| 1 | 点图片打开原查看界面、不误弹键盘 | 点图片块中心 `(540,1300)` | 出现全屏查看层，仅一个「关闭」节点；`mInputShown=false`；无 EditText 获焦；关闭后三块文字原样 | 通过 | `screenshots/20-image-viewer.png`、`ui-dump/20-image-viewer.xml` |
| 2 | 录音播放/暂停、进度拖动正常，不误跳正文末尾 | 点播放 → 点暂停 → 展开 → 拖 `SeekBar [69,2062][1011,2125]` 从 200 到 850 | 播放中 `content-desc="暂停录音"`、副标题 `00:08 · 正在播放`；暂停后回 `· 录音`；拖动后位置 `00:06`；全程 `mInputShown=false`、无 EditText 获焦、C 文本未变 | 通过 | `22-playing.*`、`27-seeked.*`、`screenshots/27-seeked.png` |
| 3 | 点 A、B 的文字，输入落在点击位置 | A：点 `x=120` 与 `x=200` 各输入 1 字符；B：点 `x=150` 输入 1 字符 | A：`alphZa-A-block`（下标 4）、`alpha-AZ-block`（下标 7）；B：`bravoQ-B-line`（下标 5）。插入点随点击 x 变化，**不是固定末尾** | 通过 | `ui-dump/35-A-tap120.xml`、`34-A-tap200.xml`、`36-B-tap150.xml` |
| 4 | 点 C 之后空白，输入只追加在 C 末尾 | 点 `(540,2280)`（C 底边 2188 以下）后输入 `TAIL` | `mInputShown` false→true；C 获焦；结果 `charlie-C-tailTAIL`；块数不变；随后 4 次退格恢复原样 | 通过 | `ui-dump/38-C-tail.xml`、`screenshots/38-C-tail.png` |
| 5 | 混排内容与末尾空白拖动仍可滚动、不误触聚焦 | ① 内容区 `700→1700`；② 从末尾空白起手 `2200→2390` | ① A 的 y `281→934`；② A 的 y `281→442`。两次 `mInputShown=false`，焦点块未变 | 通过 | `ui-dump/41-padded.xml`、`43-drag2.xml`、`46-blank-drag-down.xml` |
| 6 | 不新增块/换行，返回重开一致 | 三次取 `files/notes.json`；返回列表再进入 | 创建后 / 检查后 / 模式往返后，`content` 字符串**完全相等**；5 块、8 个换行（4 个 `\n\n`）；`images`/`audio` 引用不变；重开仍是 3 个文字块 + 图片块 + 录音块 | 通过 | `notes/00-created.json`、`01-after-checks.json`、`02-after-mode-roundtrip.json`、`59-final-open.*` |

### 关于第 5 项的测量方式（如实说明）

这条笔记的原始内容**不足一屏**，没有滚动余量，所以先临时在 C 末尾加了 5 行 `pad1..pad5` 制造滚动空间；测完逐字删除，再核对 `notes.json` 的 `content` 与创建时**逐字节相等**（`notes/01-after-checks.json`）。删除过程中曾误删到 C 的正文，重打后同样核对通过——最终态就是原始态，没有把"测试残留"留在笔记里。

第 2 项也有一次操作失误：第一次拖动起点取 `x=90`（滑块最左端）未生效，改从 `x=200` 起拖才生效。这是点击落点问题，不是功能失效，如实记录。

## 3. 清单 → 文本 切换后立即点空白

| 方向 | 操作 | 观测 | 结论 |
|---|---|---|---|
| 文本 → 清单 | 点「清单」后**不等待**，立即点空白 `(540,2200)` | 无崩溃（pid 8827 前后一致）；`mInputShown=false`；`focused=true` 节点 **0 个**；清单 3 项；头部仍是「1 张图片 · 1 段录音」 | 通过：清单模式不接管空白点击 |
| 清单 → 文本 | 点「文本」后**不等待**，立即点空白 `(540,2200)` | 无崩溃；`mInputShown=true`；焦点落在**最后一个文字块 C**（不是别的块）；内容显示为 `- [ ] alpha-A-block` 等（这是应用既有的清单↔文本标记约定，不是本改动引入） | 通过：坐标未就绪没有导致错块聚焦或崩溃 |

两点必须说明，避免把话说大：

- "立即"是两条 `adb shell input tap` 之间**不加 sleep**（实测间隔约 100–300ms），不是同一帧注入。它证明的是"切换后很快点击"这条路径没有崩溃/错块，**不证明零延迟下的所有时序**。
- 切回文本后正文带 `- [ ] ` 前缀，是 `todosToContent` 的既有设计（为了记住勾选状态）；本轮随后用键盘把这些前缀删掉，才回到原始正文，并核对 `content` 相等。

## 4. 环境噪声（如实记录，不归因于本改动）

`pm clear` 后首次冷启动，**列表页**出现过一次 ANR：`Input dispatching timed out ... Waited 5002ms for FocusEvent(hasFocus=true)`（13:02:34）。主线程栈停在 Compose 绘制（`LinearGradientShader` → `BackgroundNode.draw`），没有任何本改动的帧，且发生在编辑页之外。强制停止后重启未复现。

证据：`logs/anr-coldstart-head.txt`（`dumpsys dropbox --print data_app_anr` 头部）。这条记录只为完整，不作为"改动无回归"的正面证据，也不拿它证明"改动有问题"。

## 5. 仍未验证（不因本轮通过而消失）

- **真机**：本轮按任务要求只用模拟器。混排这条检查按任务口径**只需要模拟器**，不需要真机；但系统输入法差异、真实触控精度、刘海/手势条适配仍未被覆盖。
- 像素级光标位置：只用"输入 1 个字符落在哪个下标"间接推断（本机 16sp 下约 22–23px/字符），精度约 ±1 字符。
- 第三方输入法、无障碍（TalkBack）、深色主题、横屏、大字体、分屏/悬浮窗：未验证。
- 自动化 UI 回归：仍然没有（Espresso 3.5.1 在 API 37 的 `InputManager.getInstance()` 反射问题未变），本轮的结论依旧只来自模拟器手动对照 + UI dump。

## 6. 复跑

命令序列见 `logs/adb-commands.md`；素材生成方式见 `fixtures/`（PNG 与 m4a 均为本轮生成，无隐私内容）。
