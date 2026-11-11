package com.example.myapplicationfirstapp.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Ordering only; this does not provide a disk transaction or process-death recovery. */
internal class NotesWriteCoordinator {
    internal class Ticket internal constructor(
        internal val owner: Any,
        internal val generation: Long,
        internal val sequence: Long
    )
    private val gate = Any()
    private val writer = Mutex()
    private var generation = 0L
    private var sequence = 0L
    private var savedSequence = 0L
    private var replacing = false

    fun reserve(): Ticket? = synchronized(gate) {
        if (replacing) null else Ticket(gate, generation, ++sequence)
    }

    suspend fun save(ticket: Ticket, write: suspend () -> Unit): Boolean = writer.withLock {
        val current = synchronized(gate) {
            require(ticket.owner === gate) { "Ticket belongs to another store" }
            ticket.generation == generation && ticket.sequence > savedSequence
        }
        if (!current) return@withLock false
        write()
        synchronized(gate) { savedSequence = ticket.sequence }
        true
    }

    /**
     * Not used by the application in this incremental patch.
     * Requires rollback/recovery, pre-mutation guards, and cancellation handling
     * at the durable commit boundary before import integration is safe.
     */
    suspend fun <T> replace(commit: suspend () -> T): T = writer.withLock {
        synchronized(gate) { replacing = true }
        try {
            val result = commit()
            synchronized(gate) {
                generation++
                savedSequence = sequence
            }
            result
        } finally {
            synchronized(gate) { replacing = false }
        }
    }
}
