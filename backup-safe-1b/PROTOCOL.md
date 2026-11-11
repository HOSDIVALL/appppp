# BACKUP-SAFE-1B 提交协议（rev2 · 待实施）

源码基线：`26a0de3cc80636718059c118d5af6e4c6f179fc0`（`fix/backup-safe-1`）。
本文只定义一套方案，不并列备选。**只读核对与设计**：未改源码、未跑设备测试、未接 UI。

核对过的现状（决定协议形状的四个事实）：

- `NotesRepository.save()` 是 `temp → write → fsync → rename`，只保证单个文件替换原子，不同文件之间无事务。
- `NotesState.importBackup()` 先 `pruneAttachments()`，再换内存，再异步 `persist()`；三步之间进程被杀就留下「旧图片没了、笔记还是旧的」。
- `NotesWriteCoordinator.replace()` 目前**无调用方**，且 `join` 类语义无法证明写入队列已排空。
- `ReminderBootReceiver` 直接 `NotesRepository(context).load()`，完全绕过 `NotesState` 与协调器。

本版只修正六个协议问题，修正后的规则直接在正文生效：

| # | 修正点 | 落在 |
|---|---|---|
| 1 | 可复现性保证收窄到「同一次物化结果」 | §1.3 |
| 2 | 旧 `notes.json` 实体副本、备份顺序、恢复规则；活跃事务材料损坏不得清理 | §2.2、§5.2、§1.5 |
| 3 | 统一提交判据、清理终态、按 `txnId` 的结果留存 | §2.4、§4.1、§8.3 |
| 4 | 完整 `classify` 表、回滚可达状态、清理关键步骤停止规则 | §3、§5.4、§4.3 |
| 5 | ticket 代次、修改前门禁、异步回调代次、提醒读取协调与 BLOCKED 加载 | §6.3–§6.5 |
| 6 | 取消后的回滚/阻塞责任；掉电保证收窄 | §7、§9 |

---

## 1. 准备与物化

### 1.1 一次性物化，物化即落盘

补齐默认值只允许发生在 `prepare()` 的**一次执行**内，并且生成后立刻写成材料文件。之后确认、重试、恢复**只读材料**，任何路径都不得再调用 `UUID.randomUUID()` 或 `System.currentTimeMillis()` 去补笔记内容。

`preparedAt` 在 `prepare()` 开始时**只取一次**，写入 `plan.json`；此后本次导入的全部时间戳都引用它，不再各自取时刻。

准备阶段的材料目录（1A 已产出 `backup.zip`，1B 只增加两个文件）：

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
- 缺 `createdAt` / `updatedAt` → 一律取 `plan.preparedAt`，两者相同。

`idSeed` 用暂存 zip 的字节摘要而不是 token：同一份备份被重复选中时 token 会变，**ID** 不该跟着变。但时间戳没有这个性质，见 §1.3。

### 1.3 提交载荷与可复现性的准确范围

`commit.json` = `CanonicalJson` 序列化「原始元素 ∪ 物化后的已知字段」，沿用 1A 的合并规则（未知字段保留、`deletedAt`/`remindAt` 为 null 时删键、键字典序）。与 1A 的唯一差别：输入不再是「可能带随机补齐值的归一结果」，而是 `plan.json` 里已经定死的值。

保证范围分两种，不能混用：

| 场景 | 保证 | 依据 |
|---|---|---|
| **同一次物化结果**（同一 `plan.json`、同一 `txnId`）的重试 / 恢复 / 重复序列化 | `commit.json` **逐字节相同**，`commitDigest` 不变 | 输入全部来自 `plan.json`，序列化确定性 |
| **重新执行 `prepare()`**（新 token、新 `preparedAt`） | 只保证「已有 `id`」与「由 `idSeed` 派生的 `id`」稳定；缺时间戳的笔记取新的 `preparedAt`，`commit.json` **允许不同** | 时间戳来自本次 `preparedAt` |

因此：

- **不允许跨 prepare 比对 `commitDigest`**。拿上一次的摘要校验新材料，必须判 `MaterialChanged`，要求重新确认，不得当成「材料被篡改」也不得当成「同一事务」。
- 1A 那条「缺时间戳不承诺可复现」在 1B **不撤销**，只是把可复现范围限定在同一 `plan.json` 内；1A 的边界描述继续成立，不需要反证。
- 重试必须复用同一 `txnId` 与同一份 `plan.json`；一旦重新 prepare，就是新事务，旧材料作废。

`PreparedBackup.notes` 也必须来自物化结果（与 `commit.json` 同源），避免「界面预览的 ID」与「写盘的 ID」不一致。

### 1.4 三类摘要各校验什么

| 摘要 | 计算对象 | 用途 | 不用于 |
|---|---|---|---|
| `notesDigest` | 暂存 `backup.zip` 内 `notes.json` 的**原始字节** | 提交开始时重读 zip、重新抽取该条目并比对，确认「暂存材料仍是用户选的那一份」 | 判断是否已提交；判断载荷是否正确 |
| `commitDigest` | `commit.json` 的字节 | ① 提交前重算材料摘要；② 写入 `notes.json` 后比对实际落盘字节；③ 提交凭据里记录的比对基准 | **单独**判断是否已提交（见 §2.4） |
| `oldNotesDigest` | 提交前 `files/notes.json` 的字节（不存在则记 `null`） | 回滚目标、`Orphan` 判读（§3.2 第 2 组） | 判断提交 |

附件逐个记 `old` / `new` 摘要，用途同 `oldNotesDigest`。所有摘要都只是**材料一致性证据**，是否已提交由 §2.4 判定。

### 1.5 材料缺失或损坏：分活跃 / 非活跃

材料损坏的处置取决于**事务是否已经动过正式数据**，两者不能合并：

