# BACKUP-SAFE-1A 评审材料（证据分支）

本分支只承载**评审副本**。它不修改源码分支，也不代表整改源码已经固化或验收通过。

- 源码基线：`d252ed3c2a9780c305b7a1be515186695a8e9c72`（`fix/backup-safe-1`）
- 本分支根目录的 `app/` 仍是基线内容；整改后的九个文件只以副本形式放在 `backup-safe-1a/rev1/files/` 与 `backup-safe-1a/rev2/files/` 下
- 实验工作区的未提交修改未被本分支改变，也未提交、未推送

**最新轮次：rev2**（`rev2/BACKUP-SAFE-1A-rev2.diff` + `rev2/NOTES.md` + `rev2/files/` + `logs/rev2/`）。rev0/rev1 原样保留。

**固化状态：已固化**。rev2 通过限定范围评审后，九文件以 `26a0de3cc80636718059c118d5af6e4c6f179fc0`（父 `d252ed3`）提交到 `fix/backup-safe-1` 并普通推送。详见 `freeze/FREEZE-1A-26a0de3.md`。固化不等于 1B 已实施。

## 1. 目录结构

```
backup-safe-1a/
  INDEX.md                          本文件
  MANIFEST.sha256                   全部材料文件的 SHA-256
  rev0/BACKUP-SAFE-1A.diff          第 1 轮：相对 d252ed3 的九文件完整补丁
  rev1/BACKUP-SAFE-1A-rev1.diff     第 2 轮：相对 rev0 的增量补丁（七文件）
  rev1/files/...                    第 2 轮结束时的九个完整源码副本（仓库相对路径）
  rev2/BACKUP-SAFE-1A-rev2.diff     第 3 轮：相对 rev1 的增量补丁（六文件）
  rev2/NOTES.md                     第 3 轮整改说明（五项 → 落点 → 用例 → 契约变化）
  rev2/files/...                    第 3 轮结束时的九个完整源码副本（仓库相对路径）
  logs/full-androidtest.xml         第 2 轮（rev1）全量仪器测试结果
  logs/compile.log                  第 2 轮（rev1）编译日志（已脱敏）
  logs/rev2/full-androidtest.xml    第 3 轮（rev2）全量仪器测试结果
  logs/rev2/compile.log             第 3 轮（rev2）编译日志（已脱敏）
  freeze/FREEZE-1A-26a0de3.md       源码固化记录（提交、blob、推送核对、测试口径、1B 待办）
```

## 2. 三轮材料的关系

| 轮次 | 材料 | 基准 | 范围 |
|---|---|---|---|
| rev0 | `rev0/BACKUP-SAFE-1A.diff` | `d252ed3` | 9 个文件，1,887 行，`+1821 −3` |
| rev1 | `rev1/BACKUP-SAFE-1A-rev1.diff` | rev0 的九文件状态 | 7 个文件，952 行，`+611 −45` |
| rev2 | `rev2/BACKUP-SAFE-1A-rev2.diff` | rev1 的九文件状态 | 6 个文件，852 行，`+545 −48` |

每个补丁都是**增量**，不能单独应用到 `d252ed3`。完整复现顺序：

```
git archive d252ed3 | tar -x        # 基线
patch -p1 < rev0/BACKUP-SAFE-1A.diff
patch -p1 < rev1/BACKUP-SAFE-1A-rev1.diff
patch -p1 < rev2/BACKUP-SAFE-1A-rev2.diff
```

`rev1/files/`、`rev2/files/` 分别是各步结束后的最终状态，可直接与下表逐文件比对，不必回放补丁。
`BackupNotesParser.kt` 与 `BackupImporterLimitsTest.kt` 从 rev1 起未再改动，所以 rev1 补丁只有 7 段、rev2 补丁只有 6 段。

应用方式用 `patch -p1`；若用 `git apply`，需加 `-c core.autocrlf=false`，否则产物会变成 CRLF、字节与原文件不符。

## 3. 九个文件

blob 为 `git hash-object --no-filters` 的结果，SHA-256 与对应轮次 `files/` 下的副本一致。

### 3.1 rev1 最终状态

