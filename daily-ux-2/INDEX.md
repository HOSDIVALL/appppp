# DAILY-UX-2 —— 点正文下方空白继续输入（评审证据）

- 任务：点击普通文本笔记正文下方空白区，即可继续输入正文。
- 基线：`ef4d98cef923247e2b4fc8bfe5d974d188272a02`（`ux/daily-ux-1` 固化提交）
- 工作分支：`ux/daily-ux-2`，独立工作区 `D:/SAVE-ORDER-UX-2-wt`（从基线新检出，未携带 `data/txn` 或任何未提交实验代码）
- 本轮**只提交证据**；源码留在工作分支工作区，等待固定 SHA 评审。未合并 `main`、未建 PR、未改动 `fix/backup-safe-1` / `ux/daily-ux-1`。

## 1. 先复现，再修改

模拟器：`Medium_Phone_API_37.0`（`emulator-5554`），API 37，1080×2400 @420dpi。
**只用模拟器**；真机（虽仍连接）本轮零访问、零命令。内容全部是本轮现敲的虚构文本（`alpha-/beta-/gamma-line`、`line1..18`、`extra20..34`）。

### 复现步骤（修改前 / 基线 APK）

1. 列表页点「+」新建 → 键盘自动弹出（UX-1 行为），输入三行文字。
2. 按 BACK 收起键盘（`mInputShown=false`，正文块仍保持 `focused=true`）。
3. 点正文最后一行下方空白（1080×2400 下取 `(540,1500)`；正文块实测边界 `[42,970][1038,1149]`）。
4. 再打开这条已有笔记，重复第 3 步。

### 复现结果（修改前）

| 步骤 | `mInputShown` | 焦点 | 结论 |
|---|---|---|---|
| 新建笔记输入后收键盘 | false | 正文块 focused | 正常 |
| **点空白（新建）** | **false** | 仍在原块，光标停在原位置 | **键盘不弹，无法继续输入** |
| 打开已有笔记 | false | 无 | 正常（不抢焦点） |
| **点空白（已有）** | **false** | 无 | **键盘不弹，无法继续输入** |
| 点正文文字（对照） | true | 落在点击处 | 正常的原有定位 |

- 有效点击区域：只有正文文字本身（`[42,970][1038,1149]`）。正文下方的空白（12dp 间隔 + 96dp 底部留白 + 内容不足一屏时的余量）整片无响应。
- 一个旁证：点空白**前后**两次 UI dump（`ui-dump/before-editor.xml` 与 `ui-dump/before-after-blank-tap.xml`）
  逐字节相同（sha256 均为 `55b87521…`），即这次点击没有改变任何节点——不是"点了没聚焦"，是"点了什么都没有发生"。
- 证据：`screenshots/before/1..4`、`ui-dump/before-editor.xml`、`ui-dump/before-after-blank-tap.xml`。

## 2. 改了什么

只改一处交互 + 一个纯判定函数 + 一处测试，**不动**数据模型、保存、自动保存去抖、返回逻辑。

| 文件 | 统计 |
|---|---|
| `app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt` | +71 −7 |
| `app/src/test/java/com/example/myapplicationfirstapp/ui/NoteEditorBlankTapPolicyTest.kt`（新增） | 81 行 |

实现要点：

1. **复用已有块聚焦通道**：`focusRequest` 由 `Int?` 改成 `BodyFocusRequest(index, caretAtEnd)`。
   既有调用（插入文字后聚焦、新建笔记自动聚焦）传 `caretAtEnd = false`，行为逐字保持；
   点空白传 `caretAtEnd = true`，`EditableBlock` 在要焦点之前先把选区移到文本末尾。
2. **只接管"最后一块之下"**：给滚动容器加 `detectTapGestures`，用两个 root 坐标比较
   （`viewportTopY + tapOffset.y` 与最后一块文字底边 `bodyBottomY`），
   只有 `tapY > bodyBottomY` 才算"点空白"。判定抽成
   `shouldFocusBodyOnBlankTap(checklist, lastTextIndex, tapY, bodyBottomY)`。
