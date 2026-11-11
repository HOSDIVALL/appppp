package com.example.myapplicationfirstapp.data.txn

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicLong

/**
 * 写入闸门：`gateId` + `epoch` + `sequence` + `writer` 锁内校验。
 *
 * ## 四样东西各管一件事
 *
 * - `gateId`：票是哪个闸门发的。跨闸门的票一律拒绝——两个闸门的 `epoch` 起点相同，
 *   光看数字分辨不出来。
 * - `epoch`：代次。导入、恢复、状态切换都会推进它，让**在途回调**失效。
 * - `sequence`：同代次内的先后。防止一张旧票在队列里排到新一轮之后被当成新写入。
 * - `writer`：锁内复核。`beginWrite()` 通过到真正落盘之间，状态可能已经被改掉。
 *
 * ## 三个失败点必须分开
 *
 * - [Refusal] / [Admission.Refused]：**写之前**拒绝（[Refusal.NotOpen] 状态不许写、
 *   [Refusal.Busy] 已有写入在跑、[Refusal.ForeignTicket] 票不是本闸门的、
 *   [Refusal.Superseded] 票已被用掉或已跨代）。没有任何 IO。
 * - [Freshness.Stale]：写入可能已经发生，但票属于旧代次，**结果必须丢弃**。
 *   把它当失败上报，会把一次正常的新保存标成错误。
 *
 * ## 释放与放行分开
 *
 * [releaseExclusive] 只做「交还写锁 + 把状态留给调用方指定」。要不要回到 [Mode.OPEN]，
 * 由内核按磁盘是否收敛到 `Idle` 决定——清理没做完、状态损坏、事务被隔离时，
 * 写锁虽然交还了，普通保存仍然要被拒。
 */
internal class WriteGate {

    internal enum class Mode { OPEN, IMPORTING, COMMITTED_PENDING_PUBLISH, BLOCKED, RECOVERING }

    internal data class Ticket(val gateId: Long, val epoch: Long, val sequence: Long)

    internal enum class Refusal { NotOpen, Busy, Superseded, ForeignTicket }

    internal enum class Freshness { Current, Stale }

    internal sealed interface Admission {
        data class Granted(val ticket: Ticket) : Admission
        data class Refused(val refusal: Refusal) : Admission
    }

    internal sealed interface WriteRun<out T> {
        data class Completed<T>(val value: T) : WriteRun<T>
        data class Refused(val refusal: Refusal) : WriteRun<Nothing>
    }

    private val gateId = IDS.incrementAndGet()

    private val guard = Any()
    private val writer = Mutex()

    private var mode = Mode.OPEN
    private var epoch = 0L
    private var sequence = 0L
    private var savedSequence = 0L
    private var holding = false

    fun mode(): Mode = synchronized(guard) { mode }

    fun epoch(): Long = synchronized(guard) { epoch }

    /**
     * 领一张写票。锁内判定，不排队：非 OPEN 或已有写入 → [Admission.Refused]。
     *
     * 这里**不阻塞**等待，是为了让 UI 立刻知道「这次没写」，而不是挂在一个看不见的队列上。
     */
    fun beginWrite(): Admission = synchronized(guard) { admitLocked() }

    /**
     * 持票写入。[block] 在 `writer` 锁内执行，进入前再次核对票的归属与代次。
     *
     * 返回 [WriteRun.Refused] 时 [block] **没有执行**。
     */
    suspend fun <T> write(ticket: Ticket, block: suspend () -> T): WriteRun<T> {
        if (ticket.gateId != gateId) return WriteRun.Refused(Refusal.ForeignTicket)
        if (!writer.tryLock()) return WriteRun.Refused(Refusal.Busy)
        try {
            val refusal = synchronized(guard) {
                when {
                    mode != Mode.OPEN -> Refusal.NotOpen
                    holding -> Refusal.Busy
                    ticket.epoch != epoch -> Refusal.Superseded
                    ticket.sequence <= savedSequence -> Refusal.Superseded
                    else -> null
                }
            }
            if (refusal != null) return WriteRun.Refused(refusal)

            synchronized(guard) { holding = true }
            try {
                val value = block()
                synchronized(guard) { savedSequence = maxOf(savedSequence, ticket.sequence) }
                return WriteRun.Completed(value)
            } finally {
                synchronized(guard) { holding = false }
            }
        } finally {
            writer.unlock()
        }
    }

    /** 回调、结果投递、提醒重排都用它判断「这张票还作不作数」 */
    fun freshness(ticket: Ticket): Freshness = synchronized(guard) {
        val current = ticket.gateId == gateId && ticket.epoch == epoch && ticket.sequence > savedSequence
        if (current) Freshness.Current else Freshness.Stale
    }

    /**
     * 事务独占：拿到 writer 锁并领票，同时把状态切到导入态。
     *
     * 非 OPEN 时如实返回 [Admission.Refused] 并立刻交还锁，不占用写通道。
     */
    suspend fun beginExclusive(): Admission {
        writer.lock()
        val admission = synchronized(guard) {
            val admitted = admitLocked()
            if (admitted is Admission.Granted) {
                mode = Mode.IMPORTING
                holding = true
            }
            admitted
        }
        if (admission is Admission.Refused) writer.unlock()
        return admission
    }

    /**
     * 恢复独占：先等正在跑的写入退出，再进 [Mode.RECOVERING]。
     *
     * 与 [beginExclusive] 的区别是**不看当前状态**：`BLOCKED` 之后必须还能再试一次恢复，
     * 否则一次失败就永久锁死。互斥仍然完整——它拿的是同一把 `writer` 锁。
     */
    suspend fun beginRecovery(): Ticket {
        writer.lock()
        return synchronized(guard) {
            mode = Mode.RECOVERING
            holding = true
            Ticket(gateId, epoch, ++sequence)
        }
    }

    /**
     * 交还写锁。**不**决定是否放行普通保存，[next] 由调用方按磁盘状态给出。
     *
     * 无论回到哪个状态都推进 `epoch`：这次事务期间发出的回调一律失效。
     */
    fun releaseExclusive(ticket: Ticket, next: Mode) {
        require(ticket.gateId == gateId) { "ticket from another gate" }
        synchronized(guard) {
            savedSequence = maxOf(savedSequence, ticket.sequence)
            mode = next
            epoch++
            holding = false
        }
        writer.unlock()
    }

    /**
     * 切换状态。只有影响写权限的切换才推进 `epoch`，其余保持原样，
     * 免得每次状态刷新都让在途回调变成 `Stale`。
     */
    fun enter(next: Mode) {
        synchronized(guard) {
            if (next == mode) return
            val before = mode == Mode.OPEN
            mode = next
            if (before || next == Mode.OPEN) epoch++
        }
    }

    /** 调用方必须已经持 `guard` */
    private fun admitLocked(): Admission {
        if (mode != Mode.OPEN) return Admission.Refused(Refusal.NotOpen)
        if (holding) return Admission.Refused(Refusal.Busy)
        return Admission.Granted(Ticket(gateId, epoch, ++sequence))
    }

    private companion object {
        val IDS = AtomicLong()
    }
}
