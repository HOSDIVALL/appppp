package com.example.myapplicationfirstapp.data.txn

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * core-rev2 回归（R1）：**写票不可重放**与**最新成功写入回调仍有效**是两件事。
 *
 * rev1 用同一个 `sequence > savedSequence` 判两件事，于是「自己刚刚写成功」的回调
 * 在写入完成瞬间就变成 `Stale`——一次正常保存被自己的成功回调判成失效。
 *
 * 三个阶段分开断言：
 *
 * 1. 自身最新成功：写完后回调仍是 `Current`，但同一张票不能再写；
 * 2. 更新保存完成：新一次写入落地后，旧回调变 `Stale`，新回调 `Current`；
 * 3. 跨代：`epoch` 推进（停写 / 释放独占）后，即使没有更新的写入，旧回调也失效。
 */
@RunWith(AndroidJUnit4::class)
internal class ImportTxnRev2GateTest {

    @Test
    fun ownSuccessKeepsItsCallbackCurrentWhileReplayIsRefused() = runBlocking {
        val gate = WriteGate()
        val ticket = (gate.beginWrite() as WriteGate.Admission.Granted).ticket
        assertTrue(gate.write(ticket) { 1 } is WriteGate.WriteRun.Completed)

        assertEquals(
            "自身最新一次成功写入的回调必须仍然有效",
            WriteGate.Freshness.Current,
            gate.freshness(ticket)
        )

        val replay = gate.write(ticket) { 2 }
        assertTrue("同一张票不能重放，实际=$replay", replay is WriteGate.WriteRun.Refused)
        assertEquals(
            WriteGate.Refusal.Superseded,
            (replay as WriteGate.WriteRun.Refused).refusal
        )
        assertEquals(
            "重放被拒不等于回调失效",
            WriteGate.Freshness.Current,
            gate.freshness(ticket)
        )
    }

    @Test
    fun newerCompletionRetiresThePreviousCallback() = runBlocking {
        val gate = WriteGate()
        val first = (gate.beginWrite() as WriteGate.Admission.Granted).ticket
        val second = (gate.beginWrite() as WriteGate.Admission.Granted).ticket

        gate.write(first) { 1 }
        assertEquals(
            "新票还没写出内容，旧回调仍是最新的一次成功写入",
            WriteGate.Freshness.Current,
            gate.freshness(first)
        )

        gate.write(second) { 2 }
        assertEquals("更新落地后旧回调失效", WriteGate.Freshness.Stale, gate.freshness(first))
        assertEquals("最新一次成功写入的回调有效", WriteGate.Freshness.Current, gate.freshness(second))
    }

    @Test
    fun crossingGenerationRetiresCallbackWithoutANewerWrite() = runBlocking {
        val gate = WriteGate()
        val ticket = (gate.beginWrite() as WriteGate.Admission.Granted).ticket
        gate.write(ticket) { 1 }
        assertEquals(WriteGate.Freshness.Current, gate.freshness(ticket))

        gate.enter(WriteGate.Mode.BLOCKED)
        assertEquals("跨代后旧回调失效", WriteGate.Freshness.Stale, gate.freshness(ticket))

        gate.enter(WriteGate.Mode.OPEN)
        assertEquals("重新开放也不复活旧回调", WriteGate.Freshness.Stale, gate.freshness(ticket))
    }

    /**
     * 显式查询把三种「不能写」分开：用过了、跨代了、不是本闸门的。
     *
     * 这条断言依赖 rev2 新增的 `replayability()`，所以它不在 core-rev1 的红基线里——
     * 红基线由上面三条 `freshness` 断言暴露。
     */
    @Test
    fun replayabilityDistinguishesConsumedFromCrossGeneration() = runBlocking {
        val gate = WriteGate()
        val ticket = (gate.beginWrite() as WriteGate.Admission.Granted).ticket
        assertEquals(WriteGate.Replayability.Replayable, gate.replayability(ticket))

        gate.write(ticket) { 1 }
        assertEquals(WriteGate.Replayability.Consumed, gate.replayability(ticket))
        assertEquals("用过了，但回调仍是最新", WriteGate.Freshness.Current, gate.freshness(ticket))

        assertEquals(
            "别的闸门的票",
            WriteGate.Replayability.Foreign,
            WriteGate().replayability(ticket)
        )

        gate.enter(WriteGate.Mode.BLOCKED)
        assertEquals(
            "跨代与「用过了」必须能分开",
            WriteGate.Replayability.CrossedGeneration,
            gate.replayability(ticket)
        )
    }
}
