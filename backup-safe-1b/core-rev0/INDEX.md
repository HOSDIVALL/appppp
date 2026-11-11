# BACKUP-SAFE-1B-CORE / core-rev0

隔离事务内核与隔离测试的第一版实现证据。本目录只证明「内核按 rev2 协议工作」，
不证明任何生产路径已经接入。

- 源码基线：`26a0de3cc80636718059c118d5af6e4c6f179fc0`（`fix/backup-safe-1`）
- 协议：`backup-safe-1b/PROTOCOL.md`（含进入本批前修入的四项规则，见该文档文首 #7–#10）
- 本批改动：**新增 13 个文件，未修改任何既有文件、未改依赖、未改 Gradle 配置**
- 集成分支状态：`fix/backup-safe-1` 仍停在基线 `26a0de3`，实现留在工作区未提交；
  未合并 `main`、未建 PR、未推送实现分支

## 1. 目录

| 路径 | 内容 |
|---|---|
| `source/app/src/main/java/.../data/txn/` | 5 个生产文件（事务内核） |
| `source/app/src/androidTest/java/.../data/txn/` | 8 个测试文件（含测试支撑） |
| `diff/core-rev0.diff` | 相对基线的完整 diff（13 个 `new file`，118 KB） |
| `test-results/targeted-txn.xml` | 事务包定向测试结果 XML |
| `test-results/full-androidtest.xml` | 全量 `connectedDebugAndroidTest` 结果 XML |
| `logs/targeted-txn.log` | 定向测试 Gradle 输出 |
| `logs/full-androidtest.log` | 全量测试 Gradle 输出 |
| `logs/replay-check.log` | 用基线重放 `diff/core-rev0.diff` 的逐文件校验记录 |
| `SHA256SUMS` | 上述源码副本与 diff 的校验和 |

`source/` 下保留了仓库内相对路径，因此可以直接与基线检出逐文件比对，也可以把
`source/app/...` 覆盖回工作区后复跑测试。

## 2. 生产文件（5 个，共 1338 行）

| 文件 | 行数 | 职责 |
|---|---|---|
| `ImportTxnKernel.kt` | 779 | `classify / commit / rollback / cleanup / recover / inspect / results / discardQuarantined`；所有公开操作走 `mutex.withLock { withContext(io) { … } }`，同一根目录的并发由这一把锁串行化 |
| `TxnModels.kt` | 150 | journal、凭据、结果记录的数据形态，`DiskState` 十态，各类 `*Outcome`；不引用 `Note` / `PreparedBackup`，只处理已物化字节 |
| `TxnFileOps.kt` | 129 | 唯一文件接缝 `TxnFileOps` + `RealTxnFileOps`，原子写收敛为 `writeAtomic`；`TxnStep` 定义稳定注入点名 |
| `TxnJson.kt` | 136 | journal / 凭据 / 结果的严格编解码，任何版本、枚举、类型、重名异常都返回 `null` 而不是猜测 |
| `WriteGate.kt` | 144 | `Ticket(epoch, sequence)` 代次校验；`Admission.Refused` 是写前拒绝，`Freshness.Stale` 是写后旧回调失效 |

内核不持有 `Context`，构造参数只有根目录、文件操作、写闸门、时钟、IO 调度器：

```kotlin
internal class ImportTxnKernel(
    val root: File,
    private val ops: TxnFileOps = RealTxnFileOps(),
    private val gate: WriteGate = WriteGate(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
)
```

## 3. 协议规则 → 实现落点

