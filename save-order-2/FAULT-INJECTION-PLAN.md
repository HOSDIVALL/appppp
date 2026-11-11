# SAVE-ORDER-2 / 第三部分：故障注入方案（仅方案，未改生产代码）

状态：**未实施**。当前工作区生产代码零改动。本文只描述设计、给出最小接缝的候选 diff、
逐条列明断言与诚实边界，等确认后再动手。

---

## 1. 为什么"不改代码"就覆盖不了这三个失败点

`NotesRepository.save()` 把四步真实 IO 写死在同一个方法体里：

```kotlin
val temp = File.createTempFile("notes-", ".tmp", parent)   // 1 建临时文件
temp.outputStream().use { output ->
    output.write(json)                                     // 2 写字节
    output.fd.sync()                                       // 3 fsync
}
if (!temp.renameTo(file)) throw IOException(...)           // 4 原子替换
```

`NotesRepository` 是 final 类，`save()` 是 final 方法（Kotlin 默认），外部无法替身或覆盖其中任何一步。
逐一检查"零改动"的替代手段：

| 手段 | 能否只让目标步骤失败 | 判定 |
| --- | --- | --- |
| 换 filesDir | 整个写入成功或整个目录不可写，不能定位到某一步 | 不可用 |
| `chmod` 目录 500/400 | 只能稳定制造 `createTempFile` 失败；`rename` 与 `createTemp` 同目录，无法只要 rename 失败 | 不可用，且属"权限变化"，已排除 |
| 预置同名只读文件 | 临时文件名由 `createTempFile` 随机生成，无法预置 | 不可用 |
| 反射替换 `File` / `FileSystem` | 依赖 ART 内部实现，等价于改生产语义，且不可移植 | 拒绝 |
| 真实写满磁盘 / 制造 EIO | 需 root、`dm-flakey`、FUSE（libfiu 等），本环境不具备，且不可重复 | 拒绝 |
| 改 `save()` 返回 Boolean、吞异常 | 改变生产 API 契约，超出"保护旧文件"的验证范围 | 拒绝 |

结论：要覆盖"写入失败 / fsync 失败 / rename 失败"三个点，**必须留一处最小接缝**。

---

## 2. 最小接缝设计

核心约束（按你的要求）：

- 默认生产路径**仍然**真实建临时文件、真实写字节、真实 fsync、真实 rename；
- 生产端不得出现"测试模式就跳过写盘"的分支；
- 接缝只在"哪一步出错"上可替换，不改变原子写的顺序与收尾。

### 2.1 新增 `data/NotesFileOps.kt`（约 30 行）

```kotlin
package com.example.myapplicationfirstapp.data

import java.io.File
import java.io.FileOutputStream

/**
 * notes.json 原子写的五个真实 IO 步骤。
 *
 * 生产默认实现 [RealNotesFileOps] 就是原来 save() 里的那几行代码，
 * 不做任何模拟；接缝只为让测试能在指定步骤注入失败。
 */
internal interface NotesFileOps {
    fun createTemp(parent: File): File
    fun openStream(temp: File): FileOutputStream
    fun write(out: FileOutputStream, bytes: ByteArray)
    fun sync(out: FileOutputStream)
    fun rename(from: File, to: File): Boolean
}

internal object RealNotesFileOps : NotesFileOps {
    override fun createTemp(parent: File) = File.createTempFile("notes-", ".tmp", parent)
    override fun openStream(temp: File) = FileOutputStream(temp)
    override fun write(out: FileOutputStream, bytes: ByteArray) = out.write(bytes)
    override fun sync(out: FileOutputStream) = out.fd.sync()
    override fun rename(from: File, to: File) = from.renameTo(to)
}
```

### 2.2 `NotesRepository` 的改动（4 处替换 + 1 个默认参数）

