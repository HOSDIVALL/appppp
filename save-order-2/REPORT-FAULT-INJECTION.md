# SAVE-ORDER-2 故障注入阶段：回传

生成时间：2026-11-11 14:52 (GMT+8)
实验 worktree：`D:/SAVE-ORDER-1-wt`
分支：`fix/backup-safe-1`
源码基线：`26a5cd9669c76ce267b5eaaea2fafe64a9e92269`

---

## 0. 结论

- 接缝按评审意见实施：`internal NotesFileOps` + `RealNotesFileOps`，`NotesRepository` 改为 internal 双参主构造函数 + 公开单参次构造函数。
- 生产改动的净增量：**新增 42 行 + 修改 13 行 / 删除 6 行**，只涉及 `NotesFileOps.kt`（新）与 `NotesRepository.kt`。
- 四项故障测试按规格完成：**4/4 通过**；全量 androidTest **21/21 通过**。
- 生产路径仍执行真实 `createTempFile → FileOutputStream → write → fd.sync → renameTo`，无"测试模式跳过写盘"分支，异常照常向调用方传播。
- 未提交、未推送、未合并 main，未在真实数据设备上安装。

---

## 1. 基线与工作区核验

| 项 | 期望 | 实际 |
| --- | --- | --- |
| HEAD | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269` | 一致 |
| 分支 | `fix/backup-safe-1` | 一致 |
| 已跟踪文件差异 | 无 | `git diff --stat` 为空 |
| 未跟踪文件 | 上一轮 3 个 androidTest 文件 | 恰好这 3 个，无计划外改动 |
| main | `61dff28` | 一致，未触碰 |

开工前若出现上表以外的改动，本报告不成立；实际核验结果是**没有**。

---

## 2. 生产最小 diff

### 2.1 新增 `app/src/main/java/com/example/myapplicationfirstapp/data/NotesFileOps.kt`（42 行）

```kotlin
package com.example.myapplicationfirstapp.data

import java.io.File
import java.io.FileOutputStream

/**
 * notes.json 原子写的五个真实 IO 步骤。
 * [RealNotesFileOps] 就是 `save()` 原先直接调用的那几行，行为完全等价；
 * 把步骤拆出来只为让测试能在**指定的一步**注入失败，生产默认路径不做任何模拟。
 * 只在本模块与 androidTest 之间可见，不对外暴露。
 */
internal interface NotesFileOps {
    fun createTemp(parent: File): File
    fun openStream(temp: File): FileOutputStream
    fun write(out: FileOutputStream, bytes: ByteArray)
    fun sync(out: FileOutputStream)
    fun rename(from: File, to: File): Boolean
}

