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
 * 清理：顺序固定（结果 → 材料 → 目录 → journal → 凭据），关键步骤失败即停。
 *
 * 每个中断点都必须满足同一件事：**结论仍是已提交**，不能被重新解释成需要回滚。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnCleanupTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val oldA = byteArrayOf(1, 1, 1)
    private val newA = byteArrayOf(2, 2, 2)

    private fun plantCommitted(txnId: String = "t1") {
        sandbox.plant(
            PlantedTxn(txnId, oldNotes, newNotes, mapOf("a.jpg" to oldA), mapOf("a.jpg" to newA)),
            notesState = NotesState.NEW,
            journalState = JournalState.COMMITTED,
            writeCredential = true
        )
    }

    @Test
    fun journalDeleteFailureKeepsCredentialAndCommitFact() = runBlocking {
        plantCommitted()
        val ops = FaultyOps().apply { failOnce += TxnStep.JOURNAL }
        val kernel = sandbox.kernel(ops)

        val outcome = kernel.recover()
        assertTrue(outcome is RecoverOutcome.Settled)
        assertEquals("journal", (outcome as RecoverOutcome.Settled).cleanup?.stoppedAt)
        assertEquals(DiskState.Committed("t1"), kernel.inspect())
        assertTrue("journal 应仍在", sandbox.journalFile().isFile)
        assertTrue("凭据应仍在", sandbox.credentialFile().isFile)
        assertFalse("材料应已删完", sandbox.txnDir("t1").exists())
        sandbox.expectNotes(newNotes, "已提交的笔记不得被回滚")

        val second = kernel.recover()
        assertTrue((second as RecoverOutcome.Settled).cleanup?.cleaned == true)
        assertEquals(DiskState.Idle, kernel.inspect())
    }

    @Test
    fun credentialDeleteFailureKeepsJournalGoneAndMaterialsGone() = runBlocking {
        plantCommitted()
        val ops = FaultyOps().apply { failOnce += TxnStep.CREDENTIAL }
        val kernel = sandbox.kernel(ops)

        val outcome = kernel.recover()
        assertEquals("credential", (outcome as RecoverOutcome.Settled).cleanup?.stoppedAt)
        assertEquals(DiskState.Committed("t1"), kernel.inspect())
        assertFalse("journal 已删", sandbox.journalFile().exists())
        assertTrue("凭据仍在", sandbox.credentialFile().isFile)
        assertFalse(sandbox.txnDir("t1").exists())

        kernel.recover()
        assertEquals(DiskState.Idle, kernel.inspect())
    }

    @Test
    fun materialDeleteFailureStopsBeforeEvidence() = runBlocking {
        plantCommitted()
        val ops = FaultyOps().apply { failOnce += "delete-material:old-attachments/a.jpg" }
        val kernel = sandbox.kernel(ops)

        val outcome = kernel.recover()
        assertEquals("materials", (outcome as RecoverOutcome.Settled).cleanup?.stoppedAt)
        assertEquals(DiskState.Committed("t1"), kernel.inspect())
        assertTrue("journal 必须保留", sandbox.journalFile().isFile)
        assertTrue("凭据必须保留", sandbox.credentialFile().isFile)
        assertTrue("材料仍在", sandbox.txnDir("t1").isDirectory)

        kernel.recover()
        assertEquals(DiskState.Idle, kernel.inspect())
    }

    @Test
    fun materialsAreNeverLeftWithoutEvidence() = runBlocking {
        plantCommitted()
        val ops = FaultyOps().apply { alwaysFail += "delete-material:old-attachments/a.jpg" }
        val kernel = sandbox.kernel(ops)

        repeat(3) { kernel.recover() }

        // 材料删不掉时，绝不能把 journal/凭据删掉——否则会退化成无法判定的隔离态
        assertTrue(sandbox.txnDir("t1").isDirectory)
        assertTrue(sandbox.journalFile().isFile)
        assertTrue(sandbox.credentialFile().isFile)
        assertEquals(DiskState.Committed("t1"), kernel.inspect())

        ops.alwaysFail.clear()
        kernel.recover()
        assertEquals(DiskState.Idle, kernel.inspect())
    }

    @Test
    fun resultWriteFailureStopsBeforeAnyDeletion() = runBlocking {
        plantCommitted()
        val ops = FaultyOps().apply { alwaysFail += TxnStep.RESULTS }
        val kernel = sandbox.kernel(ops)

        val outcome = kernel.recover()
        assertEquals("results", (outcome as RecoverOutcome.Settled).cleanup?.stoppedAt)
        assertEquals(DiskState.Committed("t1"), kernel.inspect())
        assertTrue("结果没写成就不能开始删", sandbox.txnDir("t1").isDirectory)
        assertTrue(sandbox.journalFile().isFile)
        assertTrue(sandbox.credentialFile().isFile)

        ops.alwaysFail.clear()
        kernel.recover()
        assertEquals(DiskState.Idle, kernel.inspect())
        assertNotNull(sandbox.results().firstOrNull { it.txnId == "t1" })
    }

    @Test
    fun cleanupRefusesInterruptedStateAndKeepsMaterials() = runBlocking {
        sandbox.plant(
            PlantedTxn("t1", oldNotes, newNotes, mapOf("a.jpg" to oldA), mapOf("a.jpg" to newA)),
            notesState = NotesState.NEW
        )
        val kernel = sandbox.kernel()

        val outcome = kernel.cleanup("t1")
        assertFalse(outcome.cleaned)
        assertEquals("state", outcome.stoppedAt)
        assertTrue(sandbox.txnDir("t1").isDirectory)
        assertTrue(sandbox.journalFile().isFile)
        assertEquals(DiskState.Interrupted("t1"), kernel.inspect())
    }

    @Test
    fun resultsRemainQueryableByTxnIdAfterCleanup() = runBlocking {
        plantCommitted()
        val kernel = sandbox.kernel()

        kernel.recover()
        assertEquals(DiskState.Idle, kernel.inspect())
        assertTrue("结果留存不得被清理删掉", sandbox.resultsFile().isFile)

        val record = kernel.currentResult("t1")
        assertNotNull(record)
        assertEquals(ResultOutcome.COMMITTED, record!!.outcome)
        assertFalse(record.consumed)
        assertTrue(kernel.markConsumed("t1"))
        assertTrue(kernel.currentResult("t1")!!.consumed)
    }

    @Test
    fun ordinarySaveAfterCleanupIsNotMistakenForCorruption() = runBlocking {
        plantCommitted()
        val kernel = sandbox.kernel()
        kernel.recover()

        // 用户在导入之后继续编辑并保存：内容与旧事务摘要不同，但这不是损坏
        sandbox.writeNotes("""[{"id":"n1","title":"导入后再编辑"}]""")
        assertEquals(DiskState.Idle, kernel.inspect())
        assertNotNull("历史结果仍应可查", kernel.currentResult("t1"))
    }

    @Test
    fun resultsAreCappedAndDeduplicated() = runBlocking {
        val kernel = sandbox.kernel()
        sandbox.writeNotes(oldNotes)

        for (index in 1..10) {
            val txnId = "t$index"
            val materialized = kernel.materialize(txnId, newNotes.toByteArray(), mapOf("a.jpg" to newA))
            val digest = (materialized as MaterializeOutcome.Ready).commitDigest
            val outcome = kernel.commit(MaterialRef(txnId, digest))
            assertTrue("第 $index 次提交失败：$outcome", outcome is CommitOutcome.Committed)
        }

        val records = kernel.results()
        assertEquals("结果留存上限", 8, records.size)
        assertEquals("最新的在最前", "t10", records.first().txnId)

        val again = kernel.materialize("t1", newNotes.toByteArray(), mapOf("a.jpg" to newA))
        kernel.commit(MaterialRef("t1", (again as MaterializeOutcome.Ready).commitDigest))
        val after = kernel.results()
        assertEquals(8, after.size)
        assertEquals("同一 txnId 不应重复", 1, after.count { it.txnId == "t1" })
        assertEquals("t1", after.first().txnId)
    }
}
