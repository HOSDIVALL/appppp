# DAILY-UX-6 · 固化交付

交付日期：2026-11-13（UTC+8）
评审结论：UX-6 已通过，本轮只做固化交付，不扩功能、不改源码。

## 一、源码

| 项 | 值 |
|---|---|
| 基线 | `ede4f75fb25bc34a78d5acc89a863f1f45199e22`（DAILY-UX-4 固化提交） |
| 固化提交 | `7f141485b57b87fcbd60a4ef98ceeb2420f4bbd8` |
| 父提交 | `ede4f75`（快进，无 merge） |
| 分支 | `ux/daily-ux-6`（已推送，远端同名分支指向同一 SHA） |
| 改动 | 单文件 `app/src/main/java/com/example/myapplicationfirstapp/ui/NotesListScreen.kt`，**+13 / −3** |
| 文件 blob | `a4d9542baa3dd193aa3ed955bae2a5203ebc828d` |
| 文件字节 | 37,104 B |

### 固化前四项核对（全部通过后才提交）

| 核对项 | 期望 | 实测 |
|---|---|---|
| 工作区 HEAD | `ede4f75` | `ede4f75fb25bc34a78d5acc89a863f1f45199e22` |
| 改动范围 | 仅 `NotesListScreen.kt` | `git status --short` 仅一行 ` M .../NotesListScreen.kt` |
| 改动统计 | +13 / −3 | `1 file changed, 13 insertions(+), 3 deletions(-)` |
| 与评审副本一致 | blob `a4d9542…` | `git hash-object` 工作区文件 = `a4d9542baa3dd193aa3ed955bae2a5203ebc828d`，与 `daily-ux-6/files/NotesListScreen.kt` 相同 |

## 二、调试 APK

| 项 | 值 |
|---|---|
| 文件 | `app-debug-daily-ux-6-7f14148.apk`（由 `app/build/outputs/apk/debug/app-debug.apk` 改名） |
| 大小 | **57,387,528 字节**（54.73 MiB） |
| SHA-256 | `3f7d9a1ceb1034e7c3a7b6fec7ef602bc7018835a1af9bc72900981fbb342a57` |
| 构建命令 | `JAVA_HOME=D:/Android/jbr ./gradlew assembleDebug --offline` |
| 构建结果 | `BUILD SUCCESSFUL`，37 tasks **all up-to-date**（输入与检查轮那次构建一致，未重新编译） |
| 产物时间 | APK mtime `2026-11-12 23:46:55` > 源码 mtime `2026-11-12 23:45:31` |
| 含本次改动 | 解包后 `classes7.dex`、`classes8.dex` 均命中符号 `openNote`（`grep -ac` 各 2 处）；`classes.dex` 等其余 dex 为 0 |

> 说明：`assembleDebug` 报 all up-to-date，表示 Gradle 按输入哈希判定产物已对应当前源码（工作区在提交 `7f14148` 时干净）。`openNote` 符号检查用于排除「交付了旧包」这一情形。

### 交付方式（沿用既定 Release 直链）

| 项 | 值 |
|---|---|
| Tag | `daily-ux-6-apk`（prerelease，指向 `7f141485…`） |
| Release id | `443302536` |
| Asset id | `740444275` |
| 直链 | `https://github.com/HOSDIVALL/appppp/releases/download/daily-ux-6-apk/app-debug-daily-ux-6-7f14148.apk` |
| 平台侧 digest | `sha256:3f7d9a1ceb1034e7c3a7b6fec7ef602bc7018835a1af9bc72900981fbb342a57`（与本地一致） |
| 平台侧 size | 57,387,528（与本地一致） |

下载回验：用上述直链完整下载一次，`curl_rc=0`，耗时 80 s，得到 57,387,528 B，`sha256sum` 与本地产物**完全相同**。

## 三、未重跑单测（登记）

- 本轮**未重跑** `testDebugUnitTest`。
- `test-xml/` 里仍是检查轮那一次运行的原始 XML：3 suite / 9 条 / failures 0 / errors 0 / skipped 0。
- 依据：本轮没有改动测试代码，也没有改动产品代码（只做提交、构建与文档更正）；按「不为文档更正或交付重新跑测试」的口径处理。
- 这 9 条只覆盖 `shouldAutoFocusBody` / `shouldFocusBodyOnBlankTap` 等策略函数，不构成焦点或键盘的 UI 验证。

## 四、本轮口径更正（两处，均不涉及源码）

1. **material3 1.4.0 源码降为参考。** 原 INDEX 用「`SearchBarDefaults.InputField` 只在获焦时回调 `onExpandedChange(true)`」作为「清焦点不会反向关面板」的依据。现改为：源码阅读仅作参考，**未核对安装包实际链接的 material3 构件版本与字节，就不作实现保证**；该结论只以设备实测为据（A/B/C 各 3 次一致）。
2. **D 项改为「正文块内点击」。** D 场景两次点击都在正文块边界以内（第一行、块底 −24），**不代表「点正文块外空白」的回归**；块外留白属 UX-2 范围，本轮未覆盖。

## 五、边界

- 未合并 `main`（仍 `61dff28`）、未建 PR；`fix/backup-safe-1` = `26a0de3`、`ux/daily-ux-1/2/4` 均未变动。
- 未安装到真机；未跑 `connectedDebugAndroidTest`；未操作真机。
- 未改数据模型、保存、排序、搜索匹配规则、`BackHandler`；无新依赖、无导航框架。
- 证据分支不含 APK、AAR、配置文件或真实数据；APK 只在 Release 资产中。
- 文档分支的 README 更正与本次源码提交**分开提交**，互不混合。
