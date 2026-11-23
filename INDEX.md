# 证据索引 — 最近使用接线 + 首页接线候选

**用途**：只承载审阅副本。本分支不含任何 `app/` 源码改动，源码仍在工作区、**未提交**。
**本分支派生自**：`ux/rhythm-recent-notes-1` @ `fd9438894d3b121ed8ed233e887ac19ce113f20b`，只追加 `recent-notes-1/` 目录。

两轮材料，互不覆盖：

| 轮次 | 基线 | 目录 | 结论 |
|---|---|---|---|
| 第一批「最近使用业务接线」 | `fd94388` | `recent-notes-1/batch1/` | 通过：JVM 38 / 仪器化 22，全绿 |
| 第二轮「首页接线候选验证」 | `5c688af` | `recent-notes-1/home-wiring-5c688af/` | 前四阶段通过，卡在单测编译（基线遗留），详见该目录 `NOTES.md` |

## 1 目录结构

```
INDEX.md                                  本文件
SHA256SUMS                                全部材料校验和（不包含自身）
recent-notes-1/batch1/
  NOTES.md                                交付说明：基线、改动清单、测试表、风险、待确认项
  batch1.diff                             完整补丁（相对 fd94388 的增量，含 2 个新增文件）
  test-results/jvm/*.xml                  7 份 JVM 单测结果 XML（38 条）
  test-results/android/connected-androidtest.xml
                                          集成测试结果 XML（22 条，原文件名
                                          "TEST-Medium_Phone_API_37.0(AVD) - 17.xml"）
  screens/R2-home.png                     首页 64 条合成数据渲染
  screens/R3-opened.png                   点开后进入编辑页
  db/recent_notes.db                      端到端操作后的元数据库（可直接 sqlite3 打开）
  logs/env.txt                            设备/工具链参数与实跑命令
```

## 2 判据（取结果 XML，不取 Gradle 的 BUILD SUCCESSFUL）

| 门 | 文件 | tests | failures | errors | skipped |
|---|---|---|---|---|---|
| 纯 JVM | `test-results/jvm/*.xml`（7 份） | 38 | 0 | 0 | 0 |
| 仪器化（SQLite + 接线） | `test-results/android/connected-androidtest.xml` | 22 | 0 | 0 | 0 |
| 整 App 构建 | `./gradlew :app:assembleDebug` | — | 退出码 0 | — | — |

两套新增用例（各 11 条，全部通过）：

- `data.RecentNotesStoreTest`：`recordsAndOrdersByOpenOrder`、`reopeningMovesToTopWithoutDuplicates`、`rowsSurviveNewStoreInstance`、`orderSurvivesClockRollback`、`forgetRemovesOnlyThatRow`、`forgetAllRemovesBatch`、`forgetAllWithBlankIdsIsNoOp`、`blankNoteIdIsRejected`、`negativeTimestampIsRejected`、`corruptDatabaseIsRecreatedInsteadOfCrashing`、`unopenableDatabaseSurfacesError`
- `ui.NotesStateRecentNotesTest`：`openingExistingNoteRecordsExactlyOnce`、`reopeningOrdersMostRecentFirst`、`unknownNoteIsNotOpenedAndNotRecorded`、`trashedNoteIsNotOpenedAndNotRecorded`、`softDeleteHidesFromDisplayButKeepsHistory`、`permanentDeleteForgetsMetadata`、`emptyTrashForgetsEveryTrashedId`、`openingDoesNotTouchContentOrUpdatedAt`、`loadFailureDoesNotBlockNotes`、`recordFailureDoesNotBlockOpening`、`staleSnapshotDoesNotOverwriteNewerState`

## 3 口径变化（必须读）

1. **JVM 单测源的既有破损**：`app/src/test/.../NotesListScrollbarMathTest.kt` 在基线 `fd94388` 上就编译不过——它引用 `ScrollbarEstimate`（阶段C `a686f31` 的产物），而 `5773397` / `0cc4367` 的 v4 重写已删除该类（基线实现 0 处引用、测试 62 处）。本批改动**未触碰**这两个文件。为取得真实单测结果，运行时把该文件临时移出源集，跑完立即还原（还原前后 sha256 均为 `8aae7d36…4bb4b0d`）。因此上表 38 条**不含**这份失效测试，它在本批次里没有任何一条被执行。处置方式待定（重写 / 删除 / 保留）。
2. **新建笔记不计入最近使用**：`createNoteNow` 不经过记录入口，属有意为之，待确认。
3. **备份覆盖导入**只清理指向已消失笔记的记录，保留仍存在的 id；不扩展备份格式。

