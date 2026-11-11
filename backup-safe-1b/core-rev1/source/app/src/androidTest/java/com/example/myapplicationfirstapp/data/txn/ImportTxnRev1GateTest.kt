package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * core-rev1 回归（B3）：闸门释放与 `OPEN` 是两件事。
 *
 * - 磁盘没收敛到 `Idle`（`Corrupt` / `Quarantined` / `RollbackFailed` / 清理未完成）时，普通保存继续停写；
 * - 恢复与普通写共享同一把写锁，恢复要等正在跑的保存退出；
 * - 写票带闸门身份，跨闸门不得通过；重放被拒的原因是「票已失效」而不是「写正忙」。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev1GateTest : TxnKernelTestBase() {

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

    private suspend fun seed() {
        sandbox.writeNotes(oldNotes)
        sandbox.writeAttachment("a.jpg", oldA)
    }

    // ---------------- 停写与放行 ----------------

    @Test
    fun recoverAfterCorruptKeepsWritesBlocked() = runBlocking {
        sandbox.writeJournalRaw("{not-json")
        val gate = WriteGate()
        val kernel = sandbox.kernel(gate = gate)

        val outcome = kernel.recover()
        assertTrue("损坏态必须停手，实际=$outcome", outcome is RecoverOutcome.Stopped)
        assertTrue("损坏态不得放行普通保存", gate.beginWrite() is WriteGate.Admission.Refused)
    }

    @Test
    fun recoverAfterQuarantineKeepsWritesBlocked() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW, writeJournal = false)
        val gate = WriteGate()
        val kernel = sandbox.kernel(gate = gate)

        val outcome = kernel.recover()
        assertTrue(outcome is RecoverOutcome.Stopped)
        assertTrue("隔离态不得放行普通保存", gate.beginWrite() is WriteGate.Admission.Refused)
    }

    @Test
    fun recoverAfterRollbackFailedKeepsWritesBlocked() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val ops = FaultyOps().apply { failOnce += "old-attachment:a.jpg" }
        val gate = WriteGate()
        val kernel = sandbox.kernel(ops, gate)

        val outcome = kernel.recover()
        assertTrue("应停在回滚失败，实际=$outcome", outcome is RecoverOutcome.Stopped)
        assertTrue("回滚失败不得放行普通保存", gate.beginWrite() is WriteGate.Admission.Refused)
    }

    @Test
    fun commitWithUnfinishedCleanupKeepsWritesBlocked() = runBlocking {
        seed()
        val ops = FaultyOps().apply { failOnce += "delete-material:old-attachments/a.jpg" }
        val gate = WriteGate()
        val kernel = sandbox.kernel(ops, gate)
        val materialized = kernel.materialize("t1", newNotes.toByteArray(), mapOf("a.jpg" to newA, "b.jpg" to newB))
        val digest = (materialized as MaterializeOutcome.Ready).commitDigest

        val outcome = kernel.commit(MaterialRef("t1", digest))
        assertTrue("应已提交，实际=$outcome", outcome is CommitOutcome.Committed)
        assertEquals("materials", (outcome as CommitOutcome.Committed).cleanup.stoppedAt)
        assertTrue("清理没做完就不能放行普通保存", gate.beginWrite() is WriteGate.Admission.Refused)

        val recovered = kernel.recover()
        assertTrue("清理补齐后应收敛，实际=$recovered", recovered is RecoverOutcome.Settled)
        assertTrue("收敛到 Idle 后必须放行", gate.beginWrite() is WriteGate.Admission.Granted)
    }

    @Test
    fun cleanupRefusedByStateStillKeepsWritesBlocked() = runBlocking {
        sandbox.plant(spec(), notesState = NotesState.NEW)
        val gate = WriteGate()
        val kernel = sandbox.kernel(gate = gate)

        val outcome = kernel.cleanup("t1")
        assertFalse("中断态不得清理", outcome.cleaned)
        assertTrue("中断态清理失败后仍须停写", gate.beginWrite() is WriteGate.Admission.Refused)
    }

    // ---------------- 互斥 ----------------

    @Test
    fun recoverWaitsForInFlightSave() = runBlocking {
        sandbox.writeNotes(oldNotes)
        val gate = WriteGate()
        val kernel = sandbox.kernel(gate = gate)
        val ticket = (gate.beginWrite() as WriteGate.Admission.Granted).ticket

        val saveStarted = CountDownLatch(1)
        val releaseSave = CountDownLatch(1)
        val save = launch(Dispatchers.IO) {
            gate.write(ticket) {
                saveStarted.countDown()
                releaseSave.await(10, TimeUnit.SECONDS)
            }
        }
        assertTrue("保存未开始", saveStarted.await(10, TimeUnit.SECONDS))

        val recover = launch(Dispatchers.IO) { kernel.recover() }
        val finishedWhileSaving = withTimeoutOrNull(1_000) {
            recover.join()
            true
        } ?: false
        assertFalse("恢复必须等普通写释放写锁", finishedWhileSaving)

        releaseSave.countDown()
        save.join()
        recover.join()
        assertNotNull("恢复最终必须收敛", kernel.inspect())
    }

    // ---------------- 票据 ----------------

    @Test
    fun foreignTicketIsRefused() = runBlocking {
        val one = WriteGate()
        val other = WriteGate()
        val ticket = (one.beginWrite() as WriteGate.Admission.Granted).ticket

        val run = other.write(ticket) { 1 }
        assertTrue("别的闸门发的票不得被接受，实际=$run", run is WriteGate.WriteRun.Refused)
        assertFalse(
            "跨闸门拒绝不是「写正忙」",
            (run as WriteGate.WriteRun.Refused).refusal == WriteGate.Refusal.Busy
        )
    }

    @Test
    fun replayIsRefusedAsStaleNotBusy() = runBlocking {
        val gate = WriteGate()
        val ticket = (gate.beginWrite() as WriteGate.Admission.Granted).ticket
        assertTrue(gate.write(ticket) { 1 } is WriteGate.WriteRun.Completed)

        val replay = gate.write(ticket) { 2 }
        assertTrue("同一张票不能重放，实际=$replay", replay is WriteGate.WriteRun.Refused)
        val refusal = (replay as WriteGate.WriteRun.Refused).refusal
        assertFalse("重放拒绝不是「写正忙」", refusal == WriteGate.Refusal.Busy)
        assertFalse("重放拒绝不是「不在 OPEN」", refusal == WriteGate.Refusal.NotOpen)
        assertEquals(WriteGate.Freshness.Stale, gate.freshness(ticket))
    }
}
