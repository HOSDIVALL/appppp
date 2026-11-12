# 复跑命令（仅 emulator-5554）

所有命令都显式带 `-s emulator-5554`；本轮未向任何真机下发命令。
`MSYS_NO_PATHCONV=1` 只在 Git Bash 下需要，避免 `/sdcard/...` 被改写成 Windows 路径；改成 `D:/...` 形式的本地路径。

## 0. 素材

```bash
# 虚构图片（900x600，色块 + 文字）
python - <<'PY'
from PIL import Image, ImageDraw
W,H=900,600
img=Image.new("RGB",(W,H),(238,242,248)); d=ImageDraw.Draw(img)
for i,c in enumerate([(64,110,180),(240,170,70),(120,180,140),(200,110,120)]):
    d.rectangle([40+i*205,120,40+i*205+180,300],fill=c)
d.rectangle([40,340,860,540],outline=(60,60,60),width=4)
d.text((60,380),"FIXTURE IMAGE 01",fill=(20,20,20))
d.save("FIXTURE_IMG_01.png")
PY

# 8 秒测试音
ffmpeg -f lavfi -i "sine=frequency=440:duration=8" -af volume=0.25 -c:a aac -b:a 96k FIXTURE_AUDIO_01.m4a
```

## 1. 安装与投放素材

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell pm clear com.example.myapplicationfirstapp
adb -s emulator-5554 push FIXTURE_IMG_01.png /sdcard/Pictures/FIXTURE_IMG_01.png
adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d file:///sdcard/Pictures/FIXTURE_IMG_01.png
adb -s emulator-5554 push FIXTURE_AUDIO_01.m4a /data/local/tmp/FIXTURE_AUDIO_01.m4a
adb -s emulator-5554 shell run-as com.example.myapplicationfirstapp mkdir -p files/recordings
adb -s emulator-5554 shell run-as com.example.myapplicationfirstapp \
    cp /data/local/tmp/FIXTURE_AUDIO_01.m4a files/recordings/FIXTURE_AUDIO_01.m4a
adb -s emulator-5554 shell run-as com.example.myapplicationfirstapp \
    cp /data/local/tmp/FIXTURE_AUDIO_01.json files/recordings/FIXTURE_AUDIO_01.json
```

## 2. 造一条 5 块混排笔记（走应用 UI）

```bash
adb -s emulator-5554 shell am start -n com.example.myapplicationfirstapp/.MainActivity -W
adb -s emulator-5554 shell input tap 954 2221          # 新建笔记
adb -s emulator-5554 shell input text "alpha-A-block"
adb -s emulator-5554 shell input tap 199 870           # 添加图片
adb -s emulator-5554 shell input tap 474 1814          # 位置：插入正文
adb -s emulator-5554 shell input tap 150 2032          # 从相册选择
adb -s emulator-5554 shell input tap 177 1586          # 选中 FIXTURE_IMG_01
adb -s emulator-5554 shell input keyevent 4            # 收键盘
adb -s emulator-5554 shell input tap 540 1900          # 点图片下方空白 -> 聚焦新文字块
adb -s emulator-5554 shell input text "bravo-B-line"
adb -s emulator-5554 shell input keyevent 4
adb -s emulator-5554 shell input tap 492 870           # 插入录音
adb -s emulator-5554 shell input tap 464 1700          # 位置：插入正文
adb -s emulator-5554 shell input tap 768 2169          # 选中 FIXTURE-AUDIO-01 并插入
adb -s emulator-5554 shell input tap 540 2100          # 最后一块
adb -s emulator-5554 shell input text "charlie-C-tail"
adb -s emulator-5554 shell input keyevent 4
adb -s emulator-5554 shell input keyevent 4            # 回列表（触发保存）
adb -s emulator-5554 shell run-as com.example.myapplicationfirstapp cat files/notes.json
```

## 3. 观测手段

```bash
# 键盘状态
adb -s emulator-5554 shell dumpsys input_method | grep mInputShown
# 层级与焦点
adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml
adb -s emulator-5554 pull /sdcard/ui.xml ./ui.xml
# 应用真实落盘内容
adb -s emulator-5554 shell run-as com.example.myapplicationfirstapp cat files/notes.json
```

判据示例（均取自上面的 dump）：

- 图片：`mInputShown=false`，仅「关闭」节点。
- 录音：`content-desc` 在「播放录音 / 暂停录音」间切换；`SeekBar` 边界 `[69,2062][1011,2125]`；拖动后时间文本由 `00:01` 变 `00:06`。
- 光标：插入 1 字符后比对 `text`，下标即插入点（A `x=120`→4、`x=200`→7；B `x=150`→5）。
- 末尾追加：C `x=400` 以上点空白 → `charlie-C-tailTAIL`。
- 滚动：比较同一节点在两次 dump 里的 `bounds` 纵坐标（A：`281→934`、`281→442`）。
- 模式切换：`focused="true"` 节点计数（文本→清单后为 0）。
