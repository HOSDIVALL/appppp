# 逐项检查记录（DAILY-UX-3）

设备：`emulator-5554`（API 37，1080×2400 @420dpi）。所有命令均带 `-s emulator-5554`，真机零访问。
动画缩放置 0（`window/transition/animator` 三项均为 0），减少过渡时长对本轮判断的干扰。

坐标（1080×2400 实测）：列表页「新建笔记」`(954,2221)`；编辑页返回箭头 `(74,147)`；标题输入区 `(540,300)`；正文区 `(540,1000)`；正文末尾空白 `(540,1120)`；列表第 1 行 `(500,875)`、第 2 行 `(500,1075)`。

---

## 0. 准备

```sh
adb -s emulator-5554 install -r app-debug.apk      # 由 04f400f 构建（assembleDebug，57,387,528 B）
adb -s emulator-5554 shell pm clear com.example.myapplicationfirstapp
adb -s emulator-5554 shell am start -n com.example.myapplicationfirstapp/.MainActivity -W
```

观测：空列表 `共 0 条`（`screenshots/01-list-empty.png`、`ui-dump` 里的 `00-launch`）。

进入编辑页后（`screenshots/02-editor-opened.png`）：

- 标题 EditText `[42,231][1038,394]`，正文 EditText `[42,938][1064]`（后续内容变化后边界随行数变化）；
- 返回箭头 `desc='返回' [43,116][106,179]`；`mInputShown=true`（UX-1 自动聚焦仍在，正文块 `focused=true`）。

> 披露：实际做过 2 次 `pm clear`。第一次之后输入时 `adb input text` 丢字导致内容混乱，重来一次；此后到检查结束未再清数据。

---

## 1. 检查一：新建笔记，最后输入后 46 ms 返回

输入（每段之间留 0.4–0.6 s，避免注入丢字）：

```sh
adb -s emulator-5554 shell input tap 540 300;  adb -s emulator-5554 shell input text "T1-title"
adb -s emulator-5554 shell input tap 540 1000; adb -s emulator-5554 shell input text "para-one"
adb -s emulator-5554 shell input keyevent 66; adb -s emulator-5554 shell input keyevent 66
adb -s emulator-5554 shell input text "para-two"
```

关窗（单条 shell，取设备时间戳）：

```sh
adb -s emulator-5554 shell 'input text "Z"; sleep 0.3; input text "E"; sleep 0.3; \
  input text "T"; sleep 0.3; input text "A"; T1=$(date +%s%3N); input tap 74 147; \
  T2=$(date +%s%3N); echo "T1=$T1 T2=$T2 间隔=$((T2-T1))ms"'
```

观测：

| 观测点 | 值 |
|---|---|
| 返回前正文（`ui-dump/02-typed-before-back.xml`） | `parae\n\nparatwo` |
| 间隔 | `T1=1794491402839`、`T2=1794491402885`、**46 ms** |
| 返回后列表（`screenshots/03`、`ui-dump/05` 同批） | `共 1 条`、`T1-title`、`今天 13:50` |
| `notes.json`（`notes/01`） | `title='T1-title'`、`content='parae\n\nparatwoZETA'`、`updatedAt=1794491402874` |
| 重开（`ui-dump/03-reopen-after-new.xml`） | 标题 `T1-title`、正文 `parae\n\nparatwoZETA`，`focused=false`、`mInputShown=false` |

判据：`updatedAt` 1794491402874 ∈ [T1, T2] → 落盘发生在返回点击的派发窗口内，比防抖（T1+500 ms）早约 465 ms。
结论：**末尾 `ZETA` 未丢失；新建笔记未被误删；重开一致。**

---

## 2. 检查二：已有笔记，改标题 + 删一段 + 追加末尾，34 ms 返回

```sh
# 标题追加 v2
adb -s emulator-5554 shell input tap 540 300; adb -s emulator-5554 shell input keyevent 123
adb -s emulator-5554 shell input text "v"; adb -s emulator-5554 shell input text "2"
# 正文末尾退格 13 次：删掉第二段 paratwoZETA 与中间两个换行
adb -s emulator-5554 shell input tap 540 1120
adb -s emulator-5554 shell input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67
# 追加并立即返回
adb -s emulator-5554 shell 'input text "T"; sleep 0.25; input text "AI"; sleep 0.25; \
  input text "L"; T1=$(date +%s%3N); input tap 74 147; T2=$(date +%s%3N); \
  echo "T1=$T1 T2=$T2 间隔=$((T2-T1))ms"'
```

观测：

| 观测点 | 值 |
|---|---|
| 删除后、追加前（`ui-dump/04-after-delete-before-back.xml`） | 正文 `parae`（第二段已消失） |
| 间隔 | `1794491471026`、`1794491471060`、**34 ms** |
| 列表（`screenshots/04`、`ui-dump/05-list-after-edit-back.xml`） | `T1-titlev2`、`今天 13:51`、`共 1 条` |
| `notes.json`（`notes/02`） | `title='T1-titlev2'`、`content='paraeTAIL'`、`updatedAt=1794491471050` |

结论：**删除未回滚，标题与末尾追加均落盘**；列表标题同步更新。
（若存在「旧快照在返回时回写」，这里会看到 `paratwoZETA` 复活或标题停在 `T1-title`，两者都没有发生。）

