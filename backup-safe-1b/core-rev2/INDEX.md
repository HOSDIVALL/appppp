# BACKUP-SAFE-1B-CORE / core-rev2

core-rev1 复审：B1 / B2 / B5 / B6 已接受，B3 / B4 仍有 R1–R3。本目录只修 R1–R3：
不固化、不接线、不重写长协议。`core-rev0/`、`core-rev1/` 原样保留，未改动其中任何文件。

- 源码基线（业务代码未动）：`26a0de3cc80636718059c118d5af6e4c6f179fc0`
- 修订起点：`backup-safe-1b/core-rev1/source`（父提交 `e1fce65`）
- 本批仍只新增/修改 `data/txn/` 包内的文件：不改既有业务源码、不改依赖、
  不接 prepare / `NotesStore` / UI
- 集成分支 `fix/backup-safe-1` 仍停在基线，实现留在工作区未提交；未合并 `main`、未建 PR

## 1. 目录

| 路径 | 内容 |
|---|---|
| `source/app/src/main/java/.../data/txn/` | 13 个生产文件（完整源码副本；新增 `TxnPathGuard`） |
| `source/app/src/androidTest/java/.../data/txn/` | 16 个测试文件（含 3 个新增回归套件） |
| `diff/core-rev2.diff` | **相对 core-rev1 的增量 diff**（15 个文件：11 修改 + 4 新增） |
| `test-results/red-on-core-rev1.xml` | 先加测试、未改实现时的红基线：`tests=111 failures=17` |
| `test-results/targeted-txn.xml` | 修订后事务包：`tests=112 failures=0` |
| `test-results/full-androidtest.xml` | 修订后全量：`tests=199 failures=0`（含 1A 的 87 项） |
| `logs/` | 三次运行的 Gradle 输出 + 增量 diff 回放校验 |
| `SHA256SUMS` | 源码副本与 diff 的校验和 |
| `INDEX.md` | 本文件 |

`source/` 保留仓库内相对路径，可直接覆盖回工作区复跑，也可与 `core-rev1/source` 逐文件比对。

## 2. R1–R3 落点

### R1 写票不可重放 / 最新成功回调仍有效

rev1 用同一个 `sequence > savedSequence` 判两件事，于是**自己刚写成功**的回调在写入完成瞬间
就变成 `Stale`。现在拆成两个查询：

| 查询 | 含义 | 判定 |
|---|---|---|
| `WriteGate.replayability(ticket)` | 这张票还能不能拿去写 | `Replayable` / `Consumed` / `CrossedGeneration` / `Foreign` |
| `WriteGate.freshness(ticket)` | 这张票代表的写入是不是磁盘上最新的那次成功写入 | `epoch` 未变 **且** `sequence == savedSequence` → `Current`，否则 `Stale` |

- `write()` 走 `replayabilityLocked`：`Consumed` / `CrossedGeneration` → `Refusal.Superseded`，
  `Foreign` → `Refusal.ForeignTicket`（跨闸门在拿写锁之前就退掉，不再以「写正忙」的名义排队）。
- 没写过的票没有「成功回调」可言：`freshness` 为 `Stale`，可用性看 `replayability`。
- 三个阶段：自身最新成功（`Current` + `Consumed`）→ 更新保存完成（旧 `Stale`、新 `Current`）
  → 跨代（`epoch` 推进，无更新写入也 `Stale`）。

测试：`ImportTxnRev2GateTest` 4 例（`ownSuccessKeepsItsCallbackCurrentWhileReplayIsRefused`、
`newerCompletionRetiresThePreviousCallback`、`crossingGenerationRetiresCallbackWithoutANewerWrite`、
`replayabilityDistinguishesConsumedFromCrossGeneration`）。

### R2 判定用摘要保留三态

`TxnDisk` 新增 `FileRead` 与 `DigestRead`（`Absent` / `Unreadable` / `Present`）：

| 情形 | rev1 | rev2 |
|---|---|---|
| 读取抛 IOException | `null`（= 不存在） | `Unreadable` |
| 路径是目录占位 | `null`（= 不存在） | `Unreadable` |
| 确实没有这个文件 | `null` | `Absent` |

落点：

- `TxnClassifier` 的所有 notes 摘要比较先看 `Unreadable → Corrupt("notes-unreadable")`，
  不再落到 `prepared-but-notes-changed` / `credential-inconsistent` 这类「内容变了」的结论。
- `ImportTxnRollbackPath.planNotes` / `planAttachment` 在 `old == null` 的分支上**先判
  `Unreadable` 再判 `Absent`**：读不出来就 `StopRollback`，不写 `ABORTED`、不删证据、不放行保存。