| 协议条款 | 实现位置 |
|---|---|
| §3.2 #2 无 journal/凭据的 `txn-*` ⇒ `Quarantined` | `classifyLocked()`：凭据不匹配且 `txnDirs` 非空即隔离；`recover()` 对该态只返回停手，不删；只有 `discardQuarantined(txnId)` 能清 |
| §3.2 #11 `APPLYING` 无终态证据 ⇒ `Interrupted` | `judge()` 的 `APPLYING` 分支只在「凭据完整匹配且摘要一致」时判 `Committed`，其余一律 `Interrupted`；注释显式写明**不看** `notes.json` 是否已等于新载荷 |
| §2.2 备份顺序 | `commitUnderLock()`：`APPLYING` journal → `old-notes.json` → `old-attachments/` → `old-manifest.json`，全部完成前不写第一个新文件；副本用复制不用搬移 |
| §4.1 唯一提交判据 | 终态证据（凭据可读 或 journal `state==COMMITTED`）且 `sha256(notes.json)==commitDigest` |
| §4.3 清理顺序与门禁 | `cleanupLocked()` 顺序 0–6；门禁「材料目录不存在才删证据」，材料还在就直接返回 `CleanupOutcome(cleaned=false, stoppedAt="gate")` |
| §5.2 回滚完成条件 | `rollbackLocked()` 按「当前摘要 == 目标摘要即跳过」，逐文件判定；`oldNotesDigest==null` 时只有 `nowDigest==commitDigest` 才删 |
| §6.3 写入资格 | `WriteGate.write(ticket){}` 在 writer 互斥体内复核 `mode`、`holding`、`epoch`、`sequence` |
| §6.6 接线门禁 G1–G4 | 只以 KDoc 与测试断言存在，本批**没有**任何生产接线 |

`results.json` 在所有判定里都不参与：它可以被删除、被截断、被写坏，`classify`
结果不变（`ImportTxnJournalTest.resultsFileIsNotEvidence` 断言这一点）。

## 4. 要求的 11 条反例 → 对应测试

| # | 反例 | 测试 |
|---|---|---|
| 1 | 新旧 notes 字节相同、附件不同、部分应用后恢复 | `ImportTxnCommitTest.identicalNotesDifferentAttachmentsRollsBackAttachmentsOnly`、`ImportTxnRollbackTest.onlyAppliedAttachmentsAreRestored` |
| 2 | notes 已替换但凭据未写 | `ImportTxnRollbackTest.notesReplacedWithoutCredentialRestoresOld` |
| 3 | 旧 notes 原本不存在 | `ImportTxnCommitTest.absentOldNotesIsCreatedWithoutCopy`、`ImportTxnRollbackTest.rollbackWithoutOldNotesDeletesTheFile` |
| 4 | 备份中断但正式数据未变 | `ImportTxnCommitTest.journalWriteFailureLeavesLiveDataUntouched`、`ImportTxnCancelTest.cancelDuringBackupAlsoRollsBack` |
| 5 | 回滚重复中断后继续恢复 | `ImportTxnRollbackTest.repeatedInterruptionsStillConverge` |
| 6 | 需要的旧副本损坏时停写并保留材料 | `ImportTxnRollbackTest.corruptedCopyWithCorrectDigestInJournalStopsWrite`、`ImportTxnRollbackTest.missingOldCopyStopsAndKeepsMaterials` |
| 7 | 无 journal/凭据的歧义 txn 不被删除 | `ImportTxnClassifyTest.txnWithoutJournalOrCredentialIsQuarantinedAndKept`、`ImportTxnCommitTest.quarantinedTransactionBlocksNewCommit` |
| 8 | 清理逐步中断不误回滚已提交事务 | `ImportTxnCleanupTest.resultWriteFailureStopsBeforeAnyDeletion`、`materialDeleteFailureStopsBeforeEvidence`、`journalDeleteFailureKeepsCredentialAndCommitFact`、`credentialDeleteFailureKeepsJournalGoneAndMaterialsGone`、`materialsAreNeverLeftWithoutEvidence` |
| 9 | 清理后仍可按 `txnId` 查询历史结果 | `ImportTxnCleanupTest.resultsRemainQueryableByTxnIdAfterCleanup`、`resultsAreCappedAndDeduplicated` |
| 10 | 后续正常保存不被旧事务摘要误判损坏 | `ImportTxnCleanupTest.ordinarySaveAfterCleanupIsNotMistakenForCorruption` |
| 11 | rename/凭据 IO 异常与取消分别处理 | `ImportTxnCancelTest.ioFailureAtNotesIsTreatedAsRollbackNotCancellation`、`ioFailureAtCredentialLeavesNoCredentialAndRollsBack`、`cancelDuringReplacementRollsBackAndRethrowsSameException`、`cancelInsideNonCancellableWindowCommitsAndDoesNotRollBack` |