---

## 3. 检查三：键盘显示时的系统返回

```sh
adb -s emulator-5554 shell input tap 540 1120     # 聚焦正文，键盘弹出
adb -s emulator-5554 shell dumpsys input_method | grep mInputShown   # true
adb -s emulator-5554 shell input keyevent 4       # 第一次系统返回
```

| 动作 | `mInputShown` | 界面 | 结论 |
|---|---|---|---|
| 第一次返回 | **false** | 仍在编辑页，`T1-titlev2` / `paraeTAIL`（`ui-dump/06`） | 只收键盘 |
| 第二次返回 | false | 列表 `共 1 条`（`ui-dump/07`） | 退出编辑页 |
| 键盘显示时连续两次返回，间隔 60 ms | false | 列表 `共 2 条`（`ui-dump/09`） | 两次足够，未吞键 |
| 键盘显示时点返回箭头 `(74,147)` | false | 直接回列表 | 正常 |

结论：**没有异常的重复返回需求**；第一次返回被系统输入法消费属于标准行为，应用没有抢。

---

## 4. 检查四：三次「修改 → 返回 → 重开」

每轮：点列表第 1 行 → 点正文末尾 → 追加 2 字符 → 立即点返回 → 读 `notes.json` → 下一轮。

| 轮次 | T1 | T2 | 间隔 | `updatedAt` | `content` | 列表 |
|---|---|---|---|---|---|---|
| R1 | 1794491515590 | 1794491515650 | 60 ms | 1794491515623 | `paraeTAILR1` | `共 1 条`、`今天 13:51` |
| R2 | 1794491531635 | 1794491531695 | 60 ms | 1794491531686 | `paraeTAILR1R2` | `共 1 条` |
| R3 | 1794491536835 | 1794491536897 | 62 ms | 1794491536889 | `paraeTAILR1R2R3` | `共 1 条`、`今天 13:52` |

- `id` 三轮相同（`notes/03`、`04`、`05` 对比）→ 没有重复笔记。
- 标题在检查二后立刻变成 `T1-titlev2`；时间随分钟推进 `13:51 → 13:52` → 列表摘要（标题 + 修改时间）未停止更新。
- 回闪抽样：`back → 立即打开另一条`，连拍 6 帧（设备时刻 1794491692748 / …964 / …185 / …398 / …601 / …819，间隔约 200–220 ms）。第 1 帧为列表页，第 2 帧即为目标笔记且正文正确；第 2–6 帧字节数完全相同（77225 B）→ 画面稳定，未见旧内容。`screenshots/05-burst1-list.png`、`06-burst2-opened.png`。

---

## 5. 附加：新笔记首字符后 44 ms 返回

```sh
adb -s emulator-5554 shell input tap 954 2221          # 新建
adb -s emulator-5554 shell input tap 540 300; adb -s emulator-5554 shell input text "T3"
adb -s emulator-5554 shell input tap 540 1000
adb -s emulator-5554 shell 'input text "x"; T1=$(date +%s%3N); input tap 74 147; \
  T2=$(date +%s%3N); echo "间隔=$((T2-T1))ms"'
```

观测：间隔 44 ms；`notes/07` → `title='T3'`、`content='x'`、`updatedAt=1794491714146`（∈ [1794491714117, 1794491714161]）；
列表 `共 3 条`；重开 `ui-dump/11-t3-reopen.xml` → `T3` / `x`。

对照（同一台设备）：打开 T3 后不改任何内容直接返回，`notes.json` 的 md5 与返回前完全相同（`550494a2…`），说明无改动时不会产生多余写入。

---

## 6. 失败与重试（如实记录）

| 现象 | 原因判断 | 处理 |
|---|---|---|
| `input text "para-one"` 落成 `parae`；`"para-three"` 落成 `para-thre`；`"second"` 落成 `seond` | `adb shell input text` 逐字符注入过快，应用侧丢键 | 改为分段/逐字符输入；正文内容以屏幕与 `notes.json` 逐字符一致为准，不以输入意图为准 |
| 首次「清空正文」用 `keyevent 123` + 25 次退格，未完全清空，出现 `pa\npara-two\npara-threra-one…` 的混乱态 | 光标不在文末（该次点击落在了正文中间） | 直接 `pm clear` 重来；此后每次先点 `(540,1120)`（正文下方空白）确保光标在末尾，再用「探针字符 + dump」确认落点 |
| `screenrecord` 两次分别只录到 1.263 s / 1.656 s，`ffprobe` 报 `moov atom not found` 或时长不足 | 录屏进程随 `adb shell` 结束被终止；后台方式启动会在父 shell 退出时收到终止 | 放弃视频，改用 `screencap` 连拍 6 帧，并在 INDEX 里写明采样限制 |
| 一次「back → 300 ms → 打开另一条」实际打开了同一条笔记 | 返回后列表按 `updatedAt` 重排，第 1 行已变成刚编辑的那条，仍点 `(500,1075)` 落到旧那条 | 重新确认行顺序后再点；连拍证据取自顺序确认之后 |

以上都不是应用行为缺陷，只影响取证方式。
