# BACKUP-SAFE-1A 实施报告（准备与验证层）

生成时间：2026-11-11 16:35 (GMT+8)
基线：`d252ed3c2a9780c305b7a1be515186695a8e9c72`（`fix/backup-safe-1`）
本轮范围：**只做 1A 准备层**。未接入导入流程、未改 UI、未写 live 数据、未提交、未推送、未操作真机。

> **1A 只是准备层，不代表备份导入风险已关闭。** 现在能保证的只是：
> 「读一次 URI → 有上限地校验 → 得到可提交或可拒绝的结论，且任何返回路径都不改正式数据」。
> 真正的写入、回滚、恢复仍是 1B，尚未实施。

---

## 1. 结论摘要

| 项 | 状态 |
| --- | --- |
| 1A 准备层源码 | **已实施**（3 个新文件，1 个文件小改） |
| 1A 测试 | **已实施**（4 个测试类 + 1 个支撑文件，41 个用例） |
| 全量 instrumentation | **62 项，0 失败**（新增 41 + 原有 21） |
| 接入生产流程 | **未接入**（`grep` 证实新类只被测试引用） |
| 1B 提交/恢复 | **仅为方案**（见 §8，已按六条否决意见修订） |
| 本地提交 / 推送 | 无。工作区停在未提交状态 |

---

## 2. 改动清单

### 2.1 新增（生产）

| 文件 | 行数 | blob(SHA-1) | 作用 |
| --- | --- | --- | --- |
| `data/BackupFileOps.kt` | 81 | `d5469014c06d6cea0b3f2ffdf84e6d5f01c8de0f` | 准备阶段 IO 接缝（读源/写暂存/fsync/改名/删除/摘要 + 测试钩子） |
| `data/BackupImportModels.kt` | 176 | `b10b32fec7f51b2808a1eac646f2e2dbf6105d1e` | 限额、三种结果、拒绝原因、告警与展示清洗 |
| `data/BackupImporter.kt` | 524 | `5bbb8ce444e13ace3346f33a781ce9f28015d679` | 读一次 URI、扫描校验、暂存、取消清理 |

### 2.2 修改（生产）

| 文件 | 变化 | blob |
| --- | --- | --- |
| `data/BackupNotesParser.kt` | +12 −3：拆出 `parseBackupArrayOrThrow`，`parseBackupNotesOrThrow` 改为委托 | `002aef08750daaa123745dd11e40fd2ca830988e` |

拆分的理由：导入层需要先拿到 `JSONArray` 做字段类型检查，再转 `Note`；
原函数直接返回 `List<Note>`，无法在转换前拦截类型错误。行为与原有测试完全一致（`BackupNotesParserTest` 6 项全过）。

### 2.3 新增（测试）

| 文件 | 行数 | blob |
| --- | --- | --- |
| `androidTest/.../ImportTestSupport.kt` | 222 | `8771eeea274cc5fb4ba9a63213e16d6e4dd8c802` |
| `androidTest/.../BackupImporterPrepareTest.kt` | 270 | `decb054cba62006bb9e6b163863c85695e8fed53` |
| `androidTest/.../BackupImporterValidationTest.kt` | 264 | `5f986f343781c3a5ff8aa37706499c16812deeb2` |
| `androidTest/.../BackupImporterLimitsTest.kt` | 163 | `84e2327011e2441bb3eedf8cca838191c634878e` |
| `androidTest/.../BackupImporterCancelTest.kt` | 106 | `97feed029118e288b35e83c3aea8a0d6cb09d3f2` |

完整 diff（含新文件全文，1887 行）：`D:/SAVE-ORDER-2/logs/backup-1a/BACKUP-SAFE-1A.diff`
sha256 `3659881eeb8bc56571ccf489891008e2d827dd811bd7308f3f65ea284b60ca55`

**没有改动**：`NotesRepository.kt`（旧 `importBackup` 原样保留、未被调用）、`NotesState.kt`、
`MainActivity.kt`、`Note.kt`、`BackupNotesParserTest.kt`、`IsolatedNoteStorage.kt`、导出逻辑、依赖、清单文件。

---

