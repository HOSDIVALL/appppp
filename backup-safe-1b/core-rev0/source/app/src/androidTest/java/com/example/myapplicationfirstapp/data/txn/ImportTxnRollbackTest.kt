package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 回滚：只从 `APPLYING` / `ROLLING_BACK` 进入，逐文件比较「当前 vs 目标」，重复中断可收敛。
 *
 * 现场用 [TxnSandbox.plant] 直接摆盘，不靠让 `commit` 崩溃来制造半应用状态。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRollbackTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val oldA = byteArrayOf(1, 1, 1)
    private val newA = byteArrayOf(2, 2, 2)
    private val newB = byteArrayOf(3, 3)

    private fun spec() = PlantedTxn(
        txnId = "t1",
        oldNotes = oldNotes,
        newNotes = newNotes,
        oldAttachments = mapOf("a.jpg" to oldA),
        newAttachments = mapOf("a.jpg" to newA, "b.jpg" to newB)
    )

    @Test
    fun notesReplacedWithoutCredentialRestoresOld() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeCredential = false)
        val kernel = sandbox.kernel()
        assertEquals(DiskState.Interrupted("t1"), kernel.inspect())

        val outcome = kernel.recover()
        assertTrue("应收敛，实际=$outcome", outcome is RecoverOutcome.Settled)
        sandbox.expectNotes(oldNotes, "笔记必须回滚到旧内容")
        sandbox.expectAttachments(mapOf("a.jpg" to oldA), "附件必须回滚，新增附件必须删除")
        assertEquals(DiskState.Idle, kernel.inspect())
        assertEquals(ResultOutcome.ABORTED, sandbox.results().single().outcome)
    }

    @Test
    fun rollbackWithoutOldNotesDeletesTheFile() = runBlocking {
        sandbox.plant(
            PlantedTxn("t1", null, newNotes, emptyMap(), mapOf("a.jpg" to newA)),
            notesState = NotesState.NEW
        )
        val kernel = sandbox.kernel()

        kernel.recover()
        assertNull("旧 notes 不存在时，回滚目标是删除", sandbox.notesText())
        assertEquals(DiskState.Idle, kernel.inspect())
    }

    @Test
    fun onlyAppliedAttachmentsAreRestored() = runBlocking {
        // a.jpg 已替换，b.jpg 还没来得及写：回滚只恢复 a，b 本来就不存在
        sandbox.plant(
            spec().copy(appliedAttachments = mapOf("a.jpg" to newA)),
            notesState = NotesState.NEW
        )
        val kernel = sandbox.kernel()

        kernel.recover()
        sandbox.expectAttachments(mapOf("a.jpg" to oldA), "只应恢复已应用的附件")
    }

    @Test
    fun repeatedInterruptionsStillConverge() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val ops = FaultyOps()
        val kernel = sandbox.kernel(ops)

        ops.failOnce += "old-attachment:a.jpg"
        val first = kernel.recover()
        assertTrue("第一次应停在回滚失败，实际=$first", first is RecoverOutcome.Stopped)
        val journalAfterFirst = sandbox.journal()
        assertNotNull(journalAfterFirst)
        assertEquals(JournalState.ROLLING_BACK, journalAfterFirst!!.state)
        assertNotNull("失败位置要落盘", journalAfterFirst.failedAt)

        ops.failOnce += "delete-material:attachments/b.jpg"
        val second = kernel.recover()
        assertTrue("第二次仍应停在回滚失败，实际=$second", second is RecoverOutcome.Stopped)
        assertTrue("第一次已恢复的附件不应被改回去", sandbox.attachment("a.jpg").readBytes().contentEquals(oldA))

        val third = kernel.recover()
        assertTrue("第三次应收敛，实际=$third", third is RecoverOutcome.Settled)
        sandbox.expectNotes(oldNotes, "最终必须回到旧笔记")
        sandbox.expectAttachments(mapOf("a.jpg" to oldA), "最终必须回到旧附件")
        assertEquals(DiskState.Idle, kernel.inspect())
    }

    @Test
    fun missingOldCopyStopsAndKeepsMaterials() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        File(sandbox.txnDir("t1"), "old-notes.json").delete()

        val kernel = sandbox.kernel()
        val outcome = kernel.recover()

        assertTrue("必须停写，实际=$outcome", outcome is RecoverOutcome.Stopped)
        assertTrue((outcome as RecoverOutcome.Stopped).state is DiskState.RollbackFailed)
        sandbox.expectNotes(newNotes, "副本损坏时不得猜测，正式数据保持原样")
        assertTrue("材料必须保留", sandbox.txnDir("t1").isDirectory)
        assertTrue("journal 必须保留", sandbox.journalFile().isFile)
        assertEquals(JournalState.ROLLING_BACK, sandbox.journal()?.state)
    }

    @Test
    fun corruptedCopyWithCorrectDigestInJournalStopsWrite() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        // 副本内容被改坏，journal 里记的仍是原始摘要
        File(sandbox.txnDir("t1"), "old-notes.json").writeText("""[{"id":"n1","title":"被改过的"}]""")

        val outcome = sandbox.kernel().recover()
        assertTrue(outcome is RecoverOutcome.Stopped)
        sandbox.expectNotes(newNotes, "副本摘要不符时不得写回")
        assertTrue(sandbox.txnDir("t1").isDirectory)
    }

    @Test
    fun rollbackRefusesCommittedTransaction() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeCredential = true, journalState = JournalState.COMMITTED)

        val outcome = sandbox.kernel().rollback("t1")
        assertTrue("已提交不得回滚，实际=$outcome", outcome is RollbackOutcome.Failed)
        sandbox.expectNotes(newNotes, "已提交的笔记不得被回滚")
        assertTrue(sandbox.credentialFile().isFile)
    }

    @Test
    fun rollbackThenCleanupLeavesNothingBehind() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val kernel = sandbox.kernel()

        kernel.recover()
        assertFalse(sandbox.txnDir("t1").exists())
        assertFalse(sandbox.journalFile().exists())
        assertFalse(sandbox.credentialFile().exists())
        assertTrue("临时文件未清干净：${sandbox.tempFiles()}", sandbox.tempFiles().isEmpty())
        assertEquals(DiskState.Idle, kernel.inspect())
    }
}
