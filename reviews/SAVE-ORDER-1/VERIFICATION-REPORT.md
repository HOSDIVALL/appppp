# SAVE-ORDER-1 验证报告（实验 worktree）

日期：2026-11-11 13:16（GMT+8）
发布：本文件由执行方在独立 worktree 内完成，未修改任何业务代码
包：`D:\SAVE-ORDER-1-review-bundle.zip`（7,282 B / 5 条目 / `unzip -t` 无错）
解压：`D:\SAVE-ORDER-1\`
实验 worktree：`D:\SAVE-ORDER-1-wt`
主工程：`D:\myappappappappapp`（未触碰）

## 结论

补丁在 `fcac718` 基线上成功应用，编译通过，**11 项仪器测试全部通过（0 失败 0 错误 0 跳过）**。
未提交、未推送、未合并 main、未安装到真机。

## 1. 远端与基线同步

| 命令 | 退出码 | 输出 |
|---|---|---|
| `git remote -v` | 0 | `origin https://github.com/HOSDIVALL/appppp.git`（fetch/push 同） |
| `git ls-remote --heads origin main fix/backup-safe-1` | 0 | `fcac718c8c20f869f0a2836defb7c62ef211068f refs/heads/fix/backup-safe-1`；`61dff28e4041aa5a8d9376ba1da872a87a009301 refs/heads/main` |
| `git fetch origin refs/heads/fix/backup-safe-1:refs/remotes/origin/fix/backup-safe-1` | 0 | `* [new branch] fix/backup-safe-1 -> origin/fix/backup-safe-1` |
| `git cat-file -e fcac718…^{commit}` | 0 | 存在 |
| `git show --stat --oneline fcac718…` | 0 | `test: add explicit backup JSON parsing and regression cases`；`BackupNotesParserTest.kt +63`、`BackupNotesParser.kt +23`，2 files / 86 insertions |
| `git merge-base --is-ancestor 61dff28… fcac718…` | 0 | 是祖先（整改分支建立在基线之上） |

远端地址与两个分支 SHA 均与预期一致，未更换 remote，未重置任何工作区。

## 2. worktree 快进

| 命令 | 退出码 | 输出 |
|---|---|---|
| `git -C D:/SAVE-ORDER-1-wt status --short` | 0 | 空（快进前干净） |
| `git -C D:/SAVE-ORDER-1-wt merge --ff-only origin/fix/backup-safe-1` | 0 | `Updating 61dff28..fcac718` / `Fast-forward` / 2 files changed, 86 insertions(+) |

最终 HEAD：`fcac718c8c20f869f0a2836defb7c62ef211068f`
`BackupNotesParser.kt`（861 B）、`BackupNotesParserTest.kt`（1,960 B，6 个 `@Test`）均存在。

## 3. dry-run 复跑

```
python apply_save_order.py D:/SAVE-ORDER-1-wt
Validated files: 4
Dry run only; no files changed. Review README before using --apply.
退出码 0
```

前置条件复核：
- `NotesRepository.kt` blob = `9edd7200777a937cef15c3a3a256e02b684059c6`（与 payload 一致）
- `NotesState.kt` blob = `0ab7f3f88820f37f8a582e381467d276a2c490b2`（与 payload 一致）
- `NotesWriteCoordinator.kt` / `NotesWriteCoordinatorTest.kt` 均不存在

## 4. 应用与差异检查

```
python apply_save_order.py D:/SAVE-ORDER-1-wt --apply
Validated files: 4
Applied to worktree only. No commit, push, build, or installation performed.
退出码 0
```

`git diff --check` 退出码 **0**（无空白错误）。
`git diff --stat`：2 files changed, 36 insertions(+), 12 deletions(-)。
完整 diff 见 `logs/applied.diff`；两个新增文件副本见 `logs/new-*.kt`。

新增文件（未跟踪）：
- `app/src/main/java/com/example/myapplicationfirstapp/data/NotesWriteCoordinator.kt`（1,819 B）
- `app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesWriteCoordinatorTest.kt`（2,719 B，5 个 `@Test`）

## 5. 编译

```
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --offline
```

- 退出码 **0**，`BUILD SUCCESSFUL in 1m 50s`，25 tasks executed
- `e:` 错误行 0；`w:` 警告 12 行，全部为既有废弃 API（`rememberModalBottomSheetState`、旧 `ListItem(headlineContent)`、
  `SearchBarDefaults.InputField`、`Icons.Filled.Sort/NoteAdd`、`RecorderState.stopPlayback`），与本次补丁无关