## 3. 逐条对照本轮要求

### 3.1 有上限地读一次 URI，建立独立私有暂存副本

`BackupImporter.prepare(uri)`：

```
files/.import/staging-<uuid>/backup.zip      ← copySource()：一次 openSource(uri)，边读边计数
files/.import/staging-<uuid>/attachments/**  ← 解压落点
files/.import/staging-<uuid>/…               ← 后续校验只读这两个
```

- `copySource` 是**唯一**触碰 `uri` 的地方；每 64 KiB 检查一次取消，并按**实际读到的字节**卡 `maxZipBytes`。
- 暂存在 `filesDir` 而不是 `cacheDir`：进程被杀后材料还在（1B 恢复要用），也不会被系统"清理缓存"顺手删掉。
- 测试 `sourceUriIsOpenedExactlyOnce` 用计数接缝断言 `openSource` 调用次数 == 1。

### 3.2 后续解析与确认使用同一份副本

`scanArchive()` 只读 `backup.zip`；`notes.json` 读进内存并在内存里做全部校验；
附件解压到同一个 staging 目录。没有任何路径在 `prepare()` 之后再次打开 `uri`。

「空备份二次确认」由 1B 的 UI 负责调用同一个 `PreparedBackup`（token 指向 staging），届时也不需要重读 URI。

### 3.3 三种结果 + 两类告警

```kotlin
sealed interface PrepareResult {
  data class Ready(val prepared: PreparedBackup)   // 有笔记
  data class Empty(val prepared: PreparedBackup)   // 合法空备份（notes.json == []）
  data class Invalid(val reason: InvalidReason, val detail: String?)
}
```

告警（只提示，不阻止导入）：

| 类别 | 触发 | 例子 |
| --- | --- | --- |
| `MissingImages` | 笔记引用（`images` + 正文 `![图片]()`）里的名字不在备份附件集合 | `missingImagesWarnButKeepReferences` |
| `RecordingsNotInBackup` | 笔记引用 `audio` 或正文 `!audio()` | `recordingReferencesWarnWithoutTouchingRecordings` |

**引用原样保留**：导入层不改 `note.images` / `note.audio`，只把缺失情况报出来。
录音这一侧只做「备份不含录音」的静态提示，不去读 live `recordings/` 目录（准备层不碰任何 live 路径）。

### 3.4 校验项

