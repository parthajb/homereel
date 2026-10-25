package com.parthajb.homereel.server

import android.util.Log
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.AudioTrack
import com.parthajb.homereel.shared.Bitrate
import com.parthajb.homereel.shared.Certifications
import com.parthajb.homereel.shared.KidsSetting
import com.parthajb.homereel.shared.Kind
import com.parthajb.homereel.shared.Markers
import com.parthajb.homereel.shared.MatchState
import com.parthajb.homereel.shared.MediaFiles
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.SeasonMarkers
import com.parthajb.homereel.shared.Show
import com.parthajb.homereel.shared.Subtitle
import com.parthajb.homereel.shared.VideoTrack
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One video file in the library, with what the phone knows about it and what people changed. */
@Serializable
data class Entry(
    val id: String,
    val docId: String,
    val parentDocId: String,
    val fileName: String,
    val size: Long,
    val modified: Long,
    val parsedTitle: String,
    val parsedYear: Int? = null,
    val titleOverride: String? = null,
    val yearOverridden: Boolean = false,
    val yearOverride: Int? = null,
    val durationMs: Long = 0,
    val video: VideoTrack? = null,
    val audio: List<AudioTrack> = emptyList(),
    val probeError: String? = null,
    val posterDocId: String? = null,
    val posterGenerated: Boolean = false,
    val frameFailed: Boolean = false,
    /** Bumped when frames change size; older frames are grabbed again once. */
    val frameVersion: Int = 0,
    val subtitleDocIds: List<String> = emptyList(),
    val subtitles: List<Subtitle> = emptyList(),
    val addedAt: Long = 0,
    val positionMs: Long = 0,
    val watched: Boolean = false,
    val lastPlayedAt: Long = 0,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: Double? = null,
    /** A thumbnail uploaded on the admin page, kept in app storage; it wins over any other poster. */
    val customPoster: Boolean = false,
    val artVersion: Long = 0,
    /** What TMDB (and OMDb) said about this film. Values typed in above always win over it. */
    val tmdb: TmdbInfo? = null,
    val match: String = MatchState.NONE,
    /** When a lookup last failed for a network reason; retried a day later. */
    val matchFailedAt: Long = 0,
    val private: Boolean = false,
    val kind: String = Kind.MOVIE,
    val showId: String? = null,
    val showName: String? = null,
    val showYear: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** The title from the file name, when it has one. */
    val episodeTitle: String? = null,
    /** The episode's details from TMDB, filled in when its show is matched. */
    val episodeInfo: EpisodeInfo? = null,
    /** Each profile's place in it. [positionMs], [watched] and [lastPlayedAt] above are from before profiles. */
    val progress: Map<String, Progress> = emptyMap(),
    /** The admin's choice for kids profiles (films; episodes follow their show). */
    val kids: String = KidsSetting.AUTO,
    /** A lighter copy for the TV exists in app storage (see Optimizer). */
    val optimized: Boolean = false,
    val optimizeError: String? = null,
) {
    val isEpisode: Boolean get() = kind == Kind.EPISODE

    fun progressFor(pid: String): Progress =
        progress[pid] ?: if (pid == Profiles.MAIN) Progress(positionMs, watched, lastPlayedAt) else Progress()

    val kidsOk: Boolean get() = Certifications.kidsOk(kids, tmdb?.certification, private)
    /** The title and year to search with: whatever the user typed, else the file name. */
    val searchTitle: String get() = titleOverride ?: parsedTitle
    val searchYear: Int? get() = if (yearOverridden) yearOverride else parsedYear

    /** The folder the file is in, from a document id like "primary:Movies/Home videos". */
    val folderName: String? get() = parentDocId.substringAfter(':', "").trimEnd('/').substringAfterLast('/').ifEmpty { null }

    /** As [viewer] sees it: their progress and My List. Null is the first profile, for the admin page. */
    /** [show] is the episode's show, for its title, kids setting and skip markers. */
    fun toMovie(show: ShowRecord? = null, viewer: Viewer? = null): Movie {
        val p = progressFor(viewer?.pid ?: Profiles.MAIN)
        return if (isEpisode) toEpisode(show, p) else toFilm(p, viewer)
    }

    private fun toFilm(p: Progress, viewer: Viewer?) = Movie(
        id = id,
        title = titleOverride ?: tmdb?.title ?: parsedTitle,
        year = if (yearOverridden) yearOverride else tmdb?.year ?: parsedYear,
        fileName = fileName,
        container = MediaFiles.extension(fileName),
        size = size,
        durationMs = durationMs,
        video = video,
        audio = audio,
        subtitles = subtitles,
        addedAt = addedAt,
        positionMs = p.positionMs,
        watched = p.watched,
        lastPlayedAt = p.lastPlayedAt,
        hasPoster = customPoster || tmdb?.poster == true || posterDocId != null || posterGenerated,
        hasBackdrop = tmdb?.backdrop == true || posterGenerated || customPoster || posterDocId != null,
        customPoster = customPoster,
        artVersion = artVersion,
        probeError = probeError,
        description = description ?: tmdb?.overview,
        genres = genres.ifEmpty { tmdb?.genres ?: emptyList() },
        imdbRating = imdbRating ?: tmdb?.imdbRating ?: tmdb?.tmdbRating,
        ratingSource = when {
            imdbRating != null || tmdb?.imdbRating != null -> "IMDb"
            tmdb?.tmdbRating != null -> "TMDB"
            else -> null
        },
        tagline = tmdb?.tagline,
        cast = tmdb?.cast ?: emptyList(),
        director = tmdb?.director,
        certification = tmdb?.certification,
        collection = tmdb?.collection,
        tmdbId = tmdb?.tmdbId,
        imdbId = tmdb?.imdbId,
        match = match,
        locked = listOfNotNull(
            "title".takeIf { titleOverride != null },
            "year".takeIf { yearOverridden },
            "description".takeIf { description != null },
            "genres".takeIf { genres.isNotEmpty() },
            "rating".takeIf { imdbRating != null },
            "poster".takeIf { customPoster },
        ),
        private = private,
        inMyList = viewer?.myList?.contains("m:$id") == true,
        kids = kids,
        kidsOk = kidsOk,
        bitrateMbps = Bitrate.mbps(size, durationMs),
        optimized = optimized,
        optimizeError = optimizeError,
    )

    /** An episode: its title from TMDB, the file name, or just its number; pictures are TMDB's still or a frame. */
    private fun toEpisode(show: ShowRecord?, p: Progress): Movie {
        val marks = Markers.forEpisode(show?.markers?.firstOrNull { it.season == season }, durationMs)
        return Movie(
        id = id,
        title = episodeInfo?.title ?: episodeTitle ?: "Episode $episode",
        year = episodeInfo?.year,
        fileName = fileName,
        container = MediaFiles.extension(fileName),
        size = size,
        durationMs = durationMs,
        video = video,
        audio = audio,
        subtitles = subtitles,
        addedAt = addedAt,
        positionMs = p.positionMs,
        watched = p.watched,
        lastPlayedAt = p.lastPlayedAt,
        hasPoster = episodeInfo?.still == true || posterGenerated,
        hasBackdrop = episodeInfo?.still == true || posterGenerated,
        artVersion = artVersion,
        probeError = probeError,
        description = episodeInfo?.overview,
        kind = Kind.EPISODE,
        showId = showId,
        showTitle = show?.title ?: showName,
        season = season,
        episode = episode,
        match = MatchState.SKIPPED,
        kidsOk = show?.kidsOk == true,
        bitrateMbps = Bitrate.mbps(size, durationMs),
        optimized = optimized,
        optimizeError = optimizeError,
        introStartMs = marks.introStartMs,
        introEndMs = marks.introEndMs,
        creditsStartMs = marks.creditsStartMs,
    )
    }
}

