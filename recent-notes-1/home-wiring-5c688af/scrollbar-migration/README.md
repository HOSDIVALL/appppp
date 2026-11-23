# 滚动条单测迁移与三组 JUnit 核对（首个阻塞已解除）

**范围**：在已应用首页候选的 `5c688af` 分离工作树中，执行 `7d6a001` 提供的迁移与核对脚本，再分别重跑两支打包。
**工作树**：`<evidence>/worktree`，detached `5c688af4d30f01f8939319cad47b2393a8ebaa99`。
**取件**：`git show 7d6a001:recent-notes-1/home-wiring-5c688af/harness/{migrate_scrollbar_math.py,verify_home_junit.py}` 到临时目录；目录内**无** `extract_scrollbar_math.py`。含 7 项旧断言的总脚本本轮**未重跑**。

## 1 退出码

| 步骤 | 退出码 | 关键输出 |
|---|---|---|
| `migrate_scrollbar_math.py` | **0** | `Scrollbar migration applied; tests not run.` |
| `:app:testDebugUnitTest` | **0** | `BUILD SUCCESSFUL in 42s`（判据另取 XML） |
| `verify_home_junit.py app` | **0** | `Verified 24 home model, 11 recent usage, 11 scrollbar cases.` |
| `:app:assembleDebug` | **0** | APK 58,081,932 B |
| `:app:assembleDebugAndroidTest` | **0** | APK 1,254,265 B |

迁移未报任何源码锚点错误；Gradle 未产生任何 `e:`。

## 2 迁移改了什么（`migration.diff`，536 行）

| 文件 | 变化 |
|---|---|
| `ui/ScrollbarMath.kt` | 新增，2450 B：`AxisTracker` 提为 `internal`，配 8 个纯函数（travel / touchProgress / handleY / target / smooth / glyph / scale / slide） |
| `ui/NotesListScrollbar.kt` | 改为调用上述函数，删去 `LazyListItemInfo` 引用 |
| `test/.../NotesListScrollbarMathTest.kt` | 按新 API 整体重写，11 条 |

`candidate-full.diff`（1,852 行 / 12 文件）= 第一批 + 首页接线 + 本轮迁移的合并结果，可直接整份应用；两份 diff 均通过 `git apply --reverse --check`。

## 3 JUnit 原始计数（`junit/` 下 9 份 XML）

| 类 | tests | F | E | S |
|---|---|---|---|---|
| `NotesHomeListModelTest` | 24 | 0 | 0 | 0 |
| `RecentNoteUsageTest` | 11 | 0 | 0 | 0 |
| `NotesListScrollbarMathTest` | 11 | 0 | 0 | 0 |
| `NotesListGroupShapeTest` | 7 | 0 | 0 | 0 |
| `NotesListActionsTest` | 6 | 0 | 0 | 0 |
| `ListVisualTokensTest` | 5 | 0 | 0 | 0 |
| `NoteEditorBlankTapPolicyTest` | 5 | 0 | 0 | 0 |
| `NoteEditorAutoFocusPolicyTest` | 3 | 0 | 0 | 0 |
| `ExampleUnitTest` | 1 | 0 | 0 | 0 |
| **合计** | **73** | **0** | **0** | **0** |

三组目标全部命中：首页模型 24、最近使用 11、滚动条 11。后两者是不同类、不同用例，不重叠。

## 4 打包（迁移后，替换上一轮旧 APK）

| 产物 | 大小 | sha256 |
|---|---|---|
| `app-debug.apk` | 58,081,932 B | `354fedc000904b9345c21a5d69aa0198decc1027d785d68b4f574bcc6711d526` |
| `app-debug-androidTest.apk` | 1,254,265 B | `1c6c51a0ce9b5085d42914bfadc91dd37613d88d86ccc8b99020929c29660ba6` |

- APK 内部核对：11 个 dex，含 `ScrollbarMath` 符号，**不含** `ScrollbarEstimate`（旧符号已彻底消失）。
- androidTest APK 与迁移前逐字节相同：本轮没有改任何 androidTest 源文件，该任务仅 1 个任务实际执行、50 个 up-to-date。应用侧变化体现在 `app-debug.apk`；跑 connected 需同时安装两个新 APK，本轮按令未安装。
- 迁移前的 `d85ae6f7…` 与旧 androidTest 从此作废，不得作为修复后证据。
- 体积较迁移前 +612,484 B，未深入归因（dex 重新分组），功能符号已核对。

## 5 `git diff --check`：仍有 20 条，全在 `MainActivity.kt`

`diff-check.txt`：20 条 `trailing whitespace`，全部集中在 `MainActivity.kt`；迁移触及的两个文件 `i/lf w/lf`，未被标记。根因仍是该文件 blob 为 CRLF（`core.autocrlf=false`），实测 0 行以空格结尾。**按要求只记录，未改整文件行尾。**

## 6 边界

未推送源码、未提交、未改 `main`、未安装 APK、未操作真机、未跑仪器化测试；`D:/RECENT-NOTES-1-wt` 的未提交改动未被触碰。