## 4 边界

- 源码：**未提交、未推送**；本分支零 `app/` 改动（可用 `git diff --stat fd94388` 只看到 `recent-notes-1/` 自证）。
- 未合并、未建 PR、未动 `main`、未动 `ux/rhythm-recent-notes-1`。
- 未碰真机：全部测试与截图来自 `emulator-5554` / `Medium_Phone_API_37.0` 合成数据（64 条）。
- 测试收尾导致模拟器上的应用被卸载；已用备份还原 `files/`、`shared_prefs/`、`databases/`，`files/notes.json` sha256 还原后一致。

## 5 如何校验

```bash
git fetch origin review/rhythm-recent-notes-1-evidence
git checkout review/rhythm-recent-notes-1-evidence
sha256sum -c SHA256SUMS
git apply --stat recent-notes-1/batch1/batch1.diff      # 只看规模
sqlite3 recent-notes-1/batch1/db/recent_notes.db \
  "select open_order, note_id from recent_note_usage order by open_order desc"
```

## 6 第二轮材料（2026-11-23）：首页接线候选在 `5c688af` 的验证

目的：在 `5c688af` 的隔离工作树上，先应用第一批补丁，再执行首页接线脚本，导出两批合并的 `candidate.diff`，然后用真实 AAR 构建并跑单测。

```
recent-notes-1/home-wiring-5c688af/
  NOTES.md          本轮完整说明：阶段、首个错误、归因、待裁决项
  status.txt        仅一行，指出停在哪个阶段
  run.log           脚本完整输出（绝对路径已脱敏）
  batch1.diff       从本分支 a818854 抽出的第一批补丁，sha256 与 INDEX 记录一致
  candidate.diff    两批合并候选补丁（1,316 行 / 57,616 B，9 文件，可逆）
  harness/          本轮使用的两个脚本原件（verify_home_candidate.sh、apply_home_wiring.py）
```

判据（取自 `run.log`，不取任何口头结论）：

| 阶段 | 结果 |
|---|---|
| baseline（fetch == `5c688af`） | 通过 |
| batch1（sha256 + `git apply --check --verbose`，8 文件） | 通过 |
| wiring（脚本 exit 0） | 通过 |
| dependency（AAR `a8d09d1d…0ee7`） | 通过 |
| android_build_and_tests | **失败 exit 1**：`:app:compileDebugUnitTestKotlin`，101 条 `e:` 全在 `NotesListScrollbarMathTest.kt` |
| test_evidence | 未执行 |

要点：

1. 失败文件不在 `candidate.diff` 内（`grep -c` = 0），主源集 `ScrollbarEstimate` 引用 0 处、测试侧 62 处 → 归因为 v4 滚动条重写后的基线遗留，与本候选无关。
2. 本轮**零条用例真实执行**，无 `test-results/`、无 APK 产出，因此不提供任何通过数。
3. 第二处将触发的不符：`test_evidence` 期望 `RecentNoteUsageTest` 为 7，源码为 11（基线 7 + 第一批新增 4）。

### 6.1 分开执行的构建与单测（同日补测）

```
recent-notes-1/home-wiring-5c688af/separate-tasks/
  README.md        本轮说明：缺件、三条命令、退出码、CRLF 归因、未验证项
  status.txt       六行，逐条退出码/状态
  b1.log           :app:assembleDebug
  b2.log           :app:testDebugUnitTest
  b3.log           :app:assembleDebugAndroidTest
  diff-check.txt   git diff --check 的全部输出（19 条，全在 MainActivity.kt）
```

