# 实际命令序列（脱敏）

工作区路径、设备序列号已去掉；设备固定 `emulator-5554`。

```sh
A="adb -s emulator-5554"

# 环境
$A install -r app-debug.apk
$A shell pm clear com.example.myapplicationfirstapp
$A shell am start -n com.example.myapplicationfirstapp/.MainActivity -W
$A shell settings put global window_animation_scale 0
$A shell settings put global transition_animation_scale 0
$A shell settings put global animator_duration_scale 0

# 通用观测
$A shell uiautomator dump /sdcard/ui.xml && $A pull /sdcard/ui.xml <out>.xml
$A exec-out screencap -p > <out>.png
$A shell dumpsys input_method | grep mInputShown
$A shell dumpsys window | grep mCurrentFocus
$A shell run-as com.example.myapplicationfirstapp cat files/notes.json

# 检查一：新建笔记，末字符后立即返回
$A shell input tap 954 2221
$A shell input tap 540 300
$A shell input text "T1-title"
$A shell input tap 540 1000
$A shell input text "para-one"
$A shell input keyevent 66
$A shell input keyevent 66
$A shell input text "para-two"
$A shell 'input text "Z"; sleep 0.3; input text "E"; sleep 0.3; input text "T"; sleep 0.3; \
  input text "A"; T1=$(date +%s%3N); input tap 74 147; T2=$(date +%s%3N); echo "$T1 $T2 $((T2-T1))"'

# 检查二：改标题 + 删一段 + 追加末尾
$A shell input tap 540 300
$A shell input keyevent 123
$A shell input text "v"
$A shell input text "2"
$A shell input tap 540 1120
$A shell input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67
$A shell 'input text "T"; sleep 0.25; input text "AI"; sleep 0.25; input text "L"; \
  T1=$(date +%s%3N); input tap 74 147; T2=$(date +%s%3N); echo "$T1 $T2 $((T2-T1))"'

# 检查三：系统返回
$A shell input tap 540 1120
$A shell input keyevent 4            # 第一次：收键盘
$A shell input keyevent 4            # 第二次：退出编辑页
$A shell 'input keyevent 4; sleep 0.06; input keyevent 4'   # 连按两次

# 检查四：三轮往返（每轮）
$A shell input tap 500 875
$A shell input tap 540 1120
$A shell 'input text "R"; sleep 0.2; input text "N"; T1=$(date +%s%3N); \
  input tap 74 147; T2=$(date +%s%3N); echo "$T1 $T2 $((T2-T1))"'
$A shell run-as com.example.myapplicationfirstapp cat files/notes.json

# 回闪抽样：返回后立即打开另一条，连拍 6 帧
$A shell 'rm -f /sdcard/c*.png; input tap 74 147; sleep 0.05; input tap 500 1075; \
  for i in 1 2 3 4 5 6; do screencap -p /sdcard/c$i.png; date +%s%3N; done'

# 附加：新笔记首字符后立即返回
$A shell input tap 954 2221
$A shell input tap 540 300; $A shell input text "T3"
$A shell input tap 540 1000
$A shell 'input text "x"; T1=$(date +%s%3N); input tap 74 147; T2=$(date +%s%3N); echo "$T1 $T2 $((T2-T1))"'
```

未使用：`am force-stop`、`pm uninstall`、`adb shell rm` 删除应用数据、任何真机命令。
