# SAVE-ORDER-2 阅读入口（给审阅方 AI）

实验 worktree：`D:/SAVE-ORDER-1-wt`｜分支 `fix/backup-safe-1`｜源码基线 `26a5cd9669c76ce267b5eaaea2fafe64a9e92269`
状态：**未提交、未推送、未合并 main**（只把文档与测试源码以 review 分支形式发布）

本目录分四批：

| 批次 | 内容 | 生产代码 |
| --- | --- | --- |
| 第一批 | 两个真实文件测试（往返、顺序保护） | **零改动** |
| 第二批 | 故障注入接缝 + 四项失败测试 | 新增 `NotesFileOps.kt`（42 行），`NotesRepository.kt` +13 −6 |
| 第三批 | 收尾：seed 前置断言、精确轨迹、M1 口径修正、正常测试收尾、表述收窄 | **零改动** |
| 第四批（本轮） | 真机验证：Redmi API 36 上跑全量 21 项 + 真实数据前后指纹 | **零改动** |

## 阅读顺序

**第四批（本轮，先看）**

| 顺序 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `REPORT-REALDEVICE.md` | 真机 21/21、三段指纹、21 条 started/finished 逐项明细、0.335 s 的核实过程 |
| 2 | `logs/realdevice-am-instrument.log` | `am instrument` 原始输出 |
| 3 | `logs/realdevice-logcat-testrunner.txt` | logcat 中 21 条 `started:` / ``finished:``、0 条失败 |
| 4 | `logs/realdevice-fingerprint.txt` | 安装前 / 跑完 / 事后三段 `notes.json` 指纹与目录清单 |

**第三批**

| 顺序 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `REPORT-TIGHTEN.md` | 逐条对应六项要求的改动、M1′ 的定向破坏证据、表述收窄清单 |
| 2 | `src/NotesRepositoryFailureTest.kt` | `seedOldContent` 前置断言 + `assertAttemptTrace` 精确轨迹（376 行） |
| 3 | `src/IsolatedNoteStorage.kt` | `dispose()` 返回是否删净 + `finishIsolatedTest()`（113 行） |
| 4 | `logs/SAVE-ORDER-2-tighten.diff` | 收尾后的完整 diff（6 文件，+802 −6） |
| 5 | `logs/xml/t2-full-androidtest.xml` | 收尾后全量 21/21 |
| 6 | `logs/xml/m1prime-targeted-corruption.xml` | M1′ 定向破坏的失败证据（预期失败，`expected.length=173 actual.length=0`） |

**第二批**

| 顺序 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `REPORT-FAULT-INJECTION.md` | 接缝 diff、四项测试断言、真实/注入对照表、未覆盖项 |
| 2 | `src/NotesFileOps.kt` | 生产接缝（internal）与真实实现 |
| 3 | `logs/SAVE-ORDER-2-fault-injection.diff` | 第二批的完整 diff |
| 4 | `logs/xml/f1-final-failure-tests.xml` | 四项故障测试 4/4 |
| 5 | `logs/xml/f2-full-androidtest.xml` | 全量 21/21 |
| 6 | `logs/xml/f1-mutation-nonatomic.xml`、`logs/xml/f1-mutation-no-cleanup.xml` | 第二批两次注入的失败证据（前者已在第三批降级，见下） |
| — | `logs/xml/m1prime-targeted-corruption.xml` | 见第三批，取代 `f1-mutation-nonatomic.xml` 作为定向证据 |

**第一批**

| 顺序 | 文件 | 内容 |
| --- | --- | --- |
| 1 | `REPORT.md` | 主报告：基线、隔离机制、两个测试的断言序列、执行记录 |
| 2 | `FAULT-INJECTION-PLAN.md` | 当时的方案（已被评审修订，实际实施见第二批） |
| 3 | `src/NotesRepositoryFileTest.kt`、`src/NotesWriteCoordinatorFileTest.kt` | 第一批测试源码（第三批已加 `@After` 收尾） |

## 第三批结论（摘要）

