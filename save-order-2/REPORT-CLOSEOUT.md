# SAVE-ORDER-2 真机证据收口

生成时间：2026-11-11 15:16 (GMT+8)
对象：`REPORT-REALDEVICE.md`（15:08 版）与 `d5a17c1` / `4ca4cea` 两次发布所用的真机证据
本轮性质：**只做证据核对与表述收口**。没有新增真机操作，没有回滚，没有卸载主应用，没有清数据，
没有为补 XML 重跑真机，没有删除测试包，没有合并 main。

---

## 0. 本轮做了什么 / 没做什么

| 做了 | 没做 |
| --- | --- |
| 复核本地产物，读取两个 APK 的完整 SHA-256 与身份信息 | 没有重新安装、没有执行任何 adb 命令 |
| 用 `dexdump` 反汇编主 APK，核对 `save()` 的写入目标 | 没有回滚真机上的 APK，也没有回滚数据 |
| 重新生成源码 diff，与 15:01:47 的存档逐字节比对 | 没有补做真机 JUnit XML，没有重跑测试 |
| 把「数据零变化」拆成三条不同强度的结论 | 没有删除设备上的测试包 |
| 统一哈希采样次数，标出留存缺口 | 没有把重建产物当作历史证据 |

---

## 1. 「数据零变化」拆成三件事

原报告用「数据零变化」概括，强度过高。实际是三种不同强度的结论：

### 1.1 A 类：`notes.json` 端点内容一致（有限结论）

| 项 | 值 |
| --- | --- |
| 路径 | `/data/data/com.example.myapplicationfirstapp/files/notes.json` |
| 长度 | 2793 B（每次采样均为 2793） |
| SHA-256 | `83275cf129453228fb70d96202843b5969e2880ee3b2b038b5fd25446d1a4795` |
| 采样次数 | **4 次**（见第 2 节），4 次同值 |
| mtime | 2026-11-10 18:54（未变） |

**结论边界**：这是**端点比对**——在 4 个采样时刻读到同一份字节。
它**不**证明测试期间任何时刻都没有发生过写入（写入后回滚成同样字节，端点比对看不出来）。
所以只能写「路径隔离、前后指纹一致」，不能写「中间绝对从未写入」。

### 1.2 B 类：附件/录音数量一致（只到条目数）

| 目录 | 测试前 | 测试后 | 依据 |
| --- | --- | --- | --- |
| `files/attachments/` | 9 | 9 | `ls` 条目计数 |
| `files/recordings/` | 57 | 57 | `ls` 条目计数 |
| `files/asr/` | 存在 | 存在 | `ls` |

**结论边界**：只统计了**条目数**，没有逐个文件取哈希。数量相同不等于内容相同。

### 1.3 C 类：APK 与测试包状态**已改变**（不属于「零变化」）

| 对象 | 上一次状态 | 现在的状态 | 是否改变 |
| --- | --- | --- | --- |
| 主应用 `com.example.myapplicationfirstapp` | 设备上原有安装包（11-10 构建，字节未留存） | 本轮构建 `93c27b89…`（见 4.1） | **已改变**（`install -r` 覆盖，数据目录保留） |
| 测试包 `com.example.myapplicationfirstapp.test` | 设备上原有安装包（疑似 11-09 构建，未证实） | 本轮构建 `b49eb23a…`（见 4.1） | **已改变**（`install -r` 覆盖） |
| 主应用是否已卸载 | — | **未卸载** | 否 |
| 数据目录 `files/` | 2793 B 等 | 同左 | 否（按 A/B 类结论） |

所以准确表述是：**「`files/` 内容在端点采样上未变；应用安装状态已改变」**。
原报告的标题「数据零变化」不成立的正是后半句。

---

## 2. 哈希采样统一口径

原报告写「安装前 / 跑完 / 事后 三次」，**漏记了备份前那一次**，实际是 4 次。统一如下：

