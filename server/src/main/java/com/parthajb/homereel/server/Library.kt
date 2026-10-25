package com.parthajb.homereel.server

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import com.parthajb.homereel.shared.AdminStatus
import com.parthajb.homereel.shared.Bitrate
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.Genres
import com.parthajb.homereel.shared.KidsSetting
import com.parthajb.homereel.shared.MatchState
import com.parthajb.homereel.shared.Matching
import com.parthajb.homereel.shared.MetadataSettings
import com.parthajb.homereel.shared.MetadataSettingsUpdate
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.MovieEdit
import com.parthajb.homereel.shared.PlayabilityItem
import com.parthajb.homereel.shared.PlayabilityReport
import com.parthajb.homereel.shared.Playability
import com.parthajb.homereel.shared.ProgressUpdate
import com.parthajb.homereel.shared.PrivateState
import com.parthajb.homereel.shared.PrivateUpdate
import com.parthajb.homereel.shared.Ratings
import com.parthajb.homereel.shared.SeasonMarkers
import com.parthajb.homereel.shared.Show
import com.parthajb.homereel.shared.ShowDetail
import com.parthajb.homereel.shared.TmdbResult
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/** The library as the HTTP server and the phone screen see it: the catalog, scans, uploads and deletes. */
class Library(context: Context, private val prefs: Prefs, private val locks: ActivityLocks, private val events: EventLog) {
    private val cr = context.contentResolver
    private val posterDir = File(context.filesDir, "posters").apply { mkdirs() }
    private val catalog = Catalog(File(context.filesDir, "catalog.json"))
    private val scanner = Scanner(context, posterDir)
    val uploads = Uploads(cr, prefs)
    private val worker = Executors.newSingleThreadExecutor()
    private var scanQueued = false
    val metadata = Metadata(prefs)
    val optimizer = Optimizer(
        context,
        File(context.filesDir, "optimized"),
        object : Optimizer.Callbacks {
            override fun source(id: String): Pair<Uri, Entry>? {
                val e = catalog.get(id)?.takeIf { !it.offline } ?: return null
                val tree = treeOf(e) ?: return null
                return Saf.docUri(tree, e.docId) to e
            }

            override fun finished(id: String, ok: Boolean, error: String?) {
                val e = catalog.update(id) { it.copy(optimized = ok, optimizeError = error) }
                if (ok) events.info("Made a lighter copy of ${e?.fileName}") else events.warn("No lighter copy of ${e?.fileName}: $error")
            }

            override fun busy(on: Boolean) {
                if (on) locks.begin() else locks.end()
            }
        },
    )
    private val lookups = Executors.newSingleThreadExecutor()
    private val lookupQueue = LinkedHashSet<String>()

    @Volatile var metadataError: String? = null
        private set

    @Volatile var scanning = false
        private set

    @Volatile var scanError: String? = null
        private set

    @Volatile private var report: PlayabilityReport? =
        prefs.playabilityJson?.let { runCatching { ApiJson.decodeFromString(PlayabilityReport.serializer(), it) }.getOrNull() }

    val tree: Uri? get() = prefs.treeUri

    /** The folder an entry's file is in: its extra folder, or the main one. */
    private fun treeOf(e: Entry): Uri? = e.treeUri?.let(Uri::parse) ?: tree

    @Volatile var lastScanAt = 0L
        private set

    @Volatile var lastScanMs = 0L
        private set

    /** The main folder and any extra ones, with whether they could be read at the last scan. */
    @Volatile var unavailable: Set<String> = emptySet()
        private set

    fun folders(): List<com.parthajb.homereel.shared.LibraryFolder> {
        val all = catalog.all()
        return listOfNotNull(tree?.let { it to true }) .plus(prefs.extraTrees.map { it to false }).map { (uri, main) ->
            val key = uri.toString()
            val (free, total) = runCatching { Saf.space(uri) }.getOrNull() ?: (null to null)
            com.parthajb.homereel.shared.LibraryFolder(
                uri = key, name = Saf.folderName(uri), main = main, available = key !in unavailable,
                freeBytes = free, totalBytes = total,
                movieCount = all.count { (it.treeUri ?: tree.toString()) == key },
            )
        }
    }

    fun addFolder(uri: Uri) {
        if (uri == tree || uri in prefs.extraTrees) return
        prefs.extraTrees = prefs.extraTrees + uri
        events.info("Added the folder ${Saf.folderName(uri)}")
        requestScan()
    }

    fun removeFolder(uri: Uri) {
        prefs.extraTrees = prefs.extraTrees - uri
        events.info("Removed the folder ${Saf.folderName(uri)}")
        requestScan()
    }

    fun count(): Int = catalog.size()

    fun showCount(): Int = catalog.shows().size

    /** Films (not episodes). Private ones only when [withPrivate]: for the admin, or while Private is unlocked. */
    /**
     * Films (not episodes) as [viewer] sees them: private ones only when they may see Private, and only titles
     * cleared for kids on a kids profile.
     */
    fun movies(viewer: Viewer): List<Movie> =
        catalog.all().filter { !it.isEpisode && canSee(it, viewer) }.map { withJob(it.toMovie(viewer = viewer)) }

    /** Adds where the phone is with making a lighter copy, for the admin page. */
    private fun withJob(m: Movie): Movie = optimizer.status(m.id)?.let { m.copy(optimizing = it) } ?: m

    /** Every film, private ones included, for the phone's own screen. */
    fun allMovies(): List<Movie> = catalog.all().filter { !it.isEpisode }.map { it.toMovie() }

    fun movie(id: String, viewer: Viewer): Movie {
        val e = visible(id, viewer)
        val show = e.showId?.let { catalog.show(it) }
        return withJob(e.toMovie(show, viewer))
    }

