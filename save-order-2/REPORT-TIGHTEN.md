# SAVE-ORDER-2 收尾：回传

生成时间：2026-11-11 15:05 (GMT+8)
实验 worktree：`D:/SAVE-ORDER-1-wt`
分支：`fix/backup-safe-1`
源码基线：`26a5cd9669c76ce267b5eaaea2fafe64a9e92269`
对应证据提交：`d5a17c12cd75062b7f87ed9cc814f3a6c037fc7f`（`review/save-order-2-fault-injection`）

---

## 0. 结论

本轮只做收尾，**没有重做接缝、没有加功能、没有改生产逻辑**：

| # | 要求 | 结果 |
| --- | --- | --- |
| 1 | `seedOldContent()` 前置断言 | 已加：非空、可解析、新实例读回整体等于 v1 |
| 2 | 恢复重试前的精确顺序/次数断言 | 已加：F1 3 步、F2 4 步、F3 5 步、F4 3 步 + 合计 8 步，条数与逐项均钉死 |
| 3 | 修正 M1 口径 | 原 M1 降级为粗粒度路径破坏证据；另做 M1′ 定向破坏，有效 v1 = 173 字节 / `581f59cd…`，破坏后 0 字节，失败位置 `kt:271` |
| 4 | 两个正常测试补失败时收尾 | 已加 `@Before/@After` + `finishIsolatedTest`，清理结果显式断言 |
| 5 | 收窄绝对化表述 | 4 处改为「路径隔离、前后指纹一致」 |
| 6 | 重新编译 + 全量 androidTest | 编译 0 error；**21 tests, 0 failures**（未新增 @Test） |

生产代码仍为 `NotesRepository.kt` +13 −6、`NotesFileOps.kt` 42 行，blob 与上轮一致（`df379206…`）。

---

## 1. 基线核验（开工前）

| 项 | 期望 | 实际 |
| --- | --- | --- |
| HEAD | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269` | 一致 |
| 分支 | `fix/backup-safe-1` | 一致 |
| 已跟踪差异 | 仅 `NotesRepository.kt`（+13 −6） | 一致 |
| 未跟踪 | 5 个文件（NotesFileOps + 4 个测试） | 一致，无计划外改动 |
| main | `61dff28` | 未触碰 |

---

## 2. 相对 `d5a17c12…` 那批证据的修改清单

| 文件 | 上轮 | 本轮 | 改了什么 |
| --- | --- | --- | --- |
| `IsolatedNoteStorage.kt` | 69 行 | **113 行** | `dispose()` 返回 `Boolean`；新增 `finishIsolatedTest()`；类注释改为「路径隔离」 |
| `NotesRepositoryFailureTest.kt` | 298 行 | **376 行** | seed 前置断言；新增 `assertAttemptTrace`；4 处轨迹断言替换为精确序列；`@After` 改用 `finishIsolatedTest`；类注释补边界 |
| `NotesRepositoryFileTest.kt` | 118 行 | **138 行** | 加 `@Before/@After`；测试体内的指纹断言移到 `@After`；注释改为路径隔离 |
| `NotesWriteCoordinatorFileTest.kt` | 99 行 | **120 行** | 同上 |
| `NotesFileOps.kt` | 42 行 | 42 行 | **未改动** |
| `NotesRepository.kt` | +13 −6 | +13 −6 | **未改动**（blob `df3792064346a364d6fb8bd5dd2851faf3905d71`） |

合计：6 文件，+802 −6（其中生产代码仍只占 42 新增 + 13/6）。

---

## 3. `seedOldContent()` 的前置断言

不允许拿空文件或坏文件当「旧文件保持不变」的基准 —— 之前只断言了 `target.isFile`，空文件也能通过。现在在返回字节之前依次断言：

```kotlin
NotesRepository(sandbox).save(listOf(oldNote()))

assertTrue("前置：v1 必须已落盘", target.isFile)
assertTrue("前置：v1 不得为空文件，实际 ${target.length()} 字节", target.length() > 0L)

