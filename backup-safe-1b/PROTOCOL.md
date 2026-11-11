# BACKUP-SAFE-1B 提交协议（定稿）

源码基线：`26a0de3cc80636718059c118d5af6e4c6f179fc0`（`fix/backup-safe-1`）。
本文只定义一套方案，不并列备选。**只读核对与设计**，未改源码、未跑设备测试。

核对过的现状（决定协议形状的四个事实）：

- `NotesRepository.save()` 是 `temp → write → fsync → rename`，只保证单个文件替换原子，不同文件之间无事务。
- `NotesState.importBackup()` 先 `pruneAttachments()`，再换内存，再异步 `persist()`；三步之间进程被杀就留下「旧图片没了、笔记还是旧的」。
- `NotesWriteCoordinator.replace()` 目前**无调用方**，且 `join` 类语义无法证明写入队列已排空。
- `ReminderBootReceiver` 直接 `NotesRepository(context).load()`，完全绕过 `NotesState` 与协调器。

边界：本协议不承诺完整掉电耐久性（见 §7）。

---

## 1. 准备结果固定

### 1.1 一次性物化，物化即落盘

补齐默认值只允许发生在 `prepare()` 的**一次执行**内，并且生成后立刻写成材料文件。之后确认、重试、恢复**只读材料**，任何路径都不得再调用 `UUID.randomUUID()` 或 `System.currentTimeMillis()` 去补笔记内容。

准备阶段新增两个材料文件（与 `backup.zip` 同在一个暂存目录）：

```
files/.import/staging-<token>/
  backup.zip          源材料副本（1A 已有）
  plan.json           物化结果：idSeed / preparedAt / 每条笔记与待办的最终 ID 与时间戳
  commit.json         提交载荷（唯一允许写入 notes.json 的字节）
```

`plan.json` 结构（字段定死，多一个都不行）：

```json
{
  "version": 1,
  "token": "<uuid>",
  "idSeed": "<sha256(backup.zip 原始字节)>",
  "preparedAt": 1762900000000,
  "notes": [
    {"index":0,"id":"...","createdAt":1,"updatedAt":2,
     "todos":[{"index":0,"id":"..."}]}
  ]
}
```

### 1.2 派生规则（确定性，与时间无关）

- 笔记已有 `id` → 原样保留。
- 笔记缺 `id` → `noteId = uuidFrom(sha256("note\0" + idSeed + "\0" + index))`，取前 16 字节，写入 version/variant 位后格式化为 UUID 字符串。
- 待办缺 `id` → `todoId = uuidFrom(sha256("todo\0" + idSeed + "\0" + noteIndex + "\0" + todoIndex))`。
- 缺 `createdAt` / `updatedAt` → 一律取 `plan.preparedAt`（prepare 开始时**只取一次**的 `System.currentTimeMillis()`），两者相同，不再各自取时刻。

`idSeed` 用暂存 zip 的字节摘要而不是 token：同一份备份被重复选中时 token 会变，ID 不该跟着变。

### 1.3 提交载荷由物化结果生成

`commit.json` = `CanonicalJson` 序列化「原始元素 ∪ 物化后的已知字段」，沿用 1A 的合并规则（未知字段保留、`deletedAt`/`remindAt` 为 null 时删键、键字典序）。与 1A 的唯一差别：输入不再是「可能带随机补齐值的归一结果」，而是 `plan.json` 里已经定死的值。

因此 `commit.json` 对同一份备份是**逐字节可复现**的，包括原本缺 `id` 或时间戳的旧备份——1A 那条「缺时间戳不承诺可复现」的边界在 1B 关闭。

`PreparedBackup.notes` 也必须来自物化结果（与 `commit.json` 同源），避免「界面预览的 ID」与「写盘的 ID」不一致。

### 1.4 两个摘要各校验什么

| 摘要 | 计算对象 | 用途 | 不用于 |
|---|---|---|---|
| `notesDigest` | 暂存 `backup.zip` 内 `notes.json` 的**原始字节** | 提交开始时重读 zip、重新抽取该条目并比对，确认「暂存材料仍是用户选的那一份」 | 判断是否已提交；判断载荷是否正确 |
| `commitDigest` | `commit.json` 的字节 | ① 提交前重算材料摘要，确认材料未被改动；② 写入 `notes.json` 后比对实际落盘字节，确认「写进去的就是这份载荷」 | **单独**判断是否已提交（见 §2） |

