package com.parthajb.homereel.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Constants both apps agree on. Bump [VERSION] only for a change an older TV app can't read. */
object Api {
    const val VERSION = 1
    const val PREFIX = "/api/v1"
    const val SERVICE_TYPE = "_homereel._tcp"
    const val DEFAULT_PORT = 8080
    const val ROLE_VIEWER = "viewer"
    const val ROLE_ADMIN = "admin"
    const val TOKEN_COOKIE = "hr_token"

    /** Cookie-authenticated requests that change something must carry this header, which a cross-site form can't send. */
    const val CSRF_HEADER = "x-homereel"
    const val CHUNK_BYTES = 8 * 1024 * 1024
}

val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class VideoTrack(
    val mime: String,
    val codecs: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
)

@Serializable
data class AudioTrack(
    val mime: String,
    val codecs: String? = null,
    val language: String? = null,
    val channels: Int = 0,
    val label: String? = null,
)

/** A subtitle file that sits beside the movie: `Movie.srt` or `Movie.en.srt`. */
@Serializable
data class Subtitle(
    val index: Int,
    val label: String,
    val language: String? = null,
    val mime: String = "application/x-subrip",
)

@Serializable
data class Movie(
    val id: String,
    val title: String,
    val year: Int? = null,
    val fileName: String,
    val container: String = "",
    val size: Long = 0,
    val durationMs: Long = 0,
    val video: VideoTrack? = null,
    val audio: List<AudioTrack> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    /** How much later (earlier when negative) the subtitle files are served, set from the TV or the web app. */
    val subtitleOffsetMs: Long = 0,
    val addedAt: Long = 0,
    val positionMs: Long = 0,
    val watched: Boolean = false,
    val lastPlayedAt: Long = 0,
    val hasPoster: Boolean = false,
    /** A wide picture for the TV's hero area: a frame from the film, or the poster if no frame could be grabbed. */
    val hasBackdrop: Boolean = false,
    /** True when the poster is a thumbnail uploaded on the admin page. */
    val customPoster: Boolean = false,
    /** Changes whenever the poster or backdrop changes, so the TV knows to fetch them again. */
    val artVersion: Long = 0,
    /** Set when the phone couldn't read the file's tracks at all. */
    val probeError: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    /** Typed in on the admin page, or looked up by the phone (OMDb for IMDb, else TMDB's own score). */
    val imdbRating: Double? = null,
    /** Where [imdbRating] came from: "IMDb" (typed in or from OMDb) or "TMDB". */
    val ratingSource: String? = null,
    val tagline: String? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    /** The age rating, Indian first (U, UA 13+, A…), else American (PG-13…). */
    val certification: String? = null,
    val collection: String? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    /** One of [MatchState]. */
    val match: String = MatchState.NONE,
    /** Fields typed in on the admin page, which a TMDB refresh never overwrites. */
    val locked: List<String> = emptyList(),
    /** "movie" or "episode". Episodes only come inside a [ShowDetail] (and as a show's up-next). */
    val kind: String = Kind.MOVIE,
    val showId: String? = null,
    val showTitle: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** In the Private folder: only listed while the admin has it unlocked, and never looked up online. */
    val private: Boolean = false,
    /** A camera or phone clip, or one marked Don't look up: it goes in Home videos rather than among the films. */
    val homeVideo: Boolean = false,
    /** For a home video, when it was recorded: from the date in its name, else when the file last changed. */
    val recordedAt: Long? = null,
    /** On the asking profile's My List. */
    val inMyList: Boolean = false,
    /** The admin's choice for kids profiles: [KidsSetting]. */
    val kids: String = KidsSetting.AUTO,
    /** Whether kids profiles see it, after applying [kids] to the age rating. */
    val kidsOk: Boolean = false,
    /** The file's average bitrate; over [Bitrate.HEAVY_MBPS] it may stutter over Wi-Fi. */
    val bitrateMbps: Double? = null,
    /** A lighter copy made by the phone exists and is what the TV streams. */
    val optimized: Boolean = false,
    /** 0–100 while the phone is making that copy; -1 while it's waiting its turn. */
    val optimizing: Int? = null,
    val optimizeError: String? = null,
    /** Episodes: where the intro is, and where the credits start, from the show's per-season markers. */
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    /** On an extra folder that isn't there right now (a USB drive unplugged): listed for the admin only. */
    val offline: Boolean = false,
)

/** Where a season's intro sits, and how long its end credits run, for Skip intro and an early Next episode. */
@Serializable
data class SeasonMarkers(
    val season: Int,
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    /** The length of the end credits, counted back from each episode's end. */
    val creditsMs: Long? = null,
)

/** Whether a title shows on kids profiles: from its age rating, or the admin's yes or no. */
object KidsSetting {
    const val AUTO = "auto"
    const val YES = "yes"
    const val NO = "no"