// 可解析
val array = JSONArray(target.readText(Charsets.UTF_8))
assertEquals("前置：磁盘上应当只有一条笔记", 1, array.length())
assertEquals("note-v1", json.getString("id"))
assertEquals("v1 旧内容", json.getString("title"))
assertEquals("第一版正文", json.getString("content"))
assertEquals(1_760_000_100_000L, json.getLong("updatedAt"))

// 新实例读回 == 预期 v1（字段级 + 整体相等）
val back = NotesRepository(sandbox).load().single()
assertEquals("v1 旧内容", back.title)
assertEquals("第一版正文", back.content)
assertEquals(1_760_000_100_000L, back.updatedAt)
assertEquals("前置：读回的笔记必须整体等于预期 v1", oldNote(), back)

assertEquals("前置：读回字节长度应与文件一致", target.length(), target.readBytes().size.toLong())
```

四项测试都走这个函数，所以 F1–F4 的「旧文件」基准现在都是**已证明有效**的。

---

## 4. 精确轨迹断言（顺序 + 次数）

新增 `assertAttemptTrace(stage, actual, vararg expected)`：先比对**条数**，再逐项比对**位置与形态**。
`write(full)` 匹配完整写入，`write(partial)` 匹配半写后注入失败，其余按字面精确匹配。
任何多一步、少一步、重复、乱序都会失败。

断言点都在「恢复重试之前」：

| 用例 | 断言时机 | 期望轨迹（精确） |
| --- | --- | --- |
| F1 | `repository.save(v2)` 恢复调用**之前** | `createTemp → openStream → write(partial)` （3 步） |
| F2 | 同上 | `createTemp → openStream → write(full) → sync-injected` （4 步） |
| F3 | 同上 | `createTemp → openStream → write(full) → sync → rename-injected-false` （5 步） |
| F4 | 第一次失败后、第二次保存**之前** | `createTemp → openStream → write(partial)` （3 步） |
| F4 | 第二次成功之后（两次合计） | 上述 3 步 + `createTemp → openStream → write(full) → sync → rename(true)` （共 8 步） |

F3 的关键变化：不再只检查「write / sync / rename 三个事件都出现」，而是钉死为**五步且顺序固定**。
这样以下实现错误都会被抓住：`rename` 抢在 `sync` 之前、`sync` 被调用两次、`openStream` 之前就写、
多建一个临时文件。

实测四条断言全部与真实轨迹吻合（21/21 通过），说明注入器记录的事件序列与预期完全一致，不是「放宽到能过」。

---

## 5. M1 证据口径修正

### 5.1 原 M1 降级

上一轮 M1（`openStream(temp)` → `openStream(file)`）的失败信息是
`expected.length=0 actual.length=86` —— 说明**连准备阶段写 v1 都被破坏了**，v1 从未有效建立。
因此它只能算**粗粒度路径破坏检测证据**（证明"非原子路径会破坏文件"这一点能被察觉），
**不能**作为"定向破坏被测保存、旧文件仍被检查到"的证据。本轮据此降级表述。

### 5.2 M1′ 重做：先建立有效 v1，再定向破坏

做法（临时探针，跑完已删除，不进入最终 21 项）：

1. **不经过被测 `save()`**，直接写入与 `save()` 同形态的字节：
   `target.writeText(notesToJson(listOf(oldNote())))`；
2. 证明基准有效：非空、`JSONArray` 可解析、新实例 `load()` 读回 `"v1 旧内容"`；
3. 施加与原 M1 相同的生产变异（`openStream(temp)` → `openStream(file)`），
   **只让这一条被测保存**走变异路径；
4. 调用 `assertOldFileIntact(oldBytes)`，它必须失败。

实测结果：

| 项 | 值 |
| --- | --- |
| 有效旧字节 | `len=173`，`sha256=581f59cda32774082cbea3f1f3c5a7e1127a08c563d468e3ad369a71ada8e7b2` |
| 变异保存返回 | `result=null`（**save() 认为成功**） |
| 破坏后文件 | `len=0`，`sha256=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`（空文件哈希） |
| 实际失败位置 | `NotesRepositoryFailureTest.kt:271`，即 `assertOldFileIntact` 的 `assertArrayEquals`（第 2 条断言） |
| 失败信息 | `notes.json 字节必须逐字节不变: array lengths differed, expected.length=173 actual.length=0` |

含义：在非原子写路径下，`save()` 会**谎报成功**并把旧文件清成 0 字节；而「旧文件字节不变」这条检查确实抓到了它，
且基准是 173 字节的有效 JSON —— 不是空文件。

**边界**：这仍然是人造变异，不是真实文件系统故障。

---

## 6. 两个正常测试补上失败时也执行的收尾

`NotesRepositoryFileTest` 与 `NotesWriteCoordinatorFileTest` 从「测试体内联 `val context`」
改为字段 + `@Before/@After`，并共用 `finishIsolatedTest`：

```kotlin
internal fun finishIsolatedTest(sandbox: IsolatedAppContext, realBefore: String): List<String> {
    val violations = mutableListOf<String>()

    try {
        val after = realNotesFingerprint()
        if (after != realBefore) {
            violations += "路径隔离失效：真实 notes.json 指纹前后不一致（before=$realBefore after=$after）"
        }
    } catch (t: Throwable) {
        violations += "读取真实 notes.json 指纹失败：$t"
    }

    var cleared = false
    try {
        cleared = sandbox.dispose()          // 只删自己的 UUID 目录
    } catch (t: Throwable) {
        violations += "清理沙箱时抛异常：$t"
    }
    if (!cleared) violations += "本测试的 UUID 沙箱未被删除：${sandbox.rootDir}"

    val parent = sandbox.rootDir.parentFile
    if (parent == null || !parent.isDirectory) {
        violations += "公共父目录 save-order-2/ 不得被删除：$parent"
    }

    return violations
}
```

`dispose()` 改为返回 `!rootDir.exists()`，**清理失败不再被静默忽略**。
`@After` 不抛单个 assert，而是收集所有违规一次性断言，所以「指纹不一致」和「清理失败」会同时报出来，
不会因为前一条失败就跳过清理。

覆盖到的失败场景：测试体任意位置抛异常 → `@After` 仍执行 → 指纹比对 + 清理 + 违规清单断言。

### 目录实测（手动装包 + `am instrument`，21 项跑完后 `run-as` 查看）

```
cache/save-order-2/   → 存在，空目录（只有 . 和 ..，UUID 子目录已全部删除）
files/                → 只有 profileInstalled（24 B，安装时由系统写入）
                        notes.json 不存在