/** Where one profile is in one video. */
@Serializable
data class Progress(val positionMs: Long = 0, val watched: Boolean = false, val lastPlayedAt: Long = 0)

/** An episode's details from TMDB's season listing. */
@Serializable
data class EpisodeInfo(
    val title: String? = null,
    val overview: String? = null,
    val year: Int? = null,
    /** Whether TMDB's still picture was downloaded. */
    val still: Boolean = false,
)

/** A series in the library: built from its episodes, with what TMDB said and what people typed in. */
@Serializable
data class ShowRecord(
    val id: String,
    val parsedTitle: String,
    val parsedYear: Int? = null,
    val titleOverride: String? = null,
    val yearOverridden: Boolean = false,
    val yearOverride: Int? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val imdbRating: Double? = null,
    val customPoster: Boolean = false,
    val artVersion: Long = 0,
    val tmdb: TmdbShowInfo? = null,
    val match: String = MatchState.NONE,
    val matchFailedAt: Long = 0,
    val kids: String = KidsSetting.AUTO,
    /** Per season: where the intro is and how long the credits run. */
    val markers: List<SeasonMarkers> = emptyList(),
) {
    val title: String get() = titleOverride ?: tmdb?.title ?: parsedTitle
    val kidsOk: Boolean get() = Certifications.kidsOk(kids, tmdb?.certification, private = false)
    val searchTitle: String get() = titleOverride ?: parsedTitle
    val searchYear: Int? get() = if (yearOverridden) yearOverride else parsedYear

    /** [episodes] in order; [frameFallback] says whether one of them has a frame to stand in for missing pictures. */
    fun toShow(episodes: List<Entry>, frameFallback: Boolean, viewer: Viewer? = null): Show {
        val ordered = episodes.sortedWith(EPISODE_ORDER)
        val pid = viewer?.pid ?: Profiles.MAIN
        return Show(
            id = id,
            title = title,
            year = if (yearOverridden) yearOverride else tmdb?.year ?: parsedYear,
            endYear = tmdb?.endYear,
            description = description ?: tmdb?.overview,
            genres = genres.ifEmpty { tmdb?.genres ?: emptyList() },
            imdbRating = imdbRating ?: tmdb?.imdbRating ?: tmdb?.tmdbRating,
            ratingSource = when {
                imdbRating != null || tmdb?.imdbRating != null -> "IMDb"
                tmdb?.tmdbRating != null -> "TMDB"
                else -> null
            },
            certification = tmdb?.certification,
            cast = tmdb?.cast ?: emptyList(),
            creators = tmdb?.creators,
            seasons = ordered.mapNotNull { it.season }.distinct(),
            episodeCount = ordered.size,
            addedAt = ordered.maxOfOrNull { it.addedAt } ?: 0,
            lastPlayedAt = ordered.maxOfOrNull { it.progressFor(pid).lastPlayedAt } ?: 0,
            upNext = upNext(ordered, pid)?.toMovie(this, viewer),
            hasPoster = customPoster || tmdb?.poster == true || frameFallback,
            hasBackdrop = tmdb?.backdrop == true || frameFallback || customPoster,
            customPoster = customPoster,
            artVersion = artVersion,
            tmdbId = tmdb?.tmdbId,
            imdbId = tmdb?.imdbId,
            match = match,
            locked = listOfNotNull(
                "title".takeIf { titleOverride != null },
                "year".takeIf { yearOverridden },
                "description".takeIf { description != null },
                "genres".takeIf { genres.isNotEmpty() },
                "rating".takeIf { imdbRating != null },
                "poster".takeIf { customPoster },
            ),
            inMyList = viewer?.myList?.contains("s:$id") == true,
            kids = kids,
            kidsOk = kidsOk,
            markers = markers,
        )
    }

    companion object {
        val EPISODE_ORDER: Comparator<Entry> = compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.fileName })

        /**
         * What to carry on with: the most recently played episode if it isn't finished, else the next one after
         * it. Null for a show nobody has started, or one watched to the end.
         */
        fun upNext(ordered: List<Entry>, pid: String): Entry? {
            val last = ordered.filter { it.progressFor(pid).lastPlayedAt > 0 }.maxByOrNull { it.progressFor(pid).lastPlayedAt }
                ?: return null
            val p = last.progressFor(pid)
            if (!p.watched && p.positionMs > 0) return last
            return ordered.drop(ordered.indexOf(last) + 1).firstOrNull { !it.progressFor(pid).watched }
        }
    }
}

