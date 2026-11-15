# 证据索引 — 第一批「最近使用业务接线」

**用途**：只承载审阅副本。本分支不含任何 `app/` 源码改动，源码仍在工作区、**未提交**。
**被评审基线**：`ux/rhythm-recent-notes-1` @ `fd9438894d3b121ed8ed233e887ac19ce113f20b`
**本分支派生自**：同一基线（`fd94388`），只追加 `recent-notes-1/` 目录。

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