两个摘要都只是**材料一致性证据**。是否已提交由 §2 的提交凭据判定。

### 1.5 重试与恢复不得重新生成

- 重试：沿用同一 `token`、同一 `plan.json`、同一 `commit.json`；若 `commitDigest` 与重算不符，判 `MaterialChanged`，拒绝提交并要求重新 prepare。
- 恢复：`plan.json` 或 `commit.json` 缺失/不可解析 → 判 `MaterialCorrupt`，**不重建**，直接进入清理（清不掉则 BLOCKED）。
- 唯一例外：进程在写 `plan.json` **之前**退出，此时材料不完整且未产生任何 ID，属于「没准备成功」，按 `StagingFailed` 清理，不视为丢失。

---

## 2. 唯一事务提交判据

### 2.1 三类物理件

| 文件 | 位置 | 写入时机 |
|---|---|---|
| `journal.json` | `files/.import/journal.json` | 状态推进时原子替换（temp + fsync + rename） |
| `committed.json` | `files/.import/committed.json` | **只在正式数据全部就位之后**写入，内容是提交凭据 |
| 事务材料 | `files/.import/txn-<txnId>/` | `commit.json`、`new-attachments/`、`old-attachments/`、`old-manifest.json` |

`committed.json`：`{"txnId":"...","commitDigest":"...","committedAt":...}`。

### 2.2 提交顺序（不可调换）

1. 校验 `commitDigest`（重算 `commit.json`）与 `notesDigest`（重抽 zip 内原始 notes）。
2. 写 `journal.json` = `{state:"APPLYING", txnId, commitDigest, oldNotesDigest, attachments:[...]}`，fsync，rename。
3. 逐附件替换：旧文件 `attachments/<n>` → `txn/old-attachments/<n>`；新文件 `txn/new-attachments/<n>` → `attachments/<n>`。每个文件搬完 fsync。
4. `commit.json` 原子写为 `files/notes.json`（temp + fsync + rename）。
5. 写 `committed.json`（temp + fsync + rename）。
6. 写 `journal.json` = `{state:"COMMITTED", ...}`。

**第 4 步完成 ≠ 已提交。** 第 5 步是唯一的分界。

### 2.3 判据

> 事务已确认提交 ⟺ `committed.json` 存在，且其 `txnId` 与 `journal`（若存在）一致，且 `sha256(files/notes.json)` == 其 `commitDigest`。

- 第 4 步后、第 5 步前退出：`notes.json` 已是新内容，但**无凭据** → 判 `Interrupted`，回滚到旧数据。
- 第 5 步写失败（IO 错误、空间不足）：同上，回滚。宁可回滚，不猜提交。
- 第 5 步写完、第 6 步前退出：凭据在 → `Committed`，恢复时只补写 `journal` 终态并清理。
- `journal.json` 损坏：先看凭据（凭据独立可读）。凭据成立 → `Committed`；凭据不成立 → `Corrupt`（BLOCKED，停写，保留全部材料，不自动回滚）。**不允许**因为 journal 读不出来就假设「没提交」而回滚一份可能已提交的数据。
- `notes.json` 摘要与凭据不符：`Corrupt`（有人绕过协议改过文件），BLOCKED 并保留材料。

---

## 3. 清理协议

### 3.1 核心不变量

> `journal.state == COMMITTED` 本身是终态，优先级高于任何文件是否存在。

因此清理中断在任何位置，都不会被重新解释成「未提交」：只要 journal 还在且写着 COMMITTED，恢复就只走清理，绝不走回滚；journal 已经不在了也不影响，因为凭据还在或正式数据已就位。

### 3.2 删除顺序（固定）

1. 删 `journal.json`
2. 删 `committed.json`
3. 删 `txn/old-attachments/` 全部文件
4. 删 `txn/new-attachments/`
5. 删 `txn/commit.json`、`txn/old-manifest.json`
6. 删 `txn-<txnId>/` 目录

