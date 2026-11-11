package com.example.myapplicationfirstapp.data.txn

import kotlinx.coroutines.sync.Mutex

/**
 * 写入资格：`epoch` + `sequence` + `writer` 锁内校验。
 *
 * 为什么三样都要：
 *
 * - 只有 `epoch`：同代次里「排队很久的旧保存」在导入结束后会被当成新写入。
 * - 只有 `sequence`：跨代次的回调无法区分（旧代次的 sequence 可能比新代次的大）。
 * - 没有锁内校验：`beginWrite()` 通过后到真正写盘之间，状态可能已经被改掉。
 *
 * ## 两个失败点必须分开
 *
 * - [Refusal] / [Admission.Refused]：**写之前**就拒绝（非 OPEN 或已有写入），没有任何 IO。
 *   调用方应当把它当「这次没写」，不要显示成「保存失败」。
 * - [Freshness.Stale]：写入可能已经发生，但 ticket 属于旧代次，结果必须**丢弃**。
 *   把它当失败上报，会把一次正常的新保存标成错误。
 *
 * 本类不碰磁盘，也不认识导入；它只回答「现在能不能写、这张 ticket 还算不算数」。
 */
internal class WriteGate {

    internal enum class Mode { OPEN, IMPORTING, COMMITTED_PENDING_PUBLISH, BLOCKED, RECOVERING }

    internal data class Ticket(val epoch: Long, val sequence: Long)

    internal enum class Refusal { NotOpen, Busy }

    internal enum class Freshness { Current, Stale }

    internal sealed interface Admission {
        data class Granted(val ticket: Ticket) : Admission
        data class Refused(val refusal: Refusal) : Admission
    }

    internal sealed interface WriteRun<out T> {
        data class Completed<T>(val value: T) : WriteRun<T>
        data class Refused(val refusal: Refusal) : WriteRun<Nothing>
    }

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
     * 持票写入。[block] 在 `writer` 锁内执行，进入前再次核对 `epoch` / `sequence`。
     *
     * 返回 [WriteRun.Refused] 时 [block] **没有执行**。
     */
    suspend fun <T> write(ticket: Ticket, block: suspend () -> T): WriteRun<T> {
        if (!writer.tryLock()) return WriteRun.Refused(Refusal.Busy)
        try {
            val allowed = synchronized(guard) {
                mode == Mode.OPEN && !holding &&
                    ticket.epoch == epoch && ticket.sequence > savedSequence
            }
            if (!allowed) return WriteRun.Refused(Refusal.Busy)
            synchronized(guard) { holding = true }
            try {
                val value = block()
                synchronized(guard) { savedSequence = ticket.sequence }
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
        if (ticket.epoch != epoch || ticket.sequence <= savedSequence) Freshness.Stale else Freshness.Current
    }

    /**
     * 事务独占：拿到 writer 锁并领票，直到 [endExclusive] 才释放。
     *
     * 整个导入期间 `mode` 由调用方置为 [Mode.IMPORTING]，其他写入一律 `NotOpen`。
     */
    suspend fun beginExclusive(): Admission {
        writer.lock()
        val admission = synchronized(guard) {
            val admitted = admitLocked()
            if (admitted is Admission.Granted) {
                // 独占就是导入态：写权限在拿到锁的同一刻关闭，不额外自增 epoch
                mode = Mode.IMPORTING
                holding = true
            }
            admitted
        }
        if (admission is Admission.Refused) writer.unlock()
        return admission
    }

    /** 结束独占：回到 `OPEN` 并让 `epoch` 自增，使排队中的保存与在途回调一起失效 */
    fun endExclusive(ticket: Ticket) {
        synchronized(guard) {
            savedSequence = maxOf(savedSequence, ticket.sequence)
            mode = Mode.OPEN
            epoch++
            holding = false
        }
        writer.unlock()
    }

    /**
     * 切换状态。只有影响写权限的切换才自增 `epoch`，其余保持原样，
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
        return Admission.Granted(Ticket(epoch, ++sequence))
    }
}
