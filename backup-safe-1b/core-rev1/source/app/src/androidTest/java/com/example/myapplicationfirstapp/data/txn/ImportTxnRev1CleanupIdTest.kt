package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * core-rev1 回归（B2）：`cleanup(txnId)` 必须先把**参数**与**终态 txnId** 对上。
 *
 * 传错 ID 时不得写结果、不得删材料、更不得删全局证据（journal / 凭据）——
 * 否则一次参数错误就会把另一个事务的提交事实抹掉。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev1CleanupIdTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val oldA = byteArrayOf(1, 1, 1, 1)
    private val newA = byteArrayOf(2, 2, 2, 2)

    private fun plantCommitted(txnId: String = "t1") {
        sandbox.plant(
            PlantedTxn(txnId, oldNotes, newNotes, mapOf("a.jpg" to oldA), mapOf("a.jpg" to newA)),
            notesState = NotesState.NEW,
            journalState = JournalState.COMMITTED,
            writeCredential = true
        )
    }

    @Test
    fun cleanupWithWrongTxnIdTouchesNothing() = runBlocking {
        plantCommitted()
        val kernel = sandbox.kernel()

        val outcome = kernel.cleanup("t9")
        assertFalse("参数与终态 txnId 不符时不得清理，实际=$outcome", outcome.cleaned)
        assertEquals("txn-id", outcome.stoppedAt)
        assertTrue("材料必须保留", sandbox.txnDir("t1").isDirectory)
        assertTrue("journal 必须保留", sandbox.journalFile().isFile)
        assertTrue("凭据必须保留", sandbox.credentialFile().isFile)
        assertTrue("不得为错误 txnId 写结果：${sandbox.results()}", sandbox.results().isEmpty())
        assertEquals(DiskState.Committed("t1"), kernel.inspect())
        sandbox.expectNotes(newNotes, "不得改动正式数据")
    }

    @Test
    fun wrongTxnIdDoesNotConsumeTheRealCleanup() = runBlocking {
        plantCommitted()
        val kernel = sandbox.kernel()
        kernel.cleanup("t9")

        val outcome = kernel.cleanup("t1")
        assertTrue("正确 txnId 仍应能清理，实际=$outcome", outcome.cleaned)
        assertEquals(DiskState.Idle, kernel.inspect())
        assertNotNull("结果必须在正确 txnId 下留存", kernel.currentResult("t1"))
        assertEquals("错误 txnId 不得留下记录", null, kernel.currentResult("t9"))
    }

    @Test
    fun cleanupAfterAbortedAlsoChecksTxnId() = runBlocking {
        sandbox.plant(
            PlantedTxn("t1", oldNotes, newNotes, mapOf("a.jpg" to oldA), mapOf("a.jpg" to newA)),
            notesState = NotesState.OLD,
            journalState = JournalState.ABORTED
        )
        val kernel = sandbox.kernel()

        val wrong = kernel.cleanup("t2")
        assertFalse("回滚终态同样要核对 txnId", wrong.cleaned)
        assertEquals("txn-id", wrong.stoppedAt)
        assertTrue(sandbox.txnDir("t1").isDirectory)
        assertTrue(sandbox.journalFile().isFile)
    }
}
