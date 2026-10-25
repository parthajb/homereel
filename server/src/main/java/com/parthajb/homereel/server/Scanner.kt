package com.parthajb.homereel.server

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.MetadataRetriever
import com.parthajb.homereel.shared.AudioTrack
import com.parthajb.homereel.shared.EpisodeParser
import com.parthajb.homereel.shared.Kind
import com.parthajb.homereel.shared.MatchState
import com.parthajb.homereel.shared.MediaFiles
import com.parthajb.homereel.shared.MovieIds
import com.parthajb.homereel.shared.Subtitle
import com.parthajb.homereel.shared.TitleParser
import com.parthajb.homereel.shared.VideoTrack
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Walks the library folder. A file whose size and date haven't changed keeps everything it had; a new or changed
 * one is probed for its tracks, and gets a poster from a frame if no picture sits beside it.
 */
class Scanner(private val context: Context, private val posterDir: File) {
    private val cr = context.contentResolver

    /** A folder still to walk: its document id, the folder names from the top down to it, and whether it's private. */
    private class Dir(val docId: String, val path: List<String>, val private: Boolean)

    fun scan(tree: Uri, previous: Map<String, Entry>): List<Entry> {
        val result = ArrayList<Entry>()
        val pending = ArrayDeque<Dir>().apply { add(Dir(Saf.rootDocId(tree), emptyList(), false)) }
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            val dirId = dir.docId
            val rows = Saf.children(cr, tree, dirId)
            if (dir.private) hideFromGallery(tree, dir, rows)
            rows.filter { it.isDir && !it.name.startsWith(".") }.forEach {
                // The Private folder is the one called Private at the top of the library, and everything inside it.
                val private = dir.private || (dir.path.isEmpty() && it.name.equals(PRIVATE_FOLDER, ignoreCase = true))
                pending += Dir(it.docId, dir.path + it.name, private)
            }
            val files = rows.filter { !it.isDir && !it.name.startsWith(".") && !it.name.endsWith(".part") }
            val videos = files.filter { MediaFiles.isVideo(it.name) }
            // poster.jpg / folder.jpg belong to a folder's only movie, the usual one-folder-per-movie layout.
            val folderPoster = if (videos.size == 1) {
                files.firstOrNull { it.name.lowercase() in FOLDER_POSTERS }
            } else {
                null
            }
            for (video in videos) {
                result += entryFor(tree, dir, video, files, folderPoster, previous)
            }
        }
        return result
    }

    /**
     * A `.nomedia` file tells the phone's gallery and video apps to leave the folder out, so personal videos don't
     * turn up in Photos. It doesn't stop a file manager, so the phone itself still needs its screen lock.
     */
    private fun hideFromGallery(tree: Uri, dir: Dir, rows: List<DocRow>) {
        if (rows.any { it.name == ".nomedia" }) return
        runCatching {
            DocumentsContract.createDocument(cr, Saf.docUri(tree, dir.docId), "application/octet-stream", ".nomedia")
        }.onFailure { Log.w(TAG, "Couldn't create .nomedia in ${dir.path}", it) }
    }

    private fun entryFor(
        tree: Uri,
        dir: Dir,
        video: DocRow,
        siblings: List<DocRow>,
        folderPoster: DocRow?,
        previous: Map<String, Entry>,
    ): Entry {
        val dirId = dir.docId
        val uri = Saf.docUri(tree, video.docId)
        val id = MovieIds.of(uri.toString())
        val base = MediaFiles.baseName(video.name)
        val poster = siblings.firstOrNull {
            MediaFiles.extension(it.name) in MediaFiles.IMAGE_EXTENSIONS &&
                MediaFiles.baseName(it.name).equals(base, ignoreCase = true)
        } ?: folderPoster
        val subtitleRows = siblings.filter {
            val b = MediaFiles.baseName(it.name)
            MediaFiles.extension(it.name) in MediaFiles.SUBTITLE_EXTENSIONS &&
                (b.equals(base, ignoreCase = true) || b.startsWith("$base.", ignoreCase = true))
        }.sortedBy { it.name.lowercase() }
        val subtitles = subtitleRows.mapIndexed { i, row ->
            val tag = MediaFiles.baseName(row.name).drop(base.length).trimStart('.').ifEmpty { null }
            Subtitle(i, languageLabel(tag) ?: "Subtitles ${i + 1}", tag, MediaFiles.subtitleMime(row.name))
        }

        val old = previous[id]
        val unchanged = old != null && old.size == video.size && old.modified == video.modified
        var entry = if (unchanged) {
            old!!
        } else {
            val parsed = TitleParser.parse(video.name)
            val probe = probe(uri)
            // A changed file keeps everything people set on it: title, details, thumbnail, resume point.
            val base = old ?: Entry(
                id = id, docId = video.docId, parentDocId = dirId, fileName = video.name, size = 0, modified = 0,
                parsedTitle = parsed.title,
                addedAt = video.modified.takeIf { it > 0 } ?: System.currentTimeMillis(),
            )
            base.copy(
                size = video.size, modified = video.modified,
                parsedTitle = parsed.title, parsedYear = parsed.year,
                durationMs = probe.durationMs, video = probe.video, audio = probe.audio, probeError = probe.error,
                frameFailed = false,
                // A changed file needs a new lighter copy; the old one is removed after the scan.
                optimized = false, optimizeError = null,
            )
        }
        if (entry.posterDocId != poster?.docId) entry = entry.copy(artVersion = System.currentTimeMillis())
        // Private videos are personal: never treated as episodes of a show, never looked up online.
        val episode = if (dir.private) null else EpisodeParser.parse(video.name, dir.path)
        entry = entry.copy(
            fileName = video.name, parentDocId = dirId,
            posterDocId = poster?.docId, subtitleDocIds = subtitleRows.map { it.docId }, subtitles = subtitles,
            private = dir.private,
            kind = if (episode != null) Kind.EPISODE else Kind.MOVIE,
            showId = episode?.let { EpisodeParser.showId(it.show, it.showYear) },
            showName = episode?.show,
            showYear = episode?.showYear,
            season = episode?.season,
            episode = episode?.episode,
            episodeTitle = episode?.title,
            match = when {
                // Episodes are looked up through their show, not one by one.
                dir.private || episode != null -> MatchState.SKIPPED
                else -> entry.match
            },
        )
        // The frame is kept even when a poster exists: it is the wide backdrop behind the TV's hero area.
        val frame = frameFile(id)
        entry = when {
            unchanged && entry.frameVersion == FRAME_VERSION && entry.posterGenerated && frame.exists() -> entry
            // Don't retry a frame the phone couldn't decode on every scan; a changed file tries again.
            unchanged && entry.frameVersion == FRAME_VERSION && entry.frameFailed -> entry
            else -> {
                val ok = grabFrame(uri, entry.durationMs, frame)
                entry.copy(
                    posterGenerated = ok, frameFailed = !ok, frameVersion = FRAME_VERSION,
                    artVersion = System.currentTimeMillis(),
                )
            }
        }
        return entry
    }

    fun frameFile(id: String) = File(posterDir, "$id.jpg")

    fun customPosterFile(id: String) = File(posterDir, "custom-$id.jpg")

    private class Probe(val durationMs: Long, val video: VideoTrack?, val audio: List<AudioTrack>, val error: String?)

    @OptIn(markerClass = [UnstableApi::class])
    private fun probe(uri: Uri): Probe {
        var video: VideoTrack? = null
        val audio = ArrayList<AudioTrack>()
        var error: String? = null
        try {
            // Media3's extractors are the same ones the TV plays with, so they see DTS, TrueHD and AVI tracks
            // that the phone's own MediaExtractor would skip.
            val groups = MetadataRetriever.retrieveMetadata(context, MediaItem.fromUri(uri)).get(90, TimeUnit.SECONDS)
            for (i in 0 until groups.length) {
                val format = groups[i].getFormat(0)
                val mime = format.sampleMimeType ?: continue
                if (MimeTypes.isVideo(mime) && video == null) {
                    video = VideoTrack(
                        mime, format.codecs, format.width.coerceAtLeast(0), format.height.coerceAtLeast(0),
                        format.frameRate.coerceAtLeast(0f),
                    )
                } else if (MimeTypes.isAudio(mime)) {
                    // "und" is the container's way of saying it doesn't know.
                    val language = format.language?.takeIf { it != "und" }
                    audio += AudioTrack(mime, format.codecs, language, format.channelCount.coerceAtLeast(0), format.label)
                }
            }
        } catch (e: Exception) {
            val cause = e.cause ?: e
            Log.w(TAG, "Couldn't read tracks of $uri", cause)
            error = cause.message?.take(120) ?: cause.javaClass.simpleName
        }
        var duration = 0L
        withRetriever(uri) { mmr ->
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        }
        return Probe(duration, video, audio, error)
    }

    /**
     * A frame from 15% in (past most opening logos), at most 1280 px wide: sharp enough for a full-screen backdrop.
     * Fails quietly on codecs the phone can't decode, and the TV shows the title instead.
     */
    private fun grabFrame(uri: Uri, durationMs: Long, out: File): Boolean = withRetriever(uri) { mmr ->
        val atUs = if (durationMs > 0) durationMs * 150 else 60_000_000L
        val frame = mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: error("no frame")
        val scaled = if (frame.width > 1280) {
            Bitmap.createScaledBitmap(frame, 1280, frame.height * 1280 / frame.width, true)
        } else {
            frame
        }
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        true
    } ?: false.also { out.delete() }

    /** MediaMetadataRetriever is only AutoCloseable from API 29, so release it by hand. */
    private fun <T> withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> T): T? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            block(mmr)
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever failed for $uri: ${e.message}")
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun languageLabel(tag: String?): String? {
        if (tag == null) return null
        val code = tag.substringBefore('.')
        if (code.length !in 2..3) return tag
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
        return if (name.isEmpty() || name.equals(code, ignoreCase = true)) tag else name
    }

    companion object {
        private const val TAG = "HomeReel"
        private const val FRAME_VERSION = 2

        /** The folder at the top of the library whose videos are private. */
        const val PRIVATE_FOLDER = "Private"
        private val FOLDER_POSTERS = setOf("poster.jpg", "poster.jpeg", "poster.png", "folder.jpg", "cover.jpg")
    }
}
