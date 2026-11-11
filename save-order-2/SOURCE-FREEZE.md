# SAVE-ORDER-2 源码固化与证据归档

生成时间：2026-11-11 15:30 (GMT+8)
实验 worktree：`D:/SAVE-ORDER-1-wt`｜分支 `fix/backup-safe-1`
本轮性质：**只固化已测工作树 + 修正文档口径**。不新增功能、不改业务逻辑、不合并 main、不操作真机。

> 本文写在独立文档目录 `D:/SAVE-ORDER-2/`，**没有**进入源码提交。
> 历史证据文件（`REPORT-CLOSEOUT.md`、`REPORT-REALDEVICE.md`、`logs/main-apk-dex-save-trace.txt`、
> `logs/SAVE-ORDER-2-final.diff`）**均未改动**，见第 7 节的 blob 前后校验。

---

## 1. 固化前核验（原样记录）

| 命令 | 实际输出 |
| --- | --- |
| `git rev-parse HEAD` | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269` |
| `git branch --show-current` | `fix/backup-safe-1` |
| `git diff --check` | 无输出，退出码 `0` |
| `git diff --name-only` | 仅 `app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt` |
| `git ls-files --others --exclude-standard` | 5 个：`IsolatedNoteStorage.kt`、`NotesRepositoryFailureTest.kt`、`NotesRepositoryFileTest.kt`、`NotesWriteCoordinatorFileTest.kt`、`NotesFileOps.kt` |

`git status --short`：

```
 M app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt
?? app/src/androidTest/java/com/example/myapplicationfirstapp/data/IsolatedNoteStorage.kt
?? app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesRepositoryFailureTest.kt
?? app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesRepositoryFileTest.kt
?? app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesWriteCoordinatorFileTest.kt
?? app/src/main/java/com/example/myapplicationfirstapp/data/NotesFileOps.kt
```

与预期一致：1 个已跟踪修改 + 5 个新增，无其他未说明改动，HEAD 与分支未变。因此继续。
**未执行** `reset`、`clean`、`merge`、`rebase`。

---

## 2. 六个路径的 blob 三方核对

暂存前（worktree）、暂存后（index）、提交后（HEAD）三次取值完全相同。

| # | 文件 | 期望 blob | worktree | index | HEAD |
| --- | --- | --- | --- | --- | --- |
| 1 | `data/NotesFileOps.kt` | `002c814c4d5e7f2add6007d39ff1f087a74f0aa2` | 同 | 同 | 同 |
| 2 | `data/NotesRepository.kt` | `df3792064346a364d6fb8bd5dd2851faf3905d71` | 同 | 同 | 同 |
| 3 | `data/IsolatedNoteStorage.kt` | `890b6275af9fad5dd4dc9ec1e6690b51e58b9639` | 同 | 同 | 同 |
| 4 | `data/NotesRepositoryFileTest.kt` | `d31c6d51fa974910eedfc5f4083dfdbd3f9bd767` | 同 | 同 | 同 |
| 5 | `data/NotesWriteCoordinatorFileTest.kt` | `78c715166ecb6ee1c0e955454417d1e23b548066` | 同 | 同 | 同 |
| 6 | `data/NotesRepositoryFailureTest.kt` | `1e5242fe05a1486888b109751fe2fd0cdab59ca6` | 同 | 同 | 同 |

说明：`NotesRepository.kt` 的 `df379206…` 与 `HEAD` 原值 `fdcd1d1c…` **不相等是预期的**——
接缝（+13 −6）本轮才提交。核对判据是「与清单一致」，不是「与旧 HEAD 一致」。

变异与探针残留检查：`m1TargetedCorruption` 命中 **0**；`fileOps.openStream(temp)` 在
`NotesRepository.kt:53`；`openStream(file)` 变异形态命中 **0**；`temp.delete()` 1 处（`finally`）。

---

## 3. 暂存范围（逐个路径，未用 `git add .` / `-A`）

提交前检查：

| 命令 | 结果 |
| --- | --- |
| `git diff --cached --check` | 无输出，退出码 `0` |
| `git diff --cached --name-only` | 恰好 6 个路径 |
| `git diff --cached --stat` | `6 files changed, 802 insertions(+), 6 deletions(-)` |
| `git diff --name-only`（未暂存） | **空**，`UNSTAGED_COUNT=0`（无部分暂存） |

逐文件行数：`IsolatedNoteStorage.kt` 113、`NotesRepositoryFailureTest.kt` 376、
`NotesRepositoryFileTest.kt` 138、`NotesWriteCoordinatorFileTest.kt` 120、
`NotesFileOps.kt` 42、`NotesRepository.kt` 19（+13 −6）。

未暂存任何 AAR、`local.properties`、APK 与构建产物、报告/日志/XML/截图、
真实笔记与附件、其他源码或依赖版本文件。

---

## 4. 提交

| 项 | 值 |
| --- | --- |
| 提交说明 | `test: verify note storage failures with real file operations` |
| **新提交 SHA** | **`d252ed3c2a9780c305b7a1be515186695a8e9c72`** |
| 父提交 | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269`（与预期一致） |
| 作者 / 提交者 | `HOSDIVALL <HOSDIVALL@users.noreply.github.com>`（仓库级配置，未改全局） |
| 提交时间 | 2026-11-11T15:30:43+08:00 |
| 钩子 | 钩子目录中只有 `*.sample`，**无生效钩子**；提交后六个 blob 与暂存时逐一相同，说明未发生钩子改写 |