- 副本读取与恢复后复核同样三态化：`old-notes-unreadable` / `old-attachment-unreadable:<name>` /
  `notes-restore-verify-unreadable` / `attachment-restore-verify-unreadable`。
- 回滚被分类拒绝时 detail 带上原因（`refuse-rollback-corrupt:notes-unreadable`），
  不再只说「corrupt」，否则读失败与真损坏在日志里分不开。
- `NotesRead` 并入 `FileRead`：同一套读法只有一个类型，避免两处各折叠一次。

测试：`ImportTxnRev2DigestTest` 7 例。核心两例是
`unreadableNotesWithNoOldCopyIsNotTreatedAsRestored`（rev1 会 `Restored` 并删证据）与
`unreadableNewAttachmentWithNoOldCopyStopsRollback`（rev1 会跳过该附件并宣称收敛）；
另有目录占位两例、分类原因两例，以及一个不注入故障的对照组 `healthySceneStillRollsBack`。

### R3 根身份与实际路径边界

新增 `TxnPathGuard`（110 行）：

| 检查 | 做法 |
|---|---|
| 根身份 | 首次自检时记下根的规范路径，之后必须一致；根本身是链接直接拒 |
| 布局内链接 | 从根往下逐层 `Os.lstat`，任一层是符号链接即整片判 `Unsafe` |
| 路径边界 | 每层规范父目录必须等于上一层，抓住「中间层被换成指向别处的目录」 |
| 范围 | 根 → `notes.json` / `attachments` / `.import`，以及 `.import` 下 4 层（含 txn 材料） |

判 `Unsafe` 的处置：`classify()` → `Corrupt("unsafe-layout:<reason>")`（`inspect` 得到它、
`recover` 停手、回滚被拒、`nextModeLocked()` 保持 `BLOCKED`）；`commit` / `materialize` →
`NotStarted` / `Rejected`；`cleanup` / `discard` → `stoppedAt="layout"`，一个字节都不动。

明确不做：不防御检查与使用之间的并发换链，也不解析挂载点——不是这批的范围。

测试：`ImportTxnRev2LayoutTest` 5 例，只用两个沙箱和一个哨兵文件：
`discardNeverFollowsSymlinkedTxnDir`、`cleanupNeverFollowsSymlinkedMaterialDir`、
`recoverNeverWritesThroughSymlinkedImportDir`、
`commitNeverWritesOutsideRootThroughSymlinkedImportDir`、
`symlinkedRootIsNotTreatedAsTheStorageRoot`。

## 3. 先红后绿

1. **先只加测试**（3 个 `ImportTxnRev2*` 套件 + 对 R1 错误断言的纠正），实现仍是 core-rev1，
   跑出 `tests=111 failures=17`（`test-results/red-on-core-rev1.xml`）：

   | 门 | 失败 | 用例 |
   |---|---|---|
   | R1 | 3 | `ownSuccessKeepsItsCallbackCurrentWhileReplayIsRefused`、`newerCompletionRetiresThePreviousCallback`、`crossingGenerationRetiresCallbackWithoutANewerWrite` |
   | R2 | 6 | `unreadableNotesWithNoOldCopyIsNotTreatedAsRestored`、`unreadableNewAttachmentWithNoOldCopyStopsRollback`、`directoryPlaceholderForNewAttachmentIsNotAbsent`、`directoryPlaceholderForNotesIsNotAbsent`、`unreadableNotesWithOldCopyReportsUnreadableNotMissing`、`classifierReportsUnreadableNotesNotChangedNotes` |
   | R3 | 5 | 上面 5 例全部失败（rev1 分别返回 `cleaned=true`、改写根外 journal、`Ready`、把链接根当正常根） |
   | 纠正断言 | 3 | `replayIsRefusedAsStaleNotBusy`、`olderTicketBecomesStaleAfterANewerWrite`、`ticketIssuedAfterReopenIsWritable`——它们编码的正是 rev1 的错误语义，所以纠正后同样先红 |

2. **再改实现**（本目录源码）。
3. 定向 `tests=112 failures=0 errors=0 skipped=0`（`test-results/targeted-txn.xml`）。
4. 全量 `tests=199 failures=0 errors=0 skipped=0`（`test-results/full-androidtest.xml`，
   含 1A 的 87 项）。设备显式 `emulator-5554`（API 37），未操作真机。

新增 16 例分布：`ImportTxnRev2DigestTest` 7、`ImportTxnRev2LayoutTest` 5、
`ImportTxnRev2GateTest` 4。事务包从 96 例增到 112 例；数量只作记录，不作为验收目标。

