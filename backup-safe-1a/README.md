# BACKUP-SAFE-1A · 导入准备与验证层（源码附件版）

基线：`d252ed3c2a9780c305b7a1be515186695a8e9c72`（`fix/backup-safe-1`，远端已同步）
状态：**已实施、未提交、未推送、未合并 main、未接入任何生产调用链**
1B（提交/回滚/恢复）：**仅方案**，见 `BACKUP-SAFE-1A-REPORT.md` §8

一句话：本批只回答「输入是否可信」，**没有**回答「导入是否安全」——因为 1A 一个字都不写正式数据。

---

## 1. 目录

| 路径 | 内容 |
| --- | --- |
| `README.md` | 本文件：范围、应用方式、证据、请质疑的点 |
| `BACKUP-SAFE-1A-REPORT.md` | 完整报告（含 §8 修订后的 1B 状态机与失败矩阵） |
| `BACKUP-SAFE-1A.diff` | 完整补丁：8 个新增文件含全文 + 1 个修改文件 |
| `src/main/…` `src/androidTest/…` | 九个文件的完整源码，按仓库相对路径镜像，可单文件阅读 |
| `logs/compile.log` | `compileDebugKotlin` + `compileDebugAndroidTestKotlin` 输出，退出码 0 |
| `logs/full-androidtest.xml` | 全量 instrumentation 结果 `tests=62 failures=0 errors=0` |
| `MANIFEST.sha256` | 上述所有文件的 sha256 与字节数，可直接 `sha256sum -c` 前逐行核对 |

---

## 2. 补丁用法（已实测）

在 `d252ed3` 的干净工作树上：

```bash
git status --short          # 期望为空
git apply --check BACKUP-SAFE-1A.diff
git apply BACKUP-SAFE-1A.diff
```

等价：`patch -p1 < BACKUP-SAFE-1A.diff`

两条命令都在 `d252ed3` 的干净导出上实测：`git apply --check` 通过、`patch -p1` 无 reject 无 fuzz。

**字节级复现请用 `patch -p1`**：在 `git archive d252ed3` 展开的普通目录里打补丁，九个文件与实验工作区 sha256 **9/9 完全一致**。
`git apply` 的产物会受本机 `core.autocrlf` 影响（这台机器 system gitconfig 是 `true`，实测产物被转成 CRLF、字节数变化、内容不变），
若要按 blob 哈希比对，请先 `git -c core.autocrlf=false apply …`。

换行符：仓库内本目录的 Markdown 与源码为 **LF**；本地报告原件是 CRLF，两者仅换行符不同
（`diff --strip-trailing-cr` 无差异，28,860 → 28,380 字节）。`logs/compile.log` 保留 Gradle 原始输出未做规范化。

实测记录：在 `git archive d252ed3` 的干净导出上执行 `patch -p1`，九个文件全部成功、无 reject、无 fuzz；
打补丁后逐文件比对 sha256，**9/9 与实验工作区完全一致**。补丁自带新文件全文，不依赖任何本地未提交内容。

---

## 3. 九个文件

| 文件 | 行 | blob |
| --- | --- | --- |
| `data/BackupImporter.kt`（新） | 524 | `5bbb8ce` |
| `data/BackupImportModels.kt`（新） | 179 | `b10b32f` |
| `data/BackupFileOps.kt`（新） | 81 | `d546901` |
| `androidTest/…/BackupImporterPrepareTest.kt`（新） | 270 | `decb054` |
| `androidTest/…/BackupImporterValidationTest.kt`（新） | 264 | `5f986f3` |
| `androidTest/…/ImportTestSupport.kt`（新） | 222 | `8771eee` |
| `androidTest/…/BackupImporterLimitsTest.kt`（新） | 163 | `84e2327` |
| `androidTest/…/BackupImporterCancelTest.kt`（新） | 106 | `97feed0` |
| `data/BackupNotesParser.kt`（改） | 32 | `002aef0`（+12 −3） |

合计 **+1821 −3**。唯一被改的既有文件只做一件拆解：把 `JSONArray` 解析抽成 `parseBackupArrayOrThrow`，
让导入层能先查字段类型再转 `Note`；原有行为与原有 6 项测试不变。

---

## 4. 本批实际行为