| 情形 | 判读 | 动作 |
|---|---|---|
| 未开始提交：无 `journal` 或 `journal.state == PREPARED`，且无凭据；`plan.json` / `commit.json` 缺失或不可解析 | `StagingFailed` / `Orphan`（未应用） | 放弃本次导入，删除材料，**不动正式数据**，无 ID 被使用（§3.2 2a、8b） |
| 已开始提交：`journal.state ∈ {APPLYING, ROLLING_BACK}`，或存在凭据；**回滚所需齐备**（journal 的 `Dold` + 需要恢复的文件都有副本） | `Interrupted` | 回滚（§5）；**回滚完成前不清理任何材料** |
| 已开始提交，**回滚所需不齐备**（`journal` 不可读、`Dold` 与副本不足以还原） | `Corrupt` | **`BLOCKED`：保留全部材料，不清理，不回滚** |
| 旧副本缺失：`old-manifest.json` 声明 `notes.present == true` 但 `old-notes.json` 不存在/摘要不符 | 分情况 | 该文件**仍需恢复**时 `Corrupt` → `BLOCKED`（没有副本就无法证明能回到旧状态，猜一次就是把用户数据赌掉）；该文件已处于旧状态时不需要副本，回滚照常进行（§5.1） |

`plan.json` 在提交开始后不再参与判定（只用于物化与重试前的材料校验），它缺失不影响回滚，也不构成 `Corrupt`。只有「未开始提交」才允许直接清理；「已开始提交」的**必需材料**损坏一律停写并保留现场。副本先写 `*.tmp` → fsync → rename，所以半截副本永远不会以正式名字存在。

---

## 2. 提交：备份顺序与唯一判据

### 2.1 物理件

`txnId` 与 prepare 的 `token` 取同一值；提交开始时把 `staging-<token>/` 整体改名为 `txn-<txnId>/`（同目录内 rename），材料从此归事务所有。

| 文件 | 位置 | 写入时机 |
|---|---|---|
| `journal.json` | `files/.import/journal.json` | 状态推进时原子替换（temp + fsync + rename） |
| `committed.json` | `files/.import/committed.json` | **只在正式数据全部就位之后**写入，内容是提交凭据 |
| `results.json` | `files/.import/results.json` | 终态记录，按 `txnId` 留存；**不是凭据**（§8.3） |
| 事务材料 | `files/.import/txn-<txnId>/` | `backup.zip`、`plan.json`、`commit.json`、`old-notes.json`、`old-manifest.json`、`old-attachments/`、`new-attachments/` |

`committed.json`：`{"txnId":"...","commitDigest":"...","committedAt":...}`。

事务目录存在期间，1A 的暂存超时清理**必须跳过**它：清理只扫 `staging-*`，`txn-*` 只能由本协议 §4 删除。否则会把回滚副本当垃圾删掉。

### 2.2 备份顺序：先备份，后修改

1. 单遍读取 `files/notes.json` 与 `attachments/`，算 `oldNotesDigest` 与每个附件的 `old` 摘要。
2. 写 `journal.json = {state:"APPLYING", txnId, commitDigest, oldNotesDigest, attachments:[{name,old,new}]}`，fsync，rename。
3. 复制 `notes.json` → `old-notes.json.tmp` → fsync → rename 为 `old-notes.json`。
4. 逐个复制旧附件 → `old-attachments/<n>.tmp` → fsync → rename。
5. 写 `old-manifest.json = {version:1, notes:{present,digest,size}|{present:false}, attachments:[{name,digest,size}]}`，fsync，rename。

**第 5 步完成之前，不允许写第一个新文件。** `old-manifest.json` 是「备份完整」的唯一标志；需要从副本恢复时先校验它（§5.2）。

副本是**复制**而不是搬移：`attachments/<n>` 在整个事务期间始终存在，不存在「搬到一半附件不见了」的窗口，回滚也不需要「先把旧文件搬回来」这一步。

### 2.3 提交顺序（不可调换）

0. `staging-<token>/` 整体改名为 `txn-<txnId>/`（同目录内 rename），材料所有权转移给事务；失败则 `NotStarted`，不动正式数据。
1. 校验 `commitDigest`（重算 `commit.json`）与 `notesDigest`（重抽 zip 内原始 notes）；校验可用空间 ≥ 材料字节数 + 旧副本字节数，不足则 `NotStarted` 并删材料（此时尚未写 journal，删 `txn-` 目录即回到 `Idle`），不动正式数据。
2. 备份（§2.2 第 1–5 步），其中 §2.2 第 1 步就是本步的前置摘要计算。
3. 逐附件替换：`new-attachments/<n>` → 写 `attachments/<n>.tmp` → fsync → rename。每个文件单独原子。
4. 把 `commit.json` 的字节写为 `files/notes.json.tmp` → fsync → rename 为 `files/notes.json`。
5. 写 `committed.json`（凭据）。
6. 追加 `results.json` 的 `Committed` 记录。
7. 写 `journal.json = {state:"COMMITTED", ...}`。

**第 4 步完成 ≠ 已提交。** 第 5 步是唯一分界。

### 2.4 唯一提交判据

> 已提交 ⟺ 存在**同一 `txnId`** 的终态证据，且 `sha256(files/notes.json)` 等于该证据记录的 `commitDigest`。

终态证据只有两种，任一成立即可：

- `committed.json` 可读，且 `txnId` 与当前事务一致；
- `journal.json` 可读，且 `state == COMMITTED`（其 `txnId`、`commitDigest` 与事务一致）。

两者同时可读时必须给出**相同**的 `txnId` 与 `commitDigest`；不一致判 `Corrupt`（`BLOCKED`）。任一份不可读而另一份成立，判 `Committed`：journal 不可读时用凭据重写 journal；凭据不可读时**不重建凭据**（它会在清理中被删除），只以 journal 为证据。

`results.json` **永远不是证据**，不参与本判据。

由此得到的边界：

- 第 4 步后、第 5 步前退出：`notes.json` 已是新内容，但**无凭据** → 判 `Interrupted`，回滚到旧数据。
- 第 5 步写失败（IO 错误、空间不足）：同上，回滚。宁可回滚，不猜提交。
- 第 5 步写完、第 6/7 步前退出：凭据在 → `Committed`，恢复时补写结果记录与 journal 终态，然后清理。
- `journal` 文件**存在但不可解析** ≠ 没有 journal。此时先看凭据：凭据成立 → `Committed`；凭据不成立 → `Corrupt`，`BLOCKED`，保留全部材料，不自动回滚。
- `notes.json` 摘要与凭据不符 → `Corrupt`，`BLOCKED`。

