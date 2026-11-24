# 代码审查报告：ux/rhythm-recent-notes-1 @ eafd31e

日期：2026-11-24
审查对象：`D:/RECENT-NOTES-1-scope-v2`，HEAD `eafd31e823f1ddb3e7e8595d9c4762b2ef1d1009`（= 特性分支远端），父 `5c688af`
方法：三路并行只读精读（数据层 / UI 生命周期层 / 新特性层），主审对 P0/P1 逐条回到源码复核
性质：**只读审查，未修改任何源码、未提交特性分支、未构建、未跑测试**

## 结论摘要

| 级别 | 条数 | 性质 |
|---|---|---|
| P0 | 1 | 不可逆数据丢失：畸形备份导入会清空全部笔记和附件 |
| P1 | 5 | 录音元数据丢失 / 编辑内容丢失 / ANR 风险 / 启动性能 / 冷启动轮播 |
| P2 | 12 | 健壮性、原子写、性能抖动、边界条件 |
| TEST-GAP | 6 | 覆盖回退与缺失用例 |

根因级的 P0 只有一条：**生产导入路径绕开了已经写好的严格校验器**，详见 P0-1。

## P0（必须优先修）

### P0-1 畸形备份导入 → 返回空列表 → 删光附件 + 清空笔记 + 落盘空数组
- 位置：`data/Note.kt:216-220`、`data/NotesRepository.kt:167-200`、`ui/NotesState.kt:1215-1237`、`MainActivity.kt:915/926-929`
- 链条（已逐行复核确认）：
  1. `notesFromJson(text)` 解析失败 `getOrDefault(emptyList())` —— 失败返回**空列表，不是 null**。
  2. `NotesRepository.importBackup` 把该空列表当成功结果向上返回（只有 openInputStream 为 null 或抛异常才返回 null）。
  3. `NotesState.importBackup:1217` 的 `?: return -1` 拦不住空列表。
  4. `:1223 pruneAttachments(emptySet())` → `NotesRepository.kt:126-132` 对 attachments 目录下每个文件 `if (name !in keep) file.delete()` —— 全部图片被删。
  5. `:1227-1231 _notes.clear(); addAll(emptyList()); persist()` —— notes.json 被写成空数组。
  6. UI `:927` 显示"已导入 0 条笔记"，用户以为成功。
- 触发：用户选择一个 zip，其 `notes.json` 存在但畸形（截断、乱码、`{}` 非数组、尾部垃圾）。
- 后果：正文 + 全部附件不可逆删除，回收站也无副本。
- 修复建议：
  1. 解析层对畸形 JSON 返回 null/抛错，不要静默降级为空列表；
  2. `NotesState.importBackup` 对 `imported.isEmpty()` 做"不覆盖现有数据"硬保护（需区分"用户真的导出了空备份"，可要求二次确认）；
  3. 根治：把生产入口切到 `BackupImporter`（见 P0-2）。

### P0-2 整套 BackupImporter 安全校验在生产零引用
- 位置：`data/BackupImporter.kt:45`（prepare）；生产唯一入口 `ui/NotesState.kt:1217`
- 证据：全仓 `BackupImporter.prepare(...)` 引用**全部位于 `app/src/androidTest/`**（Cancel/Limits/Prepare/Validation 四个测试类），`app/src/main/` 无任何引用。
- 生产路径 `NotesRepository.importBackup:167-200` 缺失 BackupImporter 已有的全部防护：条目大小/数量上限、严格 UTF-8、字段类型校验、CRC、路径校验、取消检查。
- 后果：P0-1 的触发前提成立；此外 zip 解压炸弹、超大 notes.json OOM 均可发生。
- 修复建议：将 `NotesState.importBackup` 切换到 `BackupImporter.prepare()` + commit 流程，消费其校验结果。

## P1

| 编号 | 位置 | 问题 / 触发 / 后果 | 修复 |
|---|---|---|---|
| P1-1 | `ui/RecorderState.kt:409,411,539,541,714` | prepare/start/stop/release 均为主线程同步 binder 调用；点录音、保存、进详情页时掉帧甚至 ANR | 用 `withContext(Dispatchers.IO)` 封装 |
| P1-2 | `MainActivity.kt:327` + `ui/RecorderState.kt:553,1000` | 退出/旋转时 `onDispose` 先于 `scope.launch` 执行，保存协程无法启动；m4a 已落盘但波形/标题/转写元数据丢失 | 保存走独立 SupervisorJob+IO 作用域并同步等待 |
| P1-3 | `ui/NoteEditorScreen.kt:167,173,374-378,1254,1272-1276` | 正文延迟 500ms、待办 400ms 才写回 state；窗口内旋转/回收丢输入 | `rememberSaveable` 草稿或每次输入即写回，onDispose 兜底 flush |
| P1-4 | `data/AsrModelStore.kt:52-120` + `data/SpeechTranscriber.kt:42-44` | 模型下载无大小上限、无 SHA-256、下载与转写不共用 Mutex、无 fd.sync；可写满磁盘 / 加载损坏模型崩溃 | 加上限+校验和+共用锁+sync |
| P1-5 | `ui/NotesState.kt:286-289` + `ui/NotesListScreen.kt:162-165` | 冷启动先置 `loaded=true` 再异步 `refreshRecent()`，轮播晚于列表出现导致首屏看不到、列表错位（验收报告已记录"未修"） | `loaded` 推迟到 refreshRecent 完成，或轮播出现时校正滚动位置 |

