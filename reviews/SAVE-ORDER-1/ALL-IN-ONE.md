# SAVE-ORDER-1 验证证据合集（单文件版）
> 汇总：5 项 NotesWriteCoordinatorTest + 6 项 BackupNotesParserTest 全部通过；编译通过；未提交、未推送、未合并、未装真机。
> 本文件把报告、diff、新增文件、JUnit XML 与构建/测试日志全部内联，适合一次性读取；也可分别读取 `evidence/` 下的独立文件。

---

## 1. 验证报告

# SAVE-ORDER-1 验证报告（实验 worktree）

日期：2026-11-11 13:16（GMT+8）
发布：本文件由执行方在独立 worktree 内完成，未修改任何业务代码
包：`D:\SAVE-ORDER-1-review-bundle.zip`（7,282 B / 5 条目 / `unzip -t` 无错）
解压：`D:\SAVE-ORDER-1\`
实验 worktree：`D:\SAVE-ORDER-1-wt`
主工程：`D:\myappappappappapp`（未触碰）

## 结论

补丁在 `fcac718` 基线上成功应用，编译通过，**11 项仪器测试全部通过（0 失败 0 错误 0 跳过）**。
未提交、未推送、未合并 main、未安装到真机。

## 1. 远端与基线同步

| 命令 | 退出码 | 输出 |
|---|---|---|
| `git remote -v` | 0 | `origin https://github.com/HOSDIVALL/appppp.git`（fetch/push 同） |
| `git ls-remote --heads origin main fix/backup-safe-1` | 0 | `fcac718c8c20f869f0a2836defb7c62ef211068f refs/heads/fix/backup-safe-1`；`61dff28e4041aa5a8d9376ba1da872a87a009301 refs/heads/main` |
| `git fetch origin refs/heads/fix/backup-safe-1:refs/remotes/origin/fix/backup-safe-1` | 0 | `* [new branch] fix/backup-safe-1 -> origin/fix/backup-safe-1` |
| `git cat-file -e fcac718…^{commit}` | 0 | 存在 |
| `git show --stat --oneline fcac718…` | 0 | `test: add explicit backup JSON parsing and regression cases`；`BackupNotesParserTest.kt +63`、`BackupNotesParser.kt +23`，2 files / 86 insertions |
| `git merge-base --is-ancestor 61dff28… fcac718…` | 0 | 是祖先（整改分支建立在基线之上） |

远端地址与两个分支 SHA 均与预期一致，未更换 remote，未重置任何工作区。

## 2. worktree 快进

| 命令 | 退出码 | 输出 |
|---|---|---|
| `git -C D:/SAVE-ORDER-1-wt status --short` | 0 | 空（快进前干净） |
| `git -C D:/SAVE-ORDER-1-wt merge --ff-only origin/fix/backup-safe-1` | 0 | `Updating 61dff28..fcac718` / `Fast-forward` / 2 files changed, 86 insertions(+) |

最终 HEAD：`fcac718c8c20f869f0a2836defb7c62ef211068f`
`BackupNotesParser.kt`（861 B）、`BackupNotesParserTest.kt`（1,960 B，6 个 `@Test`）均存在。

## 3. dry-run 复跑

```
python apply_save_order.py D:/SAVE-ORDER-1-wt
Validated files: 4
Dry run only; no files changed. Review README before using --apply.
退出码 0
```

前置条件复核：
- `NotesRepository.kt` blob = `9edd7200777a937cef15c3a3a256e02b684059c6`（与 payload 一致）
- `NotesState.kt` blob = `0ab7f3f88820f37f8a582e381467d276a2c490b2`（与 payload 一致）
- `NotesWriteCoordinator.kt` / `NotesWriteCoordinatorTest.kt` 均不存在

## 4. 应用与差异检查

```
python apply_save_order.py D:/SAVE-ORDER-1-wt --apply
Validated files: 4
Applied to worktree only. No commit, push, build, or installation performed.
退出码 0
```

`git diff --check` 退出码 **0**（无空白错误）。
`git diff --stat`：2 files changed, 36 insertions(+), 12 deletions(-)。
完整 diff 见 `logs/applied.diff`；两个新增文件副本见 `logs/new-*.kt`。

新增文件（未跟踪）：
- `app/src/main/java/com/example/myapplicationfirstapp/data/NotesWriteCoordinator.kt`（1,819 B）
- `app/src/androidTest/java/com/example/myapplicationfirstapp/data/NotesWriteCoordinatorTest.kt`（2,719 B，5 个 `@Test`）