---

## 3. `classify()`：完整判定

### 3.1 journal 状态与可达转换

```
（无）--PREPARED--> PREPARED --APPLYING--> APPLYING --COMMITTED--> COMMITTED -->（无）
                       |                     |
                       |                     +--ROLLING_BACK--> ROLLING_BACK --ABORTED--> ABORTED -->（无）
                       +--ABORTED（用户放弃 / StagingFailed）
```

- `PREPARED`：材料齐、备份未开始、正式数据未动。
- `APPLYING`：备份或替换进行中，可能出现任意混合。
- `ROLLING_BACK`：回滚启动但未完成（含回滚自身失败后的停写状态）。
- `ABORTED`：回滚已完成，正式数据已回到旧状态，等待清理。
- `COMMITTED`：已提交，等待清理。

**回滚只在 `APPLYING` 与 `ROLLING_BACK` 可达**；从 `PREPARED` 只做放弃（无需回滚，数据未动），从 `COMMITTED` / `ABORTED` / 无 journal 一律不可回滚。

### 3.2 判定表

记号：`N` = `files/notes.json`；`Dnew` = 事务的 `commitDigest`；`Dold` = `journal.oldNotesDigest`（或 `old-manifest.notes.digest`）。凭据列「匹配」= 可读且 `txnId` 一致且 `sha256(N) == 凭据.commitDigest`。

| # | journal | 凭据 | `N` | 材料 | 判定 | 动作 |
|---|---|---|---|---|---|---|
| 1 | 无 | 无 | 任意 | 无 | `Idle` | 无 |
| 2a | 无 | 无 | `== Dold`，或 `Dold == null` 且 `N` 不存在 | 有 | `Orphan`（未应用） | 删材料 → `Idle` |
| 2b | 无 | 无 | `==` 材料内 `commit.json` 摘要，且 `!= Dold` | 有 | `CommittedUnproven` | `BLOCKED`，保留材料 |
| 2c | 无 | 无 | 其他 | 有 | `Corrupt` | `BLOCKED`，保留材料 |
| 3 | 无 | 匹配 | `== Dnew` | 任意 | `Committed` | 清理 |
| 4 | 无 | 可读但不成立（`txnId` 不符或 `sha256(N) != commitDigest`） | 任意 | 任意 | `Corrupt` | `BLOCKED` |
| 5 | 无 | 存在但不可读 | 任意 | 任意 | `Corrupt` | `BLOCKED` |
| 6 | 损坏 | 匹配 | `== Dnew` | 任意 | `Committed` | 补结果记录 → 用凭据重建 journal `COMMITTED` → 清理 |
| 7 | 损坏 | 无 / 不可读 / 不匹配 | 任意 | 任意 | `Corrupt` | `BLOCKED`，保留材料，不清理 |
| 8 | `PREPARED` | 无 | `== Dold`（或 `Dold == null` 且 `N` 不存在） | 完整 | `Prepared` | 等待确认；放弃 → 写 `ABORTED` → 清理 |
| 8b | `PREPARED` | 无 | `== Dold`（同上） | 不完整 / 目录不应存在 | `StagingFailed` | 写 `NotStarted` 结果 → 删材料 → `Idle`（§1.5） |
| 9 | `PREPARED` | 存在（任意） | 任意 | 任意 | `Corrupt` | `BLOCKED`（凭据不应早于 APPLYING 出现） |
| 10 | `PREPARED` | 无 | 已变 | 任意 | `Corrupt` | `BLOCKED` |
| 11 | `APPLYING` | 无 | `!= Dnew` | 满足回滚需要（§5.1） | `Interrupted` | 回滚（§5） |
| 12 | `APPLYING` | 无 | `!= Dnew` | 有文件需恢复但副本不可用 | `Corrupt` | `BLOCKED`，保留材料 |
| 13 | `APPLYING` | 匹配 | `== Dnew` | 任意 | `Committed` | 补结果记录 → 补 journal `COMMITTED` → 清理 |
| 14 | `APPLYING` | 匹配 | `!= Dnew` | 任意 | `Corrupt` | `BLOCKED` |
| 15 | `APPLYING` | `txnId`/摘要与 journal 冲突 | 任意 | 任意 | `Corrupt` | `BLOCKED` |
| 16 | `ROLLING_BACK` | 无 | 任意 | 满足回滚需要 | `RollingBack` | 继续回滚 |
| 17 | `ROLLING_BACK` | 无 | 任意 | 有文件需恢复但副本不可用 | `RollbackFailed` | `BLOCKED` |
| 18 | `ROLLING_BACK` | 存在 | 任意 | 任意 | `Corrupt` | `BLOCKED`（已回滚的事务不可能有凭据） |
| 19 | `ABORTED` | 无 | `== Dold` 或 `Dold == null && N` 不存在 | 任意 | `Aborted` | 写结果记录 → 清理 |
| 19b | `ABORTED` | 无 | 其他 | 任意 | `Corrupt` | `BLOCKED`（回滚已宣称完成，正式数据却不是旧状态） |
| 20 | `ABORTED` | 存在 | 任意 | 任意 | `Corrupt` | `BLOCKED` |
| 21 | `COMMITTED` | 无 / 匹配 | `== Dnew` | 任意 | `Committed` | 清理 |
| 22 | `COMMITTED` | 任意 | `!= Dnew` | 任意 | `Corrupt` | `BLOCKED` |

同一次 `classify()` 只看磁盘，不看内存、不看 `MutableStateFlow`、不看上一次返回值。

### 3.3 判定优先级

1. **先判提交事实**：证据 + 摘要（#3、#6、#13、#21）。摘要不符一律 `Corrupt`，绝不因为 journal 停在 `APPLYING` 就假设「没提交」而回滚一份可能已提交的数据。
2. **再判是否动过数据**：`PREPARED` 可放弃；`APPLYING` / `ROLLING_BACK` 只能前进或回滚，不能删除材料。
3. **最后判材料能否支撑动作**：回滚需要旧副本，提交需要 `commit.json`；两者都不具备 → `BLOCKED`，不猜。
4. `journal` 缺失与 `journal` 不可解析是两件事，后者一律视为「事务存在但状态未知」，只能靠凭据救回（#6），否则 `BLOCKED`（#7）。

