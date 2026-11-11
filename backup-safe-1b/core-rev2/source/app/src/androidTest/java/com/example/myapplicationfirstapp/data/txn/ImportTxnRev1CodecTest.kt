package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * core-rev1 回归（B4）：journal / 凭据的严格校验。
 *
 * 三条线：
 * 1. `oldNotesDigest` 与附件 `old` **必须存在**（允许显式 `null`），缺字段不等于 `null`；
 * 2. `txnId`、附件名、摘要必须是能落回路径与比较的形状（越界名、非十六进制摘要一律不可读）；
 * 3. 凭据不可读 ≠ 凭据不存在——这时不能猜「没提交过」去回滚。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev1CodecTest : TxnKernelTestBase() {

    private val oldNotes = """[{"id":"n1","title":"旧"}]"""
    private val newNotes = """[{"id":"n1","title":"新"}]"""
    private val a = "a1".repeat(32)
    private val b = "b2".repeat(32)

    private fun journalJson(
        txnId: String = "t1",
        commitDigest: String = a,
        oldNotesField: String = "\"oldNotesDigest\":null,",
        attachments: String = "[]"
    ): String = """{"version":1,"state":"APPLYING","txnId":"$txnId","commitDigest":"$commitDigest",""" +
        oldNotesField + """"attachments":$attachments,"failedAt":null}"""

    @Test
    fun wellFormedJournalStillDecodes() {
        assertNotNull(TxnCodec.decodeJournal(journalJson()))
        assertNotNull(
            TxnCodec.decodeJournal(
                journalJson(
                    oldNotesField = "\"oldNotesDigest\":\"$b\",",
                    attachments = """[{"name":"a.jpg","old":"$b","new":"$a"}]"""
                )
            )
        )
    }

    @Test
    fun journalWithoutOldNotesDigestIsUnreadable() {
        assertNull(
            "缺 oldNotesDigest 不得当成「旧文件不存在」",
            TxnCodec.decodeJournal(journalJson(oldNotesField = ""))
        )
    }

    @Test
    fun attachmentWithoutOldFieldIsUnreadable() {
        assertNull(
            "附件缺 old 不得当成新增",
            TxnCodec.decodeJournal(journalJson(attachments = """[{"name":"a.jpg","new":"$a"}]"""))
        )
    }

    @Test
    fun attachmentWithoutNewFieldIsUnreadable() {
        assertNull(TxnCodec.decodeJournal(journalJson(attachments = """[{"name":"a.jpg","old":null}]""")))
    }

    @Test
    fun journalWithTraversalTxnIdIsUnreadable() {
        assertNull("txnId 不得越出事务根", TxnCodec.decodeJournal(journalJson(txnId = "../escape")))
    }

    @Test
    fun journalWithTraversalAttachmentNameIsUnreadable() {
        assertNull(
            "附件名不得越出附件目录",
            TxnCodec.decodeJournal(
                journalJson(attachments = """[{"name":"../a.jpg","old":null,"new":"$a"}]""")
            )
        )
    }

    @Test
    fun journalWithMalformedDigestIsUnreadable() {
        assertNull("摘要必须是 64 位十六进制", TxnCodec.decodeJournal(journalJson(commitDigest = "abc")))
    }

    @Test
    fun credentialWithTraversalTxnIdIsUnreadable() {
        val text = """{"version":1,"txnId":"../escape","commitDigest":"$a","committedAt":1}"""
        assertNull(TxnCodec.decodeCredential(text))
    }

    @Test
    fun credentialWithMalformedDigestIsUnreadable() {
        val text = """{"version":1,"txnId":"t1","commitDigest":"xyz","committedAt":1}"""
        assertNull(TxnCodec.decodeCredential(text))
    }

    // ---------------- 内核层面 ----------------

    @Test
    fun unreadableCredentialIsNotTreatedAsAbsent() = runBlocking {
        sandbox.plant(
            PlantedTxn("t1", oldNotes, newNotes, emptyMap(), mapOf("a.jpg" to byteArrayOf(2, 2))),
            notesState = NotesState.NEW
        )
        sandbox.credentialFile().writeText("{torn")

        val kernel = sandbox.kernel()
        val state = kernel.inspect()
        assertTrue("凭据不可读不得退化成「没凭据 → 回滚」，实际=$state", state is DiskState.Corrupt)

        val outcome = kernel.rollback("t1")
        assertTrue("凭据不可读时不得猜测回滚，实际=$outcome", outcome is RollbackOutcome.Failed)
        sandbox.expectNotes(newNotes, "不得改动正式数据")
    }

    @Test
    fun journalWithUnsafeTxnIdNeverReachesDisk() = runBlocking {
        sandbox.plant(
            PlantedTxn("t1", oldNotes, newNotes, emptyMap(), mapOf("a.jpg" to byteArrayOf(2, 2))),
            notesState = NotesState.NEW
        )
        sandbox.writeJournalRaw(journalJson(txnId = "../escape"))

        val kernel = sandbox.kernel()
        assertTrue("越界 txnId 的 journal 必须不可读", kernel.inspect() is DiskState.Corrupt)
        assertTrue(kernel.rollback("t1") is RollbackOutcome.Failed)
        sandbox.expectNotes(newNotes, "不得按越界路径写盘")
    }
}