## 5. 编译

```
./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --offline
```

- 退出码 **0**，`BUILD SUCCESSFUL in 1m 50s`，25 tasks executed
- `e:` 错误行 0；`w:` 警告 12 行，全部为既有废弃 API（`rememberModalBottomSheetState`、旧 `ListItem(headlineContent)`、
  `SearchBarDefaults.InputField`、`Icons.Filled.Sort/NoteAdd`、`RecorderState.stopPlayback`），与本次补丁无关
- 日志：`logs/compile.log`

## 6. 仪器测试

设备：`emulator-5554`，AVD `Medium_Phone_API_37.0`，API 37 / Android 17，
model `sdk_gphone64_x86_64`，分辨率 1080×2400，密度 420。无真机连接，未安装到任何真机。

### 6.1 NotesWriteCoordinatorTest（5 项）

```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplicationfirstapp.data.NotesWriteCoordinatorTest
BUILD SUCCESSFUL in 59s  退出码 0
```

XML：`tests=5 failures=0 errors=0 skipped=0`

| 测试 | 结果 | 耗时 |
|---|---|---|
| olderCannotOverwriteNewer | PASS | 0.005s |
| failedWriteCanRetry | PASS | 0.006s |
| replacementInvalidatesWaitingOldSave | PASS | 0.035s |
| failedReplacementPreservesPendingTicket | PASS | 0.007s |
| foreignTicketIsRejected | PASS | 0.074s |

### 6.2 BackupNotesParserTest（6 项）

```
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplicationfirstapp.data.BackupNotesParserTest
BUILD SUCCESSFUL in 23s  退出码 0
```

XML：`tests=6 failures=0 errors=0 skipped=0`

| 测试 | 结果 | 耗时 |
|---|---|---|
| acceptsEmptyArray | PASS | 0.001s |
| roundTripsCurrentNote | PASS | 0.012s |
| preservesLegacyOptionalDefaults | PASS | 0.028s |
| rejectsMalformedInput | PASS | 0.005s |
| rejectsWrongRootAndInvalidElements | PASS | 0.037s |
| rejectsTrailingContentButAcceptsWhitespace | PASS | 0.005s |

XML 归档：`logs/xml/run1-coordinator.xml`、`logs/xml/run2-parser.xml`

## 7. 环境改动（仅构建前提，不改版本）

- `app/libs/sherpa-onnx-2.0.1.aar`：从主工程复制，SHA-256 前 16 位 `a8d09d1d32f3eb85`，与主工程**同一文件**
- `local.properties`：从主工程复制（`sdk.dir` 指向本机 SDK），被 `.gitignore` 排除
- `app/build.gradle.kts`、`gradle/libs.versions.toml`：`git diff` 为空，**零改动**
- 唯一联网动作：`connectedDebugAndroidTest` 需要 `com.android.tools.utp:gradle-work-action:32.5.0`，
  本地缓存缺失，故该步去掉 `--offline` 由 AGP 自取；该构件为测试工具链，不改任何声明版本

## 8. 未执行 / 失败项

- **无失败项**。首轮 `connectedDebugAndroidTest` 曾因 `--offline` 缺 UTP 构件失败（退出码 1，属环境缺件），
  去掉 `--offline` 后成功；代码未因此改动。
- 未执行：`assembleRelease`、lint、真机安装、任何 `git commit` / `push` / `merge`。
- 未验证（超出本包范围，如实标注）：进程被杀死后的保存丢失、`rename`/`fd.sync` 在真实故障下的行为、
  无目录 fsync 的耐久性、`replace()` 与导入流程的接入、保存失败的用户可见反馈。

## 9. 工作区状态

| 位置 | 分支 | HEAD | 改动 |
|---|---|---|---|
| 主工程 `D:\myappappappappapp` | `main` | `61dff28` | 0 |
| 实验 worktree `D:\SAVE-ORDER-1-wt` | `fix/backup-safe-1` | `fcac718` | 2 modified + 2 untracked（未提交） |

## 10. 证据文件