---

## 4. 清理

### 4.1 清理终态

> 清理的终态是 `Idle`：无 `journal`、无凭据、无事务材料目录，正式数据保持已提交内容，`results.json` 保留终态记录。

清理**从不清洗正式数据**：只删 `.import/` 下的事务件。清理完成即 `OPEN`，可以接受普通保存。

### 4.2 顺序（固定，先删不可证明物，后删证据）

0. 确保 `results.json` 有本 `txnId` 的终态记录（缺则补写）。
1. 删 `old-attachments/` 内全部文件。
2. 删 `new-attachments/` 内全部文件。
3. 删 `commit.json`、`old-notes.json`、`old-manifest.json`。
4. 删 `txn-<txnId>/` 目录（仅当已空）。
5. 删 `journal.json`。
6. 删 `committed.json`。

证据留在最后：任何中断都还能用凭据 + 摘要证明「已提交」，不会退回「无凭据」的歧义。

### 4.3 关键步骤的停止规则

- **门禁**：执行第 5 步前必须确认 `txn-<txnId>/` 已不存在。材料删不干净就**不许删证据**，否则会落进 §3.2 的 2b/2c（无证据 + 材料）而被迫 `BLOCKED`。
- **关键步骤**：第 0、5、6 步。
  - 第 0 步失败 → 立即停，先保住证据，不删任何材料。
  - 第 5 步失败 → 停，保留 journal 与凭据（此时材料已删完），下次重试。
  - 第 6 步失败 → 停，保留凭据，下次重试。
- **非关键步骤**：第 1–4 步。失败只记 `leftovers`，可继续下一步；但第 4 步失败（目录非空）等价于门禁不通过，第 5、6 步不执行。
- 任何一步都按「删除不存在即成功」实现，重复执行结果相同；清理失败不改写事务终态，不触发回滚。

### 4.4 每步中断后的判读

| 中断位置 | 剩余物 | 判读 | 动作 |
|---|---|---|---|
| 第 0 步前 | journal(COMMITTED) + 凭据 + 全部材料 | `Committed` | 重跑清理 |
| 第 0 步后、第 4 步前 | journal + 凭据 + 材料残缺 | `Committed` | 重跑清理，已删的跳过 |
| 第 4 步后、第 5 步前 | journal + 凭据（无材料） | `Committed` | 删 journal |
| 第 5 步后、第 6 步前 | 仅凭据 | `Committed` | 删凭据 → `Idle` |
| 第 6 步后 | 无 | `Idle` | 无 |
| 第 1–4 步中有失败（材料残留） | journal + 凭据 + 材料 | `Committed` | 重跑材料删除；门禁通过前**不删证据** |

### 4.5 清理失败与结果

清理失败不改变「已提交」事实：`Imported(..., cleanup = Partial(paths))`。下次启动再清，直到 `Idle`；材料残留不影响正式数据。`results.json` 已在第 0 步落定，所以清理失败也不影响「这次导入成功了吗」这个查询。

---

## 5. 回滚

### 5.1 依据：实体副本 + 摘要

回滚不靠「覆盖/删除所以幂等」，而靠两样东西：**旧内容的实体副本**，以及**摘要比对得出的当前状态**。只有摘要没有副本时，无法把已被覆盖的 `notes.json` 变回去；只有副本没有摘要时，无法判断当前文件该不该动。

> **回滚可行** ⟺ 每一个「当前状态 ≠ 目标状态」的文件都有可用的恢复来源。

不需要推进的文件不需要副本：备份阶段就被中断时 `notes.json` 仍是旧字节，回滚是空操作，即使 `old-notes.json` 还没写完也不影响结论。反之，只要有文件需要恢复而副本缺失或摘要不符，一律 `BLOCKED`。

### 5.2 旧 `notes.json` 的恢复规则

`journal.oldNotesDigest` 非 `null` 时，目标状态是「`notes.json` 的字节 == `oldNotesDigest`」，恢复来源是 `txn/old-notes.json`：

| 当前 `notes.json` | 动作 |
|---|---|
| 摘要 `== Dold` | 已达成，跳过 |
| 摘要 `== Dnew` | 用 `old-notes.json` 写 `notes.json.tmp` → fsync → rename；随后重算校验 `== Dold` |
| 不存在 | 同上（从副本重建） |
| 其他 | `RollbackFailed`，停写 |

需要从副本恢复时，先校验 `old-notes.json` 的摘要 `== Dold` 且 `old-manifest.json` 声明 `notes.present == true`；不符即 §1.5 的 `Corrupt`，不继续。已经处于 `Dold` 时不做这项校验（不需要副本）。

`journal.oldNotesDigest == null`（提交前本就没有 `notes.json`）时，目标状态是「文件不存在」：仅当当前摘要 `== Dnew` 才删除；已不存在则跳过；其他情况 `RollbackFailed`。

### 5.3 每个附件的恢复规则

三处位置：`A = attachments/<n>`、`O = old-attachments/<n>`、`old`/`new` 取自 journal。

| `old` | 当前 `A` | 动作 |
|---|---|---|
| 非 null | 摘要 `== old` | 已达成，跳过 |
| 非 null | 摘要 `== new` 或不存在 | 校验 `O` 摘要 `== old`，写 `A.tmp` → fsync → rename |
| 非 null | 其他 | `RollbackFailed` |
| null（本次新增） | 摘要 `== new` | 删除 `A` |
| null | 不存在 | 已达成，跳过 |
| null | 其他 | `RollbackFailed` |

因为副本与恢复都走 `temp + rename`，`A` 只会出现旧字节或新字节，「搬到一半」这一状态**不可达**，不需要为它准备分支。

### 5.4 回滚的可达状态与收敛

