package com.parthajb.homereel.tv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import com.parthajb.homereel.shared.AudioTrack
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.Playability
import com.parthajb.homereel.shared.VideoTrack

/**
 * Checks a movie against this TV's own decoders, the way the player will: video by codec profile and size, audio
 * by a decoder or by passing it through to a soundbar or receiver over HDMI.
 */
@OptIn(markerClass = [UnstableApi::class])
class TvCodecs(context: Context) {
    @Suppress("DEPRECATION")
    private val audioCapabilities = AudioCapabilities.getCapabilities(context)
    private val cache = HashMap<String, Boolean>()

    fun reason(movie: Movie): String? = Playability.reason(movie, ::video, ::audio)

    private fun video(v: VideoTrack): Boolean = cache.getOrPut("v:${v.mime}:${v.codecs}:${v.width}x${v.height}") {
        val format = Format.Builder()
            .setSampleMimeType(v.mime)
            .setCodecs(v.codecs)
            .setWidth(if (v.width > 0) v.width else Format.NO_VALUE)
            .setHeight(if (v.height > 0) v.height else Format.NO_VALUE)
            .setFrameRate(if (v.frameRate > 0) v.frameRate else Format.NO_VALUE.toFloat())
            .build()
        // Dolby Vision files usually carry an HEVC or H.264 base layer that the player falls back to.
        val mimes = listOfNotNull(v.mime, MediaCodecUtil.getAlternativeCodecMimeType(format))
        mimes.any { mime ->
            runCatching {
                MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.isFormatSupported(format) }
            }.getOrDefault(false)
        }
    }

    private fun audio(a: AudioTrack): Boolean = cache.getOrPut("a:${a.mime}:${a.codecs}") {
        val decoder = runCatching { MediaCodecUtil.getDecoderInfos(a.mime, false, false).isNotEmpty() }.getOrDefault(false)
        val encoding = MimeTypes.getEncoding(a.mime, a.codecs)
        decoder || (encoding != C.ENCODING_INVALID && audioCapabilities.supportsEncoding(encoding))
    }
}
