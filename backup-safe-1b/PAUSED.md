# BACKUP-SAFE-1B 暂停说明

**状态：PAUSED（主动暂停，非完成，非放弃）**
**暂停时间：2026-11-12**
**截止证据：`35784c79d2c3c52fb28ab7419a9244ccf9c893d5`**

## 1. 为什么暂停

用户当前**没有导入/导出需求**，主动暂停本条工作线，把优先级切到 DAILY-UX-1（新建/编辑笔记体验）。

因此本批立即停止以下全部动作：

- 不再修改导入/导出相关的任何源码；
- 不补测（上一条 `core-rev3-supplement` 补测任务已撤回，见 §4）；
- 不固化、不接线、不合并、不建 PR。

这**不代表** BACKUP-SAFE-1B 已完成。`core-rev3` 已送复审并通过——R3 余下两项收口为
「检查范围 = 删除范围」「安全检查失败即停手」，实现修复被接受；但它的**补充测试没有做**，
且补测任务已随本次暂停撤回（见 §4.2）。所以本线整体仍处在暂停状态：**未固化、未接线、未合并**。

它停在一个"可以随时捡起来"的位置：证据齐全、源码副本可还原、代码从未进入产品路径。

## 2. 截止时的仓库事实

| 项 | 值 |
|---|---|
| 证据分支 | `review/backup-safe-1a-evidence` |
| 证据 head（本地 = 远端） | `35784c79d2c3c52fb28ab7419a9244ccf9c893d5`（暂停时刻；其后本分支另有 `23e27b8` 归档提交与 DAILY-UX-1 证据） |
| 证据 head 提交 | `core-rev3`（父 `cb8b678`） |
| 实现工作区 | `D:/SAVE-ORDER-1-wt`，分支 `fix/backup-safe-1` |
| 实现基线提交 | `26a0de3cc80636718059c118d5af6e4c6f179fc0` |
| `fix/backup-safe-1` | `26a0de3`（**没有被更新过**） |
| `main` | `61dff28e4041aa5a8d9376ba1da872a87a009301`（无改动） |
| 未提交内容 | 仅两个未跟踪目录 `data/txn/`（main + androidTest）与本地 `logs/` |

## 3. 已有材料（恢复入口）

### 3.1 索引与协议

| 路径 | 内容 |
|---|---|
| `backup-safe-1b/PAUSED.md` | 本文件 |
| `backup-safe-1b/PROTOCOL.md` | 1B 协议 rev2 正文，最后修订提交 `e171dba`（core-rev0 入库前），本轮未动 |
| `backup-safe-1b/core-rev0/INDEX.md` | 隔离事务内核首版：范围、协议落点、11 条反例对照 |
| `backup-safe-1b/core-rev1/INDEX.md` | B1–B6 修订；含 `ImportTxnKernel` 拆分说明 |
| `backup-safe-1b/core-rev2/INDEX.md` | R1–R3 修订；含纠正的 3 处 freshness 断言对照 |
| `backup-safe-1b/core-rev3/INDEX.md` | R3 收口：检查范围 = 删除范围、检查失败即停；含撤回掉电强保证的说明 |

### 3.2 源码副本

每轮 `core-revN/source/` 都是一份**完整源码副本**，保留仓库内相对路径
（`app/src/main/…/data/txn/*.kt`、`app/src/androidTest/…/data/txn/*.kt`），
配套 `SHA256SUMS`（31 条：31 个 `.kt`）与 `diff/core-revN.diff`（相对上一轮的增量）。

- `core-rev3/source/` = 截止时刻的完整实现，**14 生产 + 17 测试 = 31 文件**
- `core-rev3/SHA256SUMS` 32 条（31 个源文件 + 1 个 diff）
- `core-rev3/diff/core-rev3.diff` 相对 `core-rev2/source` 增量（6 修改 + 2 新增）
- 已核：本地工作区 31 个 `.kt` 与 `core-rev3/source` **逐字节相同**（MATCH=31 / DIFF=0 / MISSING=0）

### 3.3 测试记录

| 轮次 | 红基线 | 定向 | 全量 |
|---|---|---|---|
| core-rev1 | 96 / 28 失败（在 core-rev0 上） | 96 / 0 / 0 / 0 | 183 / 0 / 0 / 0 |
| core-rev2 | 111 / 17 失败（在 core-rev1 上） | 112 / 0 / 0 / 0 | 199 / 0 / 0 / 0 |
| core-rev3 | 117 / 4 失败（在 core-rev2 上） | 117 / 0 / 0 / 0 | 204 / 0 / 0 / 0 |

设备一律显式 `emulator-5554`（`Medium_Phone_API_37.0`，API 37），**未操作真机**。
全量含 1A 原有的 87 项。

每轮 `logs/` 内另有 `replay-check.log`：从上一轮 `source/` 出发 `patch -p1 --fuzz=0`
回放增量 diff，再逐文件 sha256 比对，结果为全一致。这条保证「diff 与 source 副本
指向同一份代码」。

## 4. 评审状态与未完成项（如实登记）

### 4.1 已经过评审的轮次

