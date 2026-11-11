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

/** 一次实际读取的三种结局。目录占位、IO 异常都算 `Unreadable`，不算「不存在」。 */
internal sealed interface FileRead {
    data object Absent : FileRead
    data object Unreadable : FileRead
    data class Present(val bytes: ByteArray) : FileRead
}

/**
 * 参与**判定**的摘要。
 *
 * 三态必须保留：把 `Unreadable` 折叠成 `Absent`，回滚就会把「读不出来的新附件」
 * 判成「本来就没有」，然后宣称恢复完成并删掉证据。`Absent` 才是「确实没有」。
 */
internal sealed interface DigestRead {
    data object Absent : DigestRead
    data object Unreadable : DigestRead
    data class Present(val digest: String) : DigestRead

    /** 只给「已经确认不是 `Unreadable`」的分支用；`Unreadable` 上也返回 null，不要依赖它 */
    val digestOrNull: String? get() = (this as? Present)?.digest
}

/**
 * 只读磁盘访问。
 *
 * 所有「读一眼再决定」的动作都集中在这里，分类器、回滚、清理、提交共用同一套读法，
 * 免得某处把「读失败」当成「不存在」。
 */
internal class TxnDisk(private val layout: TxnLayout, private val ops: TxnFileOps) {

    fun journal(): JournalRead = when (val read = readFile(layout.journalFile)) {
        FileRead.Absent -> JournalRead.Absent
        FileRead.Unreadable -> JournalRead.Unreadable
        is FileRead.Present ->
            TxnCodec.decodeJournal(String(read.bytes, Charsets.UTF_8))
                ?.let { JournalRead.Present(it) }
                ?: JournalRead.Unreadable
    }

    fun credential(): CredentialRead = when (val read = readFile(layout.credentialFile)) {
        FileRead.Absent -> CredentialRead.Absent
        FileRead.Unreadable -> CredentialRead.Unreadable
        is FileRead.Present ->
            TxnCodec.decodeCredential(String(read.bytes, Charsets.UTF_8))
                ?.let { CredentialRead.Present(it) }
                ?: CredentialRead.Unreadable
    }

    fun notesRead(): FileRead = readFile(layout.notesFile)

    fun notesDigest(): DigestRead = digestOf(layout.notesFile)

    fun digestOf(file: File): DigestRead = when (val read = readFile(file)) {
        FileRead.Absent -> DigestRead.Absent
        FileRead.Unreadable -> DigestRead.Unreadable
        is FileRead.Present -> DigestRead.Present(ops.digestBytes(read.bytes))
    }

    /**
     * 读一个文件，三种结局分开。
     *
     * 目录占位按 `Unreadable` 处理：它既不是「没有这个文件」，也不能被当成空内容。
     * 这是 R2 的要点——**决策用**的读取不允许把异常折叠成 `null`。
     */
    fun readFile(file: File): FileRead {
        if (!ops.exists(file)) return FileRead.Absent
        if (ops.isDirectory(file)) return FileRead.Unreadable
        return try {
            FileRead.Present(ops.readBytes(file))
        } catch (failure: IOException) {
            FileRead.Unreadable
        }
    }

    fun commitMaterialExists(txnId: String): Boolean =
        ops.exists(File(layout.txnDir(txnId), TxnLayout.COMMIT_JSON))
}