`git show --stat --oneline HEAD`：

```
d252ed3 test: verify note storage failures with real file operations
 .../data/IsolatedNoteStorage.kt                    | 113 +++++++
 .../data/NotesRepositoryFailureTest.kt             | 376 +++++++++++++++++++++
 .../data/NotesRepositoryFileTest.kt                | 138 ++++++++
 .../data/NotesWriteCoordinatorFileTest.kt          | 120 +++++++
 .../myapplicationfirstapp/data/NotesFileOps.kt     |  42 +++
 .../myapplicationfirstapp/data/NotesRepository.kt  |  19 +-
 6 files changed, 802 insertions(+), 6 deletions(-)
```

提交后 `git status --short`：**空**（`STATUS_LINES=0`）。

---

## 5. 推送（**失败**，仅完成本地提交）

先核对远端，再推送。两者都失败：

| 步骤 | 命令 | 结果 |
| --- | --- | --- |
| 远端核对（第 1 次） | `git ls-remote origin refs/heads/fix/backup-safe-1` | 退出码 `128`，`error: RPC failed; curl 52 Empty reply from server` / `fatal: expected flush after ref listing` |
| 远端核对（重试 2 次） | 同上 | 均 `fatal: unable to access ...: CONNECT tunnel failed, response 502` |
| 推送 | `git push origin HEAD:refs/heads/fix/backup-safe-1` | **退出码 `128`**，`fatal: unable to access 'https://github.com/HOSDIVALL/appppp.git/': CONNECT tunnel failed, response 502` |

因此：**本地提交已创建并保留，远端未收到任何内容**。

按约定本轮**没有**改用 Git Data API 更新源码分支引用，**没有**更换 remote，**没有** force push，
**没有**推送 main，**没有**建 PR，**没有**变基或合并。

远端实际 SHA 用**只读**方式核对（仅 GET `/git/ref/heads/<branch>`，未写入任何引用）：

| 分支 | 远端 SHA | 与本地关系 |
| --- | --- | --- |
| `fix/backup-safe-1` | `26a5cd9669c76ce267b5eaaea2fafe64a9e92269` | 仍是父提交，**未包含 `d252ed3`** |
| `main` | `61dff28e4041aa5a8d9376ba1da872a87a009301` | 未变 |

即「远端一致」这一条**已确认成立**（远端确实还是 `26a5cd96`），失败发生在推送这一步的传输层，
不是远端状态不符。等网络恢复后重跑同一条普通推送即可，无需任何额外操作。

