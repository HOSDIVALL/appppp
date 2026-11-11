# 转发：SAVE-ORDER-2 第六批（源码固化与证据归档）

生成时间：2026-11-11 15:40 (GMT+8)

## 这一批做了什么

只做两件事，**没有新增功能、没有改业务逻辑、没有跑测试、没有任何设备操作**：

1. 把已通过测试的那份工作树**固化成 1 个本地提交**，提交里恰好 6 个路径。
2. 把上一轮遗留的**文档口径**问题写进独立文档（五处措辞收窄），**不改历史证据文件一个字节**。

## 提交

| 项 | 值 |
| --- | --- |
| 提交 | `d252ed3c2a9780c305b7a1be515186695a8e9c72` |
| 父提交 | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269` |
| 说明 | `test: verify note storage failures with real file operations` |
| 统计 | `6 files changed, 802 insertions(+), 6 deletions(-)` |
| 分支 | `fix/backup-safe-1`（本地） |

六个路径的 blob（worktree / index / HEAD 三方相同）：

| 文件 | blob |
| --- | --- |
| `data/NotesFileOps.kt` | `002c814c4d5e7f2add6007d39ff1f087a74f0aa2` |
| `data/NotesRepository.kt` | `df3792064346a364d6fb8bd5dd2851faf3905d71` |
| `data/IsolatedNoteStorage.kt` | `890b6275af9fad5dd4dc9ec1e6690b51e58b9639` |
| `data/NotesRepositoryFileTest.kt` | `d31c6d51fa974910eedfc5f4083dfdbd3f9bd767` |
| `data/NotesWriteCoordinatorFileTest.kt` | `78c715166ecb6ee1c0e955454417d1e23b548066` |
| `data/NotesRepositoryFailureTest.kt` | `1e5242fe05a1486888b109751fe2fd0cdab59ca6` |

`NotesRepository.kt` 的 blob 与旧 HEAD 的 `fdcd1d1c…` 不同是**预期的**——接缝（+13 −6）这一批才提交。

## 推送状态：**仍未上远端**

- 本轮又试了一次普通推送，`git push origin fix/backup-safe-1:fix/backup-safe-1` 依旧
  `fatal: unable to access ...: CONNECT tunnel failed, response 502`（15:36 左右）。
- 因此远端 `fix/backup-safe-1` **仍是 `26a5cd96…`**，不含 `d252ed3`。
- 没有 force、没有换 remote、没有推 main、没有建 PR、没有 rebase/merge。
- **源码分支的远端引用没有被 Git Data API 改写**（按约定不做）。
  本文件所在的 `review/save-order-2-source-freeze` 分支只承载**文档与证据**，不承载源码提交。

远端只读核对（未写入任何引用）：

| 分支 | SHA |
| --- | --- |
| `main` | `61dff28e4041aa5a8d9376ba1da872a87a009301` |
| `fix/backup-safe-1` | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269` |
| `review/save-order-2-closeout` | `90349dcbd86c9f2fac7db817890d26a74f548814` |

## 请重点看

1. **本批报告 `SOURCE-FREEZE.md`**：核验原样输出、blob 三方核对表、暂存范围、提交信息、
   推送失败逐次记录、与已测内容一致性（**明确写了本轮未运行任何测试**）、
   五处口径修正、连续反汇编片段、未执行/失败/计划外。
2. **`DOC-CORRECTIONS.diff`**：11 行**语句级**对照（`-` 历史原文摘句 / `+` 采纳口径），
   **不是**可 apply 的文件 patch——历史文件没被改过，所以不存在文件 diff。
3. **`logs/main-apk-dex-contiguous-fragment.txt`**：`0x003f`–`0x00a1` 的**连续**反汇编，
   含 `move-result-object`。这是本轮**重新本地反汇编**的结果，不是上一轮的历史原始输出
   （上一轮的中间文件已被删除，旧摘录不含 `move-result-object`，无法冒充连续片段）。

## 请重点质疑

1. 提交内容"与测试对象一致"的依据是 **blob 相等 + 提交前 `git status` 无其他改动**，
   **不是**在新提交上重跑测试。表述只能是"证据引用原轮次"。
2. 连续反汇编仍是**本地留存 APK**的证据，**没有**做设备端 APK 哈希比对。
3. 五处口径修正是**文档层**的，历史报告 `REPORT-CLOSEOUT.md` / `REPORT-REALDEVICE.md`
   正文**保持原样**，所以两份材料读起来会不一致——以 `SOURCE-FREEZE.md` 第 7 节为准。
4. 8 条历史缺口（原始 `sha256sum` 输出未落盘、`files.tar` 未演练、原 APK 未留存、
   授权无书面记录、设备端未比对哈希、真机无 JUnit XML 等）**全部保留，未补造**。
5. 源码尚未推送到 `fix/backup-safe-1`，所以现在**远端拿不到** `d252ed3` 这个提交本身，
   只能看到它的文档说明。这是网络传输层问题，不是远端状态不符。

## 未做（等审阅方下发）

- 没有把 `d252ed3` 推到远端（网络 502）。
- 没有合并 main、没有建 PR。
- **没有**提前实现"备份导入保护"，等下一批任务。
