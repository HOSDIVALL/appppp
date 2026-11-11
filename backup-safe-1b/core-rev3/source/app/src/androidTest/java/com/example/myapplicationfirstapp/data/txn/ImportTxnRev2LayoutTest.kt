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
 * core-rev2 回归（R3）：校验根身份与**实际**路径边界，拒绝布局里预存的符号链接。
 *
 * 只验证「已经摆在盘上的链接不会把删改带到根外」，不承担恶意并发换链的防御。
 * 现场只用一个实现沙箱、一个靶沙箱和若干哨兵文件，不碰真实数据。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev2LayoutTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val oldA = byteArrayOf(1, 1, 1, 1)
    private val newA = byteArrayOf(2, 2, 2, 2)
    private val newB = byteArrayOf(3, 3)

    private fun spec() = PlantedTxn(
        txnId = "t1",
        oldNotes = oldNotes,
        newNotes = newNotes,
        oldAttachments = mapOf("a.jpg" to oldA),
        newAttachments = mapOf("a.jpg" to newA, "b.jpg" to newB)
    )

    /** 靶目录：放在另一个沙箱里，作为「根外」 */
    private fun victim(): TxnSandbox = TxnSandbox("victim")

    @Test
    fun discardNeverFollowsSymlinkedTxnDir() = runBlocking {
        val victim = victim()
        try {
            val sentinel = File(victim.root, "sentinel.txt").apply { writeText("keep") }
            val victimNotes = File(victim.root, "notes.json").apply { writeText("victim-notes") }
            sandbox.symlinkTo(".import/txn-t1", victim.root)

            val outcome = sandbox.kernel().discardQuarantined("t1")

            assertFalse("不得声称已清除，实际=$outcome", outcome.cleaned)
            assertTrue("根外哨兵被删掉了", sentinel.exists())
            assertEquals("keep", sentinel.readText())
            assertTrue("根外 notes 被删掉了", victimNotes.exists())
        } finally {
            File(sandbox.root, ".import/txn-t1").delete()
            victim.dispose()
        }
    }

    @Test
    fun cleanupNeverFollowsSymlinkedMaterialDir() = runBlocking {
        sandbox.plant(
            spec(),
            notesState = NotesState.NEW,
            journalState = JournalState.COMMITTED,
            writeCredential = true
        )
        val victim = victim()
        try {
            val sentinel = File(victim.root, "sentinel.txt").apply { writeText("keep") }
            File(sandbox.txnDir("t1"), "old-attachments").deleteRecursively()
            sandbox.symlinkTo(".import/txn-t1/old-attachments", victim.root)

            val outcome = sandbox.kernel().cleanup("t1")

            assertFalse("不得继续清理，实际=$outcome", outcome.cleaned)
            assertTrue("根外哨兵被删掉了", sentinel.exists())
            assertTrue("journal 不得被删", sandbox.journalFile().isFile)
        } finally {
            File(sandbox.txnDir("t1"), "old-attachments").delete()
            victim.dispose()
        }
    }

    @Test
    fun recoverNeverWritesThroughSymlinkedImportDir() = runBlocking {
        val victim = victim()
        try {
            victim.plant(spec(), notesState = NotesState.NEW)
            val victimJournal = File(victim.root, ".import/journal.json")
            val before = victimJournal.readText()
            sandbox.symlinkTo(".import", File(victim.root, ".import"))

            val outcome = sandbox.kernel().recover()

            assertTrue("布局不安全时必须停手，实际=$outcome", outcome is RecoverOutcome.Stopped)
            assertEquals("根外 journal 被改写", before, victimJournal.readText())
            assertTrue("根外材料被删", File(victim.root, ".import/txn-t1").isDirectory)
        } finally {
            File(sandbox.root, ".import").delete()
            victim.dispose()
        }
    }

    @Test
    fun commitNeverWritesOutsideRootThroughSymlinkedImportDir() = runBlocking {
        sandbox.writeNotes(oldNotes)
        sandbox.writeAttachment("a.jpg", oldA)
        val victim = victim()
        try {
            sandbox.symlinkTo(".import", victim.root)
            val kernel = sandbox.kernel()

            val materialized = kernel.materialize("t1", newNotes.toByteArray(), mapOf("b.jpg" to newB))
            assertTrue(
                "布局不安全时不得落地材料，实际=$materialized",
                materialized is MaterializeOutcome.Rejected
            )
            assertEquals("根外目录被写入", 0, victim.root.listFiles()?.size ?: 0)
        } finally {
            File(sandbox.root, ".import").delete()
            victim.dispose()
        }
    }

    @Test
    fun symlinkedRootIsNotTreatedAsTheStorageRoot() = runBlocking {
        val victim = victim()
        val linkRoot = File(sandbox.root, "linked-root")
        try {
            android.system.Os.symlink(victim.root.absolutePath, linkRoot.absolutePath)
            val kernel = ImportTxnKernel(linkRoot)

            assertTrue("根是链接时不得当成可用存储根", kernel.inspect() is DiskState.Corrupt)
            val materialized = kernel.materialize("t1", newNotes.toByteArray(), mapOf("b.jpg" to newB))
            assertTrue("不得在链接目标里落材料，实际=$materialized", materialized is MaterializeOutcome.Rejected)
            assertEquals("根外目录被写入", 0, victim.root.listFiles()?.size ?: 0)
        } finally {
            linkRoot.delete()
            victim.dispose()
        }
    }
}