```

未删除公共父目录 `save-order-2/`，符合要求。

---

## 7. 表述收窄清单

| 位置 | 原文 | 改为 |
| --- | --- | --- |
| `IsolatedNoteStorage.kt` 类注释 | 「真机 notes.json 一个字节都不会被碰到」 | 「带来的是**路径隔离**：真实 notes.json 与沙箱路径不同，测试前后的指纹比对应当一致。它不构成『进程内任何时刻都绝对没有触碰真实路径』的证明」 |
| `NotesRepositoryFileTest.kt` 注释 | 无边界说明 | 「本机真实 `files/` 里本来就没有 notes.json，前后都是 `<absent>`，这证明隔离没有回落到真实路径，但不等于『进程内任何时刻都绝对没有触碰过真实路径』」 |
| `NotesRepositoryFileTest.kt` 注释 | 「真实笔记文件一个字节都没被碰过」 | 删除，改由 `@After` 的指纹比对承担 |
| `NotesWriteCoordinatorFileTest.kt` 注释 | 无边界说明 | 同上「路径隔离……不等于绝对没有触碰」 |
| `NotesRepositoryFailureTest.kt` 类注释 | 只有故障注入边界 | 追加隔离边界，并保留「不得表述成真实故障」 |

全仓已无「真实底层故障已验证」「一个字节都不会被碰到」这类表述（`grep` 复核）。

---

## 8. 执行记录

| # | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 1 | `git diff --check` | 0 | 无空白字符问题 |
| 2 | `:app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` | 0 | BUILD SUCCESSFUL in 22s，0 error |
| 3 | `connectedDebugAndroidTest -P...class=NotesRepositoryFailureTest` | 0 | **4 tests, 0 failures** |
| 4 | M1′ 探针（变异 + 单项） | 1 | 1 test, 1 failure（**预期**，见第 5 节） |
| 5 | `./gradlew :app:connectedDebugAndroidTest`（全量，还原后） | 0 | **21 tests, 0 failures, 0 errors, 0 skipped** |
| 6 | `am instrument` 全量（手动装包，用于查目录） | 0 | `OK (21 tests)` |
| 7 | `git diff --check`（还原后复核） | 0 | 通过 |

全量 21 项构成：

| 测试类 | 数量 | 失败 |
| --- | --- | --- |
| `ExampleInstrumentedTest` | 1 | 0 |
| `data.BackupNotesParserTest` | 6 | 0 |
| `data.NotesRepositoryFailureTest` | 4 | 0 |
| `data.NotesRepositoryFileTest` | 1 | 0 |
| `data.NotesWriteCoordinatorFileTest` | 1 | 0 |
| `data.NotesWriteCoordinatorTest` | 5 | 0 |
| `ui.AttachmentImageLoaderTest` | 3 | 0 |

没有新增 `@Test`，所以仍是 21 项。M1′ 探针在跑完后已从源码删除（`grep` 复核 0 处残留）。

设备：`emulator-5554`，AVD `Medium_Phone_API_37.0`，Android 17 / API 37，`sdk_gphone64_x86_64`，
x86_64，1080×2400 @420dpi。只在模拟器运行，未安装到有真实数据的设备。

依赖与 AAR 未改动（`app/libs/sherpa-onnx-2.0.1.aar` 仍在，`libs.versions.toml` 无改动）。

---

## 9. 还原核验

M1′ 实验后逐文件还原并复核：

```
NotesRepository.kt  blob = df3792064346a364d6fb8bd5dd2851faf3905d71   （与实验前一致）
openStream 调用     line 53: fileOps.openStream(temp)                 （已恢复）
m1TargetedCorruptionAfterValidV1 残留 = 0 处
git diff --stat     NotesRepository.kt | 19 +++--（+13 −6）           （与实验前一致）
```

---

## 10. 未覆盖项（全部保留）

1. **进程终止 / 进程重启恢复**（不在本批范围）。
2. **目录 fsync**：`rename` 之后没有对 `filesDir` 做 fsync，掉电时目录项仍可能丢。
3. **备份导入事务**（`replace()` 路径）。
4. 真实 `ENOSPC` / `EIO` / 真实掉电：未制造，不得据本报告推断已覆盖。
5. 并发下故障：四项测试都是顺序调用。
6. 大体积笔记、低存储空间下的降级行为。
7. 真实设备：只有 x86_64 模拟器。
8. **本轮新增说明**：`files/profileInstalled` 是应用安装时由系统写入的 24 字节文件，
   与本 App 业务数据无关；指纹核验只针对 `notes.json`，它仍是 `<absent>`。
9. **本轮新增说明**：M1′ 的破坏是「把临时文件路径换成正式文件」造成的，
   属于代码级变异，不是内核返回的写入失败。

---

## 11. 当前工作区状态

```
HEAD = 26a5cd9669c76ce267b5eaaea2fafe64a9e92269   （未提交）
分支 = fix/backup-safe-1
M  app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt   （+13 −6）
?? app/src/main/java/com/example/myapplicationfirstapp/data/NotesFileOps.kt      （42 行，未改）
?? app/src/androidTest/.../data/NotesRepositoryFailureTest.kt                    （376 行）
?? app/src/androidTest/.../data/IsolatedNoteStorage.kt                           （113 行）
?? app/src/androidTest/.../data/NotesRepositoryFileTest.kt                       （138 行）
?? app/src/androidTest/.../data/NotesWriteCoordinatorFileTest.kt                 （120 行）
```

未提交、未推送、未合并 main。