| 轮次 | 范围 | 评审结论 |
|---|---|---|
| core-rev0 | 隔离事务内核 + 61 例测试 | 首轮代码评审未通过，提出 B1–B6 → rev1 |
| core-rev1 | B1–B6 | B1/B2/B5/B6 接受；B3/B4 有 R1–R3 |
| core-rev2 | R1–R3 | R1/R2 接受；R3 余两项（有限检查无限删除、检查失败仍放行） |
| core-rev3 | R3 收口 | **复审通过**：两项收口被接受；补充测试未完成（任务已撤回） |

> 口径更正（2026-11-12）：本节早期版本把 rev3 记为「尚未送审、未获结论」，与复审结果
> 不符，已按实际状态改写。

`core-rev3` 已经过复审，实现修复被接受。但这个「通过」的边界要说清楚：它只覆盖 R3 的
两项收口，**补充测试没有做**，补测任务也已撤回。因此复审通过**不等于**已固化，更不等于
已接线——本线仍暂停，`fix/backup-safe-1` 仍停 `26a0de3`。

### 4.2 已被撤回的补充测试任务（`core-rev3-supplement`）

上一条指令要求为 rev3 追加补充测试。该任务**在暂停通知到达时尚未开始**，
并且随后被**正式撤回**（不会自行恢复）：

- 没有新建测试文件；
- 没有跑任何测试；
- 没有产生 `core-rev3-supplement/` 目录或任何中间产物。

因此**不存在需要清理的半成品**，也不存在"补测通过"的记录可引用。
若日后恢复，补充测试需要从零开始，不能假定已覆盖。

### 4.3 明确未做的事

- 未固化：`fix/backup-safe-1` 仍停 `26a0de3`，一个提交都没加。
- 未接线：`NotesRepository` / `NotesState` / `MainActivity` / `ReminderReceiver`
  与生产 `prepare` 物化流程**均未接入**新内核。
- 未改业务源码、未改依赖、未删除旧导入。
- 未合并 `main`、未建 PR、未上传真实数据 / APK / AAR / 配置。

协议里登记的四道接线门禁（G1 提醒读取、G2 媒体回调、G3 prepare 物化、
G4 结果消费）在代码中**只以 KDoc 与测试断言存在**，没有生产接线。

## 5. 本地未提交源码 ↔ 远端副本的对应关系

本地 `D:/SAVE-ORDER-1-wt` 的 31 个 `.kt` 全部是**未跟踪文件**（untracked），
不在任何提交里。它们与远端的对应关系是：

```
本地工作区 app/src/{main,androidTest}/…/data/txn/*.kt   (31 个，untracked)
        ⇅  逐字节相同（已核对 MATCH=31 / DIFF=0 / MISSING=0）
远端 backup-safe-1b/core-rev3/source/…                  (31 个，已提交)
```

也就是说：**远端副本就是本地未提交代码的完整备份**，靠 `SHA256SUMS` 可验证。
`core-rev3.diff` 从 `core-rev2/source` 回放能精确重建同一份代码（`replay-check.log` 为证）。

### 恢复步骤

```bash
# 1. 取证据分支
git fetch origin review/backup-safe-1a-evidence
git checkout 35784c79d2c3c52fb28ab7419a9244ccf9c893d5

# 2. 校验副本完整性（在 backup-safe-1b/core-rev3/ 下）
sha256sum -c SHA256SUMS

# 3a. 直接用完整副本覆盖回实现仓库
cp -r backup-safe-1b/core-rev3/source/app /path/to/impl-worktree/

# 3b. 或从上一轮回放增量（等价，可交叉验证）
cd /path/to/impl-worktree
patch -p1 --fuzz=0 < backup-safe-1b/core-rev3/diff/core-rev3.diff

# 4. 验证 31 个文件与 SHA256SUMS 一致后，跑定向测试
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.example.myapplicationfirstapp.data.txn
```

恢复后的预期：定向 117 项全绿（全量 204 项全绿），与 §3.3 一致。

## 6. 暂停通知到达前是否有新改动

**没有。** 截止提交 `35784c7`（2026-11-12 04:37:57 +0800）之后，实现工作区未再产生
任何源码改动：

- 31 个 `.kt` 与 `core-rev3/source` 逐字节一致；
- `logs/` 中最新文件为 `rev3-full.*`（04:36），早于截止提交；
- 工作区 `git status` 只有两个 untracked 的 `data/txn/` 目录与 `logs/`。

因此本暂停说明的"截止证据"与实际代码状态一致，没有需要额外登记的漂移。
**本地 `logs/` 按指示保留，未删除、未清理。**

## 7. 恢复时的注意事项

1. **不要**把 rev3 的「复审通过」当成整体验收：通过的只有 R3 两项收口，补充测试
   未做且任务已撤回。恢复本线要重新下令补测，不能假定已覆盖。
2. **不要**把 rev0–rev2 的材料当成过期废弃物删掉：每轮 diff 是链式的，
   删掉中间一环就无法用 `patch` 回放验证。
3. 恢复后先跑 `sha256sum -c` 再跑测试，先确认副本没坏，再谈行为。
4. 接线（G1–G4）是从未开始的工作，不是"做了一半"。
5. 掉电保证已在 `core-rev3/INDEX.md` §4 撤回为四条「不承诺」，恢复时不要
   按早期文档的强保证去设计用例，否则会把已知限制当成回归。