    fun valid(value: String?) = value == null || value == AUTO || value == YES || value == NO
}

/** A person (or a household) watching: their own progress, My List and Continue watching. */
@Serializable
data class Profile(val id: String, val name: String, val avatar: Int = 0, val kids: Boolean = false)

@Serializable
data class ProfileList(val profiles: List<Profile>, val pinSet: Boolean)

@Serializable
data class ProfileEdit(val name: String, val avatar: Int = 0, val kids: Boolean = false)

/** A null [pin] clears it. */
@Serializable
data class PinUpdate(val pin: String? = null)

@Serializable
data class PinCheck(val pin: String)

object Kind {
    const val MOVIE = "movie"
    const val EPISODE = "episode"
}

/** A series made of the episode files in the library. */
@Serializable
data class Show(
    val id: String,
    val title: String,
    val year: Int? = null,
    val endYear: Int? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: Double? = null,
    val ratingSource: String? = null,
    val certification: String? = null,
    val cast: List<String> = emptyList(),
    val creators: String? = null,
    val seasons: List<Int> = emptyList(),
    val episodeCount: Int = 0,
    val addedAt: Long = 0,
    val lastPlayedAt: Long = 0,
    /** The episode to carry on with: the one in progress, or the one after the last watched. */
    val upNext: Movie? = null,
    val hasPoster: Boolean = false,
    val hasBackdrop: Boolean = false,
    val customPoster: Boolean = false,
    val artVersion: Long = 0,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val match: String = MatchState.NONE,
    val locked: List<String> = emptyList(),
    val inMyList: Boolean = false,
    val kids: String = KidsSetting.AUTO,
    val kidsOk: Boolean = false,
    val markers: List<SeasonMarkers> = emptyList(),
)

@Serializable
data class ShowList(val shows: List<Show>)

/** A show and its episodes in order, season by season. */
@Serializable
data class ShowDetail(val show: Show, val episodes: List<Movie>)

/** Where the Private folder stands. [until] is when it locks itself again. */
@Serializable
data class PrivateState(val unlocked: Boolean, val until: Long? = null, val count: Int = 0)

@Serializable
data class PrivateUpdate(val unlock: Boolean, val minutes: Int = 120)

@Serializable
data class MoveRequest(val private: Boolean)

/** How a movie stands with the online lookup. */
object MatchState {
    /** Not looked up yet. */
    const val NONE = "none"
    const val MATCHED = "matched"

    /** Looked up, but no result was certain enough: pick one on the admin page. */
    const val NEEDS_MATCH = "needs-match"

    /** A home video, or the user said not to look it up. */
    const val SKIPPED = "skipped"
}

@Serializable
data class MovieList(val movies: List<Movie>)

@Serializable
data class ServerInfo(
    val name: String,
    val version: String,
    val api: Int = Api.VERSION,
    val movieCount: Int,
    val scanning: Boolean,
    /** Set while the Private folder is unlocked: when it locks again. */
    val privateUntil: Long? = null,
)

@Serializable
data class PairRequest(val code: String, val client: String, val role: String = Api.ROLE_VIEWER)

@Serializable
data class PairResponse(val token: String, val role: String, val serverName: String)

@Serializable
data class SubtitleOffset(val offsetMs: Long)

@Serializable
data class ProgressUpdate(val positionMs: Long, val durationMs: Long = 0)

/**
 * Everything the admin page can change about a movie. A blank [title] goes back to the title read from the file
 * name; a null [year], [description] or [imdbRating] clears it.
 */
@Serializable
data class MovieEdit(
    val title: String,
    val year: Int? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: Double? = null,
    /** Fields to hand back to the online lookup: "title", "year", "description", "genres", "rating". */
    val reset: List<String> = emptyList(),
    /** A [KidsSetting], or null to leave it as it is. */
    val kids: String? = null,
)

@Serializable
data class TmdbResult(
    val tmdbId: Int,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
)

@Serializable
data class MatchRequest(val tmdbId: Int)

/** What the admin page may know about the lookup settings. The keys themselves never leave the phone. */
@Serializable
data class MetadataSettings(
    val tmdbKeySet: Boolean,
    val omdbKeySet: Boolean,
    val autoMatch: Boolean,
    /** Debug builds only: where to send lookups, for tests against a fake TMDB. */
    val testHooks: Boolean = false,
    /** Make lighter copies of very heavy files by themselves, while the phone is charging. */
    val autoOptimize: Boolean = true,
    /** Subtitles are found on OpenSubtitles for videos with none, once a key is set. */
    val openSubtitlesKeySet: Boolean = false,
    /** The languages to find, as typed: "en, hi". */
    val subtitleLanguages: String = "en",
    /** Set while OpenSubtitles' daily download limit is reached: when it lifts. */
    val subtitlesPausedUntil: Long? = null,
)