/** A series' details from TMDB, and its IMDb rating from OMDb when there's a key for it. */
@Serializable
data class TmdbShowInfo(
    val tmdbId: Int,
    val imdbId: String? = null,
    val title: String,
    val year: Int? = null,
    val endYear: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val creators: String? = null,
    val certification: String? = null,
    val tmdbRating: Double? = null,
    val imdbRating: Double? = null,
    val poster: Boolean = false,
    val backdrop: Boolean = false,
    val fetchedAt: Long = 0,
)

/** A film's details from TMDB, and its IMDb rating from OMDb when there's a key for it. */
@Serializable
data class TmdbInfo(
    val tmdbId: Int,
    val imdbId: String? = null,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val certification: String? = null,
    val collection: String? = null,
    val tmdbRating: Double? = null,
    val imdbRating: Double? = null,
    /** Whether the poster and backdrop were downloaded to app storage. */
    val poster: Boolean = false,
    val backdrop: Boolean = false,
    val fetchedAt: Long = 0,
)

@Serializable
private data class CatalogFile(
    val treeUri: String? = null,
    val entries: List<Entry> = emptyList(),
    val shows: List<ShowRecord> = emptyList(),
)

/** The library index, kept in memory and saved to app storage a few seconds after each change. */
class Catalog(private val file: File) {
    private val entries = LinkedHashMap<String, Entry>()
    private val shows = LinkedHashMap<String, ShowRecord>()
    private var treeUri: String? = null
    private val saver = Executors.newSingleThreadScheduledExecutor()
    private var saveQueued = false

