# 限位候选构建 + 专用模拟器安装（scope-build-v2）

时间：2026-11-23 19:28–19:41 CST
基线：`5c688af4d30f01f8939319cad47b2393a8ebaa99`（detached）

## 复现步骤

```text
1) 新建分离工作树：git worktree add --detach D:/RECENT-NOTES-1-scope-v2 5c688af4
2) 应用补丁（git -c core.autocrlf=false apply）：candidate-full.diff（229af49 取）
   12 files changed, 958 insertions(+), 513 deletions(-)；--check 干净
3) 放入 app/libs/sherpa-onnx-2.0.1.aar，sha256 a8d09d1d…f0ee7（与上一轮同一份）
4) bash build_scrollbar_candidate.sh <脚本绝对路径> /d/v2-scope-tools/apply_note_scrollbar_scope.py ./scrollbar-scope-build-evidence
5) bash workbuddy_install_scrollbar_v2.sh <adb 绝对路径> ./scrollbar-scope-build-evidence/build-manifest.json
```

## 构建结果

```text
status.txt                build_passed_no_install: exit 0
build-manifest.json       variant=note-scrollbar-scope, build_passed=true,
                          base=5c688af4…, scope_diff_sha256=1f9ce6e8…
app_sha256                440e6c48…7c3ae29   (57,485,832 B)
test_sha256               1c6c51a0…29660ba6   (1,254,265 B，与迁移前同值)
unit                      73 tests / 0 failures / 0 errors / 0 skipped
git diff --check          干净（仅两个滚动条文件）
```

单测分布见 `unit-summary.txt` 与 `unit-xml/`：`NotesHomeListModelTest` 24、`RecentNoteUsageTest` 11、
`NotesListScrollbarMathTest` 11，其余 5 类合计 27。三支 Gradle 各自 `BUILD SUCCESSFUL`，日志为
`unit.log` / `app.log` / `androidTest.log`。

## 限位改动（after.diff）

- `NotesListScreen.kt`：新增 `noteTrackBounds`（`derivedStateOf`，遍历 `layoutInfo.visibleItemsInfo`
  中属于笔记项的索引，取相对视口的上下沿），把 `trackTopPx` / `trackBottomPx` 传给滚动条。
- `NotesListScrollbar.kt`：新增 `trackTopPx: Float = 0f`、`trackBottomPx: Float = Float.NaN`；
  `trackBottomPx.isNaN()` 时回退整轨（保留旧行为），否则把轨道夹在 `[resolvedTopPx, fullHeightPx]`。

## 安装：harness 失败与处置

harness 首跑退出 1，首错原文：

```text
Install failed: Performing Streamed Install
adb.exe: filename doesn't end .apk or .apex: D:\RECENT-NOTES-1-scope-v2\app\build\outputs\apk\debug\app-debug.apk
```

根因（已取证，非 APK 问题）：脚本内嵌 python 用 `print(path)` 输出，Windows 下 stdout 将 `\n` 转为
`\r\n`；bash `mapfile -t` 按 `\n` 切分，元素尾部保留 `\r`，adbd 收到的参数是 `…app-debug.apk\r`，
后缀判定失败。复现命令：

```text
printf '%s' "${apks[0]}" | od -c | tail -2   # 末尾出现 \r
```

修法二选一：python 侧 `sys.stdout.reconfigure(newline="\n")`，或 bash 侧 `paths=$(… | tr -d '\r')`。
**脚本本体未做任何修改**，Kotlin 未做任何修改。

处置：经委托方确认，按同一脚本语义手工安装（同一 manifest、同一哈希、同样 `install -r -t`）：

```text
三项哈希             scope_diff / app / test 全部 MATCH
设备身份             emulator-5558 state=device；emu avd name=NotesGO_V2_Dedicated+OK；boot_completed=1
安装前包名           pm list packages | grep myapplication → 无匹配
app APK              install -r -t → Success (RC=0)
test APK             install -r -t → Success (RC=0)
```

结果见 `install/device.txt`：两个包均在，instrumentation 已注册，`versionName=2.0 / versionCode=2`。

## 现场观察（非 harness 判据）

- 冷启动后 `topResumedActivity=com.example.myapplicationfirstapp/.MainActivity`，`logcat -b crash` 为空。
- 全新 AVD 无笔记，首页为空态「还没有笔记 / 点「新建笔记」写下第一条」；无列表也就无滚动条，
  **限位效果本轮无法肉眼验收**，需先有笔记数据。截图见 `install/01…03`。
- 期间系统组件 Pixel Launcher 报过一次 ANR，与本应用无关，已点 Wait 关闭。

## 未做

- 未跑 `connectedDebugAndroidTest`（harness 明示 No tests were run）。
- 未创建笔记、未改设备数据、未上传 APK（体积原因，只给哈希与绝对路径）。
- 未推送特性分支、未合并、未碰 main 与真机。