| 文件（仓库相对路径） | blob | SHA-256 | 行数 | rev1 是否改动 |
|---|---|---|---|---|
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupFileOps.kt` | `5e4b82c09559d2d78fbc9035c35b2c42ed19779f` | `9042231d23691d0aca78b24a01a520987b3a4cb4de3bd057ae449949c526575e` | 89 | 是 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupImportModels.kt` | `c778aba89e26d6757bd63bf98a422d49b9de36bb` | `68bc225f9a38ed7288fc0c8a1539922b3eee5d1b3cce7a53428a707b0c9f09b0` | 217 | 是 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupImporter.kt` | `8ec2728da8c42123364c9e422b52e29a6eb594f5` | `3525bf2ae555e1165e508465db0e2b9d9f37dd4424fef323b723206ad7041a2e` | 623 | 是 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupNotesParser.kt` | `002aef08750daaa123745dd11e40fd2ca830988e` | `87f46e04c962b332865a72e4572198425dba57fa177436bce35db6379926c3ea` | 32 | 否 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/ImportTestSupport.kt` | `fe7243e10342c2c982b43c2a8da8f8ffa99649f3` | `88bf8dec94a37a0cf0504633af4b747f0547e772144fb706d2a5acb3c6b539db` | 306 | 是 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterPrepareTest.kt` | `586b7b456cf80956c9ae4dc4e693a069f73087ac` | `c3f8727c99f58b5a90045afb035a0553f81d7e2f86bf1c8076de322e96fe15f7` | 430 | 是 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterValidationTest.kt` | `f4cb748a4bcb4bf94b779a8addfbbb2bb05da976` | `29699b02d5a1bfb1d4247cfe07d841bca0f33f91857b1ead8dfb6b96e4a36352` | 346 | 是 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterLimitsTest.kt` | `84e2327011e2441bb3eedf8cca838191c634878e` | `d86d0271fecf1c62ad6ce1b11f5bde9da5d59f2d57527b0f1d7590c608716094` | 163 | 否 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterCancelTest.kt` | `69a083e62098694cd953a7eec8f1d081dd2f6891` | `81bde09240e856ab0c188e5ba3a1eaa41a7f4dee32037d6b30f3a47bc26bae92` | 201 | 是 |

### 3.2 rev2 最终状态

| 文件（仓库相对路径） | blob | SHA-256 | 行数 | rev2 是否改动 |
|---|---|---|---|---|
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupFileOps.kt` | `5e4b82c09559d2d78fbc9035c35b2c42ed19779f` | `9042231d23691d0aca78b24a01a520987b3a4cb4de3bd057ae449949c526575e` | 89 | 否 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupImportModels.kt` | `f9073a141784a11a7b27f931d326b0df1a2cfe13` | `736524225d27b9cfab54da6d4b540b5116722db33ea179b5c8a2f060584f07d7` | 285 | 是 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupImporter.kt` | `f93e98e890bbe878d36e6ee90c820d3d8b23ab7d` | `4ebe4e795fbc292dc7b262d10f4f1d1c9f4845f78713de1604809b44f4cc98a5` | 745 | 是 |
| `app/src/main/java/com/example/myapplicationfirstapp/data/BackupNotesParser.kt` | `002aef08750daaa123745dd11e40fd2ca830988e` | `87f46e04c962b332865a72e4572198425dba57fa177436bce35db6379926c3ea` | 32 | 否 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/ImportTestSupport.kt` | `2d0582ef11477f3940b33d86974dc7dce59cc2d1` | `957b8489015d2b8b3c11e89dc48a4530bb49bcd8246a66e1ff26b11451f486e4` | 364 | 是 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterPrepareTest.kt` | `cb59862d7e84516d8147f5ca8a1ccc180451457e` | `b64441c89f17d598f87034113314da75ae34fd20f3f38a21f5dd285425a22c95` | 586 | 是 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterValidationTest.kt` | `ce223ed2fe4f8bfb1ea7fa9c62f32204a4d6b90b` | `67cb6bd1fe36a874b9f8863ffe38f57695e46f8ca7eb194f84a879630e6be2e2` | 367 | 是 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterLimitsTest.kt` | `84e2327011e2441bb3eedf8cca838191c634878e` | `d86d0271fecf1c62ad6ce1b11f5bde9da5d59f2d57527b0f1d7590c608716094` | 163 | 否 |
| `app/src/androidTest/java/com/example/myapplicationfirstapp/data/BackupImporterCancelTest.kt` | `cdac4d23457608cc686bb56a1b41dcac3af2bb89` | `6a392f1af0274a2fcb4ee22c243c5ab6be449901c4de0a99e28854ec647748e6` | 273 | 是 |

## 4. 测试对应轮次

