# DAILY-UX-6 · 逐轮检查记录

设备 `emulator-5554`，API 37，1080×2400 @420dpi。全部虚构数据。
`mInputShown` 取自 `dumpsys input_method`，`imeTop` 取自 `dumpsys window` 里 `type=ime` 的 visibleFrame 顶边（键盘显示时 = 1517）。
控件 `focused` 取自 `uiautomator dump`。

---

## 0. 基线与装包

- 工作区从 `ede4f75` 新建，`git rev-parse HEAD` = `ede4f75fb25bc34a78d5acc89a863f1f45199e22`，分支 `ux/daily-ux-6`，工作区干净（除未跟踪素材）。
- 复现阶段装的是基线自身构建的调试包；修复后用 `install -r` 覆盖安装，**未卸载**，`notes.json` md5 前后一致（`0315e59c…`）。
- 冷启动一律 `am start -S`。装包/冷启动后搜索框 `focused=false`、`mInputShown=false`、`共 40 条`。

---

## 1. 复现（基线 `ede4f75`）

`dumps/00-home.xml` → `01-search.xml` → `02-open-r1.xml` → `03-focus-probe.xml` → `04-back-to-list.xml`

| 步骤 | 观测 |
|---|---|
| 点搜索框 + `input text LIME7` | `mInputShown=true`，`mImeWindowVis=3`，`imeTop=1517`；`匹配 18 条笔记`；搜索框 `[63,520][1008,667]` `focused=true`，文本 `LIME7` |
| 点第一条结果（`UX5-01`，中心 540,1145） | 编辑页出现（返回箭头 `[12,85][138,211]`），**`mInputShown=true`（键盘没退）**；`UX5-01` 标题 `[42,231][1038,406]` `focused=false`，正文 `[42,970][1038,1346]` `focused=false` |

→ 编辑页没有申请焦点，但键盘在 ⇒ 键盘不是编辑页要来的。

## 2. 焦点归属判定（同一次会话继续）

在编辑页 `input text Q`（此时编辑页没有任何输入框获焦），然后 `KEYCODE_BACK`：

- `03-focus-probe.xml`：**仍在编辑页**（标题/正文原样），即第一次返回只收键盘；
- 再按一次 `KEYCODE_BACK` → `04-back-to-list.xml`：回到列表，query 变成 **`LIME7Q`**、`匹配 0 条`，搜索框 `focused=true`。

→ 字符落进搜索框 ⇒ **搜索焦点残留**；`Q` 之后返回要两次。

清理：点搜索框、`KEYCODE_DEL` 删掉 `Q`、再 `KEYCODE_BACK` 收键盘 → `05-restore.xml`（`LIME7` / 18 条 / 面板仍在）。

> 说明：这次判定实验改动了会话内的 query，属人为注入；数据未写盘。

## 3. 改动与编译

- 改动：`ui/NotesListScreen.kt` 增 `openNote(id)`（`keyboard?.hide()` + `focusManager.clearFocus()` + `onOpen(id)`），三处列表项 `onClick` 改走它。`+13 / −3`，单文件。
- `JAVA_HOME=D:/Android/jbr ./gradlew assembleDebug --offline` → `BUILD SUCCESSFUL in 1m 9s`，37 tasks executed（只有与本文件无关的 M3 `ListItem` 弃用告警）。
- 装包后行为即变化，说明装的确实是含改动的包。

## 4. 场景 A / B（每轮冷启动重来）

同样步骤：点搜索框 → `LIME7` → 点结果 → 等 2.4 s 抓 `*-3-editor` → 一次 `KEYCODE_BACK` → 抓 `*-4-back`。

