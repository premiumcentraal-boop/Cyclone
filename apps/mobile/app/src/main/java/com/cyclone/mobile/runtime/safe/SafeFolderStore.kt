package com.cyclone.mobile.runtime.safe

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Only the owner-picked persisted tree is used. Providers without rename/fsync fail without pruning old copies. */
internal class SafeFolderStore(private val context: Context, private val tree: Uri) {
    data class Copy(val uri: Uri, val user: Int, val seq: Long, val bytes: Long)
    fun list(): List<Copy> {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        return buildList {
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val match = Regex("safe-([0-9]{1,5})-([1-9][0-9]*)\\.cyc").matchEntire(cursor.getString(1)) ?: continue
                    val seq = match.groupValues[2].toLongOrNull() ?: continue
                    add(Copy(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)), match.groupValues[1].toInt(), seq, cursor.getLong(2)))
                }
            } ?: error("SAFE_FOLDER_UNREADABLE")
        }
    }
    fun put(user: Int, seq: Long, ciphertext: File, key: ByteArray) {
        val name = SafeRootPlan.name(user, seq)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        check(list().none { it.user == user && it.seq == seq }) { "SAFE_FOLDER_EXISTS" }
        var current = DocumentsContract.createDocument(context.contentResolver, root, "application/octet-stream", "$name.part")
            ?: error("SAFE_FOLDER_CREATE_FAILED")
        var verified = false
        try {
            context.contentResolver.openFileDescriptor(current, "w")?.use { fd ->
                val out = java.io.FileOutputStream(fd.fileDescriptor)
                ciphertext.inputStream().use { it.copyTo(out) }; out.flush(); fd.fileDescriptor.sync()
            } ?: error("SAFE_FOLDER_WRITE_FAILED")
            current = DocumentsContract.renameDocument(context.contentResolver, current, name) ?: error("SAFE_FOLDER_RENAME_FAILED")
            context.contentResolver.query(current, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == name) { "SAFE_FOLDER_NAME_MISMATCH" }
            } ?: error("SAFE_FOLDER_NAME_MISMATCH")
            val (header, entries) = context.contentResolver.openInputStream(current)?.use { SafeSnapshot.read(it, key) }
                ?: error("SAFE_FOLDER_VERIFY_FAILED")
            try { check(header.getInt("androidUserId") == user && header.getLong("seq") == seq) { "SAFE_FOLDER_MISMATCH" } }
            finally { entries.forEach { it.close() } }
            verified = true
            list().filter { it.user == user }.sortedByDescending { it.seq }.drop(3).forEach {
                check(DocumentsContract.deleteDocument(context.contentResolver, it.uri)) { "SAFE_FOLDER_RETENTION_FAILED" }
            }
        } finally { if (!verified) runCatching { DocumentsContract.deleteDocument(context.contentResolver, current) } }
    }
}
