# SAVE-ORDER-2 回传：真实文件保存与失败保护（第一批）

生成时间：2026-11-11 14:30 (GMT+8)
实验 worktree：`D:/SAVE-ORDER-1-wt`
分支：`fix/backup-safe-1`

---

## 0. 一句话结论

两个不改生产代码的真实文件测试已完成并在模拟器上通过（2/2），全量 androidTest 回归 17/17 通过；
生产代码**零改动**（`git status` 只有 3 个新增的 androidTest 文件），SAVE-ORDER-1 的固化提交 `26a5cd9` 保持原样；
故障注入只给了方案，**没有**动生产代码。

---

## 1. 基线核验

| 项目 | 期望 | 实际 |
| --- | --- | --- |
| worktree HEAD | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269` | 一致 |
| 分支 | `fix/backup-safe-1` | 一致 |
| 工作区 | 干净 | `git status --short` 执行前为空 |
| main | 未被触碰 | `D:/myappappappappapp` 仍为 `61dff28` |
| SAVE-ORDER-1 固化 | 保留 | `26a5cd9` 的提交内容未修改 |

提交、推送、合并：均未执行。

---

## 2. 隔离机制（为什么不碰真实笔记）

`NotesRepository` 取路径的方式是：

```kotlin
private val appContext = context.applicationContext
private val file: File get() = File(appContext.filesDir, "notes.json")
```

关键点是 `context.applicationContext`。如果只做 `ContextWrapper` 覆盖 `getFilesDir()`，
`getApplicationContext()` 会把请求转发给真正的 `Application`，`filesDir` 又会指回真机目录。
所以测试里的 wrapper 让**自己**充当 applicationContext：

```kotlin
internal class IsolatedAppContext(base: Context, private val root: File) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this        // 不再转发给真实 Application
    override fun getFilesDir(): File { ... File(root, "files") } // 沙箱
    override fun getCacheDir(): File { ... File(root, "cache") }
}
```

沙箱根目录：`targetContext.cacheDir/save-order-2/<tag>-<uuid>/`，每个测试一份全新空目录。

额外加了一道硬证据：每个测试开头和结尾各取一次**真实** `targetContext.filesDir/notes.json`
的 SHA-256（不存在则记 `<absent>`），断言两次完全相同 —— 若隔离失效、真文件被写，这条断言会失败。

---

## 3. 测试一：`repositorySaveReloadRoundTrip`

文件：`app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesRepositoryFileTest.kt`

**用真实文件还是模拟接缝：真实文件。** 无 mock、无内存替身，写的是沙箱里的真 `notes.json`。

构造的笔记覆盖了要求的全部字段：

- 标题 `周会要点`，正文含换行和 `![图片](img_1001.jpg)` 图片引用
- 待办 3 项，勾选状态 `true / false / true`（含 id 与文本）
- `images = [img_1001.jpg, img_1002.jpg]`、`tags`、`pinned`、`colorIndex = 3`、`checklist = true`、`remindAt`
- `createdAt / updatedAt` 用固定时间戳，避免断言依赖当前时间

断言序列：

1. 调**实际** `NotesRepository.save()`；
2. `notes.json` 存在且非空；
3. 直接读原始文本 → `JSONArray` → 逐字段核对磁盘上的 JSON：
   `id / title / content / createdAt / updatedAt / colorIndex / pinned / checklist / remindAt`、
   `deletedAt` 为 null 时**不得**出现该键、`tags/images/todos` 三个键必须存在、
   `images` 两项顺序正确、`todos[1].done = false`、`todos[2].done = true`；
4. **新建** `NotesRepository` 实例调 `load()`（不是同一个实例的缓存）；
5. 逐字段比对 + `Note` 整体 `equals` 比对（data class 覆盖全部字段）；
6. `back.inlineImages == [img_1001.jpg]`，正文图片引用能重新解析；
7. 沙箱内无残留 `notes-*.tmp`；
8. 真实 `notes.json` 指纹前后一致。

> **边界说明**：这是"新实例从磁盘读取"，**不是进程重启测试**。进程被杀后的恢复、
> 以及页面缓存是否已落盘，本测试都不证明。

---

## 4. 测试二：`coordinatorPersistsNewestSnapshotToRealFile`

文件：`app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesWriteCoordinatorFileTest.kt`

**用真实文件还是模拟接缝：真实文件。** 协调器的写回调里调的是真实 `NotesRepository.save()`，
判断依据是真实 `notes.json` 的字节与 SHA-256，不是字符串变量。

步骤：

1. 先直接写一份"基线"内容到磁盘，记录 `baselineHash`；
2. **调度之前**就把旧、新两份快照和它们的 ticket 都取好：
   `staleTicket = reserve()`、`freshTicket = reserve()`（旧在前、新在后）；
3. 新快照先走协调器 → 真实写盘，断言返回 `true`；
   断言磁盘哈希已变、文件里有"新快照"、没有"基线标题"；
4. 再执行旧快照请求，写回调里放了一个 `staleWriteRan` 标记：
   - 断言 `save()` 返回 `false`（被判定过期）；
   - 断言 `staleWriteRan == false`（**被跳过的请求根本没有执行写操作**）；
5. 断言 `notes.json` 的 SHA-256 与第 3 步完全一致（逐字节未变）、文本与第 3 步相同、不含"旧快照"；
6. 新 `NotesRepository` 实例 `load()` → 标题 `新快照`、正文、`updatedAt` 均为新值；
7. 协调器未被卡死：新 ticket 仍能写入"再后来"，再读回确认；
8. 无残留 `.tmp`；真实 `notes.json` 指纹前后一致。

---

## 5. 灵敏度验证（证明这两个测试不是空跑）

只是"跑绿"无法证明断言有效，所以做了两次**临时回归注入**，跑完立即逐字节还原：

| 注入 | 改动 | 结果 |
| --- | --- | --- |
| M1 | `NotesWriteCoordinator.save` 的顺序条件 `ticket.sequence > savedSequence` 改成只校验 generation（等价于 SAVE-ORDER-1 修复前的行为） | `NotesWriteCoordinatorFileTest` **FAILED**：`AssertionError: 旧快照必须被判定为过期`（第 62 行） |
| M2 | `Note.toJson()` 里 `if (todos.isNotEmpty())` 改成 `if (false)` | `NotesRepositoryFileTest` **FAILED**：`JSONException: No value for todos`（第 77 行）—— 因为断言直接读磁盘 JSON，序列化回归会被抓到 |

还原方式：`cp` 备份回原文件，随后 `git diff --stat` 为空、`git status --short` 无 `M` 条目。
证据：`logs/xml/mutation1-coordinator-order.xml`、`logs/xml/mutation2-todos-serialization.xml`、`logs/logs-save-order-2-mutation1.log`、`logs/logs-save-order-2-mutation2.log`。

> 注：M2 之后补了 3 条显式断言（`tags/images/todos` 必须存在），否则失败会以原始 `JSONException` 呈现而非可读断言消息。

---

## 6. 执行记录

| # | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 1 | `./gradlew :app:compileDebugAndroidTestKotlin` | 1 → 0 | 第一次因 Kotlin 语法错误失败（主构造参数在成员函数体不可见），修正后 `BUILD SUCCESSFUL in 20s` |
| 2 | `./gradlew :app:connectedDebugAndroidTest -P...class=NotesRepositoryFileTest,NotesWriteCoordinatorFileTest` | 0 | 2 tests, 0 failures |
| 3 | 同 #2（加隔离指纹断言后） | 0 | 2 tests, 0 failures |
| 4 | 同上，最终一轮 | 0 | 2 tests, 0 failures（`run3-final-two-tests.xml`） |
| 5 | `./gradlew :app:connectedDebugAndroidTest`（全量） | 0 | **17 tests, 0 failures, 0 errors, 0 skipped** |
| 6 | M1 注入下跑协调器测试 | 1 | 1 test, 1 failure（预期失败） |
| 7 | M2 注入下跑往返测试 | 1 | 1 test, 1 failure（预期失败） |

全量 17 项明细（`logs/xml/run4-full-androidtest.xml`）：

| 测试类 | 数量 | 失败 |
| --- | --- | --- |
| `ExampleInstrumentedTest` | 1 | 0 |
| `data.BackupNotesParserTest` | 6 | 0 |
| `data.NotesRepositoryFileTest`（本次新增） | 1 | 0 |
| `data.NotesWriteCoordinatorFileTest`（本次新增） | 1 | 0 |
| `data.NotesWriteCoordinatorTest` | 5 | 0 |
| `ui.AttachmentImageLoaderTest` | 3 | 0 |

设备：

| 项 | 值 |
| --- | --- |
| 设备 | `emulator-5554`，AVD `Medium_Phone_API_37.0` |
| 型号 / device | `sdk_gphone64_x86_64` / `emu64xa` |
| Android | 17，API 37 |
| ABI | `x86_64` |
| 屏幕 | 1080×2400，420dpi |
| 启动方式 | `-no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader_indirect` |

**未在真实设备上运行**，也**没有**把应用装到任何带真实笔记数据的设备上。

---

## 7. 本次是否修改了生产代码

**没有。** 最终 `git status --short` 只有 3 个未跟踪的 androidTest 文件：

```
?? app/src/androidTest/java/com/example/myapplicationfirstapp/data/IsolatedNoteStorage.kt
?? app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesRepositoryFileTest.kt
?? app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesWriteCoordinatorFileTest.kt
```

`git diff`（对已跟踪文件）为空。第 5 节的两处注入是**临时**的，已逐字节还原。

测试文件自身的两次修改（都在 androidTest 内，不涉及生产代码）：

1. `IsolatedAppContext` 的 `root` 由普通构造参数改为 `private val`（Kotlin 主构造参数在成员函数体里不可见）；
2. 补 `has("tags"/"images"/"todos")` 三条显式断言。

未提交、未推送、未合并 main。日志写在仓库外的 `D:/SAVE-ORDER-2/logs/`，避免污染 worktree。

---

## 8. 未覆盖项（明确列出）

1. **进程被杀 / 重启后的恢复**：两个测试都是同进程内新建实例。
2. **真实掉电、真实 ENOSPC / EIO**：没有制造，也没有权限或满盘操作。
3. **目录项 fsync**：`save()` 只对临时文件 `fd.sync()`，rename 之后没有对 `filesDir` 做 fsync，
   掉电时"新内容已 rename 但目录项未落盘"仍可能丢。本批测试不覆盖。
4. **并发**：两个测试都是顺序调用；协程并发下的互斥由既有 `NotesWriteCoordinatorTest`（5 项）覆盖，
   但"并发 + 真实文件"没有组合测试。
5. **大体积笔记**（MB 级 JSON）、低存储空间下的降级行为。
6. **图片/录音附件实际落盘**：本次只验证 `images` 字段里的引用往返，没有走 `importImage`。
7. **真实设备**：只有 x86_64 模拟器。

---

## 9. 目录

```
D:/SAVE-ORDER-2/
├─ REPORT.md                       ← 本文件
├─ FAULT-INJECTION-PLAN.md         ← 第三部分：故障注入方案（未实施）
└─ logs/
   ├─ SAVE-ORDER-2.diff            ← 完整 diff（3 个新文件，281 行）
   ├─ run3-final-two-tests.log
   ├─ run4-full-androidtest.log
   ├─ logs-save-order-2-run1.log
   ├─ logs-save-order-2-run2.log
   ├─ logs-save-order-2-mutation1.log
   ├─ logs-save-order-2-mutation2.log
   └─ xml/
      ├─ run3-final-two-tests.xml
      ├─ run4-full-androidtest.xml
      ├─ mutation1-coordinator-order.xml
      └─ mutation2-todos-serialization.xml
```
