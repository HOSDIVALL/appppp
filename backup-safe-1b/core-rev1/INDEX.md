# BACKUP-SAFE-1B-CORE / core-rev1

core-rev0 首轮评审未通过后的修订版。范围只有 **B1–B6**：不固化、不接线、不写长协议报告。

- 源码基线（业务代码未动）：`26a0de3cc80636718059c118d5af6e4c6f179fc0`
- 修订起点：`backup-safe-1b/core-rev0`（本次原样保留，未改动其中任何文件）
- 本批仍**只新增/修改 `data/txn/` 包内的文件**：不改既有业务源码、不改依赖、
  不接 prepare / `NotesStore` / UI
- 集成分支 `fix/backup-safe-1` 仍停在基线，实现留在工作区未提交；未合并 `main`、未建 PR

## 1. 目录

| 路径 | 内容 |
|---|---|
| `source/app/src/main/java/.../data/txn/` | 12 个生产文件（完整源码副本） |
| `source/app/src/androidTest/java/.../data/txn/` | 13 个测试文件（含 5 个新增回归套件） |
| `diff/core-rev1.diff` | **相对 core-rev0 的增量 diff**（18 个文件：12 新增 + 6 修改） |
| `test-results/red-on-core-rev0.xml` | 先加测试、未改实现时的红基线：`tests=96 failures=28` |
| `test-results/targeted-txn.xml` | 修订后事务包：`tests=96 failures=0` |
| `test-results/full-androidtest.xml` | 修订后全量：`tests=183 failures=0` |
| `logs/` | 三次运行的 Gradle 输出 + 增量 diff 回放校验 |
| `SHA256SUMS` | 源码副本与 diff 的校验和 |
| `INDEX.md` | 本文件 |

`source/` 保留仓库内相对路径，可直接覆盖回工作区复跑，也可与 `core-rev0/source` 逐文件比对。

## 2. B1–B6 落点

### B1 回滚统一按磁盘分类授权

`ImportTxnRollbackPath.rollbackLocked` 的第一件事是 `classifier.classify()`，**不再看
`journal.state` 的字样**：

| 分类结果 | 处置 |
|---|---|
| `Interrupted` / `RollingBack` / `RollbackFailed` | 授权，且状态里的 `txnId` 必须等于参数 |
| `Committed` | `refuse-rollback-committed`（凭据成立时 `APPLYING` 也算已提交） |
| `Corrupt` / `Quarantined` / `Prepared` / `Aborted` / `StagingFailed` / `Idle` | 一律拒绝，材料保留 |

提交中途失败的内部回滚（`settleAfterFailure`）走同一个入口，所以「内部路径绕过授权」的口子也一起堵上了。

测试：`rollbackRefusedWhenApplyingButCredentialIsValid`（B1 的核心反例）、
`rollbackRefusedWhenStateIsCorrupt`、`rollbackRefusedWhenQuarantined`、
`rollbackRefusedForDifferentTxnId`、`rollbackRefusedForUnsafeTxnId`。

### B2 cleanup 先核对终态 txnId

`ImportTxnCleanupPath.cleanupLocked` 的顺序改成
`安全名 → 分类 → (stateTxnId, outcome) → 比对 → 写结果 → 删材料 → 删目录 → 删 journal → 删凭据`。
比对失败返回 `CleanupOutcome(cleaned=false, stoppedAt="txn-id")`，**在写任何字节之前返回**：
不写结果、不删材料、不碰 journal / 凭据。`discardQuarantined` 同样先校验安全名。

测试：`cleanupWithWrongTxnIdTouchesNothing`、`wrongTxnIdDoesNotConsumeTheRealCleanup`、
`cleanupAfterAbortedAlsoChecksTxnId`、`cleanupRefusedForUnsafeTxnId`。

### B3 闸门释放与 OPEN 分开

| 要求 | 落点 |
|---|---|
| 释放 ≠ 放行 | `WriteGate.releaseExclusive(ticket, next)` 只交还写锁并把状态留给调用方；`ImportTxnKernel.nextModeLocked()` 只在 `classify() is Idle` 时给 `OPEN`，其余给 `BLOCKED` |
| 损坏/隔离/回滚失败继续停写 | `recover()` 结束后按上一条决定状态，`Corrupt`/`Quarantined`/`RollbackFailed`/`Prepared` 全部 `BLOCKED` |
| 清理未完成不得放行 | 清理停在 `materials`/`journal`/`credential` 时状态仍是 `Committed`，`nextModeLocked()` 给 `BLOCKED` |
| 恢复与普通写共享互斥 | `beginRecovery()` 先 `writer.lock()` 再切 `RECOVERING`；与 `write()` 用同一把锁，且不看当前状态，`BLOCKED` 之后仍可重试恢复 |
| 写票重放 vs 回调新鲜度 | `Refusal.Superseded`（票已用掉/已跨代，写前拒绝，无 IO）与 `Freshness.Stale`（可能已落盘，结果必须丢弃）分开表达 |
| 跨闸门拒绝 | `Ticket` 带 `gateId`，`write()` / `freshness()` / `releaseExclusive()` 都核对；外来票返回 `Refusal.ForeignTicket` |

