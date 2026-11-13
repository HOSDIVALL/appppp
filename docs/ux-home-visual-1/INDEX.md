# UX-HOME-VISUAL-1 · C 轮：按源重写

分支 `ux/home-visual-1`。本目录只在 `docs/` 下新增材料，不改 `app/` 之外的任何东西（`app/` 内的改动就是本轮交付本身）。

## 1. 这一轮在解决什么

上一轮（阶段 A + 阶段 B，提交 `17d7a39`、`0e65208`）做完后，用户判定：

> 滚动条和列项表的制作很不满意 …… 为什么效果不能做得和网页一样？为什么总和计划的有出入？

把两个网页源（`Rhythm-Scrollbar-Motion-Lab-v4.html`、`Rhythm-List-Reorder-Player-Merged-v6 (1).html`）逐段读完，比对后确认偏差不是"没实现"，而是三类系统性原因：

1. **任务书与源冲突**：任务书写"常态 12dp、更多按钮 48×48"，源里是"细宽 8dp、`.more{32×44; border-radius:50px}`"。上一版照着任务书做，所以"像计划，不像网页"。
2. **LazyColumn 拿不到全表几何**：源是 DOM，能直接量到 scrollHeight；Compose 只能看到可见项。上一版用"平均行高 × 条数"估总高，遇到超高行就整条行程跑偏。
3. **动效只做了"有没有"，没做"曲线"**：手柄高度按比例伸缩（源是**固定 48dp**）、气泡只有出现/消失（源是 180ms、scale .82→1、slide 8dp）。

本轮决定（用户选项确认）：**以源为准，全覆盖**。任务书里与源冲突的数值作废，逐条记在下面。

## 2. 口径变更记录（上一轮任务书作废项）

| 项 | 上一轮任务书 | 源 | 本轮实现 |
| --- | --- | --- | --- |
| 滚动条常态宽 | 12dp | 细宽 8dp | `scrollbarIdleWidth = 8.dp` |
| 手柄高度 | 按内容比例 | 固定 48dp | `scrollbarThumbHeight = 48.dp`（不再随内容伸缩） |
| 按住/拖动宽 | 28dp | 24 + 4 = 28dp | `scrollbarActiveWidth = 28.dp`（沿用） |
| 右留白 | 未规定 | 4dp | `scrollbarRightInset = 4.dp` |
| 轨道上下 gap | 未规定 | 8dp | `scrollbarTrackGap = 8.dp` |
| 宽度动画 | 未规定 | 200ms | `scrollbarWidthMillis = 200` |
| 定位气泡 | 有/无 | 180ms，scale .82→1，slide 8dp | `scrollbarBubbleMillis/Slide` + `bubbleScale/bubbleSlidePx` |
| 松手平滑 | 未规定 | 70ms，且只在"源未滚动 + 每步 ≥16dp + 总跳变 ≥10dp" | `shouldSmooth` + `scrollbarSettleMillis` |
| 代表步长 | 平均行高 | 可见相邻步长的**中位数** + 按项缓存 + 前缀修正 | `ScrollbarEstimate.Estimator` |
| 尾部"更多"按钮 | 48×48 | 32×44，`border-radius:50px`（胶囊） | `moreButtonWidth/Height = 32/44`，`RoundedCornerShape(percent = 50)` |
| 分组菜单 | 未规定 | 16/4；行间 3dp；内边 14×10；图标底 28、图标 16；容器 `padding:8` | `ListVisualTokens.menu*` + `GroupedMenu/GroupedMenuItem` |

作废的只有"任务书里的数字"，没有作废任何验收项本身；受影响的两项（V3 的"more 仍 48dp"、V4 的"常态 12"）在下表按**反例**处理：判 PASS 的依据是源值，并在备注里写明口径变化，不伪称旧断言仍然成立。

## 3. 验收 14 项

结果口径：PASS / FAIL / NOT_RUN。设备固定 `emulator-5554`、API 37、1080×2400、density 420（1dp = 2.625px），只用测试合成数据（`共 64 条`）。

