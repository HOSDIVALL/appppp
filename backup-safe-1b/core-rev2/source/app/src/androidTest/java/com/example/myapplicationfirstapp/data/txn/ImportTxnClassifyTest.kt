package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `classify()` 的判定表。只看磁盘，不看内存。
 *
 * 重点覆盖两条与旧表不同的规则：
 * 1. `APPLYING` 没有终态证据时，**不看** notes 是否已经等于新载荷，一律 `Interrupted`；
 * 2. 没有 journal、没有凭据的 `txn-*` 一律 `Quarantined`，不按摘要推断「可删」。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnClassifyTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val oldBytes = mapOf("a.jpg" to byteArrayOf(1, 1, 1))
    private val newBytes = mapOf("a.jpg" to byteArrayOf(2, 2, 2))

    private fun spec(txnId: String = "t1") = PlantedTxn(
        txnId = txnId,
        oldNotes = oldNotes,
        newNotes = newNotes,
        oldAttachments = oldBytes,
        newAttachments = newBytes
    )

    @Test
    fun emptyRootIsIdle() = runBlocking {
        assertEquals(DiskState.Idle, sandbox.kernel().inspect())
    }

    @Test
    fun txnWithoutJournalOrCredentialIsQuarantinedAndKept() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeJournal = false)

        val kernel = sandbox.kernel()
        val state = kernel.inspect()
        assertTrue("无 journal/凭据必须隔离，实际=$state", state is DiskState.Quarantined)
        assertEquals(listOf("txn-t1"), (state as DiskState.Quarantined).txnDirs)

        // 即便 notes 已经是载荷内容，也不能据此删除材料
        val recovered = kernel.recover()
        assertTrue(recovered is RecoverOutcome.Stopped)
        assertTrue("事务材料被删了", sandbox.txnDir("t1").isDirectory)
        assertTrue("不应产生结果记录", sandbox.results().isEmpty())
    }

    @Test
    fun applyingWithoutCredentialIsInterruptedEvenWhenNotesAlreadyNew() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeCredential = false)

        assertEquals(DiskState.Interrupted("t1"), sandbox.kernel().inspect())
    }

    @Test
    fun applyingWithMatchingCredentialIsCommitted() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeCredential = true)

        assertEquals(DiskState.Committed("t1"), sandbox.kernel().inspect())
    }

    @Test
    fun applyingWithCredentialButOldNotesIsCorrupt() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.OLD, writeCredential = true)

        assertTrue(sandbox.kernel().inspect() is DiskState.Corrupt)
    }

    @Test
    fun abortedWithOldNotesIsAborted() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.OLD, journalState = JournalState.ABORTED)

        assertEquals(DiskState.Aborted("t1"), sandbox.kernel().inspect())
    }

    @Test
    fun abortedButNotesChangedIsCorrupt() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, journalState = JournalState.ABORTED)

        assertTrue(sandbox.kernel().inspect() is DiskState.Corrupt)
    }

    @Test
    fun unreadableJournalWithMatchingCredentialIsCommitted() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeJournal = false, writeCredential = false)
        sandbox.writeJournalRaw("{not-json")
        sandbox.credentialFile().writeText(
            TxnCodec.encodeCredential(CommitCredential("t1", newNotes.toByteArray().sha256(), 1))
        )

        assertEquals(DiskState.Committed("t1"), sandbox.kernel().inspect())
    }

    @Test
    fun unreadableJournalWithoutCredentialIsCorrupt() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeJournal = false)
        sandbox.writeJournalRaw("{not-json")

        assertTrue(sandbox.kernel().inspect() is DiskState.Corrupt)
    }

    @Test
    fun preparedWithoutCommitMaterialIsStagingFailed() = runBlocking {
        sandbox.plant(
            spec(),
            notesState = NotesState.OLD,
            journalState = JournalState.PREPARED,
            writeNewMaterial = false
        )

        assertEquals(DiskState.StagingFailed("t1"), sandbox.kernel().inspect())
    }

    @Test
    fun rollingBackWithCredentialIsCorrupt() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, journalState = JournalState.ROLLING_BACK, writeCredential = true)

        assertTrue(sandbox.kernel().inspect() is DiskState.Corrupt)
    }

    @Test
    fun credentialWithWrongDigestIsCorrupt() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeJournal = false)
        sandbox.credentialFile().writeText(
            TxnCodec.encodeCredential(CommitCredential("t1", "0".repeat(64), 1))
        )

        assertTrue(sandbox.kernel().inspect() is DiskState.Corrupt)
    }

    @Test
    fun secondTxnDirectoryNextToActiveJournalIsNotSilentlyIgnored() = runBlocking {
        sandbox.plant(spec("t1"), notesState = NotesState.NEW, writeCredential = true)
        File(sandbox.root, ".import/txn-t2").mkdirs()

        val kernel = sandbox.kernel()
        // 有证据 → 已提交；多余目录不改变提交事实
        assertEquals(DiskState.Committed("t1"), kernel.inspect())

        // 但清理完成、证据删除之后，剩下的目录必须变成隔离态，而不是被当成垃圾删掉
        kernel.recover()
        val state = kernel.inspect()
        assertTrue("多余事务目录必须被隔离，实际=$state", state is DiskState.Quarantined)
        assertEquals(listOf("txn-t2"), (state as DiskState.Quarantined).txnDirs)
        assertTrue(File(sandbox.root, ".import/txn-t2").isDirectory)
    }
}
