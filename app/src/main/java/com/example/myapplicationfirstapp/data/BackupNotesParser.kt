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
    val array = parseBackupArrayOrThrow(text)
    return (0 until array.length()).map { index ->
        noteFromJson(array.getJSONObject(index))
    }
}

/**
 * 只做到「根是 JSON 数组、没有尾部垃圾」这一步，字段级校验由备份导入准备层负责。
 * 拆出来是为了让导入层能在转换成 [Note] 之前先检查字段类型。
 */
internal fun parseBackupArrayOrThrow(text: String): JSONArray {
    val tokener = JSONTokener(text)
    val array = tokener.nextValue() as? JSONArray
        ?: throw JSONException("Backup notes must be a JSON array")
    if (tokener.nextClean() != '\u0000') {
        throw JSONException("Unexpected content after backup notes")
    }
    return array
}