**红基线只覆盖能编译在 rev1 上的断言。** `replayabilityDistinguishesConsumedFromCrossGeneration`
依赖 rev2 才有的 `replayability()`，无法在 rev1 上编译，因此不在 17 条红基线里——它是在实现
落地后补的，作用是把「用过了」与「跨代了」两种拒绝原因钉住，不替代上面三条 `freshness` 红例。

## 4. 对既有断言的修正（单独说明）

R1 允许纠正原本错误的 freshness 断言。共 **3 处**，全部是「把 rev1 的错误语义当成了预期」，
不是放宽强度（修正后仍是等值断言，且新增了 `replayability` 侧的正向断言）：

| 位置 | rev1 断言 | rev2 断言 | 原因 |
|---|---|---|---|
| `ImportTxnRev1GateTest.replayIsRefusedAsStaleNotBusy` | 重放被拒后 `freshness == Stale` | `freshness == Current` | 那张票刚写成功、且没有更新的写入；重放被拒是 `replayability` 的事，不能顺带把回调判死 |
| `WriteGateTest.olderTicketBecomesStaleAfterANewerWrite` | 第一次写完就 `Stale` | 第一次写完 `Current`，第二次写完才 `Stale` | 同上；用例名要的本来就是「更新之后」才失效 |
| `WriteGateTest.ticketIssuedAfterReopenIsCurrent` → `ticketIssuedAfterReopenIsWritable` | 未写入就断言 `Current` | 先断言能写，写完后 `Current` | 没写过的票没有「成功回调」，原断言把写资格与回调新鲜度混在一起 |

除以上 3 处，core-rev0 / core-rev1 的其余测试**未改一个字节**；没有删除用例，也没有放宽
任何 `assert`。旧测试文件共 13 个，其中 10 个逐字节未改（`diff/core-rev2.diff` 可核：只出现
3 个旧测试文件 + 3 个新测试文件）。

另外两处非断言改动，供审阅时对照：`TxnTestSupport` 增加了读取故障注入
（`readFailAfter` / `failReadOf`）与 `symlinkTo` 两个测试辅助；`FaultyOps` 只多了这两个钩子，
生产代码里没有任何测试分支。

## 5. 掉电保证（收窄后）

本批不重写协议，只把索引里的口径收紧到实现真正做到的：

- **只承诺「重启后能判定」**：掉电后磁盘最多落在 `Interrupted`（journal 是 `APPLYING` 且无终态
  证据）、`Quarantined`（有材料无证据）、`Corrupt`（读不出或自相矛盾）三种可判定脏态之一。
- **不承诺自动收敛**：`Quarantined` 与 `Corrupt` 只停手并保留材料，必须显式处置；`RollbackFailed`
  需要再次 `recover()` 尝试，成功与否取决于副本是否还在。
- **原子性只覆盖单文件**：`temp → 写入 → fsync → rename`，父目录项未 fsync。掉电可能丢掉
  rename 本身，此时回落到 journal 仍是 `APPLYING` 的 `Interrupted`，按回滚处理——不会出现
  「notes 换了但没有任何证据」。
- **提交点不是原子的两步**：`notes.json` 替换与凭据写入之间掉电，分类为 `Interrupted` 并回滚。
  这是有意选择：宁可回到旧数据，也不接受「数据换了但无法证明」。
- **不覆盖**：并发换链、外部进程改写文件、介质谎报 `fsync`、以及任何形式的恶意布局变更。
  R3 只保证「已经摆在盘上的链接不会把删改带到根外」。

## 6. 本批明确没做的事

- 不接 `NotesRepository` / `NotesState` / `MainActivity` / `ReminderReceiver`；
- 不接 prepare 物化、共享 `NotesStore`、UI、媒体回调与提醒读取（协议 §6.6 的 G1–G4）；
- 不改 `PROTOCOL.md`——本轮不写长协议报告，R1–R3 的规则只落在代码与本文第 2 节；
- 不改依赖、不删旧导入、不操作真机、不合并 `main`、不建 PR。

## 7. 已知限制

- 注入的仍是**代码级 `IOException`**，不等于真实磁盘故障或掉电；掉电承诺见第 5 节。
- `TxnPathGuard` 的扫描范围是布局 + `.import` 下 4 层，不是整棵树：`attachments/` 的**子项**
  不逐层检查。理由是对单个附件文件的写入走 `rename`（替换链接本身）、删除走 `unlink`，
  不会顺着链接改到根外；真正危险的是目录级 `deleteRecursively`，它落在被检查的范围内。
- 布局自检在每次 `classify()` 都跑一遍，代价是若干 `lstat`；这些文件数量由事务布局决定，
  与附件总量无关。
- `freshness` 不再对「未写入的票」返回 `Current`；如果接线批需要「这张票还能不能写」，
  必须用 `replayability()`，不能拿 `freshness` 代替。