**补记（2026-11-11 15:36，交付文档前复核）**：又重跑了一次同一条普通推送
`git push origin fix/backup-safe-1:fix/backup-safe-1`，仍然
`fatal: unable to access 'https://github.com/HOSDIVALL/appppp.git/': CONNECT tunnel failed, response 502`。
远端 `fix/backup-safe-1` 经只读核对仍为 `26a5cd96…`，未收到 `d252ed3`。
除时间点外本节的结论与做法均不改变：未 force、未换 remote、未推 main、未建 PR、
未用 Git Data API 改写源码分支引用。

---

## 6. 与已测内容的一致性 / 是否运行测试

- 六个文件的内容与已通过测试的那一版**逐字节相同**（blob 三方一致，且提交前后无钩子改写）。
- 一致性依据是 blob 相等 + 提交前 `git status` 无其他改动，不是重新执行测试。
- **本轮未运行任何测试**：没有编译、没有 `connectedDebugAndroidTest`、没有 `am instrument`。
- 因此准确表述是：**「提交内容与原测试对象一致，测试证据引用原轮次。」**
  不得表述为「在本次新提交上重新运行测试通过」。
- 原轮次测试证据指向：模拟器全量 21/21（`logs/xml/t2-full-androidtest.xml`）、
  真机 `OK (21 tests)`（`logs/realdevice-am-instrument.log`、`logs/realdevice-logcat-testrunner.txt`）。
  这些证据产生于父提交 `26a5cd9` + 同一份未提交改动之上，即与本提交内容同一。

---

## 7. 文档口径修正（只改表述，不动已测源码）

五处收窄，逐条给出原文与采纳口径。历史文件**未修改**，本表是「以后以此为准」的说明。

机器可读的对照见 `D:/SAVE-ORDER-2/DOC-CORRECTIONS.diff`
（11 行，sha256 `9bd4df03…6b62`）。它是**语句级对照**，由两组逐条 1:1 对应的句子做 `diff -u` 生成，
**不是文件级 patch**——历史文件一个字节都没改（见 7.6），因此不存在可直接 apply 的文件 diff。
`-` 行是历史文件的原文摘句，`+` 行是本轮采纳口径；两边都标了出处章节。

### 7.1 「数据可回滚」

- 历史原文（`REPORT-REALDEVICE.md` §0 表「应用状态」附近、`README.md` 第四批摘要）：
  「备份可回滚」
- 采纳口径：
  **「`files/` 已归档，具备恢复材料；未做恢复演练，原安装包未留存，不能宣称完整可回滚。」**

### 7.2 「整个 files/ 内容未变」

- 历史原文（`REPORT-REALDEVICE.md` 标题与 §0）：
  「数据零变化」／「`files/` 其他数据 attachments 9 → 9，recordings 57 → 57」
- 采纳口径：
  **「据留存汇总，`notes.json` 采样哈希和长度一致；附件与录音仅核对条目数量，未证明逐文件内容一致。」**

### 7.3 字节码检查的证明范围

- 历史原文（`REPORT-CLOSEOUT.md` §4.3 标题「变异已还原」及其结论句）：
  由本地 APK 反汇编推出「装到真机的是未变异的原子写实现」
- 采纳口径：
  **「本地留存 APK 的已检查路径与临时文件写入实现一致。」**
  只能到此为止——**不得**扩大为已完成设备端 APK 字节核对。设备端从未比对哈希。

### 7.4 `find -newer` 与 diff 一致的性质

- 历史原文（`REPORT-CLOSEOUT.md` §4.2）：
  以「构建后源码未变 + diff 与存档逐字节相同」支撑「与最终源码的绑定链」
- 采纳口径：
  **「`find -newer` 与 diff 逐字节一致属于时间顺序与构建输入一致性旁证。」**
  不声称可重现构建，不声称完整 APK—源码加密绑定（无 Gradle inputs 快照，无字节级重现）。

### 7.5 保留的历史缺口（不倒填、不补造）

