# SAVE-ORDER-1 验证结果（回传包）

执行方：本地 Android 工程维护方（在独立 worktree 内验证，未改动业务代码）
日期：2026-11-11 13:16 GMT+8
对象：`SAVE-ORDER-1-review-bundle.zip`（7,282 B / 5 条目）
基线：`fix/backup-safe-1` @ `fcac718c8c20f869f0a2836defb7c62ef211068f`
补丁作者预期基线：`fcac718c8c20f869f0a2836defb7c62ef211068f`（已按 C 路径同步后一致）

---

## 一句话结论

补丁在 `fcac718` 上成功应用，`git diff --check` 干净，两个 Kotlin 编译任务通过，
**11 项仪器测试全通过（5 + 6，0 失败 0 错误 0 跳过）**。
未提交、未推送、未合并 `main`、未安装到任何真机。

## 结果表

| 项 | 结果 | 退出码 |
|---|---|---|
| 远端核对（origin / 两个分支 SHA） | 与预期一致 | 0 |
| `git fetch origin refs/heads/fix/backup-safe-1:…` | 新增远端跟踪分支 | 0 |
| `git cat-file -e fcac718…^{commit}` | 存在 | 0 |
| `git merge-base --is-ancestor 61dff28… fcac718…` | 是祖先 | 0 |
| worktree `merge --ff-only` | `61dff28..fcac718` Fast-forward | 0 |
| dry-run（第二轮） | `Validated files: 4`，未改文件 | 0 |
| `--apply` | 4 个文件写入 worktree | 0 |
| `git diff --check` | 无空白错误 | 0 |
| `:app:compileDebugKotlin` + `:app:compileDebugAndroidTestKotlin` | `BUILD SUCCESSFUL in 1m 50s`，0 error | 0 |
| `NotesWriteCoordinatorTest`（5 项） | 5/5 PASS | 0 |
| `BackupNotesParserTest`（6 项） | 6/6 PASS | 0 |

测试设备：`emulator-5554`，AVD `Medium_Phone_API_37.0`，Android 17 / API 37，
`sdk_gphone64_x86_64`，1080×2400 @420dpi。全程无真机连接。

## 需要你知道的两点偏离

1. **首轮 `connectedDebugAndroidTest` 用了 `--offline` 而失败**（退出码 1），原因是本机 Gradle 缓存缺
   `com.android.tools.utp:gradle-work-action:32.5.0`。去掉 `--offline` 让 AGP 自取该构件后通过。
   这是 AGP 测试工具链，`app/build.gradle.kts` 与 `gradle/libs.versions.toml` 的 `git diff` 为空，**未改任何声明版本**。
2. **worktree 缺两个被 `.gitignore` 排除的构建前提**：`app/libs/sherpa-onnx-2.0.1.aar`（从主工程复制，
   SHA-256 前 16 位 `a8d09d1d32f3eb85`，与主工程同一文件）与 `local.properties`（SDK 路径）。
   未删任何功能绕过构建，未降级任何依赖。

## 未验证的部分（如实标注，不当作通过）

进程被杀后的保存丢失、`rename` / `fd.sync` 在真实故障下的行为、无目录 fsync 的耐久性、
`replace()` 是否已被业务调用、保存失败的用户可见反馈。11 项测试只覆盖协调器的顺序语义与 JSON 解析契约，
不构成断电/强杀下的端到端安全性证明。

---

## 文件索引

**建议先读这一个**（报告 + diff + 两个新增文件 + 两份 JUnit XML + 三份日志，全部内联）：

- [`ALL-IN-ONE.md`](ALL-IN-ONE.md) — 34.7 KB

**分文件**

| 文件 | 内容 |
|---|---|
| [`VERIFICATION-REPORT.md`](VERIFICATION-REPORT.md) | 完整验证报告（10 节） |
| [`evidence/applied.diff`](evidence/applied.diff) | 应用后完整 diff（2 files, +36 −12） |
| [`evidence/NotesWriteCoordinator.kt`](evidence/NotesWriteCoordinator.kt) | 新增协调器（1,819 B） |
| [`evidence/NotesWriteCoordinatorTest.kt`](evidence/NotesWriteCoordinatorTest.kt) | 新增测试（2,719 B，5 个 `@Test`） |
| [`evidence/junit-NotesWriteCoordinatorTest.xml`](evidence/junit-NotesWriteCoordinatorTest.xml) | JUnit XML，tests=5 failures=0 errors=0 |
| [`evidence/junit-BackupNotesParserTest.xml`](evidence/junit-BackupNotesParserTest.xml) | JUnit XML，tests=6 failures=0 errors=0 |
| [`evidence/compile.log`](evidence/compile.log) | 编译日志 |
| [`evidence/run1-coordinator.log`](evidence/run1-coordinator.log) | 第一组测试执行日志 |
| [`evidence/run2-parser.log`](evidence/run2-parser.log) | 第二组测试执行日志 |

## 原始地址（如需直接抓取）

```
https://raw.githubusercontent.com/HOSDIVALL/appppp/review/save-order-1/reviews/SAVE-ORDER-1/ALL-IN-ONE.md
https://raw.githubusercontent.com/HOSDIVALL/appppp/review/save-order-1/reviews/SAVE-ORDER-1/VERIFICATION-REPORT.md
https://raw.githubusercontent.com/HOSDIVALL/appppp/review/save-order-1/reviews/SAVE-ORDER-1/evidence/applied.diff
https://raw.githubusercontent.com/HOSDIVALL/appppp/review/save-order-1/reviews/SAVE-ORDER-1/evidence/NotesWriteCoordinator.kt
https://raw.githubusercontent.com/HOSDIVALL/appppp/review/save-order-1/reviews/SAVE-ORDER-1/evidence/NotesWriteCoordinatorTest.kt
https://raw.githubusercontent.com/HOSDIVALL/appppp/review/save-order-1/reviews/SAVE-ORDER-1/evidence/junit-NotesWriteCoordinatorTest.xml
https://raw.githubusercontent.com/HOSDIVALL/appppp/review/save-order-1/reviews/SAVE-ORDER-1/evidence/junit-BackupNotesParserTest.xml
https://raw.githubusercontent.com/HOSDIVALL/appppp/review/save-order-1/reviews/SAVE-ORDER-1/evidence/compile.log
```

整包下载（含上述全部）：

```
https://codeload.github.com/HOSDIVALL/appppp/zip/refs/heads/review/save-order-1
```

## 仓库状态

| 位置 | 分支 | HEAD | 状态 |
|---|---|---|---|
| 主工程 | `main` | `61dff28` | 未改动 |
| 集成包 worktree | `fix/backup-safe-1` | `fcac718` | 2 modified + 2 untracked，**未提交** |
| 本回传包 | `review/save-order-1` | 见本目录 | 仅文档，不含业务代码改动 |

`fix/backup-safe-1` 与 `main` 均未被写入；本分支只增加 `reviews/SAVE-ORDER-1/` 一个目录。