3. **不改原有光标定位**：点正文文字时 `tapY ≤ bodyBottomY`，手势直接放过，
   由 `BasicTextField` 自己定位光标——不依赖"子控件是否消费了事件"这种脆弱假设。
4. **不新增块、不插换行、不改正文**：只设置 `focusedBlock` 与 `focusRequest`。
5. **清单模式整条跳过**：`pointerInput` 里 `if (note.checklist) return@pointerInput`，手势检测器根本不装。
6. 坐标用普通 `FloatArray` 持有（不是 `mutableStateOf`），滚动时不会每帧触发重组。

## 3. 改后验证（同一台模拟器，同一套虚构内容）

| # | 检查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 空白笔记：收键盘后点正文空白 → 能继续输入 | 通过（`mInputShown` false→true，输入 `two` 落到 `onetwo` 末尾，6 字，未新增块/换行） | `screenshots/after/4-new-note-continue.png` |
| 2 | 多段笔记：点末尾空白 → 新输入落在正文末尾 | 通过。先把光标点进第一行中部并输入 `MM`（得到 `alpha-leMM-one`），再点空白输入 `ZZ`，结果落在**最后一行末尾** `gama-ine-threZZ` | `screenshots/after/3-caret-at-end.png` |
| 3 | 正文文字：直接点击仍按原有方式定位光标 | 通过（`alpha-leMM-one`，插入点就在点击位置，没有被拉到末尾） | 同上（同一次操作的前半段） |
| 4 | 滚动：拖动空白区域仍可滚动，不误触聚焦 | 通过（长内容下 swipe 前后正文块 `y` 由 231→168，`mInputShown` 保持 false） | `screenshots/after/5-long-content.png` |
| 5 | 已有笔记：刚打开不弹键盘；主动点空白后才弹 | 通过（打开时 false；点空白后 true，焦点回正文块） | `screenshots/after/1`、`after/2` |
| 6 | 清单与媒体控件：原有交互未被点击层遮挡 | 通过。清单：点空白 `mInputShown` 保持 false（未被接管），点清单条目正常弹键盘并聚焦该条；「添加图片」弹层正常出现（`从相册选择 / 拍照 / 作为附件 / 插入正文`） | 见 §5 记录 |

长内容场景另做一次：内容超一屏并滚到底后，点末尾空白 → 键盘弹出，输入 `TAIL` 落到正文末尾（`extra3TAIL`）。

## 4. 测试

| 项 | 结果 | 文件 |
|---|---|---|
| JVM 单测 | **9 / 0 / 0 / 0**（新增 `NoteEditorBlankTapPolicyTest` 5 例：空白生效、点文字不接管、清单不接管、无文字块不接管、边界取严） | `test-results/TEST-*UnitTest.xml`、`test-results/TEST-*PolicyTest.xml` |
| 仪器化（模拟器实跑） | **87 / 0 / 0 / 0** | `test-results/full-androidtest.xml`（`testcase` 计数 87，failure/error/skipped 各 0） |

**关于测试口径，必须说清楚**：

- 这 5 例是**判定单测**，只证明 `shouldFocusBodyOnBlankTap` 的分支，**不证明 UI 行为**。
  焦点是否真的落下、光标是否真的在末尾、键盘是否真的弹出，只能由 §3 的模拟器前后对照说明。
- **没有**为此新增或升级 Espresso。项目里既有的 UI 测试在 API 37 上受环境限制（`espresso-core 3.5.1` 反射
  `InputManager.getInstance()` 失败，UX-1 已移除并记录），本轮沿用同样处理：不写受环境阻塞的 UI 测试，
  改用模拟器手动对照 + UI dump + `dumpsys input_method` 取值。
- 因此：**本轮没有可自动复跑的 UI 回归用例**。这是已知缺口，不是我把它算成"已通过"。

