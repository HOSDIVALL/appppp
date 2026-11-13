# UX-LIST-ACTION-1 证据索引

笔记列表行的「更多操作」入口：给每一行一个看得见、点得到的入口，不再只能长按。

- 分支：`ux/list-actions-1`
- 基线：`7f141485b57b87fcbd60a4ef98ceeb2420f4bbd8`（`ux/daily-ux-6`）
- 设备：`emulator-5554`，API 37，`ro.kernel.qemu=1`（模拟器，未用真机）
- 数据：全部为合成数据（45 条，标题前缀 `UXLA1` / `UX5-01`）；测试前的原始数据在验收结束后逐字节写回

## 改了什么

只有两个生产文件，加一个 JVM 单测。

| 文件 | 基线 blob | 改后 blob | 行数 | 内容 |
| --- | --- | --- | --- | --- |
| `app/src/main/java/com/example/myapplicationfirstapp/ui/NotesListScreen.kt` | `a4d9542` | `760db89` | +28 / −10 | `NoteRow` 的 `Row` 末尾（`Column` 之后）加一个 `Box`：`IconButton`（`size(48.dp)`）+ 既有 `DropdownMenu`；图标 `Icons.Filled.MoreVert`、20dp、`tint = onSurfaceVariant`、`contentDescription = strings.noteActions(title)`；`onClick` 只把 `menuOpen` 置真 |
| `app/src/main/java/com/example/myapplicationfirstapp/ui/i18n/AppStrings.kt` | `37765a8` | `8b45674` | +9 / −2 | 新增成员函数 `noteActions(title)`；首页提示改为「共 %d 条 · 左滑删除，长按或点更多」/「%d notes · swipe to delete, hold or tap more」，`%d` 保留 |
| `app/src/test/java/com/example/myapplicationfirstapp/ui/NotesListActionsTest.kt` | 新增 | — | +82 | 6 个 JVM 单测，只锁文案契约 |

两个生产文件合计 +37 / −12。

关键取舍：

- 菜单**仍是同一套**。`menuOpen` 是原有的那一个状态，长按和新按钮打开的是同一个 `DropdownMenu`，没有第二套菜单、没有第二个 `pointerInput`、没有新增业务动作。
- 按钮**自己可点击**。`Card` 上是 `combinedClickable`，若尾部区域不可点，点击会被合并进整行、变成「打开笔记」；`IconButton` 自带 clickable 语义，正好把这块从行的点击里切出来。
- 触控区 48×48dp，图标 20dp。层级实测可点容器 126×126px（÷2.625 = 48.0dp），内层图标 53px（≈20.2dp）。

## 逐项结果

12 项全部 PASS（T11 原先被我误判为不可达，见下面「我自己的失误」第 4 条）。

| 项 | 内容 | 结果 | 关键事实 |
| --- | --- | --- | --- |
| T0 | 缺口是否真实 | PASS | 改动前层级里「笔记操作」节点 **0** 个；长按能出菜单（7 节点，仅「置顶」） |
| T1 | 点标题仍打开笔记 | PASS | 编辑页正文是 dup-a 的正文，打开的是点中的那条 |
| T2 | 点更多只开菜单 | PASS | 7 节点、仅「置顶」；数据哈希未变 |
| T3 | 长按仍是同一套菜单 | PASS | 7 节点、仅「置顶」，无多选 |
| T4 | 菜单置顶生效且不牵连 | PASS | 只有 `uxla1-dup-b` 的 `pinned` 变；取消后哈希回到 `2a6210ec` |
| T5 | 点外部/返回能关 | PASS | 回到 102 节点列表，弹出区消失 |
| T6 | 横滑取消路径不受影响 | PASS | 120px（≈46dp）< 阈值，条数 45、哈希不变 |
| T7 | 搜索态往返 | PASS | 「匹配 4 条笔记」保持，query 还在，focused 节点 0 |
| T8 | 同名笔记不串行 | PASS | 置顶 a 只改 `uxla1-dup-a`，`uxla1-dup-b` 不动 |
| T9 | 中英/深色/尺寸 | PASS | 英文 6 条 `Note actions: …`，中文 6 条 `笔记操作：…`；48.0dp / 20.2dp |
| T10 | 新建与编辑 | PASS | 45 → 46；只有 dup-b 正文变化（含 `EDIT10`） |
| T11 | 键盘焦点可到、可激活、可关闭 | PASS | 第 10 次 TAB 聚焦 `[849,1177][975,1303]`（126×126）；ENTER 弹菜单；BACK 关闭 |

T11 的边界：键盘焦点落在**可点容器**上，容器的 `content-desc` 为空，文本在内层图标节点（`focusable=false`）。读屏走语义树、不走键盘焦点，所以这一项只证明「键盘可达可激活」，**不声称读屏播报内容已实测**。

## 目录结构

```
docs/ux-list-actions-1/
├── INDEX.md                      本文件
├── observations.json             12 项逐项结果（含我的 5 处测试失误、4 项未做）
├── build-and-tests.txt           构建/测试记录：命令、耗时、结果 XML 计数、失败根因
├── SHA256SUMS                    本目录全部材料的哈希（不含自身）
├── logs/                         构建与测试原始日志（已脱敏）
│   ├── build-baseline.log        基线构建（源码零改动）
│   ├── build-new.log             改动后构建
│   ├── build-with-test.log       尝试打包 androidTest
│   ├── unit-all.log              全量 JVM 单测
│   ├── unit-NotesListActionsTest.log
│   └── instrument-NotesListActionsTest.txt   androidTest 全 Error 的完整堆栈
└── evidence/
    ├── T0/ … T11/                各项截图与 uiautomator 层级
    └── list/                     验收后列表全貌
```

