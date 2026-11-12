# DAILY-UX-2 手工验证记录（模拟器，可复跑）

环境：`Medium_Phone_API_37.0`（`emulator-5554`），API 37，1080×2400 @420dpi，arm64。
设备一律用 `-s emulator-5554` 显式指定。真机未参与本轮任何步骤。
内容全部为虚构文本，未读取任何既有笔记/图片/录音。

## 0. 构建与安装

```
export JAVA_HOME=<Android Studio JBR>
./gradlew :app:assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -W -n com.example.myapplicationfirstapp/.MainActivity
```

第一版 APK 由基线 `ef4d98c` 构建（复现用），第二版为改动后构建（验证用）。
两次构建使用同一 AVD、同一套虚构内容。

## 1. 取值方法

```
# 键盘是否弹出
adb -s emulator-5554 shell dumpsys input_method | grep mInputShown

# 焦点 / 边界 / 文本
adb -s emulator-5554 shell uiautomator dump /sdcard/x.xml
adb -s emulator-5554 exec-out cat /sdcard/x.xml > x.xml
# 解析 node 的 class=...EditText 的 focused / bounds / text
```

判定只认上面两类输出，截图仅作旁证。

## 2. 修改前（基线 ef4d98c）

| 步骤 | 命令要点 | 观测 |
|---|---|---|
| 新建笔记 | 列表页点「+」（`content-desc="新建笔记"`，bounds `[917,2184][991,2258]`） | `mInputShown=true`（UX-1 行为） |
| 输入三行 | `input text` + `keyevent 66` ×2 | 正文 `alpha-le-one / beta-line-tw / gama-ine-thre`，39 字 |
| 收键盘 | `input keyevent 4` | `mInputShown=false`，正文块仍 `focused=true` |
| **点空白** | `input tap 540 1500` | **`mInputShown=false`**（键盘不回来） |
| 打开已有笔记 | 列表点卡片 → `mInputShown=false`，无焦点 | 正常不抢焦点 |
| **点空白（已有）** | `input tap 540 1500` | **`mInputShown=false`** |
| 点正文文字 | `input tap 540 1000` | `mInputShown=true`，焦点落该块 |

正文块实测边界：`[42,970][1038,1149]`。空白点击区域整片无响应。

## 3. 修改后

| # | 步骤 | 观测 |
|---|---|---|
| 1 | 打开已有笔记 | `mInputShown=false`，`focused=false`（未抢焦点） |
| 2 | 点空白 `(540,1500)` | `mInputShown=true`，正文块 `focused=true` |
| 3 | 点正文第一行中部 `(200,995)` 后输入 `MM` | 正文变 `alpha-leMM-one`（原有定位未被改） |
| 4 | 收键盘后点空白，输入 `ZZ` | 正文变 `gama-ine-threZZ`（落到末尾），43 字，仍 3 行、无新块 |
| 5 | 新建笔记：点「+」→ 输入 `one` → 收键盘 → 点空白 `(540,1400)` → 输入 `two` | `mInputShown` false→true，正文 `onetwo`，6 字 |
| 6 | 切「清单」→ 点空白 `(540,1400)` | `mInputShown=false`（未被接管） |
| 7 | 点清单条目 `(400,1100)` | `mInputShown=true`，该条 `focused=true` |
| 8 | 点「添加图片」`(199,869)` | 弹层出现：`从相册选择 / 拍照 / 作为附件 / 插入正文` |
| 9 | 长内容（20+ 行）拖动空白 | 正文块 y 231→168，`mInputShown=false`（不误触） |
| 10 | 长内容滚到底点末尾空白 `(540,2260)` → 输入 `TAIL` | `mInputShown=true`，正文尾部 `extra3TAIL` |

## 4. 测试

```
export JAVA_HOME=<Android Studio JBR>
export ANDROID_SERIAL=emulator-5554
./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

结果 XML：`test-results/`。单测 9/0/0/0；仪器化 87/0/0/0（`testcase` 计数 87）。
`gradle-tests.log` 里 `Running tests on devices: Medium_Phone_API_37.0(AVD) - 17` 确认只跑了模拟器。

## 5. 备注（踩到的环境问题，供复跑参考）

- 该 AVD 首次冷启动后出现过 `dumpsys` 超时、截图冻结（画面不刷新），
  用 `emu kill` + `-wipe-data -no-snapshot -gpu swiftshader_indirect` 重启后正常。
  这是模拟器自身状态问题，与本次改动无关。
- 本机 `am force-stop` 会被环境拦截（命令被 SIGTERM），改用 `am start -W` 直接拉起，不影响判定。
- `uiautomator dump` 必须 `MSYS_NO_PATHCONV=1` 且用 `exec-out cat` 取回；否则 `/sdcard/...` 会被路径转换。