/** A null field is left as it is; an empty key clears it. */
@Serializable
data class MetadataSettingsUpdate(
    val tmdbKey: String? = null,
    val omdbKey: String? = null,
    val autoMatch: Boolean? = null,
    val autoOptimize: Boolean? = null,
    val tmdbBaseUrl: String? = null,
    val tmdbImageBaseUrl: String? = null,
    val omdbBaseUrl: String? = null,
    val openSubtitlesKey: String? = null,
    val subtitleLanguages: String? = null,
    val openSubtitlesBaseUrl: String? = null,
)

@Serializable
data class PlayabilityItem(val id: String, val reason: String)

/** What the TV found it can't play, checked against its own decoders. */
@Serializable
data class PlayabilityReport(val client: String, val items: List<PlayabilityItem>)

@Serializable
data class UploadRequest(val name: String, val size: Long, val private: Boolean = false)

@Serializable
data class UploadState(
    val uploadId: String,
    val name: String,
    val size: Long,
    val offset: Long,
    val done: Boolean = false,
)

@Serializable
data class AdminStatus(
    val serverName: String,
    val libraryName: String? = null,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    val scanning: Boolean,
    val scanError: String? = null,
    val movieCount: Int,
    val cantPlay: List<PlayabilityItem>,
    /** "tv" when [cantPlay] came from a TV's own check, "guess" before any TV has reported. */
    val cantPlaySource: String,
    /** True when a TMDB key is set and automatic lookups are on. */
    val metadataEnabled: Boolean = false,
    /** Titles waiting to be looked up. */
    val metadataQueue: Int = 0,
    val metadataError: String? = null,
    /** Files waiting for, or being made into, a lighter copy. */
    val optimizeQueue: Int = 0,
    /** Videos waiting to have subtitles found. */
    val subtitleQueue: Int = 0,
    val subtitleError: String? = null,
)

@Serializable
data class ErrorBody(val error: String)

/** Something the server wants the admin to know about: a crash, a failed scan, a hot battery. */
@Serializable
data class Event(val time: Long, val level: String, val text: String) {
    companion object {
        const val INFO = "info"
        const val WARN = "warn"
        const val ERROR = "error"
    }
}

/** A folder the library reads: the main one (uploads and Private live there) or an extra one such as a USB drive. */
@Serializable
data class LibraryFolder(
    val uri: String,
    val name: String,
    val main: Boolean,
    val available: Boolean,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    val movieCount: Int = 0,
)

@Serializable
data class Health(
    val serverName: String,
    val version: String,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val batteryTempC: Double? = null,
    val uptimeMs: Long = 0,
    val wifiBand: String? = null,
    val wifiLinkMbps: Int? = null,
    val folders: List<LibraryFolder> = emptyList(),
    val lastScanAt: Long = 0,
    val lastScanMs: Long = 0,
    val movieCount: Int = 0,
    val showCount: Int = 0,
    val optimizeQueue: Int = 0,
    /** Plain-language problems to fix now: a hot battery, a missing USB drive, a nearly full phone. */
    val warnings: List<String> = emptyList(),
    val events: List<Event> = emptyList(),
)

@Serializable
data class RestoreSummary(val films: Int, val filmsInBackup: Int, val shows: Int, val showsInBackup: Int, val profiles: Int)

/** An app update the admin uploaded to the phone: the TV app ([Updates.TV]) or the phone app ([Updates.PHONE]). */
@Serializable
data class AppUpdate(
    val target: String,
    val versionCode: Long,
    val versionName: String,
    val size: Long,
    val sha256: String,
    val uploadedAt: Long,
)

@Serializable
data class UpdateStatus(
    val phoneVersionCode: Long,
    val phoneVersionName: String,
    val tv: AppUpdate? = null,
    val phone: AppUpdate? = null,
)

object Updates {
    const val TV = "tv"
    const val PHONE = "phone"
    const val TV_PACKAGE = "com.parthajb.homereel.tv"
    const val PHONE_PACKAGE = "com.parthajb.homereel.server"

    /** Whether an uploaded update is newer than what's installed. */
    fun isNewer(update: AppUpdate?, installedVersionCode: Long): Boolean = update != null && update.versionCode > installedVersionCode

    /** The CPU types the phone app is built for, one APK each, with what people call them. */
    val PHONE_ABIS = linkedMapOf("arm64-v8a" to "64-bit phones", "armeabi-v7a" to "32-bit phones")

    /** The CPU types an APK has native code for, from its file names (lib/<abi>/…). */
    fun abisIn(entryNames: Sequence<String>): Set<String> =
        entryNames.filter { it.startsWith("lib/") }.mapNotNull { it.split('/').getOrNull(1)?.ifEmpty { null } }.toSet()