`observations.json` 里的 `checks[].evidence` 是相对本目录的路径，可直接对应到文件。

## 构建与测试

详细命令、耗时与判据见 `build-and-tests.txt`，这里只放结论：

- 基线构建（源码零改动）：`BUILD SUCCESSFUL in 1m 47s`，APK 57,387,528 B / `3f7d9a1c…2a57`，**与历史 DAILY-UX-6 交付的 APK 哈希完全一致**。
- 改动后构建：`BUILD SUCCESSFUL in 25s`，APK 57,924,609 B / `51d3dc0e…f056`。
- JVM 单测全量重跑（`--rerun-tasks`）：15 个用例，0 跳过、0 失败、0 错误（其中新增 6 个）。
- androidTest：**没跑成**。`espresso-core 3.5.1` 在 API 37 上反射 `InputManager.getInstance()` 抛 `NoSuchMethodException`，Compose 测试规则初始化即失败，7 个用例全 Error。任务禁止升级依赖，故该测试文件已删除，UI 行为改由模拟器实测覆盖。这条记录保留，是为了说明「为什么没有 Compose UI 测试」，不是把 7 个 Error 当成通过。

判据一律取结果 XML 的 `tests / skipped / failures / errors`，不看 `BUILD SUCCESSFUL`。

## 设备与数据

- 安装用 `adb install -r`，保留数据；**没有** clear data、**没有**卸载应用。
- 跑 instrument 前整包备份 `files` + `shared_prefs`（`tar` 23,556 B）。
- 结束后用 `adb exec-out` 取原始字节复核：`notes.json` `3fa693ac…f5c4`（17,123 B）、`settings.xml` `c98eb4b7…098f`（116 B），与测试前逐字节一致。
- 测试 APK 已卸载，设备只剩 `com.example.myapplicationfirstapp` 一个包。

取文件时用 `exec-out` 而不是 `adb shell cat`：后者会经 pty 把 LF 转成 CRLF，116 B 会变成 120 B、哈希对不上（见「我自己的失误」第 5 条）。

## 我自己的失误

不是为了免责，是因为这 5 处都会影响你怎么读证据：

1. **T6 第一次横滑把笔记删了**：位移 260px（≈99dp）超过行宽 35% 的删除阈值，`uxla1-dup-a` 进了回收站。这是测试动作越界，不是产品缺陷；写回数据后哈希复原，再用 120px 慢速横滑重测取消路径。
2. **两次按返回退出到桌面**：T9 语言页、T10 编辑页各一次。用 `am start` 重新拉起，没把「退到桌面」记成产品行为。
3. **T10 输入的连字符被 IME 吃掉**：`T10-new-note-body` 落成 `T10-new--by`。如实记录，未重跑掩盖，后续改用无连字符串。
4. **T11 第一版判据写错**：我只找「`content-desc` 非空且 `focused=true`」的节点，差点登记 NOT_RUN。实际可点容器是 `focused=true` 但 `content-desc` 为空、文本在内层图标节点，被判据筛掉了。改成「focused 节点 bounds 是否等于该行更多按钮的 bounds」后，第 10 次 TAB 就命中，随后完成激活与关闭。
5. **settings.xml 一度以为被改**：`adb shell cat` 拿到 120 B / `2dec1581…`，改用 `exec-out` 得到 116 B / `c98eb4b7…`，与测试前一致。差异是 pty 换行转换。

## 没做的事

- 未改 `Note` / `NotesState` / `AppSettings` / `NotesRepository` / `NotesWriteCoordinator` / `NotesFileOps`
- 未引入手动排序、`MANUAL`、`rank`
- 未新增删除、清空等业务动作（菜单仍是既有「置顶 / 取消置顶」）
- 未改 `MainActivity`、编辑页、录音页
- 未新增依赖，未改 SDK / Gradle / 版本号
- 未绕开 writer 直接写文件
- 未跑 `connectedDebugAndroidTest`（AGP 会卸载 app 与测试 APK，清空 `files/` 与 `shared_prefs/`）
- 未用真机，未开读屏实测播报
- 未 force push、未合并 `main`、未创建 PR
- 未把 APK、AAR、笔记数据、录音、图片提交进 Git

APK 是本地交付物，不在仓库里：

```
app-debug.apk   57,924,609 B   SHA-256 51d3dc0ec3bd82f131343620ef2986c8644348f3456d4c10a79c2c7db8a0f056
```

## 复验方法

```bash
git fetch origin ux/list-actions-1
git checkout 7f141485b57b87fcbd60a4ef98ceeb2420f4bbd8
git diff 7f141485 -- app/src/main/java/com/example/myapplicationfirstapp/ui/NotesListScreen.kt
# 基线构建应得到 3f7d9a1c…2a57；再切到 ux/list-actions-1 构建应得到 51d3dc0e…f056

# 判据只看结果 XML
cat app/build/test-results/testDebugUnitTest/TEST-*.xml | grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"'

# 证据材料完整性
cd docs/ux-list-actions-1 && sha256sum -c SHA256SUMS
```

日志里的本机路径已脱敏：工作区 → `<workspace>`，用户目录 → `<user-home>`，Android SDK → `<sdk>`。
