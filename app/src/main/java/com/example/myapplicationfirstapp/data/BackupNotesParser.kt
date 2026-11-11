package com.example.myapplicationfirstapp.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener

/**
 * Backup-only parser: malformed input must never become an empty backup.
 * Preserves noteFromJson defaults for legacy optional fields.
 * This checks the array/object structure, not every optional field type.
 * Callers must handle JSONException before changing live data.
 */
internal fun parseBackupNotesOrThrow(text: String): List<Note> {
    val tokener = JSONTokener(text)
    val array = tokener.nextValue() as? JSONArray
        ?: throw JSONException("Backup notes must be a JSON array")
    if (tokener.nextClean() != '\u0000') {
        throw JSONException("Unexpected content after backup notes")
    }
    return (0 until array.length()).map { index ->
        noteFromJson(array.getJSONObject(index))
    }
}