| 轮次 | 命令 | 结果 | 判据 |
|---|---|---|---|
| rev0 | `./gradlew :app:connectedDebugAndroidTest` | 62 条，0 失败 | 结果 XML 计数 |
| rev1 | 同上 | 80 条，0 失败 / 0 错误 / 0 跳过 | `logs/full-androidtest.xml` |
| rev2 针对性 | 同上 + `-Pandroid.testInstrumentationRunnerArguments.class=…PrepareTest,…ValidationTest,…CancelTest` | 57 条，0 失败 | 当次结果 XML |
| rev2 全量 | `./gradlew :app:connectedDebugAndroidTest` | 87 条，0 失败 / 0 错误 / 0 跳过 | `logs/rev2/full-androidtest.xml` |

- 设备：`Medium_Phone_API_37.0(AVD)`（`emulator-5554`，API 37）。未操作真机。
- `logs/full-androidtest.xml` 是 rev1 那一轮的原件，`tests=80`，时间戳 `2026-11-11T09:28:20`。
- `logs/rev2/full-androidtest.xml` 是 rev2 全量原件，`tests=87`，时间戳 `2026-11-11T13:12:42`。
- 80 条中属于 rev1 整改范围的是 `BackupImporter*` 共 59 条（Cancel 5 / Limits 9 / Prepare 21 / Validation 24）；其余 21 条为既有测试，用于证明无回归。
- rev2 的 87 条 = Prepare 27 + Cancel 6 + Validation 24 + Limits 9 + 既有 21。相对 rev1 新增 7 条（明细见 `rev2/NOTES.md` 第三节）。
- 计数一律取 XML 的 `tests/failures/errors/skipped`，不以 Gradle `BUILD SUCCESSFUL` 作依据。

## 5. 日志脱敏

`logs/compile.log` 与 `logs/rev2/compile.log` 在上传前把本机绝对路径替换成占位符，共两条规则：

- 指向本地实验工作区的 `file:///D:/<本地目录>/` 前缀 → `file:///<workspace>/`
- 用户目录前缀（`C:/Users/<用户名>/`）→ `<user-home>/`

本次编译日志只命中第一条；第二条为预防规则，实际未出现。除此之外未改动任何字节（任务名、告警、`BUILD SUCCESSFUL` 原样保留）。原始日志留在实验工作区本地，未上传。

两份 XML 未做任何改写（`grep` 本机路径命中数为 0）。

本分支不含：真实笔记、附件、录音、`files.tar`、APK、AAR、`local.properties`、凭据、设备序列号等隐私信息。

## 6. 状态边界

- 准备层可编译、可测，九文件已固化到 `fix/backup-safe-1@26a0de3`（见 §8），但仍**未接线**：`BackupImporter` / `PreparedBackup` / `PrepareResult` 在 `app/src/main` 中没有任何生产调用方，`NotesRepository`、`NotesState`、`MainActivity`、`NoteEditorScreen` 一行未改，旧导入路径未删除。
- 固化只处理九文件；评审当时的「未固化」状态已随 §8 结束，但**未进入 1B**：提交、代次、回滚、跨代清理、状态三态分离等仍只是方案。
- 1A 不宣称导入风险已关闭。

## 7. 后续轮次

rev3 及以后在本目录下新增独立子目录（`backup-safe-1a/rev3/`、`logs/rev3/`），保留 rev0/rev1/rev2 原样，不覆盖旧证据。每轮结束时 `MANIFEST.sha256` 全量重算。

## 8. 源码固化

| 项 | 值 |
|---|---|
| 源码分支 | `fix/backup-safe-1` |
| 提交 SHA | `26a0de3cc80636718059c118d5af6e4c6f179fc0` |
| 父提交 | `d252ed3c2a9780c305b7a1be515186695a8e9c72` |
| 提交说明 | `feat: prepare and validate backup imports without touching live data` |
| 范围 | 恰 9 个文件，`+2884 −3`，无额外文件 |
| 推送 | 普通推送，退出码 0，未 force |

提交的九文件 blob 与 §3.2 逐条相等（9/9），因此**内容与 rev2 测试对象一致**，测试引用原 87 项轮次（`logs/rev2/full-androidtest.xml`）；本轮为纯固化，**未重新运行测试**。

完整记录（固化前检查、暂存核对、远端独立核对、测试口径、1B 两项待办）见 `freeze/FREEZE-1A-26a0de3.md`。
