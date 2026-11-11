# BACKUP-SAFE-1B / core-rev3

`core-rev2` 复审结论：R1、R2 接受，不重开；R3 还有两处未收口——**有限检查、无限删除**，
以及**安全检查失败被放行**。本轮只修这两处，不固化、不接线、不重写长协议。

- 源码基线：`core-rev2`（`backup-safe-1b/core-rev2/source`）
- 实现工作区：`D:/SAVE-ORDER-1-wt`，基线提交 `26a0de3`，**仍未提交**
- 设备：`emulator-5554`（`Medium_Phone_API_37.0`，API 37），未操作真机

## 1. 目录

```
backup-safe-1b/core-rev3/
├── INDEX.md                 本文件
├── SHA256SUMS               源码副本与 diff 的校验和（32 条）
├── source/                  完整源码副本，保留仓库内相对路径
│   └── app/src/{main,androidTest}/…/data/txn/*.kt   （14 生产 + 17 测试）
├── diff/core-rev3.diff       相对 core-rev2 的增量 diff（8 文件）
├── test-results/
│   ├── red-on-core-rev2.xml  新回归在 core-rev2 上：117 / 4 失败
│   ├── targeted-txn.xml      事务包定向：117 / 0 / 0 / 0
│   └── full-androidtest.xml  全量：204 / 0 / 0 / 0（含 1A 87 项）
└── logs/
    ├── red-on-core-rev2.log / targeted-txn.log / full-androidtest.log
    └── replay-check.log      core-rev2/source + patch -p1 --fuzz=0 → 31 文件 sha256 全一致
```

## 2. 先红后绿

新增 5 个用例（`ImportTxnRev3LayoutTest`）时**实现未动**，仍是 core-rev2：

| 用例 | core-rev2 实际结果 |
|---|---|
| `discardStopsAtUnknownSubtreeBeyondScanDepth` | `cleaned=true`，根外哨兵被删 |
| `cleanupStopsAtUnknownSubtreeAndKeepsEvidence` | `cleaned=true`，哨兵、journal、凭据全被删 |
| `discardStopsWhenTxnSubtreeCannotBeEnumerated` | `cleaned=true`，枚举不了的目录被当成空的 |
| `cleanupStopsWhenLayoutCannotBeChecked` | `cleaned=true`，检查不出来照删 |
| `healthyLayoutStillDiscardsAndCleans` | 通过（收严前就应通过，作为“不许变成永远清不掉”的对照） |

红基线 `tests=117 failures=4`；改实现后定向 `117/0/0/0`，全量 `204/0/0/0`。

`discardStops*` 两例里“枚举不了”是**真实权限现场**（目录权限收成 `000`，`listFiles()` 返回
`null`），不是注入的代码级异常；测试同时断言“现场确实施加成功”，否则用例自身失败，
不会静默通过。

## 3. 修复落点

**3.1 检查范围 = 删除范围**

`TxnPathGuard` 改成显式形状遍历，只认 `.import/` 下已知的三层：

| 层 | 允许的东西 |
|---|---|
| `.import/` | `journal.json`、`committed.json`、`results.json`（普通文件）；`txn-<安全名>/`（目录） |
| `txn-<id>/` | `commit.json`、`old-notes.json`、`old-manifest.json`（普通文件）；`old-attachments/`、`new-attachments/`（目录） |
| 材料目录 | **只允许平铺普通文件**，出现任何目录即整片判不安全 |

布局外的东西一律 `unknown-entry:<相对路径>`，不是“没看见”。`writeAtomic` 的残留临时文件
（`.<名字>-<随机>.tmp`，普通文件、单文件删除）显式放行，避免崩溃残留把清理永久卡死。
根目录下的其他应用数据（录音等）不扫描，也不在任何删除路径上。

删除侧把 `TxnFileOps.deleteTree`（`File.deleteRecursively()`）**整个删掉**，换成
`TxnSafeDelete.deleteVerified(target, maxDepth)`：逐层 `entryKind` 看实际类型，是链接、
判不出来、枚举不出来都整片返回 `false`；深度上限之外不删；只 `rmdir` 空目录。
`cleanup` / `discardQuarantined` / `materialize` 失败清场全部走它。生产代码里现在
没有任何无界递归删除。

**3.2 检查失败必须停手**

`TxnFileOps` 新增两个查询，与旧的“失败即空”分开：

- `listStrict(dir)`：枚举不出来抛 `IOException`（`list()` 保持原样，只在不影响判定的地方用）；
- `entryKind(file)`：五态 `Regular / Directory / Symlink / Absent / Unknown`。只有
  `ENOENT`、`ENOTDIR` 才算 `Absent`；`EACCES`、`ELOOP`、`EIO` 一律 `Unknown`。

守卫侧：枚举失败 → `Unsafe("unlistable:…")`；`lstat` 判不出来 → `Unsafe("undecidable:…")`；
列完之后条目又不见了 → `Unsafe("vanished:…")`。`cleanup` 返回 `stoppedAt="layout"`，
一个字节都不动；`materialize` / `commit` 返回 `Rejected` / `NotStarted`。

`notes.json` 的具体类型**不在守卫里判**：目录占位仍由 `TxnDisk` 判成 `Unreadable`
（`Corrupt("notes-unreadable")`），保持 R2 的语义，不被降级成“布局越界”。

## 4. 掉电保证：本轮直接撤回

上一轮索引里的掉电表述一并作废，**本节为准**。下面四条都**不承诺**：

1. **不承诺完整掉电耐久性**。原子写只有单文件的 `temp → fsync → rename`，父目录项没有
   `fsync`，掉电可能留下“文件内容对、目录项没落盘”的状态。
2. **不保证所有不一致都能被检测**。判定只覆盖 `journal` / 凭据 / `notes.json` / 附件摘要
   这几处，布局外或摘要之外的偏差不保证发现。
3. **不保证一定留下 journal 或凭据**。清理阶段删掉证据之后掉电，可能只剩正式数据，
   也回不到“可判定的中断态”。
4. **不保证一定能回滚**。需要的旧副本缺失或损坏时，回滚停在 `RollbackFailed` 并保留材料，
   等人工处理，不自愈。

因此对外只能说：**正常路径**上写入不会把数据留在半截，异常路径上尽量停在“可判定状态”；
不把“重启后一定收敛”当承诺。本轮不新增目录 `fsync`，也不做真实掉电测试。

## 5. 保留与未改

- `core-rev0` / `core-rev1` / `core-rev2` 三个目录与 `PROTOCOL.md` **零改动**。
- core-rev2 的 16 个测试文件全部保留且**逐字节未改**，没有删除用例、没有放宽断言；
  本轮只新增 1 个测试文件 `ImportTxnRev3LayoutTest`（5 例）。
- 未改业务源码、未接 `prepare` / `NotesStore` / 主界面 / 提醒接收器，未改依赖。

## 6. 已知限制

- 只处理“已经摆在盘上的东西”。检查与使用之间被换掉（并发换链）**不在本批范围**，
  `TxnSafeDelete` 的逐层 `entryKind` 只是把窗口压小，不构成防御。
- 布局外的条目会让整个事务进入 `Corrupt("unsafe-layout:…")` 并**持续停写**，
  需要人工确认后处置——这是刻意的取舍，优先“不误删”而不是“总能自动清干净”。
- 陌生条目不再自动删除：`discardQuarantined` 也会拒绝，只能人工清理。