测试（8 例）：`recoverAfterCorruptKeepsWritesBlocked`、`recoverAfterQuarantineKeepsWritesBlocked`、
`recoverAfterRollbackFailedKeepsWritesBlocked`、`commitWithUnfinishedCleanupKeepsWritesBlocked`、
`cleanupRefusedByStateStillKeepsWritesBlocked`、`recoverWaitsForInFlightSave`、
`foreignTicketIsRefused`、`replayIsRefusedAsStaleNotBusy`。

### B4 journal / 凭据严格校验

- **缺字段 ≠ 显式 null**：`TxnCodec.optionalString` 先 `has(key)`，缺了整份判不可读。
  适用于 `oldNotesDigest`、附件 `old`、`failedAt`、结果 `reason`。
- **形状校验**：`txnId`、附件名走 `TxnLayout.isSafeName`（拒绝 `..`、路径分隔符、控制字符）；
  摘要必须是 64 位小写十六进制。
- **恢复路径边界**：`rollbackLocked` / `cleanupLocked` / `discardLocked` 入口先校验安全名；
  所有磁盘路径由 `TxnLayout` 用 `txnId` 与附件名拼装，不再就地 `File(root, ...)`。
- **凭据不可读 ≠ 不存在**：`TxnClassifier` 五个 journal 分支都把 `CredentialRead.Unreadable`
  判为 `Corrupt`，不再落到「没凭据 → 可回滚」。凭据与 journal 同时不可读时也单独区分为
  `journal-and-credential-unreadable`。

测试（11 例，`ImportTxnRev1CodecTest`）：缺 `oldNotesDigest`、缺附件 `old`、缺 `new`、
越界 `txnId`、越界附件名、畸形摘要（journal 与凭据各一）、
`unreadableCredentialIsNotTreatedAsAbsent`、`journalWithUnsafeTxnIdNeverReachesDisk` 等。

### B5 备份阶段取消由当前 commit 收尾

`ImportTxnCommitPath.commitLocked` 的备份段新增
`catch (CancellationException) → withContext(NonCancellable) { rollback } → 重抛同一实例`。
取消不再「逃出调用栈等下次启动」，本次 commit 自己把 journal 推到 `ABORTED` 并写回滚结果。

测试 `ImportTxnRev1CancelTest` 两例（`OLD_NOTES` 与 `old-attachment:a.jpg` 两个窗口）
**先断言取消结束后的磁盘状态**（旧 notes、旧附件、无凭据、结果记录为 `ABORTED`、
`inspect() == Aborted`），**之后才**（如需）调用恢复流程——不再用 `recover()` 冒充当前调用的收尾。

### B6 回滚遇到既非 old 也非 new 的内容

`ImportTxnRollbackPath` 把回滚拆成「先算计划、再执行」：

| 当前内容 | notes（有旧副本） | 附件（有旧副本） | 附件（无旧副本） |
|---|---|---|---|
| `== old` | 跳过 | 跳过 | — |
| `== new` | 从副本恢复 | 从副本恢复 | 删除 |
| 文件不存在 | **停写** `notes-missing` | **停写** `attachment-missing:<name>` | 跳过 |
| 其它内容 | **停写** `notes-not-from-txn` | **停写** `attachment-not-from-txn:<name>` | **停写** `unexpected-attachment:<name>` |

需要动用副本时，先核对 `old-manifest.json`：缺文件 → `old-manifest-missing`、读不出 →
`old-manifest-unreadable`、与 journal 的「notes 是否存在 / 旧摘要 / 附件名与旧摘要集合」不符 →
`old-manifest-mismatch`，一律停写并保留材料。**全部文件都已是目标状态时不要求 manifest**，
这样重复中断的最后一跳仍能收敛。

测试：`rollbackStopsWhenAttachmentContentIsNeitherOldNorNew`、`rollbackStopsWhenNotesIsNeitherOldNorNew`、
`rollbackStopsWhenManifestIsMissing`、`rollbackStopsWhenManifestDisagrees`、
`rollbackWithoutAnyRestoreDoesNotNeedManifest`。

## 3. 文件拆分（只为便于审阅，不改架构）

`ImportTxnKernel.kt` 从 779 行降到 218 行，机制按职责分到独立文件；内核本身只留
「串行化 + 闸门 + 恢复编排」。