```diff
-class NotesRepository(context: Context) {
+class NotesRepository(
+    context: Context,
+    private val fileOps: NotesFileOps = RealNotesFileOps
+) {
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

为什么这样是最小的：

- `fileOps` 有默认值，`MainActivity.kt:171` 与 `ReminderReceiver.kt:56` 两个调用点**一行都不用改**；
- `finally { temp.delete() }` 原样保留 —— 所以"临时文件被清理"是真删，不是假删；
- 生产默认实现逐字对应原来的 `File.createTempFile` / `FileOutputStream` / `write` / `fd.sync()` / `renameTo`，
  语义完全等价（只是拆成五个方法调用）；
- 改动总量：新文件约 30 行 + `NotesRepository` 4 处替换，不触碰协程、异常类型、DAO 或 UI。

### 2.3 注入器（只放 androidTest，不进生产）

```kotlin
internal class FaultyNotesFileOps(
    private val delegate: NotesFileOps = RealNotesFileOps,
    private val fault: Fault
) : NotesFileOps {
    enum class Fault { NONE, WRITE, SYNC, RENAME }

    override fun createTemp(parent: File) = delegate.createTemp(parent)          // 永远真实
    override fun openStream(temp: File) = delegate.openStream(temp)              // 永远真实

    override fun write(out: FileOutputStream, bytes: ByteArray) {
        if (fault == Fault.WRITE) {
            out.write(bytes, 0, bytes.size / 2)                                 // 真写一半
            out.flush()
            throw IOException("injected: write failed after half of ${bytes.size} bytes")
        }
        delegate.write(out, bytes)
    }

    override fun sync(out: FileOutputStream) {
        if (fault == Fault.SYNC) throw IOException("injected: fsync failed")
        delegate.sync(out)
    }

    override fun rename(from: File, to: File) =
        if (fault == Fault.RENAME) false else delegate.rename(from, to)
}
```

注入后的真实状态：

| 故障 | 临时文件 | 正式 notes.json |
| --- | --- | --- |
| `WRITE` | 已创建、含一半字节 | 完全未触碰 |
| `SYNC` | 已创建、含全部字节 | 完全未触碰（还没 rename） |
| `RENAME` | 已创建、含全部字节且已 fsync | 完全未触碰（rename 未执行） |

三种都发生在**替换旧文件之前**，正好满足第四部分"能够确认失败发生于替换旧文件之前"的前提。

---

## 3. 四个用例与断言

公共前置（每个用例独立沙箱，互不复用）：

1. 用 `RealNotesFileOps` 正常保存 v1（旧内容），记录 `sha256(v1)`、`length`、`readText()`；
2. 换成 `FaultyNotesFileOps(fault = X)`，保存 v2（新内容），捕获异常；
3. 断言（第四部分要求逐条对应）：
   - **向调用方报告失败**：`save()` 抛出 `IOException`，不是静默返回；
   - **原文件不变**：`sha256(notes.json) == sha256(v1)`，且长度相同；
   - **临时文件被清理**：`filesDir.listTempNotesFiles()` 为空（`notes-*.tmp`）；
   - 额外：`NotesRepository(context).load()` 读回仍是 v1 的标题与正文；
4. 恢复 `RealNotesFileOps`，同一路径再存 v2：成功返回、`sha256` 变为 v2、无 `.tmp` 残留、
   新实例 `load()` 得到 v2 —— 对应第四部分"后续保存可以恢复"。

### F1 `tempWriteFailureKeepsOldFile`

注入点 `write`：真写一半后抛 `IOException`。断言同上。

### F2 `syncFailureKeepsOldFile`

注入点 `sync`：`write` 真实写完整个字节数组，`sync` 抛 `IOException`。
这一条专门验证"临时文件已写满但没 rename"时不会污染正式文件，也是三种里最接近真实 `ENOSPC` 的形态。

### F3 `renameFailureKeepsOldFile`

注入点 `rename`：返回 `false`。断言同上，并额外断言异常类型为 `IOException`、消息为
`Cannot replace notes file`（与生产代码第 51 行的文案一致），确保不是被别的异常路径掩盖。

### F4 `retryAfterFailureSucceeds`

`WRITE` 失败一次后，用 `RealNotesFileOps` 在同一 `NotesRepository` 的路径上再存 v2：
必须成功，且磁盘上只有一个 `notes.json`、无 `.tmp`、内容为 v2。

### 用例与"真实 / 注入"归属（预备回传口径）

| 用例 | 临时文件 | 写入字节 | fsync | rename | 结论表述 |
| --- | --- | --- | --- | --- | --- |
| F1 | 真实 | 真实写一半后注入异常 | 未到达 | 未到达 | 模拟注入 |
| F2 | 真实 | 真实写全 | 注入异常（未真跑 fsync） | 未到达 | 模拟注入 |
| F3 | 真实 | 真实写全 | 真实执行 | 返回 false（未真 rename） | 模拟注入 |
| F4 | 真实 | 真实 | 真实 | 真实 | 真实路径 |

---

## 4. 诚实边界（回传时必须照抄，不得美化）

1. F1–F3 抛的是**我们自己构造的 `IOException`**，不是内核返回的 `ENOSPC` / `EIO`，
   **不能**说成"设备真实断电"或"底层文件系统故障"。
2. F2 里 `fd.sync()` **并未真正失败**（`RealNotesFileOps.sync` 被跳过），测的是"flush 之后、rename 之前失败"的收尾，
   即"临时文件已完整但尚未替换"的中间态保护。
3. F3 的 `renameTo` 返回 `false` 是 Android/Linux 上 `File.renameTo` 的真实失败形态（它不抛异常，只返回布尔），
   但本次没有制造真实原因（跨文件系统、目标被占用等）。
4. 目录项未 fsync 的掉电窗口（rename 成功但目录未落盘）依旧不覆盖，属于已知缺口，不在本批断言内。
5. 注入器只存在于 androidTest；生产 APK 的字节码里只有 `RealNotesFileOps`。

---

## 5. 影响面与回归

- 生产 API 不变（构造函数多一个带默认值的参数，二进制兼容源码级调用）；
- 现有 `NotesWriteCoordinatorTest`（5 项）、`BackupNotesParserTest`（6 项）、
  本批新增 2 项，均不依赖新接口，不受影响；
- 计划在实施后跑全量 `connectedDebugAndroidTest`，预期 21 项（17 + 4）；
- 若接缝被批准，建议一并做一次"接缝等价性"检查：用 `RealNotesFileOps` 跑 A/B 两次保存同一组笔记，
  断言 `notes.json` 的 SHA-256 完全一致，证明重构没有改变写盘结果。

---

## 6. 需要你确认的事项

1. 是否批准上述接缝（新增 `internal NotesFileOps` + `NotesRepository` 构造函数默认参数）？
2. 注入器里 `write` 失败采用"真写一半再抛"是否可接受（比"一个字节都不写"更接近真实）；
   若你希望更保守，可改为"完全不写"，代价是少覆盖"半截文件"形态。
3. F1–F4 是否按四个独立 `@Test` 落在同一个新类 `NotesRepositoryFailureTest`，还是并到已有的
   `NotesRepositoryFileTest` 里（我倾向于新类，便于单独按类过滤运行）。
