# v2 harness 调用：缺脚本文本与 manifest，未执行

**时间**：2026-11-23 18:50 CST
**收到的内容**：只有调用命令，没有脚本正文。

```text
bash <path>/workbuddy_install_scrollbar_v2.sh \
  "<sdk>/platform-tools/adb.exe" \
  "$TEST_DEVICE_SERIAL" \
  "$APP_APK" "$ANDROID_TEST_APK" \
  "$BUILD_MANIFEST" \
  ./scrollbar-device-evidence-v2 \
  --dedicated-device
```

## 缺项

| # | 缺什么 | 现场核对结果 |
|---|---|---|
| 1 | `workbuddy_install_scrollbar_v2.sh` 正文 | 磁盘上只有 v1：`workbuddy_install_scrollbar_candidate.sh`（`D:/uhv1/tools/`）。v2 不存在任何位置。 |
| 2 | `BUILD_MANIFEST` | D:/、D:/uhv1、Downloads、Desktop、工作区 maxdepth 3 搜索均无。 |
| 3 | `$APP_APK` 取值 | 磁盘只有两版：`354fedc0…6711d526`（迁移后）、`d85ae6f7…686719d`（迁移前）。v1 认定两者皆旧；v2 若同判，则当前无可装 APK。 |
| 4 | `$ANDROID_TEST_APK` 取值 | 现有 `1c6c51a0…29660ba6`，是否沿用未说明。 |
| 5 | `$TEST_DEVICE_SERIAL` 取值 | 调用用变量占位，未给字面值。 |

## 设备已改由模拟器承担

按要求不再依赖真机。真机自 18:38 起不在 `adb devices` 列表中，本轮未触碰。

但 harness 的 `--dedicated-device` 语义要求 **包名不存在**（它用 `install --user`，无 `-r`，且明确 no overwrite or cleanup）。本机两台 AVD 均不满足：

```text
Medium_Phone_API_37.0   userdata-qemu.img absent（未启动核实，不能据此断言包不存在）
NotesGO_B2_Test         userdata-qemu.img present；18:0x 已 install -r 装入候选包
                        → com.example.myapplicationfirstapp 存在，必然触发 exit 3
```

详见 `avd-inventory.txt`。可选两条：

- **(a) 新建一次性 AVD**（全新 userdata，包不存在），不触碰现有两台的任何状态；
- **(b) 对 `NotesGO_B2_Test` 用 `-wipe-data` 启动**，包会消失，但会毁掉 18:0x 验收那轮对应的设备状态，不建议未获明确指令就做。

未执行 (a) 或 (b)，因为脚本文本尚未到手，先建机器无意义。

## 本轮动作

零。未落盘 v2、未创目录、未建/启 AVD、未安装、未跑测试、未改脚本、未改源码、未推送特性分支。
真机用户数据仍为 18:14 安装后的原样（`notes.json` 7,117 B，
sha256 `743e56f824c69b3eb67ce7ad2228435c607231cdfa19fc4082e47edca9575c01`）。

## 给齐后立刻可跑

```text
1) adb devices -l 或 emulator -list-avds 确认目标在线且 state=device
2) 新建一次性 AVD（若采用方案 a），记录 AVD 名与镜像、端口
3) 原样落盘 v2 → 记录 sha256
4) 原样执行调用行；逐步记录 status.txt 与退出码
5) 失败回传首个错误原文，不修脚本
```