```
D:\SAVE-ORDER-1\
├─ logs\applied.diff                     应用后的完整 diff（modified 两文件）
├─ logs\new-NotesWriteCoordinator.kt     新增文件副本
├─ logs\new-NotesWriteCoordinatorTest.kt 新增文件副本
├─ logs\compile.log                      编译日志
├─ logs\test-coordinator.log             第一组测试日志
├─ logs\test-parser.log                  第二组测试日志
├─ logs\xml\run1-coordinator.xml         JUnit XML（5 项）
└─ logs\xml\run2-parser.xml              JUnit XML（6 项）
```


---

## 2. 应用后完整 diff

`applied.diff`

`````diff
diff --git a/app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt b/app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt
index 9edd720..fdcd1d1 100644
--- a/app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt
+++ b/app/src/main/java/com/example/myapplicationfirstapp/data/NotesRepository.kt
@@ -36,17 +36,22 @@ class NotesRepository(context: Context) {
         runCatching { notesFromJson(file.readText()) }.getOrDefault(emptyList())
     }
 
-    /** 保存全部笔记 */
+    /** Write a unique temporary file; propagate failures without truncating the old file. */
     suspend fun save(notes: List<Note>) = withContext(Dispatchers.IO) {
-        runCatching {
-            val json = notesToJson(notes)
-            // 先写临时文件再改名：写到一半被杀死也不会把原文件写坏
-            val temp = File(file.parentFile, "$NOTES_FILE_NAME.tmp")
-            temp.writeText(json)
+        val json = notesToJson(notes).toByteArray(Charsets.UTF_8)
+        val parent = checkNotNull(file.parentFile)
+        check(parent.isDirectory || parent.mkdirs()) { "Cannot create notes directory" }
+        val temp = File.createTempFile("notes-", ".tmp", parent)
+        try {
+            temp.outputStream().use { output ->
+                output.write(json)
+                output.fd.sync()
+            }
             if (!temp.renameTo(file)) {
-                file.writeText(json)
-                temp.delete()
+                throw java.io.IOException("Cannot replace notes file")
             }
+        } finally {
+            temp.delete()
         }
         Unit
     }
diff --git a/app/src/main/java/com/example/myapplicationfirstapp/ui/NotesState.kt b/app/src/main/java/com/example/myapplicationfirstapp/ui/NotesState.kt
index 0ab7f3f..e61287b 100644
--- a/app/src/main/java/com/example/myapplicationfirstapp/ui/NotesState.kt
+++ b/app/src/main/java/com/example/myapplicationfirstapp/ui/NotesState.kt
@@ -1244,12 +1244,31 @@ class NotesState(
 
 
 
-    private fun persist() {
-
-        val snapshot = _notes.toList()
+    /** Observable diagnostic only; UI presentation/retry is a separate integration task. */
+    var persistenceError by mutableStateOf<String?>(null)
+        private set
 
-        scope.launch { repository.save(snapshot) }
+    private val writeCoordinator = com.example.myapplicationfirstapp.data.NotesWriteCoordinator()
+    private var newestSaveTicket: com.example.myapplicationfirstapp.data.NotesWriteCoordinator.Ticket? = null
 
+    private fun persist() {
+        val snapshot = _notes.toList()
+        // No replace() calls are introduced in this patch. Before adding them,
+        // guard state mutations before they happen; never silently drop a save.
+        val ticket = checkNotNull(writeCoordinator.reserve()) { "Replacement is active" }
+        newestSaveTicket = ticket
+        scope.launch {
+            try {
+                val written = writeCoordinator.save(ticket) { repository.save(snapshot) }
+                if (written && newestSaveTicket === ticket) persistenceError = null
+            } catch (cancelled: kotlinx.coroutines.CancellationException) {
+                throw cancelled
+            } catch (failure: Exception) {
+                if (newestSaveTicket === ticket) {
+                    persistenceError = failure.message ?: "Saving notes failed"
+                }
+            }
+        }
     }
 
 
`````

---

## 3. 新增文件 NotesWriteCoordinator.kt

`new-NotesWriteCoordinator.kt`

