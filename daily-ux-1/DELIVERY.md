# DAILY-UX-1 固化交付记录

固化日期：2026-11-12。本文件只登记「已交付的事实」，不重述设计与测试过程
（过程见 `INDEX.md`）。

## 1. 源码提交

| 项 | 值 |
|---|---|
| 分支 | `ux/daily-ux-1` |
| 提交 | `ef4d98cef923247e2b4fc8bfe5d974d188272a02` |
| 父提交 | `26a0de3cc80636718059c118d5af6e4c6f179fc0` |
| 改动 | 3 个文件，+56 −2 |
| 提交信息 | `fix(ui): 新建笔记后正文自动获得焦点并弹出键盘` |

提交内容**只有**这三个文件。以下内容均未进入该提交，已核对：

- `data/txn/`（BACKUP-SAFE-1B 实验代码）——本工作区根本没有该目录；
- `logs/`（模拟器截图与测试日志，共 28 个未跟踪文件）——留在工作区，未入库；
- `local.properties`、`app/libs/sherpa-onnx-2.0.1.aar`——磁盘上存在，被 `.gitignore`
  第 24、29 行忽略，未提交。

## 2. 三个文件与证据副本的对应关系

证据副本指 `daily-ux-1/source/`（随 `c442bd7` 入库的那份）。

| 文件 | 提交内 blob | sha256 | 证据副本 | 逐字节 |
|---|---|---|---|---|
| `app/src/main/java/com/example/myapplicationfirstapp/MainActivity.kt` | `4c6fc4f8a71897b62bb40ea9aaf0fd5d8c59517e` | `4a9f2a14f05ddf26f993d7825b8adee072034e80754d684ff88215b1598a252f` | `source/app/src/main/java/…/MainActivity.kt` | 相同 |
| `app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt` | `9b021f3eaa461a68e5bd59daafcb4fc4b0bd0788` | `491c5212875fdb2b7c750baef1dd11f2724ca1a815b2344f988f6e6d3a77c9ac` | `source/app/src/main/java/…/ui/NoteEditorScreen.kt` | 相同 |
| `app/src/test/java/com/example/myapplicationfirstapp/ui/NoteEditorAutoFocusPolicyTest.kt` | `54558c38ddb234e437d9e8983e1590a1b756788b` | `b82137b1a6a19b96c6a224148d0c524e51d4a60ffdd2d9eb10c0eaeac9414c6e` | `source/app/src/test/java/…/ui/NoteEditorAutoFocusPolicyTest.kt` | 相同 |

核对方式：`cmp` 逐文件比对提交工作区文件与 `source/` 副本，3/3 相同；
提交内 blob 与工作区一致（暂存后 `git diff --cached --name-status` 为
`M` / `M` / `A`，无第四项）。

## 3. 测试证据（本次未重跑）

| 项 | 结果（tests / failures / errors / skipped） | 路径 |
|---|---|---|
| 判定分支单测 | 3 / 0 / 0 / 0 | `daily-ux-1/test-results/unit-policy.xml` |
| 全量仪器化 | 87 / 0 / 0 / 0（`emulator-5554`，API 37） | `daily-ux-1/test-results/full-androidtest.xml` |

**本次固化没有重跑任何测试。** 提交内容与原测试对象一致：三文件与 `source/` 副本
逐字节相同，而 `source/` 正是那两份 XML 的被测对象，因此上表结论适用于该提交。
本记录不主张「固化后重新测试通过」——没有这样的运行。

## 4. 调试 APK

| 项 | 值 |
|---|---|
| 构建机路径 | `D:/SAVE-ORDER-UX-1-wt/app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 57456915 B（约 54.8 MiB） |
| sha256 | `c9c4e51ca50a5d104b6393de433a1e805c7868ac548cbe6b9e827ce6f68a0b07` |
| 构建时间戳 | 2026-11-12 05:03（本地） |

实际获取方式：随本次会话以**文件卡片**交付（WorkBuddy 会话内可点击下载/另存），
文件即上表路径的原始构建产物，未重新构建、未修改。

**未进 Git**：`ux/daily-ux-1` 与证据分支均不含此 APK（`build/` 本就被忽略，且 57 MB
产物不适合进源码历史）。因此除会话卡片外没有第二个下载地址；拿不到卡片时，只能按
`INDEX.md` §3 的 diff 自行 `assembleDebug`，自建产物的哈希以实际构建环境和产物为准，
不能假定与本 APK 逐字节相同。

## 5. 边界

- 未合并 `main`（仍 `61dff28`）；未更新 `fix/backup-safe-1`（仍 `26a0de3`）；未建 PR。
- 未重跑测试、未升级/改动任何测试依赖、未扩功能。
- 证据分支的历史提交未改写；本次只新增本文件，并对 `daily-ux-1/INDEX.md` §7 与
  `backup-safe-1b/PAUSED.md` §1/§4/§7 做了口径更正（删掉「APK 只在签名时间戳上不同」
  的断言；把 core-rev3 的状态从「尚未送审」改为「复审通过、补充测试未完成且任务已撤回、
  整体仍暂停」）。