    /**
     * The entry, or 404 for one that doesn't exist, is private while Private is locked, or isn't for kids on a
     * kids profile: all three look the same from outside.
     */
    fun visible(id: String, viewer: Viewer): Entry {
        val e = entry(id)
        if (!canSee(e, viewer)) throw HttpError(404, "No such movie")
        return e
    }

    private fun canSee(e: Entry, viewer: Viewer): Boolean {
        if (e.private && !viewer.seePrivate) return false
        // Files on a missing USB drive can't play: only the admin page lists them, marked offline.
        if (e.offline && !viewer.admin) return false
        if (!viewer.kids) return true
        return if (e.isEpisode) e.showId?.let { catalog.show(it) }?.kidsOk == true else e.kidsOk
    }

    private fun entry(id: String): Entry = catalog.get(id) ?: throw HttpError(404, "No such movie")

    // ---- Shows ----

    fun shows(viewer: Viewer): List<Show> =
        catalog.shows().filter { !viewer.kids || it.kidsOk }.map { toShow(it, viewer = viewer) }

    fun showDetail(id: String, viewer: Viewer): ShowDetail {
        val record = catalog.show(id)?.takeIf { !viewer.kids || it.kidsOk } ?: throw HttpError(404, "No such show")
        val episodes = catalog.episodes(id)
        return ShowDetail(toShow(record, episodes, viewer), episodes.map { it.toMovie(record, viewer) })
    }

    private fun toShow(record: ShowRecord, episodes: List<Entry> = catalog.episodes(record.id), viewer: Viewer? = null): Show =
        record.toShow(episodes, episodes.any { it.posterGenerated }, viewer)

    /** A show's poster: an uploaded thumbnail, TMDB's, or the first episode's frame. */
    fun showPoster(id: String): Pair<InputStream, String>? {
        val record = catalog.show(id) ?: throw HttpError(404, "No such show")
        return showFile(record.customPoster, customShowFile(id)) ?: showFile(record.tmdb?.poster == true, tmdbShowFile(id, true))
            ?: firstFrame(id)
    }

    /** A show's backdrop: TMDB's, else the first episode's frame, else its poster. */
    fun showBackdrop(id: String): Pair<InputStream, String>? {
        val record = catalog.show(id) ?: throw HttpError(404, "No such show")
        return showFile(record.tmdb?.backdrop == true, tmdbShowFile(id, false)) ?: firstFrame(id)
            ?: showFile(record.customPoster, customShowFile(id)) ?: showFile(record.tmdb?.poster == true, tmdbShowFile(id, true))
    }

    private fun showFile(has: Boolean, file: File): Pair<InputStream, String>? =
        if (has && file.exists()) file.inputStream() to "image/jpeg" else null

    private fun firstFrame(showId: String): Pair<InputStream, String>? =
        catalog.episodes(showId).firstNotNullOfOrNull { e -> frame(e) }

    private fun customShowFile(id: String) = File(posterDir, "custom-show-$id.jpg")

    private fun tmdbShowFile(id: String, poster: Boolean) = File(posterDir, if (poster) "tmdb-sp-$id.jpg" else "tmdb-sb-$id.jpg")

    private fun stillFile(entryId: String) = File(posterDir, "tmdb-es-$entryId.jpg")

    fun setShowPoster(id: String, bytes: ByteArray): Show {
        catalog.show(id) ?: throw HttpError(404, "No such show")
        if (!JpegFiles.save(bytes, 600, customShowFile(id))) {
            throw HttpError(400, "That isn't a picture the phone can read (use JPEG or PNG)")
        }
        return toShow(catalog.updateShow(id) { it.copy(customPoster = true, artVersion = System.currentTimeMillis()) }!!)
    }

    fun removeShowPoster(id: String): Show {
        customShowFile(id).delete()
        return toShow(
            catalog.updateShow(id) { it.copy(customPoster = false, artVersion = System.currentTimeMillis()) }
                ?: throw HttpError(404, "No such show"),
        )
    }

    /** The same rules as [edit]: unchanged fields stay as they were, changed ones become yours. */
    fun editShow(id: String, edit: MovieEdit): Show {
        if (edit.year != null && edit.year !in 1870..2200) throw HttpError(400, "That year doesn't look right")
        if (edit.imdbRating != null && Ratings.normalize(edit.imdbRating) == null) {
            throw HttpError(400, "An IMDb rating is between 0 and 10")
        }
        if (!KidsSetting.valid(edit.kids)) throw HttpError(400, "Kids is auto, yes or no")
        val reset = edit.reset.toSet()
        val updated = catalog.updateShow(id) { r ->
            val shown = toShow(r)
            val title = edit.title.trim().take(200)
            val description = edit.description?.trim()?.take(2000)?.ifEmpty { null }
            val genres = Genres.normalize(edit.genres)
            val rating = Ratings.normalize(edit.imdbRating)
            var next = r
            next = when {
                "title" in reset || title.isEmpty() -> next.copy(titleOverride = null)
                title == shown.title -> next
                else -> next.copy(titleOverride = title)
            }
            next = when {
                "year" in reset -> next.copy(yearOverridden = false, yearOverride = null)
                edit.year == shown.year -> next
                else -> next.copy(yearOverridden = true, yearOverride = edit.year)
            }
            next = when {
                "description" in reset -> next.copy(description = null)
                description == shown.description -> next
                else -> next.copy(description = description)
            }
            next = when {
                "genres" in reset -> next.copy(genres = emptyList())
                genres == shown.genres -> next
                else -> next.copy(genres = genres)
            }
            next = when {
                "rating" in reset -> next.copy(imdbRating = null)
                rating == shown.imdbRating -> next
                else -> next.copy(imdbRating = rating)
            }
            next.copy(kids = edit.kids ?: next.kids)
        } ?: throw HttpError(404, "No such show")
        return toShow(updated)
    }