`````kotlin
package com.example.myapplicationfirstapp.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ordering only; this does not provide a disk transaction or process-death recovery. */
internal class NotesWriteCoordinator {
    internal class Ticket internal constructor(
        internal val owner: Any,
        internal val generation: Long,
        internal val sequence: Long
    )
    private val gate = Any()
    private val writer = Mutex()
    private var generation = 0L
    private var sequence = 0L
    private var savedSequence = 0L
    private var replacing = false

    fun reserve(): Ticket? = synchronized(gate) {
        if (replacing) null else Ticket(gate, generation, ++sequence)
    }

    suspend fun save(ticket: Ticket, write: suspend () -> Unit): Boolean = writer.withLock {
        val current = synchronized(gate) {
            require(ticket.owner === gate) { "Ticket belongs to another store" }
            ticket.generation == generation && ticket.sequence > savedSequence
        }
        if (!current) return@withLock false
        write()
        synchronized(gate) { savedSequence = ticket.sequence }
        true
    }

    /**
     * Not used by the application in this incremental patch.
     * Requires rollback/recovery, pre-mutation guards, and cancellation handling
     * at the durable commit boundary before import integration is safe.
     */
    suspend fun <T> replace(commit: suspend () -> T): T = writer.withLock {
        synchronized(gate) { replacing = true }
        try {
            val result = commit()
            synchronized(gate) {
                generation++
                savedSequence = sequence
            }
            result
        } finally {
            synchronized(gate) { replacing = false }
        }
    }
}
`````

---

## 4. 新增文件 NotesWriteCoordinatorTest.kt

`new-NotesWriteCoordinatorTest.kt`

`````kotlin
package com.example.myapplicationfirstapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class NotesWriteCoordinatorTest {
    @Test fun olderCannotOverwriteNewer() = runBlocking {
        val c = NotesWriteCoordinator()
        val old = c.reserve()!!
        val fresh = c.reserve()!!
        var disk = "initial"
        assertTrue(c.save(fresh) { disk = "fresh" })
        assertFalse(c.save(old) { disk = "old" })
        assertEquals("fresh", disk)
    }
    @Test fun failedWriteCanRetry() = runBlocking {
        val c = NotesWriteCoordinator()
        val ticket = c.reserve()!!
        try {
            c.save(ticket) { throw IOException("injected") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertTrue(c.save(ticket) { })
        assertFalse(c.save(ticket) { fail("Duplicate write") })
    }
    @Test fun replacementInvalidatesWaitingOldSave() = runBlocking {
        val c = NotesWriteCoordinator()
        val old = c.reserve()!!
        val release = CompletableDeferred<Unit>()
        var disk = "initial"
        var saved: Boolean? = null
        val replace = launch(start = CoroutineStart.UNDISPATCHED) {
            c.replace {
                assertNull(c.reserve())
                release.await()
                disk = "imported"
            }
        }
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            saved = c.save(old) { disk = "stale" }
        }
        release.complete(Unit)
        replace.join()
        save.join()
        assertEquals(false, saved)
        assertEquals("imported", disk)
        assertTrue(c.save(c.reserve()!!) { disk = "edited" })
        assertEquals("edited", disk)
    }
    @Test fun failedReplacementPreservesPendingTicket() = runBlocking {
        val c = NotesWriteCoordinator()
        val ticket = c.reserve()!!
        try {
            c.replace<Unit> { throw IOException("before commit") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertTrue(c.save(ticket) { })
        assertNotNull(c.reserve())
    }
    @Test fun foreignTicketIsRejected() = runBlocking {
        val first = NotesWriteCoordinator()
        val second = NotesWriteCoordinator()
        try {
            second.save(first.reserve()!!) { fail("Foreign ticket") }
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
`````

---

## 5. JUnit XML — NotesWriteCoordinatorTest

`run1-coordinator.xml`

`````xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="5" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-11-11T05:16:40" hostname="localhost">
  <testsuite name="com.example.myapplicationfirstapp.data.NotesWriteCoordinatorTest" tests="5" failures="0" errors="0" skipped="0" time="0.127" timestamp="2026-11-11T05:16:40" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5554" />
    </properties>
    <testcase name="foreignTicketIsRejected" classname="com.example.myapplicationfirstapp.data.NotesWriteCoordinatorTest" time="0.074" />
    <testcase name="replacementInvalidatesWaitingOldSave" classname="com.example.myapplicationfirstapp.data.NotesWriteCoordinatorTest" time="0.035" />
    <testcase name="failedWriteCanRetry" classname="com.example.myapplicationfirstapp.data.NotesWriteCoordinatorTest" time="0.006" />
    <testcase name="failedReplacementPreservesPendingTicket" classname="com.example.myapplicationfirstapp.data.NotesWriteCoordinatorTest" time="0.007" />
    <testcase name="olderCannotOverwriteNewer" classname="com.example.myapplicationfirstapp.data.NotesWriteCoordinatorTest" time="0.005" />
  </testsuite>
</testsuites>
`````

