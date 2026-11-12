# DAILY-UX-6 · 从搜索结果打开笔记：结束搜索输入，不把键盘带进编辑页

- 日期：2026-11-12
- 基线：`ede4f75fb25bc34a78d5acc89a863f1f45199e22`（`ux/daily-ux-4` 固化提交）
- 工作分支：`ux/daily-ux-6`（从基线新建的独立工作区，源码改动**未提交、未推送**）
- 设备：`emulator-5554`（API 37，1080×2400 @420dpi）。真机零访问。
- 数据：40 条虚构笔记，18 条含关键词 `LIME7`，与 DAILY-UX-5 同一份夹具（`fixture/notes.json`）。

## 结论

**已复现：从搜索结果打开笔记时，搜索框的焦点与键盘被一起带进编辑页，返回要按两次。**
**已修：只在「点已有笔记 → 打开」的交接处收键盘、清焦点，不动 query / 搜索面板 / 列表位置。**

## 一、先复现，再归因

基线包（`ede4f75`）上的实测：

| 步骤 | `mInputShown` | 编辑页内获焦控件 |
|---|---|---|
| 输入 `LIME7`，18 条匹配 | `true`（IME 顶边 1517） | 搜索框 `[63,520][1008,667]`，`focused=true` |
| 点第一条结果 UX5-01 | **`true`（键盘仍在）** | 标题、正文均 `focused=false` |

两种可能要先分开：

1. **搜索焦点残留**——搜索框仍在组合树里（编辑页是叠在列表上的抽屉，列表不离开组合），
   它继续持有输入连接，IME 因此不收起；
2. **编辑页主动申请焦点**——编辑页进来就把标题或正文聚焦。

判定实验：编辑页里没有任何输入框获焦时，用 `input text Q` 打一个字符，再返回看它落到哪。

- 编辑页标题、正文文本**都没有变**；
- 返回列表后 query 是 **`LIME7Q`**（匹配 0 条）。

字符落进了搜索框 ⇒ **是搜索焦点残留，不是编辑页主动申请焦点**。
另外第一次 `KEYCODE_BACK` 只把键盘收掉、人还在编辑页，第二次才离开 —— 与「键盘占着返回键」一致。

证据：`dumps/01-search.xml`、`dumps/02-open-r1.xml`、`dumps/03-focus-probe.xml`、`dumps/04-back-to-list.xml`，
`shots/02-open-r1.png`。

## 二、改动

只有一个文件，`+13 / −3`：

```
app/src/main/java/com/example/myapplicationfirstapp/ui/NotesListScreen.kt
```

在列表页已有的 `collapseSearch()`（关面板 + 收键盘 + 清焦点）旁边加一个**只管输入、不动面板**的交接函数：

```kotlin
fun openNote(id: String) {
    keyboard?.hide()
    focusManager.clearFocus()
    onOpen(id)
}
```

三处列表项点击（置顶分区、普通分区、无分区）从 `onOpen(note.id)` 改为 `openNote(note.id)`。

行为契约：

- 进入编辑页前就结束搜索输入 —— 不依赖编辑页在每次重组时强制隐藏键盘，也没有固定延时、轮询或反复抢焦点；
- **不调** `collapseSearch()`：`searchActive`、`query`、匹配结果、搜索面板开合、`LazyListState` 都不动；
- 编辑页对已有笔记本来就不申请焦点（`shouldAutoFocusBody(isNewNote, checklist)`，既有实现未改），
  所以清掉搜索焦点后，编辑页是稳定的阅读态；
- 新建笔记走的是导航栏 `+` → `createNoteNow()`，不经过这个交接函数，自动聚焦正文不受影响；
- 未改数据模型、保存、排序、搜索匹配规则、`BackHandler`；无新依赖、无导航框架。

`focusManager.clearFocus()` 不会反向关闭搜索面板：`SearchBarDefaults.InputField` 只在 `onFocusChanged`
**获焦**时回调 `onExpandedChange(true)`，失焦不回调（`material3` 1.4.0 源码 `SearchBar.kt` 对应实现），
`LaunchedEffect(expanded)` 的清理方向是 `!expanded && focused`。实测也确认返回后面板仍在（见 CHECKLOG §B）。

## 三、必要对照（每路径 3 次）

