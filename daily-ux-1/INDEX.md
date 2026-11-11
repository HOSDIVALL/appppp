# DAILY-UX-1 / 新建笔记落焦点

从 `26a0de3` 干净的基线另开工作区与分支 `ux/daily-ux-1`，没有携带 BACKUP-SAFE-1B 的
`data/txn` 实验代码。只修一个已复现的问题，范围收在界面层。

## 1. 问题（复现过的）

**新建笔记后不能直接写：既没有焦点、也不弹键盘。**

复现路径（`emulator-5554`，API 37，1080×2400）：

1. 列表页点右下「+」；
2. 编辑页出现，标题与正文都是占位文字，**没有光标，键盘不弹**（`screenshots/01`）；
3. 点正文下方的大片空白：**没有任何反应**——里层是可滚动的 `Column`，不是可点击区域（`screenshots/02`）；
4. 必须精确点到那行「开始写点什么…」才能开始输入。

代价：每新建一条笔记都要多一次瞄准+点击，并等键盘动画。

对照组：新建**清单**（`createNoteNow(checklist = true)`）本来就会自动聚焦第一项
（`LaunchedEffect(note.id)` + `focusTodoId`）。也就是说「新建即可写」是既有意图，
只是文本笔记这一支漏了。

## 2. 修法（最小）

只做「新建时把光标放进正文第一块」，不碰标题、不碰列表、不动数据层。

- `MainActivity`：把「这一条是不是刚由 + 新建的」传给编辑页——
  `autoFocusBody = editingNote.id == newNoteId`。`newNoteId` 只在新建时置位、返回时清空，
  从列表点开的笔记永远匹配不上。
- `NoteEditorScreen`：
  - 新增可选参数 `autoFocusBody: Boolean = false`；
  - 新增判定函数 `shouldAutoFocusBody(isNewNote, checklist)`（`internal`，供单测）；
  - 在首帧后置 `focusRequest = 0`，复用**已有的**块聚焦通道
    （`EditableBlock` 里的 `LaunchedEffect(focusRequested)` 会 `requestFocus()` + `keyboard.show()`），
    没有新增第二套焦点机制。

为什么聚焦正文而不是标题：正文才是内容落点，且与新建清单「聚焦第一项」一致；
标题是单行字段，先聚焦容易把一整句话变成被省略号截断的标题。

不改的部分：标题行为、`imePadding`、`bringIntoViewRequester`、返回键、自动保存、
空白区不可点（这一条仍是现状，见 §6）。

## 3. 改动清单

| 文件 | 改动 |
|---|---|
| `app/src/main/java/…/MainActivity.kt` | +3 −1：编辑页调用处传 `autoFocusBody` |
| `app/src/main/java/…/ui/NoteEditorScreen.kt` | +21 −1：新参数、首帧后置焦点、`shouldAutoFocusBody` |
| `app/src/test/java/…/ui/NoteEditorAutoFocusPolicyTest.kt` | 新增，3 例判定分支单测 |

`diff/daily-ux-1.diff`：3 个文件、107 行、4738 B。回放校验见 `logs/replay-check.log`
（`git worktree add --detach 26a0de3` + `git apply` → 3 个文件 sha256 全一致）。

## 4. 修改前后对照

| | 修改前 | 修改后 |
|---|---|---|
| 新建后 | 无光标、无键盘 | 光标在正文第一块，键盘自动弹出（`03`） |
| 直接键入 | 无反应（没有焦点） | 文字直接落进正文（`04`） |
| 打开已有笔记 | 不聚焦 | **不聚焦**（`05`，`mInputShown=false`） |
| 点击空白区 | 无反应 | 无反应（未改变，见 §6） |

## 5. 测试

| 项 | 结果 | 文件 |
|---|---|---|
| 判定分支单测 | **3 / 0 / 0 / 0** | `test-results/unit-policy.xml` |
| 全量仪器化（1A 基线集） | **87 / 0 / 0 / 0** | `test-results/full-androidtest.xml` |

设备：`emulator-5554`（`Medium_Phone_API_37.0`，API 37），**未操作真机**。
最终字节（CRLF 修正后）重新构建并重跑，以上数字来自最终源码。

### 自动化 UI 测试为什么没有

想过一条「点 + → 断言正文获得焦点」的 Compose 测试，写了，跑不起来：
`espresso-core 3.5.1` 在 API 37 反射 `android.hardware.input.InputManager.getInstance()`
（已移除），`Espresso.onIdle()` 直接抛 `NoSuchMethodException`，两个用例全红，
红的原因不是被测行为。修它要升级 espresso——**改依赖超出本批范围**，所以撤掉这条测试
（连带撤掉为它加的 `testTag`），改为：

- 判定分支用 JVM 单测固定（挡住「新建不聚焦」和「已有笔记被抢焦点」两种回退）；
- 焦点与键盘的**实际效果**用前后截图对照，见 §4。

这不是把单测当 UI 测试用：单测只覆盖 `shouldAutoFocusBody` 这个判定，
**不证明焦点真的落下**；那部分证据是截图。

## 6. 明确没有做的事

- 没让正文下方的空白区变成「点击即聚焦」——那是第二处行为改动，本批只修一个。
- 没改标题不自动聚焦、没改返回键、没改自动保存去抖、没动列表/回收站/录音页。
- 没改数据模型、没改保存机制、没加依赖、没新增 UI 框架。
- 没合并 `main`（仍 `61dff28`）、没更新 `fix/backup-safe-1`（仍 `26a0de3`）、没建 PR。

## 7. 调试 APK

```
D:/SAVE-ORDER-UX-1-wt/app/build/outputs/apk/debug/app-debug.apk
size   57456915 B
sha256 c9c4e51ca50a5d104b6393de433a1e805c7868ac548cbe6b9e827ce6f68a0b07
```

**没有提交进本目录**：57 MB 的构建产物进 git 会把证据分支撑坏，且上一批的口径是
不上传构建产物。需要复现时用上面的路径，或按 §3 的 diff 自行 `assembleDebug`；
两者产出的 APK 只在签名时间戳上不同。

## 8. 目录

```
daily-ux-1/
├── INDEX.md                  本文件
├── SHA256SUMS                源码副本与 diff 的校验和
├── source/                   3 个改动文件的完整副本，保留仓库内相对路径
├── diff/daily-ux-1.diff      相对 26a0de3 的完整 diff（3 文件 / 107 行）
├── screenshots/              修改前 2 张、修改后 3 张（均为模拟器实拍）
├── test-results/             unit-policy.xml、full-androidtest.xml
└── logs/                     unit.log、full-androidtest.log、replay-check.log
```

## 9. 环境备注（复现用）

- 新工作区需要两份**未纳入版本控制**的本地文件才能构建：
  `local.properties`（`sdk.dir`）与 `app/libs/sherpa-onnx-2.0.1.aar`（49 MB，`.gitignore` 里
  `app/libs/*.aar`）。两者都从既有工作区拷入，未提交、未上传。
- 首次启动加载完成前，「+」不可点；脚本化复现要等列表出现再点。
