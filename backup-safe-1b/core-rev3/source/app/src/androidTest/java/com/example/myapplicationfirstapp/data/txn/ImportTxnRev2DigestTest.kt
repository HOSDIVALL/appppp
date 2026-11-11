package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * core-rev2 回归（R2）：判定用的摘要必须保留 `Absent / Unreadable / Present` 三态。
 *
 * rev1 的 `notesDigest()` / `digestOrNull()` 把 IOException 与目录占位都折叠成 `null`，
 * 于是「读不出来」被当成「文件不存在」。最危险的一处在回滚里：`old == null` 的分支
 * 只要读到 `null` 就判 `SKIP`，回滚直接宣称完成、写 `ABORTED`、把材料删掉。
 *
 * 这些用例只换「读」的故障面，不伪造真实磁盘损坏：注入的仍然是 IOException。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev2DigestTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val oldA = byteArrayOf(1, 1, 1, 1)
    private val newA = byteArrayOf(2, 2, 2, 2)
    private val newB = byteArrayOf(3, 3)

    /** 有旧 notes 的常规现场 */
    private fun spec() = PlantedTxn(
        txnId = "t1",
        oldNotes = oldNotes,
        newNotes = newNotes,
        oldAttachments = mapOf("a.jpg" to oldA),
        newAttachments = mapOf("a.jpg" to newA, "b.jpg" to newB)
    )

    /** 导入前没有 notes 的现场：`oldNotesDigest == null`，最容易「读不到就当不存在」 */
    private fun newOnlySpec() = PlantedTxn(
        txnId = "t1",
        oldNotes = null,
        newNotes = newNotes,
        oldAttachments = emptyMap(),
        newAttachments = mapOf("a.jpg" to newA)
    )

    @Test
    fun unreadableNotesWithNoOldCopyIsNotTreatedAsRestored() = runBlocking {
        sandbox.plant(newOnlySpec(), notesState = NotesState.NEW)
        val ops = FaultyOps().apply { failReadOf("notes.json") }
        val gate = WriteGate()
        val kernel = sandbox.kernel(ops, gate)

        val outcome = kernel.rollback("t1")
        assertTrue("读不出 notes 就不能宣称已回滚，实际=$outcome", outcome is RollbackOutcome.Failed)
        assertNotNull("journal 不得被清掉", sandbox.journal())
        assertFalse(
            "不得写成 ABORTED",
            sandbox.journal()?.state == JournalState.ABORTED
        )
        assertTrue("事务材料必须保留", sandbox.txnDir("t1").isDirectory)
        assertTrue("必须继续停写", gate.beginWrite() is WriteGate.Admission.Refused)
    }

    @Test
    fun unreadableNotesWithOldCopyReportsUnreadableNotMissing() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val ops = FaultyOps().apply { failReadOf("notes.json") }
        val kernel = sandbox.kernel(ops)

        val outcome = kernel.rollback("t1")
        assertTrue("必须停手，实际=$outcome", outcome is RollbackOutcome.Failed)
        assertTrue(
            "原因必须是读不出来，而不是不存在：$outcome",
            (outcome as RollbackOutcome.Failed).detail.contains("unreadable")
        )
        assertNotNull("journal 不得被清掉", sandbox.journal())
    }

    @Test
    fun unreadableNewAttachmentWithNoOldCopyStopsRollback() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val ops = FaultyOps().apply { failReadOf("b.jpg") }
        val gate = WriteGate()
        val kernel = sandbox.kernel(ops, gate)

        val outcome = kernel.rollback("t1")
        assertTrue("读不出附件就不能判它不存在，实际=$outcome", outcome is RollbackOutcome.Failed)
        assertTrue(
            "失败位置必须点名读不出的附件：$outcome",
            (outcome as RollbackOutcome.Failed).detail.contains("b.jpg")
        )
        assertFalse("不得写成 ABORTED", sandbox.journal()?.state == JournalState.ABORTED)
        assertTrue("事务材料必须保留", sandbox.txnDir("t1").isDirectory)
        assertTrue("必须继续停写", gate.beginWrite() is WriteGate.Admission.Refused)
    }

    @Test
    fun directoryPlaceholderForNewAttachmentIsNotAbsent() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val placeholder = sandbox.attachment("b.jpg")
        placeholder.delete()
        assertTrue("占位目录没建起来", placeholder.mkdir())
        val gate = WriteGate()
        val kernel = sandbox.kernel(gate = gate)

        val outcome = kernel.rollback("t1")
        assertTrue("目录占位不等于文件不存在，实际=$outcome", outcome is RollbackOutcome.Failed)
        assertFalse("不得写成 ABORTED", sandbox.journal()?.state == JournalState.ABORTED)
        assertTrue("事务材料必须保留", sandbox.txnDir("t1").isDirectory)
    }

    @Test
    fun classifierReportsUnreadableNotesNotChangedNotes() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.OLD, journalState = JournalState.PREPARED)
        val ops = FaultyOps().apply { failReadOf("notes.json") }
        val kernel = sandbox.kernel(ops)

        val state = kernel.inspect()
        assertTrue("必须判为不可判定，实际=$state", state is DiskState.Corrupt)
        assertTrue(
            "原因必须是读不出来，而不是内容变了：$state",
            (state as DiskState.Corrupt).reason.contains("unreadable")
        )
    }

    @Test
    fun directoryPlaceholderForNotesIsNotAbsent() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.OLD, journalState = JournalState.PREPARED)
        val notes = sandbox.notesFile()
        notes.delete()
        assertTrue("占位目录没建起来", notes.mkdir())
        val kernel = sandbox.kernel()

        val state = kernel.inspect()
        assertTrue("必须判为不可判定，实际=$state", state is DiskState.Corrupt)
        assertTrue(
            "目录占位必须报 unreadable：$state",
            (state as DiskState.Corrupt).reason.contains("unreadable")
        )
    }

    @Test
    fun healthySceneStillRollsBack() = runBlocking {
        // 对照组：不注入故障时行为不变，避免把「修 R2」做成「一律停手」
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val kernel = sandbox.kernel()

        val outcome = kernel.rollback("t1")
        assertTrue("正常现场必须能回滚，实际=$outcome", outcome is RollbackOutcome.Restored)
        sandbox.expectNotes(oldNotes, "回滚后 notes 应为旧内容")
        assertEquals(oldA.toList(), File(sandbox.attachmentsDir(), "a.jpg").readBytes().toList())
        assertFalse("b.jpg 是本次新增，必须移除", sandbox.attachment("b.jpg").exists())
    }
}