| 场景 | 期望 | 实测（3/3） |
|---|---|---|
| A 键盘显示时点搜索结果 | 编辑页稳定后键盘隐藏、无标题/正文自动获焦 | `mInputShown=false`；标题 `[42,231][1038,406]`、正文 `focused=false` |
| B A 场景中未编辑就按系统返回 | 一次离开编辑页；query / 匹配数 / 浏览位置保留；搜索框不自动弹键盘 | 一次返回即回列表；`LIME7` / `18 条` / 可见项 bounds 与打开前逐条相同；`mInputShown=false`，搜索框 `focused=false` |
| C 键盘已收起时点结果 | 行为正常 | 打开前 `mInputShown=false`；打开后键盘隐藏、无获焦；返回后状态一致 |
| D 编辑页点正文 / 点末尾空白 | 能重新输入，光标位置正常 | 点第一行：键盘 `true`、正文获焦，`Z` 插在点击处（`主题：LIME7 Z记录…`）；点块内末尾空白：`Y` 追加到正文最末（`…编号 01 结束。Y`）；两次 `KEYCODE_DEL` 复原 |
| E 新建笔记 | 自动聚焦正文 | 键盘 `true`，正文 `[42,938][1038,1064]` 获焦，标题未获焦；返回后 `notes.json` 字节数不变（空白新笔记被丢弃） |
| F 普通列表打开已有笔记 | 不新增抢焦点 / 闪键盘 / 导航异常 | 编辑器 `mInputShown=false`、无获焦；一次返回回列表 |

补充（非验收项）：返回后再点搜索框，键盘恢复、`query=LIME7`、18 条不变（`dumps/tr-refocus.xml`）。

## 四、证据目录

```
daily-ux-6/
├── INDEX.md                 本文件
├── CHECKLOG.md              逐轮记录：每步命令、前后数值、判定、未覆盖项
├── daily-ux-6.diff          相对 ede4f75 的完整补丁（48 行，单文件）
├── files/
│   └── NotesListScreen.kt   改动后的完整文件副本（37,104 B，blob a4d9542）
├── fixture/
│   └── notes.json           注入设备的虚构数据（40 条 / 18 条 LIME7），与 UX-5 同一份
├── logs/
│   ├── run-a1..a3.log       场景 A/B 每轮逐步日志（含每条 adb 命令与返回码）
│   ├── run-c1..c3.log       场景 C
│   ├── run-d1..d3.log       场景 D
│   ├── run-e1..e3.log       场景 E
│   ├── run-f1..f3.log       场景 F
│   ├── probe.py             取证工具（UI dump 解析 / IME 状态 / 截图）
│   ├── run.py               对照执行脚本（路径已替换为占位符）
│   ├── adb-commands.md      脱敏后的命令序列
│   └── notes-after.json     全部轮次结束后从设备取回的 notes.json
├── dumps/                   72 份 UI 层级（判定依据：bounds / focused / text）
├── shots/                   33 张截图（旁证）
└── test-xml/                本轮 JVM 单测原始 XML（3 suite / 9 条）
```

`SHA256SUMS` 覆盖除自身外全部文件，`sha256sum -c` 全过。

## 五、口径与边界

- **单测不冒充 UI 验证**：`test-xml/` 里 9 条只是焦点/空白点击的**策略函数**判定，
  本轮没有新增 Espresso（该 AVD 上 Espresso 受 API 37 限制），焦点与键盘结论全部来自设备实测。
- **不承诺动画逐帧无闪动**：只记录稳定态与过渡现象。设备端连拍间隔约 1 秒，
  拍到「第 1 帧仍是列表+键盘、第 2 帧起已是无键盘的编辑页」，无法逐帧验证过渡（`shots/tr3-*.png`）。
- **数据**：全程虚构。跑完后 40 条笔记的 id / 标题 / 正文 / 清单项与夹具**深比较完全相同**；
  唯一差异是 `ux5-note-01` 的 `updatedAt`（D 场景打字后删回、再保存所致），内容未变。
- **范围**：源码只在工作区，未提交、未推送功能分支；未合并 `main`、未建 PR；未改动其他基线分支；
  未在真机上做任何操作；证据里不含 APK、AAR、配置文件或真实数据。

## 六、复验

```bash
git -C <repo> worktree add <dir> -b ux/daily-ux-6 ede4f75fb25bc34a78d5acc89a863f1f45199e22
cd <dir> && git -c core.autocrlf=false apply --check /path/to/daily-ux-6.diff
# 或逐字节比对：a4d9542baa3dd193aa3ed955bae2a5203ebc828d
```

装包、输入关键词、点结果，然后：

```bash
adb -s emulator-5554 shell dumpsys input_method | grep mInputShown   # 期望 false
adb -s emulator-5554 shell dumpsys window | grep "type=ime"          # 期望 visible=false
```