- **只读一次 URI**：`openSource(uri)` → `files/.import/staging-<uuid>/backup.zip`，此后所有解析与确认只读这份副本；测试用计数器断言 `openSource` 调用次数 == 1。
- **限额按实际字节**，不读 `ZipEntry.size`、不算压缩比：zip 128 MiB / notes.json 32 MiB / 单条 64 MiB / 累计 256 MiB / 条目 2000 / 名字 128 字符。未知条目丢弃内容但仍计入字节与条目数。有一条用例先断言 zip 本地头里声明 size 确为 0，再证明塞了 200 字节的条目照样被拦。
- **三态**：`Ready` / `Empty`（`[]`）/ `Invalid`（18 种原因）。`[]` 夹带非零附件 → 拒绝；`attachments/` 目录占位 → 放行；零字节附件不算实际附件。
- **告警不拒绝**：缺失图片列数量 + 前 5 个名字（控制字符过滤、40 字截断），引用原样保留；录音不在备份中作为静态提示，不读 live `recordings/`。
- **取消**：每块检查一次取消，抛出前删本次暂存；另给 `discard()` 供 UI 取消确认。

实现中修掉两个真问题：

1. `deletedAt: null` 经 `optLong` 会变成 `0`（=1970 年删除，重启就被当过期回收站清掉）——导入层加 `normalizedNote` 把显式 null 还原，不改 `noteFromJson` 原行为。
2. Android 的 `ZipInputStream` 对 `attachments/../x` 自己抛 `Invalid zip entry path`，原判 `NotZip` 是错的——改判 `DangerousPath`；另加 `CorruptEntry`，CRC/压缩流损坏不再被 `runCatching` 吞掉。

---

## 5. 证据

- 编译：`:app:compileDebugKotlin` + `:app:compileDebugAndroidTestKotlin`，退出码 0。
- 测试：`./gradlew :app:connectedDebugAndroidTest` → `tests=62 failures=0 errors=0`（新增 41 + 原有 21 无回归）。
- 隔离：每个用例 `@Before/@After` 比对真实 `notes.json` 的 sha256、`attachments` 条目、`recordings` 名字/长度/mtime；并额外断言准备层跑完后连沙箱内 `notes.json`、`attachments/` 都未被创建。

**过程问题照实写**：① 首轮 40 项有 2 失败，即上面两个缺陷，修完重跑通过；
② 全量跑时模拟器 `system_server` 崩了，AGP 仍报 `BUILD SUCCESSFUL` 但 XML `tests=0`——这种"假成功"没当证据，`adb reboot` 后重跑才拿到 62/62。判据一律以 XML 计数为准。

---

## 6. 不能从本批得出的结论

1. 不能说"导入已经安全"——写盘、回滚、恢复、UI、状态层门禁全部属于 1B，一行没写。
2. 不能说"断电安全"——1B 方案里也只有 journal 文件 fsync，目录没有 fsync。
3. 不能说"附件已恢复"——**备份格式本身不含录音文件**，只有 `audio` 引用；本批不扩展导出格式。
4. 不能说"图片可显示"——只校验引用路径安全性与存在性，不解码图片内容。
5. 时间语义（`createdAt`/`updatedAt`/`deletedAt` 的合理范围）未校验；非法 UTF-8 走替换策略；加密 zip 条目落到 `NotZip`/`CorruptEntry`。

---

## 7. 请重点质疑

1. `attachments/` 目录占位放行、零字节附件不计数的判定边界，是否有能构造出"实际有附件却按空备份确认"的包。
2. 长度/条目/字节上限是否覆盖"大量小条目"与"单条超大未知条目"两种耗尽路径；`copyEntry(zip, null, …)` 只计数不落盘的写法是否留有绕过。
3. `normalizedNote` 只修 `deletedAt`，其余可选字段（`remindAt`、`items`、`images`）的类型可疑值是否仍会被静默接受。
4. `parseBackupArrayOrThrow` 拆分后，`parseBackupNotesOrThrow` 的旧行为是否真的逐字等价（现有 6 项测试的覆盖是否够）。
5. 报告 §8 的 1B 状态机里，"未提交/已提交/回滚失败"的判定是否仍隐含依赖目标文件哈希或时序假设。

---

## 8. 边界声明

未提交、未推送源码分支、未合并 main、未操作真机（本批只连本地模拟器）、未上传任何真实笔记或附件。
`BackupImporter` 等新类只被测试引用；`NotesRepository`、`NotesState`、`MainActivity`、`NoteEditorScreen` 本批一行未改。
