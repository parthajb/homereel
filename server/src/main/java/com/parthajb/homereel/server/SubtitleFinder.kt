package com.parthajb.homereel.server

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.FeedSafety
import com.parthajb.homereel.shared.OpenSubtitles
import kotlinx.serialization.KSerializer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.time.Instant

/** What to search for besides the file's hash: its IMDb id once matched, else its title, and an episode's number. */
class SubtitleQuery(val imdbId: String?, val title: String, val year: Int?, val season: Int? = null, val episode: Int? = null)

/** OpenSubtitles turned the key away, or its download limit for today is reached: asking again won't help yet. */
class SubtitlesStopped(message: String) : IOException(message)

/**
 * Finds subtitles on OpenSubtitles for a video with none, with the key and languages set on the admin page. Off
 * without a key. The phone sends the file's hash, its size, its title or IMDb id and the languages, and nothing else.
 * Downloaded subtitles are kept in the app's own storage: the movie folder is never written to.
 */
class SubtitleFinder(context: Context, private val prefs: Prefs) {
    private val cr = context.contentResolver
    val dir = File(context.filesDir, "subtitles").apply { mkdirs() }
    private val base get() = if (BuildConfig.DEBUG) prefs.openSubtitlesBaseUrl else "https://api.opensubtitles.com/api/v1"
    private var lastRequestAt = 0L

    val languages: List<String> get() = OpenSubtitles.languages(prefs.subtitleLanguages)
    val configured: Boolean get() = !prefs.openSubtitlesKey.isNullOrBlank() && languages.isNotEmpty()

    /** When the daily download limit lifts, if it was reached; 0 otherwise. */
    val pausedUntil: Long get() = prefs.subtitlesPausedUntil.takeIf { it > System.currentTimeMillis() } ?: 0