| # | 采样时刻（可界定的范围） | 采样对象 | 结果 | 原始输出留存 |
| --- | --- | --- | --- | --- |
| S1 | ≤ 15:06:57（备份包 `files.tar` 落盘之前） | `files/notes.json` | `83275cf1…4795`，2793 B | **未落盘**（只在会话输出中） |
| S2 | 15:06:57 – 15:07:47 之间，安装前 | 同上 | 同上 | **未落盘** |
| S3 | 同上窗口，`install -r` 之后立即 | 同上 | 同上 | **未落盘** |
| S4 | ≥ 15:07:48（`run finished: 21 tests` 之后） | 同上 | 同上 | **未落盘** |

逐次时间只能界定到上表的区间，依据是：
`files.tar` 的 mtime `15:06:57.027`、logcat `run started` `15:07:47.961`、`run finished` `15:07:48.297`。

**留存缺口（如实标注，不补造）**：

1. 4 次采样的**原始 `sha256sum` stdout 都没有重定向到文件**，只出现在当时的终端输出里。
2. 现存的 `logs/realdevice-fingerprint.txt`（sha256 `7d62242a…`）是 15:08:21 的**人工整理稿**，
   不是任何一次采样的原始输出，不能当作 S1–S4 的原始记录。
3. 每次采样的**秒级时间戳没有记录**，只有区间上界/下界。

因此本节结论强度是：「4 次采样、4 次同值，采样时刻与原始输出仅有部分留存」。

### 2.1 21 项逐条执行时间（有原始记录）

来源 `logs/realdevice-logcat-testrunner.txt`（sha256 `18ab27e8…`），`run started: 21 tests` 于
`15:07:47.961`，`run finished: 21 tests, 0 failed, 0 ignored` 于 `15:07:48.297`，墙钟约 0.336 s，
与 `am instrument` 的 `Time: 0.335` 吻合。

| 开始时刻 | 用例 | 类 |
| --- | --- | --- |
| 15:07:47.962 | useAppContext | ExampleInstrumentedTest |
| 15:07:47.968 | acceptsEmptyArray | data.BackupNotesParserTest |
| 15:07:47.972 | rejectsWrongRootAndInvalidElements | data.BackupNotesParserTest |
| 15:07:47.978 | preservesLegacyOptionalDefaults | data.BackupNotesParserTest |
| 15:07:47.986 | roundTripsCurrentNote | data.BackupNotesParserTest |
| 15:07:47.992 | rejectsMalformedInput | data.BackupNotesParserTest |
| 15:07:47.996 | rejectsTrailingContentButAcceptsWhitespace | data.BackupNotesParserTest |
| 15:07:48.001 | renameFailureKeepsOldFile | data.NotesRepositoryFailureTest |
| 15:07:48.052 | syncFailureKeepsOldFile | data.NotesRepositoryFailureTest |
| 15:07:48.072 | retryAfterFailureSucceeds | data.NotesRepositoryFailureTest |
| 15:07:48.107 | tempWriteFailureKeepsOldFile | data.NotesRepositoryFailureTest |
| 15:07:48.151 | repositorySaveReloadRoundTrip | data.NotesRepositoryFileTest |
| 15:07:48.178 | coordinatorPersistsNewestSnapshotToRealFile | data.NotesWriteCoordinatorFileTest |
| 15:07:48.203 | foreignTicketIsRejected | data.NotesWriteCoordinatorTest |
| 15:07:48.208 | replacementInvalidatesWaitingOldSave | data.NotesWriteCoordinatorTest |
| 15:07:48.214 | failedWriteCanRetry | data.NotesWriteCoordinatorTest |
| 15:07:48.219 | failedReplacementPreservesPendingTicket | data.NotesWriteCoordinatorTest |
| 15:07:48.224 | olderCannotOverwriteNewer | data.NotesWriteCoordinatorTest |
| 15:07:48.232 | fileSizeChangeInvalidatesVersionKey | ui.AttachmentImageLoaderTest |
| 15:07:48.238 | missingAndInvalidImagesReturnErrors | ui.AttachmentImageLoaderTest |
| 15:07:48.288 | localImageLoadsAndSecondRequestUsesMemoryCache | ui.AttachmentImageLoaderTest |

