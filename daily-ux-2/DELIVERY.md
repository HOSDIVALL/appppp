# DAILY-UX-2 固化交付记录

- 日期：2026-11-12
- 任务：点击普通文本笔记正文下方空白区，即可继续输入正文（评审通过后固化）。
- 边界：不扩功能、不升级依赖、不改数据模型；未合并 `main`、未建 PR、未动 `fix/backup-safe-1`、未动 `ux/daily-ux-1`。

## 1. 源码

| 项 | 值 |
|---|---|
| 分支 | `ux/daily-ux-2` |
| 固化提交 | `04f400fec16dfdd01be9cb83296a9d83181c3353` |
| 父提交 | `ef4d98cef923247e2b4fc8bfe5d974d188272a02`（`ux/daily-ux-1` 固化提交） |
| 远端 head | `04f400fec16dfdd01be9cb83296a9d83181c3353` |
| 改动 | 2 文件，+152 / −7（`71+/7-` 在生产文件，`81+` 在新增测试） |

提交内只有这两个文件，不含 `data/txn`、`logs/`、`local.properties`、`*.aar`、`*.apk` 或任何构建产物。

| 文件 | blob |
|---|---|
| `app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt` | `22e1471cf0ae4da64f3ed61f36fcaaf75eba8827` |
| `app/src/test/java/com/example/myapplicationfirstapp/ui/NoteEditorBlankTapPolicyTest.kt` | `02959c2d4037f4de0321dd73111d31f75deaaf0d` |

## 2. 与评审证据的一致性

固化前逐项核对（不是"看起来一样"）：

| 检查 | 结果 |
|---|---|
| 工作区 HEAD 是否为 `ef4d98c` | 是 |
| 改动是否仅两个文件 | 是（`git status --porcelain` 只有 1 个 `M` + 1 个 `??` 源码文件；其余 `??` 是本轮模拟器日志，未暂存） |
| 与 `daily-ux-2/source/` 逐字节比对 | 一致（`cmp` 无差异；两文件 SHA-256 相同：`e5e51a5e…2c02e9`、`6ca9d532…24ba0`） |
| 暂存后的 `git diff --cached` 与 `daily-ux-2/diff/daily-ux-2.diff` | 逐行相同（`diff` 无输出，252 行） |
| 暂存内容是否含违禁项 | 无（`data/txn` / `logs/` / `local.properties` / `*.aar` / `*.apk` / `build/` 全未命中） |

即：**评审的就是固化的**。补证轮（`d8e4188` / `762254d`）没有动源码，因此评审补丁在固化时没有变化。

## 3. 测试

- 单测 9 项（0 失败 / 0 错误 / 0 跳过），含本轮新增 `NoteEditorBlankTapPolicyTest` 5 例：`test-results/unit-*.xml`。
- 仪器化 87 项（0 失败 / 0 错误 / 0 跳过）：`test-results/full-androidtest.xml`（blob `f0839f5`）。
- 混排内容模拟器回归（7 项，真机零访问）：`supplement/SUPPLEMENT.md`、`supplement/logs/adb-commands.md`。

**固化这一轮未重跑测试**：只做了 `assembleDebug` 构建与一致性核对，没有执行 `testDebugUnitTest` / `connectedDebugAndroidTest`。上面 9/87 仍是 `bf240c4` 记录的原始结果，补证轮与本轮都没有重跑，**不得**读作"固化后重新测试通过"。

## 4. 调试 APK

| 项 | 值 |
|---|---|
| 路径 | `app/build/outputs/apk/debug/app-debug.apk`（工作区 `/d/SAVE-ORDER-UX-2-wt`，未入库） |
| 大小 | 57,903,885 字节 |
| SHA-256 | `705bb80f3621b2ba9fdae7492c5c76d9471b8190f8bbcdabf33423893987feca` |
| 构建时间 | 2026-11-12 20:44:50（源码最后修改 20:43:30） |
| 获取方式 | `JAVA_HOME=D:/Android/jbr ./gradlew assembleDebug --offline`（工作区根目录）；固化后再次执行同一命令，Gradle 报 `:app:assembleDebug UP-TO-DATE`，37 个任务全部 up-to-date、APK 时间戳与哈希未变 |
| 与源码对应 | APK 内 `classes7.dex` 含 `shouldFocusBodyOnBlankTap` 符号；构建晚于两文件最后修改 |
| 是否入库 | 否。`*.apk` 不入 Git，只经会话文件卡片交付 |

哈希取两次，间隔 2 秒，结果相同（`705bb80f…87feca`）。未在设备上安装这份 APK，也未重跑 `connectedDebugAndroidTest`。

## 5. 未覆盖

- 真机：本轮及补证轮均只按指定使用 `emulator-5554`，真机零访问；系统输入法差异、真实触控精度、刘海/手势条适配未覆盖。
- 自动化 UI 回归仍然没有（Espresso 3.5.1 在 API 37 的 `InputManager.getInstance()` 反射问题未变）。
- `supplement/SUPPLEMENT.md` §4 记录的一次冷启动 ANR：原因未确定，未证明由本改动引起，也未排除关联；未为此重跑。