先删 journal、后删凭据，是因为反过来的话会出现「凭据没了、journal 还写着 APPLYING」的组合，那正好会被 §2.3 判成 `Interrupted` 而错误回滚。

### 3.3 每步中断后的判读

| 中断位置 | 剩余物 | 恢复判读 | 动作 |
|---|---|---|---|
| 第 1 步前 | journal(COMMITTED) + 凭据 + 材料 | `Committed` | 重跑清理 |
| 第 1 步后、第 2 步前 | 凭据 + 材料（无 journal） | `Committed`（凭据 + 摘要成立） | 重跑清理 |
| 第 2 步后、第 3 步前 | 仅材料目录 | `Orphan` | 删除材料，不碰正式数据 |
| 第 3–6 步中 | 材料残缺 | `Orphan` | 同上 |
| 全部完成 | 无 | `Idle` | 无 |

每步都按「删除不存在即成功」实现，重复执行结果相同；任何一步失败只记录 `leftovers`，不改写事务终态，不触发回滚。

### 3.4 清理失败

清理失败不改变「已提交」事实：`ImportResult = Committed(cleanup = Partial(paths))`。下次启动再清，直到 `Idle`。材料残留不影响正式数据。

---

## 4. 回滚协议

### 4.1 回滚的依据是逐文件目标状态

`journal` 在 APPLYING 时记录：

```json
{"state":"APPLYING","txnId":"...","commitDigest":"...",
 "oldNotesDigest":"<旧 notes.json 摘要，不存在则 null>",
 "attachments":[{"name":"a.jpg","old":"<摘要或 null>","new":"<摘要>"}]}
```

回滚不是「覆盖/删除所以幂等」，而是每轮把每个文件的**当前状态**与**目标状态**比对，逐个推进到目标；只有全部达成才收尾。

### 4.2 逐文件状态机（每个附件名）

三处位置：`A = attachments/<n>`、`O = txn/old-attachments/<n>`、`N = txn/new-attachments/<n>`。

| 当前状态 | 判定条件 | 是否已达目标 | 动作 |
|---|---|---|---|
| 未处理 | `A` 存在且摘要 == old，`O` 不存在 | 是 | 无 |
| 已替换 | `A` 摘要 == new，`O` 摘要 == old | 否 | 删 `A`；`O` → `A` |
| 搬到一半 | `A` 不存在，`O` 摘要 == old | 否 | `O` → `A` |
| 新增附件已落 | `A` 摘要 == new，old == null | 否 | 删 `A` |
| 已搬回 | `A` 摘要 == old，`O` 不存在或为空 | 是 | 无 |
| 不可判 | 摘要既非 old 也非 new | — | `RollbackFailed`，停写 |

「旧附件已搬回后再次中断」落在最后一行前的「已搬回」：`A` 摘要等于 `old`，判为完成并跳过，不会重复搬回。

### 4.3 旧 notes.json 不存在

`oldNotesDigest == null` 时，目标状态是「`files/notes.json` 不存在」。回滚动作是**删除**，不是写回。删除前先确认当前摘要 == `commitDigest`（是自己写的才删）；若当前摘要既非 `commitDigest` 也非「不存在」，判 `RollbackFailed` 停写。

### 4.4 回滚自身失败

- 任一步失败 → `journal.state = ROLLING_BACK`（保留 `failedAt` 与失败文件），返回 `RollbackFailed`，**进入 BLOCKED**：拒绝一切 `saveAll` 与新的导入。
- 下次启动或用户重试 → 重新跑同一状态机。已完成的文件按摘要跳过，只推进未完成的。
- 全部达成 → 写 `journal.state = ABORTED` → 执行 §3 清理 → 回到 `Idle`。

### 4.5 可重复恢复的依据

| 对象 | 每轮校验 | 完成条件 |
|---|---|---|
| `notes.json` | 重算 `sha256` | == `oldNotesDigest`，或 `oldNotesDigest == null` 且文件不存在 |
| 每个附件 | 重算 `sha256` | == `old`，或 `old == null` 且文件不存在 |
| journal | 状态字段 | 仅当上两项全部达成才改写为 `ABORTED` |