- 入口只有两个：`APPLYING`（#11）与 `ROLLING_BACK`（#16）。其他状态不得进入回滚。
- 逐文件可达状态只有三种：**未处理**（当前 == old）、**已替换**（当前 == new）、**已恢复**（当前 == old，副本仍在）。因为替换与恢复都走 `temp + rename`，不存在「半个文件」的中间态；「目标文件缺失」只可能来自外部删除，仍按 §5.3 从副本恢复，或落到 `RollbackFailed`。
- 回滚开始即把 `journal.state` 置 `ROLLING_BACK`（含失败文件与 `failedAt`）。
- 每轮重新计算每个文件的摘要，按 §5.2 / §5.3 推进；完成条件是「全部摘要达成目标」，不是「执行过动作」，因此中断任意次数都能收敛。
- 全部达成 → 写 `journal.state = ABORTED` → 写 `results.json` 的 `Aborted` 记录 → 按 §4 清理 → `Idle`。

### 5.5 回滚自身失败

- 任一步失败 → `ROLLING_BACK` + `RollbackFailed` → **`BLOCKED`**：拒绝一切 `saveAll` 与新导入，保留全部材料。
- 下次启动或用户重试 → 重跑同一套规则，已完成的文件按摘要跳过。
- 回滚失败期间**不得**继续清理材料，也不得改写为 `ABORTED`。

---

## 6. 共享访问、代次与门禁

### 6.1 唯一入口

新增 `NotesStore`（进程内单例，`data/NotesStore.kt`），持有：

- `writer: Mutex` —— 与现有 `NotesWriteCoordinator` 合并（保留其代次/序列语义，`replace()` 改为内部专用）
- `epoch: Long` —— 写权限代次（§6.3）
- `state: StoreState` —— `OPEN` / `IMPORTING(txnId)` / `COMMITTED_PENDING_PUBLISH(txnId)` / `BLOCKED(reason)` / `RECOVERING`

所有落盘路径都必须经过它，禁止任何调用方直接 `File(filesDir, "notes.json")`：

| 调用方 | 现有路径 | 1B 路径 |
|---|---|---|
| 普通保存 | `NotesState.persist() → repository.save()` | `store.saveAll(snapshot)`，返回结果而非 fire-and-forget |
| 导入 | `NotesState.importBackup() → repository.importBackup() + prune + persist` | `store.import(prepared)`，内部完成 §2 全流程 |
| 启动读取 | `NotesState.load() → repository.load()` | `store.load()`；`BLOCKED` 返回 `Blocked` 且**不覆盖内存** |
| 开机补排 | `ReminderBootReceiver → NotesRepository(context).load()` | `store.readForReminder()`（只读，不 persist、不排他） |
| 附件读写 | `NotesRepository.attachmentFile()` 直连 | `store.attachment(name)`，导入期间走事务目录 |

### 6.2 状态与写权限

| 状态 | `saveAll` | `import` | `load` | 提醒调度 |
|---|---|---|---|---|
| `OPEN` | 允许 | 允许 | 允许 | 允许 |
| `IMPORTING` | 拒绝（`Busy`） | 拒绝（`Busy`） | 拒绝（`Busy`） | 暂停 |
| `COMMITTED_PENDING_PUBLISH` | **拒绝**（`Blocked`） | 拒绝 | 允许（读新数据） | 暂停 |
| `BLOCKED` | 拒绝（`Blocked`） | 拒绝 | 返回 `Blocked`，不覆盖内存 | **不排** |
| `RECOVERING` | 拒绝（`Busy`） | 拒绝 | 排队等待 | 暂停 |

三条硬约束：

1. **`BLOCKED` 时旧内存不得继续写盘。** 由 store 拒绝实现，不依赖 UI 自觉；`NotesState.persist()` 收到 `Blocked` 后置 `persistenceError` 并停止重试。
2. **磁盘已提交、内存未发布时，禁止保存。** 否则会用旧快照覆盖刚提交的新数据。`COMMITTED_PENDING_PUBLISH` 就是这个窗口，写入口全关，直到 `_notes` 替换完成。
3. **不发布中间态供提醒调度。** `NotesState.load()` 里的 `scheduler::schedule` 只在内存发布完成且状态回到 `OPEN` 后执行。

`NotesState.load()` 里「清理回收站 → `persist()`」这类隐式写入，在 `BLOCKED` / `COMMITTED_PENDING_PUBLISH` 下必须跳过，不得当作普通保存放行。

### 6.3 ticket 代次与修改前门禁

```kotlin
data class WriteTicket(val txn: Long, val epoch: Long)

sealed interface WriteStart
  data class Granted(val ticket: WriteTicket) : WriteStart
  data class Refused(val reason: Refusal) : WriteStart     // Busy | Blocked(reason)

fun beginWrite(snapshot: NotesSnapshot): WriteStart
```

- `epoch` 只在**写权限发生变化**时自增：`OPEN→IMPORTING`、`IMPORTING→OPEN`、`→COMMITTED_PENDING_PUBLISH`、`→BLOCKED`、`→RECOVERING`。普通保存成功**不改** `epoch`（否则排队中的下一次保存会被无谓作废）。
- `beginWrite()` 在 `BLOCKED` / `IMPORTING` / `COMMITTED_PENDING_PUBLISH` / `RECOVERING` 下**不发放 ticket**，返回 `Refused`。所以「拿到 ticket」本身就表示「当时允许写」。
- **修改前门禁**在三个点各校验一次 `ticket.epoch == store.epoch`：
  1. 替换内存快照前；
  2. 调用 `store.saveAll` 写盘前；
  3. 写盘返回、发布结果前。
  任一处不符 → 丢弃本次写入（`Superseded`），**不落盘、不改内存、不报错给用户**。这堵住「保存已排队，排队期间导入把状态改成 `IMPORTING`，旧快照随后覆盖新数据」。
- 导入本身也持 ticket（`txn` 字段是 txnId 的哈希），用于让并发保存确定地失效。

### 6.4 异步回调代次

`persist` 完成、失败上报、导入结果投递、提醒重排，全部携带发起时的 `epoch`：