    fun searchShows(query: String, year: Int?): List<TmdbResult> = lookup { metadata.searchShows(query.take(200), year) }

    fun matchShow(id: String, tmdbId: Int): Show {
        val r = catalog.show(id) ?: throw HttpError(404, "No such show")
        return applyShowMatch(id, lookup { metadata.fetchShow(tmdbId, r.tmdb) })
    }

    fun refreshShow(id: String): Show {
        val r = catalog.show(id) ?: throw HttpError(404, "No such show")
        val tmdbId = r.tmdb?.tmdbId ?: lookup { metadata.autoMatchShow(r.searchTitle, r.searchYear) }
            ?: return toShow(catalog.updateShow(id) { it.copy(match = MatchState.NEEDS_MATCH) } ?: r)
        return applyShowMatch(id, lookup { metadata.fetchShow(tmdbId, r.tmdb) })
    }

    fun unmatchShow(id: String, skip: Boolean): Show {
        tmdbShowFile(id, true).delete()
        tmdbShowFile(id, false).delete()
        for (e in catalog.episodes(id)) {
            stillFile(e.id).delete()
            catalog.update(e.id) { it.copy(episodeInfo = null, artVersion = System.currentTimeMillis()) }
        }
        return toShow(
            catalog.updateShow(id) {
                it.copy(tmdb = null, match = if (skip) MatchState.SKIPPED else MatchState.NEEDS_MATCH, artVersion = System.currentTimeMillis())
            } ?: throw HttpError(404, "No such show"),
        )
    }

