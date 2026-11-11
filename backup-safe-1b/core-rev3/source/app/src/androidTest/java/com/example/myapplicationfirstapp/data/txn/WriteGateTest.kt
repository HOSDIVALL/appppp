package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 写入资格：`epoch` + `sequence` + writer 锁。
 *
 * 关键是把**写前拒绝**（`Refused`，没有任何 IO）与**写后旧回调失效**（`Stale`，可能已落盘）
 * 分开断言，不能都叫「保存失败」。
 */
@RunWith(AndroidJUnit4::class)
internal class WriteGateTest {

    @Test
    fun refusedWhenNotOpenAndBlockNeverRuns() {
        val gate = WriteGate().apply { enter(WriteGate.Mode.BLOCKED) }
        var ran = 0

        val admission = gate.beginWrite()
        assertTrue(admission is WriteGate.Admission.Refused)
        assertEquals(WriteGate.Refusal.NotOpen, (admission as WriteGate.Admission.Refused).refusal)
        assertEquals("写前拒绝不得执行任何写入", 0, ran)
    }

    @Test
    fun writeAfterBlockingIsRefusedAndTicketStaysStale() = runBlocking {
        val gate = WriteGate()
        val ticket = (gate.beginWrite() as WriteGate.Admission.Granted).ticket
        gate.enter(WriteGate.Mode.BLOCKED)

        assertEquals(WriteGate.Freshness.Stale, gate.freshness(ticket))
        val run = gate.write(ticket) { 1 }
        assertTrue("状态已变，锁内必须降级为拒绝", run is WriteGate.WriteRun.Refused)
    }

    @Test
    fun ticketIssuedAfterReopenIsWritable() = runBlocking {
        val gate = WriteGate()
        gate.enter(WriteGate.Mode.BLOCKED)
        gate.enter(WriteGate.Mode.OPEN)

        val ticket = (gate.beginWrite() as WriteGate.Admission.Granted).ticket
        // 未写过的票没有「成功回调」可言，写资格看的是它能不能写，不是 freshness
        val run = gate.write(ticket) { 7 }
        assertTrue(run is WriteGate.WriteRun.Completed)
        assertEquals(7, (run as WriteGate.WriteRun.Completed).value)
        assertEquals("写完之后它才是最新的成功回调", WriteGate.Freshness.Current, gate.freshness(ticket))
    }

    @Test
    fun olderTicketBecomesStaleAfterANewerWrite() = runBlocking {
        val gate = WriteGate()
        val first = (gate.beginWrite() as WriteGate.Admission.Granted).ticket
        val second = (gate.beginWrite() as WriteGate.Admission.Granted).ticket

        gate.write(first) { }
        assertEquals("自身成功回调仍是最新的", WriteGate.Freshness.Current, gate.freshness(first))

        gate.write(second) { }
        assertEquals("更新落地后旧回调失效", WriteGate.Freshness.Stale, gate.freshness(first))

        val replay = gate.write(first) { }
        assertTrue("同一张旧票不能重放", replay is WriteGate.WriteRun.Refused)
    }

    @Test
    fun exclusiveWriteClosesSavesAndInvalidatesCallbacksOnExit() = runBlocking {
        val gate = WriteGate()
        val exclusive = (gate.beginExclusive() as WriteGate.Admission.Granted).ticket
        assertEquals(WriteGate.Mode.IMPORTING, gate.mode())

        val admission = gate.beginWrite()
        assertTrue("导入期间必须拒绝普通保存", admission is WriteGate.Admission.Refused)
        assertEquals(WriteGate.Refusal.NotOpen, (admission as WriteGate.Admission.Refused).refusal)

        gate.releaseExclusive(exclusive, WriteGate.Mode.OPEN)
        assertEquals(WriteGate.Mode.OPEN, gate.mode())
        assertEquals("导入结束，旧回调必须失效", WriteGate.Freshness.Stale, gate.freshness(exclusive))
        assertTrue(gate.beginWrite() is WriteGate.Admission.Granted)
    }

    @Test
    fun exclusiveIsRefusedWhenAlreadyImporting() = runBlocking {
        val gate = WriteGate()
        val first = gate.beginExclusive()
        assertTrue(first is WriteGate.Admission.Granted)
        gate.releaseExclusive((first as WriteGate.Admission.Granted).ticket, WriteGate.Mode.OPEN)

        gate.enter(WriteGate.Mode.RECOVERING)
        assertTrue(gate.beginExclusive() is WriteGate.Admission.Refused)
    }
}