---

## 6. JUnit XML — BackupNotesParserTest

`run2-parser.xml`

`````xml
<?xml version='1.0' encoding='UTF-8' ?>
<testsuites tests="6" failures="0" errors="0" skipped="0" time="0.000" timestamp="2026-11-11T05:17:14" hostname="localhost">
  <testsuite name="com.example.myapplicationfirstapp.data.BackupNotesParserTest" tests="6" failures="0" errors="0" skipped="0" time="0.088" timestamp="2026-11-11T05:17:14" hostname="localhost">
    <properties>
      <property name="device" value="emulator-5554" />
    </properties>
    <testcase name="acceptsEmptyArray" classname="com.example.myapplicationfirstapp.data.BackupNotesParserTest" time="0.001" />
    <testcase name="rejectsWrongRootAndInvalidElements" classname="com.example.myapplicationfirstapp.data.BackupNotesParserTest" time="0.037" />
    <testcase name="preservesLegacyOptionalDefaults" classname="com.example.myapplicationfirstapp.data.BackupNotesParserTest" time="0.028" />
    <testcase name="roundTripsCurrentNote" classname="com.example.myapplicationfirstapp.data.BackupNotesParserTest" time="0.012" />
    <testcase name="rejectsMalformedInput" classname="com.example.myapplicationfirstapp.data.BackupNotesParserTest" time="0.005" />
    <testcase name="rejectsTrailingContentButAcceptsWhitespace" classname="com.example.myapplicationfirstapp.data.BackupNotesParserTest" time="0.005" />
  </testsuite>
</testsuites>
`````

---

## 7. 编译日志

`compile.log`

`````
Starting a Gradle Daemon, 1 incompatible and 1 stopped Daemons could not be reused, use --status for details
Calculating task graph as no cached configuration is available for tasks: :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:preDebugAndroidTestBuild SKIPPED
> Task :app:processDebugAndroidTestNavigationResources
> Task :app:generateDebugResources
> Task :app:extractDeepLinksDebug
> Task :app:generateDebugAndroidTestResources
> Task :app:createDebugCompatibleScreenManifests
> Task :app:javaPreCompileDebug
> Task :app:mapDebugSourceSetPaths
> Task :app:packageDebugAndroidTestResources
> Task :app:packageDebugResources
> Task :app:parseDebugAndroidTestLocalResources
> Task :app:generateDebugAndroidTestRFile
> Task :app:processDebugNavigationResources
> Task :app:compileDebugNavigationResources
> Task :app:parseDebugLocalResources
> Task :app:generateDebugRFile
> Task :app:checkDebugAarMetadata
> Task :app:processDebugMainManifest
> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:mergeDebugResources
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/AudioPickSheet.kt:53:22 'fun rememberModalBottomSheetState(skipPartiallyExpanded: Boolean = ..., confirmValueChange: (SheetValue) -> Boolean = ...): SheetState' is deprecated. Use rememberBottomSheetState with Hidden initial value.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt:770:13 'fun ListItem(headlineContent: @Composable() ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: @Composable() ComposableFunction0<Unit>? = ..., supportingContent: @Composable() ComposableFunction0<Unit>? = ..., leadingContent: @Composable() ComposableFunction0<Unit>? = ..., trailingContent: @Composable() ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/NoteEditorScreen.kt:779:13 'fun ListItem(headlineContent: @Composable() ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: @Composable() ComposableFunction0<Unit>? = ..., supportingContent: @Composable() ComposableFunction0<Unit>? = ..., leadingContent: @Composable() ComposableFunction0<Unit>? = ..., trailingContent: @Composable() ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/NotePickerSheet.kt:59:22 'fun rememberModalBottomSheetState(skipPartiallyExpanded: Boolean = ..., confirmValueChange: (SheetValue) -> Boolean = ...): SheetState' is deprecated. Use rememberBottomSheetState with Hidden initial value.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/NotesListScreen.kt:289:35 'fun InputField(query: String, onQueryChange: (String) -> Unit, onSearch: (String) -> Unit, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, modifier: Modifier = ..., enabled: Boolean = ..., placeholder: @Composable() ComposableFunction0<Unit>? = ..., leadingIcon: @Composable() ComposableFunction0<Unit>? = ..., trailingIcon: @Composable() ComposableFunction0<Unit>? = ..., colors: TextFieldColors = ..., interactionSource: MutableInteractionSource? = ...): Unit' is deprecated. Use SearchBarDefaults.InputField with TextFieldState and SearchBarState.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/NotesListScreen.kt:756:44 'val Icons.Filled.Sort: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.Sort.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/RecorderState.kt:379:9 'fun stopPlayback(): Unit' is deprecated. 保留旧名字，外部调用语义未变.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/RecordingActionSheet.kt:74:22 'fun rememberModalBottomSheetState(skipPartiallyExpanded: Boolean = ..., confirmValueChange: (SheetValue) -> Boolean = ...): SheetState' is deprecated. Use rememberBottomSheetState with Hidden initial value.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/RecordingActionSheet.kt:150:41 'val Icons.Filled.NoteAdd: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.NoteAdd.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/ReminderSheet.kt:52:22 'fun rememberModalBottomSheetState(skipPartiallyExpanded: Boolean = ..., confirmValueChange: (SheetValue) -> Boolean = ...): SheetState' is deprecated. Use rememberBottomSheetState with Hidden initial value.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/ReminderSheet.kt:173:5 'fun ListItem(headlineContent: @Composable() ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: @Composable() ComposableFunction0<Unit>? = ..., supportingContent: @Composable() ComposableFunction0<Unit>? = ..., leadingContent: @Composable() ComposableFunction0<Unit>? = ..., trailingContent: @Composable() ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.
w: file:///D:/SAVE-ORDER-1-wt/app/src/main/java/com/example/myapplicationfirstapp/ui/SettingsScreen.kt:486:5 'fun ListItem(headlineContent: @Composable() ComposableFunction0<Unit>, modifier: Modifier = ..., overlineContent: @Composable() ComposableFunction0<Unit>? = ..., supportingContent: @Composable() ComposableFunction0<Unit>? = ..., leadingContent: @Composable() ComposableFunction0<Unit>? = ..., trailingContent: @Composable() ComposableFunction0<Unit>? = ..., colors: ListItemColors = ..., tonalElevation: Dp = ..., shadowElevation: Dp = ...): Unit' is deprecated. Use the overload where `headlineContent` is now a trailing `content` lambda.

> Task :app:compileDebugJavaWithJavac
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugAndroidTestKotlin

BUILD SUCCESSFUL in 1m 50s
25 actionable tasks: 25 executed
Configuration cache entry stored.
`````

