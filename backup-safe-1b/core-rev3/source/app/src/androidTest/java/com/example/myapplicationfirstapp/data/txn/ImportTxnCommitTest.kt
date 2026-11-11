package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 提交路径：材料校验、备份、替换、不可取消段、清理。
 *
 * 失败注入一律是 `injected-io-failure:<step>`，用来把「第几步失败」固定下来；
 * 它证明的是内核在这一点上的处置，不等价于真实磁盘故障。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnCommitTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"},{"id":"n2","title":"第二"}]"""
    private val oldAttachment = mapOf("a.jpg" to byteArrayOf(1, 1, 1, 1))
    private val newAttachment = mapOf("a.jpg" to byteArrayOf(2, 2, 2, 2), "b.jpg" to byteArrayOf(3, 3))

    private suspend fun commit(
        ops: FaultyOps,
        txnId: String = "t1",
        notes: String = newNotes,
        attachments: Map<String, ByteArray> = newAttachment,
        expected: String? = null
    ): CommitOutcome {
        val kernel = sandbox.kernel(ops)
        val materialized = kernel.materialize(txnId, notes.toByteArray(), attachments)
        assertTrue("材料落地失败：$materialized", materialized is MaterializeOutcome.Ready)
        val digest = (materialized as MaterializeOutcome.Ready).commitDigest
        return kernel.commit(MaterialRef(txnId, expected ?: digest))
    }

    private fun seed() {
        sandbox.writeNotes(oldNotes)
        oldAttachment.forEach { (name, bytes) -> sandbox.writeAttachment(name, bytes) }
    }

    @Test
    fun happyPathCommitsReplacesAttachmentsAndCleansUp() = runBlocking {
        seed()
        val ops = FaultyOps()
        val outcome = commit(ops)

        assertTrue("应已提交，实际=$outcome", outcome is CommitOutcome.Committed)
        assertEquals(2, (outcome as CommitOutcome.Committed).count)
        assertTrue(outcome.cleanup.cleaned)

        sandbox.expectNotes(newNotes, "笔记应为新载荷")
        sandbox.expectAttachments(newAttachment, "附件应为新内容")
        assertFalse("journal 应已清理", sandbox.journalFile().exists())
        assertFalse("凭据应已清理", sandbox.credentialFile().exists())
        assertFalse("事务目录应已清理", sandbox.txnDir("t1").exists())
        assertTrue("临时文件未清干净：${sandbox.tempFiles()}", sandbox.tempFiles().isEmpty())

        val record = sandbox.results().single()
        assertEquals(ResultOutcome.COMMITTED, record.outcome)
        assertEquals("t1", record.txnId)
        assertEquals(DiskState.Idle, sandbox.kernel().inspect())
    }

    @Test
    fun materialChangedIsNotStartedAndTouchesNothing() = runBlocking {
        seed()
        val outcome = commit(FaultyOps(), expected = "0".repeat(64))

        assertTrue("应拒绝，实际=$outcome", outcome is CommitOutcome.NotStarted)
        assertEquals("material-changed", (outcome as CommitOutcome.NotStarted).reason)
        sandbox.expectNotes(oldNotes, "笔记不得被改动")
        assertFalse(sandbox.journalFile().exists())
    }

    @Test
    fun journalWriteFailureLeavesLiveDataUntouched() = runBlocking {
        seed()
        val ops = FaultyOps().apply { failOnce += TxnStep.JOURNAL }
        val outcome = commit(ops)

        assertTrue(outcome is CommitOutcome.NotStarted)
        assertEquals("journal-write-failed", (outcome as CommitOutcome.NotStarted).reason)
        sandbox.expectNotes(oldNotes, "笔记不得被改动")
        assertTrue("附件不得被改动", sandbox.attachment("a.jpg").readBytes().contentEquals(oldAttachment.getValue("a.jpg")))
    }

    @Test
    fun unreadableNotesIsNotStarted() = runBlocking {
        // 让 notes.json 变成一个目录：存在但读不出来，必须拒绝而不是当成「没有笔记」
        sandbox.notesFile().mkdirs()
        val outcome = commit(FaultyOps())

        assertTrue(outcome is CommitOutcome.NotStarted)
        assertEquals("notes-unreadable", (outcome as CommitOutcome.NotStarted).reason)
    }

    @Test
    fun insufficientSpaceIsNotStarted() = runBlocking {
        seed()
        val ops = FaultyOps().apply { forcedUsableSpace = 0L }
        val outcome = commit(ops)

        assertTrue(outcome is CommitOutcome.NotStarted)
        assertEquals("insufficient-space", (outcome as CommitOutcome.NotStarted).reason)
        sandbox.expectNotes(oldNotes, "笔记不得被改动")
    }

    @Test
    fun unsafeTxnIdIsRejected() = runBlocking {
        seed()
        val outcome = sandbox.kernel().commit(MaterialRef("../escape"))

        assertTrue(outcome is CommitOutcome.NotStarted)
        assertEquals("unsafe-txn-id", (outcome as CommitOutcome.NotStarted).reason)
    }

    @Test
    fun identicalNotesDifferentAttachmentsRollsBackAttachmentsOnly() = runBlocking {
        // 反例：新旧 notes 字节完全相同，只有附件不同。回滚不能因为「notes 已经是新内容」就跳过，
        // 也不能因为「notes 没变」就以为事务没发生过。
        sandbox.writeNotes(oldNotes)
        sandbox.writeAttachment("a.jpg", byteArrayOf(1, 1, 1, 1))
        val ops = FaultyOps().apply { failOnce += TxnStep.NOTES }
        val outcome = commit(ops, notes = oldNotes, attachments = mapOf("a.jpg" to byteArrayOf(9, 9), "b.jpg" to byteArrayOf(8)))

        assertTrue("应回滚，实际=$outcome", outcome is CommitOutcome.Failed)
        sandbox.expectNotes(oldNotes, "新旧 notes 相同，字节必须保持")
        sandbox.expectAttachments(mapOf("a.jpg" to byteArrayOf(1, 1, 1, 1)), "附件必须回到旧内容，新增附件必须删掉")
        assertEquals(DiskState.Idle, sandbox.kernel().inspect())
    }

    @Test
    fun absentOldNotesIsCreatedWithoutCopy() = runBlocking {
        val ops = FaultyOps()
        val outcome = commit(ops, notes = newNotes, attachments = mapOf("a.jpg" to byteArrayOf(7)))

        assertTrue(outcome is CommitOutcome.Committed)
        sandbox.expectNotes(newNotes, "旧文件不存在时应新建")
        assertFalse(
            "旧 notes 不存在时不应产生副本",
            ops.sawCheckpoint(TxnStep.OLD_NOTES)
        )
        assertEquals(DiskState.Idle, sandbox.kernel().inspect())
    }

    @Test
    fun activeJournalRefusesSecondCommit() = runBlocking {
        sandbox.plant(
            PlantedTxn("t0", oldNotes, newNotes, oldAttachment, newAttachment),
            notesState = NotesState.OLD
        )
        val outcome = commit(FaultyOps(), txnId = "t1")

        assertTrue(outcome is CommitOutcome.NotStarted)
        assertTrue(
            "应说明已有事务，实际=${(outcome as CommitOutcome.NotStarted).reason}",
            outcome.reason.startsWith("journal-present")
        )
    }

    @Test
    fun quarantinedTransactionBlocksNewCommit() = runBlocking {
        File(sandbox.root, ".import/txn-zombie").mkdirs()
        val outcome = commit(FaultyOps(), txnId = "t1")

        assertTrue(outcome is CommitOutcome.NotStarted)
        assertEquals("quarantined:txn-zombie", (outcome as CommitOutcome.NotStarted).reason)
        assertTrue("隔离目录不得被顺带删掉", File(sandbox.root, ".import/txn-zombie").isDirectory)
    }

    @Test
    fun commitRefusesWhenGateIsBlocked() = runBlocking {
        seed()
        val gate = WriteGate().apply { enter(WriteGate.Mode.BLOCKED) }
        val kernel = sandbox.kernel(gate = gate)
        val materialized = kernel.materialize("t1", newNotes.toByteArray(), newAttachment)
        val digest = (materialized as MaterializeOutcome.Ready).commitDigest

        val outcome = kernel.commit(MaterialRef("t1", digest))
        assertTrue("写前应被拒，实际=$outcome", outcome is CommitOutcome.Refused)
        sandbox.expectNotes(oldNotes, "被拒时不得有任何写入")
    }
}
