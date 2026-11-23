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

### 7.1 v2 harness 调用请求（同日 18:50）：缺正文与 manifest

```
recent-notes-1/device-install-gate/v2-request/
  NOTES-2.md        收到 v2 调用命令但无脚本正文；四项取值未给；设备改由模拟器
  avd-inventory.txt 本机 AVD 只读盘点：两台均不满足「包名不存在」
```

要点：

1. 本轮只收到调用行，没有 `workbuddy_install_scrollbar_v2.sh` 正文，也没有 `BUILD_MANIFEST`；
   磁盘上仅存在 v1 脚本（sha256 `9f6ffe96…90a5c70`）。`$APP_APK` / `$ANDROID_TEST_APK` / `$TEST_DEVICE_SERIAL` 未给字面值。
2. 设备改用模拟器后，`--dedicated-device` 的「包名不存在」前置仍不成立：
   `NotesGO_B2_Test` 有持久 userdata 且已装入候选包；`Medium_Phone_API_37.0` 未启动核实，不能断言干净。
   两条出路：新建一次性 AVD（推荐），或对 B2_Test 用 `-wipe-data`（会毁 18:0x 验收对应设备状态）。
3. 本轮零动作：未建/启 AVD、未安装、未跑测试、未改脚本。

### 7.2 同日后补：一次性 AVD 已就绪，前置已实测

```
recent-notes-1/device-install-gate/v2-request/avd-ready.txt
```

7.1 记录的「两台 AVD 均不满足包名不存在」已处理：

1. 手工新建 `NotesGO_V2_Dedicated`（仅写 `.ini` 与 `config.ini`，未复制任何镜像，首次启动生成全新 userdata），未改动、未擦除、未启动原有两台。
2. 以端口 `5558` 启动，`sys.boot_completed=1`；设备 `sdk_gphone64_x86_64` / Android 17 / API 37 / x86_64 / 1080x2400@420dpi / current user 0。
3. 实测 `pm list packages -u | grep -i myapplication` 无匹配 —— 前置「包名不存在」成立，非由镜像缺失推断。

仍需：v2 脚本正文、`BUILD_MANIFEST`、`APP_APK` / `ANDROID_TEST_APK` 字面值（serial 用 `emulator-5558`）。

### 7.3 限位候选（scope）构建通过；v2 安装脚本 CRLF 缺陷；已按同语义装入专用模拟器

```
recent-notes-1/device-install-gate/scope-build-v2/
  NOTES.md            复现步骤、构建数字、限位改动、harness 首错与根因、安装结果
  status.txt          build_passed_no_install: exit 0
  build-manifest.json 三项哈希 + 两个 APK 绝对路径（app 440e6c48…，test 1c6c51a0…）
  before.diff / after.diff   限位前后（仅两个滚动条文件）
  apply.log / unit.log / app.log / androidTest.log
  unit-summary.txt    73 tests / 0 fail / 0 err / 0 skip
  unit-xml/           9 个 TEST-*.xml 原始结果
  install/            device.txt、install-attempt.log、RECORD.md、01..03.png
```

要点：

1. 取件按 `0d63c8f`（三份脚本）与 `229af49`（`candidate-full.diff`）。隔离分离工作树
   `D:/RECENT-NOTES-1-scope-v2`，`git apply --check` 干净，实际 12 文件 +958/−513；
   AAR 校验 `a8d09d1d…f0ee7`，与上一轮同一份。
2. 构建全绿：单测 73/0/0/0（24 首页模型、11 最近使用、11 滚动条），两支打包各 exit 0；
   `git diff --check` 干净；manifest 已产出且 app 哈希不等于旧包 `354fedc0…`。
3. **v2 安装脚本首跑 exit 1**：`adb.exe: filename doesn't end .apk or .apex: …\app-debug.apk`。
   根因是 python `print(path)` 在 Windows 输出 CRLF，`mapfile -t` 元素尾留 `\r`，adb 收到
   `…apk\r`。脚本与 Kotlin 均未改动；修法见 NOTES（python 侧重配 newline 或 bash 侧 `tr -d '\r'`）。
4. 经委托方确认按同一语义手工安装：三项哈希 MATCH、AVD 身份与 boot 核对通过、安装前包名不存在、
   两次 `install -r -t` 均 `Success`；`versionName 2.0 / code 2`，instrumentation 已注册。
5. 全新 AVD 无笔记，首页为空态，滚动条不出现 —— 限位效果尚未肉眼验收；未跑
   `connectedDebugAndroidTest`（harness 明示 No tests were run）。

### 7.4 专用模拟器验收：限位与拖动判定通过；气泡首字口径待确认；冷启动轮播仍未在首屏

