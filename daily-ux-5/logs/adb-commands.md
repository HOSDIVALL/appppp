# DAILY-UX-5 命令记录（已脱敏）

`$ADB` = `platform-tools/adb`（本机绝对路径已替换）。`$PKG` = `com.example.myapplicationfirstapp`。
所有命令只作用于 `emulator-5554`；真机零访问。

## 0. 工作区与构建

```sh
git -C <repo> worktree add <ws> -b ux/daily-ux-5 ede4f75fb25bc34a78d5acc89a863f1f45199e22
cp <prev-ws>/local.properties <ws>/local.properties          # 本机 SDK 路径，不入库
cp <prev-ws>/app/libs/sherpa-onnx-2.0.1.aar <ws>/app/libs/   # 本地 AAR，不入库
cd <ws> && JAVA_HOME=<jdk> ./gradlew assembleDebug
```

## 1. 注入虚构数据

```sh
python logs/ux5/make_notes.py                                  # 生成 notes.json（40 条）
$ADB -s emulator-5554 install -r -t <ws>/app/build/outputs/apk/debug/app-debug.apk
$ADB -s emulator-5554 shell pm clear $PKG
$ADB -s emulator-5554 push <ws>/logs/ux5/notes.json /data/local/tmp/ux5_notes.json
$ADB -s emulator-5554 shell "run-as $PKG mkdir -p files/attachments"
$ADB -s emulator-5554 shell "run-as $PKG sh -c 'cat /data/local/tmp/ux5_notes.json > files/notes.json'"
$ADB -s emulator-5554 shell am start -W -n $PKG/.MainActivity
```

注：`files/` 目录在 `pm clear` 后不存在，必须先 `mkdir -p`，否则 `cat >` 会报
`can't create files/notes.json`。`adb push` 的本地路径要写 Windows 形式（`D:/...`）并
`MSYS_NO_PATHCONV=1`，否则 MSYS 会把 `/data/...` 改写成盘符路径。

## 2. 取证工具

```sh
python logs/ux5/probe.py dump  <name>.xml    # uiautomator dump → 本地
python logs/ux5/probe.py list  <name>.xml    # 可见 UX5- 项 + bounds + 顶部/底部项
python logs/ux5/probe.py ime                 # mInputShown + IME 可见区顶边
python logs/ux5/probe.py shot  <name>.png    # 截图
```

`probe.py` 通过环境变量取 adb：`ADB=C:/.../platform-tools/adb.exe SERIAL=emulator-5554`。
（Python 是 Windows 原生解释器，传 MSYS 风格 `/c/...` 会 `WinError 2`。）

## 3. 路径 A：完整列表

```sh
# 慢速滚动（900 ms，避免 fling 直接飞到尾部）
for i in 1 2 3; do $ADB -s emulator-5554 shell input swipe 540 1800 540 700 900; sleep 0.4; done
python probe.py dump a-r1-before.xml && python probe.py list a-r1-before.xml
$ADB -s emulator-5554 shell input tap 540 <y>          # 点某条
$ADB -s emulator-5554 shell input keyevent 4           # 系统返回
python probe.py dump a-r1-after.xml
diff <(python probe.py list a-r1-before.xml | grep bounds) \
     <(python probe.py list a-r1-after.xml | grep bounds)
```

## 4. 路径 B / C：搜索

```sh
$ADB -s emulator-5554 shell input tap 300 456           # 点搜索框
$ADB -s emulator-5554 shell input text "LIME7"         # 只能注入 ASCII
$ADB -s emulator-5554 shell input keyevent 4           # 先收键盘（query 与结果保留）
$ADB -s emulator-5554 shell input swipe 540 1900 540 1100 900   # 仅在无键盘时滚动列表
python probe.py dump c-r1-before.xml
$ADB -s emulator-5554 shell input tap 540 <y>          # 点结果
$ADB -s emulator-5554 shell input keyevent 4           # 系统返回（键盘已收 → 直接退出编辑页）
python probe.py dump c-r1-after.xml
```

编辑页左上角返回箭头：`content-desc="返回"`，dump 得 bounds `[43,116][106,179]`，点 `(74,147)`。

## 5. 路径 D：清空

```sh
# 清空按钮 content-desc="清除"，位置随面板展开变化，每次按 dump 现算
$ADB -s emulator-5554 shell input tap <x> <y>
python probe.py dump d-r1-after.xml   # 期望 共 40 条 + 占位符 搜索笔记 + 无 LIME7
```

## 6. 校验「查看未写数据」

```sh
$ADB -s emulator-5554 shell "run-as $PKG cat files/notes.json" > after-notes.json
# 深比较：40 条全字段相同、updatedAt 全未变；仅换行被重写（CRLF → \r\r\n）
```
