# 手工按脚本语义安装：记录（v2 harness 因 CRLF 透传失败后执行）

时间：2026-11-23 19:37–19:41 CST
设备：emulator-5558 / AVD `NotesGO_V2_Dedicated` / API 37 / boot_completed=1
镜像：sdk_gphone64_x86_64（emu64xa）

## 为什么手工执行

`workbuddy_install_scrollbar_v2.sh` 首跑退出码 1，首错原文：

```text
Install failed: Performing Streamed Install
adb.exe: filename doesn't end .apk or .apex: D:\RECENT-NOTES-1-scope-v2\app\build\outputs\apk\debug\app-debug.apk
```

根因：脚本内嵌 python 用 `print(path)` 输出，Windows 下 stdout 把 `\n` 翻成 `\r\n`；
bash `mapfile -t` 按 `\n` 切分后每个元素尾部留下 `\r`，adbd 收到的参数变成 `...app-debug.apk\r`，
于是判定后缀不是 `.apk`。已用 `printf '%s' "${apks[0]}" | od -c` 复现末尾 `\r`。
脚本未做任何修改；Kotlin 未做任何修改。

## 手工步骤（严格照脚本语义）

1. 按 manifest 校验三项哈希：`scope_diff_sha256` / `app_sha256` / `test_sha256` → 全部 MATCH。
2. 核对设备身份（对应脚本 `check_avd`）：get-state=`device`、`emu avd name`=`NotesGO_V2_Dedicated`+`OK`、`sys.boot_completed=1`。
3. 安装前 `pm list packages | grep myapplication` 无匹配（包不存在，符合专用机前提）。
4. 应用 APK：`adb -s emulator-5558 install -r -t <app-debug.apk>` → `Success`（RC=0）。
5. 再核对身份，测试 APK：`install -r -t <app-debug-androidTest.apk>` → `Success`（RC=0）。

## 结果

```text
package:com.example.myapplicationfirstapp
package:com.example.myapplicationfirstapp.test
instrumentation:com.example.myapplicationfirstapp.test/androidx.test.runner.AndroidJUnitRunner (target=com.example.myapplicationfirstapp)
versionCode=2 versionName=2.0 minSdk=24 targetSdk=37
firstInstallTime=lastUpdateTime=2026-11-23 11:38:42 (UTC)
```

补充观察（不属于 harness 判据）：

- `am start -W` 冷启动后 `topResumedActivity=com.example.myapplicationfirstapp/.MainActivity`，`logcat -b crash` 为空。
- 全新 AVD 无笔记，首页显示空态「还没有笔记 / 点「新建笔记」写下第一条」，因此没有列表也就没有滚动条，
  限位改动在本轮无法用肉眼观察；需先有笔记才能看效果。
- 期间系统组件 Pixel Launcher 报过一次 ANR（`Pixel Launcher isn't responding`），与本应用无关，已点 Wait 关闭。

## 未做

- 未跑 `connectedDebugAndroidTest`（本轮 harness 明示 No tests were run）。
- 未创建笔记、未改设备数据。
- 未推送、未合并、未碰 main 与真机。