## 5. 手工验证记录（可复跑）

命令与观测值见 `logs/verify-steps.md`。要点：

- 判定一律取 `adb shell dumpsys input_method | grep mInputShown` 与 `uiautomator dump` 的
  `focused` / `bounds` / `text`，不看截图"看起来对不对"。
- 全程 `-s emulator-5554` 显式指定设备。

## 6. 已验证 / 未验证

**已验证（模拟器）**

- 纯文本笔记：空白、单块多行、超一屏长内容；新建与打开已有两条路径。
- 点正文空白 → 聚焦最后一块 + 光标末尾 + 键盘弹出 + 不新增块/换行。
- 点正文文字 → 光标仍按点击位置落。
- 拖动 → 滚动、不误触。
- 清单模式：点空白不弹键盘；清单条目点击正常。
- 「添加图片」入口点击正常（弹层出现）。
- 打开已有笔记不抢焦点（UX-1 行为未回退）。

**未验证（如实登记）**

- **真机**：本轮明令禁止，未验证。系统输入法差异（尤其第三方输入法）、真实触控精度、刘海/手势条适配均未覆盖。
- **图片块 / 录音块在正文里时**点末尾空白：只验证了"无文字块返回 false"这条分支（单测），
  没有在真机上摆一条"文字 + 内联图片 + 文字"的笔记去点空白。理论上取的是最后一个文字块，但未实测。
- **清单 → 文本切换后立刻点空白**：只验证了切换本身（`- [ ] onetwo` 正确保留），没验证切换瞬间的坐标刷新。
- 深色主题、横屏、大字体、分屏/悬浮窗：未验证。
- 无障碍（TalkBack）下的空白点击语义：未验证。
- 自动化 UI 回归：受环境限制没有，见 §4。

## 7. 范围限制与已知风险

- 判定基于 root 坐标比较。若以后正文被挪进另一个滚动容器、或编辑页外面再套一层滚动/缩放，
  `viewportTopY` 与 `bodyBottomY` 的基准要重新核对——这是本轮实现里最容易失效的一处假设。
- `bodyBottomY` 只在最后一块文字的 `onGloballyPositioned` 里更新；首帧之前是 0，
  此时任何点击都会被判成"在正文下方"。实际影响可忽略（首帧即完成布局，且首帧没有用户点击），
  但审阅时值得知道这个初值。
- 未做"点空白时同时收起键盘"之类的额外行为；不点空白不会改变任何既有交互。
- 没有触碰 `notes.json` 的写入路径、`delay(500)` 自动保存、`BackHandler` 返回逻辑。

## 8. 目录

```
daily-ux-2/
├── INDEX.md                    本文件
├── diff/daily-ux-2.diff        相对 ef4d98c 的完整补丁（2 文件，含新增测试）
├── source/                     改动文件的完整副本（保持仓库内相对路径）
├── test-results/               单测 3 份 XML + 仪器化 1 份 XML
├── logs/gradle-tests.log       测试运行记录
├── screenshots/before|after/   模拟器前后对照截图
└── ui-dump/                    UI 层级 dump（before / after）
```

## 9. 复验建议

```
git checkout ef4d98c
git apply -c core.autocrlf=false daily-ux-2/diff/daily-ux-2.diff
```

补丁只新增/修改两个 `.kt` 文件，不含 APK、AAR、`local.properties`、真实笔记或设备序列号。
仓库里 `NoteEditorScreen.kt` 是 CRLF，本轮编辑保持了原行尾（1497 个 CRLF、0 个纯 LF），
补丁因此只有 78 行变动而不是整文件重写。

**本轮已做回放校验**：在 `ef4d98c` 上另建 detached 工作树，`git -c core.autocrlf=false apply` 该补丁，
两个文件应用后逐字节与 `source/` 及源码工作区一致（`MATCH` 2/2），临时工作树随后删除。
