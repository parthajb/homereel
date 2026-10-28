package com.parthajb.homereel.server

import android.os.ParcelFileDescriptor
import android.util.Log
import com.parthajb.homereel.shared.Api
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.AppUpdate
import com.parthajb.homereel.shared.ErrorBody
import com.parthajb.homereel.shared.MatchRequest
import com.parthajb.homereel.shared.MediaFiles
import com.parthajb.homereel.shared.MetadataSettings
import com.parthajb.homereel.shared.MetadataSettingsUpdate
import com.parthajb.homereel.shared.MovieEdit
import com.parthajb.homereel.shared.MoveRequest
import com.parthajb.homereel.shared.MovieList
import com.parthajb.homereel.shared.Health
import com.parthajb.homereel.shared.PinCheck
import com.parthajb.homereel.shared.PinUpdate
import com.parthajb.homereel.shared.PrivateRules
import com.parthajb.homereel.shared.PrivateState
import com.parthajb.homereel.shared.Profile
import com.parthajb.homereel.shared.ProfileEdit
import com.parthajb.homereel.shared.ProfileList
import com.parthajb.homereel.shared.RestoreSummary
import com.parthajb.homereel.shared.PrivateUpdate
import com.parthajb.homereel.shared.Show
import com.parthajb.homereel.shared.ShowDetail
import com.parthajb.homereel.shared.ShowList
import com.parthajb.homereel.shared.PairRequest
import com.parthajb.homereel.shared.PairResponse
import com.parthajb.homereel.shared.PlayabilityReport
import com.parthajb.homereel.shared.ProgressUpdate
import com.parthajb.homereel.shared.RangeResult
import com.parthajb.homereel.shared.ByteRange
import com.parthajb.homereel.shared.SeasonMarkers
import com.parthajb.homereel.shared.ServerInfo
import com.parthajb.homereel.shared.SubtitleOffset
import com.parthajb.homereel.shared.TmdbResult
import com.parthajb.homereel.shared.MagnetRequest
import com.parthajb.homereel.shared.TorrentItem
import com.parthajb.homereel.shared.TorrentList
import com.parthajb.homereel.shared.TorrentSettings
import com.parthajb.homereel.shared.Torrents
import com.parthajb.homereel.shared.IndexerCheck
import com.parthajb.homereel.shared.FeedEdit
import com.parthajb.homereel.shared.FeedList
import com.parthajb.homereel.shared.SearchAdd
import com.parthajb.homereel.shared.TorrentSearchPage
import com.parthajb.homereel.shared.UploadRequest
import com.parthajb.homereel.shared.UpdateStatus
import com.parthajb.homereel.shared.UploadState
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.InputStream

/**
 * The REST API and the admin page. Every route but pairing and the admin page itself needs a token: a TV sends it
 * as a bearer header, a browser as a cookie plus the CSRF header on anything that changes data.
 */
class HttpServer(private val app: ServerApp, port: Int) : NanoHTTPD(port) {
    private val library get() = app.library

    override fun serve(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0
        val body = Body(session.inputStream, length)
        bodies.set(body)
        return try {
            route(session)
        } catch (e: HttpError) {
            val extra = e.body
            if (extra is UploadState) json(e.status, UploadState.serializer(), extra) else error(e.status, e.message ?: "")
        } catch (e: Exception) {
            Log.w(TAG, "${session.method} ${session.uri} failed", e)
            error(500, e.message ?: e.javaClass.simpleName)
        } finally {
            // NanoHTTPD keeps the connection open, so a body nobody read would be parsed as the next request.
            body.drain()
            bodies.remove()
        }
    }

    private val bodies = ThreadLocal<Body>()

    /** The request body, cut off at Content-Length. */
    private class Body(private val input: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            return input.read().also { if (it >= 0) left-- else left = 0 }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n else left = 0
            return n
        }