    /** Checks a key by asking for OpenSubtitles' list of formats, which needs only the key. */
    fun checkKey(key: String) {
        val c = open("$base/infos/formats", key)
        try {
            when (val code = c.responseCode) {
                200 -> Unit
                401, 403 -> throw SubtitlesStopped("OpenSubtitles turned that key away. Copy it again from opensubtitles.com/consumers.")
                else -> throw IOException("OpenSubtitles answered $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads the best subtitles for [uri] in each wanted language that isn't in [have], into [dir], named after
     * [entryId]. Returns the languages and file names saved; none when OpenSubtitles has nothing.
     */
    fun find(entryId: String, uri: Uri, size: Long, query: SubtitleQuery, have: Set<String>): List<FetchedSubtitle> {
        val key = prefs.openSubtitlesKey ?: throw SubtitlesStopped("Add an OpenSubtitles key in Settings first")
        if (pausedUntil > 0) throw SubtitlesStopped(pausedMessage())
        val wanted = languages.filter { it !in have }
        if (wanted.isEmpty()) return emptyList()
        // Parameters sorted and in lower case, as OpenSubtitles asks, so its cache can answer.
        val params = sortedMapOf<String, String>()
        params["languages"] = wanted.sorted().joinToString(",")
        hashOf(uri, size)?.let { params["moviehash"] = it }
        if (query.imdbId != null && query.season == null) {
            params["imdb_id"] = query.imdbId.removePrefix("tt").trimStart('0')
        } else {
            params["query"] = query.title.lowercase()
            query.year?.takeIf { query.season == null }?.let { params["year"] = it.toString() }
        }
        if (query.season != null && query.episode != null) {
            params["season_number"] = query.season.toString()
            params["episode_number"] = query.episode.toString()
            params["type"] = "episode"
        } else {
            params["type"] = "movie"
        }
        val url = "$base/subtitles?" + params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val page = get(url, key, OpenSubtitles.SearchPage.serializer())
        val saved = ArrayList<FetchedSubtitle>()
        for (choice in OpenSubtitles.pick(page.data, wanted)) {
            val reply = post("$base/download", key, ApiJson.encodeToString(OpenSubtitles.DownloadRequest.serializer(), OpenSubtitles.DownloadRequest(choice.fileId)))
            val link = reply.link ?: throw IOException(reply.message ?: "OpenSubtitles gave no download link")
            val bytes = download(link)
            val name = "$entryId.${choice.language}.srt"
            File(dir, name).writeBytes(bytes)
            saved += FetchedSubtitle(choice.language, name)
            if ((reply.remaining ?: 1) <= 0) {
                pauseUntil(reply.resetTimeUtc)
                break
            }
        }
        return saved
    }

    fun file(f: FetchedSubtitle) = File(dir, f.fileName)

    fun pausedMessage(): String {
        val at = java.util.Date(pausedUntil)
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(at)
        val today = android.text.format.DateUtils.isToday(pausedUntil)
        return "OpenSubtitles' download limit for today is reached. HomeReel asks again ${if (today) "at" else "tomorrow at"} $time."
    }

    private fun pauseUntil(resetUtc: String?) {
        val at = resetUtc?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: (System.currentTimeMillis() + DAY)
        prefs.subtitlesPausedUntil = at.coerceIn(System.currentTimeMillis() + 60_000, System.currentTimeMillis() + DAY)
    }

    /** The OpenSubtitles hash of the file's first and last 64 KB; none for a file too small to have one. */
    private fun hashOf(uri: Uri, size: Long): String? {
        if (size < 2L * OpenSubtitles.CHUNK) return null
        return runCatching {
            ParcelFileDescriptor.AutoCloseInputStream(cr.openFileDescriptor(uri, "r")!!).channel.use { ch ->
                fun read(at: Long): ByteArray {
                    val buf = ByteBuffer.allocate(OpenSubtitles.CHUNK)
                    ch.position(at)
                    while (buf.hasRemaining() && ch.read(buf) > 0) Unit
                    return buf.array()
                }
                OpenSubtitles.hash(size, read(0), read(size - OpenSubtitles.CHUNK))
            }
        }.onFailure { Log.w(TAG, "Couldn't hash $uri", it) }.getOrNull()
    }

    // ---- Talking to OpenSubtitles ----

    private fun <T> get(url: String, key: String, serializer: KSerializer<T>): T = decode(send(open(url, key)), serializer)

    private fun post(url: String, key: String, body: String): OpenSubtitles.DownloadReply {
        val c = open(url, key)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        return decode(send(c), OpenSubtitles.DownloadReply.serializer())
    }

    /** Waits so requests are at least a second apart: OpenSubtitles allows a few a second, but there's no hurry. */
    @Synchronized private fun open(url: String, key: String): HttpURLConnection {
        val wait = lastRequestAt + 1000 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Api-Key", key)
            setRequestProperty("User-Agent", "HomeReel v${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun send(c: HttpURLConnection): String {
        try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            when (code) {
                in 200..299 -> return text
                401, 403 -> throw SubtitlesStopped("OpenSubtitles turned the key away. Check it in Settings → Movie info.")
                406, 429 -> {
                    val reset = runCatching { ApiJson.decodeFromString(OpenSubtitles.DownloadReply.serializer(), text).resetTimeUtc }.getOrNull()
                    if (code == 406 || "download" in text.lowercase()) {
                        pauseUntil(reset)
                        throw SubtitlesStopped(pausedMessage())
                    }
                    throw IOException("OpenSubtitles says too many requests; it'll be tried again later")
                }
                else -> throw IOException("OpenSubtitles answered $code")
            }
        } finally {
            c.disconnect()
        }
    }

    /** The subtitle file from OpenSubtitles' download link: https only, at most 2 MB. */
    private fun download(link: String): ByteArray {
        val url = URL(link)
        // A debug build's fake OpenSubtitles may hand out its own plain http:// links.
        val fake = BuildConfig.DEBUG && FeedSafety.sameHost(link, base)
        if (url.protocol != "https" && !fake) throw IOException("OpenSubtitles gave a download link that isn't https")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "HomeReel v${BuildConfig.VERSION_NAME}")
        try {
            if (c.responseCode != 200) throw IOException("The subtitle download answered ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) throw IOException("That subtitle file is too big to be one")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun <T> decode(text: String, serializer: KSerializer<T>): T = try {
        ApiJson.decodeFromString(serializer, text)
    } catch (e: Exception) {
        throw IOException("Unexpected answer from OpenSubtitles", e)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "HomeReel"
        const val DAY = 24 * 60 * 60 * 1000L
        const val MAX_BYTES = 2 * 1024 * 1024
    }
}