/** 生产实现：真实建文件、真实写、真实 fsync、真实 rename。 */
internal object RealNotesFileOps : NotesFileOps {
    override fun createTemp(parent: File): File = File.createTempFile("notes-", ".tmp", parent)
    override fun openStream(temp: File): FileOutputStream = FileOutputStream(temp)
    override fun write(out: FileOutputStream, bytes: ByteArray) = out.write(bytes)
    override fun sync(out: FileOutputStream) = out.fd.sync()
    override fun rename(from: File, to: File): Boolean = from.renameTo(to)
}
```

### 2.2 `NotesRepository.kt`：+13 / −6

```diff
-class NotesRepository(context: Context) {
+class NotesRepository internal constructor(
+    context: Context,
+    private val fileOps: NotesFileOps
+) {
+    constructor(context: Context) : this(context, RealNotesFileOps)
@@ save()
-        val temp = File.createTempFile("notes-", ".tmp", parent)
+        val temp = fileOps.createTemp(parent)
         try {
-            temp.outputStream().use { output ->
-                output.write(json)
-                output.fd.sync()
+            fileOps.openStream(temp).use { output ->
+                fileOps.write(output, json)
+                fileOps.sync(output)
             }
-            if (!temp.renameTo(file)) {
+            if (!fileOps.rename(temp, file)) {
                 throw java.io.IOException("Cannot replace notes file")
             }
         } finally {
             temp.delete()
         }
```

`git diff --check` 退出码 0（无空白字符问题）。

### 2.3 顺序、异常传播、清理均未变

- 顺序仍是「建临时文件 → 写 → sync → rename → finally 删临时文件」，没有插入任何判断或重试。
- `save()` 内部没有 `try/catch`，注入的 `IOException` 原样抛给调用方；`rename` 返回 false 仍抛
  `IOException("Cannot replace notes file")`。
- `finally { temp.delete() }` 未被条件包裹，失败路径同样执行。
- 没有 `if (BuildConfig.DEBUG)` / `isTest` 之类的旁路。

---

## 3. 构造函数可见性（已实测）

`NotesFileOps` 与主构造函数都是 `internal`，公开 API 只有 `NotesRepository(context)`。
androidTest 通过「同模块 friend 路径」访问内部入口：

```kotlin
val repository = NotesRepository(sandbox, InjectingFileOps(failAt = Step.WRITE))
```

- `:app:compileDebugKotlin` + `:app:compileDebugAndroidTestKotlin` 一起编译 **BUILD SUCCESSFUL in 41s**，
  0 error；这条编译本身就是「androidTest 能访问内部注入入口」的确认，不是推断。
- 生产侧调用点未改：`MainActivity.kt:171`、`ReminderReceiver.kt:56` 仍是 `NotesRepository(context)`。

---

## 4. 四项测试

文件：`app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesRepositoryFailureTest.kt`（298 行）

公共前置：每个测试独立沙箱 → 用真实 IO 保存 v1（`v1 旧内容 / 第一版正文 / updatedAt=1760000100000`）
→ 记录磁盘原始字节，然后对 v2（`v2 新内容 / 第二版正文 / updatedAt=1760000200000`）执行会失败的保存。

公共断言（四步，逐条对应要求）：

1. `save()` 向调用方抛出 `IOException`；
2. `notes.json` **字节数组逐字节相等**（`assertArrayEquals`）、长度相等、SHA-256 相等；
3. `notes-*.tmp` 为空（本次临时文件已清理）；
4. 新 `NotesRepository` 实例 `load()` 仍读到 v1 的三个字段；随后同一实例恢复保存，读到 v2。

### F1 `tempWriteFailureKeepsOldFile`

- 注入点：`write` —— 先真实写入**一半字节**（`bytes.copyOf(size/2)`），`flush()`，再抛 `IOException`。
- 轨迹断言：`createTemp`、`openStream`、`write:partial(k/n)-injected` 出现；
  **`sync` 与 `rename` 均未出现**。
- 失败后磁盘为 v1；恢复保存成功，读到 v2。

### F2 `syncFailureKeepsOldFile`

- 注入点：`sync` —— 完整内容已真实写入临时文件，`sync` 抛 `IOException`。
- 轨迹断言：`write(n)`（完整，非 partial）、`sync-injected` 出现；**`rename` 未出现**。
- 不声称制造了真实 `fd.sync` 故障或真实 ENOSPC：`RealNotesFileOps.sync` 在本次调用中**没有被执行**。

### F3 `renameFailureKeepsOldFile`

- 注入点：`rename` 返回 `false` —— 真实 `renameTo` 未执行。
- 真实执行的步骤：`createTempFile`、完整 `write`、**真实 `out.fd.sync()`**。
- 轨迹断言：`write(n)`、`sync`、`rename-injected-false` 依次出现。
- 断言异常类型 `IOException` 且消息等于 `Cannot replace notes file`（与生产第 51 行一致，防止被别的异常路径掩盖）。

### F4 `retryAfterFailureSucceeds`

- 注入器**只失败一次**（`injectedCount == 0` 才注入，之后全部委托真实实现）。
- 断言：第一次失败、`injectedCount == 1`、旧文件不变；
  **同一个 `NotesRepository` 实例、同一个路径**第二次保存成功，`injectedCount` 仍为 1，
  读回 v2，无 `.tmp`。
- 轨迹断言：两次 `createTemp`；只有一次"完整 write"；恰好一次 `rename(true)`。
- 没有通过"换一个正常 Repository 实例"来证明恢复 —— 恢复用的就是失败的那个实例，
  它的 `fileOps` 仍是注入器，只是注入额度用尽。

---

## 5. 每项哪些 IO 真实执行、哪些是注入

| 用例 | 真实执行的 IO | 注入的部分 | 备注 |
| --- | --- | --- | --- |
| F1 | 建临时文件、打开流、写一半字节、finally 删临时文件、恢复时全流程 | `write` 在半写后抛 `IOException` | 临时文件磁盘上确实有半截内容，随后被删 |
| F2 | 建临时文件、完整写 86 字节、finally 删除、恢复时全流程 | `sync` 抛 `IOException` | `fd.sync()` **未执行**；不是真实 ENOSPC/EIO |
| F3 | 建临时文件、完整写、**真实 fd.sync()**、finally 删除、恢复时全流程 | `rename` 返回 `false` | 真实 `renameTo` 未执行；返回布尔是 `renameTo` 的真实失败形态 |
| F4 | 第二次保存的 create/write/sync/rename/删除全部真实 | 只有第一次的 `write` 注入一次 | 证明同一实例可恢复 |

`rename` 返回 false 与 `IOException` 都是**我们构造的**，不是内核错误；本批没有制造过真实掉电、
真实磁盘满或真实文件系统故障。

---

## 6. 灵敏度验证（证明断言不是恒真）

跑绿之后做了两次**临时**回归注入，跑完立即还原（`git hash-object` 复核一致）：

| 注入 | 改动 | 结果 |
| --- | --- | --- |
| M1 非原子写 | `fileOps.openStream(temp)` → `fileOps.openStream(file)`（直接写正式文件） | F1 **FAILED**：`notes.json 字节必须逐字节不变: array lengths differed, expected.length=0 actual.length=86` |
| M2 不清理临时文件 | `finally { temp.delete() }` → `finally { Unit }` | F3 **FAILED**：`本次 notes-*.tmp 必须被清理 expected:<[]> but was:<[notes-1487823736603518680.tmp]>` |

M1 的形态说明：改成直写正式文件后，连"写入 v1"这一步都会先把 v1 清空，所以失败信息里
`expected.length=0`——它恰好证明这条断言盯的就是"旧文件被破坏"。

还原后 `git diff --stat` 与本轮实施完成时完全一致（`NotesRepository.kt` +13 −6），
`git hash-object` 回到 `df3792064346a364d6fb8bd5dd2851faf3905d71`。

---

## 7. 隔离与清理

- 机制未变：`IsolatedAppContext` 覆盖 `getApplicationContext()` 返回自身，
  `filesDir` / `cacheDir` 指向 `cacheDir/save-order-2/<tag>-<uuid>/`。
- 本轮给 `IsolatedAppContext` 增加了 `internal val rootDir` 与 `dispose()`（只删自己的 UUID 目录，
  不碰父目录 `save-order-2/`）。
- `@After` 里：先比对真实 `notes.json` 指纹，再 `dispose()`；用 `try/finally` 保证断言失败时
  清理仍然执行（要求"指纹核验尽量在失败时也执行"）。
- 实测（手动装 APK + `am instrument` 跑完四项后，用 `run-as` 查看）：

```
cache/save-order-2/        → 存在，空目录（无 UUID 子目录）
files/                     → 空目录（真实 notes.json 始终不存在）
```

- 因此**表述为「路径隔离，测试前后指纹一致」**：本机真实 `files/` 里本来就没有 `notes.json`，
  前后都是 `<absent>`。它证明的是"隔离没有回落到真实路径"（否则 fingerprints 会从 `<absent>` 变成哈希，
  或 `files/` 里多出文件），**不**据此声称"中间任何时刻都绝对没有发生过写入真实路径"。

手动安装的 APK 已卸载，设备回到无第三方包的状态。

---

## 8. 执行记录

| # | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 1 | `git diff --check` | 0 | 无空白字符问题 |
| 2 | `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` | 0 | BUILD SUCCESSFUL in 41s，0 error |
| 3 | `connectedDebugAndroidTest -P...class=NotesRepositoryFailureTest` | 0 | **4 tests, 0 failures** |
| 4 | `./gradlew :app:connectedDebugAndroidTest`（全量） | 0 | **21 tests, 0 failures, 0 errors, 0 skipped** |
| 5 | M1 注入下跑 F1 | 1 | 1 test, 1 failure（预期） |
| 6 | M2 注入下跑 F3 | 1 | 1 test, 1 failure（预期） |
| 7 | `am instrument -e class ...NotesRepositoryFailureTest`（手动装包，用于检查目录） | 0 | `OK (4 tests)` |

全量 21 项构成（`logs/xml/f2-full-androidtest.xml`）：

| 测试类 | 数量 | 失败 |
| --- | --- | --- |
| `ExampleInstrumentedTest` | 1 | 0 |
| `data.BackupNotesParserTest` | 6 | 0 |
| `data.NotesRepositoryFailureTest`（本批新增） | 4 | 0 |
| `data.NotesRepositoryFileTest` | 1 | 0 |
| `data.NotesWriteCoordinatorFileTest` | 1 | 0 |
| `data.NotesWriteCoordinatorTest` | 5 | 0 |
| `ui.AttachmentImageLoaderTest` | 3 | 0 |

设备：`emulator-5554`，AVD `Medium_Phone_API_37.0`，Android 17 / API 37，`sdk_gphone64_x86_64`，
x86_64，1080×2400 @420dpi。

依赖与 AAR：未改动（`app/libs/sherpa-onnx-2.0.1.aar` 仍在，`libs.versions.toml` 无改动）。

---

## 9. 首轮失败与修正记录

- 编译与测试**首轮即通过**，本轮没有"先红后绿"的修正。
- 唯一两次变红是第 6 节主动做的回归注入，属预期失败，不是缺陷修复。
- 相对上一轮已交付文件，本轮改动：`IsolatedNoteStorage.kt` 增加 `rootDir` / `dispose()`（69 行，原 64 行）；
  `NotesRepositoryFileTest.kt`、`NotesWriteCoordinatorFileTest.kt` **未改动**（仍为 118 / 99 行）。
  这两个文件里的指纹核验仍在测试体末尾（失败时不会执行），本批没有回改它们——如实说明，不夸大成"全部失败也校验"。

---

## 10. 未覆盖项

1. **进程终止 / 进程重启恢复**（按约定不在本批范围）。
2. **目录 fsync**：`rename` 之后没有对 `filesDir` 做 fsync，掉电时目录项仍可能丢（不在本批范围）。
3. **备份导入事务**（`replace()` 路径，按约定不在本批范围）。
4. 真实 `ENOSPC` / `EIO` / 真实掉电：未制造，也不应据本报告推断已覆盖。
5. 并发下故障：四项测试都是顺序调用，没有"多协程同时失败"的组合。
6. 大体积笔记、低存储空间下的降级行为。
7. 真实设备：只有 x86_64 模拟器。

---

## 11. 当前工作区状态

```
HEAD = 26a5cd9669c76ce267b5eaaea2fafe64a9e92269   （未提交）
分支 = fix/backup-safe-1
M  app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt   （+13 −6）
?? app/src/main/java/com/example/myapplicationfirstapp/data/NotesFileOps.kt      （新，42 行）
?? app/src/androidTest/.../data/NotesRepositoryFailureTest.kt                    （新，298 行）
?? app/src/androidTest/.../data/IsolatedNoteStorage.kt                           （69 行，本轮加 rootDir/dispose）
?? app/src/androidTest/.../data/NotesRepositoryFileTest.kt                       （118 行，未改）
?? app/src/androidTest/.../data/NotesWriteCoordinatorFileTest.kt                 （99 行，未改）
```

未提交、未推送、未合并 main。日志与 XML 在 `D:/SAVE-ORDER-2/logs/`（仓库外）。