## P2（部分为待确认边界）

- P2-1 `data/Note.kt:198`：`"deletedAt":null` 被 `optLong` 读成 0（1970），启动清理 `NotesState.kt:215-219` 会把它当过期笔记连同附件删除。**注意**：`Note.toJson():152` 从不写 null 字段，故仅外部篡改/旧版文件触发；`BackupImporter.normalizeImportedNote:736` 已处理该陷阱但未用于生产读取路径。修复：把 null 判定下沉到 `noteFromJson`。
- P2-2 `data/NotesRepository.kt:47-64`：notes.json 原子写在 rename 后未 fsync 父目录（极端断电丢目录项）。
- P2-3 `data/RecordingStore.kt:141-143`：录音 meta 用 `writeText` 非原子写，崩溃会截断转写/标题。
- P2-4 `data/AppSettings.kt` 全量 `apply()` 异步提交，进程被杀设置回退。
- P2-5 `data/RecordingStore.kt:78` 文件名秒级精度，同秒两次录音互相覆盖。
- P2-6 `data/NotesRepository.kt:150-155`：导出时 image 名来自笔记 images 未做路径校验；若 notes.json 被篡改含 `../../x` 可越界读私有目录外文件（**待确认**：前提是攻击者已能改写私有 notes.json）。
- P2-7 `ui/NotesState.kt:1399`：`checkNotNull(writeCoordinator.reserve())` 在 replace 期间崩溃（当前 replace 生产无调用，为死代码）。
- P2-8 `data/RecentNotesStore.kt:119-121`：`onUpgrade` 直接抛错，未来 DB 版本升级会整体失败（当前 version=1 不触发，作者刻意 fail-loud）。
- P2-9 `ui/NotesState.kt:277,299-301,821,1015` + `ui/RecorderState.kt:949`：启动时删过期附件、重排闹钟、删笔记/图片/录音均在主线程做同步 IO / binder。
- P2-10 `ui/NoteEditorScreen.kt:1190` 超长待办用 Column 全量组合；`ui/RecordingDetailScreen.kt:203` 播放时每 60ms 全量扫描文字稿段。
- P2-11 `MainActivity.kt:264-268` 新建笔记无防连点，快速连点产生多条残留空笔记。
- P2-12 `data/ReminderScheduler.kt:33,117`：持有 Activity Context（当前生命周期内不构成实际泄漏）；`requestCode = noteId.hashCode()` 有极低概率冲突覆盖闹钟；录音期间无前台服务，后台久置可能被杀中断。

## 优化点与测试缺口

统计口径 / 排序（已核对，未发现缺陷）：打开去重靠 UNIQUE+CONFLICT_REPLACE；`open_order` 自增抗时钟回拨；软删除保留历史、恢复自动重现；置顶与最近使用分组互不污染。

测试缺口（建议补）：
1. `NotesStateRecentNotesTest` 缺"DB 已有历史行 → load() 后 recentNotes 非空"的冷启动 hydrate 用例（直接锁住 P1-5）。
2. `NotesListScrollbarMathTest` 新版 11 条删除了旧版 `AxisTracker.observe/resetIfNeeded`、中位数抗离群等用例，存在实质性覆盖回退。
3. `RecentNotesStoreTest` 缺并发 `recordOpened` / `forgetAll` 交错用例（Mutex 与 open_order 单调性）。
4. `ScrollbarMath.kt:5-10` 三个纯函数无 NaN/Infinity/零分母兜底（当前调用方有 coerce 保护，属潜在缺陷），测试也未覆盖。
5. `NotesHomeListModelTest:139-142` 只测 NaN，未覆盖 Infinity/0/负数三条 require 分支。
6. `RecentNoteUsageTest` 未测 blank noteId 过滤与重复 id 折叠分支。

## 边界

- 未确认项已显式标注"待确认"，未当作已发生问题陈述。
- 本次未构建、未跑任何测试、未改代码；严重级别基于源码静态分析与调用链交叉核对。
- 修复优先级建议：P0-1/P0-2（同一根因，一次性修）→ P1-2/P1-3（数据一致性）→ P1-5（特性达标）→ P1-1/P1-4 → P2/测试缺口。