        fun drain() {
            val buf = ByteArray(64 * 1024)
            runCatching { while (read(buf, 0, buf.size) > 0) Unit }
        }
    }

    override fun useGzipWhenAccepted(r: Response): Boolean =
        r.mimeType?.let { it.startsWith("application/json") || it.startsWith("text/html") } == true

    private fun route(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        val method = s.method
        if (path == "/" && method == Method.GET) return page("watch.html", media = true)
        if (path == "/admin" && method == Method.GET) return page("admin.html", media = false)
        if (!path.startsWith(Api.PREFIX)) throw HttpError(404, "Not found")
        val p = path.removePrefix(Api.PREFIX).split('/').filter { it.isNotEmpty() }

        if (p == listOf("pair") && method == Method.POST) return pair(s)

        val role = authenticate(s) ?: throw HttpError(401, "Pair with the code shown in the HomeReel app on the phone")
        fun requireAdmin() {
            if (role != Api.ROLE_ADMIN) throw HttpError(403, "Only a browser paired as admin can do that")
        }
        // Which profile is asking (a TV sends it; the admin page doesn't). The admin always sees the Private
        // folder; a TV only while the admin has it unlocked, and never on a kids profile.
        // A browser's <video> and <img> can't send headers, so the web app names the profile in the address instead.
        val profileId = s.headers[PROFILE_HEADER] ?: s.parameters["profile"]?.firstOrNull()
        val named = app.profiles.resolve(profileId)
        // The admin page names no profile and must always see everything, even if the first profile is a kids one.
        val profile = if (role == Api.ROLE_ADMIN && profileId == null) named.copy(kids = false) else named
        val viewer = Viewer(
            profile,
            app.profiles.myList(profile.id).toSet(),
            seePrivate = PrivateRules.seesPrivate(admin = role == Api.ROLE_ADMIN, unlocked = library.privateUnlocked, kidsProfile = profile.kids),
            admin = role == Api.ROLE_ADMIN,
        )
        if (p.size >= 2 && p[0] == "movies") library.visible(p[1], viewer)

        return when {
            p == listOf("info") && method == Method.GET -> json(
                200, ServerInfo.serializer(),
                ServerInfo(
                    app.prefs.serverName, BuildConfig.VERSION_NAME, Api.VERSION, library.count(), library.scanning,
                    library.privateState().until,
                ),
            )
            p == listOf("movies") && method == Method.GET ->
                json(200, MovieList.serializer(), MovieList(library.movies(viewer)))
            p == listOf("shows") && method == Method.GET -> json(200, ShowList.serializer(), ShowList(library.shows(viewer)))
            p == listOf("profiles") -> when (method) {
                Method.GET -> json(200, ProfileList.serializer(), app.profiles.list())
                Method.POST -> {
                    requireAdmin()
                    json(200, Profile.serializer(), app.profiles.create(readJson(s, ProfileEdit.serializer())))
                }
                else -> throw HttpError(405, "Method not allowed")
            }
            p == listOf("profiles", "pin") && method == Method.POST -> {
                app.profiles.checkPin(readJson(s, PinCheck.serializer()).pin)
                noContent()
            }
            p.size == 2 && p[0] == "profiles" -> {
                requireAdmin()
                when (method) {
                    Method.PATCH -> json(200, Profile.serializer(), app.profiles.update(p[1], readJson(s, ProfileEdit.serializer())))
                    Method.DELETE -> {
                        app.profiles.delete(p[1])
                        noContent()
                    }
                    else -> throw HttpError(405, "Method not allowed")
                }
            }
            p == listOf("admin", "pin") && method == Method.PUT -> {
                requireAdmin()
                app.profiles.setPin(readJson(s, PinUpdate.serializer()).pin)
                json(200, ProfileList.serializer(), app.profiles.list())
            }
            p == listOf("me", "list") && method == Method.GET ->
                json(200, ListSerializer(String.serializer()), app.profiles.myList(profile.id))
            p.size == 3 && p[0] == "me" && p[1] == "list" -> {
                val key = p[2]
                when (method) {
                    Method.PUT -> app.profiles.setInList(profile.id, key, true)
                    Method.DELETE -> app.profiles.setInList(profile.id, key, false)
                    else -> throw HttpError(405, "Method not allowed")
                }
                noContent()
            }
            p.size == 2 && p[0] == "shows" -> when (method) {
                Method.GET -> json(200, ShowDetail.serializer(), library.showDetail(p[1], viewer))
                Method.PATCH -> {
                    requireAdmin()
                    json(200, Show.serializer(), library.editShow(p[1], readJson(s, MovieEdit.serializer())))
                }
                else -> throw HttpError(405, "Method not allowed")
            }
            p.size == 3 && p[0] == "shows" && p[2] == "poster" -> when (method) {
                Method.GET -> image(library.showPoster(p[1]) ?: throw HttpError(404, "No poster"))
                Method.PUT -> {
                    requireAdmin()
                    json(200, Show.serializer(), library.setShowPoster(p[1], readPicture(s)))
                }
                Method.DELETE -> {
                    requireAdmin()
                    json(200, Show.serializer(), library.removeShowPoster(p[1]))
                }
                else -> throw HttpError(405, "Method not allowed")
            }
            p.size == 3 && p[0] == "shows" && p[2] == "backdrop" && method == Method.GET ->
                image(library.showBackdrop(p[1]) ?: throw HttpError(404, "No backdrop"))
            p.size == 3 && p[0] == "shows" && p[2] == "match" -> {
                requireAdmin()
                val show = when (method) {
                    Method.POST -> library.matchShow(p[1], readJson(s, MatchRequest.serializer()).tmdbId)
                    Method.DELETE -> library.unmatchShow(p[1], skip = false)
                    else -> throw HttpError(405, "Method not allowed")
                }
                json(200, Show.serializer(), show)
            }
            p.size == 3 && p[0] == "shows" && p[2] == "refresh" && method == Method.POST -> {
                requireAdmin()
                json(200, Show.serializer(), library.refreshShow(p[1]))
            }
            p.size == 3 && p[0] == "shows" && p[2] == "skip" && method == Method.POST -> {
                requireAdmin()
                json(200, Show.serializer(), library.unmatchShow(p[1], skip = true))
            }
            p == listOf("admin", "health") && method == Method.GET -> {
                requireAdmin()
                json(200, Health.serializer(), app.health.report())
            }
            // App updates: a TV asks whether there's a newer TV app and downloads it; the admin page uploads them.
            p.size == 2 && p[0] == "updates" && method == Method.GET ->
                json(200, AppUpdate.serializer(), app.updates.info(p[1]) ?: throw HttpError(404, "No update"))
            p.size == 3 && p[0] == "updates" && p[2] == "apk" && method == Method.GET -> {
                val file = app.updates.file(p[1])
                if (app.updates.info(p[1]) == null) throw HttpError(404, "No update")
                newFixedLengthResponse(Response.Status.OK, "application/vnd.android.package-archive", FileInputStream(file), file.length())
            }
            p == listOf("admin", "updates") && method == Method.GET -> {
                requireAdmin()
                json(200, UpdateStatus.serializer(), app.updates.status())
            }
            p.size == 3 && p[0] == "admin" && p[1] == "updates" -> {
                requireAdmin()
                when (method) {
                    Method.PUT -> {
                        val length = s.headers["content-length"]?.toLongOrNull() ?: throw HttpError(411, "Content-Length is missing")
                        json(200, AppUpdate.serializer(), app.updates.store(p[2], bodies.get()!!, length))
                    }
                    Method.DELETE -> {
                        app.updates.delete(p[2])
                        noContent()
                    }
                    else -> throw HttpError(405, "Method not allowed")
                }
            }
            p == listOf("admin", "backup") && method == Method.GET -> {
                requireAdmin()
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", library.backup(app.profiles)).apply {
                    addHeader("Content-Disposition", "attachment; filename=\"homereel-backup-$stamp.json\"")
                }
            }
            p == listOf("admin", "restore") && method == Method.POST -> {
                requireAdmin()
                val length = s.headers["content-length"]?.toLongOrNull() ?: throw HttpError(411, "Content-Length is missing")
                if (length > 50_000_000) throw HttpError(413, "That backup is too big")
                val bytes = ByteArray(length.toInt())
                DataInputStream(bodies.get()!!).readFully(bytes)
                json(200, RestoreSummary.serializer(), library.restore(bytes.decodeToString(), app.profiles))
            }
            p == listOf("admin", "private") -> {
                requireAdmin()
                val state = when (method) {
                    Method.GET -> library.privateState()
                    Method.PUT -> library.setPrivate(readJson(s, PrivateUpdate.serializer()))
                    else -> throw HttpError(405, "Method not allowed")
                }
                json(200, PrivateState.serializer(), state)
            }
            p.size == 3 && p[0] == "movies" && p[2] == "optimize" -> {
                requireAdmin()
                val movie = when (method) {
                    Method.POST -> library.optimize(p[1])
                    Method.DELETE -> library.removeOptimized(p[1])
                    else -> throw HttpError(405, "Method not allowed")
                }
                json(200, com.parthajb.homereel.shared.Movie.serializer(), movie)
            }
            p.size == 3 && p[0] == "shows" && p[2] == "markers" && method == Method.PUT -> {
                requireAdmin()
                json(200, Show.serializer(), library.setMarkers(p[1], readJson(s, SeasonMarkers.serializer())))
            }
            p.size == 3 && p[0] == "movies" && p[2] == "move" && method == Method.POST -> {
                requireAdmin()
                json(200, com.parthajb.homereel.shared.Movie.serializer(), library.move(p[1], readJson(s, MoveRequest.serializer()).private))
            }
            p.size == 2 && p[0] == "movies" -> when (method) {
                Method.GET -> json(200, com.parthajb.homereel.shared.Movie.serializer(), library.movie(p[1], viewer))
                Method.PATCH -> {
                    requireAdmin()
                    val edit = readJson(s, MovieEdit.serializer())
                    json(200, com.parthajb.homereel.shared.Movie.serializer(), library.edit(p[1], edit))
                }
                Method.DELETE -> {
                    requireAdmin()
                    library.delete(p[1])
                    noContent()
                }
                else -> throw HttpError(405, "Method not allowed")
            }
            p.size == 3 && p[0] == "movies" && p[2] == "stream" && method == Method.GET -> stream(s, p[1])
            p.size == 3 && p[0] == "movies" && p[2] == "poster" -> when (method) {
                Method.GET -> image(library.poster(p[1]) ?: throw HttpError(404, "No poster"))
                Method.PUT -> {
                    requireAdmin()
                    json(200, com.parthajb.homereel.shared.Movie.serializer(), library.setCustomPoster(p[1], readPicture(s)))
                }
                Method.DELETE -> {
                    requireAdmin()
                    json(200, com.parthajb.homereel.shared.Movie.serializer(), library.removeCustomPoster(p[1]))
                }
                else -> throw HttpError(405, "Method not allowed")
            }
            p.size == 3 && p[0] == "movies" && p[2] == "backdrop" && method == Method.GET ->
                image(library.backdrop(p[1]) ?: throw HttpError(404, "No backdrop"))
            p.size == 4 && p[0] == "movies" && p[2] == "subtitles" && method == Method.GET -> {
                val index = p[3].toIntOrNull() ?: throw HttpError(404, "No such subtitle")
                val (input, mime) = library.subtitle(p[1], index)
                newChunkedResponse(Response.Status.OK, "$mime; charset=utf-8", input)
            }
            p.size == 3 && p[0] == "movies" && p[2] == "subtitle-offset" && method == Method.PUT -> json(
                200, com.parthajb.homereel.shared.Movie.serializer(), library.setSubtitleOffset(p[1], readJson(s, SubtitleOffset.serializer()).offsetMs, viewer),
            )
            p.size == 3 && p[0] == "movies" && p[2] == "progress" && method == Method.PUT -> {
                val update = readJson(s, ProgressUpdate.serializer())
                json(200, com.parthajb.homereel.shared.Movie.serializer(), library.setProgress(p[1], update, viewer))
            }
            p == listOf("rescan") && method == Method.POST -> {
                library.requestScan()
                noContent()
            }
            p == listOf("playability") && method == Method.PUT -> {
                library.setReport(readJson(s, PlayabilityReport.serializer()))
                noContent()
            }
            p == listOf("admin", "status") && method == Method.GET -> {
                requireAdmin()
                json(200, com.parthajb.homereel.shared.AdminStatus.serializer(), library.status(app.prefs.serverName))
            }
            p.size == 3 && p[0] == "movies" && p[2] == "match" -> {
                requireAdmin()
                val movie = when (method) {
                    Method.POST -> library.match(p[1], readJson(s, MatchRequest.serializer()).tmdbId)
                    Method.DELETE -> library.unmatch(p[1], skip = false)
                    else -> throw HttpError(405, "Method not allowed")
                }
                json(200, com.parthajb.homereel.shared.Movie.serializer(), movie)
            }
            p.size == 3 && p[0] == "movies" && p[2] == "refresh" && method == Method.POST -> {
                requireAdmin()
                json(200, com.parthajb.homereel.shared.Movie.serializer(), library.refresh(p[1]))
            }
            p.size == 3 && p[0] == "movies" && p[2] == "skip" && method == Method.POST -> {
                requireAdmin()
                json(200, com.parthajb.homereel.shared.Movie.serializer(), library.unmatch(p[1], skip = true))
            }
            p == listOf("admin", "settings") -> {
                requireAdmin()
                val settings = when (method) {
                    Method.GET -> library.settings()
                    Method.PUT -> library.updateSettings(readJson(s, MetadataSettingsUpdate.serializer()))
                    else -> throw HttpError(405, "Method not allowed")
                }
                json(200, MetadataSettings.serializer(), settings)
            }
            p == listOf("admin", "tmdb", "search") && method == Method.GET -> {
                requireAdmin()
                val query = s.parameters["q"]?.firstOrNull()?.trim().orEmpty()
                if (query.isEmpty()) throw HttpError(400, "Type a title to search for")
                val year = s.parameters["year"]?.firstOrNull()?.toIntOrNull()
                val results = if (s.parameters["type"]?.firstOrNull() == "tv") library.searchShows(query, year) else library.search(query, year)
                json(200, ListSerializer(TmdbResult.serializer()), results)
            }
            p == listOf("admin", "metadata", "run") && method == Method.POST -> {
                requireAdmin()
                library.requestMetadata(force = true)
                noContent()
            }
            p == listOf("admin", "uploads") && method == Method.POST -> {
                requireAdmin()
                val tree = library.tree ?: throw HttpError(503, "Choose the movie folder on the phone first")
                val request = readJson(s, UploadRequest.serializer())
                val folder = if (request.private) library.privateFolder(tree, create = true)!! else Saf.rootDocId(tree)
                json(200, UploadState.serializer(), library.uploads.start(tree, request, folder))
            }
            p.size == 3 && p[0] == "admin" && p[1] == "uploads" -> {
                requireAdmin()
                val tree = library.tree ?: throw HttpError(503, "Choose the movie folder on the phone first")
                when (method) {
                    Method.GET -> json(200, UploadState.serializer(), library.uploads.state(tree, p[2]))
                    Method.PUT -> upload(s, tree, p[2])
                    Method.DELETE -> {
                        library.uploads.cancel(tree, p[2])
                        noContent()
                    }
                    else -> throw HttpError(405, "Method not allowed")
                }
            }
            // Torrent downloads: admin only, and refused until they're turned on in Settings.
            p == listOf("admin", "torrents") -> {
                requireAdmin()
                when (method) {
                    Method.GET -> json(200, TorrentList.serializer(), app.torrents.list(includePrivate = true))
                    Method.POST -> {
                        val length = s.headers["content-length"]?.toLongOrNull() ?: throw HttpError(411, "Content-Length is missing")
                        if (length <= 0 || length > Torrents.MAX_TORRENT_BYTES) throw HttpError(413, "That's too big to be a .torrent file")
                        val bytes = ByteArray(length.toInt())
                        DataInputStream(bodies.get()!!).readFully(bytes)
                        val private = s.parameters["private"]?.firstOrNull() == "1"
                        json(200, TorrentItem.serializer(), app.torrents.add(bytes, private))
                    }
                    else -> throw HttpError(405, "Method not allowed")
                }
            }
            p == listOf("admin", "torrents", "magnet") && method == Method.POST -> {
                requireAdmin()
                val request = readJson(s, MagnetRequest.serializer())
                json(200, TorrentItem.serializer(), app.torrents.addMagnet(request.magnet, request.private))
            }
            p == listOf("admin", "torrents", "settings") && method == Method.PUT -> {
                requireAdmin()
                json(200, TorrentList.serializer(), app.torrents.updateSettings(readJson(s, TorrentSettings.serializer())))
            }
            // Torrent search across the admin's feeds. Before the {id}/pause route, which "search/add" would match.
            p == listOf("admin", "torrents", "feeds") -> {
                requireAdmin()
                val feeds = when (method) {
                    Method.GET -> app.torrentSearch.list()
                    Method.POST -> app.torrentSearch.add(readJson(s, FeedEdit.serializer()))
                    else -> throw HttpError(405, "Method not allowed")
                }
                json(200, FeedList.serializer(), feeds)
            }
            p == listOf("admin", "torrents", "feeds", "test") && method == Method.POST -> {
                requireAdmin()
                json(200, IndexerCheck.serializer(), app.torrentSearch.check(readJson(s, FeedEdit.serializer())))
            }
            p.size == 4 && p[0] == "admin" && p[1] == "torrents" && p[2] == "feeds" -> {
                requireAdmin()
                val feeds = when (method) {
                    Method.PUT -> app.torrentSearch.update(p[3], readJson(s, FeedEdit.serializer()))
                    Method.DELETE -> app.torrentSearch.remove(p[3])
                    else -> throw HttpError(405, "Method not allowed")
                }
                json(200, FeedList.serializer(), feeds)
            }
            p == listOf("admin", "torrents", "search") && method == Method.GET -> {
                requireAdmin()
                val q = s.parameters["q"]?.firstOrNull().orEmpty()
                json(200, TorrentSearchPage.serializer(), app.torrentSearch.search(q, s.parameters["cat"]?.firstOrNull()))
            }
            p == listOf("admin", "torrents", "search", "add") && method == Method.POST -> {
                requireAdmin()
                val request = readJson(s, SearchAdd.serializer())
                json(200, TorrentItem.serializer(), app.torrentSearch.add(request.id, request.private))
            }
            p.size == 3 && p[0] == "admin" && p[1] == "torrents" && method == Method.DELETE -> {
                requireAdmin()
                app.torrents.remove(p[2])
                noContent()
            }
            p.size == 4 && p[0] == "admin" && p[1] == "torrents" && method == Method.POST -> {
                requireAdmin()
                when (p[3]) {
                    "pause" -> app.torrents.pause(p[2], true)
                    "resume" -> app.torrents.pause(p[2], false)
                    "retry" -> app.torrents.retry(p[2])
                    else -> throw HttpError(404, "Not found")
                }
                noContent()
            }
            else -> throw HttpError(404, "Not found")
        }
    }

    private fun authenticate(s: IHTTPSession): String? {
        s.headers["authorization"]?.let { header ->
            if (header.startsWith("Bearer ", ignoreCase = true)) return app.auth.roleOf(header.substring(7).trim())
        }
        val cookie = s.cookies.read(Api.TOKEN_COOKIE) ?: return null
        if (s.method != Method.GET && s.headers[Api.CSRF_HEADER] != "1") {
            throw HttpError(403, "Missing the ${Api.CSRF_HEADER} header")
        }
        return app.auth.roleOf(cookie)
    }

    private fun pair(s: IHTTPSession): Response {
        val request = readJson(s, PairRequest.serializer())
        return when (val result = app.auth.pair(request)) {
            // A browser keeps its token in a cookie, as an admin (the admin page) or a viewer (the web app); the TV
            // app uses the token in the body and ignores the cookie.
            is PairResult.Ok -> json(200, PairResponse.serializer(), PairResponse(result.token, result.role, app.prefs.serverName)).apply {
                addHeader("Set-Cookie", "${Api.TOKEN_COOKIE}=${result.token}; Path=/; Max-Age=31536000; HttpOnly; SameSite=Strict")
            }
            PairResult.WrongCode -> throw HttpError(403, "That code isn't right. Check the HomeReel app on the phone.")
            is PairResult.Locked -> throw HttpError(429, "Too many wrong codes. Try again in ${result.seconds} s.")
        }
    }

    /**
     * The video, or its lighter copy when there is one (?original=1 asks for the original). Seeking works the same
     * either way.
     */
    private fun stream(s: IHTTPSession, id: String): Response {
        val (entry, original) = library.open(id)
        val copy = if (s.parameters["original"] == null) library.optimizedFile(entry) else null
        val pfd = if (copy != null) {
            original.close()
            ParcelFileDescriptor.open(copy, ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            original
        }
        val size = pfd.statSize.takeIf { it >= 0 } ?: entry.size
        val mime = if (copy != null) "video/mp4" else MediaFiles.videoMime(entry.fileName)
        val (status, start, length) = when (val range = ByteRange.parse(s.headers["range"], size)) {
            RangeResult.Full -> Triple(200, 0L, size)
            is RangeResult.Partial -> Triple(206, range.start, range.length)
            RangeResult.Unsatisfiable -> {
                pfd.close()
                return error(416, "Range not satisfiable").apply { addHeader("Content-Range", "bytes */$size") }
            }
        }
        app.locks.begin(watching = true)
        val body = FileSlice(pfd, start, length) { app.locks.end(watching = true) }
        return newFixedLengthResponse(Code(status), mime, body, length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (status == 206) addHeader("Content-Range", "bytes $start-${start + length - 1}/$size")
        }
    }

    private fun upload(s: IHTTPSession, tree: android.net.Uri, uploadId: String): Response {
        val offset = s.parameters["offset"]?.firstOrNull()?.toLongOrNull() ?: throw HttpError(400, "offset is missing")
        val length = s.headers["content-length"]?.toLongOrNull() ?: throw HttpError(411, "Content-Length is missing")
        if (length > Api.CHUNK_BYTES * 2L) throw HttpError(413, "Chunks are at most ${Api.CHUNK_BYTES} bytes")
        app.locks.begin()
        try {
            val (state, finishedDocId) = library.uploads.write(tree, uploadId, offset, length, bodies.get()!!)
            finishedDocId?.let { library.uploaded(it) }
            return json(200, UploadState.serializer(), state)
        } finally {
            app.locks.end()
        }
    }

    private fun readPicture(s: IHTTPSession): ByteArray {
        val length = s.headers["content-length"]?.toLongOrNull() ?: throw HttpError(411, "Content-Length is missing")
        if (length > 15_000_000) throw HttpError(413, "Pictures are at most 15 MB")
        val bytes = ByteArray(length.toInt())
        DataInputStream(bodies.get()!!).readFully(bytes)
        return bytes
    }

    /** Pictures are fetched with ?v=<artVersion>, so a long cache is safe: a new picture has a new URL. */
    private fun image(picture: Pair<InputStream, String>): Response =
        newChunkedResponse(Response.Status.OK, picture.second, picture.first).apply {
            addHeader("Cache-Control", "private, max-age=86400")
        }

    /** The admin page, or the web app (which also plays video and loads subtitles it converts in the browser). */
    private fun page(asset: String, media: Boolean): Response {
        val html = app.assets.open(asset).use { it.readBytes() }
        val mediaSrc = if (media) "media-src 'self' blob:; " else ""
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html.inputStream(), html.size.toLong()).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("X-Frame-Options", "DENY")
            addHeader(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: https://image.tmdb.org; $mediaSrc" +
                    "style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; frame-ancestors 'none'",
            )
        }
    }

    private fun <T> readJson(s: IHTTPSession, serializer: KSerializer<T>): T {
        val length = s.headers["content-length"]?.toLongOrNull() ?: 0
        if (length > 1_000_000) throw HttpError(413, "Body too large")
        val bytes = ByteArray(length.toInt())
        DataInputStream(bodies.get()!!).readFully(bytes)
        return try {
            ApiJson.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: Exception) {
            throw HttpError(400, "Bad JSON: ${e.message?.take(120)}")
        }
    }

    private fun <T> json(status: Int, serializer: KSerializer<T>, value: T): Response {
        return newFixedLengthResponse(Code(status), "application/json; charset=utf-8", ApiJson.encodeToString(serializer, value))
    }

    private fun error(status: Int, message: String): Response {
        return newFixedLengthResponse(
            Code(status), "application/json; charset=utf-8", ApiJson.encodeToString(ErrorBody.serializer(), ErrorBody(message)),
        )
    }

    private fun noContent(): Response {
        return newFixedLengthResponse(Code(204), "text/plain", "")
    }

    private class Code(private val code: Int) : Response.IStatus {
        override fun getDescription(): String = "$code ${REASONS[code] ?: "Status"}"
        override fun getRequestStatus(): Int = code
    }

    /** A window of the file for one response; closing it closes the file and lets the phone sleep again. */
    private class FileSlice(
        private val pfd: ParcelFileDescriptor,
        start: Long,
        private var left: Long,
        private val onClose: () -> Unit,
    ) : InputStream() {
        private val input = FileInputStream(pfd.fileDescriptor).also { it.channel.position(start) }
        private var closed = false

        override fun read(): Int {
            if (left <= 0) return -1
            return input.read().also { if (it >= 0) left-- }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { input.close() }
            runCatching { pfd.close() }
            onClose()
        }
    }

    private companion object {
        const val TAG = "HomeReel"
        const val PROFILE_HEADER = "x-profile"
        val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 206 to "Partial Content", 302 to "Found", 400 to "Bad Request",
            401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
            409 to "Conflict", 411 to "Length Required", 413 to "Payload Too Large", 416 to "Range Not Satisfiable",
            429 to "Too Many Requests", 500 to "Internal Server Error", 503 to "Service Unavailable",
            507 to "Insufficient Storage",
        )
    }
}
