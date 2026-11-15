package com.example.myapplicationfirstapp.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Separate metadata database. Does not read, migrate, or rewrite notes.json. */
class RecentNotesStore private constructor(context: Context) {
    private val helper = Helper(context.applicationContext)
    private val mutex = Mutex()

    suspend fun load(): List<RecentNoteUsage> = mutex.withLock {
        withContext(Dispatchers.IO) { read(helper.readableDatabase) }
    }

    /** Call only after resolving an existing, active note at the open boundary. */
    suspend fun recordOpened(
        noteId: String,
        openedAt: Long = System.currentTimeMillis()
    ): List<RecentNoteUsage> {
        require(noteId.isNotBlank()) { "noteId must not be blank" }
        require(openedAt >= 0L) { "openedAt must not be negative" }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val db = helper.writableDatabase
                db.beginTransaction()
                try {
                    val values = ContentValues().apply {
                        put("note_id", noteId)
                        put("last_opened_at", openedAt)
                    }
                    // Replace this metadata row, allocating a new persistent AUTOINCREMENT order.
                    val inserted = db.insertWithOnConflict(
                        "recent_note_usage", null, values, SQLiteDatabase.CONFLICT_REPLACE
                    )
                    check(inserted != -1L) { "Cannot record recent note usage" }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                read(db)
            }
        }
    }

    /** Integration must call this on permanent deletion, not on temporary list filtering. */
    suspend fun forget(noteId: String): List<RecentNoteUsage> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val db = helper.writableDatabase
            db.delete("recent_note_usage", "note_id = ?", arrayOf(noteId))
            read(db)
        }
    }

    private fun read(db: SQLiteDatabase): List<RecentNoteUsage> = buildList {
        db.query(
            "recent_note_usage",
            arrayOf("note_id", "last_opened_at", "open_order"),
            null, null, null, null, "open_order DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                add(RecentNoteUsage(cursor.getString(0), cursor.getLong(1), cursor.getLong(2)))
            }
        }
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "recent_notes.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE recent_note_usage (" +
                    "open_order INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "note_id TEXT NOT NULL UNIQUE, " +
                    "last_opened_at INTEGER NOT NULL CHECK(last_opened_at >= 0))"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("An explicit recent usage migration is required: $oldVersion -> $newVersion")
        }
    }

    companion object {
        @Volatile private var instance: RecentNotesStore? = null

        fun getInstance(context: Context): RecentNotesStore = instance ?: synchronized(this) {
            instance ?: RecentNotesStore(context.applicationContext).also { instance = it }
        }
    }
}