| ID | 名称 | 结果 | 实测与证据 |
| --- | --- | --- | --- |
| V0 | 基线对照 | PASS | 同条件基线 APK `059a5cc0…549b05`（57,387,528 B，由 `e7bcdab` 重建）；`screens/V0-baseline-top.png`、`V0-baseline-bottom.png`、`V0-baseline-scroll.mp4`；`logs/build-V0-baseline*.log` |
| V1 | 真实列表视觉变化 | PASS | 列表行 24/6：组外 24、组内 6、组内相邻 4、组间 12（`ListVisualTokens`，`NotesListGroupShapeTest` 7 条）；`screens/C1-top.png` 可见"置顶"分区标题 + 首组外角圆、组内接缝更方 |
| V2 | 信息层级与内容保留 | PASS | `screens/C1-top.png`：超长标题单行省略（"这是一条刻意写得很长的…"）不挤压置顶星标与更多；`C5-bottom.png` 摘要 2 行截断、`11月5日 · 1/2` 元信息完整 |
| V3 | 按压与目标边界 | PASS（口径变化） | 按压缩放实测：卡片宽 idle 954px → 按下 936px，936/954 = **0.981**，中心不变（`screens/C11-row-idle.png` / `C11-row-press.png`）。**更多按钮按源改为 32×44**（不是 48×48）：按压高亮差分只有 `x 880–963 / y 960–1075` = 84×116px = **32×44dp**（`C8-more-press.png` 与 idle 帧逐像素差分） |
| V4 | 新版交互滑块 | PASS（口径变化） | 常态 `x 1049–1068` = 20px ≈ **8dp**（不是 12dp）；按住 `x 996–1068` = 72px = **28dp**、`y 1117–1242` = 126px = **48dp**；拖动时手柄加粗 + 浅色轨道 + 气泡可见（`C2-hold.png`、`C3-drag-bubble.png`）；按源固定高，不按比例伸缩 |
| V5 | 首中尾定位 | PASS | 首：`C1-top.png` 手柄 `y 688–813` 贴顶部 gap；中：`C3-drag-bubble.png` 气泡 `11月10日` 与当前可见首条一致；尾：`C5-bottom.png` 手柄 `y 1938–2063` 贴底部 gap，末尾 `季度复盘与下周计划 … 11月5日 · 1/2`（分区标题/筛选行不进气泡：`nearestNoteIndex` + 单测 `非笔记项吸附`、`全非笔记返回 -1`） |
| V6 | 边缘命中互不遮挡 | PASS | 更多按钮可视盒右缘 **963.5px**，滚动条命中区（40dp，右留白 4dp）左缘 **964.5px**——相邻不重叠；层级见 `hierarchy/C-list-top.xml`（可点节点 `[859,638][985,764]` 是无障碍最小触控膨胀，不等于布局盒，见 §5）；顶栏/搜索/底部导航均可正常操作（`C1-top.png`） |
| V7 | 滚动与动态数据 | PASS | 触屏普通滑动仍有效、拖动不被抢（`C4-released.png` 松手后回落到拖动终点）；内容不足一屏时手柄隐藏（上一构建 `screens/B-short-content-hidden.png`，该判定逻辑本轮未改）+ 单测 `内容不足一屏隐藏`；`contentKey` 变化时 `LaunchedEffect` 复位 drag 状态、`Estimator` 清观测（单测 `条数/内容键变化清空`） |
| R1 | 更多与长按回归 | PASS | 点"更多"开的是同一个 `menuOpen`，只开菜单不导航（`C7-menu-single.png`，菜单项"置顶"）；长按走 `combinedClickable(onLongClick)` 同一状态 |
| R2 | 搜索交接回归 | PASS | 本轮未改搜索与编辑页交接代码；上一轮 `logs/build-B-stage-b.log` 同源构建的回归记录仍有效 |
| R3 | 滑动取消与稳定ID | PASS | 本轮未改滑动/删除代码；`NotesListActionsTest` 6 条仍全绿 |
| R4 | 主题语言与可读性 | PASS | 深色：`C9-drag-bubble-dark.png`（气泡浅底深字 `昨天 15:10`）、`C10-menu-sort-dark.png`（容器 `(13,14,18)` / 条目 `(24,25,32)`，条目仍是"亮一档的托盘"）；浅色：容器 `(250,248,254)` / 条目 `(238,237,246)`；英文气泡走同一函数，单测 `气泡文案随语言切换` 断言英文标签无 CJK；窄屏 720×1600、字体 1.3 见上一轮证据且已复原（`wm size reset`、`font_scale` 回 1.0） |
| R5 | 数据与保存零侵入 | PASS | 全程 `run-as … sha256sum files/notes.json` 恒为 `cb5c19dde…2be36399`；主题/语言/排序复原为 `theme_mode=1`、`app_language=zh`、`sort_mode=0` |
| B1 | 软件交付 | PASS | 最终 APK `cb253f8f12e4905afea6ffa46a8d5340b60f1cc72de35a22ea382e3ca34b531b`（57,958,421 B），构建 23:06，之后只改测试文件；JVM 单测 **48 条 / 0 失败 / 0 跳过**（`logs/TEST-*.xml`、`logs/build-C7-tests.log`、`build-C8-tests.log`） |