    init {
        runCatching {
            if (file.exists()) {
                val data = ApiJson.decodeFromString(CatalogFile.serializer(), file.readText())
                treeUri = data.treeUri
                data.entries.forEach { entries[it.id] = it }
                data.shows.forEach { shows[it.id] = it }
            }
        }.onFailure { Log.w(TAG, "Catalog unreadable, starting empty", it) }
    }

    @Synchronized fun all(): List<Entry> = entries.values.toList()

    @Synchronized fun get(id: String): Entry? = entries[id]

    @Synchronized fun size(): Int = entries.size

    /** The entries for [tree], or none if the catalog was built for another folder. */
    @Synchronized fun forTree(tree: String): Map<String, Entry> = if (tree == treeUri) entries.toMap() else emptyMap()

    /**
     * Swaps in a new scan. Shows follow their episodes: a new show gets a record, a show whose last episode went
     * away loses its record, and everything typed in or looked up for a show that's still there is kept.
     */
    @Synchronized fun replaceAll(tree: String, list: List<Entry>) {
        treeUri = tree
        entries.clear()
        list.forEach { entries[it.id] = it }
        val byShow = list.filter { it.isEpisode && it.showId != null }.groupBy { it.showId!! }
        shows.keys.retainAll(byShow.keys)
        for ((id, episodes) in byShow) {
            if (id !in shows) {
                val first = episodes.first()
                shows[id] = ShowRecord(id, first.showName ?: "Unknown show", first.showYear)
            }
        }
        saveSoon()
    }

    @Synchronized fun shows(): List<ShowRecord> = shows.values.toList()

    @Synchronized fun show(id: String): ShowRecord? = shows[id]

    @Synchronized fun episodes(showId: String): List<Entry> =
        entries.values.filter { it.showId == showId }.sortedWith(ShowRecord.EPISODE_ORDER)

    @Synchronized fun updateShow(id: String, change: (ShowRecord) -> ShowRecord): ShowRecord? {
        val old = shows[id] ?: return null
        return change(old).also {
            shows[id] = it
            saveSoon()
        }
    }

    @Synchronized fun put(entry: Entry) {
        entries[entry.id] = entry
        saveSoon()
    }

    @Synchronized fun remove(id: String): Entry? = entries.remove(id).also { saveSoon() }

    @Synchronized fun update(id: String, change: (Entry) -> Entry): Entry? {
        val old = entries[id] ?: return null
        return change(old).also {
            entries[id] = it
            saveSoon()
        }
    }

    @Synchronized private fun saveSoon() {
        if (saveQueued) return
        saveQueued = true
        saver.schedule({ save() }, 3, TimeUnit.SECONDS)
    }

    private fun save() {
        val text = synchronized(this) {
            saveQueued = false
            ApiJson.encodeToString(
                CatalogFile.serializer(),
                CatalogFile(treeUri, entries.values.toList(), shows.values.toList()),
            )
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) Log.w(TAG, "Couldn't save the catalog")
    }

    private companion object {
        const val TAG = "HomeReel"
    }
}
