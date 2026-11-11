package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * core-rev3 回归（R3 余项）：**检查范围必须等于删除范围**，且检查不出来就停手。
 *
 * 这批只验证两件事：
 *
 * 1. 布局自检看不到的东西（超出已验证结构/深度的子树、枚举不了的目录）不得被判成通过，
 *    更不得交给无边界的递归删除；
 * 2. 健康布局的清理行为不变——收严不能变成「永远清不掉」。
 *
 * 现场只用两个沙箱（实现 + 靶）与哨兵文件，不碰真实数据。棚外的符号链接只用来验证
 * 「删除会不会顺着链接跑出去」，不承担恶意并发换链的防御。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev3LayoutTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val oldA = byteArrayOf(1, 1, 1, 1)
    private val newA = byteArrayOf(2, 2, 2, 2)

    private fun spec() = PlantedTxn(
        txnId = "t1",
        oldNotes = oldNotes,
        newNotes = newNotes,
        oldAttachments = mapOf("a.jpg" to oldA),
        newAttachments = mapOf("a.jpg" to newA)
    )

    private fun victim(): TxnSandbox = TxnSandbox("victim")

    /** 超出 `TxnPathGuard` 已知形状的一串目录：材料目录下面不该再套目录 */
    private fun deepSubtree(under: File): File =
        File(under, "l1/l2/l3/l4").apply { mkdirs() }

    /**
     * 把目录权限收成 000：进程**枚举不了**它，`listFiles()` 返回 null。
     *
     * 这是真实的「无法枚举」现场（不是注入的异常），用来验证内核不会把它当成空目录删掉。
     */
    private fun lockDir(dir: File) {
        dir.setReadable(false, false)
        dir.setExecutable(false, false)
        dir.setWritable(false, false)
        assertNull("现场构造失败：测试进程仍能枚举 ${dir.name}", dir.listFiles())
    }

    private fun unlockDir(dir: File) {
        dir.setReadable(true, false)
        dir.setExecutable(true, false)
        dir.setWritable(true, false)
    }

    // ================= 1. 扫描边界之外的预存链接 =================

    @Test
    fun discardStopsAtUnknownSubtreeBeyondScanDepth() = runBlocking {
        val victim = victim()
        val leakDir = File(sandbox.txnDir("t1"), "new-attachments")
        try {
            val sentinel = File(victim.root, "sentinel.txt").apply { writeText("keep") }
            File(leakDir, "a.jpg").parentFile!!.mkdirs()
            File(leakDir, "a.jpg").writeBytes(newA)
            deepSubtree(leakDir)
            sandbox.symlinkTo(".import/txn-t1/new-attachments/l1/l2/l3/l4/leak", victim.root)

            val outcome = sandbox.kernel().discardQuarantined("t1")

            assertFalse("看不到的子树不得当成可删，实际=$outcome", outcome.cleaned)
            assertTrue("根外哨兵被删掉了", sentinel.exists())
            assertEquals("keep", sentinel.readText())
            assertTrue("事务目录被删掉了", sandbox.txnDir("t1").isDirectory)
        } finally {
            File(leakDir, "l1/l2/l3/l4/leak").delete()
            victim.dispose()
        }
    }

    @Test
    fun cleanupStopsAtUnknownSubtreeAndKeepsEvidence() = runBlocking {
        sandbox.plant(
            spec(),
            notesState = NotesState.NEW,
            journalState = JournalState.COMMITTED,
            writeCredential = true
        )
        val victim = victim()
        val oldAttachments = File(sandbox.txnDir("t1"), "old-attachments")
        try {
            val sentinel = File(victim.root, "sentinel.txt").apply { writeText("keep") }
            deepSubtree(oldAttachments)
            sandbox.symlinkTo(".import/txn-t1/old-attachments/l1/l2/l3/l4/leak", victim.root)

            val outcome = sandbox.kernel().cleanup("t1")

            assertFalse("不得继续清理，实际=$outcome", outcome.cleaned)
            assertTrue("根外哨兵被删掉了", sentinel.exists())
            assertTrue("journal 不得被删", sandbox.journalFile().isFile)
            assertTrue("凭据不得被删", sandbox.credentialFile().isFile)
            assertTrue("事务材料不得被删", sandbox.txnDir("t1").isDirectory)
        } finally {
            File(oldAttachments, "l1/l2/l3/l4/leak").delete()
            victim.dispose()
        }
    }

    // ================= 2. 检查失败必须停手 =================

    @Test
    fun discardStopsWhenTxnSubtreeCannotBeEnumerated() = runBlocking {
        val locked = File(sandbox.txnDir("t1"), "new-attachments/secret").apply { mkdirs() }
        try {
            lockDir(locked)

            val outcome = sandbox.kernel().discardQuarantined("t1")

            assertFalse("枚举不了就不许说清干净了，实际=$outcome", outcome.cleaned)
            assertTrue("事务目录被删掉了", sandbox.txnDir("t1").isDirectory)
        } finally {
            unlockDir(locked)
        }
    }

    @Test
    fun cleanupStopsWhenLayoutCannotBeChecked() = runBlocking {
        sandbox.plant(
            spec(),
            notesState = NotesState.NEW,
            journalState = JournalState.COMMITTED,
            writeCredential = true
        )
        val locked = File(sandbox.txnDir("t1"), "new-attachments/secret").apply { mkdirs() }
        try {
            lockDir(locked)

            val outcome = sandbox.kernel().cleanup("t1")

            assertFalse("检查不出来就不许删，实际=$outcome", outcome.cleaned)
            assertEquals("layout", outcome.stoppedAt)
            assertTrue("journal 不得被删", sandbox.journalFile().isFile)
            assertTrue("凭据不得被删", sandbox.credentialFile().isFile)
            assertTrue("事务材料不得被删", sandbox.txnDir("t1").isDirectory)
        } finally {
            unlockDir(locked)
        }
    }

    // ================= 3. 健康布局仍然能清 =================

    @Test
    fun healthyLayoutStillDiscardsAndCleans() = runBlocking {
        val kernel = sandbox.kernel()

        // 隔离态：只有材料、没有 journal/凭据，处置后应真的删净
        File(sandbox.txnDir("q1"), "new-attachments").mkdirs()
        File(sandbox.txnDir("q1"), "commit.json").writeText(newNotes)
        File(sandbox.txnDir("q1"), "new-attachments/a.jpg").writeBytes(newA)
        val discarded = kernel.discardQuarantined("q1")
        assertTrue("健康布局应当能处置，实际=$discarded", discarded.cleaned)
        assertFalse("事务目录应已删除", sandbox.txnDir("q1").exists())

        // 已提交态：材料 + journal + 凭据，清理后只留正式数据与结果留存
        sandbox.plant(
            spec(),
            notesState = NotesState.NEW,
            journalState = JournalState.COMMITTED,
            writeCredential = true
        )
        val cleaned = kernel.cleanup("t1")
        assertTrue("健康布局应当能清理，实际=$cleaned", cleaned.cleaned)
        assertFalse("事务目录应已删除", sandbox.txnDir("t1").exists())
        assertFalse("journal 应已删除", sandbox.journalFile().exists())
        assertFalse("凭据应已删除", sandbox.credentialFile().exists())
        sandbox.expectNotes(newNotes, "清理不得动正式数据")
        assertTrue("结果留存应仍在", sandbox.results().any { it.txnId == "t1" })
    }
}
