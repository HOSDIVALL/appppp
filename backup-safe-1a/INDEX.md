# BACKUP-SAFE-1A 评审材料（证据分支）

本分支只承载**评审副本**。它不修改源码分支，也不代表整改源码已经固化或验收通过。

- 源码基线：`d252ed3c2a9780c305b7a1be515186695a8e9c72`（`fix/backup-safe-1`）
- 本分支根目录的 `app/` 仍是基线内容；整改后的九个文件只以副本形式放在 `backup-safe-1a/rev1/files/` 下
- 实验工作区（`D:/SAVE-ORDER-1-wt` 一类本地目录）的未提交修改未被本分支改变，也未提交、未推送

## 1. 目录结构

```
backup-safe-1a/
  INDEX.md                        本文件
  MANIFEST.sha256                 全部材料文件的 SHA-256
  rev0/BACKUP-SAFE-1A.diff        第 1 轮：相对 d252ed3 的九文件完整补丁
  rev1/BACKUP-SAFE-1A-rev1.diff   第 2 轮：相对 rev0 的增量补丁（七文件）
  rev1/files/...                  第 2 轮结束时的九个完整源码副本（仓库相对路径）
  logs/full-androidtest.xml       最终全量仪器测试结果（模拟器）
  logs/compile.log                主代码 + 测试代码编译日志（已脱敏）
```

## 2. 两轮材料的关系

| 轮次 | 材料 | 基准 | 范围 |
|---|---|---|---|
| rev0 | `rev0/BACKUP-SAFE-1A.diff` | `d252ed3` | 9 个文件，1,887 行，`+1821 −3` |
| rev1 | `rev1/BACKUP-SAFE-1A-rev1.diff` | rev0 的九文件状态 | 7 个文件，952 行，`+611 −45` |

rev1 是**增量**，不能单独应用到 `d252ed3`。完整复现顺序：

```
git archive d252ed3 | tar -x        # 基线
patch -p1 < rev0/BACKUP-SAFE-1A.diff
patch -p1 < rev1/BACKUP-SAFE-1A-rev1.diff
```

`rev1/files/` 是第二步结束后的最终状态，可直接与上表逐文件比对，不必回放补丁。
`BackupNotesParser.kt` 与 `BackupImporterLimitsTest.kt` 在 rev1 未改动，所以 rev1 补丁只有 7 段。

应用方式用 `patch -p1`；若用 `git apply`，需加 `-c core.autocrlf=false`，否则产物会变成 CRLF、字节与原文件不符。

## 3. 九个文件（rev1 最终状态）

blob 为 `git hash-object --no-filters` 的结果，SHA-256 与 `rev1/files/` 下的副本一致。

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

## 4. 测试对应轮次

| 轮次 | 命令 | 结果 | 判据 |
|---|---|---|---|
| rev0 | `./gradlew :app:connectedDebugAndroidTest` | 62 条，0 失败 | 结果 XML 计数 |
| rev1 | 同上 | 80 条，0 失败 / 0 错误 / 0 跳过 | `logs/full-androidtest.xml` |

- 设备：`Medium_Phone_API_37.0(AVD)`（`emulator-5554`，API 37）。未操作真机。
- `logs/full-androidtest.xml` 是 rev1 那一轮的原件，`tests=80`，时间戳 `2026-11-11T09:28:20`。
- 80 条中属于本轮整改范围的是 `BackupImporter*` 共 59 条（Cancel 5 / Limits 9 / Prepare 21 / Validation 24）；其余 21 条为既有测试，用于证明无回归。
- 计数一律取 XML 的 `tests/failures/errors/skipped`，不以 Gradle `BUILD SUCCESSFUL` 作依据。

## 5. 日志脱敏

`logs/compile.log` 在上传前把本机绝对路径替换成占位符，共两条规则：

- 指向本地实验工作区的 `file:///D:/<本地目录>/` 前缀 → `file:///<workspace>/`
- 用户目录前缀（`C:/Users/<用户名>/`）→ `<user-home>/`

本次编译日志只命中第一条；第二条为预防规则，实际未出现。除此之外未改动任何字节（任务名、告警、`BUILD SUCCESSFUL` 原样保留）。原始日志留在实验工作区本地，未上传。

本分支不含：真实笔记、附件、录音、`files.tar`、APK、AAR、`local.properties`、凭据、设备序列号等隐私信息。

## 6. 状态边界

- 准备层已可编译、可测，但**未接线**：`BackupImporter` / `PreparedBackup` / `PrepareResult` 在 `app/src/main` 中没有任何生产调用方，`NotesRepository`、`NotesState`、`MainActivity`、`NoteEditorScreen` 一行未改。
- 整改源码**未固化**：`fix/backup-safe-1` 仍是 `d252ed3`，`main` 未变，未提交、未推送源码。
- **未进入 1B**：提交、代次、回滚、跨代清理、状态三态分离等仍只是方案。
- 1A 不宣称导入风险已关闭。

## 7. 后续轮次

rev2 及以后在本目录下新增独立子目录（如 `backup-safe-1a/rev2/`），保留 rev0/rev1 原样，不覆盖旧证据。
