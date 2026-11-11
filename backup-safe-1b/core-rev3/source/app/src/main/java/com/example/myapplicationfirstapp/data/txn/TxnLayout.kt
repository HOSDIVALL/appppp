package com.example.myapplicationfirstapp.data.txn

import java.io.File

/**
 * 事务在存储根下的固定布局与命名规则。
 *
 * 单独拆出来，是因为「哪些名字能落成路径」是**安全边界**，不能散落在提交、回滚、清理三处
 * 各写一遍：一次漏校验就等于让 `journal` 里的字符串决定写盘位置。
 */
internal class TxnLayout(val root: File, private val ops: TxnFileOps = RealTxnFileOps()) {

    private val guard = TxnPathGuard(root, ops)

    /**
     * 实际路径边界自检。所有会读、写、删的入口在动第一个字节之前都要先问一次：
     * 根是不是还是那个根、`.import/` 下有没有**布局之外**的东西（含符号链接、判不出来的条目）。
     */
    fun verify(): LayoutVerdict = guard.verify()

    val notesFile = File(root, NOTES)
    val attachmentsDir = File(root, ATTACHMENTS)
    val importDir = File(root, IMPORT_DIR)
    val journalFile = File(importDir, JOURNAL)
    val credentialFile = File(importDir, CREDENTIAL)
    val resultsFile = File(importDir, RESULTS)

    fun txnDirName(txnId: String): String = "$TXN_PREFIX$txnId"

    fun txnDir(txnId: String): File = File(importDir, txnDirName(txnId))

    fun txnDirs(ops: TxnFileOps): List<File> =
        ops.list(importDir).filter { it.isDirectory && it.name.startsWith(TXN_PREFIX) }

    fun oldNotesFile(txnId: String): File = File(txnDir(txnId), OLD_NOTES)

    fun oldManifestFile(txnId: String): File = File(txnDir(txnId), OLD_MANIFEST)

    fun oldAttachmentFile(txnId: String, name: String): File = File(txnDir(txnId), "$OLD_ATTACHMENTS/$name")

    fun newAttachmentFile(txnId: String, name: String): File = File(txnDir(txnId), "$NEW_ATTACHMENTS/$name")

    fun attachmentFile(name: String): File = File(attachmentsDir, name)

    companion object {
        const val NOTES = "notes.json"
        const val ATTACHMENTS = "attachments"
        const val IMPORT_DIR = ".import"
        const val JOURNAL = "journal.json"
        const val CREDENTIAL = "committed.json"
        const val RESULTS = "results.json"
        const val TXN_PREFIX = "txn-"
        const val COMMIT_JSON = "commit.json"
        const val OLD_NOTES = "old-notes.json"
        const val OLD_MANIFEST = "old-manifest.json"
        const val OLD_ATTACHMENTS = "old-attachments"
        const val NEW_ATTACHMENTS = "new-attachments"

        /**
         * 能不能把一段外部字符串当成单层文件名。
         *
         * 允许 `.` 之外的普通名字；拒绝空白、`.`、`..`、以及任何带路径分隔符的写法。
         * 这是路径越界的唯一闸口，`journal` 解码与所有对外入口都走这里。
         */
        fun isSafeName(name: String): Boolean =
            name.isNotBlank() &&
                name != "." &&
                name != ".." &&
                name == File(name).name &&
                '/' !in name &&
                '\\' !in name &&
                '\u0000' !in name
    }
}
