# DAILY-UX-4 操作命令（已脱敏）

约定：`$ADB` = `<sdk>/platform-tools/adb`，所有命令都带 `-s emulator-5554`，真机零访问。
Git Bash 下需要 `export MSYS_NO_PATHCONV=1`，否则 `/data/local/tmp/...` 会被改写成 Windows 路径。
本地工作区记为 `<workspace>`，本轮为 `ux/daily-ux-4` 的独立工作区。

## 1. 准备

```sh
git -C <repo> worktree add <workspace> -b ux/daily-ux-4 04f400fec16dfdd01be9cb83296a9d83181c3353
cd <workspace>
./gradlew assembleDebug
$ADB install -r -t app/build/outputs/apk/debug/app-debug.apk
```

`app/libs/sherpa-onnx-2.0.1.aar` 不入库，需从相邻工作区拷入后再构建。

## 2. 注入虚构数据

```sh
$ADB shell pm clear com.example.myapplicationfirstapp
$ADB push <workspace>/logs/ux4/notes.json                 /data/local/tmp/ux4_notes.json
$ADB push <workspace>/logs/ux4/ux4_img1.png               /data/local/tmp/ux4_img1.png
$ADB push <workspace>/logs/ux4/ux4_img2.png               /data/local/tmp/ux4_img2.png
$ADB shell "run-as com.example.myapplicationfirstapp mkdir -p files/attachments"
$ADB shell "run-as com.example.myapplicationfirstapp sh -c 'cat /data/local/tmp/ux4_notes.json > files/notes.json'"
$ADB shell "run-as com.example.myapplicationfirstapp sh -c 'cat /data/local/tmp/ux4_img1.png > files/attachments/ux4_img1.png'"
$ADB shell "run-as com.example.myapplicationfirstapp sh -c 'cat /data/local/tmp/ux4_img2.png > files/attachments/ux4_img2.png'"
$ADB shell am start -W -n com.example.myapplicationfirstapp/.MainActivity
```

素材生成见 `fixture/make_notes.py` 与 `fixture/make_images.py`（全部为编造文本与自绘 PNG）。

## 3. 取证

```sh
# 键盘上边界（visibleFrame 顶边）与 mInputShown
python logs/probe.py ime

# 光标矩形：连拍 3–4 帧，相邻帧做差，只保留窄而高的差异块
python logs/probe.py caret a3-03 4

# 单张截图 / UI 层级
python logs/probe.py shot a3-03-after-blanktap.png
python logs/probe.py dump a3-03.xml
```

`probe.py` 只依赖 `adb` 与 Pillow；`ADB` 取环境变量，缺省用 PATH 上的 `adb`。

## 4. 关键动作（对应 CHECKLOG）

```sh
# 滚到末尾
for i in 1 2 3 4 5 6; do $ADB shell input swipe 540 1900 540 500 200; sleep 0.3; done

# 点正文下方空白（UX-2 路径）
$ADB shell input tap 700 2250

# 末尾换行 + 输入
$ADB shell input keyevent 66; $ADB shell input text "ux4-41"

# 收起键盘：点 Gboard 左下角的收起箭头（键盘可见时约 (115,2335)）
$ADB shell input tap 115 2335

# 键盘显示时上滑阅读
for i in 1 2 3; do $ADB shell input swipe 540 1200 540 550 220; sleep 0.35; done
```

`input keyevent 4` 在键盘显示时只收键盘，再按一次才离开编辑页（BackHandler → 保存并返回）。

## 5. 单元测试

```sh
./gradlew testDebugUnitTest
```

结果见 `logs/unit-test.txt`：3 个 suite / 9 条 / 0 失败。这些是策略函数单测，**不**代表滚动行为已验证。
