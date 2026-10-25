package com.parthajb.homereel.server

import android.content.Context
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.StatFs
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.parthajb.homereel.shared.Bitrate
import java.io.File

/**
 * Makes lighter copies of very heavy files with the phone's hardware encoder, one at a time on its own thread:
 * the same resolution at a bitrate Wi-Fi carries smoothly (a 4K clip goes from about 90 to 24 Mbps). The copy lives
 * in app storage, the original stays untouched in the library, and the TV is sent the copy.
 */
@OptIn(markerClass = [UnstableApi::class])
class Optimizer(
    private val context: Context,
    private val dir: File,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun source(id: String): Pair<Uri, Entry>?
        fun finished(id: String, ok: Boolean, error: String?)
        fun busy(on: Boolean)
    }

    private val thread = HandlerThread("optimizer").apply { start() }
    private val handler = Handler(thread.looper)
    private val queue = LinkedHashSet<String>()
    private var transformer: Transformer? = null

    @Volatile private var current: String? = null
    @Volatile private var progress = 0

    init {
        dir.mkdirs()
        // Half-written copies from a run that was cut short.
        dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun file(id: String) = File(dir, "$id.mp4")

    /** 0–100 for the file being worked on, -1 for one waiting, null for neither. */
    fun status(id: String): Int? = when {
        current == id -> progress
        synchronized(queue) { id in queue } -> -1
        else -> null
    }

    fun queued(): Int = synchronized(queue) { queue.size } + if (current != null) 1 else 0

    fun enqueue(id: String) {
        synchronized(queue) {
            if (id == current || !queue.add(id)) return
        }
        handler.post(::next)
    }

    fun cancel(id: String) {
        synchronized(queue) { queue.remove(id) }
        if (current == id) handler.post { transformer?.cancel(); done(id, false, null) }
    }

    val charging: Boolean
        get() = context.getSystemService(BatteryManager::class.java)?.isCharging == true

    private fun next() {
        if (current != null) return
        val id = synchronized(queue) { queue.firstOrNull()?.also { queue.remove(it) } } ?: return
        val (uri, entry) = callbacks.source(id) ?: return next()
        val video = entry.video
        if (video == null) {
            callbacks.finished(id, false, "No video track the phone can read")
            return next()
        }
        val bitrate = Bitrate.target(video.width, video.height)
        val needed = bitrate / 8L * (entry.durationMs / 1000 + 1) * 11 / 10 + 500L * 1024 * 1024
        if (StatFs(dir.path).availableBytes < needed) {
            callbacks.finished(id, false, "Not enough space on the phone for a lighter copy")
            return next()
        }
        // HEVC keeps 4K small; otherwise H.264, which every TV plays.
        val hevc = video.mime == MimeTypes.VIDEO_H265 || minOf(video.width, video.height) >= 2000
        start(id, uri, bitrate, if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264, retryWithH264 = hevc)
    }

    private fun start(id: String, uri: Uri, bitrate: Int, mime: String, retryWithH264: Boolean) {
        current = id
        progress = 0
        callbacks.busy(true)
        val out = File(dir, "$id.mp4.tmp")
        out.delete()
        val t = Transformer.Builder(context)
            .setLooper(thread.looper)
            .setVideoMimeType(mime)
            .setEncoderFactory(
                DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setEnableFallback(true)
                    .build(),
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) {
                    val ok = out.renameTo(file(id))
                    Log.i(TAG, "Optimised $id: ${result.fileSizeBytes / 1_000_000} MB")
                    done(id, ok, if (ok) null else "Couldn't save the copy")
                }

                override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                    Log.w(TAG, "Optimising $id failed with $mime", exception)
                    out.delete()
                    if (retryWithH264) {
                        // start() takes the wake lock again, so give this one back first.
                        callbacks.busy(false)
                        current = null
                        start(id, uri, bitrate, MimeTypes.VIDEO_H264, retryWithH264 = false)
                    } else {
                        done(id, false, describe(exception))
                    }
                }
            })
            .build()
        transformer = t
        t.start(EditedMediaItem.Builder(MediaItem.fromUri(uri)).build(), out.path)
        handler.post(object : Runnable {
            override fun run() {
                if (current != id || transformer !== t) return
                val holder = ProgressHolder()
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun done(id: String, ok: Boolean, error: String?) {
        if (current != id) return
        current = null
        transformer = null
        callbacks.busy(false)
        callbacks.finished(id, ok, error)
        handler.post(::next)
    }

    private fun describe(e: ExportException): String = when (e.errorCode) {
        ExportException.ERROR_CODE_DECODER_INIT_FAILED, ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "The phone can't decode this file's video or audio (DTS and TrueHD audio need converting on a computer)"
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
            "The phone's encoder can't make a copy this size"
        else -> e.errorCodeName
    }

    private companion object {
        const val TAG = "HomeReel"
    }
}
