# 真机安装 harness 执行：第一道闸门未生效，停在 preflight

**日期**：2026-11-23（真机安装 18:14 CST，harness 执行 18:38 CST）
**设备**：`6LZT7T7TM7NZL799` / 23078RKD5C (Redmi) / Android 16 / API 36 / arm64-v8a
**本轮动作**：把作者提供的 `workbuddy_install_scrollbar_candidate.sh` 原样落盘，用真实参数执行一次；不做任何替换、不重建 APK、不改脚本。

## 三句话结论

1. **脚本第一道闸门失效**：它用来拒绝旧候选的模式是 `354fedc0*71d526`，而目标 APK 的 sha256 结尾是 `11d526`，不匹配 → 该被拒的一版被放行。
2. 放行后停在 `preflight`，原因是脚本裸调 `adb` 而本机 `platform-tools` 不在 PATH；同时 18:38 时设备已从 `adb devices` 消失。
3. 即使前两条都不存在，第三道闸门仍会拦：真机上包名已存在（18:14 由前一轮 `install -r` 装入），而脚本写明 no overwrite or cleanup。

**零条仪器化用例被执行，零条通过。** 本轮不提供任何通过数。

## 材料

```
recent-notes-1/device-install-gate/
  NOTES.md                    本文件
  harness/
    workbuddy_install_scrollbar_candidate.sh   作者脚本原样副本
                                               sha256 9f6ffe9603770fef6165f0ee450a092be64f8f2873e97543e0709b99d90a5c70
  evidence/
    status.txt                  harness 退出时写的唯一一行：preflight: exit 1
    apk.sha256                  harness 自校验产物（两行 OK 的来源）
    hash-gate.txt               闸门 1 的比对与复现命令
    gate-order.txt              按执行顺序逐项状态
    install-record.txt          18:14 真机安装记录与装前装后数据指纹
    device.txt                  设备参数（不含任何笔记内容）
```

## 闸门 1 的证据

```text
脚本模式       354fedc0*71d526 | d85ae6f7*686719d
目标 APK 真实 sha256
               354fedc000904b9345c21a5d69aa0198decc1027d785d68b4f574bcc6711d526
               末 6 位 11d526   末 8 位 6711d526
结果           不匹配 71d526，仅匹配 11d526
实际后果       没有 exit 2，继续跑到 sha256sum -c 并输出两行 OK
```

差一个字符 `1`。这个截断写法出自上一轮回传正文里的 `354fedc0…71d526`，以后一律给完整 64 位。

## 现场未被改动的部分

- 真机上用户数据与 18:14 安装后完全一致：`files/notes.json` 7,117 B、
  sha256 `743e56f824c69b3eb67ce7ad2228435c607231cdfa19fc4082e47edca9575c01`（装前装后同值），
  `attachments/` `recordings/` `asr/` `profileInstalled` `shared_prefs/settings.xml` 均在，`databases/` 仍未创建。
- 装前整包备份已做并校验（249,519,104 B，45 条目，
  sha256 `845f1cb422de68615d575db7a28670d4acb67172ef83235d0fb4d5d72eb3c470`）。
  **该 tar 未上本分支**——它含用户自己的笔记、录音与语音模型。

## 未纳入本分支的材料及原因

- 首屏截图与 uiautomator 控件树：渲染的是用户真实笔记标题，属私人内容。需要可另出脱敏版。
- 上述备份 tar：同上。

## 要把这条链跑通，需要四项输入

1. 一份**新**候选 APK（作者认定 `354fedc0…` 与 `d85ae6f7…` 都属旧版；磁盘上目前只有这两版）。
2. 对应新候选的 androidTest APK 及其 sha256。
3. 一台包名不存在的设备/用户；或明确授权卸载真机现有包（会清数据，备份已在手可还原）。
4. `adb` 的可见性：允许把 platform-tools 加入 PATH，或由执行方预先设好。

## 边界

未推送特性分支、未合并、未建 PR、未动 `main`；未安装（harness 未跑到安装）；未卸载、未清数据；
未改脚本、未改源码；未重建或替换任何 APK。