---

## 8. 测试日志 — NotesWriteCoordinatorTest

`test-coordinator.log`

`````
Starting a Gradle Daemon, 1 incompatible and 3 stopped Daemons could not be reused, use --status for details
Calculating task graph as no cached configuration is available for tasks: :app:connectedDebugAndroidTest
> Task :app:generateDebugAndroidTestAssets UP-TO-DATE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:preBuild UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:preDebugAndroidTestBuild SKIPPED
> Task :app:generateDebugAndroidTestResources UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:processDebugAndroidTestNavigationResources UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:packageDebugAndroidTestResources UP-TO-DATE
> Task :app:parseDebugAndroidTestLocalResources UP-TO-DATE
> Task :app:generateDebugAndroidTestRFile UP-TO-DATE
> Task :app:writeDebugAppMetadata
> Task :app:javaPreCompileDebugAndroidTest
> Task :app:mergeDebugJniLibFolders
> Task :app:desugarDebugAndroidTestFileDependencies
> Task :app:writeDebugSigningConfigVersions
> Task :app:validateSigningDebug
> Task :app:mapDebugAndroidTestSourceSetPaths
> Task :app:compileDebugAndroidTestNavigationResources
> Task :app:mergeDebugAndroidTestJniLibFolders
> Task :app:validateSigningDebugAndroidTest
> Task :app:writeDebugAndroidTestSigningConfigVersions
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:processDebugNavigationResources UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:compileDebugNavigationResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:generateDebugRFile UP-TO-DATE
> Task :app:mergeDebugAssets
> Task :app:compressDebugAssets
> Task :app:mergeDebugAndroidTestAssets
> Task :app:checkDebugAndroidTestAarMetadata
> Task :app:compressDebugAndroidTestAssets
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:checkDebugAndroidTestDuplicateClasses
> Task :app:compressDebugJavaRes
> Task :app:mergeDebugAndroidTestManifest
> Task :app:processDebugAndroidTestManifest
> Task :app:mergeLibDexDebugAndroidTest
> Task :app:compileDebugAndroidTestKotlin UP-TO-DATE
> Task :app:compileDebugAndroidTestJavaWithJavac NO-SOURCE
> Task :app:compressDebugAndroidTestJavaRes
> Task :app:checkDebugDuplicateClasses
> Task :app:mergeDebugNativeLibs
> Task :app:mergeDebugAndroidTestNativeLibs NO-SOURCE
> Task :app:stripDebugAndroidTestDebugSymbols NO-SOURCE
> Task :app:generateDebugGlobalSynthetics
> Task :app:generateDebugAndroidTestGlobalSynthetics
> Task :app:mergeLibDexDebug
> Task :app:mergeDebugAndroidTestJavaResource

> Task :app:stripDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so, libonnxruntime.so, libsherpa-onnx-c-api.so, libsherpa-onnx-cxx-api.so, libsherpa-onnx-jni.so. Run with --info option to learn more.

> Task :app:desugarDebugFileDependencies
> Task :app:mergeExtDexDebugAndroidTest
> Task :app:mergeDebugJavaResource
> Task :app:mergeDebugAndroidTestResources
> Task :app:processDebugAndroidTestResources
> Task :app:dexBuilderDebug
> Task :app:mergeProjectDexDebug
> Task :app:dexBuilderDebugAndroidTest
> Task :app:mergeProjectDexDebugAndroidTest
> Task :app:packageDebugAndroidTest
> Task :app:createDebugAndroidTestApkListingFileRedirect
> Task :app:mergeExtDexDebug
> Task :app:packageDebug
> Task :app:createDebugApkListingFileRedirect

> Task :app:connectedDebugAndroidTest
Running tests on devices: Medium_Phone_API_37.0(AVD) - 17

WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::arrayBaseOffset has been called by com.google.protobuf.UnsafeUtil$MemoryAccessor (file:/C:/Users/Mechrevo/.gradle/caches/modules-2/files-2.1/com.google.protobuf/protobuf-java/3.25.5/5ae5c9ec39930ae9b5a61b32b93288818ec05ec1/protobuf-java-3.25.5.jar)
WARNING: Please consider reporting this to the maintainers of class com.google.protobuf.UnsafeUtil$MemoryAccessor
WARNING: sun.misc.Unsafe::arrayBaseOffset will be removed in a future release

BUILD SUCCESSFUL in 59s
69 actionable tasks: 44 executed, 25 up-to-date
Configuration cache entry stored.
`````

