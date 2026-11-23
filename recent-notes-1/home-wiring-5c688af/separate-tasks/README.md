# 分开执行的三支 Gradle（候选态工作树）

**目的**：确认候选改动是否让应用**独立可打包**，避免单测编译失败掩盖打包结论。三条命令分别调用、分别记退出码，不合并成一次 Gradle 调用。
**工作树**：`<evidence>/worktree`，detached `5c688af4d30f01f8939319cad47b2393a8ebaa99`，已应用第一批补丁与首页接线（`candidate.diff`，9 文件）。

## 1 未执行的两步（缺件）

| 计划步骤 | 状态 | 原因 |
|---|---|---|
| `python3 migrate_scrollbar_math.py` | **未执行** | 该文件在本机不存在（D:/ 根、D:/uhv1、Downloads、Desktop、工作区均搜过） |
| `python3 verify_home_junit.py app` | **未执行** | 同上 |

这两步是滚动条单测迁移与 24/11 项核对的入口，缺件即停，未自行补写替代实现。

## 2 三条命令与退出码

| # | 命令 | 退出码 | 日志 | 产物 |
|---|---|---|---|---|
| 1 | `:app:assembleDebug` | **0** | `b1.log` | `app-debug.apk` 57,469,448 B<br>`sha256 d85ae6f7aba6a3257a45361063e02c2eaba3a9705d8149d45f0b07ddb686719d` |
| 2 | `:app:testDebugUnitTest` | **1** | `b2.log` | 无（编译失败，无 `test-results/`） |
| 3 | `:app:assembleDebugAndroidTest` | **0** | `b3.log` | `app-debug-androidTest.apk` 1,254,265 B<br>`sha256 1c6c51a0ce9b5085d42914bfadc91dd37613d88d86ccc8b99020929c29660ba6` |

结论：**单测编译失败不影响应用打包，也不影响 androidTest APK 打包**。两条打包命令各自 `BUILD SUCCESSFUL`（59s / 21s）。

`b1` 的 APK 与上一轮（同一工作树、未迁移滚动条测试时单跑）逐字节相同，构建可复现。

第 2 条的失败仍是单一文件、单一原因：

```text
:app:compileDebugUnitTestKotlin FAILED
e: .../ui/NotesListScrollbarMathTest.kt:19:48 Unresolved reference 'ScrollbarEstimate'.
（e: 共 101 条，全部该文件；BUILD FAILED in 24s）
```

## 3 `git diff --check`：exit 2，属 CRLF 假阳性

`diff-check.txt` 共 19 条 `trailing whitespace`，**全部**在 `MainActivity.kt`（行 80、137–141、179–180、428–434、629–641）。

| 检查 | 结果 |
|---|---|
| `git ls-files --eol MainActivity.kt` | `i/crlf w/crlf`（历史 blob 即 CRLF） |
| `core.autocrlf` | `false` |
| 行尾实际字节 | `b'led = {\r'` / `b' = null\r'`，是 `\r` 被计为行尾空白 |
| 以空格结尾的行 | 0 |
| 对照 `NotesState.kt`（`i/lf`） | 未被标记 |

未做任何行尾修正——统一 EOL 会造成整文件翻动，属未授权改动。

## 4 未验证项（是"没跑"，不是"通过"）

- 首页模型 24 项、最近使用 11 项：零条执行，无 XML。
- 滚动条 11 项：零条执行，无 XML。它与最近使用那 11 项是两组不同用例（前者属 `NotesListScrollbarMathTest`，后者属 `RecentNoteUsageTest`）。

## 5 边界

未推送源码、未提交、未改 `main`、未安装 APK、未操作真机、未跑仪器化测试。主工作区 `D:/RECENT-NOTES-1-wt` 的未提交改动未被触碰。