---

## 3. 备份与回滚能力（诚实版）

### 3.1 `files.tar` 已归档，**未做恢复演练**

```
路径   D:/SAVE-ORDER-2/real-device-backup-20261111/files.tar
大小   255,684,096 B (243.8 MiB)
sha256 7bcecefd3c2bad813634d017e3b6ac58b59accaaa516a23d82a3e6ccc6947d1d
mtime  2026-11-11 15:06:57.027
条目   74（root 6 / asr 2 / attachments 9 / recordings 57）
```

- 包内 `files/notes.json` 解出后的 sha256 与设备端一致（`83275cf1…`），这一点在备份当时核对过。
- **只做了「备份可读、内容一致」的检查，没有真正执行过一次解包覆盖回去。**
  因此不能声称「已验证可恢复」，只能说「已归档，具备恢复材料」。

### 3.2 原 APK 未保存 → 不能宣称完整可回滚

- 设备上被 `install -r` 覆盖掉的那个安装包，**本机没有留存副本**，也无法再从设备导出（已被覆盖）。
- 因此：**数据可回滚（有 tar），应用二进制不可回滚**。两者要分开说，不能合并成「可完整回滚」。

### 3.3 疑似候选原件（仅候选，未证实）

主工程目录里有两个更早的构建产物，**不能**当作设备上被覆盖的那一个：

| 文件 | 大小 | 构建时间 | SHA-256 | 判定 |
| --- | --- | --- | --- | --- |
| `D:/myappappappappapp/app/build/outputs/apk/debug/app-debug.apk` | 57,391,301 B | 2026-11-10 19:04:54 | `6ec72906f6f58b801428b0cc339ab28995381b1f07cb69582e3b4ba82101fb44` | 同机更早构建，**未证实**与设备原安装包一致 |
| `D:/myappappappappapp/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 1,154,359 B | 2026-11-09 23:41:24 | `3a3d2858cda7d50024705e7b29bcee3c8c24911eed6757785837447836acdec7` | 同上，**未证实** |

标为缺口：**原始安装包不可取得**；不以这两个候选替代历史证据。

### 3.4 覆盖安装前的用户授权记录

- **授权形式**：会话内的选项确认。当时的四个选项中，「`am instrument` 跑全量 21 项（推荐）」被选中。
- **时间**：只能界定在 15:06:57（备份完成）之后、15:07:47（测试开始）之前；秒级时间未记录。
- **留存状态**：**没有书面授权、没有签名、没有独立的时间戳文件**。唯一依据是对话记录本身。
  若要作为正式审计材料，这条属于**缺失**，需要补一份书面确认。

---

## 4. APK 身份与源码绑定

### 4.1 两个 APK 的完整 SHA-256 与身份

**本轮安装到真机的主 APK**（本地留存，可复核）

| 项 | 值 |
| --- | --- |
| 路径 | `D:/SAVE-ORDER-1-wt/app/build/outputs/apk/debug/app-debug.apk` |
| 大小 | 57,424,115 B（54.75 MiB） |
| **SHA-256** | `93c27b8922d5182e6b05d5c7ced680cb93da4a9d622d75460cbb83a23526418d` |
| MD5 | `2c433d7b1cf60221586ca2e74e2acd95` |
| 构建落盘时间 | 2026-11-11 15:00:59.998 |
| package | `com.example.myapplicationfirstapp` |
| versionCode / versionName | `2` / `2.0` |
| minSdk / targetSdk / compileSdk | 24 / 37 / 37 |
| debuggable | 是（`application-debuggable`） |
| launchable-activity | `com.example.myapplicationfirstapp.MainActivity`（label `Notes GO`） |
| 签名证书 DN | `C=US, O=Android, CN=Android Debug` |
| 证书 SHA-256 | `b45eb6001aeb29d9b142105ea6e16a5b9325a199e5f8b6321d30e87ecf8482a8` |
| 证书 SHA-1 | `9ae54fa60eddd64a142667be3d9973ca5722b90c` |

**本轮安装到真机的测试 APK**

| 项 | 值 |
| --- | --- |
| 路径 | `D:/SAVE-ORDER-1-wt/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| 大小 | 1,193,312 B |
| **SHA-256** | `b49eb23abede03f029bf7910d264da50f25d53fb9c77aad91533e996102ac0ea` |
| MD5 | `d32c8d893e70c4f5568a4e53b21ac327` |
| 构建落盘时间 | 2026-11-11 15:01:01.449 |
| package | `com.example.myapplicationfirstapp.test` |
| versionCode / versionName | 空 / 空（测试包不声明） |
| minSdk / targetSdk | 24 / 37 |
| debuggable | 是 |
| 签名证书 SHA-256 | `b45eb600…`（与主 APK 同一 debug 证书） |

