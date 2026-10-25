package com.parthajb.homereel.shared

/**
 * Why a movie may not play. The phone can only guess with [baseline]; the TV checks its own decoders and reports
 * back, and that report replaces the guess.
 */
object Playability {
    private val BASELINE_VIDEO = setOf(
        "video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/x-vnd.on2.vp8", "video/mp4v-es", "video/mpeg2",
        "video/3gpp", "video/dolby-vision",
    )
    private val BASELINE_AUDIO = setOf(
        "audio/mp4a-latm", "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2", "audio/ac3", "audio/eac3",
        "audio/eac3-joc", "audio/opus", "audio/vorbis", "audio/flac", "audio/raw",
    )

    fun reason(movie: Movie, videoOk: (VideoTrack) -> Boolean, audioOk: (AudioTrack) -> Boolean): String? {
        movie.probeError?.let { return "Couldn't read the file ($it)" }
        val video = movie.video ?: return "No video track found"
        if (!videoOk(video)) {
            val size = if (video.height > 0) " at ${video.width}×${video.height}" else ""
            return "${describe(video.mime)} video$size isn't supported"
        }
        if (movie.audio.isNotEmpty() && movie.audio.none(audioOk)) {
            return "${movie.audio.map { describe(it.mime) }.distinct().joinToString(", ")} audio isn't supported"
        }
        return null
    }

    fun baseline(movie: Movie): String? = reason(
        movie,
        { it.mime in BASELINE_VIDEO && !(it.mime == "video/hevc" && it.height > 2160) },
        { it.mime in BASELINE_AUDIO },
    )

    fun describe(mime: String): String = when (mime) {
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC (H.265)"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/av01" -> "AV1"
        "video/mp4v-es" -> "MPEG-4 (DivX/Xvid)"
        "video/mpeg2" -> "MPEG-2"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2" -> "MP3"
        "audio/ac3" -> "Dolby Digital"
        "audio/eac3", "audio/eac3-joc" -> "Dolby Digital Plus"
        "audio/true-hd" -> "Dolby TrueHD"
        "audio/vnd.dts" -> "DTS"
        "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC"
        else -> mime.substringAfter('/')
    }
}