    /**
     * Saves a show's details and pictures, then each season the library has: episode titles, descriptions and
     * stills. A season that fails to load keeps what its episodes had.
     */
    private fun applyShowMatch(id: String, fetched: FetchedShow): Show {
        val poster = fetched.poster?.let { JpegFiles.save(it, 600, tmdbShowFile(id, true)) }
            ?: (fetched.info.poster && tmdbShowFile(id, true).exists())
        val backdrop = fetched.backdrop?.let { JpegFiles.save(it, 1280, tmdbShowFile(id, false)) }
            ?: (fetched.info.backdrop && tmdbShowFile(id, false).exists())
        if (!poster) tmdbShowFile(id, true).delete()
        if (!backdrop) tmdbShowFile(id, false).delete()
        catalog.updateShow(id) {
            it.copy(
                tmdb = fetched.info.copy(poster = poster, backdrop = backdrop),
                match = MatchState.MATCHED,
                matchFailedAt = 0,
                artVersion = System.currentTimeMillis(),
            )
        } ?: throw HttpError(404, "No such show")
        val episodes = catalog.episodes(id)
        for (season in episodes.mapNotNull { it.season }.distinct()) {
            val listing = try {
                metadata.fetchSeason(fetched.info.tmdbId, season).associateBy { it.number }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "Season $season of show $id didn't load", e)
                continue
            }
            for (e in episodes.filter { it.season == season }) {
                val ep = listing[e.episode] ?: continue
                val still = ep.stillPath?.let { path -> metadata.still(path)?.let { JpegFiles.save(it, 400, stillFile(e.id)) } }
                    ?: (e.episodeInfo?.still == true && stillFile(e.id).exists())
                catalog.update(e.id) {
                    it.copy(
                        episodeInfo = EpisodeInfo(ep.title, ep.overview, ep.year, still),
                        artVersion = System.currentTimeMillis(),
                    )
                }
            }
        }
        return toShow(catalog.show(id)!!)
    }

    // ---- Private ----

    val privateUnlocked: Boolean get() = System.currentTimeMillis() < prefs.privateUntil

    fun privateState() = PrivateState(
        unlocked = privateUnlocked,
        until = prefs.privateUntil.takeIf { privateUnlocked },
        count = catalog.all().count { it.private },
    )

    /** Unlocks Private for [PrivateUpdate.minutes] (5 minutes to 12 hours), or locks it now. */
    fun setPrivate(update: PrivateUpdate): PrivateState {
        prefs.privateUntil = if (update.unlock) {
            System.currentTimeMillis() + update.minutes.coerceIn(5, 12 * 60) * 60_000L
        } else {
            0
        }
        return privateState()
    }

    /**
     * Moves a film into the Private folder or back to the top of the library, with its subtitles and poster. Its
     * resume point, details and pictures move with it, under the id its new location gives it.
     */
    fun move(id: String, toPrivate: Boolean): Movie {
        val tree = requireTree()
        val e = entry(id)
        if (e.isEpisode) throw HttpError(400, "Episodes stay with their show")
        if (e.treeUri != null) throw HttpError(400, "Only files in the main folder can move to Private")
        if (e.private == toPrivate) return e.toMovie()
        val target = if (toPrivate) privateFolder(tree, create = true)!! else Saf.rootDocId(tree)
        val source = Saf.docUri(tree, e.parentDocId)
        val targetUri = Saf.docUri(tree, target)
        val moved = try {
            DocumentsContract.moveDocument(cr, Saf.docUri(tree, e.docId), source, targetUri)
        } catch (ex: Exception) {
            throw HttpError(500, "The phone wouldn't move ${e.fileName}: ${ex.message}")
        } ?: throw HttpError(500, "The phone wouldn't move ${e.fileName}")
        for (side in e.subtitleDocIds + listOfNotNull(e.posterDocId)) {
            runCatching { DocumentsContract.moveDocument(cr, Saf.docUri(tree, side), source, targetUri) }
        }
        val docId = DocumentsContract.getDocumentId(moved)
        val newId = com.parthajb.homereel.shared.MovieIds.of(Saf.docUri(tree, docId).toString())
        for ((from, to) in listOf(scanner.frameFile(id) to scanner.frameFile(newId), scanner.customPosterFile(id) to scanner.customPosterFile(newId),
            tmdbFile(id, true) to tmdbFile(newId, true), tmdbFile(id, false) to tmdbFile(newId, false),
            optimizer.file(id) to optimizer.file(newId))) {
            if (from.exists()) from.renameTo(to)
        }
        catalog.remove(id)
        // Private files are never looked up; one coming out of Private gets looked up like any new film.
        val next = e.copy(
            id = newId, docId = docId, parentDocId = target, private = toPrivate,
            match = if (toPrivate) MatchState.SKIPPED else if (e.tmdb != null) MatchState.MATCHED else MatchState.NONE,
            artVersion = System.currentTimeMillis(),
        )
        catalog.put(next)
        requestScan()
        return next.toMovie()
    }

    /** The Private folder's document id, created (with its .nomedia) when [create] is set and it's missing. */
    fun privateFolder(tree: Uri, create: Boolean): String? {
        val root = Saf.rootDocId(tree)
        Saf.children(cr, tree, root).firstOrNull { it.isDir && it.name.equals(Scanner.PRIVATE_FOLDER, ignoreCase = true) }
            ?.let { return it.docId }
        if (!create) return null
        val dir = DocumentsContract.createDocument(cr, Saf.docUri(tree, root), DocumentsContract.Document.MIME_TYPE_DIR, Scanner.PRIVATE_FOLDER)
            ?: throw HttpError(500, "The phone wouldn't create the Private folder")
        runCatching { DocumentsContract.createDocument(cr, dir, "application/octet-stream", ".nomedia") }
        return DocumentsContract.getDocumentId(dir)
    }

    private fun requireTree(): Uri = tree ?: throw HttpError(503, "Choose the movie folder in the HomeReel app on the phone first")

    fun setFolder(uri: Uri) {
        prefs.treeUri = uri
        requestScan()
    }

    /** Queues a scan; a request while one is waiting is folded into it. */
    fun requestScan() {
        synchronized(this) {
            if (scanQueued) return
            scanQueued = true
        }
        worker.execute {
            synchronized(this) { scanQueued = false }
            val tree = prefs.treeUri ?: return@execute
            scanning = true
            val started = System.currentTimeMillis()
            try {
                val previous = catalog.forTree(tree.toString())
                val entries = scanner.scan(tree, previous).toMutableList()
                // Extra folders: one that can't be read (a USB drive unplugged) keeps its entries, marked offline,
                // so progress and details survive until it's plugged back in.
                val missing = HashSet<String>()
                for (extra in prefs.extraTrees) {
                    try {
                        entries += scanner.scan(extra, previous, main = false)
                    } catch (e: Exception) {
                        missing += extra.toString()
                        entries += previous.values.filter { it.treeUri == extra.toString() }.map { it.copy(offline = true) }
                        if (extra.toString() !in unavailable) {
                            Log.w(TAG, "Extra folder unavailable", e)
                            val name = Saf.folderName(extra)
                            // Moving or renaming a folder on the phone takes away the app's access to it.
                            events.warn(
                                if (e is SecurityException) "HomeReel lost access to the folder $name. Remove it and add it again."
                                else "The folder $name isn't available (USB drive unplugged?)",
                            )
                        }
                    }
                }
                unavailable = missing
                val before = catalog.size()
                catalog.replaceAll(tree.toString(), entries)
                if (entries.size != before) events.info("Library scanned: ${entries.size} videos (was $before)")
                lastScanAt = System.currentTimeMillis()
                lastScanMs = lastScanAt - started
                val showIds = entries.mapNotNull { it.showId }.toSet()
                val keep = entries.flatMap {
                    listOf("${it.id}.jpg", "custom-${it.id}.jpg", "tmdb-p-${it.id}.jpg", "tmdb-b-${it.id}.jpg", "tmdb-es-${it.id}.jpg")
                }.toSet() + showIds.flatMap { listOf("custom-show-$it.jpg", "tmdb-sp-$it.jpg", "tmdb-sb-$it.jpg") }
                posterDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
                scanError = null
                val keepCopies = entries.filter { it.optimized }.map { "${it.id}.mp4" }.toSet()
                optimizer.file("x").parentFile?.listFiles()?.filter { it.name.endsWith(".mp4") && it.name !in keepCopies }?.forEach { it.delete() }
                requestMetadata()
                autoOptimize()
            } catch (e: SecurityException) {
                scanError = "The app lost access to the folder. Choose it again."
                events.error(scanError!!)
                Log.w(TAG, "Scan failed", e)
            } catch (e: Exception) {
                scanError = e.message ?: e.javaClass.simpleName
                events.error("Scan failed: $scanError")
                Log.w(TAG, "Scan failed", e)
            } finally {
                scanning = false
            }
        }
    }

    fun open(id: String): Pair<Entry, ParcelFileDescriptor> {
        val e = entry(id)
        if (e.offline) throw HttpError(404, "That file's folder isn't available right now")
        val tree = treeOf(e) ?: requireTree()
        val pfd = try {
            cr.openFileDescriptor(Saf.docUri(tree, e.docId), "r")
        } catch (ex: Exception) {
            requestScan()
            throw HttpError(404, "The file is gone from the phone")
        } ?: throw HttpError(404, "The file is gone from the phone")
        return e to pfd
    }

    /**
     * The poster as a stream and its MIME type: an uploaded thumbnail, TMDB's poster, a picture beside the file,
     * or a frame.
     */
    fun poster(id: String): Pair<InputStream, String>? {
        val e = entry(id)
        if (e.isEpisode) return still(e) ?: frame(e)
        return customPoster(e) ?: tmdbPicture(e, poster = true) ?: sidecarPoster(e) ?: frame(e)
    }

    private fun still(e: Entry): Pair<InputStream, String>? =
        if (e.episodeInfo?.still == true && stillFile(e.id).exists()) stillFile(e.id).inputStream() to "image/jpeg" else null

    /** A wide picture for the TV's hero area: TMDB's backdrop, else a frame, else whatever poster there is. */
    fun backdrop(id: String): Pair<InputStream, String>? {
        val e = entry(id)
        if (e.isEpisode) return still(e) ?: frame(e)
        return tmdbPicture(e, poster = false) ?: frame(e) ?: customPoster(e) ?: sidecarPoster(e)
    }

    private fun tmdbPicture(e: Entry, poster: Boolean): Pair<InputStream, String>? {
        val has = if (poster) e.tmdb?.poster else e.tmdb?.backdrop
        val file = tmdbFile(e.id, poster)
        return if (has == true && file.exists()) file.inputStream() to "image/jpeg" else null
    }

    private fun tmdbFile(id: String, poster: Boolean) = File(posterDir, if (poster) "tmdb-p-$id.jpg" else "tmdb-b-$id.jpg")

    private fun customPoster(e: Entry): Pair<InputStream, String>? {
        val file = scanner.customPosterFile(e.id)
        return if (e.customPoster && file.exists()) file.inputStream() to "image/jpeg" else null
    }

    private fun sidecarPoster(e: Entry): Pair<InputStream, String>? {
        val docId = e.posterDocId ?: return null
        val tree = treeOf(e) ?: return null
        val mime = if (docId.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
        return runCatching { cr.openInputStream(Saf.docUri(tree, docId)) }.getOrNull()?.let { it to mime }
    }

    private fun frame(e: Entry): Pair<InputStream, String>? {
        val file = scanner.frameFile(e.id)
        return if (e.posterGenerated && file.exists()) file.inputStream() to "image/jpeg" else null
    }

    /**
     * Saves an uploaded thumbnail, re-encoded as a JPEG at most 600 px wide. Decoding it here also refuses anything
     * that isn't really an image.
     */
    fun setCustomPoster(id: String, bytes: ByteArray): Movie {
        entry(id)
        if (!JpegFiles.save(bytes, 600, scanner.customPosterFile(id))) {
            throw HttpError(400, "That isn't a picture the phone can read (use JPEG or PNG)")
        }
        return (catalog.update(id) { it.copy(customPoster = true, artVersion = System.currentTimeMillis()) }
            ?: throw HttpError(404, "No such movie")).toMovie()
    }

    fun removeCustomPoster(id: String): Movie {
        scanner.customPosterFile(id).delete()
        return (catalog.update(id) { it.copy(customPoster = false, artVersion = System.currentTimeMillis()) }
            ?: throw HttpError(404, "No such movie")).toMovie()
    }

    fun subtitle(id: String, index: Int): Pair<InputStream, String> {
        val e = entry(id)
        val docId = e.subtitleDocIds.getOrNull(index) ?: throw HttpError(404, "No such subtitle")
        val stream = runCatching { cr.openInputStream(Saf.docUri(treeOf(e) ?: requireTree(), docId)) }.getOrNull()
            ?: throw HttpError(404, "The subtitle file is gone")
        return stream to (e.subtitles.getOrNull(index)?.mime ?: "application/x-subrip")
    }

    /** Saves [viewer]'s place in a video; past 95% counts as watched and goes back to the start. */
    fun setProgress(id: String, update: ProgressUpdate, viewer: Viewer): Movie {
        val now = System.currentTimeMillis()
        val updated = catalog.update(id) { e ->
            val duration = if (update.durationMs > 0) update.durationMs else e.durationMs
            val watched = duration > 0 && update.positionMs >= duration * 95 / 100
            val old = e.progressFor(viewer.pid)
            val next = Progress(
                positionMs = if (watched) 0 else update.positionMs.coerceAtLeast(0),
                watched = watched,
                lastPlayedAt = if (watched || update.positionMs > 0) now else old.lastPlayedAt,
            )
            e.copy(progress = e.progress + (viewer.pid to next), durationMs = if (e.durationMs > 0) e.durationMs else duration)
        } ?: throw HttpError(404, "No such movie")
        val show = updated.showId?.let { catalog.show(it) }
        return updated.toMovie(show, viewer)
    }

    /**
     * Applies the admin page's form. A field saved unchanged keeps whatever it had, so opening and saving a matched
     * movie doesn't freeze TMDB's values; a changed field becomes a typed-in value that refreshes never overwrite.
     * Fields in [MovieEdit.reset] go back to the online lookup (or the file name).
     */
    fun edit(id: String, edit: MovieEdit): Movie {
        if (edit.year != null && edit.year !in 1870..2200) throw HttpError(400, "That year doesn't look right")
        if (edit.imdbRating != null && Ratings.normalize(edit.imdbRating) == null) {
            throw HttpError(400, "An IMDb rating is between 0 and 10")
        }
        if (!KidsSetting.valid(edit.kids)) throw HttpError(400, "Kids is auto, yes or no")
        val reset = edit.reset.toSet()
        return (catalog.update(id) { e ->
            val shown = e.toMovie()
            val title = edit.title.trim().take(200)
            val description = edit.description?.trim()?.take(2000)?.ifEmpty { null }
            val genres = Genres.normalize(edit.genres)
            val rating = Ratings.normalize(edit.imdbRating)
            var next = e
            next = when {
                "title" in reset || title.isEmpty() -> next.copy(titleOverride = null)
                title == shown.title -> next
                else -> next.copy(titleOverride = title)
            }
            next = when {
                "year" in reset -> next.copy(yearOverridden = false, yearOverride = null)
                edit.year == shown.year -> next
                else -> next.copy(yearOverridden = true, yearOverride = edit.year)
            }
            next = when {
                "description" in reset -> next.copy(description = null)
                description == shown.description -> next
                else -> next.copy(description = description)
            }
            next = when {
                "genres" in reset -> next.copy(genres = emptyList())
                genres == shown.genres -> next
                else -> next.copy(genres = genres)
            }
            next = when {
                "rating" in reset -> next.copy(imdbRating = null)
                rating == shown.imdbRating -> next
                else -> next.copy(imdbRating = rating)
            }
            next.copy(kids = edit.kids ?: next.kids)
        } ?: throw HttpError(404, "No such movie")).toMovie()
    }

    // ---- Backup and restore ----

    /**
     * Everything typed in, looked up or watched, as one JSON file: entries and shows (with their TMDB details,
     * progress and settings), profiles and My Lists. Not the videos, keys, PIN or downloaded pictures; pictures come
     * back with Refresh info, or by themselves for new lookups.
     */
    fun backup(profiles: Profiles): String {
        val (entries, shows) = catalog.snapshot()
        return ApiJson.encodeToString(
            BackupFile.serializer(),
            BackupFile(1, System.currentTimeMillis(), prefs.serverName, entries, shows, profiles.exportJson(), prefs.autoMatch, prefs.autoOptimize),
        )
    }

    /**
     * Puts a backup's details back on the files that are here now, matched by id (same folder) or by file name and
     * size (a new phone or a new folder). File-level facts (where it is, its tracks) stay as scanned.
     */
    fun restore(json: String, profiles: Profiles): com.parthajb.homereel.shared.RestoreSummary {
        val b = try {
            ApiJson.decodeFromString(BackupFile.serializer(), json)
        } catch (e: Exception) {
            throw HttpError(400, "That isn't a HomeReel backup")
        }
        val current = catalog.all()
        val byName = current.groupBy { it.fileName to it.size }
        var films = 0
        for (old in b.entries) {
            val target = catalog.get(old.id) ?: byName[old.fileName to old.size]?.singleOrNull() ?: continue
            catalog.update(target.id) {
                it.copy(
                    titleOverride = old.titleOverride, yearOverridden = old.yearOverridden, yearOverride = old.yearOverride,
                    description = old.description, genres = old.genres, imdbRating = old.imdbRating, kids = old.kids,
                    // Pictures aren't in the backup: keep the details and let Refresh fetch them again.
                    tmdb = old.tmdb?.copy(poster = false, backdrop = false), match = old.match,
                    episodeInfo = old.episodeInfo?.copy(still = false),
                    progress = mergeProgress(it.progress, old.progress + legacy(old)),
                    artVersion = System.currentTimeMillis(),
                )
            }
            films++
        }
        var shows = 0
        val showsByTitle = catalog.shows().associateBy { Matching.normalise(it.parsedTitle) }
        for (old in b.shows) {
            val target = catalog.show(old.id) ?: showsByTitle[Matching.normalise(old.parsedTitle)] ?: continue
            catalog.updateShow(target.id) {
                it.copy(
                    titleOverride = old.titleOverride, yearOverridden = old.yearOverridden, yearOverride = old.yearOverride,
                    description = old.description, genres = old.genres, imdbRating = old.imdbRating, kids = old.kids,
                    tmdb = old.tmdb?.copy(poster = false, backdrop = false), match = old.match, markers = old.markers,
                    artVersion = System.currentTimeMillis(),
                )
            }
            shows++
        }
        val restoredProfiles = b.profiles?.let { profiles.importJson(it) } ?: 0
        prefs.autoMatch = b.autoMatch
        prefs.autoOptimize = b.autoOptimize
        events.info("Restored a backup from ${java.text.DateFormat.getDateInstance().format(java.util.Date(b.exportedAt))}: $films films, $shows shows")
        return com.parthajb.homereel.shared.RestoreSummary(films, b.entries.size, shows, b.shows.size, restoredProfiles)
    }

    private fun legacy(e: Entry): Map<String, Progress> =
        if (e.lastPlayedAt > 0 && Profiles.MAIN !in e.progress) mapOf(Profiles.MAIN to Progress(e.positionMs, e.watched, e.lastPlayedAt)) else emptyMap()

    /** For each profile, whichever was played last wins. */
    private fun mergeProgress(now: Map<String, Progress>, old: Map<String, Progress>): Map<String, Progress> =
        (now.keys + old.keys).associateWith { pid ->
            listOfNotNull(now[pid], old[pid]).maxByOrNull { it.lastPlayedAt }!!
        }

    // ---- Lighter copies ----

    /** The lighter copy to stream instead of the original, if there is one. */
    fun optimizedFile(e: Entry): File? = optimizer.file(e.id).takeIf { e.optimized && it.exists() }

    fun optimize(id: String): Movie {
        val e = entry(id)
        if (e.optimized) return e.toMovie()
        catalog.update(id) { it.copy(optimizeError = null) }
        optimizer.enqueue(id)
        return withJob(entry(id).toMovie())
    }

    fun removeOptimized(id: String): Movie {
        optimizer.cancel(id)
        optimizer.file(id).delete()
        return (catalog.update(id) { it.copy(optimized = false, optimizeError = null) } ?: throw HttpError(404, "No such movie")).toMovie()
    }

    /** While charging, queues every very heavy file that has no lighter copy yet (and hasn't failed before). */
    private fun autoOptimize() {
        if (!prefs.autoOptimize || !optimizer.charging) return
        catalog.all()
            .filter { !it.optimized && it.optimizeError == null && Bitrate.heavy(Bitrate.mbps(it.size, it.durationMs)) }
            .forEach { optimizer.enqueue(it.id) }
    }

    // ---- Skip intro and credits ----

    fun setMarkers(showId: String, m: SeasonMarkers): Show {
        val start = m.introStartMs
        val end = m.introEndMs
        if (start != null && end != null && end <= start) {
            throw HttpError(400, "The intro has to end after it starts")
        }
        val empty = m.introStartMs == null && m.introEndMs == null && m.creditsMs == null
        val updated = catalog.updateShow(showId) { r ->
            r.copy(markers = (r.markers.filter { it.season != m.season } + listOfNotNull(m.takeIf { !empty })).sortedBy { it.season })
        } ?: throw HttpError(404, "No such show")
        return toShow(updated)
    }

    // ---- Online lookups ----

    /**
     * Queues every title not looked up yet (and network failures from more than a day ago). One title a second,
     * on its own thread, so a big library fills in over a few minutes without blocking anything.
     */
    fun requestMetadata(force: Boolean = false) {
        if (!metadata.configured || !prefs.autoMatch) return
        val now = System.currentTimeMillis()
        fun due(match: String, failedAt: Long) = match == MatchState.NONE && (force || failedAt == 0L || now - failedAt > DAY)
        val ids = catalog.all().filter { !it.isEpisode && !it.private && due(it.match, it.matchFailedAt) }.map { "m:${it.id}" } +
            catalog.shows().filter { due(it.match, it.matchFailedAt) }.map { "s:${it.id}" }
        synchronized(lookupQueue) {
            val idle = lookupQueue.isEmpty()
            lookupQueue.addAll(ids)
            if (idle && lookupQueue.isNotEmpty()) lookups.execute { drainLookups() }
        }
    }

    fun lookupQueueSize(): Int = synchronized(lookupQueue) { lookupQueue.size }

    private fun drainLookups() {
        while (true) {
            val key = synchronized(lookupQueue) { lookupQueue.firstOrNull()?.also { lookupQueue.remove(it) } } ?: return
            if (key.startsWith("s:")) {
                if (!lookUpShow(key.removePrefix("s:"))) return
                Thread.sleep(1000)
                continue
            }
            val id = key.removePrefix("m:")
            val e = catalog.get(id) ?: continue
            if (e.match != MatchState.NONE) continue
            if (Matching.isHomeVideo(e.fileName, e.folderName)) {
                catalog.update(id) { it.copy(match = MatchState.SKIPPED) }
                continue
            }
            try {
                val tmdbId = metadata.autoMatch(e.searchTitle, e.searchYear)
                if (tmdbId == null) {
                    catalog.update(id) { it.copy(match = MatchState.NEEDS_MATCH) }
                } else {
                    applyMatch(id, metadata.fetch(tmdbId, e.tmdb))
                }
                metadataError = null
            } catch (ex: KeyRejected) {
                metadataError = ex.message
                synchronized(lookupQueue) { lookupQueue.clear() }
                return
            } catch (ex: Exception) {
                Log.w(TAG, "Lookup failed for ${e.fileName}", ex)
                metadataError = OFFLINE
                catalog.update(id) { it.copy(matchFailedAt = System.currentTimeMillis()) }
            }
            Thread.sleep(1000)
        }
    }

    /** Looks one show up. Returns false when the key was refused, which stops the whole queue. */
    private fun lookUpShow(id: String): Boolean {
        val r = catalog.show(id) ?: return true
        if (r.match != MatchState.NONE) return true
        try {
            val tmdbId = metadata.autoMatchShow(r.searchTitle, r.searchYear)
            if (tmdbId == null) {
                catalog.updateShow(id) { it.copy(match = MatchState.NEEDS_MATCH) }
            } else {
                applyShowMatch(id, metadata.fetchShow(tmdbId, r.tmdb))
            }
            metadataError = null
        } catch (ex: KeyRejected) {
            metadataError = ex.message
            synchronized(lookupQueue) { lookupQueue.clear() }
            return false
        } catch (ex: Exception) {
            Log.w(TAG, "Lookup failed for show ${r.parsedTitle}", ex)
            metadataError = OFFLINE
            catalog.updateShow(id) { it.copy(matchFailedAt = System.currentTimeMillis()) }
        }
        return true
    }

    private fun applyMatch(id: String, fetched: Fetched): Movie {
        // No bytes but still marked as having one: the download failed and the copy already on the phone stays.
        val poster = fetched.poster?.let { JpegFiles.save(it, 600, tmdbFile(id, true)) }
            ?: (fetched.info.poster && tmdbFile(id, true).exists())
        val backdrop = fetched.backdrop?.let { JpegFiles.save(it, 1280, tmdbFile(id, false)) }
            ?: (fetched.info.backdrop && tmdbFile(id, false).exists())
        if (!poster) tmdbFile(id, true).delete()
        if (!backdrop) tmdbFile(id, false).delete()
        return (catalog.update(id) {
            it.copy(
                tmdb = fetched.info.copy(poster = poster, backdrop = backdrop),
                match = MatchState.MATCHED,
                matchFailedAt = 0,
                artVersion = System.currentTimeMillis(),
            )
        } ?: throw HttpError(404, "No such movie")).toMovie()
    }

    fun search(query: String, year: Int?): List<TmdbResult> = lookup { metadata.search(query.take(200), year) }

    fun match(id: String, tmdbId: Int): Movie {
        val e = entry(id)
        return applyMatch(id, lookup { metadata.fetch(tmdbId, e.tmdb) })
    }

    /** Fetches a matched movie's details again, or tries an automatic match now for one that isn't matched. */
    fun refresh(id: String): Movie {
        val e = entry(id)
        val tmdbId = e.tmdb?.tmdbId ?: lookup { metadata.autoMatch(e.searchTitle, e.searchYear) }
            ?: return (catalog.update(id) { it.copy(match = MatchState.NEEDS_MATCH) } ?: e).toMovie()
        return applyMatch(id, lookup { metadata.fetch(tmdbId, e.tmdb) })
    }

    /** Forgets the match. [skip] also marks it a home video, never looked up again. */
    fun unmatch(id: String, skip: Boolean): Movie {
        tmdbFile(id, true).delete()
        tmdbFile(id, false).delete()
        return (catalog.update(id) {
            it.copy(
                tmdb = null,
                match = if (skip) MatchState.SKIPPED else MatchState.NEEDS_MATCH,
                artVersion = System.currentTimeMillis(),
            )
        } ?: throw HttpError(404, "No such movie")).toMovie()
    }

    fun settings() = MetadataSettings(
        tmdbKeySet = !prefs.tmdbKey.isNullOrBlank(),
        omdbKeySet = !prefs.omdbKey.isNullOrBlank(),
        autoMatch = prefs.autoMatch,
        testHooks = BuildConfig.DEBUG,
        autoOptimize = prefs.autoOptimize,
    )

    /** Saves the settings; a new TMDB key is tried first, so a typo is caught at once instead of on every lookup. */
    fun updateSettings(update: MetadataSettingsUpdate): MetadataSettings {
        if (BuildConfig.DEBUG) {
            update.tmdbBaseUrl?.let { prefs.tmdbBaseUrl = it }
            update.tmdbImageBaseUrl?.let { prefs.tmdbImageBaseUrl = it }
            update.omdbBaseUrl?.let { prefs.omdbBaseUrl = it }
        }
        update.tmdbKey?.trim()?.let { key ->
            if (key.isNotEmpty()) lookup { metadata.checkTmdbKey(key) }
            prefs.tmdbKey = key
            metadataError = null
        }
        update.omdbKey?.trim()?.let { prefs.omdbKey = it }
        update.autoMatch?.let { prefs.autoMatch = it }
        update.autoOptimize?.let {
            prefs.autoOptimize = it
            if (it) autoOptimize()
        }
        requestMetadata(force = true)
        return settings()
    }

    /** Runs a lookup for the admin page, turning lookup failures into errors the page can show. */
    private fun <T> lookup(block: () -> T): T = try {
        block()
    } catch (e: KeyRejected) {
        throw HttpError(400, e.message ?: "The key was rejected")
    } catch (e: java.io.IOException) {
        Log.w(TAG, "Lookup failed", e)
        throw HttpError(502, OFFLINE)
    }

    /** Deletes the video and the poster and subtitles that belong only to it. */
    fun delete(id: String) {
        val e = entry(id)
        if (e.offline) throw HttpError(400, "That file's folder isn't available right now")
        val tree = treeOf(e) ?: requireTree()
        // A folder's poster.jpg is only ever used for the folder's one movie, so it belongs to this movie too.
        val docs = listOf(e.docId) + e.subtitleDocIds + listOfNotNull(e.posterDocId)
        for (docId in docs) {
            try {
                DocumentsContract.deleteDocument(cr, Saf.docUri(tree, docId))
            } catch (ex: Exception) {
                if (docId == e.docId && Saf.row(cr, tree, docId) != null) {
                    throw HttpError(500, "The phone wouldn't delete ${e.fileName}: ${ex.message}")
                }
            }
        }
        scanner.frameFile(id).delete()
        scanner.customPosterFile(id).delete()
        tmdbFile(id, true).delete()
        tmdbFile(id, false).delete()
        stillFile(id).delete()
        optimizer.cancel(id)
        optimizer.file(id).delete()
        catalog.remove(id)
    }

    /** Picks up a finished upload straight away; a sidecar file triggers a scan to attach it. */
    fun uploaded(docId: String) {
        requestScan()
        Log.i(TAG, "Upload finished: $docId")
    }

    fun setReport(report: PlayabilityReport) {
        this.report = report
        prefs.playabilityJson = ApiJson.encodeToString(PlayabilityReport.serializer(), report)
    }

    /** What the TV said it can't play, or the phone's guess before any TV has reported. */
    fun cantPlay(): Pair<List<PlayabilityItem>, String> {
        val ids = catalog.all().map { it.id }.toSet()
        report?.let { r -> return r.items.filter { it.id in ids } to "tv" }
        return allMovies().mapNotNull { m -> Playability.baseline(m)?.let { PlayabilityItem(m.id, it) } } to "guess"
    }

    fun status(serverName: String): AdminStatus {
        val tree = tree
        val (free, total) = tree?.let { runCatching { Saf.space(it) }.getOrNull() } ?: (null to null)
        val (cantPlay, source) = cantPlay()
        return AdminStatus(
            serverName = serverName,
            libraryName = tree?.let { Saf.folderName(it) },
            freeBytes = free,
            totalBytes = total,
            scanning = scanning,
            scanError = scanError,
            movieCount = count(),
            cantPlay = cantPlay,
            cantPlaySource = source,
            metadataEnabled = metadata.configured && prefs.autoMatch,
            metadataQueue = lookupQueueSize(),
            metadataError = metadataError,
            optimizeQueue = optimizer.queued(),
        )
    }

    @kotlinx.serialization.Serializable
    private class BackupFile(
        val version: Int,
        val exportedAt: Long,
        val serverName: String,
        val entries: List<Entry>,
        val shows: List<ShowRecord>,
        val profiles: String? = null,
        val autoMatch: Boolean = true,
        val autoOptimize: Boolean = true,
    )

    private companion object {
        const val TAG = "HomeReel"
        const val DAY = 24 * 60 * 60 * 1000L
        const val OFFLINE = "Couldn't reach TMDB, even after trying twice. Check the phone's internet connection and try again."
    }
}
