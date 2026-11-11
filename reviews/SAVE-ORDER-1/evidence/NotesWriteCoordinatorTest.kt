package com.example.myapplicationfirstapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class NotesWriteCoordinatorTest {
    @Test fun olderCannotOverwriteNewer() = runBlocking {
        val c = NotesWriteCoordinator()
        val old = c.reserve()!!
        val fresh = c.reserve()!!
        var disk = "initial"
        assertTrue(c.save(fresh) { disk = "fresh" })
        assertFalse(c.save(old) { disk = "old" })
        assertEquals("fresh", disk)
    }
    @Test fun failedWriteCanRetry() = runBlocking {
        val c = NotesWriteCoordinator()
        val ticket = c.reserve()!!
        try {
            c.save(ticket) { throw IOException("injected") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertTrue(c.save(ticket) { })
        assertFalse(c.save(ticket) { fail("Duplicate write") })
    }
    @Test fun replacementInvalidatesWaitingOldSave() = runBlocking {
        val c = NotesWriteCoordinator()
        val old = c.reserve()!!
        val release = CompletableDeferred<Unit>()
        var disk = "initial"
        var saved: Boolean? = null
        val replace = launch(start = CoroutineStart.UNDISPATCHED) {
            c.replace {
                assertNull(c.reserve())
                release.await()
                disk = "imported"
            }
        }
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            saved = c.save(old) { disk = "stale" }
        }
        release.complete(Unit)
        replace.join()
        save.join()
        assertEquals(false, saved)
        assertEquals("imported", disk)
        assertTrue(c.save(c.reserve()!!) { disk = "edited" })
        assertEquals("edited", disk)
    }
    @Test fun failedReplacementPreservesPendingTicket() = runBlocking {
        val c = NotesWriteCoordinator()
        val ticket = c.reserve()!!
        try {
            c.replace<Unit> { throw IOException("before commit") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertTrue(c.save(ticket) { })
        assertNotNull(c.reserve())
    }
    @Test fun foreignTicketIsRejected() = runBlocking {
        val first = NotesWriteCoordinator()
        val second = NotesWriteCoordinator()
        try {
            second.save(first.reserve()!!) { fail("Foreign ticket") }
            fail("Expected rejection")
        } catch (_: IllegalArgumentException) { }
    }
}
