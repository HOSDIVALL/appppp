package com.example.myapplicationfirstapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupNotesParserTest {
    @Test fun acceptsEmptyArray() {
        assertTrue(parseBackupNotesOrThrow("[]").isEmpty())
    }

    @Test fun roundTripsCurrentNote() {
        val note = Note(
            id = "backup-test-note",
            title = "Title",
            content = "Body",
            createdAt = 100L,
            updatedAt = 200L,
            checklist = true,
            todos = listOf(TodoItem(id = "todo-1", text = "Task", done = true))
        )
        assertEquals(listOf(note), parseBackupNotesOrThrow(notesToJson(listOf(note))))
    }

    @Test fun preservesLegacyOptionalDefaults() {
        val note = parseBackupNotesOrThrow("[{\"id\":\"legacy\",\"title\":\"Old\"}]").single()
        assertEquals("legacy", note.id)
        assertEquals("Old", note.title)
        assertTrue(note.audio.isEmpty())
        assertTrue(note.todos.isEmpty())
    }

    @Test fun rejectsMalformedInput() {
        assertRejected("[")
        assertRejected("")
    }

    @Test fun rejectsWrongRootAndInvalidElements() {
        assertRejected("{}")
        assertRejected("null")
        assertRejected("[null]")
        assertRejected("[{\"id\":\"valid\"},7]")
    }

    @Test fun rejectsTrailingContentButAcceptsWhitespace() {
        assertRejected("[] trailing")
        assertTrue(parseBackupNotesOrThrow("  [] \n").isEmpty())
    }

    private fun assertRejected(text: String) {
        try {
            parseBackupNotesOrThrow(text)
            fail("Expected JSONException for: $text")
        } catch (_: JSONException) {
            // Expected: invalid input is not a successful empty backup.
        }
    }
}