| 文件 | 行数 | 职责 |
|---|---|---|
| `ImportTxnKernel` | 218 | `Mutex`、闸门发放/交还、`recover` 分支、只读查询 |
| `ImportTxnCommitPath` | 240 | 物化、备份（含清单）、替换、提交点、提交后清理 |
| `ImportTxnRollbackPath` | 239 | 分类授权的回滚、逐文件计划与执行、清单校验 |
| `ImportTxnCleanupPath` | 135 | 清理（先核 `txnId`）与隔离处置 |
| `TxnJson` | 219 | journal / 凭据 / 结果 / manifest 的严格编解码 |
| `WriteGate` | 185 | `gateId + epoch + sequence + writer`，票与新鲜度 |
| `TxnModels` | 165 | `DiskState` 十态、journal、凭据、结果、manifest |
| `TxnClassifier` | 104 | 磁盘 → `DiskState`（不写文件） |
| `TxnDisk` | 74 | 只读访问，`Absent` 与 `Unreadable` 分开 |
| `TxnLayout` | 66 | 布局、路径拼装、`isSafeName` |
| `TxnLedger` | 60 | 结果留存 |
| `TxnFileOps` | 129 | 未改动 |

## 4. 先红后绿

1. **先只加测试**（5 个 `ImportTxnRev1*` 套件，35 例），实现仍是 core-rev0，跑出
   `tests=96 failures=28`（`test-results/red-on-core-rev0.xml`）：

   | 门 | 失败数 | 代表用例 |
   |---|---|---|
   | B5 | 2 | `cancelWhileCopyingOldNotesIsSettledByTheSameCommit` |
   | B2 | 4 | `cleanupWithWrongTxnIdTouchesNothing`、`cleanupRefusedForUnsafeTxnId` |
   | B4 | 9 | `journalWithoutOldNotesDigestIsUnreadable`、`unreadableCredentialIsNotTreatedAsAbsent` |
   | B3 | 8 | `recoverAfterCorruptKeepsWritesBlocked`、`foreignTicketIsRefused` |
   | B1 | 1 | `rollbackRefusedWhenApplyingButCredentialIsValid` |
   | B6 | 4 | `rollbackStopsWhenManifestIsMissing` 等 |

2. **再改实现**（本目录源码）。
3. 定向 `tests=96 failures=0 errors=0 skipped=0`（`test-results/targeted-txn.xml`）。
4. 全量 `tests=183 failures=0 errors=0 skipped=0`（`test-results/full-androidtest.xml`，含 1A 的 87 项）。

新增 35 例分布：`ImportTxnRev1CodecTest` 11、`ImportTxnRev1RollbackTest` 11、
`ImportTxnRev1GateTest` 8、`ImportTxnRev1CleanupIdTest` 3、`ImportTxnRev1CancelTest` 2。
事务包从 61 例增到 96 例。数量只作记录，不作为验收目标。

## 5. 原测试的处理

- core-rev0 的 13 个文件全部保留，**没有删除、没有放宽任何断言**；其中 6 个测试文件
  （`ImportTxnClassifyTest`、`ImportTxnCommitTest`、`ImportTxnRollbackTest`、`ImportTxnCleanupTest`、
  `ImportTxnJournalTest`、`ImportTxnCancelTest`）逐字节未改。
- 两处必要改动，都与断言强度无关：
  1. `WriteGateTest` 里 `endExclusive(t)` → `releaseExclusive(t, WriteGate.Mode.OPEN)`。
     方法名本身表达了「释放即放行」，B3 之后这两件事分开了；断言仍是原来那三条
     （回 `OPEN`、旧回调变 `Stale`、之后能继续领票）。
  2. `TxnTestSupport.plant` 追加写 `old-manifest.json`。真实崩溃点是在备份完成之后才替换，
     现场本来就应该有这份清单；各用例的断言未变。

## 6. 本批明确没做的事

- 不接 `NotesRepository` / `NotesState` / `MainActivity` / `ReminderReceiver`；
- 不接 prepare 物化、共享 `NotesStore`、UI、媒体回调与提醒读取（协议 §6.6 的 G1–G4）；
- 不改 `PROTOCOL.md`——本轮不写长协议报告，新规则只落在代码与本文第 2 节；
- 不改依赖、不删旧导入、不操作真机、不合并 `main`、不建 PR。

## 7. 已知限制

- 注入的仍只是**代码级 `IOException`**，不等于真实磁盘故障或掉电；掉电只承诺
  `Interrupted` / `Quarantined` / `Corrupt` 三态可判定。
- `commit` 的起始检查是「没有 journal、没有凭据、没有别的事务目录」三项，而不是
  `classify() == Idle`：本次要提交的材料目录本身就在磁盘上，会被单独判成隔离态，
  必须排除掉。等价性由上三条覆盖。
- `releaseExclusive` 无论回到哪个状态都推进 `epoch`，因此导入期间发出的票在结束后一律
  `Stale`；这是刻意的，代价是调用方必须靠 `freshness()` 而不是返回值判断「还要不要发回调」。
- manifest 只在**需要动用副本**时校验；全部文件已达目标时不校验，否则收敛的最后一跳会被
  已清理的清单卡住。这个取舍是把「不覆盖来路不明的内容」放在「manifest 必须永远存在」之前。
- `Refusal.Superseded` 与 `Freshness.Stale` 目前只在闸门层区分，产品文案与重试策略留给接线批。
