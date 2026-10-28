package com.parthajb.homereel.shared

import java.security.MessageDigest

object MediaFiles {
    val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov")
    val SUBTITLE_EXTENSIONS = setOf("srt", "vtt")
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png")
    val UPLOAD_EXTENSIONS = VIDEO_EXTENSIONS + SUBTITLE_EXTENSIONS + IMAGE_EXTENSIONS

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun baseName(name: String): String = name.substringBeforeLast('.', name)

    fun isVideo(name: String) = extension(name) in VIDEO_EXTENSIONS

    fun videoMime(name: String): String = when (extension(name)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    fun subtitleMime(name: String): String =
        if (extension(name) == "vtt") "text/vtt" else "application/x-subrip"

    /** A file name safe to create in the library folder, or null if nothing usable is left. */
    fun safeUploadName(raw: String): String? {
        val name = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_")
            .trim().trimStart('.')
            .take(200)
        if (name.isEmpty() || extension(name) !in UPLOAD_EXTENSIONS) return null
        return name
    }
}

object MovieIds {
    /** A stable id from the file's document URI: the same file keeps its id, and so its resume point, across rescans. */
    fun of(documentUri: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(documentUri.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

object TitleParser {
    data class Parsed(val title: String, val year: Int?)

    private val YEAR = Regex("(?<![0-9])(19[0-9]{2}|20[0-9]{2})(?![0-9])")
    private val TAG = Regex(
        "(?i)(?<![a-z0-9])(2160p|1080p|720p|576p|480p|4k|uhd|hdr10\\+?|hdr|dv|imax|bluray|blu-ray|brrip|bdrip|" +
            "webrip|web-dl|webdl|web|hdrip|hdtv|dvdrip|dvd|x264|x265|h\\.?264|h\\.?265|hevc|avc|aac|ac3|dts|" +
            "ddp?5\\.?1|10bit|remux|proper|repack|extended|unrated|remastered|yify|yts|rarbg)(?![a-z0-9])",
    )

    /** The text before the first release tag ("1080p", "WEBRip"…), which may be empty. */
    fun beforeTags(text: String): String = TAG.find(text)?.let { text.substring(0, it.range.first) } ?: text

    fun parse(fileName: String): Parsed {
        val base = MediaFiles.baseName(fileName).replace('_', ' ').replace('.', ' ')
            .replace(Regex("\\s+"), " ").trim()
        // The last year wins, and never one at the very start: "2012 (2009)", "Blade Runner 2049 2017".
        val year = YEAR.findAll(base).filter { it.range.first > 0 }.lastOrNull()
        val tag = TAG.find(base)?.takeIf { it.range.first > 0 }
        val cut = listOfNotNull(year?.range?.first, tag?.range?.first).minOrNull() ?: base.length
        val title = base.substring(0, cut)
            .replace(Regex("[\\s\\-\\[\\](){}]+$"), "")
            .trim()
            .ifEmpty { base }
        return Parsed(title, year?.value?.toInt())
    }
}

object Genres {
    /** Suggested on the admin page; typing one of these in any case gets this spelling, so rows don't split. */
    val COMMON = listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family",
        "Fantasy", "History", "Horror", "Music", "Musical", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller",
        "War", "Western", "Home video",
    )

    const val MAX = 5

    fun normalize(raw: List<String>): List<String> = raw
        .map { it.trim().replace(Regex("\\s+"), " ").take(30) }
        .filter { it.isNotEmpty() }
        .map { g -> COMMON.firstOrNull { it.equals(g, ignoreCase = true) } ?: g.replaceFirstChar { it.uppercaseChar() } }
        .distinctBy { it.lowercase() }
        .take(MAX)
}

object Ratings {
    /** An IMDb-style rating: 0.0 to 10.0, one decimal. Null for anything else. */
    fun normalize(value: Double?): Double? {
        if (value == null || value.isNaN() || value < 0 || value > 10) return null
        return Math.round(value * 10) / 10.0
    }
}

/** Deciding whether a search result is the film a file is, before anything is filled in without asking. */
object Matching {
    const val AUTO_THRESHOLD = 0.85

    /** Without a year in the file name, only a near-exact title is trusted. */
    const val AUTO_THRESHOLD_NO_YEAR = 0.97

    private val CAMERA_NAME = Regex(
        "^(DJI|VID|IMG|PXL|MVI|GOPR|GX\\d{2}|GH\\d{2}|DSC|DSCF|MOV|C\\d{4}|Screen[ _-]?Recording|WhatsApp Video|signal-)[ _-]?\\d",
        RegexOption.IGNORE_CASE,
    )

    fun normalise(title: String): String {
        val plain = java.text.Normalizer.normalize(title.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return plain.replace(Regex("^(the|a|an) "), "").replace(Regex("\\s+"), " ")
    }

    /**
     * How alike two titles are, 0 to 1: the lower of their Jaro-Winkler similarity and how many of their words
     * match. The word check stops a shared start from carrying the score ("Night Train" isn't "Night Shift", and
     * "Drishyam" isn't "Drishyam 2").
     */
    fun similarity(a: String, b: String): Double {
        val s = normalise(a)
        val t = normalise(b)
        if (s == t) return 1.0
        return minOf(jaroWinkler(s, t), wordOverlap(s.split(' '), t.split(' ')))
    }

    /** Dice overlap of the two titles' words, counting near-identical words (a typo, a plural) as the same. */
    private fun wordOverlap(a: List<String>, b: List<String>): Double {
        val unused = b.toMutableList()
        var matched = 0
        for (word in a) {
            val hit = unused.firstOrNull { it == word || jaroWinkler(it, word) >= 0.92 } ?: continue
            unused.remove(hit)
            matched++
        }
        return 2.0 * matched / (a.size + b.size)
    }

    private fun jaroWinkler(s: String, t: String): Double {
        if (s == t) return 1.0
        if (s.isEmpty() || t.isEmpty()) return 0.0
        val window = (maxOf(s.length, t.length) / 2 - 1).coerceAtLeast(0)
        val sMatched = BooleanArray(s.length)
        val tMatched = BooleanArray(t.length)
        var matches = 0
        for (i in s.indices) {
            val from = maxOf(0, i - window)
            val to = minOf(t.length - 1, i + window)
            for (j in from..to) {
                if (!tMatched[j] && s[i] == t[j]) {
                    sMatched[i] = true
                    tMatched[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in s.indices) {
            if (!sMatched[i]) continue
            while (!tMatched[k]) k++
            if (s[i] != t[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / s.length + m / t.length + (m - transpositions / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < minOf(4, s.length, t.length) && s[prefix] == t[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }

    /**
     * How sure we are that a search result is this file: the better of the title and original-title similarity,
     * or 0 when the file names a year and the result is more than a year off.
     */
    fun score(fileTitle: String, fileYear: Int?, title: String, originalTitle: String?, year: Int?): Double {
        if (fileYear != null && (year == null || kotlin.math.abs(year - fileYear) > 1)) return 0.0
        return maxOf(similarity(fileTitle, title), originalTitle?.let { similarity(fileTitle, it) } ?: 0.0)
    }

    fun confident(score: Double, fileYear: Int?): Boolean =
        score >= if (fileYear != null) AUTO_THRESHOLD else AUTO_THRESHOLD_NO_YEAR

    /** Camera and phone clips, and anything in a "Home videos" folder, are never sent for lookup. */
    fun isHomeVideo(fileName: String, folderName: String?): Boolean =
        CAMERA_NAME.containsMatchIn(fileName) || folderName?.contains("home video", ignoreCase = true) == true
}

/** Home videos: their own section, newest day first, with each clip's date from its name when it has one. */
object HomeVideos {
    // 20261012 or 2026-10-12, with the time after it when there is one (VID_20261012_101530, PXL_20261012_101530123).
    private val STAMP = Regex("(?<!\\d)(20\\d{2})-?([01]\\d)-?([0-3]\\d)(?:[_ T-]?([0-2]\\d)[.-]?([0-5]\\d)[.-]?([0-5]\\d))?")

    /** When the clip was recorded: the date and time in its name, else when the file last changed. */
    fun recordedAt(fileName: String, modified: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val m = STAMP.find(fileName) ?: return modified
        val (y, mo, d, h, mi, s) = m.destructured
        return runCatching {
            java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.ifEmpty { "0" }.toInt(), mi.ifEmpty { "0" }.toInt(), s.ifEmpty { "0" }.toInt())
                .atZone(zone).toInstant().toEpochMilli()
        }.getOrDefault(modified)
    }

    /**
     * A camera or phone clip's name reads like its date ("Video, 12 October 2026 at 10:15") rather than its file name;
     * null for any other video, which keeps its own title.
     */
    fun title(fileName: String, recordedAt: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
        if (!Matching.isHomeVideo(fileName, null)) return null
        val at = java.time.Instant.ofEpochMilli(recordedAt).atZone(zone)
        return "Video, " + at.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm", java.util.Locale.ENGLISH))
    }

    /** Clips grouped by the day they were recorded, the newest day first, each day in the order it happened. */
    fun byDay(clips: List<Movie>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Pair<java.time.LocalDate, List<Movie>>> =
        clips.groupBy { java.time.Instant.ofEpochMilli(it.recordedAt ?: it.addedAt).atZone(zone).toLocalDate() }
            .map { (day, list) -> day to list.sortedBy { it.recordedAt ?: it.addedAt } }
            .sortedByDescending { it.first }
}

/** Reads "Show S01E02", "Show 1x02" and "Show/Season 1/02 - Title" style names. */
object EpisodeParser {
    data class Episode(val show: String, val showYear: Int?, val season: Int, val episode: Int, val title: String?)

    private val SXE = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]?e(\\d{1,3})(?:[ ._-]?e\\d{1,3})?(?![0-9])")
    private val NXN = Regex("(?i)(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?![0-9])")
    private val SEASON_DIR = Regex("(?i)^(?:season|series|s)[ ._-]*(\\d{1,2})$")
    private val EP_ONLY = Regex("(?i)^(?:e|ep|episode)[ ._-]*(\\d{1,3})(?![0-9])|^(\\d{1,3})(?=[ ._-]|$)")

    /**
     * [folders] are the folder names from the library's top down to the file's own folder. Returns null for
     * anything that doesn't look like an episode, which is then treated as a film.
     */
    fun parse(fileName: String, folders: List<String>): Episode? {
        val base = MediaFiles.baseName(fileName).replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " ").trim()
        val match = SXE.find(base) ?: NXN.find(base)
        val seasonDir = folders.lastOrNull()?.let { SEASON_DIR.find(it.trim()) }
        val (season, episode, before, after) = when {
            match != null -> listOf(
                match.groupValues[1].toInt(), match.groupValues[2].toInt(),
                base.substring(0, match.range.first), base.substring(match.range.last + 1),
            )
            seasonDir != null -> {
                val ep = EP_ONLY.find(base) ?: return null
                val number = (ep.groupValues[1].ifEmpty { ep.groupValues[2] }).toInt()
                listOf(seasonDir.groupValues[1].toInt(), number, "", base.substring(ep.range.last + 1))
            }
            else -> return null
        }
        // The show's name: before the SxxEyy in the file name, else the folder above "Season N", else the folder.
        val fromFile = (before as String).trim(' ', '-', '.')
        val showFolder = if (seasonDir != null) folders.dropLast(1).lastOrNull() else folders.lastOrNull()
        val showSource = fromFile.ifEmpty { showFolder ?: return null }
        val parsed = TitleParser.parse("$showSource.x")
        val title = TitleParser.beforeTags((after as String).trim()).trim(' ', '-', '.')
            .takeIf { it.isNotBlank() && !it.equals(parsed.title, ignoreCase = true) }
        return Episode(capitalise(parsed.title), parsed.year, season as Int, episode as Int, title)
    }

    /** "panchayat" from a lowercase file name reads better as "Panchayat"; names with any capitals are kept. */
    private fun capitalise(name: String): String =
        if (name != name.lowercase()) name else name.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }

    /** A show's stable id, from its normalised name, so episodes in different folders still join up. */
    fun showId(show: String, year: Int?): String = MovieIds.of("show:" + Matching.normalise(show) + ":" + (year ?: ""))
}

object Certifications {
    /** Indian, American and TV ratings fine for children, with spaces, slashes and dashes ignored. */
    private val KIDS = setOf("U", "UA7+", "G", "PG", "TVY", "TVY7", "TVG", "TVPG", "0", "6", "7", "ALL")

    /** No rating means no: home videos and unmatched files stay off kids profiles unless the admin says yes. */
    fun kidsSafe(certification: String?): Boolean =
        certification != null && certification.uppercase().replace(Regex("[\\s/\\-]"), "") in KIDS

    fun kidsOk(setting: String, certification: String?, private: Boolean): Boolean = when {
        private -> false
        setting == KidsSetting.YES -> true
        setting == KidsSetting.NO -> false
        else -> kidsSafe(certification)
    }
}

/** "Because you watched…": what a title has in common with another, weighted toward people over genres. */
object Recommend {
    data class Signals(val key: String, val genres: List<String>, val people: List<String>, val director: String?)

    fun score(seed: Signals, other: Signals): Int {
        if (seed.key == other.key) return 0
        val genres = other.genres.count { it in seed.genres }
        val people = other.people.count { it in seed.people }
        val director = if (seed.director != null && seed.director == other.director) 1 else 0
        // A shared genre alone is weak; two, or a shared actor or director, makes a real "because".
        return genres + people * 3 + director * 4
    }

    fun similar(seed: Signals, all: List<Signals>, limit: Int = 15): List<String> =
        all.map { it to score(seed, it) }.filter { it.second >= 2 }
            .sortedByDescending { it.second }.take(limit).map { it.first.key }
}

/** Matching what's typed in search against a title's words: every typed word must start some word of it. */
/** Film series from TMDB ("Harry Potter Collection"), for a film's own row and the Collections tiles. */
object Collections {
    /** Every collection with at least two films here, the biggest first, each in release order. */
    fun of(films: List<Movie>): List<Pair<String, List<Movie>>> =
        films.filter { !it.collection.isNullOrBlank() }
            .groupBy { it.collection!! }
            .filter { it.value.size >= 2 }
            .map { (name, list) -> name to inOrder(list) }
            .sortedWith(compareByDescending<Pair<String, List<Movie>>> { it.second.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.first })

    /** The other films in [film]'s collection, in release order; empty when it has none here. */
    fun sameAs(film: Movie, films: List<Movie>): List<Movie> {
        val name = film.collection?.takeIf { it.isNotBlank() } ?: return emptyList()
        return inOrder(films.filter { it.collection == name && it.id != film.id })
    }

    /** By year, those without one last, then by title. */
    private fun inOrder(list: List<Movie>) = list.sortedWith(compareBy<Movie>({ it.year ?: Int.MAX_VALUE }, { it.title }))
}

object Search {
    fun words(text: String?): List<String> =
        if (text == null) emptyList() else Matching.normalise(text).split(' ').filter { it.isNotEmpty() }

    /** 0 for no match; higher for a title match than a match on cast or genre. */
    fun score(query: String, title: String, other: List<String?>): Int {
        val q = words(query)
        if (q.isEmpty()) return 0
        val titleWords = words(title)
        val otherWords = other.flatMap { words(it) }
        var score = 0
        for (w in q) {
            score += when {
                titleWords.any { it.startsWith(w) } -> 3
                otherWords.any { it.startsWith(w) } -> 1
                else -> return 0
            }
        }
        if (Matching.normalise(title).startsWith(q.joinToString(" "))) score += 5
        return score
    }
}

/**
 * The TV's own search and voice search ("Play Dangal on HomeReel"): titles matching what was said, best first, from
 * the library the TV last loaded. Films and shows score as HomeReel's own search does.
 */
object VoiceSearch {
    /** A film or a show that matched, with its score. */
    data class Hit(val key: String, val title: String, val year: Int?, val score: Int, val movie: Movie? = null, val show: Show? = null)

    fun matches(query: String, movies: List<Movie>, shows: List<Show>, limit: Int = 10): List<Hit> {
        val q = query.trim().lowercase().removePrefix("play ").removeSuffix(" on homereel").trim()
        // The exact title beats a longer one that starts the same ("Night Train" over "Night Train 2").
        fun exact(title: String) = if (Matching.normalise(title) == Matching.normalise(q)) 2 else 0
        val films = movies.filter { !it.private && it.kind != Kind.EPISODE }.map {
            val score = Search.score(q, it.title, listOf(it.director, it.year?.toString(), it.collection) + it.cast + it.genres)
            Hit("m:${it.id}", it.title, it.year, if (score > 0) score + exact(it.title) else 0, movie = it)
        }
        val series = shows.map {
            val score = Search.score(q, it.title, listOf(it.creators, it.year?.toString()) + it.cast + it.genres)
            Hit("s:${it.id}", it.title, it.year, if (score > 0) score + exact(it.title) else 0, show = it)
        }
        return (films + series).filter { it.score > 0 }
            .sortedWith(compareByDescending<Hit> { it.score }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            .take(limit)
    }

    /**
     * What to play for "Play …": the clear winner, when its title matched (not only cast or genre) and nothing else
     * scored as high. Otherwise null, and the TV shows the matches instead.
     */
    fun toPlay(query: String, movies: List<Movie>, shows: List<Show>): Hit? {
        val hits = matches(query, movies, shows, 2)
        val best = hits.firstOrNull() ?: return null
        val titleMatch = Search.score(query.trim().lowercase().removePrefix("play ").removeSuffix(" on homereel"), best.title, emptyList()) > 0
        return best.takeIf { titleMatch && (hits.size < 2 || hits[1].score < it.score) }
    }
}

object Bitrate {
    /** Above this, a stream can outrun an old phone's Wi-Fi (a 90 Mbps drone clip stuttered; 30 Mbps played fine). */
    const val HEAVY_MBPS = 60.0

    fun mbps(sizeBytes: Long, durationMs: Long): Double? =
        if (sizeBytes <= 0 || durationMs <= 0) null else Math.round(sizeBytes * 8.0 / durationMs / 100.0) / 10.0

    fun heavy(mbps: Double?) = mbps != null && mbps > HEAVY_MBPS

    /** The video bitrate for a lighter copy: plenty for the size, well under what Wi-Fi carries smoothly. */
    fun target(width: Int, height: Int): Int {
        val lines = minOf(width, height).takeIf { it > 0 } ?: 1080
        return when {
            lines >= 2000 -> 24_000_000
            lines >= 1400 -> 14_000_000
            lines >= 1000 -> 10_000_000
            else -> 6_000_000
        }
    }
}

object Markers {
    data class Times(val introStartMs: Long?, val introEndMs: Long?, val creditsStartMs: Long?)

    /** Applies a season's markers to one episode; nonsense (an intro ending before it starts) is dropped. */
    fun forEpisode(m: SeasonMarkers?, durationMs: Long): Times {
        if (m == null) return Times(null, null, null)
        val introOk = m.introStartMs != null && m.introEndMs != null && m.introEndMs > m.introStartMs &&
            (durationMs <= 0 || m.introEndMs < durationMs)
        val credits = m.creditsMs?.takeIf { it > 0 && durationMs > it }?.let { durationMs - it }
        return Times(if (introOk) m.introStartMs else null, if (introOk) m.introEndMs else null, credits)
    }

    /** "1:05" or "1:02:03" to milliseconds; null for anything else. */
    fun parse(text: String?): Long? {
        val parts = text?.trim()?.takeIf { it.isNotEmpty() }?.split(':') ?: return null
        if (parts.size !in 2..3 || parts.any { it.toIntOrNull() == null || it.toInt() < 0 }) return null
        val n = parts.map { it.toLong() }
        if (n.drop(1).any { it > 59 }) return null
        return n.fold(0L) { acc, v -> acc * 60 + v } * 1000
    }
}