---

## 9. 测试日志 — BackupNotesParserTest

`test-parser.log`

`````
Starting a Gradle Daemon, 1 incompatible and 4 stopped Daemons could not be reused, use --status for details
Reusing configuration cache.
> Task :app:preBuild UP-TO-DATE
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:generateDebugAndroidTestAssets UP-TO-DATE
> Task :app:preDebugBuild UP-TO-DATE
> Task :app:preDebugAndroidTestBuild SKIPPED
> Task :app:generateDebugGlobalSynthetics UP-TO-DATE
> Task :app:javaPreCompileDebugAndroidTest UP-TO-DATE
> Task :app:mergeDebugJniLibFolders UP-TO-DATE
> Task :app:mapDebugSourceSetPaths UP-TO-DATE
> Task :app:generateDebugAndroidTestResources UP-TO-DATE
> Task :app:validateSigningDebug UP-TO-DATE
> Task :app:javaPreCompileDebug UP-TO-DATE
> Task :app:createDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:generateDebugResources UP-TO-DATE
> Task :app:processDebugAndroidTestNavigationResources UP-TO-DATE
> Task :app:extractDeepLinksDebug UP-TO-DATE
> Task :app:writeDebugSigningConfigVersions UP-TO-DATE
> Task :app:writeDebugAppMetadata UP-TO-DATE
> Task :app:generateDebugAndroidTestGlobalSynthetics UP-TO-DATE
> Task :app:desugarDebugAndroidTestFileDependencies UP-TO-DATE
> Task :app:desugarDebugFileDependencies UP-TO-DATE
> Task :app:mapDebugAndroidTestSourceSetPaths UP-TO-DATE
> Task :app:mergeDebugAndroidTestJniLibFolders UP-TO-DATE
> Task :app:validateSigningDebugAndroidTest UP-TO-DATE
> Task :app:compileDebugAndroidTestNavigationResources UP-TO-DATE
> Task :app:writeDebugAndroidTestSigningConfigVersions UP-TO-DATE
> Task :app:packageDebugAndroidTestResources UP-TO-DATE
> Task :app:parseDebugAndroidTestLocalResources UP-TO-DATE
> Task :app:generateDebugAndroidTestRFile UP-TO-DATE
> Task :app:packageDebugResources UP-TO-DATE
> Task :app:checkDebugDuplicateClasses UP-TO-DATE
> Task :app:mergeLibDexDebug UP-TO-DATE
> Task :app:mergeDebugAssets UP-TO-DATE
> Task :app:compressDebugAssets UP-TO-DATE
> Task :app:processDebugNavigationResources UP-TO-DATE
> Task :app:checkDebugAarMetadata UP-TO-DATE
> Task :app:compileDebugNavigationResources UP-TO-DATE
> Task :app:parseDebugLocalResources UP-TO-DATE
> Task :app:generateDebugRFile UP-TO-DATE
> Task :app:mergeDebugAndroidTestAssets UP-TO-DATE
> Task :app:compressDebugAndroidTestAssets UP-TO-DATE
> Task :app:mergeDebugResources UP-TO-DATE
> Task :app:checkDebugAndroidTestAarMetadata UP-TO-DATE
> Task :app:checkDebugAndroidTestDuplicateClasses UP-TO-DATE
> Task :app:mergeDebugAndroidTestResources UP-TO-DATE
> Task :app:mergeLibDexDebugAndroidTest UP-TO-DATE
> Task :app:mergeDebugNativeLibs UP-TO-DATE
> Task :app:stripDebugDebugSymbols UP-TO-DATE
> Task :app:mergeExtDexDebug UP-TO-DATE
> Task :app:processDebugMainManifest UP-TO-DATE
> Task :app:mergeDebugAndroidTestManifest UP-TO-DATE
> Task :app:processDebugManifest UP-TO-DATE
> Task :app:processDebugManifestForPackage UP-TO-DATE
> Task :app:processDebugAndroidTestManifest UP-TO-DATE
> Task :app:mergeDebugAndroidTestNativeLibs NO-SOURCE
> Task :app:stripDebugAndroidTestDebugSymbols NO-SOURCE
> Task :app:mergeExtDexDebugAndroidTest UP-TO-DATE
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compressDebugJavaRes UP-TO-DATE
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:mergeDebugJavaResource UP-TO-DATE
> Task :app:processDebugResources UP-TO-DATE
> Task :app:processDebugAndroidTestResources UP-TO-DATE
> Task :app:bundleDebugClassesToCompileJar UP-TO-DATE
> Task :app:dexBuilderDebug UP-TO-DATE
> Task :app:mergeProjectDexDebug UP-TO-DATE
> Task :app:compileDebugAndroidTestKotlin UP-TO-DATE
> Task :app:compileDebugAndroidTestJavaWithJavac NO-SOURCE
> Task :app:compressDebugAndroidTestJavaRes UP-TO-DATE
> Task :app:packageDebug UP-TO-DATE
> Task :app:createDebugApkListingFileRedirect UP-TO-DATE
> Task :app:dexBuilderDebugAndroidTest UP-TO-DATE
> Task :app:mergeProjectDexDebugAndroidTest UP-TO-DATE
> Task :app:mergeDebugAndroidTestJavaResource UP-TO-DATE
> Task :app:packageDebugAndroidTest UP-TO-DATE
> Task :app:createDebugAndroidTestApkListingFileRedirect UP-TO-DATE

> Task :app:connectedDebugAndroidTest
Running tests on devices: Medium_Phone_API_37.0(AVD) - 17

WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::arrayBaseOffset has been called by com.google.protobuf.UnsafeUtil$MemoryAccessor (file:/C:/Users/Mechrevo/.gradle/caches/modules-2/files-2.1/com.google.protobuf/protobuf-java/3.25.5/5ae5c9ec39930ae9b5a61b32b93288818ec05ec1/protobuf-java-3.25.5.jar)
WARNING: Please consider reporting this to the maintainers of class com.google.protobuf.UnsafeUtil$MemoryAccessor
WARNING: sun.misc.Unsafe::arrayBaseOffset will be removed in a future release

BUILD SUCCESSFUL in 23s
69 actionable tasks: 1 executed, 68 up-to-date
Configuration cache entry reused.
`````