因为完成条件是「摘要相等」而不是「执行过动作」，中断在任何位置重跑都不会把已完成判成未完成，也不会重复写同一份内容。

---

## 5. 共享访问与状态

### 5.1 唯一入口

新增 `NotesStore`（进程内单例，`data/NotesStore.kt`），持有：

- `writer: Mutex` —— 与现有 `NotesWriteCoordinator` 合并（保留其代次/序列语义，`replace()` 改为内部专用）
- `state: StoreState` —— `OPEN` / `IMPORTING(txnId)` / `COMMITTED_PENDING_PUBLISH(txnId)` / `BLOCKED(reason)` / `RECOVERING`

所有落盘路径都必须经过它，禁止任何调用方直接 `File(filesDir, "notes.json")`：

| 调用方 | 现有路径 | 1B 路径 |
|---|---|---|
| 普通保存 | `NotesState.persist() → repository.save()` | `store.saveAll(snapshot)`，返回结果而非 fire-and-forget |
| 导入 | `NotesState.importBackup() → repository.importBackup() + prune + persist` | `store.import(prepared)`，内部完成 §2 全流程 |
| 启动读取 | `NotesState.load() → repository.load()` | `store.load()`；若 BLOCKED 返回 `Blocked` 且**不覆盖内存** |
| 开机补排 | `ReminderBootReceiver → NotesRepository(context).load()` | `store.readForReminder()`（只读，不 persist、不排他） |
| 附件读写 | `NotesRepository.attachmentFile()` 直连 | `store.attachment(name)`，导入期间走事务目录 |

### 5.2 状态与写权限

| 状态 | `saveAll` | `import` | `load` | 提醒调度 |
|---|---|---|---|---|
| `OPEN` | 允许 | 允许 | 允许 | 允许 |
| `IMPORTING` | 拒绝（`Busy`） | 拒绝（`Busy`） | 拒绝（`Busy`） | 暂停 |
| `COMMITTED_PENDING_PUBLISH` | **拒绝**（`Blocked`） | 拒绝 | 允许（读新数据） | 暂停 |
| `BLOCKED` | 拒绝（`Blocked`） | 拒绝 | 允许只读 | **不排** |
| `RECOVERING` | 拒绝（`Busy`） | 拒绝 | 排队等待 | 暂停 |

三条硬约束：

1. **`BLOCKED` 时旧内存不得继续写盘。** 由 store 拒绝实现，不依赖 UI 自觉；`NotesState.persist()` 收到 `Blocked` 后置 `persistenceError` 并停止重试。
2. **磁盘已提交、内存未发布时，禁止保存。** 否则会用旧快照覆盖刚提交的新数据。`COMMITTED_PENDING_PUBLISH` 状态就是这个窗口，写入口全部关闭，直到 `_notes` 替换完成。
3. **不发布中间态供提醒调度。** `NotesState.load()` 里的 `scheduler::schedule` 只在内存发布完成且状态回到 `OPEN` 后执行；`ReminderBootReceiver` 走只读入口，读到的是已提交数据，若 store 处于 BLOCKED 则跳过本轮排程（等下次启动）。

`NotesState.load()` 里「清理回收站 → `persist()`」这类隐式写入，在 `BLOCKED` / `COMMITTED_PENDING_PUBLISH` 下必须跳过，不得当作普通保存放行。

---

## 6. 结果与取消

### 6.1 四层结果分开表达

```kotlin
sealed interface DiskOutcome        // 磁盘事实
  NotStarted | Applied | Committed | Interrupted | RollbackFailed | Corrupt
sealed interface PublishOutcome     // 内存发布
  Published(snapshot) | Skipped(reason)
data class CleanupOutcome           // 清理
  (cleaned: Boolean, leftovers: List<String>)
sealed interface ImportResult       // UI 消费
  Imported(count, warnings, cleanup)
  | NotImported(reason)
  | ImportedButBlocked(count, reason)   // 已提交但状态未恢复
  | NeedsUserAction(reason)             // Corrupt / RollbackFailed
```

