# BACKUP-SAFE-1A rev2 整改说明

相对 **rev1** 的增量，只改准备与验证层。未接线、未提交、未推送、未进入 1B。
基线仍是 `d252ed3c2a9780c305b7a1be515186695a8e9c72`；rev2 = d252ed3 + rev0 + rev1 + 本补丁。

## 一、五项整改

### 1. 取消「原始文本直接写盘」，给出确定性的提交载荷

- `PreparedBackup` 新增 `commitJson: String` / `commitDigest: String`，并新增 `commitBytes()`。
- `notesJson` 的职责收窄为**诊断与差异对比，禁止写盘**：它可能带 `"deletedAt": null`，
  而 `noteFromJson` 把「有键且为 null」读成 0，写回去等于把笔记标成「1970 年删除」。
- 提交载荷由 `buildCommitPayload(array, notes)` 生成，逐条 `mergeNote`：
  原始对象**整份先拷进来**（未知字段因此保留），再把已知字段覆盖成归一值；
  `deletedAt` / `remindAt` 为 null 时**删除键**而不是写 null；`audio` / `checklist` / `todos`
  为空时同样不写键，与 `Note.toJson` 的取舍一致。
- 序列化改走新增的 `CanonicalJson`：对象键按字典序、数组保序，不再依赖
  `JSONObject.toString()` 背后的 `HashMap` 迭代顺序。
- 归一函数 `normalizeImportedNote` 从 private 提到包级，供测试用**同一条规则**回读载荷。

边界（写进 KDoc 也写进测试注释）：确定性成立的前提是输入自带 `createdAt` / `updatedAt`。
两者缺失时 `noteFromJson` 按读取时刻补齐，那是既有读取语义，本层不另造一套。

### 2. `discard` 补真实根目录边界

`ownedStagingDir` 四道检查，token 格式只用来排除明显拼接，**不当作所有权证明**：

1. token 匹配 UUID 形式；
2. 暂存根的**规范路径**必须严格位于 `filesDir` 之内（等于 `filesDir` 也不算）；
3. 预期目录解析符号链接后，仍必须是根的**直接子项**、名字未被改（拦「根里放一个指向根外的链接」）；
4. 传入路径与预期目录必须 `canonicalFile` 相同（拦路径替换）。

任一不成立返回 `false` 且不删任何东西；目录已不存在返回 `true`（幂等）。

### 3. 清理与主动 discard 的主线程安全

- `cleanup` / `discard` 改为 `suspend`，删除段统一 `withContext(NonCancellable + Dispatchers.IO)`：
  调用方在主线程也不会做磁盘 IO；调用方被取消时清理仍做完。
- 不可取消段**只包住删除本身**，准入与结果构造仍在可取消路径上。
- 取消路径原样 `throw cancelled`，残留只记日志；拒绝路径把残留放进 `Invalid.residue`。

### 4. 跨调度器的交付窗口测试

新增 `deliveryWindowCancellationAcrossDispatchers`：调用方跑在 `PausableDispatcher`（dispatch 只入队），
块跑在 IO，取消从测试线程发出。顺序是「启动协程 → 块执行完、交付续体入队 → 取消 → 才执行交付续体」，
这才真正踩到 `withContext` 的 resume 检查；原 `cancellingAfterResultIsBuiltStillCleansStaging` 保留不动，
它验的是块**内部**取消，是另一条路径。

取消实例的表述与断言已对齐：用 `job.invokeOnCompletion` 取 Job 的取消原因，再 `assertSame` 与实际抛出的
`CancellationException` 比较（`cleanupFailureDoesNotReplaceCancellation` 也加了这一条）。

### 5. UTF-8 用例

- 坏字节改到**合法笔记对象的字段值里**（`title`），结构完全正确，只有编码坏；
  rev1 把坏字节放在结构位置上，那种包宽松解码后 JSON 也会解析失败，判不出是编码问题。
- 合法 Unicode 用例加入 U+FFFD（`EF BF BD`）：它是合法字符，必须原样收下，
  并补断言从提交载荷读回来仍保留。

## 二、契约变化（1B 需知）

| 项 | 变化 |
|---|---|
| `PreparedBackup` | 新增 `commitJson`、`commitDigest`、`commitBytes()` |
| `notesJson` | 语义收窄：诊断用，**禁止写盘** |
| `discard` | `fun` → `suspend fun`（内部切 IO + NonCancellable） |
| `notesDigest` | 只证明「暂存文件没被替换」，不参与提交判定 |
| 归一函数 | `normalizeImportedNote` 提为包级 internal |
| 新增类型 | `CanonicalJson`（确定性序列化） |

无语义变更的拒绝原因、限额、告警策略均未改动；`InvalidReason` 仍是 21 项。

