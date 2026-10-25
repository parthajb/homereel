package com.parthajb.homereel.server

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root

data class DocRow(val docId: String, val name: String, val mime: String, val size: Long, val modified: Long) {
    val isDir: Boolean get() = mime == Document.MIME_TYPE_DIR
}

/**
 * Storage Access Framework helpers for the one folder the person picked. Queries go straight to the provider,
 * which is much faster than DocumentFile's one query per property on a big library.
 */
object Saf {
    private val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    )

    fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    fun docUri(tree: Uri, docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    fun children(cr: ContentResolver, tree: Uri, parentDocId: String): List<DocRow> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val rows = ArrayList<DocRow>()
        cr.query(uri, COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                rows += DocRow(
                    docId = c.getString(0),
                    name = c.getString(1) ?: "",
                    mime = c.getString(2) ?: "",
                    size = if (c.isNull(3)) 0 else c.getLong(3),
                    modified = if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }
        return rows
    }

    fun row(cr: ContentResolver, tree: Uri, docId: String): DocRow? =
        cr.query(docUri(tree, docId), COLUMNS, null, null, null)?.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                DocRow(
                    c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "",
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4),
                )
            }
        }

    /** "Movies" for primary:Movies, "Internal storage" for the root of the phone's storage. */
    fun folderName(tree: Uri): String {
        val path = rootDocId(tree).substringAfter(':', "")
        return path.trimEnd('/').substringAfterLast('/').ifEmpty { "Internal storage" }
    }

    /** Free and total bytes on the volume that holds the folder, when the provider says. */
    fun space(cr: ContentResolver, tree: Uri): Pair<Long?, Long?> {
        val authority = tree.authority ?: return null to null
        val rootId = rootDocId(tree).substringBefore(':')
        runCatching {
            cr.query(
                DocumentsContract.buildRootsUri(authority),
                arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_AVAILABLE_BYTES, "capacity_bytes"), null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) == rootId) {
                        val free = if (c.isNull(1)) null else c.getLong(1)
                        val total = if (c.isNull(2)) null else c.getLong(2)
                        if (free != null) return free to total
                    }
                }
            }
        }
        if (authority == "com.android.externalstorage.documents" && rootId == "primary") {
            @Suppress("DEPRECATION")
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            return stat.availableBytes to stat.totalBytes
        }
        return null to null
    }
}