第 8 条里被注入的都是**代码级 `IOException`**（`FaultyOps` 主动抛
`injected-io-failure:<step>`），不等价于真实磁盘掉电或坏块；真实掉电只按
`Interrupted / Quarantined / Corrupt` 三态可判定来处理。

## 5. 测试怎么跑

环境（本机实测）：

```bash
export JAVA_HOME=D:/Android/jbr
export ANDROID_SERIAL=emulator-5554          # AVD: Medium_Phone_API_37.0 (API 37)
```

定向（只跑事务包）：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.example.myapplicationfirstapp.data.txn
```

全量：

```bash
./gradlew :app:connectedDebugAndroidTest
```

`TxnSandbox` 把每个用例的根目录放在
`targetContext.cacheDir/backup-1b-core/<tag>-<uuid>`，**从不**取 `filesDir`，
`dispose()` 只删自己那一层的 UUID 子目录。`TxnKernelTestBase` 在每个用例前后
对真实 `notes/attachments/recordings` 取指纹并比对，用例结束时断言沙箱已清空，
三类问题合并成一条消息抛出，避免「测试挂了但真实数据也被改了」看不出来。

## 6. 结果

| 范围 | tests | failures | errors | skipped |
|---|---|---|---|---|
| 事务包定向（`test-results/targeted-txn.xml`） | 61 | 0 | 0 | 0 |
| 全量 `connectedDebugAndroidTest`（`test-results/full-androidtest.xml`） | 148 | 0 | 0 | 0 |

全量包含 1A 阶段已有的 87 项，本批新增 61 项；新增用例分布：

| 套件 | tests |
|---|---|
| `ImportTxnClassifyTest` | 13 |
| `ImportTxnCommitTest` | 11 |
| `ImportTxnCleanupTest` | 9 |
| `ImportTxnJournalTest` | 9 |
| `ImportTxnRollbackTest` | 8 |
| `WriteGateTest` | 6 |
| `ImportTxnCancelTest` | 5 |

数量只作记录，不作为验收目标。

`diff/core-rev0.diff` 已用基线重放验证：`git archive 26a0de3 | tar -x` 后
`patch -p1 --fuzz=0` 无 fuzz 应用成功，13 个文件 sha256 与实现工作区逐一相同
（`logs/replay-check.log`）。

## 7. 本批明确没做的事

- 没有把 `NotesRepository`、`NotesState`、`MainActivity`、`ReminderReceiver`
  接到新流程；`git status` 只有两个新增目录。
- 没有接 prepare 物化、共享 `NotesStore`、UI、媒体门禁与提醒读取——这四项
  是协议 §6.6 的接线门禁 G1–G4，留在后批。
- 没有改依赖、没有删除旧导入、没有改既有测试。
- 没有在真机上跑过，也没有上传任何真实数据、APK/AAR 或签名配置。

## 8. 已知限制

- 提交点（`notes.json` rename + 凭据写入）包在 `withContext(NonCancellable)` 里，
  段内到达的取消是**延迟投递**：`withContext` 退出时会重新抛 `CancellationException`，
  调用方拿不到返回值，只能查磁盘确认是否已提交。内核在提交点 catch 到取消时
  **不回滚**，并在注释里写明这个责任边界；对应测试断言的是
  `job.isCancelled` + 磁盘已为新数据 + `inspect()==Idle`，而不是「返回 Committed」。
- `Quarantined` 是终态停手态：只要不被显式丢弃，`commit` 一律拒绝。这是刻意的，
  宁可堵住保存也不猜哪份数据是新的。
- 空间预检用的是 `usableSpace` 估算，不是配额保证；空间不足只保证「不开始」，
  不保证「开始后不会中途失败」。