## 三、测试

模拟器固定 `Medium_Phone_API_37.0`（emulator-5554，API 37），未操作真机。判据取 XML 计数。

| 轮次 | tests | failures | errors | skipped |
|---|---|---|---|---|
| rev1 | 80 | 0 | 0 | 0 |
| rev2 针对性（Prepare + Validation + Cancel） | 57 | 0 | 0 | 0 |
| rev2 全量 | 87 | 0 | 0 | 0 |

分布：Prepare 21→27、Cancel 5→6、Validation 24（条数不变，三条用例改写）、Limits 9、其余既有 21 条。
新增 7 条：`commitPayloadKeepsUnknownFieldsAndDropsNullTimestamps`、`commitPayloadIsByteStableAcrossRuns`、
`discardRejectsWellFormedTokenPointingElsewhere`、`discardRejectsSymlinkedStagingDirectory`、
`discardRefusesStagingFromAnotherStorageRoot`、`discardDeletesOnIoThreadNotOnCaller`、
`deliveryWindowCancellationAcrossDispatchers`。

过程中两条真实失败，都是改了实现/用例而不是放宽断言：

1. `commitPayloadIsByteStableAcrossRuns` 首轮红：`updatedAt` 缺失的笔记按读取时刻补齐，两次载荷不同。
   → 契约里显式写出这条边界，用例输入补齐时间戳，不假装它可复现。
2. 跨调度器用例首轮**挂住**（3 分钟无输出）：取消后排进不止一个续体，只泵一次队列导致 `join()` 永不返回。
   → 改成有界泵空 + `withTimeout(10_000) { join() }`，挂住会变成失败而不是挂死。

## 四、补丁与复验

```
BACKUP-SAFE-1A-rev2.diff   6 个文件 +545 −48，852 行
sha256 c258592c2938356aa5f9a0768fba0fa9b14b971255145169d2bb4db4645dbc34
```

未改的三个文件：`BackupFileOps.kt`、`BackupNotesParser.kt`、`BackupImporterLimitsTest.kt`（blob 未变）。

应用顺序（rev2 是增量，不能单独打在 `d252ed3` 上）：

```
git archive d252ed3 | tar -x
patch -p1 < rev0/BACKUP-SAFE-1A.diff
patch -p1 < rev1/BACKUP-SAFE-1A-rev1.diff
patch -p1 < rev2/BACKUP-SAFE-1A-rev2.diff
```

复验：以 rev1 的九文件为底，`patch -p1 --fuzz=0` 后九个文件 sha256 与工作区 **9/9 一致**。
`git apply` 需加 `-c core.autocrlf=false`，否则产物变 CRLF。

## 五、rev2 九文件（供比对）

| 文件 | blob(12) | sha256 |
|---|---|---|
| BackupFileOps.kt | 5e4b82c09559 | 9042231d23691d0aca78b24a01a520987b3a4cb4de3bd057ae449949c526575e |
| BackupImportModels.kt | f9073a141784 | 736524225d27b9cfab54da6d4b540b5116722db33ea179b5c8a2f060584f07d7 |
| BackupImporter.kt | f93e98e890bb | 4ebe4e795fbc292dc7b262d10f4f1d1c9f4845f78713de1604809b44f4cc98a5 |
| BackupNotesParser.kt | 002aef08750d | 87f46e04c962b332865a72e4572198425dba57fa177436bce35db6379926c3ea |
| ImportTestSupport.kt | 2d0582ef1147 | 957b8489015d2b8b3c11e89dc48a4530bb49bcd8246a66e1ff26b11451f486e4 |
| BackupImporterPrepareTest.kt | cb59862d7e84 | b64441c89f17d598f87034113314da75ae34fd20f3f38a21f5dd285425a22c95 |
| BackupImporterValidationTest.kt | ce223ed2fe4f | 67cb6bd1fe36a874b9f8863ffe38f57695e46f8ca7eb194f84a879630e6be2e2 |
| BackupImporterLimitsTest.kt | 84e2327011e2 | d86d0271fecf1c62ad6ce1b11f5bde9da5d59f2d57527b0f1d7590c608716094 |
| BackupImporterCancelTest.kt | cdac4d234576 | 6a392f1af0274a2fcb4ee22c243c5ab6be449901c4de0a99e28854ec647748e6 |

## 六、状态

`BackupImporter` / `PreparedBackup` / `PrepareResult` 在 `app/src/main` 中仍无生产调用方；
`NotesRepository`、`NotesState`、`MainActivity`、`NoteEditorScreen` 一行未改。
rev1 及更早证据（`rev0/`、`rev1/`、`logs/full-androidtest.xml`、`logs/compile.log`）在本分支原样保留，本轮材料在 `rev2/` 与 `logs/rev2/`。