映射规则（不与磁盘事实冲突）：

- `DiskOutcome != Committed` → `NotImported`，绝不出现「导入成功」文案。
- `Committed` + `Published` → `Imported`，清理结果单独附上（`Partial` 不影响成功）。
- `Committed` + `Skipped`（未发布）→ `ImportedButBlocked`，下次启动从磁盘重建后再发布。
- `Corrupt` / `RollbackFailed` → `NeedsUserAction`。

### 6.2 事实来源不是 Channel，也不是返回值

- 唯一事实来源：`journal.json` + `committed.json` + 磁盘数据。任何时刻都可由 `NotesStore.classify()` 从磁盘重建。
- `MutableStateFlow<ImportState>` 只是**通知缓存**，进程死了就丢，丢了不影响判定。
- 因此「协程取消导致返回值没送到」不等于失败：UI 调 `store.currentImport()` 重读磁盘，得到 `Committed` 就按成功处理并补发布内存。

### 6.3 不可取消段边界

`withContext(NonCancellable)` **只包住两步**：

1. 第 4 步 `notes.json` 的 rename
2. 第 5 步 `committed.json` 的写入

原因：这两步一旦开始就必须走完，否则会停在「数据换了但没凭据」或「凭据写了但数据没换」的不可判状态。其余步骤都可被取消，因为都有 §3/§4 的恢复路径。

清理整段建议不可取消（保证删除顺序不被打断），但它每步幂等，即使被打断也只是留下 `Orphan`，不产生错误判读。

### 6.4 结果未交付时的查询

```kotlin
suspend fun currentImport(): ImportState   // 内部 classify() + 重算摘要
```

启动顺序固定为：`recover()` → 得到 `Idle` / `Blocked` / `CommittedPendingPublish` → 再 `load()` → 再发布内存 → 再排提醒。

---

## 附录 A：操作步骤 — 异常 / 进程中断 — 恢复动作

| # | 操作步骤 | 异常 / 中断 | 恢复动作 |
|---|---|---|---|
| 1 | prepare 拷 zip | 进程被杀 | 无 journal；删 `staging-*` 残留（超时清理），正式数据未动 |
| 2 | prepare 校验 / 扫条目 | 拒绝或取消 | 1A 已有：删暂存，返回 `Invalid` / 抛取消 |
| 3 | 写 `plan.json` / `commit.json` | 写失败或被杀 | 材料不完整；按 `StagingFailed` 清理；无 ID 被使用 |
| 4 | 写 journal `PREPARED` | 写失败 | 未动正式数据；返回 `NotStarted`；材料保留可重试 |
| 5 | 写 journal `APPLYING` | 被杀 | journal 为 `PREPARED` → 丢材料；为 `APPLYING` → 走回滚 |
| 6 | 逐附件替换 | 被杀 | `APPLYING` 无凭据 → 回滚（§4.2 逐文件状态机） |
| 7 | 写 `notes.json`（rename） | 被杀 | 同上；旧 `notes.json` 从 `oldNotesDigest` 判定是否已换 |
| 8 | 写 `committed.json` | 失败 / 被杀 | 无凭据 → 判 `Interrupted` → 回滚（哪怕 notes.json 已是新内容） |
| 9 | 写 journal `COMMITTED` | 被杀 | 凭据在 → `Committed`；补写终态后清理 |
| 10 | 发布内存 | 进程死 | 磁盘 `Committed`；下次启动 `recover()` 后重新发布 |
| 11 | 清理：删 journal | 被杀 | journal 在（COMMITTED）或不在（凭据在）都能判 `Committed`，重跑清理 |
| 12 | 清理：删 `committed.json` | 被杀 | 只剩材料 → `Orphan` → 删材料，不碰正式数据 |
| 13 | 清理：删 `old-attachments/` | 被杀 | 同上（材料残缺但已无事务记录） |
| 14 | 回滚：删 `notes.json`（旧文件本不存在） | 被杀 | `ROLLING_BACK` 继续；目标 = 文件不存在 |
| 15 | 回滚：搬回旧附件 | 被杀 | 摘要 == old 的跳过，其余继续搬 |
| 16 | 回滚失败 | IO 错误 | `BLOCKED` 停写，材料全保留，下次重试同一状态机 |
| 17 | journal 损坏 | JSON 不可解析 | 凭据成立 → `Committed`；否则 `Corrupt` → `BLOCKED`，不自动回滚 |
| 18 | 摘要与凭据不符 | 外部改动 / 磁盘损坏 | `Corrupt` → `BLOCKED`，保留材料与现场 |

