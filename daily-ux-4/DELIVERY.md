# DAILY-UX-4 固化交付记录

- 日期：2026-11-12
- 任务：长笔记输入过程中的光标可见性（评审通过后固化）。
- 边界：不扩功能、不升级依赖、不改数据模型与保存/返回逻辑；未合并 `main`、未建 PR、未动 `fix/backup-safe-1`、未动 `ux/daily-ux-1` / `ux/daily-ux-2`。

## 1. 源码

| 项 | 值 |
|---|---|
| 分支 | `ux/daily-ux-4` |
| 固化提交 | `ede4f75fb25bc34a78d5acc89a863f1f45199e22` |
| 父提交 | `04f400fec16dfdd01be9cb83296a9d83181c3353`（`ux/daily-ux-2` 固化提交） |
| 远端 head | `ede4f75fb25bc34a78d5acc89a863f1f45199e22`（`refs/heads/ux/daily-ux-4`） |
| 改动 | 1 文件，+29 / −3 |

提交内只有这一个文件，不含 `data/txn`、`logs/`、`local.properties`、`*.aar`、`*.apk`、`build/` 或任何构建产物。

| 文件 | blob | 基线 blob |
|---|---|---|
| `app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt` | `82879f22e6dc436befccbbdce242d88b68a88eda` | `22e1471cf0ae4da64f3ed61f36fcaaf75eba8827` |

## 2. 与评审证据的一致性

固化前逐项核对（不是"看起来一样"）：

| 检查 | 结果 |
|---|---|
| 工作区 HEAD 是否为 `04f400f` | 是（`04f400fec16dfdd01be9cb83296a9d83181c3353`） |
| 改动是否只 1 个源码文件、+29 / −3 | 是（`git diff --stat` 与 `git diff --numstat` 均为 `29 3`，1 个路径） |
| 工作区文件 blob | `82879f22e6dc436befccbbdce242d88b68a88eda`，与评审副本 blob 相同 |
| 与 `daily-ux-4/files/NoteEditorScreen.kt` 逐字节比对 | 一致（`cmp` 无差异；两份 SHA-256 同为 `9c7ec1e92fdc1d62b1eceda3b4b1b25e856d7a3385bcba77d3d8d80863a991f8`，大小同为 66,312 B） |
| 暂存后的 blob | `82879f22e6dc436befccbbdce242d88b68a88eda`（`git rev-parse :<path>`） |
| 提交树内 blob | `82879f22e6dc436befccbbdce242d88b68a88eda`（`git rev-parse HEAD:<path>`） |
| 提交涉及文件数 | 1 |
| 提交内容是否含违禁项 | 无（`logs/`、`local.properties`、`*.aar`、`*.apk`、`build/` 全未命中） |

即：**评审的就是固化的**。`core.autocrlf=false`，该文件工作区为 CRLF、仓库内同为 CRLF（`git ls-files --eol` = `i/crlf w/crlf`），提交未做行尾转换，因此 blob 与评审副本逐字节相同。

## 3. 测试

- 本轮 JVM 单测：3 suite / 9 条 / 0 failures / 0 errors / 0 skipped。
- 原始 XML 已原样复制进 `daily-ux-4/logs/unit-test-xml/`（3 个 `TEST-*.xml`），与工作区 `app/build/test-results/testDebugUnitTest/` 下的文件 `cmp` 逐字节相同，未手工编辑。
- 运行时间：2026-11-12 22:22（源码最后修改 22:16:17，提交在 22:39 之后）；提交前后源码内容未变（blob 一致），故该 XML 对应本次固化的同一份源码。
- 逐条：`ExampleUnitTest.addition_isCorrect`；`NoteEditorAutoFocusPolicyTest` 3 条（`newTextNoteFocusesItsBody` / `existingNoteDoesNotStealFocus` / `newChecklistKeepsItsOwnFirstItemFocus`）；`NoteEditorBlankTapPolicyTest` 5 条（`tapBelowLastBlockFocusesBody` / `tapInsideTextKeepsOriginalCaretPlacement` / `noTextBlockIsNotHandled` / `tapExactlyOnBottomEdgeIsNotHandled` / `checklistIsNotHandled`）。
- 汇总文本：`daily-ux-4/logs/unit-test.txt`。
- 这些是 JVM 上的策略函数单测，**不覆盖滚动/可见性**；UI 行为验证是模拟器截图与帧差，见 `CHECKLOG.md`。

**固化这一轮未重跑测试**：只做了 `assembleDebug` 构建与一致性核对，没有执行 `testDebugUnitTest`，也没有执行 `connectedAndroidTest`。上面 9 条仍是本轮检查阶段那次运行的原始结果，**不得**读作"固化后重新测试通过"。

## 4. 模拟器对照路径

改动前后用同一台 `emulator-5554`（API 37，1080×2400 @420dpi）、同一份虚构数据、同一操作路径取证：

| 用途 | 路径 |
|---|---|
| 改动前 | `daily-ux-4/shots/before/01–08`（`01` 末尾无键盘、`02` 空白点击后跳到第 01 行、`03` 光标被键盘遮住、`04` 盲输入后补跳、`05` 换行跟随、`06` 上滑阅读、`07` 场景 B、`08` 中部点击） |
| 改动后 | `daily-ux-4/shots/after/01–09`（含原路径修复、收键盘变体、换行跟随、短笔记、新建自动聚焦、场景 B） |
| 逐项数值 | `daily-ux-4/CHECKLOG.md`（键盘顶边 1517、各次光标矩形区间、帧差结果） |

## 5. 调试 APK

| 项 | 值 |
|---|---|
| 路径 | `app/build/outputs/apk/debug/app-debug.apk`（工作区 `/d/SAVE-ORDER-UX-4-wt`，未入库） |
| 大小 | 57,921,799 字节 |
| SHA-256 | `df5ecc29f9fbf65b27222cae1acd8f0b8021c256eb4ba0dfc2d182e5772c8f67` |
| 构建时间 | 2026-11-12 22:16:59（源码最后修改 22:16:17） |
| 获取方式 | 工作区根目录执行 `JAVA_HOME=D:/Android/jbr ./gradlew assembleDebug`；固化后再次执行同一命令，Gradle 报 `:app:assembleDebug UP-TO-DATE`、`37 actionable tasks: 37 up-to-date`，APK 时间戳与哈希未变 |
| 与源码对应 | `:app:compileDebugKotlin UP-TO-DATE`（Gradle 按输入哈希判定，说明 dex 即当前源码编译产物）；APK 内 `classes.dex` 含 `getCursorRect` 符号；构建时间晚于源码最后修改 |
| 是否入库 | 否。`*.apk` 不入 Git，只经会话文件卡片交付 |

哈希取两次（间隔 2 秒），结果相同（`df5ecc29…2c8f67`）。未在设备上安装这份 APK（本轮只在检查阶段装过同一份构建产物），也未重跑 `connectedAndroidTest`。

## 6. 未覆盖

- 真机：全程只用指定 `emulator-5554`，真机零访问；系统输入法差异、真实触控精度未覆盖。
- 键盘弹出动画中间帧（约 200 ms）未验证。
- 数百行级超长正文未验证（本轮文本约 48 行 + 若干追加行）。
- 自动化 UI 回归仍然没有（Espresso 3.5.1 在 API 37 的 `InputManager.getInstance()` 反射问题未变）。
