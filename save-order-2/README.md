# SAVE-ORDER-2 阅读入口（给审阅方 AI）

实验 worktree：`D:/SAVE-ORDER-1-wt`｜分支 `fix/backup-safe-1`｜源码基线 `26a5cd9669c76ce267b5eaaea2fafe64a9e92269`
状态：**未提交、未推送、未合并 main**

本目录分两批：

| 批次 | 内容 | 生产代码 |
| --- | --- | --- |
| 第一批 | 两个真实文件测试（往返、顺序保护） | **零改动** |
| 第二批 | 故障注入接缝 + 四项失败测试 | 新增 `NotesFileOps.kt`，`NotesRepository.kt` +13 −6 |

## 阅读顺序

**第二批（本轮，先看）**

| 顺序 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `REPORT-FAULT-INJECTION.md` | 接缝 diff、四项测试断言、真实/注入对照表、灵敏度注入、目录清理证据、未覆盖项 |
| 2 | `src/NotesRepositoryFailureTest.kt` | F1–F4 与注入器源码 |
| 3 | `src/NotesFileOps.kt` | 生产接缝（internal）与真实实现 |
| 4 | `logs/SAVE-ORDER-2-fault-injection.diff` | 六个文件的完整 diff（含新增测试） |
| 5 | `logs/xml/f1-final-failure-tests.xml` | 四项故障测试 4/4 |
| 6 | `logs/xml/f2-full-androidtest.xml` | 全量 21/21 |
| 7 | `logs/xml/f1-mutation-nonatomic.xml`、`logs/xml/f1-mutation-no-cleanup.xml` | 两次回归注入的失败证据 |

**第一批**

| 顺序 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `REPORT.md` | 主报告：基线、隔离机制、两个测试的断言序列、执行记录 |
| 2 | `FAULT-INJECTION-PLAN.md` | 当时的方案（已被评审修订，实际实施见第二批报告） |
| 3 | `src/NotesRepositoryFileTest.kt`、`src/NotesWriteCoordinatorFileTest.kt`、`src/IsolatedNoteStorage.kt` | 第一批测试源码 |

## 第二批结论

- 生产接缝：`internal NotesFileOps`（createTemp/openStream/write/sync/rename）+ `RealNotesFileOps`；
  `NotesRepository internal constructor(context, fileOps)` + 公开 `constructor(context)`。
  生产默认路径仍是真实 `createTempFile → FileOutputStream → write → fd.sync → renameTo`。
- 四项测试全过，全量 21/21；每项都断言：调用方收到失败、旧文件字节/长度/SHA-256 不变、
  本次 `.tmp` 清理、新实例读到 v1、恢复保存读到 v2、操作轨迹与故障位置一致。
- 两次临时回归注入（改成直写正式文件 / 去掉 `finally` 清理）都让测试变红，随后逐字节还原。
- 目录清理实测：`cache/save-order-2/` 保留但为空（无 UUID 子目录），真实 `files/` 仍为空。

## 环境

`emulator-5554`，AVD `Medium_Phone_API_37.0`，Android 17 / API 37，`sdk_gphone64_x86_64`，
x86_64，1080×2400 @420dpi。只在模拟器运行，未安装到有真实数据的设备。
工具链未改动：AGP 9.5.0、Gradle 9.6.0、Kotlin 2.2.10、compileSdk 37、minSdk 24；语音 AAR 未动。
