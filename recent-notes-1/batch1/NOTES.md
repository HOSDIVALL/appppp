# 第一批「最近使用业务接线」本地交付包（未提交）

生成时间：2026-11-15（本地时间）
工作目录：`<workspace>`（独立 worktree，分支 `ux/rhythm-recent-notes-1`）

## 1. 基线与改动

- 开始时远端 HEAD：`fd9438894d3b121ed8ed233e887ac19ce113f20b`
- 本地 HEAD：同上（`git fetch` 后比对一致，未 reset、未覆盖）
- 工作区：开始前干净
- 提交：**未提交**（等确认）
- 变更文件（6 改 + 2 新增）：

| 文件 | 用途 |
|---|---|
| `data/RecentNoteUsage.kt` | 新增纯函数 `recentNotesForDisplay()`：按 openOrder 映射到可展示笔记，过滤回收站与未知 id |
| `data/RecentNotesStore.kt` | 抽出 `RecentNotesGateway` 接口；新增 `forgetAll(ids)` 批量清理；新增 `openOnSameDatabase()` 供测试模拟重启 |
| `ui/NotesState.kt` | 注入元数据依赖；`recentUsages` / `recentError` / `recentNotes`；统一入口 `openNote()`；加载、删除、清空、导入后的清理；旧快照丢弃 |
| `MainActivity.kt` | 构造时注入 `RecentNotesStore.getInstance`；列表点击与点通知都改走 `state.openNote()`；消费通知 id 后从 intent 移除，避免旋转重复计次 |
| `test/.../RecentNoteUsageTest.kt` | 新增 4 条展示映射用例（原 7 条保留） |
| `androidTest/.../IsolatedNoteStorage.kt` | 沙箱 Context 增加 `getDatabasePath` / `openOrCreateDatabase` 重定向；`dispose()` 加重试 |
| `androidTest/.../RecentNotesStoreTest.kt`（新） | 真实 SQLite：排序、去重、时钟回拨、重启持久化、forget/forgetAll、非法入参、坏库自愈、打不开时报错 |
| `androidTest/.../NotesStateRecentNotesTest.kt`（新） | 接线行为：有效打开、未知/回收站拒绝、软删除保留历史、永久删除清理、清空回收站、加载失败降级、写入失败降级、旧快照不覆盖新状态 |

## 2. 业务结果

- 统一打开入口：`NotesState.openNote(id)`，覆盖「列表点击」与「点通知跳转」两条路径；两者都先校验存在且 `deletedAt == null`，再记录一次。
- 新打开 vs 页面恢复：记录发生在事件回调里，`editingId` 的 `rememberSaveable` 恢复不经过该入口 → 旋转/恢复不重复计次；同时把 intent 里的 note id 消费后移除。
- 新建笔记（`createNoteNow`）**不记录**：新建不是「打开已有笔记」。若需要把新建也算作一次使用，需另行确认。
- 最近使用状态：`recentUsages`（原始）、`recentNotes`（映射后，最多 6 条）、`recentError`（与 `persistenceError` 分开）。
- 删除/清理：软删除只影响展示；`deleteForever`、`emptyTrash`、启动时过期回收站清理会调用 `forgetAll`；备份覆盖导入只清理指向已消失笔记的记录，保留仍存在的 id。
- 正文/updatedAt/备份格式：均未改。实测打开笔记后 `notes.json` sha256 不变。
- 并发：每次元数据读写领令牌，只有最新一次结果能写回状态；取消异常原样上抛。

## 3. 测试（真实执行）

| 范围 | 实际被测提交 | 命令 | 退出码 | 通过/失败/错误/跳过 | 证据 |
|---|---|---|---|---|---|
| 纯 JVM | 工作区（未提交，基于 fd94388） | `./gradlew :app:testDebugUnitTest` | 0 | 38 / 0 / 0 / 0 | `test-results/jvm/*.xml` |
| Android SQLite 集成 | 同上 | `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=…RecentNotesStoreTest,…NotesStateRecentNotesTest` | 0 | 22 / 0 / 0 / 0 | `test-results/android/TEST-Medium_Phone_API_37.0(AVD) - 17.xml` |
| 整 App 构建 | 同上 | `./gradlew :app:assembleDebug` | 0 | BUILD SUCCESSFUL，APK 57,436,680 B | 见下 |
| 模拟器 UI | 同上 | 安装后手动点开两条笔记 | — | 通过 | `R2-home.png`、`R3-opened.png`、`recent_notes.db` |

设备：`Medium_Phone_API_37.0`（emulator-5554，API 37，1080×2400 @420dpi），合成数据 64 条笔记。

### 重要前提：既有编译破损

`app/src/test/.../NotesListScrollbarMathTest.kt` 在基线 `fd94388` 上就**无法编译**：
该文件引用 `ScrollbarEstimate`（阶段C `a686f31` 的产物），而 `5773397` / `0cc4367` 的 v4 重写已把它删掉
（基线实现里 0 处、测试里 62 处引用）。本批改动未触碰这两个文件。

为拿到本批的真实单测结果，运行时**临时**把该文件移出源集，跑完立即还原，
还原后 sha256 与移动前一致（`8aae7d36…4bb4b0d`）。仓库内容未因此改变。
该文件的处置（按当前 `AxisTracker` API 重写 / 删除 / 保留）需要你决定。

## 4. 端到端实测（模拟器，合成数据）

1. 首页 64 条笔记正常渲染（`R2-home.png`）。
2. 点开「这是一条刻意写得很长的标题…」→ 编辑页正常（`R3-opened.png`），`recent_notes.db` 出现 `(1, uhv1-p01, …)`。
3. 返回 → 点开第二条 → 再点回第一条：库中只剩两行，`open_order` 为 3 / 2，第一条回到最前（`recent_notes.db`）。
4. 上述全过程中 `files/notes.json` 的 sha256 恒为 `cb5c19dd…2be36399`（打开不改正文与 updatedAt）。

## 5. 风险与待办

- 未做：首页轮播 UI、顶栏留白、搜索随滚动离屏、滚动条映射同步 —— 属第二批。
- `recentNotes` 目前只被测试与后续 UI 使用，界面尚未展示；第二批接轮播时直接复用。
- 新建笔记不计入最近使用（待确认）。
- 备份导入的清理策略：只清理失效 id，不扩展备份格式，不清空全部历史（待确认）。
- 既有 `NotesListScrollbarMathTest` 编译破损待决定处置方式。
- 未提交、未推送、未建 PR、未碰 main。