| 轮 | 打开前 | 编辑页 | 一次返回后 |
|---|---|---|---|
| a1 | `mInputShown=true`，18 条，搜索框 focused | `mInputShown=false`，`imeTop=2274`；标题/正文 `focused=false` | 列表；`LIME7`；`匹配 18 条`；5 个可见项 bounds 与打开前逐条相同；`mInputShown=false`；搜索框 `focused=false` |
| a2 | 同 a1 | 同 a1（正文块 `[42,970][1038,1346]`） | 同 a1，5 项 bounds 逐条相同 |
| a3 | 先做一次 900 ms 慢滑（y 1300→1050）再点第 2 条 `UX5-03`：7 个可见项，`01[273,861]…13[273,2061]` | `mInputShown=false`；正文 `[42,970][1038,1283]`，均 `focused=false` | `LIME7`；18 条；**7 项 bounds 与打开前逐条相同**（含顶栏收起后的偏移）；`mInputShown=false` |

a3 额外的打字探针：编辑页里 `input text Q` 后 `KEYCODE_DEL`，再返回 —— query 仍是 **`LIME7`**（基线同一操作会变成 `LIME7Q`），编辑页标题/正文文本未变。
→ 搜索框确实已经不再接收输入。

a1/a2/a3 的返回都是**一次**离开编辑页。

## 5. 场景 C（键盘已收起时点结果）

步骤：搜索 → `KEYCODE_BACK` 收键盘（搜索态保留）→ 点结果 → 一次返回。

| 轮 | 打开前 | 编辑页 | 返回后 |
|---|---|---|---|
| c1 | `mInputShown=false`，搜索框 `focused=true`，18 条 | `mInputShown=false`，无获焦控件 | `LIME7` / 18 条 / 5 项 bounds 相同 |
| c2 | 同 c1 | 同 c1 | 同 c1 |
| c3 | 同 c1 | 同 c1 | 同 c1 |

→ 「键盘已收起但搜索框仍是焦点」这条路径也正常，且没有因此弹回键盘。

## 6. 场景 D（编辑页主动编辑）

起点与 A 相同（搜索 → 点结果，编辑页键盘已隐藏）。正文块 `[42,970][1038,1346]`。

1. 点正文第一行（x=300, y=1030）→ `mInputShown=true`、`imeTop=1517`、正文 `focused=true`、标题 `focused=false`；
2. `input text Z` → 正文变成 `主题：LIME7 Z记录（第 1 条）…`（插在点击处）；
3. `KEYCODE_DEL` → 复原；
4. 点正文块内末尾空白（y = 块底 −24 = 1322）→ `mInputShown=true`、正文仍 `focused=true`；
5. `input text Y` → 正文末尾变成 `…编号 01 结束。Y`（追加在最后，与 UX-2 的空白点击语义一致）；
6. `KEYCODE_DEL` → 复原；两次 `KEYCODE_BACK`（先收键盘、再离开）→ 列表 `LIME7` / 18 条。

d1 / d2 / d3 三轮结果逐条相同（`dumps/d{1,2,3}-*`、`shots/d{1,2,3}-1-body-focus.png`、`shots/d{1,2,3}-2-tail.png`）。

## 7. 场景 E（新建笔记自动聚焦）

点导航栏 `+`（954,2221）→ 新笔记编辑页。

| 轮 | 新笔记编辑页 | 返回后 |
|---|---|---|
| e1 | `mInputShown=true`，`imeTop=1517`；正文 `[42,938][1038,1064]` `focused=true`；标题 `[42,231][1038,406]` `focused=false` | `共 40 条`；`notes.json` 17,123 B → 17,123 B（空白新笔记被丢弃） |
| e2 | 同 e1 | 同 e1 |
| e3 | 同 e1 | 同 e1 |

→ 新建的自动聚焦没有受到交接函数影响（它本来就不走列表项的点击路径）。

## 8. 场景 F（普通列表打开已有笔记）

冷启动后在无搜索的列表点第二条 `UX5-02`。

| 轮 | 编辑页 | 返回 |
|---|---|---|
| f1 | `mInputShown=false`；标题/正文均 `focused=false` | 一次返回，`共 40 条` |
| f2 | 同 f1 | 同 f1 |
| f3 | 同 f1 | 同 f1 |