1. 4 次哈希采样的 `sha256sum` **原始 stdout 均未落盘**，只有会话内输出。
2. `logs/realdevice-fingerprint.txt` 是 15:08:21 的**人工整理稿**，不是采样原始记录。
3. 采样时刻只有区间（由 `files.tar` mtime、logcat `run started/finished` 界定），**无秒级时间戳**。
4. `files.tar` **只归档，未做恢复演练**。
5. 原安装包**未留存**，本机旧产物只是候选，未证实。
6. 覆盖安装前的授权仅为会话内选项确认，**无书面授权 / 签名 / 独立时间戳**。
7. 设备端**未比对**已安装 APK 的哈希。
8. 真机**无 JUnit XML**（`am instrument` 不产出）。

以上均按原样保留，本轮未补造任何记录，也未把任何授权倒填到更早时间点。

### 7.6 历史文件未被改动（blob 校验）

| 文件 | 本地 blob（SHA-1） | 发布时 | 结论 |
| --- | --- | --- | --- |
| `REPORT-CLOSEOUT.md` | `02df5299f659b7b53d3fdf29e28b4f955b41e338` | `02df5299f659…` | 未改动 |
| `REPORT-REALDEVICE.md` | `1e7e8b2ba8e73b33071527c3e072f76f21476e51` | `1e7e8b2b…` | 未改动 |
| `logs/main-apk-dex-save-trace.txt` | `46a219643b478bc429f69c7b6d046b382811116d` | `46a219643b47…` | 未改动 |
| `logs/SAVE-ORDER-2-final.diff` | `d09faf1667e1df7c91b9a664c47dfd80b8829753` | `d09faf1667e1…` | 未改动 |

---

## 8. 连续反汇编片段（含 `move-result-object`）

### 8.1 材料来源说明（先说不确定性）

上一轮生成连续反汇编的中间文件（`tmp-dexcheck/classes5.txt`）在收口时被删除，
只留下按关键字过滤的 `main-apk-dex-save-trace.txt`——**那份摘录不含 `move-result-object`**，
也没有 `nop`、寄存器搬移等未命中关键字的指令，所以**原摘录本身不足以满足「连续片段」的要求**。

本轮用**同一份留存 APK** 在本地重新反汇编，取到连续片段，存为
`logs/main-apk-dex-contiguous-fragment.txt`（74 行，sha256 `999e7b6f6318211ea44cace4d917db3a064ddcd6f646e86b7c909bd84477a6ea`）。

必须说清三点：

- 这份是**本轮本地重新反汇编**的结果，**不是**上一轮的历史原始输出，不能冒充历史证据。
- 输入是留存产物 `app-debug.apk`（sha256 `93c27b89…418d`，与收口报告 §4.1 记录一致），
  抽取 `classes5.dex`（sha256 `e35dd4ba…7d301`），`dexdump -d` 反汇编。
- **未重建 APK，未执行任何 adb 命令，未读取设备端任何内容。**

### 8.2 片段（`0x003f`–`0x00a1`，原样保留未命中关键字的行）

```
0041: iget-object v2, v8, .../NotesRepository$save$2;.this$0:...NotesRepository; // field@0094
0043: invoke-static {v2}, ...NotesRepository;.access$getFileOps$p:(...)L...NotesFileOps; // method@018b
0046: move-result-object v2
0047: invoke-interface {v2, v1}, ...NotesFileOps;.createTemp:(Ljava/io/File;)Ljava/io/File; // method@0160
004a: move-result-object v2          <-- createTemp 的返回值进入 v2
004b: nop // spacer
004c: iget-object v3, v8, .../NotesRepository$save$2;.this$0:...NotesRepository; // field@0094
004e: invoke-static {v3}, ...NotesRepository;.access$getFileOps$p:(...)L...NotesFileOps; // method@018b
0051: move-result-object v3
0052: invoke-interface {v3, v2}, ...NotesFileOps;.openStream:(Ljava/io/File;)Ljava/io/FileOutputStream; // method@0161
                                                      ^^ 参数 v2 即 0x004a 的 createTemp 返回值
```

