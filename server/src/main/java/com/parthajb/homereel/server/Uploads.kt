package com.parthajb.homereel.server

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.MediaFiles
import com.parthajb.homereel.shared.UploadRequest
import com.parthajb.homereel.shared.UploadState
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

class HttpError(val status: Int, message: String, val body: Any? = null) : Exception(message)

@Serializable
private data class Pending(val uploadId: String, val name: String, val size: Long, val partDocId: String)

/**
 * Resumable uploads into the top of the library folder. Each file is written as `<name>.part`, chunk by chunk, and
 * renamed when the last byte arrives. The offset to resume from is always the `.part` file's real size, so a chunk
 * cut off by a Wi-Fi drop just continues from where the bytes stopped.
 */
class Uploads(private val cr: ContentResolver, private val prefs: Prefs) {
    private val serializer = ListSerializer(Pending.serializer())
    private val pending: MutableMap<String, Pending> =
        (prefs.uploadsJson?.let { runCatching { ApiJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList())
            .associateBy { it.uploadId }.toMutableMap()

    /** Starts (or resumes) an upload into the folder [parentDocId]: the top of the library, or Private. */
    @Synchronized
    fun start(tree: Uri, request: UploadRequest, parentDocId: String): UploadState {
        val name = MediaFiles.safeUploadName(request.name)
            ?: throw HttpError(400, "Only video files (${MediaFiles.VIDEO_EXTENSIONS.joinToString()}), subtitles and posters can be uploaded")
        if (request.size <= 0) throw HttpError(400, "$name is empty")
        pending.values.firstOrNull { it.name == name && it.size == request.size }?.let { p ->
            val row = Saf.row(cr, tree, p.partDocId)
            if (row != null) return UploadState(p.uploadId, name, p.size, row.size)
            forget(p.uploadId)
        }
        val existing = Saf.children(cr, tree, parentDocId)
        if (existing.any { it.name.equals(name, ignoreCase = true) }) {
            throw HttpError(409, "A file called $name is already in the library")
        }
        val (free, _) = Saf.space(tree)
        if (free != null && free < request.size + 200L * 1024 * 1024) {
            throw HttpError(507, "Not enough space on the phone for $name")
        }
        val part = DocumentsContract.createDocument(cr, Saf.docUri(tree, parentDocId), "application/octet-stream", "$name.part")
            ?: throw HttpError(500, "The phone wouldn't create $name")
        val p = Pending(UUID.randomUUID().toString(), name, request.size, DocumentsContract.getDocumentId(part))
        pending[p.uploadId] = p
        save()
        return UploadState(p.uploadId, name, p.size, 0)
    }

    @Synchronized
    fun state(tree: Uri, uploadId: String): UploadState {
        val p = pending[uploadId] ?: throw HttpError(404, "No such upload")
        val row = Saf.row(cr, tree, p.partDocId) ?: run {
            forget(uploadId)
            throw HttpError(404, "The partial file is gone; start the upload again")
        }
        return UploadState(p.uploadId, p.name, p.size, row.size)
    }

    /**
     * Writes [length] bytes from [body] at [offset]. Returns the new state, and the finished file's document id
     * once the upload is complete. Not synchronized while copying, so two files can upload at once.
     */
    fun write(tree: Uri, uploadId: String, offset: Long, length: Long, body: InputStream): Pair<UploadState, String?> {
        val current = state(tree, uploadId)
        val p = synchronized(this) { pending[uploadId] } ?: throw HttpError(404, "No such upload")
        if (offset != current.offset) throw HttpError(409, "Resume from ${current.offset}", current)
        if (offset + length > p.size) throw HttpError(400, "That's more than the file's size")
        val partUri = Saf.docUri(tree, p.partDocId)
        var written = 0L
        cr.openFileDescriptor(partUri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).channel.use { ch ->
                ch.position(offset)
                val buf = ByteArray(64 * 1024)
                while (written < length) {
                    val n = body.read(buf, 0, minOf(buf.size.toLong(), length - written).toInt())
                    if (n < 0) break
                    val b = java.nio.ByteBuffer.wrap(buf, 0, n)
                    while (b.hasRemaining()) ch.write(b)
                    written += n
                }
            }
        } ?: throw HttpError(500, "The phone wouldn't open the partial file")
        val newOffset = offset + written
        if (newOffset < p.size) return UploadState(p.uploadId, p.name, p.size, newOffset) to null
        synchronized(this) {
            val finished = DocumentsContract.renameDocument(cr, partUri, p.name)
                ?: throw HttpError(500, "The phone wouldn't rename ${p.name}.part")
            forget(uploadId)
            return UploadState(p.uploadId, p.name, p.size, p.size, done = true) to
                DocumentsContract.getDocumentId(finished)
        }
    }

    @Synchronized
    fun cancel(tree: Uri, uploadId: String) {
        val p = pending[uploadId] ?: return
        runCatching { DocumentsContract.deleteDocument(cr, Saf.docUri(tree, p.partDocId)) }
        forget(uploadId)
    }

    @Synchronized
    private fun forget(uploadId: String) {
        pending.remove(uploadId)
        save()
    }

    private fun save() {
        prefs.uploadsJson = ApiJson.encodeToString(serializer, pending.values.toList())
    }
}