| 项 | 规则 | 结果 |
| --- | --- | --- |
| 危险路径 | 附件名不得为空 / `.` / `..` / 以 `.` 开头 / 含 `/` `\` / 含 NUL / 含控制字符 / 超长；只允许 `attachments/` 一个目录占位 | `DangerousPath` |
| 重复条目 | 同名附件出现两次；两个 `notes.json` | `DuplicateEntry` |
| 重复 note id | 转换后 id 去重检查 | `DuplicateNoteId` |
| 重复 todo id | 同一笔记内非空待办 id 去重 | `DuplicateTodoId` |
| 字段类型 | **只在字段存在时**检查：`title/content/id` 字符串、`createdAt/updatedAt/colorIndex` 数字、`pinned/checklist` 布尔、`tags/images/audio` 字符串数组、`todos` 对象数组（`id/text` 字符串、`done` 布尔）、`deletedAt/remindAt` 数字或 null | `WrongFieldType`（带 `notes[i].field` 定位） |
| 图片引用安全 | `images` 与正文 inline 引用走同一套文件名规则 | `UnsafeImageReference` |
| 空备份夹带文件 | `notes` 为空但存在**非零字节**附件 | `EmptyNotesWithAttachments` |

三条与兼容性有关的决定：

1. **缺字段一律放行**（旧备份没有 `audio`/`todos`/`remindAt`），默认值仍由 `noteFromJson` 补。
   `legacyBackupWithoutOptionalFieldsKeepsDefaults` 覆盖。
2. **显式 `null` 的时间戳单独处理**：`noteFromJson` 用 `optLong`，`deletedAt: null` 会变成 `0`
   （= 1970 年删除，会被当过期回收站清掉）。导入层在转换后把显式 `null` 还原成 `null`，
   不改 `noteFromJson` 本身（普通读取路径行为不变）。`nullOptionalTimestampsAreAccepted` 覆盖。
3. **零字节附件不算实际附件**：旧导出不会产生，删掉并视为空；`[]` + 零字节附件仍是 `Empty`。

### 3.5 资源限制按实际字节

| 上限 | 值 | 计数方式 |
| --- | --- | --- |
| URI 副本 | 128 MiB | 复制循环里实测字节 |
| notes.json | 32 MiB | 解压循环 |
| 单条目 | 64 MiB | 解压循环（`ZipEntry.size` 完全没读） |
| 累计解压 | 256 MiB | 所有条目共享计数，**未知条目也计入** |
| 条目数 | 2000 | 每个 entry 计数，**未知条目也计入** |
| 文件名长度 | 128 | 校验时 |

- 无压缩比判定——比值来自声明长度，不可信；全部改成"边读边计数"。
- `declaredEntrySizeIsNotTrusted` 先断言 zip 本地头里 uncompressed size 确实是 0，
  再证明 200 字节的条目仍然被 `maxEntryBytes=100` 拦下。
- 未知条目：不落盘，但仍然逐块读取并计入单条/累计/条目数（`unknownEntriesAlsoConsumeTheBudget`）。

### 3.6 取消与清理

- `copySource` / `scanArchive` / `copyEntry` 每块都 `ensureActive()`。
- `prepare()` 捕获 `CancellationException` → `deleteTree(staging)` → 原样抛出，不吞。
- 其他异常 → 同样清理，返回 `Invalid(StagingFailed)` 或具体原因。
- `discard(prepared)` 供 UI 取消确认时使用。
- 用例：`cancellingDuringCopyCleansStagingAndKeepsLiveData`（用阻塞接缝把取消时机固定在第一次复制回调）、
  `cancelledPreparationLeavesNoResultToCommit`。

### 3.7 隔离测试

所有用例都跑在 `isolatedStorage()` 的 UUID 沙箱里（复用 SAVE-ORDER-2 的 `IsolatedAppContext`），
`@Before` 取真实数据指纹、`@After` 比对并删除沙箱（失败时也执行）：

```
files/notes.json            sha256
files/attachments/          文件名 + 长度 + mtime
files/recordings/           文件名 + 长度 + mtime
```

附加断言：准备层跑完后，沙箱里**没有** `notes.json`、没有 `attachments/` 目录
（`prepareDoesNotTouchLiveNotesOrAttachments`），证明它连沙箱里的"正式路径"都没写。

---

## 4. 产品规则落实

| 规则 | 实现 |
| --- | --- |
| 缺失附件告警、不拒绝、保留文字与引用 | `WarningKind.MissingImages`，`notes` 原样保留 |
| 危险路径 / 重复条目 / 重复 id 整包拒绝 | 全部 `Invalid`，不做"跳过继续" |
| `[]` 夹带实际附件拒绝；目录占位不算附件 | `EmptyNotesWithAttachments`；`attachments/` 占位放行 |
| 不扩展录音导出、不删除现有 recordings | 导出代码未动；准备层不访问 `recordings/` |
| 展示缺失文件名限制长度、处理控制字符 | `sanitizeForDisplay`（控制字符→`?`，换行→空格，超长→`…`）+ `displaySummary(maxNames=5, maxNameLength=40)` |

---

## 5. 编译与测试证据

### 5.1 编译

```
$ ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
BUILD SUCCESSFUL in 10s
COMPILE_EXIT=0
```

日志：`logs/backup-1a/compile.log`（本次为增量构建，首次全量编译同样退出 0）

### 5.2 测试

```
$ ./gradlew :app:connectedDebugAndroidTest
BUILD SUCCESSFUL
EXIT=0
```

- 设备：`emulator-5554`，AVD `Medium_Phone_API_37.0`，Android 17 / API 37，x86_64。
  **本轮没有连接真机**；`connectedDebugAndroidTest` 只在模拟器上跑（该任务结束会卸载模拟器上的 App，
  模拟器无真实数据）。
- JUnit XML：`logs/backup-1a/full-androidtest.xml`
- 结果：**tests=62, failures=0, errors=0, skipped=0**

| 类 | 用例数 | 说明 |
| --- | --- | --- |
| `BackupImporterPrepareTest` | 13 | 正常/空/告警/展示/暂存/未接线 |
| `BackupImporterValidationTest` | 17 | 结构、路径、重复、类型、引用、空夹带 |
| `BackupImporterLimitsTest` | 9 | 五类上限 + 默认值 + 不信任声明长度 |
| `BackupImporterCancelTest` | 2 | 取消清理、取消无可提交结果 |
| 原有 7 个类 | 21 | 与基线一致，全部通过（含 `BackupNotesParserTest` 6 项） |

覆盖到的场景（对应审阅要求逐项）：

- 成功：`readyReturnsNotesAttachmentsAndDigest`
- 超限：`tooManyEntriesIsRejected`/`singleEntryOverRealByteLimitIsRejected`/`totalBytesOverLimitIsRejected`/`notesOverLimitIsRejected`/`zipCopyOverLimitIsRejected`/`overlongAttachmentNameIsRejected`/`unknownEntriesAlsoConsumeTheBudget`/`declaredEntrySizeIsNotTrusted`
- 危险包：`dangerousAttachmentPathsAreRejected`（7 种路径）/`nestedDirectoryEntryIsRejected`/`duplicateAttachmentEntryIsRejected`/`duplicateNotesEntryIsRejected`/`corruptedEntryPayloadIsRejected`
- 缺失附件：`missingImagesWarnButKeepReferences`
- 空备份：`emptyArrayIsReportedAsEmptyNotInvalid`/`directoryPlaceholderDoesNotCountAsAttachment`/`emptyNotesWithRealAttachmentIsRejected`/`emptyNotesWithZeroByteAttachmentIsStillEmpty`
- 取消：2 项（见上）

### 5.3 过程中的失败与重跑（如实记录）

| 次序 | 结果 | 原因 | 处理 |
| --- | --- | --- | --- |
| run1 | 40 项，2 失败 | ① `deletedAt:null` 被 `optLong` 变成 0；② `attachments/../x` 被 Android 的 `ZipInputStream` 先拒，异常消息 `Invalid zip entry path`，我误判成 `NotZip` | ① 加 `normalizedNote` 归一 null；② 按异常消息把这类归到 `DangerousPath` |
| run2/3 | 2 用例通过 | 验证修复 | — |
| `run-final` | **无效**（8 项后 `System has crashed`） | 模拟器 `system_server` 崩溃（`DeadSystemException`，与本次代码无关） | 作废该轮，不作为证据 |
| `run-final2/3` | **0 项**，Gradle 仍报 SUCCESS | 崩溃后模拟器 `/sdcard` 传输端点断开、`cmd package` 服务缺失，APK 安装失败 | 通过 XML 的 `tests=0` 发现「假成功」，`adb reboot` 后重跑 |
| `run-final4` | **62 项，0 失败** | — | 作为最终证据 |

**注意**：AGP 在设备异常时会给出 `BUILD SUCCESSFUL` 但 XML 里 0 项，所以本轮判据以 XML 的 `tests` 计数为准，不看 Gradle 的退出码。

---

## 6. 隔离验证的结论与局限

- 结论：41 个新用例、每一次 `@After` 都做了真实 `notes.json` / `attachments` / `recordings` 的前后比对，
  全部一致；失败用例（现在都已修复）也走同一套比对，没有出现"测试失败时把真实数据带脏"的情况。
- 局限：这只是**路径隔离 + 端点指纹**。它证明"测试期间没有落到真实路径上"，
  不证明"进程内任何时刻绝对没有触碰真实路径"；录音/附件用「名字+长度+mtime」而不是内容哈希
  （真机上 248 MB，逐字节哈希会让每个用例多花十几秒）。

---

## 7. 已实施 / 未实施

**已实施**：`BackupImporter.prepare()`、`discard()`、限额、校验、告警、展示清洗、取消清理、41 项测试。

**未实施（有意）**：
- 任何写入 live 的代码：不写 `notes.json`、不写 `attachments/`、不调 `pruneAttachments`。
- 旧 `NotesRepository.importBackup(uri)` 未删除、未修改、未被新代码调用（它仍是"边解析边写"的旧实现，
  1B 会用新路径替换它）。
- UI 未改：`MainActivity` 的两处入口、确认对话框、提示文案都没动。
- 无新权限、无依赖变更、无数据库迁移。

---

## 8. 修订后的 1B 方案（未实施）

上一版方案被否的六点逐条回应如下。**这一节只是方案，代码一行没写。**

### 8.1 不能用 notes.json 的目标哈希判断是否提交

反例：备份的 `notes.json` 与当前文件**逐字节相同**（例如原样导入同一份备份），
但附件有增有改。此时 `sha256(notes.json) == journal.notesDigest` 在提交**之前**就成立，
按哈希判定会把「尚未提交」误判成「已提交」，从而前滚清理、把回滚材料删掉。

替代方案：**独立事务目录 + 提交标记 + 旧 notes.json 实体副本**。

```
files/.import/txn-<txId>/
    journal.json          {v:1, txId, phase:"prepared", notesPrev:{len,sha256},
                           notesNew:{len,sha256}, plan:{overwritten[],added[],deleted[]}}
    notes.prev.json       当前 notes.json 的完整字节副本（在动任何 live 文件之前写好并 fsync）
    attachments.prev/     将被覆盖的旧附件（rename 进来，不复制）
    attachments.new/      待写入的新附件（从 staging rename 进来）
