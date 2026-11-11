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
 * core-rev1 回归（B1 / B6）。
 *
 * - B1：回滚只能由**磁盘分类**授权。`journal.state` 还写着 `APPLYING`、但凭据已完整匹配时，
 *   事务事实上已提交，回滚必须被拒。
 * - B6：回滚遇到既不是 `old` 也不是 `new` 的内容时停写、不覆盖；需要动用副本前先核对
 *   `old-manifest.json` 与 `journal` 是否一致。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev1RollbackTest : TxnKernelTestBase() {

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

    // ---------------- B1 ----------------

    @Test
    fun rollbackRefusedWhenApplyingButCredentialIsValid() = runBlocking {
        sandbox.plant(
            spec(),
            notesState = NotesState.NEW,
            journalState = JournalState.APPLYING,
            writeCredential = true
        )
        val kernel = sandbox.kernel()
        assertEquals("凭据与摘要都匹配就是已提交", DiskState.Committed("t1"), kernel.inspect())

        val outcome = kernel.rollback("t1")
        assertTrue("凭据成立时 journal 的 APPLYING 字样不能推翻提交事实，实际=$outcome", outcome is RollbackOutcome.Failed)
        sandbox.expectNotes(newNotes, "已提交的笔记不得被回滚")
        sandbox.expectAttachments(mapOf("a.jpg" to newA, "b.jpg" to newB), "已提交的附件不得被回滚")
        assertTrue("凭据必须保留", sandbox.credentialFile().isFile)
        assertEquals("journal 不得被改写成回滚态", JournalState.APPLYING, sandbox.journal()?.state)
    }

    @Test
    fun rollbackRefusedWhenStateIsCorrupt() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeJournal = false)
        sandbox.writeJournalRaw("{not-json")
        val kernel = sandbox.kernel()

        val outcome = kernel.rollback("t1")
        assertTrue("损坏态不得回滚，实际=$outcome", outcome is RollbackOutcome.Failed)
        sandbox.expectNotes(newNotes, "损坏态不得猜测回滚目标")
        assertTrue(sandbox.txnDir("t1").isDirectory)
    }

    @Test
    fun rollbackRefusedWhenQuarantined() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeJournal = false)
        val kernel = sandbox.kernel()
        assertTrue(kernel.inspect() is DiskState.Quarantined)

        val outcome = kernel.rollback("t1")
        assertTrue("隔离态不得回滚，实际=$outcome", outcome is RollbackOutcome.Failed)
        assertTrue("隔离材料不得被删除", sandbox.txnDir("t1").isDirectory)
    }

    @Test
    fun rollbackRefusedForDifferentTxnId() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val kernel = sandbox.kernel()

        val outcome = kernel.rollback("t2")
        assertTrue("txnId 不符不得回滚，实际=$outcome", outcome is RollbackOutcome.Failed)
        sandbox.expectNotes(newNotes, "不得因为传错 ID 改动数据")
        assertTrue(sandbox.txnDir("t1").isDirectory)
    }

    @Test
    fun rollbackRefusedForUnsafeTxnId() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val kernel = sandbox.kernel()

        val outcome = kernel.rollback("../escape")
        assertTrue("越界 txnId 必须拒绝，实际=$outcome", outcome is RollbackOutcome.Failed)
        sandbox.expectNotes(newNotes, "越界路径不得被解析")
    }

    // ---------------- B6 ----------------

    @Test
    fun rollbackStopsWhenAttachmentContentIsNeitherOldNorNew() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val foreign = byteArrayOf(9, 9, 9, 9)
        sandbox.attachment("a.jpg").writeBytes(foreign)

        val outcome = sandbox.kernel().recover()
        assertTrue("既非 old 也非 new 时必须停写，实际=$outcome", outcome is RecoverOutcome.Stopped)
        assertTrue(
            "不得覆盖来路不明的内容",
            sandbox.attachment("a.jpg").readBytes().contentEquals(foreign)
        )
        assertTrue("材料必须保留", sandbox.txnDir("t1").isDirectory)
        assertEquals(JournalState.ROLLING_BACK, sandbox.journal()?.state)
    }

    @Test
    fun rollbackStopsWhenNotesIsNeitherOldNorNew() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val foreign = """[{"id":"n1","title":"外部改动"}]"""
        sandbox.writeNotes(foreign)

        val outcome = sandbox.kernel().recover()
        assertTrue("既非 old 也非 new 时必须停写，实际=$outcome", outcome is RecoverOutcome.Stopped)
        sandbox.expectNotes(foreign, "来路不明的 notes 不得被覆盖")
        assertTrue("材料必须保留", sandbox.txnDir("t1").isDirectory)
    }

    @Test
    fun rollbackStopsWhenManifestIsMissing() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        File(sandbox.txnDir("t1"), "old-manifest.json").delete()

        val outcome = sandbox.kernel().recover()
        assertTrue("缺 manifest 时不得用副本覆盖，实际=$outcome", outcome is RecoverOutcome.Stopped)
        sandbox.expectNotes(newNotes, "缺 manifest 时正式数据保持原样")
        assertTrue("材料必须保留", sandbox.txnDir("t1").isDirectory)
    }

    @Test
    fun rollbackStopsWhenManifestDisagrees() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        File(sandbox.txnDir("t1"), "old-manifest.json").writeText(
            manifestJson(
                oldNotesDigest = "ff".repeat(32),
                attachments = listOf(JournalAttachment("a.jpg", "ee".repeat(32), newA.sha256()))
            )
        )

        val outcome = sandbox.kernel().recover()
        assertTrue("manifest 与 journal 不符时不得恢复，实际=$outcome", outcome is RecoverOutcome.Stopped)
        sandbox.expectNotes(newNotes, "manifest 不符时正式数据保持原样")
        assertTrue(sandbox.attachment("a.jpg").readBytes().contentEquals(newA))
    }

    @Test
    fun rollbackWithoutAnyRestoreDoesNotNeedManifest() = runBlocking {
        // 全部文件都已经是目标状态：不需要副本，也就不需要 manifest，重复中断仍应收敛
        sandbox.plant(
            spec().copy(appliedAttachments = mapOf("a.jpg" to oldA)),
            notesState = NotesState.OLD
        )
        File(sandbox.txnDir("t1"), "old-manifest.json").delete()

        val outcome = sandbox.kernel().recover()
        assertTrue("无需副本时不应被 manifest 卡住，实际=$outcome", outcome is RecoverOutcome.Settled)
        assertEquals(DiskState.Idle, sandbox.kernel().inspect())
    }

    @Test
    fun cleanupRefusedForUnsafeTxnId() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeCredential = true, journalState = JournalState.COMMITTED)
        val kernel = sandbox.kernel()

        val outcome = kernel.cleanup("../escape")
        assertFalse("越界 txnId 不得清理", outcome.cleaned)
        assertTrue(sandbox.journalFile().isFile)
        assertTrue(sandbox.credentialFile().isFile)
        assertTrue(sandbox.txnDir("t1").isDirectory)
    }
}
