package com.parthajb.homereel.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finding subtitles on OpenSubtitles (opensubtitles.com), with a free API key the person adds. The phone sends the
 * file's OpenSubtitles hash, its size, its title (or IMDb id) and the languages wanted, and nothing else.
 */
object OpenSubtitles {
    /** How much of each end of the file the hash reads. */
    const val CHUNK = 64 * 1024

    /**
     * OpenSubtitles' file hash: the size plus every 64-bit little-endian word in the first and last 64 KB, wrapping
     * on overflow, as 16 hex digits. It finds subtitles timed for this exact file.
     */
    fun hash(size: Long, head: ByteArray, tail: ByteArray): String {
        var h = size
        for (chunk in listOf(head, tail)) {
            val buf = ByteBuffer.wrap(chunk, 0, chunk.size - chunk.size % 8).order(ByteOrder.LITTLE_ENDIAN)
            while (buf.remaining() >= 8) h += buf.long
        }
        return "%016x".format(java.util.Locale.ROOT, h)
    }

    /** "en, hi, pt-BR" → [en, hi, pt-br]; anything that isn't a language code is dropped. At most five. */
    fun languages(typed: String?): List<String> =
        typed.orEmpty().split(',', ' ', ';').map { it.trim().lowercase() }.filter { it.matches(Regex("[a-z]{2}(-[a-z]{2})?")) }.distinct().take(5)

    @Serializable
    class SearchPage(val data: List<Result> = emptyList())

    @Serializable
    class Result(val attributes: Attributes = Attributes())

    @Serializable
    class Attributes(
        val language: String? = null,
        @SerialName("download_count") val downloadCount: Long = 0,
        @SerialName("moviehash_match") val hashMatch: Boolean = false,
        @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
        @SerialName("machine_translated") val machineTranslated: Boolean = false,
        @SerialName("ai_translated") val aiTranslated: Boolean = false,
        val files: List<File> = emptyList(),
    )

    @Serializable
    class File(@SerialName("file_id") val fileId: Long = 0, @SerialName("file_name") val fileName: String? = null)

    @Serializable
    class DownloadRequest(@SerialName("file_id") val fileId: Long)

    @Serializable
    class DownloadReply(
        val link: String? = null,
        val remaining: Int? = null,
        val message: String? = null,
        @SerialName("reset_time_utc") val resetTimeUtc: String? = null,
    )

    /** What to download: the file and its language. */
    data class Choice(val language: String, val fileId: Long)

    /**
     * The best subtitle for each wanted language, in the order wanted: one timed for this exact file first, then one
     * a person translated rather than a machine, then the plainer one (not for the hard of hearing), then the most
     * downloaded.
     */
    fun pick(results: List<Result>, wanted: List<String>): List<Choice> = wanted.mapNotNull { lang ->
        results.map { it.attributes }
            .filter { it.language.equals(lang, ignoreCase = true) && it.files.isNotEmpty() && it.files.first().fileId > 0 }
            .sortedWith(
                compareByDescending<Attributes> { it.hashMatch }
                    .thenBy { it.machineTranslated || it.aiTranslated }
                    .thenBy { it.hearingImpaired }
                    .thenByDescending { it.downloadCount },
            )
            .firstOrNull()?.let { Choice(lang, it.files.first().fileId) }
    }

    /** "English · OpenSubtitles", the label the TV and web app show. */
    fun label(language: String): String {
        val name = java.util.Locale.forLanguageTag(language).getDisplayLanguage(java.util.Locale.ENGLISH).ifEmpty { language }
        return "$name · OpenSubtitles"
    }
}