```

写入顺序（严格）：

| 步 | 动作 | 失败后的语义 |
| --- | --- | --- |
| 1 | 建 txn 目录，复制 `notes.json` → `notes.prev.json`，fsync | 未动 live，直接 `ABORTED` |
| 2 | 写 `journal.json`（phase=prepared）并 fsync | 同上 |
| 3 | 把 staging 附件 rename 到 `txn/attachments.new/` | 同上（只是搬暂存） |
| 4 | 逐个应用：旧文件先 rename 到 `attachments.prev/`，再 rename 新文件进 `attachments/` | 逆序回滚（见矩阵） |
| 5 | 写 `notes.json.tmp` 并 fsync | 回滚附件 |
| 6 | **提交点**：rename `notes.json.tmp` → `notes.json` | 回滚附件 + 用 `notes.prev.json` 覆盖回来 |
| 7 | 写 `notes.commit.json {txId, notesDigest}` 并 fsync ← **判定标记** | 回滚（用户还没收到成功提示） |
| 8 | 内存发布（NonCancellable） | 不回滚磁盘；事件里标 `memoryPublishFailed` |
| 9 | 排提醒、prune | 不回滚；记录 `cleanupPending` |
| 10 | 删 txn/journal/commit marker/staging（journal 最后删） | `COMMITTED_CLEANUP_PENDING` |

判定规则（**只看标记，不看内容相等**）：

```
commit marker 存在 && marker.txId == journal.txId → 已提交 → 前滚清理
否则                                              → 未提交 → 用 notes.prev.json 回滚
```

- 若在 6 与 7 之间掉进程：marker 不存在 → 回滚。用户从未看到"导入成功"，语义一致。
- 若在 7 之后掉进程：marker 存在 → 前滚。数据已是新的，只差清理。
- `notes.prev.json` 始终存在，因此**任何**回滚都不依赖"能否重建旧内容"。

### 8.2 `newestSaveJob.join()` 不能作为"保存已排空"的证明

它只能等到"当前最后一个已知 job"，无法阻止 join 之后新发起的保存，也无法证明更早的 job 已经结束。
正确性改为三层，join 只作为减少空转的优化：

1. **修改前门禁**：`NotesState.importing == true` 时，所有改动型方法（`updateNote`/`addImage`/
   `addAudio`/`removeImage`/`setChecklist`/`toggleTodo`/`moveToTrash`/`setReminder`…）
   在**碰内存之前**直接返回。这样"导入期间新产生的保存请求"根本不会存在。
2. **共享协调器**：导入走 `replace{}`，与 `save{}` 共用 `writer` 互斥锁；`replace` 期间 `reserve()` 返回 null。
3. **提交成功后代次失效**：`generation++` 只发生在 `Committed`；任何旧 ticket 的
   `ticket.generation != generation` 或 `sequence <= savedSequence`，`save()` 直接拒绝写入。

`persist()` 不再 `checkNotNull` 抛崩，也不再静默丢：

```kotlin
sealed interface PersistOutcome { object Written; object DroppedReplacing; data class Failed(val e: Throwable) }
```

- 拿不到 ticket → `DroppedReplacing`，`blockedSaveCount++`，写 `persistenceError`，并暴露给 UI；
- 由于 §2 的门禁，这条路在正常流程里不可达；可达即说明有跨代异步回调（见 8.4 的清理规则）。

### 8.3 `replace` 返回失败不能推进 generation；回滚失败要停写

`replace` 的签名改成显式结果，只有 `Committed` 才推进：

```kotlin
sealed interface ReplaceOutcome<out T> {
  data class Committed<T>(val value: T) : ReplaceOutcome<T>
  data class Aborted(val reason: String, val rollback: RollbackState) : ReplaceOutcome<Nothing>
  data class Blocked(val reason: String) : ReplaceOutcome<Nothing>
}
enum class RollbackState { OK, FAILED }
```

- `Aborted(rollback=OK)` → `generation` 不变；`replacing=false`；内存与磁盘都是旧状态；可继续使用。
- `Aborted(rollback=FAILED)` → 写 `files/.import/BLOCKED`（含 txId 与原因），
  `coordinator.blocked = true`，此后 `reserve()` **永久返回 null**：
  读还可以，写全部拒绝，UI 显示"导入失败且未能恢复，请重启修复"。
  只有启动恢复成功清除 `BLOCKED` 后，才会恢复可写。**不能直接恢复正常使用。**
- `Blocked` 与 `Aborted(FAILED)` 同等对待。

### 8.4 跨代回调只能清理"自己创建的、尚未被接纳的"文件

规则（`releaseOwnedFile(ownerToken, name)`）：

1. 文件必须由本次操作创建（`importImage` 生成的名字、`prepareCameraTarget` 占的名字），
   名字里带本次操作的 `ownerToken`（或记在内存的 `ownedNames` 集合里）；
2. 检查**没有任何**笔记引用它（`images` 与正文 inline 都要查）；
3. 检查它不是共享文件（录音、字体、备份）；
4. 三条都满足才删除；否则保留并记一条"泄漏"日志。

具体：

| 回调 | 跨代时做什么 |
| --- | --- |
| 相册选图 | 删掉本次 `importImage` 刚写的那个文件（名字本次生成、未被接纳） |
| 相机返回 | 只删本次 `prepareCameraTarget` 占的、且未被任何笔记引用的文件；相机可能已写入内容，仍属本次操作 |
| 录音挂载 | **不删任何录音文件**，只丢弃"新增引用"并提示 |
| 导入 prune | 只删"导入前就存在、导入后不再被引用"的名字；跳过 `.` 开头的保留目录；绝不删本次新写入的文件 |

### 8.5 磁盘提交 / 内存发布 / UI 消费是三件事

```
diskCommit:    Committed | RolledBack | Blocked     ← 由 marker + 文件系统判定，重启后仍可重建
memoryPublished: Boolean                            ← 只描述 _notes 是否已替换
uiOutcome:     Channel<ImportOutcome>(CONFLATED)    ← 一次性事件，UI 主动消费
```

- **成功 = `diskCommit == Committed && memoryPublished`**；任何其它组合都不能显示"已导入"。
- `NonCancellable` 只保证"提交段不被打断"，**不保证**被取消的调用方还能收到返回值。
  所以结果不通过协程返回值穿过取消点，而是在 NonCancellable 段内投递到 `uiOutcome`（Channel），
  UI 恢复后照样能消费。
- 取消点只有两个合法位置：**进入 6 之前**（未提交，回滚/终止）与**离开 7 之后**（已提交，前滚）。
  6→7 之间不检查取消。
- 若 UI 始终没消费事件（进程被杀），下次启动由恢复结果生成一条"上次导入已完成/已回滚"的提示，
  而不是永久沉默。

### 8.6 恢复前置规则要覆盖所有存储入口

调用点（现状）：

| 入口 | 位置 | 现状 |
| --- | --- | --- |
| 主界面读笔记 | `MainActivity` → `NotesState.load()` → `NotesRepository.load()` | 直接读 `notes.json` |
| 开机重排提醒 | `ReminderReceiver.kt:56` `ReminderBootReceiver` → `NotesRepository(context).load()` | **第二个 Repository 实例**，绕开 UI 状态 |
| 写入 | `NotesState.persist()` → `NotesRepository.save()` | 直接写 |

改法：把恢复放进 `NotesRepository` 的共享单例 `ImportRecovery`（`companion object` 内 `Mutex` + `@Volatile done`），
在 `load()` 与 `save()` 的**第一个挂起点之前**执行。`ReminderBootReceiver` 调用 `load()` 时自然被覆盖；
两个 Repository 实例共享同一把锁，不会各自恢复一次。

规则：

| 情况 | 处理 |
| --- | --- |
| `BLOCKED` 存在 | 不读写任何文件；`load()` 返回磁盘现状（可能是中间态），`save()`/导入一律拒绝；UI 提示需修复 |
| journal 缺失、无 txn | 清理 7 天以上、无引用的 `staging-*`；其余不动 |
| journal 缺失、有 `notes.commit.json` | 按 marker 判定（digest 匹配→清理；不匹配→quarantine，保留） |
| journal 存在但解析失败 | **不盲删**：`journal.bad-<ts>` 隔离，保留 txn；marker 能自证已提交→前滚清零；否则置 `BLOCKED` |
| marker 存在且 txId 匹配且在 6 之后 | 前滚：补 prune、删 prev/new/journal/marker/staging |
| marker 不存在 | 回滚：`notes.prev.json` → tmp+fsync+rename；`attachments.prev/*` 搬回；删 `plan.added` 中仍在 live 且未被引用的 |
| 重复恢复 | 幂等：每步都是"按材料覆盖/删除"，journal 最后删；进程再崩也不产生新状态 |
| 残留 `txn-*` 但无 journal | 移到 `.import/quarantine-<ts>/`，报告，不删 |
| 恢复本身失败 | 保留全部材料 + `BLOCKED`；禁止写入；下次启动再试 |

### 8.7 1B 状态机

```
IDLE
 └─prepare→ STAGED(Ready|Empty)
      ├─取消→ discard → IDLE
      └─确认→ COMMITTING  (importing=true；先 join 最后一次 save，仅作优化)
            ├─ txn 建立/日志失败 ─────────→ ABORTED        (live 未变)
            ├─ 附件应用失败 ──────────────→ ROLLING_BACK → ROLLED_BACK
            ├─ notes 写入/改名失败 ───────→ ROLLING_BACK → ROLLED_BACK
            ├─ 回滚失败 ─────────────────→ BLOCKED        (保留材料、禁止写)
            ├─ rename 成功 → 写 marker ──→ COMMITTED
            │     ├─ 内存发布失败 ──────→ COMMITTED_MEMORY_STALE (重启后以磁盘为准)
            │     ├─ 清理失败 ─────────→ COMMITTED_CLEANUP_PENDING
            │     └─ 全部成功 ─────────→ PUBLISHED → CLEANED
            └─ 取消（仅在 6 之前） ─────→ ROLLED_BACK
启动：RECOVERING → CLEANED_FORWARD / ROLLED_BACK / QUARANTINED / BLOCKED
```

### 8.8 失败/中断处理矩阵

| 失败点 | 现象 | 判定依据 | 动作 | 结果状态 | 保留材料 |
| --- | --- | --- | --- | --- | --- |
| 建 txn / 复制 prev | 抛异常 | 无 journal | 删 txn、staging | `ABORTED` | 无 |
| 写 journal | 抛异常 | 无 journal | 删 txn、staging | `ABORTED` | 无 |
| rename 附件（第 k 个） | 抛异常 | journal phase=prepared | 逆序：新增的删、prev 搬回 | `ROLLED_BACK` | 失败则全留 |
| 写 notes.json.tmp / fsync | 抛异常 | 同上 | 同上 | `ROLLED_BACK` | 同上 |
| rename tmp → notes.json | 抛异常（旧文件仍在） | 同上 | 同上 | `ROLLED_BACK` | 同上 |
| 写 commit marker | 抛异常 | 无 marker | 用 prev 覆盖 notes.json + 回滚附件 | `ROLLED_BACK` | 失败则 `BLOCKED` |
| 进程被杀（6 前） | 重启 | 无 marker | 恢复=回滚 | `ROLLED_BACK` | 全部 + journal |
| 进程被杀（6 与 7 之间） | 重启 | 无 marker | 恢复=回滚（用户未见过成功） | `ROLLED_BACK` | 同上 |
| 进程被杀（7 之后） | 重启 | marker 匹配 | 恢复=前滚清理 | `CLEANED_FORWARD` | 仅 txn 残留 |
| 内存发布异常 | 运行中 | — | 不碰磁盘；事件标 `memoryStale`；下次启动以磁盘为准重读 | `COMMITTED_MEMORY_STALE` | 无 |
| prune 异常 | 运行中 | — | 不回滚；记 `cleanupPending` | `COMMITTED_CLEANUP_PENDING` | txn |
| journal 损坏 | 启动 | 无法解析 | quarantine + 视 marker 判定；无法判定→`BLOCKED` | `QUARANTINED`/`BLOCKED` | 全部 |
| 回滚本身失败 | 任意 | — | 不删任何材料；写 `BLOCKED`；禁止写入 | `BLOCKED` | 全部 |

### 8.9 1B 的文件改动预估（未实施）

| 文件 | 变化 |
| --- | --- |
| `data/BackupImporter.kt` | 增加 commit/rollback/recover（现 524 行 → 约 900 行），或拆 `BackupCommit.kt` |
| `data/BackupFileOps.kt` | 增加 journal 读写、marker 读写、目录 fsync 尝试、move |
| `data/NotesRepository.kt` | `load()`/`save()` 前置恢复；删除旧 `importBackup(uri)` 的边读边写实现 |
| `data/NotesWriteCoordinator.kt` | `replace` 显式结果 + `blocked` 状态 |
| `ui/NotesState.kt` | `prepareImport`/`commitPrepared`、`importing` 门禁、`contentGeneration`、`PersistOutcome`、提醒重排、事件通道 |
| `MainActivity.kt` | 两段式确认（普通/空备份）、导入中禁用、结果文案、`persistenceError` 提示 |
| `ui/NoteEditorScreen.kt` | 三个回调捕获 generation 并透传 |
| 测试 | `BackupCommitTest`、`BackupRollbackTest`、`BackupRecoveryTest`、`SaveGatingTest`（§8.8 每一行一条） |

---

## 9. 边界与未覆盖

1. 1A **不写**任何 live 数据，所以"导入是否安全"这个问题本轮**没有**被回答，只回答了"输入是否可信"。
2. 资源上限是**单机内存/磁盘保护**，不防"合法大小但内容恶意"的图片（没有解码校验）。
3. 未检查图片内容是否真的是图片（只有扩展名/长度）；这属于解码成本，1B 不打算做。
4. 未校验 `updatedAt` 与 `createdAt` 的先后、`remindAt` 是否为未来时间等语义。
5. `notes.json` 的 UTF-8 解码用「非法字节替换」策略（`String(bytes, UTF_8)`），不做严格解码失败检测；
   真正的非法字节会变成 `?`，再由 JSON 解析决定是否拒绝。
6. 未处理 zip 加密条目（`ZipInputStream` 对加密条目读不出内容，会走 `CorruptEntry`/`NotZip` 路径，不会误接受）。
7. 未覆盖：并发两个 `prepare`（token 不同、目录不冲突，但都会占磁盘）、跨进程、SAF 提供方中途失效（会走 `StagingFailed`）。

---

## 10. 下一步

等审阅。通过后按 §8 实施 1B（建议顺序：事务目录与 marker → 回滚 → 恢复 → 状态层门禁与事件 → UI）。
本轮**未**创建提交、**未**推送、**未**合并 main。