- `seedOldContent()` 现在先证明基准有效：非空、`JSONArray` 可解析、新实例读回整体等于 `oldNote()`，
  才返回磁盘字节。空文件或坏文件不可能再被当成「旧文件」。
- 四项测试的轨迹断言改为**条数 + 顺序 + 每步形态**精确匹配：
  F1 三步、F2 四步、F3 五步、F4 三步（合计八步），且都在恢复重试之前断言。
- M1 原证据（`expected.length=0`）**降级**为粗粒度路径破坏证据；另做 M1′：
  先用 `notesToJson` 写出 173 字节有效 v1（`sha256=581f59cd…`），再施加同一变异，
  破坏后文件 0 字节、`save()` 却返回成功，断言在 `kt:271` 的字节比对处失败。
- `NotesRepositoryFileTest` / `NotesWriteCoordinatorFileTest` 补了 `@Before/@After`，
  指纹核验与沙箱清理在失败时也执行；`dispose()` 返回是否删净，清理失败会显式报出。
- 表述统一为「路径隔离、前后指纹一致」，不再出现「一个字节都不会被碰到」。

## 第四批结论（摘要）

- 真机 Redmi `23078RKD5C`（corot，Android 16 / API 36，arm64-v8a）上 **`OK (21 tests)`**，
  21 个用例逐个 `started` / `finished`，0 失败。
- **没有**运行 `connectedDebugAndroidTest`：AGP 9 该任务结束会卸载应用，真机上会连带清空 `files/`。
  只用了 `adb install -r`（保留数据）+ `am instrument`（不卸载）。
  安装后应用与测试包仍在设备上，主应用未卸载。
- 真实 `notes.json` 2793 B，安装前 / 跑完 / 事后三次均为
  `83275cf129453228fb70d96202843b5969e2880ee3b2b038b5fd25446d1a4795`，长度与 mtime 未变；
  attachments 9 → 9、recordings 57 → 57。
- 证据强度高于模拟器：模拟器上真实 `files/` 没有 `notes.json`，指纹比对是 `<absent> == <absent>`；
  真机上是 2793 字节的真实笔记。但这仍只说明「路径隔离、测试前后指纹一致」，
  不等于「测试期间任何时刻绝对没有发生写入」。
- `am instrument` 不产出 JUnit XML，本轮证据是 am 输出 + logcat 逐项明细 + 前后指纹。

## 环境

**模拟器**（第一至三批）：`emulator-5554`，AVD `Medium_Phone_API_37.0`，Android 17 / API 37，
`sdk_gphone64_x86_64`，x86_64，1080×2400 @420dpi。

**真机**（第四批）：`6LZT7T7TM7NZL799`，Redmi `23078RKD5C`（corot），Android 16 / API 36，
arm64-v8a。真机上原本就装着 debug 版 App，`files/` 内有约 248 MB 真实数据（笔记 2793 B、
9 张附件、57 个录音、asr 缓存）；执行前已完整备份到实验目录外的
`real-device-backup-20261111/files.tar`（244 MB，sha256 `7bcecefd…`），备份内 `notes.json`
哈希与设备端一致。

工具链未改动：AGP 9.5.0、Gradle 9.6.0、Kotlin 2.2.10、compileSdk 37、minSdk 24；语音 AAR 未动。

## 请重点质疑的地方

1. 真机 `install -r` 覆盖了设备上原有的 debug APK（数据保留、版本号仍是 2.0），
   这本身改变了被测设备的状态；备份可回滚，但没有做回滚演练。
2. `am instrument` 只有汇总 `OK (21 tests)`，逐项证据依赖 logcat 的 `TestRunner` 行，
   未产出机器可读的 XML。
3. 0.335 秒的耗时虽已用 21 条 started/finished 核对，但没有第三方计时器佐证。
4. 「路径隔离、前后指纹一致」只是端点比对，中间态未被观测。
5. 故障注入仍是代码级异常，不是内核返回的 ENOSPC/EIO，也不是真实掉电。
6. 未覆盖：进程终止/重启恢复、目录 fsync、备份导入事务、真实 ENOSPC/EIO、并发故障、
   低存储降级、换机型与重复运行。
