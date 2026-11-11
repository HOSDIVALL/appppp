package com.example.myapplicationfirstapp.data.txn

import java.io.File
import java.io.IOException

/** journal 的读取结果。`Unreadable` 与 `Absent` 必须分开：前者是损坏，后者是没开始。 */
internal sealed interface JournalRead {
    data object Absent : JournalRead
    data object Unreadable : JournalRead
    data class Present(val value: Journal) : JournalRead
}

/**
 * 凭据的读取结果。
 *
 * `Unreadable` **不等于** `Absent`：凭据写了一半或坏了，说明「可能提交过」，
 * 这时不能退化成「没凭据」去回滚。
 */
internal sealed interface CredentialRead {
    data object Absent : CredentialRead
    data object Unreadable : CredentialRead
    data class Present(val value: CommitCredential) : CredentialRead
}

internal sealed interface NotesRead {
    data object Absent : NotesRead
    data object Unreadable : NotesRead
    data class Present(val bytes: ByteArray) : NotesRead
}

/**
 * 只读磁盘访问。
 *
 * 所有「读一眼再决定」的动作都集中在这里，分类器、回滚、清理、提交共用同一套读法，
 * 免得某处把「读失败」当成「不存在」。
 */
internal class TxnDisk(private val layout: TxnLayout, private val ops: TxnFileOps) {

    fun journal(): JournalRead {
        if (!ops.exists(layout.journalFile)) return JournalRead.Absent
        val bytes = readOrNull(layout.journalFile) ?: return JournalRead.Unreadable
        val journal = TxnCodec.decodeJournal(String(bytes, Charsets.UTF_8)) ?: return JournalRead.Unreadable
        return JournalRead.Present(journal)
    }

    fun credential(): CredentialRead {
        if (!ops.exists(layout.credentialFile)) return CredentialRead.Absent
        val bytes = readOrNull(layout.credentialFile) ?: return CredentialRead.Unreadable
        val credential = TxnCodec.decodeCredential(String(bytes, Charsets.UTF_8))
            ?: return CredentialRead.Unreadable
        return CredentialRead.Present(credential)
    }

    fun notes(): NotesRead {
        if (!ops.exists(layout.notesFile)) return NotesRead.Absent
        val bytes = readOrNull(layout.notesFile) ?: return NotesRead.Unreadable
        return NotesRead.Present(bytes)
    }

    fun notesDigest(): String? = readOrNull(layout.notesFile)?.let { ops.digestBytes(it) }

    fun digestOrNull(file: File): String? = readOrNull(file)?.let { ops.digestBytes(it) }

    fun commitMaterialExists(txnId: String): Boolean =
        ops.exists(File(layout.txnDir(txnId), TxnLayout.COMMIT_JSON))

    fun readOrNull(file: File): ByteArray? = try {
        if (ops.exists(file) && !ops.isDirectory(file)) ops.readBytes(file) else null
    } catch (failure: IOException) {
        null
    }

    fun readTextOrNull(file: File): String? = readOrNull(file)?.let { String(it, Charsets.UTF_8) }
}