从 `0x004a` 到 `0x0052` 之间只有两条指令：`iget-object v3` 取 `this$0`、
`access$getFileOps$p` 取 `fileOps`，随后 `move-result-object v3`。
**中间没有 `access$getFile`**——也就是没有把字段 `file`（正式笔记文件）取到 `openStream` 的参数位置。
变异形态（`openStream(file)`）必须在这个位置插入一次 `access$getFile`，这里不存在。

后续链路同样连续：

```
005e: invoke-static ... access$getFileOps$p  -> 0061: move-result-object v7
0062: invoke-interface {v7, v5, v0}, ...NotesFileOps;.write:(Ljava/io/FileOutputStream;[B)V
0065: invoke-static ... access$getFileOps$p  -> 0068: move-result-object v4
0069: invoke-interface {v4, v5}, ...NotesFileOps;.sync:(Ljava/io/FileOutputStream;)V
0070: invoke-static {v3, v4}, Lkotlin/io/CloseableKt;.closeFinally:...        <-- use{} 结束
0073..0078: 取 this$0 -> access$getFileOps$p -> move-result-object v3
0079: iget-object v4, v8, ...;.this$0
007b: invoke-static {v4}, ...NotesRepository;.access$getFile:(...)Ljava/io/File;
007e: move-result-object v4
007f: invoke-interface {v3, v2, v4}, ...NotesFileOps;.rename:(Ljava/io/File;Ljava/io/File;)Z
0082: move-result v3
0083: if-eqz v3, 008d
0085: invoke-virtual {v2}, Ljava/io/File;.delete:()Z          <-- 成功路径 finally 清理临时文件
008d..0094: new IOException("Cannot replace notes file") -> throw
009c..00a0: move-exception v3 -> invoke-virtual {v2}, Ljava/io/File;.delete:()Z -> throw v3  <-- 异常路径同样清理
```

`0x007f` 的第三参数 `v4` 来自 `0x007b` 的 `access$getFile`，即 `rename(temp, file)`；
`0x0085` 与 `0x009d` 两次 `delete()` 的对象都是 `v2`（临时文件），与源码
`finally { temp.delete() }` 一致。

### 8.3 这组片段能证明什么、不能证明什么

- **能证明**：「本地留存 APK 的已检查路径（`save()` 的写入目标、`rename` 两参数、临时文件清理）与临时文件写入实现一致。」
- **不能证明**：设备上已安装的那份 APK 字节相同（从未做设备端哈希比对）；
  也不能证明该 APK 与源码之间存在可加密验证的绑定。
- **不支持**任何关于「真实底层文件系统故障」「掉电安全」「目录 fsync」的结论——
  这些从未被验证（`rename` 之后没有对 `filesDir` 做 fsync）。

---

## 9. 本轮未执行 / 失败 / 计划外

**未执行**：

- 未运行任何测试、未编译（按任务要求，本轮不要求重跑）。
- 未打包、未安装、未卸载、未清数据、未回滚、未删除测试包、未重跑真机、未读取设备端哈希。
- 未合并 main，未建 PR，未变基，未 reset/clean。
- 未更新任何已发布分支引用（含源码分支）。

**失败**：

- `git push` 与 `git ls-remote` 均因代理层 `CONNECT tunnel failed, response 502` 失败，
  本地提交未上远端。这是本轮唯一未完成项。

**计划外情况**：

- 无。核验结果与预期完全一致，六文件 blob 一次命中，暂存统计 `+802 −6` 一次命中。
- 唯一补充动作：为满足「连续片段」要求，在本机对留存 APK 重新执行了一次 `dexdump`
  （非重建、非 adb），并已在 8.1 明确标注其性质。

---

## 10. 下一步（等审阅方下发，不自行推进）

- 网络恢复后重跑 `git push origin HEAD:refs/heads/fix/backup-safe-1`，把 `d252ed3` 推上去。
- 文档是否发布到 `review/*` 分支，等回传后由审阅方决定；本轮只做本地。
- 「备份导入保护」不在本轮范围，未提前实现。