```
recent-notes-1/device-install-gate/scope-accept-v2/
  NOTES.md                 逐项结论、造数说明、两个未闭环项
  01-device-identity.txt   get-state / AVD 名 / 版本 / 安装时间 / 已装 base.apk 哈希
  02-instrument.txt        am instrument 原始输出（22 tests，OK）
  02b-instrument-summary.txt 11 + 11 分类统计
  19-scrollbar-geometry.txt  像素测量的手柄/轨道坐标与参照边界
  14-top-carousel-scope.png  顶部轮播可见时的限位
  15-drag-handle-at-top.png  手柄拖到最顶
  16-drag-bubble.png         按住手柄的气泡（R）
  17-bottom-end.png          拖到最底，轨道止于最后一条笔记
  18-drag-cross-carousel.png 轮播滚出视口瞬间
  09/10/11-search-*.png      1 条 / 0 条 / 清空恢复
  13-crash.txt               crash 缓冲导出（0 行）
```

要点：

1. 身份复核：已装 `base.apk` 拉回本地算哈希 `440e6c48…3ae29`，与 scope-build-v2 的
   `app_sha256` 一致；`versionName 2.0 / code 2`，安装时间仍为 11:38:42 UTC（未重装）。
2. 仪表测试：`RecentNotesStoreTest` 11 + `NotesStateRecentNotesTest` 11 = **22 通过 / 0 失败 / 0 跳过**，
   `OK (22 tests)`，Time 5.207。
3. 顶部带轮播时：手柄 1795–1920、轨道 1931–2095；首条笔记上沿 1806、轮播下沿 1271、
   筛选行 1570–1696、导航 2153–2289 —— 滚动条只在笔记行区间，**通过**。
4. 拖动四帧手柄顶端 1889→1553→1215→1025→790，单调无回跳；顶/末端分别停在第一条与最后一条实际笔记，
   底部未进留白与导航，**通过**。
5. **气泡首字待确认**：气泡显示 `R`，对应视口第一条可见笔记 `River 河流 18`，
   而手柄正对行是 `Prairie 草原 16`（P）。若约定为"手柄正对行"，此项不通过。
6. 搜索 1 条 / 0 条时滚动条均不出现，清空后恢复；crash 缓冲 0 行。
7. **观察项（未修）**：冷启动首屏仍看不到轮播（05 截图），下滑一次才出现 —— 与上一轮记录同缺陷。

### 7.5 验收通过的源码落盘：`eafd31e` 快进推送到 `ux/rhythm-recent-notes-1`

```
recent-notes-1/feature-landing/
  NOTES.md               落盘范围、排除项、CRLF 告警成因、推送核对、两项未做
  commit-meta.txt        git log -1 --format=fuller eafd31e
  commit-stat.txt        git show --stat --oneline eafd31e（12 files, +1120/−598）
  commit-files.txt       12 个 app/src 文件的 name-status
  feature-landing.diff   git diff 5c688af4 eafd31e（104,244 B）
  landing-gates.txt      基线/暂存/check/一致性四项实测值
  remote-readback.txt    ls-remote、rev-parse、log、push 回显
```

要点：

1. 提交 `eafd31e823f1ddb3e7e8595d9c4762b2ef1d1009`，父提交 `5c688af4`；12 个文件全在 `app/src/`，
   +1120/−598。入库内容与「73 单测全绿 + 22 仪表通过 + 5558 安装验收」所用工作树逐字一致
   （提交后 `git diff HEAD -- app/src` 为 0 行）。
2. 未入库：`app/libs/sherpa-onnx-2.0.1.aar`（提交内 `app/libs` 为 0 文件）、APK、三个证据目录；
   暂存用 `git add -- app/src/` 限定路径。
3. `git diff --cached --check` **exit 2**：18 条 `trailing whitespace` 全部落在 `MainActivity.kt`，
   该文件 `i/crlf  w/crlf`、仓库无 `.gitattributes`、`core.autocrlf=false`，属既有 CRLF 约定；
   该文件本次 diff 仅 26 行，不是整篇行尾改写，其余 11 个文件无告警。
4. 用户给的 `git status --short --cached` 不被 git 接受（`unknown option 'cached'`），
   改用 `git diff --cached --name-status` 核对，清单与第 1 条一致。
5. 推送回显 `5c688af..eafd31e  HEAD -> ux/rhythm-recent-notes-1`，快进无 `--force`；
   回读 `origin/ux/rhythm-recent-notes-1` = `eafd31e8…`；`refs/heads/main` 仍为
   `61dff28e4041aa5a8d9376ba1da872a87a009301`，未触碰。
6. **未做（等指令）**：冷启动首屏轮播（需改 `NotesState`/`NotesListScreen`，属源码改动）；
   `workbuddy_install_scrollbar_v2.sh` 的 `\r` 修复。