    /**
     * Why an APK with native code for [apkAbis] is the wrong one for a phone whose CPU types are [phoneAbis] (its
     * preferred one first), or null when it fits. An APK without native code fits any phone. A 64-bit phone that can
     * run the 32-bit copy is still told to use its own, which is faster.
     */
    fun wrongAbi(apkAbis: Set<String>, phoneAbis: List<String>): String? {
        if (apkAbis.isEmpty()) return null
        val wanted = phoneAbis.firstOrNull() ?: return null
        if (wanted in apkAbis) return null
        val isFor = apkAbis.joinToString(" and ") { PHONE_ABIS[it] ?: it }
        val name = "server-$wanted-release.apk"
        return if (wanted in PHONE_ABIS) "That copy is for $isFor. This phone needs the one for ${PHONE_ABIS[wanted]}, $name."
        else "That copy is for $isFor, and this phone's CPU ($wanted) isn't one HomeReel is built for."
    }
}

/**
 * Torrent downloads, off until the admin turns them on. The phone downloads in its own time and keeps only the
 * videos and subtitles, which it moves into the library when they're complete.
 */
@Serializable
data class TorrentSettings(
    val enabled: Boolean = false,
    val onlyWhileCharging: Boolean = true,
    /** Hours of the day (0–23) downloads may run, from [startHour] up to [endHour]; both null means any time. */
    val startHour: Int? = null,
    val endHour: Int? = null,
    /** KB/s; 0 means no limit. */
    val maxDownKBps: Int = 0,
    val maxUpKBps: Int = 50,
    /** Down to a trickle while someone is watching, so the film doesn't stutter. */
    val slowWhileWatching: Boolean = true,
)

@Serializable
data class TorrentItem(
    val id: String,
    val name: String,
    /** Bytes of the files HomeReel keeps (videos and subtitles); 0 until a magnet link's details arrive. */
    val size: Long = 0,
    val done: Long = 0,
    val downRate: Long = 0,
    val upRate: Long = 0,
    val peers: Int = 0,
    val state: String,
    /** Why it's waiting, or what went wrong, in plain words. */
    val note: String? = null,
    /** The videos and subtitles it will add. */
    val files: List<String> = emptyList(),
    val private: Boolean = false,
    val addedAt: Long = 0,
    val finishedAt: Long? = null,
)

@Serializable
data class TorrentList(
    val settings: TorrentSettings,
    val items: List<TorrentItem>,
    /** Why nothing is downloading right now (not charging, outside the hours…), or null. */
    val waiting: String? = null,
)

@Serializable
data class MagnetRequest(val magnet: String, val private: Boolean = false)

object Torrents {
    const val QUEUED = "queued"
    const val DETAILS = "details"
    const val DOWNLOADING = "downloading"
    const val PAUSED = "paused"
    const val MOVING = "moving"
    const val DONE = "done"
    const val ERROR = "error"

    /** A .torrent file is small; anything bigger isn't one. */
    const val MAX_TORRENT_BYTES = 10 * 1024 * 1024

    fun isTorrentFile(name: String) = MediaFiles.extension(name) == "torrent"

    fun isMagnet(text: String) = text.trim().startsWith("magnet:?", ignoreCase = true) && "xt=urn:bt" in text.lowercase()

    /**
     * Whether a file inside a torrent is worth downloading: videos and subtitles only, and not the short "sample"
     * clip many releases include. Everything else (text files, pictures, programs) is never downloaded.
     */
    fun wanted(path: String, size: Long, largestVideo: Long): Boolean {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val ext = MediaFiles.extension(name)
        if (ext in MediaFiles.SUBTITLE_EXTENSIONS) return true
        if (ext !in MediaFiles.VIDEO_EXTENSIONS) return false
        val sample = Regex("(^|[^a-z])sample([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(path)
        return !(sample && size < largestVideo / 4)
    }

    /** Whether [hour] (0–23) falls in the allowed window; a window may run past midnight (22 to 6). */
    fun inHours(startHour: Int?, endHour: Int?, hour: Int): Boolean {
        if (startHour == null || endHour == null || startHour == endHour) return true
        return if (startHour < endHour) hour in startHour until endHour else hour >= startHour || hour < endHour
    }

    /** [name], or "name (2).ext", "name (3).ext"… until it's not in [taken] (compared ignoring case). */
    fun freeName(name: String, taken: Set<String>): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        val base = MediaFiles.baseName(name)
        val ext = MediaFiles.extension(name)
        var n = 2
        while (true) {
            val candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            if (candidate.lowercase() !in lower) return candidate
            n++
        }
    }
}
