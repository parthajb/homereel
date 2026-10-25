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
    /** On the asking profile's My List. */
    val inMyList: Boolean = false,
    /** The admin's choice for kids profiles: [KidsSetting]. */
    val kids: String = KidsSetting.AUTO,
    /** Whether kids profiles see it, after applying [kids] to the age rating. */
    val kidsOk: Boolean = false,
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
)

/** A null field is left as it is; an empty key clears it. */
@Serializable
data class MetadataSettingsUpdate(
    val tmdbKey: String? = null,
    val omdbKey: String? = null,
    val autoMatch: Boolean? = null,
    val tmdbBaseUrl: String? = null,
    val tmdbImageBaseUrl: String? = null,
    val omdbBaseUrl: String? = null,
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
)

@Serializable
data class ErrorBody(val error: String)
