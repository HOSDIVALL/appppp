# SAVE-ORDER-2 阅读入口（给审阅方 AI）

本目录是「SAVE-ORDER-2：真实文件保存与失败保护测试」第一轮的回传材料。

- 实验 worktree：`D:/SAVE-ORDER-1-wt`
- 分支：`fix/backup-safe-1`
- HEAD：`26a5cd9669c76ce267b5eaaea2fafe64a9e92269`（即 SAVE-ORDER-1 的固化提交，未被修改）
- main：`61dff28`，未触碰
- 状态：**未提交、未推送、未合并**

## 一句话结论

两个不改生产代码的真实文件测试已完成并通过（2/2），全量 androidTest 回归 17/17 通过，
生产代码**零改动**；故障注入只给了方案，没有实施。

## 建议阅读顺序

| 顺序 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `REPORT.md` | 主报告：基线核验、隔离机制、两个测试的断言序列、灵敏度验证、执行记录、未覆盖项 |
| 2 | `FAULT-INJECTION-PLAN.md` | 故障注入方案：为什么必须留接缝、最小接缝设计、F1–F4 用例断言、诚实边界、待确认项 |
| 3 | `src/NotesRepositoryFileTest.kt` | 测试一源码（往返） |
| 4 | `src/NotesWriteCoordinatorFileTest.kt` | 测试二源码（顺序保护 + 真实磁盘） |
| 5 | `src/IsolatedNoteStorage.kt` | 沙箱 Context（隔离机制） |
| 6 | `logs/SAVE-ORDER-2.diff` | 完整 diff（3 个新文件，281 行，无生产代码改动） |
| 7 | `logs/xml/*.xml` | JUnit XML：最终两测试、全量 17 项、两次回归注入的失败证据 |
| 8 | `logs/*.log` | Gradle 原始输出 |

## 需要你裁决的三点（详见 PLAN 第 6 节）

1. 是否批准最小接缝（新增 `internal NotesFileOps` + `NotesRepository(context, fileOps = RealNotesFileOps)`）；
2. `WRITE` 故障采用「真写一半再抛」是否可接受；
3. F1–F4 放新类 `NotesRepositoryFailureTest`，还是并入 `NotesRepositoryFileTest`。

## 目录映射（本地 ↔ 本分支）

| 本地路径 | 本目录 |
| --- | --- |
| `D:/SAVE-ORDER-2/REPORT.md` | `save-order-2/REPORT.md` |
| `D:/SAVE-ORDER-2/FAULT-INJECTION-PLAN.md` | `save-order-2/FAULT-INJECTION-PLAN.md` |
| `D:/SAVE-ORDER-1-wt/app/src/androidTest/.../data/*.kt`（3 个新文件） | `save-order-2/src/*.kt` |
| `D:/SAVE-ORDER-2/logs/*` | `save-order-2/logs/*` |

## 环境

设备：`emulator-5554`，AVD `Medium_Phone_API_37.0`，Android 17 / API 37，`sdk_gphone64_x86_64`，
x86_64，1080×2400 @420dpi，无头启动。只在模拟器上运行，未安装到任何带真实笔记数据的设备。

工具链版本未改动：AGP 9.5.0、Gradle 9.6.0、Kotlin 2.2.10、compileSdk 37、minSdk 24。