## 附录 B：计划修改的文件与对应测试

### 生产代码

| 文件 | 改动 |
|---|---|
| `data/ImportJournal.kt`（新） | journal / 凭据读写（原子写）、`classify()` 全状态判定、摘要重算 |
| `data/ImportTransaction.kt`（新） | §2 提交六步、§3 清理、§4 回滚状态机、`recover()` |
| `data/NotesStore.kt`（新） | 唯一存储入口、`StoreState` 状态机、写权限拒绝、`currentImport()` |
| `data/BackupImporter.kt` | prepare 末尾物化 ID/时间戳、写 `plan.json` 与 `commit.json`、返回物化后的 `notes` |
| `data/BackupImportModels.kt` | `PreparedBackup` 增 `plan` / `commitFile`；`notes` 语义改为「物化结果」 |
| `data/NotesRepository.kt` | `save` / `load` / 附件操作委托 `NotesStore`；删除 `importBackup` 的直连实现 |
| `ui/NotesState.kt` | `persist` 走 `store.saveAll` 并处理 `Busy`/`Blocked`；`importBackup` 走 `store.import`；BLOCKED 时跳过隐式写入与提醒排程 |
| `reminder/ReminderReceiver.kt` | `ReminderBootReceiver` 改用 `store.readForReminder()`，BLOCKED 时跳过 |
| `MainActivity.kt` | 导入结果按 §6.1 四态消费；确认对话框数据取自 `prepared.warnings` |

### 测试（`androidTest`，沿用现有隔离沙箱与注入接缝）

| 测试 | 覆盖 |
|---|---|
| `ImportPlanTest` | 缺 id / 缺时间戳的旧备份两次 prepare 得到相同 `commit.json`；`plan.json` 与 `commit.json` 同源；`notesDigest`、`commitDigest` 各自校验对象正确 |
| `ImportJournalTest` | `classify()` 七种组合；journal 损坏 + 凭据成立/不成立；摘要不符 |
| `ImportCommitTest` | 六步顺序；在每一步注入失败；「notes.json 已换但无凭据」判 `Interrupted` |
| `ImportCommitCancelTest` | 不可取消段边界：取消落在 rename/凭据前后各一次的确定性结果 |
| `ImportRecoveryTest` | 附录 A 的 5–10 行：中断后 `recover()` 的数据状态与 `DiskOutcome` |
| `ImportRollbackTest` | 旧 notes.json 不存在；附件部分替换；搬回后再次中断；回滚自身失败后再恢复 |
| `ImportCleanupTest` | 六步逐一中断；重复执行；「journal 先删、凭据后删」顺序不可逆 |
| `NotesStoreTest` | `BLOCKED` 拒写、`IMPORTING` 拒并发保存、`COMMITTED_PENDING_PUBLISH` 拒写 |
| `ReminderBootReadTest` | 只读入口读到已提交数据；BLOCKED 时不排提醒 |
| `ImportResultMappingTest` | 四层结果 → `ImportResult` 的映射；结果未交付时 `currentImport()` 返回 `Committed` |

## 7. 边界

- **不承诺完整掉电耐久性**：`fsync` 覆盖 `notes.json`、`commit.json`、`committed.json`、`journal.json` 的写入与替换，但不保证目录项在所有文件系统与厂商实现上都持久化；掉电后允许出现 `Corrupt` 并要求用户介入，不允许出现「静默丢失且无记录」。
- 不清洗用户数据，不做自动修复式回滚；`Corrupt` 一律停写并保留现场。
- 不处理录音文件（备份不含录音，1A 已定）。
- 本文不包含 UI 交互细节，只定义状态到四类结果的映射。