- 日志：`logs/compile.log`

## 6. 仪器测试

设备：`emulator-5554`，AVD `Medium_Phone_API_37.0`，API 37 / Android 17，
model `sdk_gphone64_x86_64`，分辨率 1080×2400，密度 420。无真机连接，未安装到任何真机。

### 6.1 NotesWriteCoordinatorTest（5 项）

```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplicationfirstapp.data.NotesWriteCoordinatorTest
BUILD SUCCESSFUL in 59s  退出码 0
```

XML：`tests=5 failures=0 errors=0 skipped=0`

| 测试 | 结果 | 耗时 |
|---|---|---|
| olderCannotOverwriteNewer | PASS | 0.005s |
| failedWriteCanRetry | PASS | 0.006s |
| replacementInvalidatesWaitingOldSave | PASS | 0.035s |
| failedReplacementPreservesPendingTicket | PASS | 0.007s |
| foreignTicketIsRejected | PASS | 0.074s |

### 6.2 BackupNotesParserTest（6 项）

```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplicationfirstapp.data.BackupNotesParserTest
BUILD SUCCESSFUL in 23s  退出码 0
```

XML：`tests=6 failures=0 errors=0 skipped=0`

| 测试 | 结果 | 耗时 |
|---|---|---|
| acceptsEmptyArray | PASS | 0.001s |
| roundTripsCurrentNote | PASS | 0.012s |
| preservesLegacyOptionalDefaults | PASS | 0.028s |
| rejectsMalformedInput | PASS | 0.005s |
| rejectsWrongRootAndInvalidElements | PASS | 0.037s |
| rejectsTrailingContentButAcceptsWhitespace | PASS | 0.005s |

XML 归档：`logs/xml/run1-coordinator.xml`、`logs/xml/run2-parser.xml`

## 7. 环境改动（仅构建前提，不改版本）

- `app/libs/sherpa-onnx-2.0.1.aar`：从主工程复制，SHA-256 前 16 位 `a8d09d1d32f3eb85`，与主工程**同一文件**
- `local.properties`：从主工程复制（`sdk.dir` 指向本机 SDK），被 `.gitignore` 排除
- `app/build.gradle.kts`、`gradle/libs.versions.toml`：`git diff` 为空，**零改动**
- 唯一联网动作：`connectedDebugAndroidTest` 需要 `com.android.tools.utp:gradle-work-action:32.5.0`，
  本地缓存缺失，故该步去掉 `--offline` 由 AGP 自取；该构件为测试工具链，不改任何声明版本

## 8. 未执行 / 失败项

- **无失败项**。首轮 `connectedDebugAndroidTest` 曾因 `--offline` 缺 UTP 构件失败（退出码 1，属环境缺件），
  去掉 `--offline` 后成功；代码未因此改动。
- 未执行：`assembleRelease`、lint、真机安装、任何 `git commit` / `push` / `merge`。
- 未验证（超出本包范围，如实标注）：进程被杀死后的保存丢失、`rename`/`fd.sync` 在真实故障下的行为、
  无目录 fsync 的耐久性、`replace()` 与导入流程的接入、保存失败的用户可见反馈。

## 9. 工作区状态

| 位置 | 分支 | HEAD | 改动 |
|---|---|---|---|
| 主工程 `D:\myappappappappapp` | `main` | `61dff28` | 0 |
| 实验 worktree `D:\SAVE-ORDER-1-wt` | `fix/backup-safe-1` | `fcac718` | 2 modified + 2 untracked（未提交） |

## 10. 证据文件

```
D:\SAVE-ORDER-1\
├─ logs\applied.diff                     应用后的完整 diff（modified 两文件）
├─ logs\new-NotesWriteCoordinator.kt     新增文件副本
├─ logs\new-NotesWriteCoordinatorTest.kt 新增文件副本
├─ logs\compile.log                      编译日志
├─ logs\test-coordinator.log             第一组测试日志
├─ logs\test-parser.log                  第二组测试日志
├─ logs\xml\run1-coordinator.xml         JUnit XML（5 项）
└─ logs\xml\run2-parser.xml              JUnit XML（6 项）
```