两个 APK 同一证书，这是 `install -r` 能覆盖成功的前提之一。
但反过来**不能**据此推断设备上原安装包的证书哈希——那需要设备端比对，本轮没做。

### 4.2 与最终源码的绑定链

| 环节 | 证据 |
| --- | --- |
| 源码 HEAD | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269`（`fix/backup-safe-1`） |
| 工作区改动 | `M NotesRepository.kt` + 5 个未跟踪文件（接缝与测试） |
| APK 构建时刻 | 主 15:00:59 / 测试 15:01:01 |
| 构建后是否再改源码 | `find app/src gradle *.gradle.kts -newer app-debug.apk` → **无结果** |
| 构建后是否再改依赖 | `find app/libs -newer app-debug.apk` → **无结果** |
| 当前 diff 与 15:01:47 存档 | 现场重新 `git diff` → sha256 `645acf70…`，与 `SAVE-ORDER-2-tighten.diff` **逐字节相同**（37590 B） |

即：15:01:47 生成的那份 diff 就是 APK 构建时的源码状态，且到本轮收口为止没有再变。

### 4.3 字节码级证据：变异已还原

M1 / M1′ 曾经把 `fileOps.openStream(temp)` 改成 `fileOps.openStream(file)`（非原子直写）。
若这个变异还留在安装到真机的 APK 里，`save()` 会把**正式文件**交给 `openStream`。

反汇编本地留存的主 APK（`classes5.dex` → `NotesRepository$save$2.invokeSuspend`）得到：

```
0047: invoke-interface {v2, v1}, NotesFileOps;.createTemp:(Ljava/io/File;)Ljava/io/File;
004a: move-result-object v2                       <- v2 = createTemp 的返回值（临时文件）
0052: invoke-interface {v3, v2}, NotesFileOps;.openStream:(Ljava/io/File;)Ljava/io/FileOutputStream;
007b: invoke-static {v4}, NotesRepository;.access$getFile:(...)     <- 到这里才取正式文件
007f: invoke-interface {v3, v2, v4}, NotesFileOps;.rename:(Ljava/io/File;Ljava/io/File;)Z
0085: invoke-virtual {v2}, Ljava/io/File;.delete:()Z                <- finally 清理临时文件
009d: invoke-virtual {v2}, Ljava/io/File;.delete:()Z                <- 异常路径同样清理
```

关键点：`0x004a` 与 `0x0052` 之间**没有** `access$getFile`。变异形态会在 `openStream` 之前插入
`access$getFile` 把字段 `file` 取到 `v2`。这里没有，说明装到真机的是**未变异的原子写实现**。
完整片段见 `logs/main-apk-dex-save-trace.txt`（sha256 `392dfd3a…`）。

源码侧的三条旁证：

| 检查 | 结果 |
| --- | --- |
| `NotesRepository.kt` 中 `fileOps.openStream(temp)` | 第 53 行，存在 |
| `NotesRepository.kt` 中 `temp.delete()` | 1 处（`finally`），存在 |
| `NotesRepository.kt` blob | `df3792064346a364d6fb8bd5dd2851faf3905d71`，与施加变异**前**记录的值一致 |

注：`df379206` ≠ `HEAD:NotesRepository.kt`（`fdcd1d1c…`）是**预期差异**——接缝（+13 −6）本就未提交。
「还原」的判据是与变异前的同一 blob 相等，不是与 HEAD 相等。

### 4.4 测试 APK 内没有探针

M1′ 的临时探针方法 `m1TargetedCorruptionAfterValidV1` 若编进了测试包，真机上会多跑一项。

| 检查 | 结果 |
| --- | --- |
| 6 个 dex 中 `m1TargetedCorruptionAfterValidV1` 命中数 | **0** |
| `renameFailureKeepsOldFile` / `tempWriteFailureKeepsOldFile` / `coordinatorPersistsNewestSnapshotToRealFile` / `repositorySaveReloadRoundTrip` | 均在 `classes3.dex` |

与 logcat 的 21 条 `started:` 相印证：没有第 22 项。

### 4.5 本节的缺口

1. **设备端未做 APK 哈希比对**（`pm path` + `sha256sum` 需要新的 adb 操作，本轮禁止）。
   所以「设备上跑的就是 `93c27b89…` 这一份」依赖的是安装命令成功 + 构建顺序 + `install -r` 行为，
   不是设备端的字节核对。
2. 4.2 的源码绑定是**时间顺序 + 构建输入未变**，不是可加密验证的「APK ↔ 源码」映射
   （没有留存 Gradle 的 inputs 快照，也不做字节级可重现构建）。

---

## 5. 最终源码清单（供源码固化）

均相对 `HEAD = 26a5cd96…`，工作区状态：1 个修改 + 5 个新增，全 LF。

| 文件 | 状态 | 行数 | 字节 | git blob (SHA-1) | SHA-256 |
| --- | --- | --- | --- | --- | --- |
| `app/src/main/java/com/example/myapplicationfirstapp/data/NotesFileOps.kt` | 新增 | 42 | 1,542 | `002c814c4d5e7f2add6007d39ff1f087a74f0aa2` | `a297960582de7d570067cd515a01c24b0c4370893c82d9130c9a4a3dca8a0fe3` |
| `app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt` | 修改 +13 −6 | 206 | 8,075 | `df3792064346a364d6fb8bd5dd2851faf3905d71`（HEAD 为 `fdcd1d1c…`） | `cb20541ce33d60ea9463aa077f1fa026358dfd38e2d898dfb795e657c1ae8949` |
| `app/src/androidTest/.../data/IsolatedNoteStorage.kt` | 新增 | 113 | 4,342 | `890b6275af9fad5dd4dc9ec1e6690b51e58b9639` | `5aab5dbb480d17dd523c76f7ba21b1df8dca6638ada608ce56fa4e7edb7c0d8a` |
| `app/src/androidTest/.../data/NotesRepositoryFileTest.kt` | 新增 | 138 | 6,270 | `d31c6d51fa974910eedfc5f4083dfdbd3f9bd767` | `c1c1cc75f5adb8d0a06e4323175865072d3fa3dce1a4bd20ed03f390a1ff6046` |
| `app/src/androidTest/.../data/NotesWriteCoordinatorFileTest.kt` | 新增 | 120 | 5,432 | `78c715166ecb6ee1c0e955454417d1e23b548066` | `40b35ac10c9fae255db2e19a66178a2ed81a0268bcc27c9a17d73d9dd23aafb0` |
| `app/src/androidTest/.../data/NotesRepositoryFailureTest.kt` | 新增 | 376 | 15,359 | `1e5242fe05a1486888b109751fe2fd0cdab59ca6` | `71f0d9e3af92bdf0888185b9c7492d1df18079b002ea3f21d1aee0383c0137fa` |

完整 diff：`logs/SAVE-ORDER-2-final.diff`，6 files changed, **+802 −6**，37,590 B，
sha256 `645acf7082a22f71181b916a0d2b61ecfd546dc08a0505b42b02c61d0978f7a5`。

`git diff --check` 退出码 0；`git status --short` 只有上述 6 项；未暂存、未提交。

---

## 6. 未覆盖项与缺口汇总

**测试未覆盖（沿用前几批，不因真机而减少）**
1. 进程终止 / 进程重启恢复
2. 目录 fsync（`rename` 之后未对 `filesDir` 做 fsync）
3. 备份导入事务（`replace()` 路径）
4. 真实 `ENOSPC` / `EIO` / 掉电
5. 并发下的故障
6. 大体积笔记、低存储空间降级
7. 真机只跑一轮，未换机型、未重复运行、未在省电模式跑

**证据缺口（本轮新增标注）**
8. 4 次哈希采样均无原始 stdout 落盘，`realdevice-fingerprint.txt` 是人工整理稿
9. 采样时刻只有区间，没有秒级时间
10. 真机没有 JUnit XML（`am instrument` 不产出）
11. 设备端未比对已安装 APK 的哈希
12. 原始安装包不可取得，本地旧产物只是候选，未证实
13. `files.tar` 未做恢复演练
14. 覆盖安装前的用户授权只有对话确认，无书面归档

---

## 附录 A：本轮执行的只读命令

| # | 命令 | 退出码 | 用途 |
| --- | --- | --- | --- |
| 1 | `sha256sum` / `md5sum` 两个 APK | 0 | 产物哈希 |
| 2 | `aapt2 dump badging` 两个 APK | 0 | 包名、版本、SDK、debuggable |
| 3 | `apksigner verify --print-certs` 两个 APK | 0 | 签名证书指纹 |
| 4 | `find app/src gradle *.gradle.kts -newer app-debug.apk` | 0 | 构建后无源码改动（无输出） |
| 5 | `unzip -p ... classes5.dex` + `dexdump -d` | 0 | `save()` 字节码核对 |
| 6 | `grep` 探针字符串于测试 APK 的 6 个 dex | 0（0 命中） | 无探针残留 |
| 7 | `git diff > SAVE-ORDER-2-final.diff` 并 `diff -q` 比对存档 | 0 | 逐字节相同 |
| 8 | `git diff --check`、`git status --short` | 0 | 工作区状态 |

## 附录 B：本轮引用/新增的证据文件

| 文件 | 字节 | sha256 |
| --- | --- | --- |
| `logs/realdevice-am-instrument.log` | 504 | `2d2226c53e97fcf64b7353a5525e268e74a5383a76ece2c636af59ae3a1b6639` |
| `logs/realdevice-logcat-testrunner.txt` | 6,417 | `18ab27e820dbf027a8bc449f0fc937ad612f79d6aa02637e7766cf5fdbb53453` |
| `logs/realdevice-fingerprint.txt`（人工整理稿） | 722 | `7d62242a1224cb3c4f01533a12befdaae26eafe9e98535268024b9b8461d7ed3` |
| `logs/main-apk-dex-save-trace.txt`（本轮新增） | — | `392dfd3ae0b825b11f365658f7763b5f017cc394f0d85fb5274120e368937583` |
| `logs/SAVE-ORDER-2-final.diff`（本轮重新生成） | 37,590 | `645acf7082a22f71181b916a0d2b61ecfd546dc08a0505b42b02c61d0978f7a5` |
| `logs/xml/t2-full-androidtest.xml`（模拟器，21/21） | 5,285 | `5a1451b3d445575206977f9594fe971c1947efb56f6505fa60c81e9d1a492bc2` |
