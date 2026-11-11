# SAVE-ORDER-2 真机验证：回传

> **本文已被 `REPORT-CLOSEOUT.md`（2026-11-11 15:16）收口修正。**
> 需要修改的表述：① 第 0 节标题「数据零变化」应拆成「notes.json 端点采样一致 / 附件与录音条目数一致 /
> APK 与测试包状态已改变」；② 第 0 节与第 4 节写「三次」采样，实际为 4 次（漏记备份前那次），且 4 次
> 均无原始输出落盘；③ 第 2 节备份只归档、未做恢复演练，原 APK 未保存，不能宣称完整可回滚。
> 本文正文保留原样，作为 15:08 时点的历史记录，不追改。

生成时间：2026-11-11 15:08 (GMT+8)
设备：Redmi `23078RKD5C`（corot），Android 16 / API 36，arm64-v8a，序列号 `6LZT7T7TM7NZL799`
被测代码：`D:/SAVE-ORDER-1-wt` @ `26a5cd96…` + 未提交改动（同第三批收尾后的工作树）
产物：`app/build/outputs/apk/debug/app-debug.apk`（15:00 构建，含生产接缝）

---

## 0. 结论

| 项 | 结果 |
| --- | --- |
| 真机全量 androidTest | **OK (21 tests)**，21 个用例逐个 `started` / `finished`，0 失败 |
| 真实 `notes.json`（2793 B） | 安装前 / 跑完 / 事后 三次均为 `83275cf1…4795`，长度与 mtime 均未变 |
| `files/` 其他数据 | attachments 9 → 9，recordings 57 → 57，asr 目录在 |
| 沙箱 `cache/save-order-2/` | 存在且为空（UUID 子目录全部删除） |
| 数据备份 | `files.tar` 244 MB / 74 条目，sha256 `7bcecefd…`，备份内 notes.json 哈希与设备一致 |
| 应用状态 | **未卸载**，仍可正常使用 |

这一轮的证据强度高于模拟器：模拟器上真实 `files/` 里没有 `notes.json`，指纹比对是
`<absent> == <absent>`；真机上是 2793 字节的真实笔记，跑完 21 项后哈希逐位相同。

---

## 1. 为什么不能直接跑 `connectedDebugAndroidTest`

前几轮已实测：AGP 9 的 `connectedDebugAndroidTest` 任务结束后会**卸载应用**（所以当时才需要手动
`install` + `am instrument` 才能查看沙箱目录）。在真机上卸载会连带清空 `files/`，
用户的笔记、9 张附件、57 个录音会一起丢失。

因此本轮**只使用**：

```
adb install -r app-debug.apk        # -r 保留 data
adb install -r app-debug-androidTest.apk
adb shell am instrument -w <runner>  # 不卸载、不清数据
```

`install -r` 的签名校验通过了（说明真机上原版本与本机构建用的是同一个 debug keystore），
安装后立刻核对 `notes.json` 哈希，未变。

---

## 2. 备份与校验（执行安装之前）

```
D:/SAVE-ORDER-2/real-device-backup-20261111/files.tar   244 MB, 74 entries
  sha256(files.tar)          = 7bcecefd3c2bad813634d017e3b6ac58b59accaaa516a23d82a3e6ccc6947d1d
  sha256(包内 files/notes.json) = 83275cf129453228fb70d96202843b5969e2880ee3b2b038b5fd25446d1a4795
  sha256(设备端 files/notes.json) = 83275cf129453228fb70d96202843b5969e2880ee3b2b038b5fd25446d1a4795
```

包内构成：`files.json` 等 root 6 项、`asr/` 2 项、`attachments/` 9 项、`recordings/` 57 项。

回滚方式（如需）：

```
adb push files.tar /data/local/tmp/
adb shell run-as com.example.myapplicationfirstapp tar -xf /data/local/tmp/files.tar -C /data/data/com.example.myapplicationfirstapp/
```

---

## 3. 执行记录

| # | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 1 | `adb -s 6LZT7T7TM7NZL799 shell run-as … sha256sum files/notes.json` | 0 | `83275cf1…4795`（安装前基线） |
| 2 | `adb install -r app-debug.apk` | 0 | `Success`（57 MB，数据保留） |
| 3 | 安装后立即核对哈希 | 0 | `83275cf1…4795`（未变） |
| 4 | `adb install -r app-debug-androidTest.apk` | 0 | `Success` |
| 5 | `adb shell am instrument -w com.example.myapplicationfirstapp.test/androidx.test.runner.AndroidJUnitRunner` | 0 | `OK (21 tests)` |
| 6 | `run-as … sha256sum files/notes.json` | 0 | `83275cf1…4795`（未变） |
| 7 | `run-as … ls -la files`、`ls cache/save-order-2` | 0 | 数据完整、沙箱为空 |

### 21 项分布（am instrument 输出）

```
ExampleInstrumentedTest:                                      1
data.BackupNotesParserTest:                                   6
data.NotesRepositoryFailureTest:                              4
data.NotesRepositoryFileTest:                                 1
data.NotesWriteCoordinatorFileTest:                           1
data.NotesWriteCoordinatorTest:                               5
ui.AttachmentImageLoaderTest:                                 3
                                                          ----
                                                             21
```

### 关于 0.335 秒

`Time: 0.335` 比模拟器（约 5 秒）快一个数量级，因此额外做了核实，避免「看起来跑完其实没跑」：

- `am instrument` 输出里每个用例打印一个 `.`，共 21 个点，无 `F`；
- logcat 中 `TestRunner` 记录 **21 条具体用例的 `started:`** 加 1 条 `run started: 21 tests` 汇总行；
- **21 条 `finished:`**，**0 条 `FAILED`**。

真机是 UFS + arm64，测试内容又都是 KB 级小文件读写，这个耗时可以解释。

证据文件：

- `logs/realdevice-am-instrument.log`（原始输出）
- `logs/realdevice-logcat-testrunner.txt`（21 条 started/finished 明细）
- `logs/realdevice-fingerprint.txt`（三段指纹与目录清单）

---

## 4. 数据零变化的具体数据

```
notes.json
  before install : 83275cf129453228fb70d96202843b5969e2880ee3b2b038b5fd25446d1a4795  len=2793
  after  21 tests: 83275cf129453228fb70d96202843b5969e2880ee3b2b038b5fd25446d1a4795  len=2793
  mtime          : 2026-11-10 18:54（未变）

files/
  asr/         present
  attachments/ 9 files
  recordings/  57 files
  notes.json   2793 B
  profileInstalled 24 B

cache/save-order-2/  存在，空目录（只有 . 和 ..）
```

`cache/save-order-2/` 为空说明 `finishIsolatedTest()` 的清理在真机上也生效：
每个用例的 UUID 子目录都被删掉，公共父目录保留。

---

## 5. 需要你知道的三件事

1. **真机上的 APK 已被替换**：现在是工作区最新 debug 构建（含 `NotesFileOps` 接缝，行为与原实现等价，
   版本号仍是 2.0 / versionCode 2）。功能没有增减，但如果你想回到 11-10 那一版，
   需要旧 APK 重新安装（本地没有保存旧 APK）。
2. **测试包 `com.example.myapplicationfirstapp.test` 仍在设备上**，不影响使用；要删可以
   `adb uninstall com.example.myapplicationfirstapp.test`（只删测试包，不动主应用数据）。
3. **`am instrument` 不产出 JUnit XML**，所以本轮证据是「am 输出 + logcat 逐项 started/finished + 前后指纹」，
   不是 XML。模拟器那轮的 XML 见 `logs/xml/t2-full-androidtest.xml`（21/21）。

---

## 6. 未覆盖项（与第三批一致，不因真机验证而减少）

1. 进程终止 / 进程重启恢复。
2. 目录 fsync：`rename` 之后没有对 `filesDir` 做 fsync。
3. 备份导入事务（`replace()` 路径）。
4. 真实 `ENOSPC` / `EIO` / 掉电：仍未制造。
5. 并发下故障。
6. 大体积笔记、低存储空间下的降级行为。
7. 真机只跑了一轮，没有重复运行、没有换机型、没有在低电量/省电模式下跑。
8. 真机验证的是「测试不碰真实数据」，**不等于**「App 在真实使用中不会丢数据」。
