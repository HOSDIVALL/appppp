# DAILY-UX-6 · 命令记录（已脱敏）

设备固定 `emulator-5554`（API 37，1080×2400 @420dpi）。真机零访问。
`<sdk>` = 本机 Android SDK 目录；`<workspace>` = 本次临时取证目录（截图 / dump / 运行日志的落地处）。
本轮全部内容为虚构笔记，未读取真实数据、备份或恢复材料。

## 0. 环境与基线

```bash
adb devices                                  # 只有 emulator-5554
adb -s emulator-5554 shell getprop ro.build.version.sdk   # 37
adb -s emulator-5554 shell pm list packages | grep myapp
adb -s emulator-5554 shell "run-as com.example.myapplicationfirstapp ls -l files/"
adb -s emulator-5554 shell "run-as com.example.myapplicationfirstapp md5sum files/notes.json"
# 0315e59c3a4836b94d24706e4b7d8c29  files/notes.json   （40 条虚构笔记，18 条含 LIME7）
```

装包一律 `install -r`（不卸载，保数据）：

```bash
adb -s emulator-5554 install -r "<workspace>/app-debug.apk"
adb -s emulator-5554 shell "run-as com.example.myapplicationfirstapp md5sum files/notes.json"
```

冷启动用 `am start -S`（本机 `am force-stop` 会被环境拦截）：

```bash
adb -s emulator-5554 shell am start -S -W -n com.example.myapplicationfirstapp/.MainActivity
```

## 1. 复现（基线 ede4f75，装的是该提交的调试包）

```bash
adb -s emulator-5554 shell input tap 535 593        # 点搜索框
adb -s emulator-5554 shell input text LIME7         # 关键词（CJK 注入不了，故用 ASCII）
adb -s emulator-5554 shell uiautomator dump /sdcard/u.xml
adb -s emulator-5554 exec-out cat /sdcard/u.xml > <workspace>/dumps/01-search.xml
adb -s emulator-5554 shell dumpsys input_method | grep -E "mInputShown=|mImeWindowVis="
adb -s emulator-5554 shell dumpsys window | grep -A3 "type=ime"

adb -s emulator-5554 shell input tap 540 1145       # 点第一条结果 UX5-01
adb -s emulator-5554 shell uiautomator dump /sdcard/u.xml
adb -s emulator-5554 exec-out cat /sdcard/u.xml > <workspace>/dumps/02-open-r1.xml
adb -s emulator-5554 shell dumpsys input_method | grep -E "mInputShown=|mImeWindowVis="

# 焦点归属判定：编辑页里没有输入框获焦时，打一个字符看它落到哪
adb -s emulator-5554 shell input text Q
adb -s emulator-5554 shell input keyevent KEYCODE_BACK     # 第 1 次：只收键盘
adb -s emulator-5554 shell uiautomator dump /sdcard/u.xml  # -> 03-focus-probe.xml（仍在编辑页）
adb -s emulator-5554 shell input keyevent KEYCODE_BACK     # 第 2 次：离开编辑页
adb -s emulator-5554 shell uiautomator dump /sdcard/u.xml  # -> 04-back-to-list.xml（query=LIME7Q，0 条）
```

## 2. 修复后 A–F（每路径 3 次，脚本 `logs/run.py`）

一次 A 轮的完整序列（B 紧接着同一轮）：

```bash
am start -S -W -n <pkg>/.MainActivity ; sleep 2.6
input tap 535 593 ; sleep 1.4
input text LIME7 ; sleep 1.6
uiautomator dump + dumpsys input_method      # a{n}-1-search
input tap <第一条结果的中心>                  # 只有 a3 先做一次 900ms 慢速滑动再点第二条
sleep 2.4 ; uiautomator dump + dumpsys input_method   # a{n}-3-editor
input keyevent KEYCODE_BACK ; sleep 2.0
uiautomator dump + dumpsys input_method      # a{n}-4-back
```

C：搜索后先 `KEYCODE_BACK` 收键盘（保留搜索态），再点结果。
D：点正文第一行 → `input text Z` → `KEYCODE_DEL`；点正文块末尾空白 → `input text Y` → `KEYCODE_DEL`；两次返回。
E：点导航栏 `+`（954,2221）→ 新笔记，检查焦点与键盘；两次返回（空白新笔记应被丢弃）。
F：普通列表点第二条 → 检查键盘与焦点；一次返回。

## 3. 观察命令

```bash
# IME 是否显示 / IME 窗口顶边（键盘屏占判据）
adb -s emulator-5554 shell dumpsys input_method | grep -E "mInputShown=|mImeWindowVis="
adb -s emulator-5554 shell dumpsys window | grep "type=ime"

# 控件树：text / class / bounds / focused
adb -s emulator-5554 shell uiautomator dump /sdcard/u.xml
adb -s emulator-5554 exec-out cat /sdcard/u.xml > <workspace>/dumps/<name>.xml

# 截图（设备端连拍，host 端逐张取回）
adb -s emulator-5554 shell "for i in 1 2 3 4 5; do screencap -p /sdcard/g\$i.png; done"
adb -s emulator-5554 exec-out cat /sdcard/g1.png > <workspace>/shots/tr3-1.png

# 数据未被污染
adb -s emulator-5554 exec-out run-as <pkg> cat files/notes.json > <workspace>/logs/notes-after.json
```

注意：`adb shell uiautomator dump /sdcard/u.xml` 在 MSYS 下会被路径转换改写成 Windows 路径，
必须 `MSYS_NO_PATHCONV=1`；同一开关会让 `install -r` 的本地路径失效，所以本地路径一律写 `D:/...`。
