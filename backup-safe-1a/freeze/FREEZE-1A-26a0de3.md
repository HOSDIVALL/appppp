# BACKUP-SAFE-1A 源码固化记录

固化对象：九文件（准备与验证层），评审依据固定提交 `bd1d262c33cbcba34fbb09dc149a29fda9da0059`（`review/backup-safe-1a-evidence`）。

## 1. 提交

| 项 | 值 |
|---|---|
| 源码分支 | `fix/backup-safe-1` |
| 新提交 SHA | `26a0de3cc80636718059c118d5af6e4c6f179fc0` |
| 父提交 SHA | `d252ed3c2a9780c305b7a1be515186695a8e9c72` |
| 提交说明 | `feat: prepare and validate backup imports without touching live data` |
| 提交内容 | 9 个文件，`+2884 −3`（8 新增 / 1 修改） |
| 推送 | 普通推送，`d252ed3..26a0de3  fix/backup-safe-1 -> fix/backup-safe-1`，退出码 `0` |

推送前核对远端 `fix/backup-safe-1` 仍为 `d252ed3c2a9780c305b7a1be515186695a8e9c72`，符合普通推送条件；未 force、未换 remote、未用 API 改写源码引用、未推 `main`、未建 PR。

## 2. 固化前检查

| 项 | 结果 |
|---|---|
| 分支 | `fix/backup-safe-1` |
| HEAD | `d252ed3c2a9780c305b7a1be515186695a8e9c72` |
| `git status --short` | 1 改（`BackupNotesParser.kt`）+ 8 新增源码 + `logs/` 未跟踪 |
| `git diff --check` | 无输出，退出码 `0` |
| 已跟踪修改 | 仅 `app/src/main/java/…/data/BackupNotesParser.kt` |
| 未跟踪 | 八个新增源码 + `logs/`（仅 `logs/` 不进入提交） |

无异常，未执行 `reset` / `clean` / `rebase`。

逐路径 `git add`（未使用 `git add .` 或 `-A`），暂存后再次核对：九个路径、`git diff --cached --check` 干净、未暂存源码改动为 0、未跟踪仅剩 `logs/`。

## 3. 九文件 blob（提交后 HEAD 实测，与 rev2 清单逐条相等）

| 文件（仓库相对路径） | blob | 状态 |
|---|---|---|
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupFileOps.kt` | `5e4b82c09559d2d78fbc9035c35b2c42ed19779f` | 新增 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupImportModels.kt` | `f9073a141784a11a7b27f931d326b0df1a2cfe13` | 新增 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupImporter.kt` | `f93e98e890bbe878d36e6ee90c820d3d8b23ab7d` | 新增 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupNotesParser.kt` | `002aef08750daaa123745dd11e40fd2ca830988e` | 修改（`+12 −3`） |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/ImportTestSupport.kt` | `2d0582ef11477f3940b33d86974dc7dce59cc2d1` | 新增 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterPrepareTest.kt` | `cb59862d7e84516d8147f5ca8a1ccc180451457e` | 新增 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterValidationTest.kt` | `ce223ed2fe4f8bfb1ea7fa9c62f32204a4d6b90b` | 新增 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterLimitsTest.kt` | `84e2327011e2441bb3eedf8cca838191c634878e` | 新增 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterCancelTest.kt` | `cdac4d23457608cc686bb56a1b41dcac3af2bb89` | 新增 |

固化前逐文件比对三份值（工作区 `hash-object --no-filters`、INDEX §3.2 期望 blob、证据提交 `bd1d262:backup-safe-1a/rev2/files/…` 的 blob 与 SHA-256）：**9/9 一致，0 失配**。暂存后 index blob 与 HEAD blob 相同，说明 `git add` 的换行过滤未改变内容。

## 4. 推送后远端核对

从远端重新 `fetch` 到临时引用后独立比对（不采信本地推回结果）：

| 核对项 | 结果 |
|---|---|
| `ls-remote fix/backup-safe-1` | `26a0de3cc80636718059c118d5af6e4c6f179fc0` |
| 远端父提交 | `d252ed3c2a9780c305b7a1be515186695a8e9c72` |
| 远端提交说明 | `feat: prepare and validate backup imports without touching live data` |
| 远端九文件 blob | 与上表逐条相等（9/9） |
| `git diff --name-only d252ed3 26a0de3` | 恰好 9 个路径，无任何额外文件（无日志、XML、APK/AAR、配置、隐私） |
| `main` | 仍 `61dff28e4041aa5a8d9376ba1da872a87a009301` |
| `review/backup-safe-1a-evidence` | 仍 `bd1d262c33cbcba34fbb09dc149a29fda9da0059` |
| 提交后本地 `git status` | 仅 `?? logs/`（未跟踪，未提交） |

无 hook 改写：提交后 HEAD blob 与暂存 index blob 相同，提交内文件数恰为 9。

## 5. 测试口径

**提交的九文件内容与 rev2 测试对象一致，引用原 87 项测试轮次。**

- 原轮次：`logs/rev2/full-androidtest.xml`，`tests=87 failures=0 errors=0 skipped=0`，时间戳 `2026-11-11T13:12:42`，设备 `Medium_Phone_API_37.0`（`emulator-5554`，API 37）。
- 一致性的依据是 blob 相等：本轮提交的九个 blob 与 rev2 测试所用文件逐条相同，无任何字节改动。
- **本轮为纯固化，未重新运行测试**，不得表述为「新提交已重新测试通过」。

## 6. 边界

- 仍未接线：`BackupImporter` / `PreparedBackup` / `PrepareResult` 在 `app/src/main` 中无生产调用方；`NotesRepository`、`NotesState`、`MainActivity`、`NoteEditorScreen` 未改；旧导入路径未删除。
- 未进入 1B：提交、代次、回滚、跨代清理、三态分离仍只是方案。
- 未操作真机、未接 UI、未新增功能。
- 1A 不宣称导入风险已关闭。

## 7. 交给 1B 的两项待办（本批未处理）

1. **默认 ID / 时间戳固定**：`noteFromJson` 对缺失 `id`、`createdAt`、`updatedAt` 会按读取时刻或随机值补齐，同一份材料两次提交会产生不同字节。1B 必须在提交前把这三项一次性固定（写入载荷即固化），否则「重试同一次导入」无法保证幂等，也无法用摘要判定「还是同一份材料」。
2. **清理返回边界**：`discard` / `cleanup` 目前把「删除成功 / 删除失败 / 已不存在」压缩成 `Boolean` 与残留列表。1B 需要区分三者：提交成功后清理失败不能当作提交失败回滚，回滚失败又必须保留材料并停写。返回值的语义边界要在 1B 的提交协议里重新定义，不能直接沿用 1A 的 `Boolean`。

两项均不修改本批九文件，留给 1B 设计时处理。