## 4. 证据清单

```
screens/C-source/           本轮（最终 APK）实机帧
  C1-top.png                列表顶部：分组、长标题、更多按钮
  C2-hold.png               按住不拖：手柄 28dp、轨道出现、无气泡
  C3-drag-bubble.png        拖动中：手柄 28dp + 轨道 + 气泡「11月10日」
  C4-released.png           松手：回落 8dp，停在与拖动终点一致的位置
  C5-bottom.png             拖到底：手柄贴下 gap，末尾条目完整
  C5-bottom-drag.png        拖到底的过程帧
  C6-menu-sort.png          排序菜单 16/4（三行，行间 3dp，容器内边 8dp）
  C7-menu-single.png        单行菜单：全角 16 + 28dp 圆形图标底
  C8-more-press.png         更多按钮按压高亮（与 idle 差分得 32×44dp）
  C9-drag-bubble-dark.png   深色气泡
  C10-menu-sort-dark.png    深色菜单
  C11-row-idle/press.png    卡片按压前后宽度对照（.98）
screens/V0-baseline-*       改版前基线（同数据/同主题/同密度）
screens/A-stage-a-*、B-*    阶段 A / 阶段 B 历史证据（不覆盖、不删除）
hierarchy/C-*.xml           本轮层级：列表顶部/底部、排序菜单（浅/深）、单行菜单
logs/build-C5…C8.log        本轮四次构建
logs/TEST-*.xml             本轮 48 条单测结果
```

## 5. 已知限制（不许当成已验收）

- **帧级动画曲线没测出来**：`screenrecord` 在模拟器上按需出帧——12s 只出 11 帧；开 10× 动画时长后，1800ms 入场窗口里也只拿到约 8 帧，且气泡半透明期与列表底色混叠，无法稳定分割出每帧的 scale。所以本轮把入场曲线抽成纯函数（`bubbleScale` / `bubbleSlidePx`）用单测锁住端点与单调性，**不声称**做过逐帧拟合。宽度动画 200ms 同理，只有常量与代码路径为证。
- **无障碍节点会虚报尺寸**：`uiautomator` 里更多按钮报 `[859,638][985,764]`（126×126 = 48dp），那是平台最小触控目标膨胀，不是布局盒。真实盒用按压高亮差分证明是 84×116px（32×44dp）。任何"按层级 bounds 量尺寸"的结论都要先排除这一点。
- **滚动条位置仍是估算**：LazyColumn 只暴露可见项，`Estimator` 用中位数步长 + 按项缓存 + 前缀修正，越滚越准但不保证像素精确；不存在"知道全表真实高度"的实现。
- **未做真机**：全部在 `emulator-5554`/API 37 上验证，没有碰物理设备。
- **上一轮的 R2/R3/R4 部分证据来自上一构建**：这几处代码本轮未改，已在表中标注来源；本轮新增的只有滚动条、菜单、更多按钮、气泡。

## 6. 本轮操作中的误触（如实记录）

取证过程中出现过四次误操作，都发生在用旧坐标点新布局时，已全部恢复并核对数据未变：

1. 按旧坐标点 `(922,701)` 命中筛选 chip「#工作」（`匹配 7 条`），随后点「全部」恢复 `共 64 条`。
2. `input tap 400 1800` 误开笔记「团队周报」编辑器，返回列表后 `notes.json` SHA256 未变。
3. 连续两次返回键把应用退到桌面，一次在桌面触发了长按菜单；重新 `am start` 回到列表。
4. 在设置里切主题时误触「从备份导入」，打开了系统文件选择器并使 `documentsui` 出现 ANR；关闭后重开应用，设置项已复原为 `theme_mode=1`。

## 7. 复现

```bash
cd <worktree>
export JAVA_HOME=D:/Android/jbr
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n com.example.myapplicationfirstapp/.MainActivity
# 冷启动首帧在 debug 包上要等 20s 左右（ART 校验），别把闪屏当渲染结果
```

拖动取证不要用 `input swipe`（长时长不可靠，会提前抬手）：用 `input motionevent DOWN/MOVE/UP` 分步发送，再夹 `screencap`。