→ 没有新增抢焦点，也没有闪键盘。

## 9. 过渡观察（不承诺逐帧）

- 主机端连拍（每帧一次 `screencap` + `exec-out`，间隔约 0.9–1.1 s）：第一帧仍是「列表 + 键盘」，第二帧起是「编辑页、无键盘」。
- 设备端连拍（`for i in 1..5; do screencap`）同样得到：`tr3-1.png` = 列表+键盘，`tr3-2..5.png` = 编辑页无键盘（后四帧字节数相同）。
- 结论只到「稳定态键盘已收、编辑页无获焦」；`screencap` 在模拟器上约 1 s 一帧，**无法证明过渡期间无闪动**。

## 10. 单测

`JAVA_HOME=D:/Android/jbr ./gradlew testDebugUnitTest --offline` → `BUILD SUCCESSFUL in 28s`。

原始 XML（`test-xml/`）：

| suite | tests | failures | errors | skipped |
|---|---|---|---|---|
| `ExampleUnitTest` | 1 | 0 | 0 | 0 |
| `NoteEditorAutoFocusPolicyTest` | 3 | 0 | 0 | 0 |
| `NoteEditorBlankTapPolicyTest` | 5 | 0 | 0 | 0 |
| **合计** | **9** | **0** | **0** | **0** |

这三组只是 `shouldAutoFocusBody` / `shouldFocusBodyOnBlankTap` 等**策略函数**的判定，
**不能当作焦点或键盘的 UI 验证**；本轮也没有为此新增 Espresso（该 AVD 上 Espresso 受 API 37 限制），未跑无关的 `connectedDebugAndroidTest`。

## 11. 数据核对

- 跑完全部轮次后取回 `files/notes.json`（`logs/notes-after.json`）：40 条，18 条含 `LIME7`。
- 与 `fixture/notes.json` 做深比较（按 id 对齐 `title` / `content` / 清单项文本）：**完全相同，差异 0 条**。
- 唯一差异：`ux5-note-01` 的 `updatedAt`（D 场景打字后删回、离开时 `saveAndLeave` 重写了一次），内容未变。
- `notes.json` 已被应用重写为单行（17,123 B；原始夹具是缩进格式 22,405 B），这是既有写盘行为，不是本轮改动引入。

## 12. 未覆盖 / 已知限制

- 未在真机验证；未验证横屏、分屏、外接键盘。
- 未验证「编辑页里从搜索结果打开一条含图片/录音的笔记」的键盘过渡（本轮笔记均为纯文本）。
- 过渡动画未逐帧验证（见 §9）。
- 未验证清空 query 后再打开笔记（属 UX-5 覆盖范围）。
- `collapseSearch()` 的既有语义未动：返回列表后按系统返回，仍是先收搜索面板（本轮未改，也未回归测试）。

## 13. 作废的尝试（透明记录）

1. **D 首轮**：第一次跑 D 时用固定 y 坐标点「末尾空白」，键盘弹起后布局上移，落点落进了标题框，`Y` 被写进标题。已即时 `KEYCODE_DEL` 撤销并核对 `notes.json`（标题恢复为 `UX5-01 编号笔记`，无 `Z记录` / `Y记` 残留），随后把脚本改成**按正文块文本前缀定位、按块底动态计算落点**再重跑三轮。作废产物未入证据。
2. **E 首轮**：脚本原以为导航栏 `+` 会先弹菜单（`新建笔记 / 新建待办`），实际 `+` 直接新建笔记，因此第一版在找菜单文案时中断。该轮已通过两次返回丢弃空白笔记（`notes.json` 字节数不变），随后按「`+` 直接新建」重写并重跑三轮。
3. **过渡连拍首轮**：取图时 query 被重复追加成 `LIME7LIME7`（返回后 query 仍在，脚本又输入了一次），列表为空、没有打开任何笔记，该组连拍作废；清空后重拍（`tr3-*`）。