| 命令 | 退出码 | 产物 |
|---|---|---|
| `:app:assembleDebug` | 0 | APK 57,469,448 B，`sha256 d85ae6f7…686719d` |
| `:app:testDebugUnitTest` | 1 | 无产物；101 条 `e:` 全在 `NotesListScrollbarMathTest.kt` |
| `:app:assembleDebugAndroidTest` | 0 | APK 1,254,265 B，`sha256 1c6c51a0…60ba6` |

两点必须一起读：

1. **打包与单测互不掩盖**：三条命令分别调用，两条打包各自成功，失败只落在单测编译。
2. `git diff --check` 退出码 2，19 条 trailing whitespace 全在 `MainActivity.kt`，根因是该文件 blob 为 CRLF（`i/crlf w/crlf`、`core.autocrlf=false`），`\r` 被计为行尾空白；实测 0 行以空格结尾。未做行尾修正。

未执行：`migrate_scrollbar_math.py`、`verify_home_junit.py`（两个文件在本机均不存在）。因此**仍是零条用例执行**，24 项 / 11 项 / 滚动条 11 项均无 XML 可查。

### 6.2 滚动条单测迁移与三组 JUnit 核对（首个阻塞已解除）

```
recent-notes-1/home-wiring-5c688af/scrollbar-migration/
  README.md            本轮说明：取件、退出码、迁移内容、XML 计数、打包、CRLF
  status.txt           六行退出码/状态
  migration.diff       本轮迁移的三文件改动（536 行）
  candidate-full.diff  第一批 + 首页接线 + 迁移的合并候选（1,852 行 / 12 文件）
  junit/               9 份 testDebugUnitTest XML（73 条，0 失败）
  b4-test-after-migration.log
  b5-assembleDebug-after-migration.log
  b6-assembleDebugAndroidTest-after-migration.log
  diff-check.txt       迁移后 git diff --check 输出（20 条，全在 MainActivity.kt）
```

| 步骤 | 退出码 |
|---|---|
| `migrate_scrollbar_math.py`（取自 `7d6a001`） | 0，无锚点错误 |
| `:app:testDebugUnitTest` | 0 |
| `verify_home_junit.py app` | 0：`Verified 24 home model, 11 recent usage, 11 scrollbar cases.` |
| `:app:assembleDebug` | 0：APK 58,081,932 B，`sha256 354fedc0…71d526` |
| `:app:assembleDebugAndroidTest` | 0：APK 1,254,265 B，`sha256 1c6c51a0…60ba6`（未变） |

三组目标用例全部执行且通过：24 / 11 / 11；全库 JVM 合计 73 条，0 失败 0 错误 0 跳过。
迁移后的 `app-debug.apk` 取代上一轮 `d85ae6f7…`，后者作废。

## 7 真机安装 harness 执行（2026-11-23 18:38）：第一道闸门未生效

目的：在真机上按作者脚本原样安装滚动条候选并跑 instrumentation。

```
recent-notes-1/device-install-gate/
  NOTES.md            本轮说明：四个阻塞点、未上传材料原因、需要的四项输入
  harness/            workbuddy_install_scrollbar_candidate.sh 原样副本
                      sha256 9f6ffe96…90a5c70
  evidence/status.txt harness 退出时唯一一行：preflight: exit 1
  evidence/apk.sha256 harness 自校验产物
  evidence/hash-gate.txt  闸门 1 比对与可复现命令
  evidence/gate-order.txt 按要求逐项状态
  evidence/install-record.txt 18:14 真机安装与装前装后指纹
  evidence/device.txt     设备参数
```

结论（三项，按严重度）：

1. **闸门 1 模式写错**：`354fedc0*71d526` 匹配不到真实结尾 `11d526`，本应 `exit 2` 的旧候选被放行，脚本继续执行到 `sha256sum -c` 并输出两行 `OK`。差一个字符 `1`。
2. 随后停在 `preflight`：脚本裸调 `adb`，本机 platform-tools 不在 PATH（`line 15: adb: command not found`），且 18:38 设备已从 `adb devices` 消失。
3. 第三道闸门仍会拦：真机包名已存在（18:14 `install -r` 装入），脚本明确 no overwrite or cleanup。

零条用例执行、零条通过。真机用户数据装前装后指纹一致；装前整包备份已做并校验，但因含私人笔记未纳入本分支；首屏截图与控件树同理未纳入。