- 回调执行时 `epoch != store.epoch` → **丢弃**，不写 `persistenceError`，不投递结果，不重排提醒。
- 理由：`persistenceError` 是「当前状态」的标志，旧保存的失败不能把新状态标成错误；同理旧导入的结果不能覆盖新事务的通知。
- 结果**不依赖回调**投递：真值在磁盘，回调丢了用 `currentImport(txnId)` 重查（§8.2）。

### 6.5 提醒读取协调与 `BLOCKED` 下的加载

- `readForReminder()` 不加写锁，依赖 `rename` 原子性：读到的要么是旧文件要么是新文件，不存在半份。若为了省事加了读锁，也不能提升为写权限。
- 读取时的状态处理：
  - `IMPORTING` / `RECOVERING` → 返回 `Deferred`，**跳过本轮排程**（避免按旧数据排出一批马上要作废的提醒）。
  - `BLOCKED` → 返回 `Blocked`，跳过排程，不尝试修复。
  - `COMMITTED_PENDING_PUBLISH` → 读磁盘新数据，但**不排程**（等发布完成）。
  - `OPEN` → 正常读取并排程。
- **`BLOCKED` 下的普通加载规则**：普通 `load()`（下拉刷新、Activity 重建）与开机读取同一规则——返回 `Blocked(cause)`，不覆盖内存快照、不 `persist()`、不排程；UI 只展示上一次内存快照并提示只读。
- `BLOCKED` 的唯一解除路径是 `recover()` 重新 `classify()` 得到 `Idle` / `Committed` / `Aborted`；不允许 UI「再点一次保存」或「重启加载成功」就恢复写权限。`load()` 成功**不等于**状态健康。

---

## 7. 取消：谁回滚，谁阻塞

### 7.1 取消窗口

`store.import()` 内部的取消点只在**可取消段**：备份、逐附件替换之间、写 `notes.json` 之前，每处前都有 `ensureActive()`。

| 取消落点 | 磁盘状态 | 责任 |
|---|---|---|
| 备份阶段（§2.2 第 1–5 步） | 正式数据未动，或仅多了副本 | 提交函数负责：`withContext(NonCancellable + IO) { rollback() }`，收敛到旧状态后写 `ABORTED` → 清理；journal 尚未写入时直接删材料即可（无事务记录） |
| 附件替换阶段（§2.3 第 3 步） | 部分附件已是新字节 | 同上，按 §5.3 逐个恢复 |
| `notes.json` rename 之前 | 附件已新、notes 仍旧 | 同上，附件全部恢复 |
| 不可取消段内（rename / 凭据） | 已提交或即将提交 | **不回滚**：取消不在这一段投递，两步必须走完 |
| 凭据写完、发布内存前 | `Committed` | **不回滚**：结果 `Committed` + `Skipped(cancelled)` → `COMMITTED_PENDING_PUBLISH`，下次启动发布 |

### 7.2 不可取消段边界

`withContext(NonCancellable)` **只包住两步**：

1. §2.3 第 4 步 `notes.json` 的 rename；
2. §2.3 第 5 步 `committed.json` 的写入。

原因：这两步一旦开始就必须走完，否则会停在「数据换了但没凭据」或「凭据写了但数据没换」的不可判状态。其余步骤都可被取消，因为都有 §4/§5 的恢复路径。

清理整段不可取消（保证删除顺序与门禁不被跳过）；它每步幂等，被打断最多留下 `Orphan` 或「证据 + 材料」，不会产生错误判读。

### 7.3 取消后的异常与结论

- 回滚完成后**重新抛出原始的 `CancellationException`（同一实例）**，不替换成回滚异常，不吞掉。沿用 1A 的规则：清理失败不覆盖原始原因，残留单独记录。
- 回滚本身失败 → 写 `ROLLING_BACK` + `failedAt`，store 进 `BLOCKED`，`currentImport(txnId)` 报 `RollbackFailed`；原始取消照常抛出。**不允许**因为「用户取消了」就把 `BLOCKED` 说成「用户取消，无事发生」。
- 取消发生在不可取消段（即已提交）：取消**不是失败**。UI 拿到 `CancellationException` 时必须先 `currentImport(txnId)`，得到 `Committed` 就按成功处理，不得提示「导入失败」。
- 取消发生在 `prepare()` 属 1A 范围，本协议不改。

---

## 8. 结果表达与留存

### 8.1 四层结果分开表达

```kotlin
sealed interface DiskOutcome        // 磁盘事实
  NotStarted | Committed | Interrupted | Aborted
  | RollbackFailed | Corrupt | CommittedUnproven
sealed interface PublishOutcome     // 内存发布
  Published(snapshot) | Skipped(reason)
data class CleanupOutcome           // 清理
  (cleaned: Boolean, leftovers: List<String>)
sealed interface ImportResult       // UI 消费
  Imported(count, warnings, cleanup)
  | NotImported(reason)
  | ImportedButBlocked(count, reason)   // 已提交但状态未恢复
  | NeedsUserAction(reason)             // Corrupt / RollbackFailed / CommittedUnproven
```

映射规则（不与磁盘事实冲突，按顺序匹配，先匹配先返回）：

1. `Corrupt` / `RollbackFailed` / `CommittedUnproven` → `NeedsUserAction`。
2. `Committed` + `Published` → `Imported`，清理结果单独附上（`Partial` 不影响成功）。
3. `Committed` + `Skipped`（未发布，含取消）→ `ImportedButBlocked`，下次启动从磁盘重建后再发布。
4. 其余（`NotStarted` / `Interrupted` / `Aborted`）→ `NotImported(reason)`，绝不出现「导入成功」文案。

### 8.2 事实来源

- 唯一事实来源：`journal.json` + `committed.json` + 磁盘数据。任何时刻都可由 `classify()` 从磁盘重建。
- `MutableStateFlow<ImportState>` 只是**通知缓存**，进程死了就丢，丢了不影响判定。
- 因此「协程取消导致返回值没送到」不等于失败：UI 调 `store.currentImport(txnId)` 重读磁盘，得到 `Committed` 就按成功处理并补发布内存。
- 启动顺序固定：`recover()`（`classify()` + 按结论收尾）→ 得到 `Idle` / `Blocked` / `CommittedPendingPublish` → `load()` → 发布内存 → 排提醒。

