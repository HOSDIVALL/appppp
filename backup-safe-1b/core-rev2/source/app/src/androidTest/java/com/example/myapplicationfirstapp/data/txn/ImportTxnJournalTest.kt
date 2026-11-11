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

/**
 * journal / 凭据 / 结果留存的编码与"谁能当证据"。
 *
 * 解码是严格的：半读出来的 journal 会让回滚目标变成猜出来的值，所以任何类型不对都判不可读。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnJournalTest : TxnKernelTestBase() {

    private val journal = Journal(
        state = JournalState.APPLYING,
        txnId = "t1",
        commitDigest = "aa".repeat(32),
        oldNotesDigest = null,
        attachments = listOf(
            JournalAttachment("a.jpg", old = null, new = "bb".repeat(32)),
            JournalAttachment("b.jpg", old = "cc".repeat(32), new = "dd".repeat(32))
        )
    )

    @Test
    fun journalRoundTripKeepsEveryField() {
        val decoded = TxnCodec.decodeJournal(TxnCodec.encodeJournal(journal))
        assertEquals(journal, decoded)
    }

    @Test
    fun journalWithUnknownStateIsUnreadable() {
        val text = TxnCodec.encodeJournal(journal).replace("\"APPLYING\"", "\"HALF_APPLIED\"")
        assertNull(TxnCodec.decodeJournal(text))
    }

    @Test
    fun journalWithWrongFieldTypeIsUnreadable() {
        val text = TxnCodec.encodeJournal(journal).replace("\"txnId\":\"t1\"", "\"txnId\":7")
        assertNull(TxnCodec.decodeJournal(text))
    }

    @Test
    fun journalWithDuplicateAttachmentNamesIsUnreadable() {
        val duplicate = journal.copy(
            attachments = journal.attachments + JournalAttachment("a.jpg", null, "ee".repeat(32))
        )
        assertNull(TxnCodec.decodeJournal(TxnCodec.encodeJournal(duplicate)))
    }

    @Test
    fun credentialRequiresDigestAndTimestamp() {
        val credential = CommitCredential("t1", "aa".repeat(32), 1_700_000_000_000)
        assertEquals(credential, TxnCodec.decodeCredential(TxnCodec.encodeCredential(credential)))

        val missing = """{"version":1,"txnId":"t1","committedAt":1}"""
        assertNull(TxnCodec.decodeCredential(missing))

        val wrongType = """{"version":1,"txnId":"t1","commitDigest":"aa","committedAt":"soon"}"""
        assertNull(TxnCodec.decodeCredential(wrongType))
    }

    @Test
    fun resultsRequireConsumedFlag() {
        val record = ResultRecord("t1", ResultOutcome.COMMITTED, 2, 1_700_000_000_000, null, false)
        assertEquals(listOf(record), TxnCodec.decodeResults(TxnCodec.encodeResults(listOf(record))))

        val missing = """{"version":1,"records":[{"txnId":"t1","outcome":"COMMITTED","count":2,"at":1}]}"""
        assertNull(TxnCodec.decodeResults(missing))
    }

    @Test
    fun tornJournalWriteIsUnreadableNotGuessed() = runBlocking {
        sandbox.writeNotes("""[{"id":"n1"}]""")
        sandbox.writeJournalRaw("""{"version":1,"state":"APPLY""")

        val state = sandbox.kernel().inspect()
        assertTrue("截断的 journal 必须不可读，实际=$state", state is DiskState.Corrupt)
    }

    @Test
    fun resultsFileIsNotEvidence() = runBlocking {
        sandbox.writeNotes("""[{"id":"n1"}]""")
        // 只伪造一条"已提交"的结果记录，没有任何 journal/凭据
        sandbox.resultsFile().parentFile?.mkdirs()
        sandbox.resultsFile().writeText(
            TxnCodec.encodeResults(
                listOf(ResultRecord("t9", ResultOutcome.COMMITTED, 1, 1, null, false))
            )
        )

        assertEquals("结果留存不能当提交证据", DiskState.Idle, sandbox.kernel().inspect())
    }

    @Test
    fun atomicWriteLeavesNoTemporaryFiles() = runBlocking {
        sandbox.writeNotes("""[{"id":"n1"}]""")
        sandbox.writeJournalRaw(TxnCodec.encodeJournal(journal))

        assertTrue("不应留下临时文件：${sandbox.tempFiles()}", sandbox.tempFiles().isEmpty())
        assertNotNull(sandbox.journal())
        assertFalse(sandbox.credentialFile().exists())
    }
}