### 8.3 按 `txnId` 的结果留存

`results.json` 只回答「这次导入最后怎么样」，**不参与提交判定**（§2.4），也不含摘要：

```json
{"version":1,"records":[
  {"txnId":"...","outcome":"Committed","count":12,"at":1762900000123,"consumed":false},
  {"txnId":"...","outcome":"Aborted","reason":"Cancelled","at":1762900000456,"consumed":true}
]}
```

- 写入时机：提交时（§2.3 第 6 步）写 `Committed`；回滚完成时（§5.4）写 `Aborted`；`StagingFailed` 时写 `NotStarted`。清理第 0 步负责补写缺失记录。
- 清理**不删** `results.json`，所以「清理完成后才回来取结果」仍能按 `txnId` 命中。
- 读取：`currentImport(txnId)` 命中记录即返回并把 `consumed` 置 `true`；`currentImport()` 返回最近一条未消费记录。
- 上限 8 条，超出按「最旧且已消费」优先淘汰；未消费记录至少保留 1 条。
- 后续正常保存不改写 `results.json`。历史记录存在**不影响** `classify()`：判定只看 journal / 凭据 / 摘要，所以「导入完成后用户又编辑并保存」不会因为 `notes.json` 变了而被判 `Corrupt`，状态仍是 `OPEN`。

---

## 9. 耐久性边界（收窄）

- **只承诺分类，不承诺结果**：任何掉电后重启，`classify()` 必须给出 `Committed` / 未提交（`Prepared`、`Interrupted`、`Aborted`、`Idle`、`Orphan`）/ `BLOCKED` 之一。
- **改数据前必先落 journal**：`journal` 的 `APPLYING` 先于任何正式文件写入，所以正常情况下不会出现「正式数据改了一半、磁盘上没有任何记录」。唯一例外是 `journal` 与凭据都未被持久化，而此时 `notes.json` 已换成载荷 → 归 §3.2 #2b 的 `CommittedUnproven`，同样 `BLOCKED`，不会静默。
- **不承诺** `fsync` 返回后目录项已持久化；不承诺掉电后 `notes.json` 与附件同代；不承诺 `results.json` 不丢；不承诺自动恢复。最坏结果是 `BLOCKED`，需用户介入。
- 掉电可能破坏副本本身（`old-notes.json` 或 `old-attachments/` 未持久化）：此时若正式文件已不是旧内容，回滚无法完成 → `BLOCKED`，保留现场，不猜、不删。
- 若 `journal` 与凭据都未持久化、而 `notes.json` 已换成载荷内容 → §3.2 #2b 的 `CommittedUnproven`：数据看起来已应用，但无证据，`BLOCKED`。协议**不**自动把它当成功，也**不**自动回滚一份用户可能已经看到的数据；由用户在确认摘要后手工收尾。
- 不清洗用户数据，不做自动修复式回滚；`Corrupt` 一律停写并保留现场。
- 不处理录音文件（备份不含录音，1A 已定）。
- 本文不包含 UI 交互细节，只定义状态到四类结果的映射。

---

## 附录 A：操作步骤 — 异常 / 进程中断 — 恢复动作

| # | 操作步骤 | 异常 / 中断 | 恢复动作 |
|---|---|---|---|
| 1 | prepare 拷 zip | 进程被杀 | 无 journal；删 `staging-*` 残留（超时清理），正式数据未动 |
| 2 | prepare 校验 / 扫条目 | 拒绝或取消 | 1A 已有：删暂存，返回 `Invalid` / 抛取消 |
| 3 | 写 `plan.json` / `commit.json` | 写失败或被杀 | 材料不完整；`StagingFailed` → 清理；无 ID 被使用 |
| 4 | 写 journal `PREPARED` | 写失败 | 未动正式数据；`NotStarted`；材料保留可重试 |
| 5 | 改名 `staging-*` → `txn-*` | 被杀 | 无 journal + 材料 + 数据未变 → `Orphan`，删材料 |
| 6 | 算旧摘要、写 journal `APPLYING` | 被杀 | journal 未落盘同上；已落盘但未备份 → `Interrupted`，回滚是空操作 |
| 7 | 复制 `old-notes.json` | 被杀 | 半截副本只在 `.tmp`；`notes.json` 仍等于 `Dold` → 回滚跳过并清理 |
| 8 | 复制旧附件、写 `old-manifest.json` | 被杀 | 同上；数据未变时回滚是空操作，可直接转入 `ABORTED` 清理 |
| 9 | 逐附件替换 | 被杀 | `APPLYING` 无凭据 → 回滚（§5.3），`A` 只有新旧两态 |
| 10 | 写 `notes.json`（rename） | 被杀 | 同上；旧内容从 `old-notes.json` 恢复 |
| 11 | 写 `committed.json` | 失败 / 被杀 | 无凭据 → `Interrupted` → 回滚（哪怕 notes 已是新内容） |
| 12 | 写 `results.json`（Committed） | 被杀 | 凭据在 → `Committed`；清理第 0 步补写记录 |
| 13 | 写 journal `COMMITTED` | 被杀 | 凭据在 → `Committed`；补写终态后清理 |
| 14 | 发布内存 | 进程死 / 取消 | `Committed` + `Skipped` → `COMMITTED_PENDING_PUBLISH`；下次启动发布 |
| 15 | 清理第 0 步（补结果记录） | 失败 | 停，不删材料，保留全部证据 |
| 16 | 清理删材料（第 1–4 步） | 被杀 / 失败 | 证据仍在 → `Committed`，重跑；目录非空则门禁不通过 |
| 17 | 清理删 journal | 被杀 | 凭据 + 摘要成立 → `Committed`，重跑删凭据 |
| 18 | 清理删凭据 | 被杀 | 仅剩凭据或已无 → `Committed` / `Idle` |
| 19 | 回滚：恢复 `notes.json` | 被杀 | `ROLLING_BACK` 继续；摘要 == `Dold` 跳过 |
| 20 | 回滚：恢复附件 | 被杀 | 摘要 == old 的跳过，其余从副本恢复 |
| 21 | 回滚失败（有文件需恢复但副本不可用） | IO 错误 / 副本缺失 | `BLOCKED`，材料全保留，停止清理 |
| 22 | journal 损坏 | JSON 不可解析 | 凭据成立 → `Committed`；否则 `Corrupt` → `BLOCKED` |
| 23 | 摘要与凭据不符 | 外部改动 / 磁盘损坏 | `Corrupt` → `BLOCKED`，保留材料与现场 |
| 24 | 无 journal 无凭据但材料在，notes 已是载荷 | 掉电丢凭据 | `CommittedUnproven` → `BLOCKED`，保留材料，人工确认 |

## 附录 B：计划修改的文件与测试（含具体反例）

### 生产代码

| 文件 | 改动 |
|---|---|
| `data/ImportJournal.kt`（新） | journal / 凭据 / 结果留存的原子读写，`classify()` 全表判定，摘要重算 |
| `data/ImportTransaction.kt`（新） | §2.2 备份、§2.3 提交、§4 清理（含门禁）、§5 回滚（副本恢复）、`recover()` |
| `data/NotesStore.kt`（新） | 唯一存储入口、`StoreState`、`epoch` 与 `WriteTicket`、写权限拒绝、`currentImport(txnId)` |
| `data/BackupImporter.kt` | prepare 末尾物化 ID/时间戳、写 `plan.json` 与 `commit.json`、返回物化后的 `notes` |
| `data/BackupImportModels.kt` | `PreparedBackup` 增 `plan` / `commitFile` / `txnId`；`notes` 语义改为「物化结果」 |
| `data/NotesRepository.kt` | `save` / `load` / 附件操作委托 `NotesStore`；删除 `importBackup` 的直连实现 |
| `ui/NotesState.kt` | `persist` 走 `store.saveAll` 并处理 `Busy`/`Blocked`/`Superseded`；`importBackup` 走 `store.import`；BLOCKED 时跳过隐式写入与排程 |
| `reminder/ReminderReceiver.kt` | `ReminderBootReceiver` 改用 `store.readForReminder()`，`Deferred`/`Blocked` 时跳过 |
| `MainActivity.kt` | 导入结果按 §8.1 四态消费；确认对话框数据取自 `prepared.warnings` |

### 测试（`androidTest`，沿用现有隔离沙箱与注入接缝）

只列要覆盖的**具体反例**，不设数量目标。

| 测试 | 反例 / 断言 |
|---|---|
| `ImportPlanTest` | ① 同一 `plan.json` 连续序列化 20 次，`commit.json` 字节全等；② **反例**：缺时间戳的备份两次独立 prepare（`preparedAt` 不同）得到不同字节，且用第一次的 `commitDigest` 校验第二次材料必须 `MaterialChanged`，不得判等；③ 改 `backup.zip` 内 notes 的一个字节 → `notesDigest` 校验失败；④ `plan.notes` 与 `commit.json` 的 ID 逐条相同 |
| `ImportJournalTest` | §3.2 表格逐行（含 2a/2b/2c 三个分支）；**反例**：journal 不可读 + 无凭据 → `BLOCKED` 且断言 `txn-*/` 仍存在；journal 不可读 + 凭据匹配 → `Committed` 且能重建 journal |
| `ImportCommitTest` | §2.3 顺序（含第 0 步改名）；**反例**：让 §2.2 第 3 步（复制 `old-notes.json`）失败 → 断言 `notes.json` 未被写、正式摘要不变；让第 5 步（凭据）失败 → 断言 `DiskOutcome == Interrupted` 且回滚后 `notes.json == Dold` |
| `ImportCommitCancelTest` | 取消落在 rename 前 / 凭据写完后各一次：前者同步回滚到 `Dold` 并抛同一 `CancellationException` 实例；后者 `Committed` 且不抛失败文案 |
| `ImportRecoveryTest` | 附录 A 第 5–14 行逐个注入中断，断言 `DiskOutcome` 与正式数据摘要；**反例**：备份中途中断（副本半截）→ 必须判 `Interrupted` 且回滚是空操作，不得 `BLOCKED` |
| `ImportRollbackTest` | ① `old-notes.json` 被截断 + `notes.json` 已是新内容 → `BLOCKED`，断言未删 `notes.json`；② 附件已是新字节且 `O` 缺失 → `BLOCKED`；③ 回滚中途再中断两次 → 仍收敛到 `Dold`；④ `Dold == null` → 目标是不存在，仅摘要 `== Dnew` 时删 |
| `ImportCleanupTest` | ① 删 journal 失败 → 断言凭据与材料都在；② 材料残留（目录非空）→ 断言**未**删 journal 与凭据；③ 重复清理三次结果相同；④ 清理完成后 `currentImport(txnId)` 仍返回 `Committed`；⑤ 清理后再 `saveAll` 一次 → `classify()` 为 `Idle`/`OPEN`，不报 `Corrupt` |
| `ImportMaterialCorruptTest` | **反例**：`APPLYING` 下删掉 `txn/commit.json` → `BLOCKED` 且材料未被清理；对照：`PREPARED` 下删掉同文件 → 清理成功回到 `Idle` |
| `NotesStoreTest` | ① 保存排队期间进入 `IMPORTING` → ticket 失效，旧快照未落盘；② `BLOCKED` 下 `beginWrite()` 返回 `Refused`；③ 旧 `epoch` 的失败回调不写 `persistenceError` |
| `ReminderBootReadTest` | ① `IMPORTING` 时 `readForReminder()` 返回 `Deferred` 且未排程；② `BLOCKED` 时 `load()` 不覆盖内存（断言内存快照仍是旧对象）且未排程；③ `COMMITTED_PENDING_PUBLISH` 读到新数据但不排程 |
| `ImportResultMappingTest` | 取消落在已提交窗口 → 结果为 `ImportedButBlocked` 而非 `NotImported`；`CommittedUnproven` → `NeedsUserAction` |
