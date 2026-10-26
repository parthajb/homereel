package com.parthajb.homereel.server

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import android.provider.DocumentsContract
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.MediaFiles
import com.parthajb.homereel.shared.TorrentItem
import com.parthajb.homereel.shared.TorrentList
import com.parthajb.homereel.shared.TorrentSettings
import com.parthajb.homereel.shared.Torrents
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.Vectors
import org.libtorrent4j.swig.byte_vector
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
private data class Record(
    val id: String,
    val name: String,
    val magnet: String? = null,
    val private: Boolean = false,
    val addedAt: Long,
    /** Paused by the admin, as opposed to waiting for the charger or the hours. */
    val paused: Boolean = false,
    /** Only the states that outlive the session: [Torrents.QUEUED], MOVING, DONE and ERROR. */
    val state: String = Torrents.QUEUED,
    val note: String? = null,
    val files: List<String> = emptyList(),
    val size: Long = 0,
    val finishedAt: Long? = null,
)

/**
 * Torrent downloads, run in the phone's own time: only while the settings allow (charging, the chosen hours, enough
 * space), slowed to a trickle while someone watches, and only the videos and subtitles in each torrent. A finished
 * download is copied into the library folder (or Private) and its own copy deleted; the phone doesn't keep sharing it.
 *
 * libtorrent only starts when there's something to download, so a phone that never uses torrents pays nothing. It
 * never opens ports on the router (UPnP and NAT-PMP are off).
 */
class TorrentManager(
    private val context: Context,
    private val prefs: Prefs,
    private val library: Library,
    private val locks: ActivityLocks,
    private val events: EventLog,
) {
    private val dir = File(context.filesDir, "torrents").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    /**
     * Downloads in progress, in the app's private storage. Not on shared storage: libtorrent opens and closes pieces
     * thousands of times, and Android's shared-storage layer (MediaProvider's FUSE) has crashed under that, taking
     * the server with it. On phones both live on the same partition, so there's as much room.
     */
    private val stage = File(context.noBackupFilesDir, "torrent-data").apply { mkdirs() }
    private val serializer = ListSerializer(Record.serializer())
    private val records = LinkedHashMap<String, Record>()

    /** Every libtorrent call that changes things runs here, one at a time. Lives as long as the app. */
    private val engine = Executors.newSingleThreadScheduledExecutor()
    private var timer: java.util.concurrent.ScheduledFuture<*>? = null
    private val details = Executors.newSingleThreadExecutor()
    private val mover = Executors.newSingleThreadExecutor()
    private var session: SessionManager? = null
    private val fetching = HashSet<String>()
    private var holdingLocks = false
    private var lastResumeSave = 0L

    @Volatile private var waiting: String? = null

    init {
        runCatching { if (indexFile.exists()) ApiJson.decodeFromString(serializer, indexFile.readText()).forEach { records[it.id] = it } }
        // A copy cut off by a restart starts again.
        for ((id, r) in records) if (r.state == Torrents.MOVING) records[id] = r.copy(state = Torrents.QUEUED)
    }

    var settings: TorrentSettings
        get() = prefs.torrentSettingsJson?.let { runCatching { ApiJson.decodeFromString(TorrentSettings.serializer(), it) }.getOrNull() }
            ?: TorrentSettings()
        private set(value) {
            prefs.torrentSettingsJson = ApiJson.encodeToString(TorrentSettings.serializer(), value)
        }

    fun start() {
        timer?.cancel(false)
        timer = engine.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { events.warn("Torrents: ${it.message}") } }, 2, 15, TimeUnit.SECONDS)
    }

    /** The server is stopping: saves where each download got to and closes libtorrent. */
    fun stop() {
        timer?.cancel(false)
        timer = null
        runCatching { engine.submit { stopSession() }.get(5, TimeUnit.SECONDS) }
    }

    // ---- What the admin page asks for ----

    fun list(includePrivate: Boolean): TorrentList = synchronized(this) {
        val items = records.values.filter { includePrivate || !it.private }.sortedByDescending { it.addedAt }.map(::item)
        TorrentList(settings, items, if (records.values.any(::pending)) waiting else null)
    }

    fun updateSettings(next: TorrentSettings): TorrentList {
        val clean = next.copy(
            startHour = next.startHour?.coerceIn(0, 23),
            endHour = next.endHour?.coerceIn(0, 23),
            maxDownKBps = next.maxDownKBps.coerceAtLeast(0),
            maxUpKBps = next.maxUpKBps.coerceAtLeast(0),
        )
        val was = settings.enabled
        settings = clean
        if (clean.enabled != was) events.info(if (clean.enabled) "Torrent downloads turned on" else "Torrent downloads turned off")
        engine.execute { tick() }
        return list(includePrivate = true)
    }

    fun add(bytes: ByteArray, private: Boolean): TorrentItem {
        requireEnabled()
        val info = runCatching { TorrentInfo(bytes) }.getOrElse { throw HttpError(400, "That isn't a .torrent file HomeReel can read") }
        val id = info.infoHash().toHex()
        val record = synchronized(this) {
            if (records.containsKey(id)) throw HttpError(409, "${info.name()} is already in Downloads")
            val (files, size) = wantedFiles(info)
            if (files.isEmpty()) throw HttpError(400, "${info.name()} has no videos HomeReel can play")
            checkSpace(size, info.name())
            File(dir, "$id.torrent").writeBytes(bytes)
            Record(id, info.name(), private = private, addedAt = System.currentTimeMillis(), files = files.map { it.second }, size = size)
                .also { records[id] = it; save() }
        }
        events.info("Torrent added: ${record.name} (${Fmt.size(record.size)})")
        engine.execute { tick() }
        return synchronized(this) { item(record) }
    }

    fun addMagnet(magnet: String, private: Boolean): TorrentItem {
        requireEnabled()
        if (!Torrents.isMagnet(magnet)) throw HttpError(400, "That isn't a magnet link (they start with magnet:?xt=urn:btih:)")
        val params = runCatching { AddTorrentParams.parseMagnetUri(magnet.trim()) }
            .getOrElse { throw HttpError(400, "HomeReel couldn't read that magnet link") }
        val id = params.infoHashes.best.toHex()
        val name = params.name?.takeIf { it.isNotBlank() } ?: "Magnet link"
        val record = synchronized(this) {
            if (records.containsKey(id)) throw HttpError(409, "$name is already in Downloads")
            Record(id, name, magnet = magnet.trim(), private = private, addedAt = System.currentTimeMillis()).also { records[id] = it; save() }
        }
        events.info("Magnet link added: $name")
        engine.execute { tick() }
        return synchronized(this) { item(record) }
    }

    /** Whether this torrent (by info hash) is already in Downloads, finished ones included. */
    fun has(id: String): Boolean = synchronized(this) { records.containsKey(id.lowercase()) }

    fun pause(id: String, paused: Boolean) {
        synchronized(this) {
            val r = records[id] ?: throw HttpError(404, "No such download")
            records[id] = r.copy(paused = paused)
            save()
            handle(id)?.let { h ->
                if (paused) {
                    h.unsetFlags(TorrentFlags.AUTO_MANAGED)
                    h.pause()
                } else {
                    h.setFlags(TorrentFlags.AUTO_MANAGED)
                    h.resume()
                }
            }
        }
        engine.execute { tick() }
    }

    /** Removes a download; anything it had downloaded is deleted. A finished one only leaves the list. */
    fun remove(id: String) {
        synchronized(this) {
            val r = records.remove(id) ?: throw HttpError(404, "No such download")
            save()
            handle(id)?.let { h -> runCatching { session?.remove(h, SessionHandle.DELETE_FILES) } }
            if (r.state != Torrents.DONE) events.info("Download removed: ${r.name}")
        }
        mover.execute { File(stage, id).deleteRecursively() }
        File(dir, "$id.torrent").delete()
        File(dir, "$id.resume").delete()
    }

    fun retry(id: String) {
        synchronized(this) {
            val r = records[id] ?: throw HttpError(404, "No such download")
            if (r.state != Torrents.ERROR) return
            records[id] = r.copy(state = Torrents.QUEUED, note = null)
            save()
        }
        engine.execute { tick() }
    }

    // ---- The engine: every 15 seconds, and after every change ----

    private fun tick() {
        val s = settings
        val todo = synchronized(this) { records.values.filter(::pending) }
        if (!s.enabled || todo.isEmpty()) {
            waiting = if (!s.enabled) "Torrent downloads are turned off in Settings." else null
            stopSession()
            return
        }
        val reason = whyWait(s)
        waiting = reason
        if (reason != null) {
            session?.let { if (!it.isPaused) it.pause() }
            releaseLocks()
            return
        }
        val sm = startSession() ?: return
        if (sm.isPaused) sm.resume()
        val watching = s.slowWhileWatching && locks.watching
        val down = s.maxDownKBps.let { if (watching) (if (it == 0) TRICKLE_KB else minOf(it, TRICKLE_KB)) else it }
        val up = if (watching) minOf(s.maxUpKBps.takeIf { it > 0 } ?: TRICKLE_UP_KB, TRICKLE_UP_KB) else s.maxUpKBps
        sm.downloadRateLimit(down * 1024)
        sm.uploadRateLimit(up * 1024)

        for (r in todo) {
            if (r.paused) continue
            val torrentFile = File(dir, "${r.id}.torrent")
            if (!torrentFile.exists()) {
                r.magnet?.let { fetchDetails(r.id, it) }
                continue
            }
            val h = handle(r.id) ?: addToSession(sm, r, torrentFile) ?: continue
            val st = h.status()
            if (st.totalWanted() > 0 && st.totalWantedDone() >= st.totalWanted() && r.state != Torrents.MOVING) moveIntoLibrary(r.id)
        }
        if (synchronized(this) { records.values.any { pending(it) && !it.paused } }) holdLocks() else releaseLocks()
        if (System.currentTimeMillis() - lastResumeSave > 2 * 60_000) {
            lastResumeSave = System.currentTimeMillis()
            for (r in todo) handle(r.id)?.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
        }
    }

    /** Why downloads wait right now, in words for the admin page; null when they may run. */
    private fun whyWait(s: TorrentSettings): String? {
        if (library.tree == null) return "Choose the movie folder in the HomeReel app on the phone first."
        if (s.onlyWhileCharging && context.getSystemService(BatteryManager::class.java)?.isCharging != true) {
            return "Waiting for the phone to be charging."
        }
        if (!Torrents.inHours(s.startHour, s.endHour, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) {
            return "Waiting for ${"%02d:00".format(s.startHour)}; downloads run from then until ${"%02d:00".format(s.endHour)}."
        }
        if (freeBytes() < MIN_FREE) return "Paused: less than 1 GB free on the phone."
        return null
    }

    private fun startSession(): SessionManager? {
        session?.let { return it }
        return try {
            val pack = SettingsPack()
                .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
                .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
                .activeDownloads(2)
                .activeSeeds(0)
                .connectionsLimit(80)
            val sm = SessionManager(false)
            sm.addListener(alerts)
            sm.start(SessionParams(pack))
            session = sm
            events.info("Torrent engine started")
            sm
        } catch (e: Throwable) {
            // UnsatisfiedLinkError on a processor libtorrent wasn't built for.
            waiting = "Torrents can't run on this phone: ${e.message}"
            null
        }
    }

    private fun stopSession() {
        val sm = session ?: return releaseLocks()
        runCatching {
            synchronized(this) { records.keys.mapNotNull(::handle) }.forEach { it.saveResumeData(TorrentHandle.SAVE_INFO_DICT) }
            Thread.sleep(1500) // the resume data arrives as alerts
            sm.stop()
        }
        session = null
        releaseLocks()
        events.info("Torrent engine stopped")
    }

    private fun addToSession(sm: SessionManager, r: Record, torrentFile: File): TorrentHandle? {
        val info = runCatching { TorrentInfo(torrentFile) }.getOrElse {
            fail(r.id, "The .torrent file is damaged. Remove it and add it again.")
            return null
        }
        val wanted = wantedFiles(info).first.map { it.first }.toSet()
        val priorities = byte_vector().apply {
            for (i in 0 until info.numFiles()) add((if (i in wanted) Priority.DEFAULT else Priority.IGNORE).swig())
        }
        // libtorrent 2.1 keeps a torrent's trackers and web seeds out of the parsed torrent, so it's loaded with
        // load_torrent_file (SessionManager.download would lose them). Saved progress comes from the resume data.
        val fromFile = runCatching { libtorrent.load_torrent_file(torrentFile.absolutePath) }.getOrElse {
            fail(r.id, "The .torrent file is damaged. Remove it and add it again.")
            return null
        }
        val params = File(dir, "${r.id}.resume").takeIf { it.exists() }?.let { f ->
            val ec = error_code()
            libtorrent.read_resume_data_ex(Vectors.bytes2byte_vector(f.readBytes()), ec).takeIf { ec.value() == 0 }
        }?.also { resumed ->
            if (resumed.trackers.isEmpty) resumed.trackers = fromFile.trackers
            if (resumed.url_seeds.isEmpty) resumed.url_seeds = fromFile.url_seeds
        } ?: fromFile
        params.save_path = File(stage, r.id).apply { mkdirs() }.absolutePath
        params.set_file_priorities(priorities)
        sm.swig().async_add_torrent(params)
        return null // the handle exists once libtorrent has added it; the next tick picks it up
    }

    /** A magnet link has no file list until other people send it; asked for once at a time, for up to 2 minutes. */
    private fun fetchDetails(id: String, magnet: String) {
        val sm = session ?: return
        synchronized(this) { if (!fetching.add(id)) return }
        details.execute {
            try {
                val bytes = runCatching { sm.fetchMagnet(magnet, 120, File(stage, "magnets").apply { mkdirs() }) }.getOrNull()
                val r = synchronized(this) { records[id] } ?: return@execute
                if (bytes == null) {
                    note(id, "Looking for people sharing it. HomeReel keeps trying.")
                    return@execute
                }
                val info = TorrentInfo(bytes)
                val (files, size) = wantedFiles(info)
                if (files.isEmpty()) {
                    fail(id, "${info.name()} has no videos HomeReel can play.")
                    return@execute
                }
                File(dir, "$id.torrent").writeBytes(bytes)
                synchronized(this) {
                    records[id] = r.copy(name = info.name(), files = files.map { it.second }, size = size, note = null)
                    save()
                }
                if (freeBytes() < size + MIN_FREE) note(id, "Needs ${Fmt.size(size)}; make room on the phone and it carries on.")
                engine.execute { tick() }
            } finally {
                synchronized(this) { fetching.remove(id) }
            }
        }
    }

    // ---- Into the library ----

    private fun moveIntoLibrary(id: String) {
        val r = synchronized(this) {
            val r = records[id] ?: return
            records[id] = r.copy(state = Torrents.MOVING, note = null)
            save()
            records[id]!!
        }
        mover.execute {
            try {
                val added = copyFiles(r)
                engine.execute {
                    synchronized(this) {
                        handle(id)?.let { h -> runCatching { session?.remove(h, SessionHandle.DELETE_FILES) } }
                        records[id] = r.copy(state = Torrents.DONE, files = added, finishedAt = System.currentTimeMillis())
                        save()
                    }
                    File(stage, id).deleteRecursively()
                    File(dir, "$id.torrent").delete()
                    File(dir, "$id.resume").delete()
                    library.requestScan()
                    events.info("Downloaded ${r.name}: added ${added.joinToString()}${if (r.private) " to Private" else ""}")
                    tick()
                }
            } catch (e: Exception) {
                fail(id, "Couldn't copy it into the library: ${e.message}. Free some space, then Try again.")
            }
        }
    }

    /** Copies the finished videos and subtitles into the library (or Private), each as "name.part" renamed when whole. */
    private fun copyFiles(r: Record): List<String> {
        val tree = library.tree ?: throw IllegalStateException("no movie folder is chosen")
        val info = TorrentInfo(File(dir, "${r.id}.torrent"))
        val files = wantedFiles(info).first
        val (free, _) = Saf.space(tree)
        val biggest = files.maxOfOrNull { info.files().fileSize(it.first) } ?: 0
        if (free != null && free < biggest + 200L * 1024 * 1024) throw IllegalStateException("not enough space in the movie folder")
        val cr = context.contentResolver
        val parent = if (r.private) library.privateFolder(tree, create = true)!! else Saf.rootDocId(tree)
        val taken = Saf.children(cr, tree, parent).map { it.name }.toMutableSet()
        val added = ArrayList<String>()
        for ((index, _) in files) {
            val source = File(File(stage, r.id), info.files().filePath(index))
            val clean = MediaFiles.safeUploadName(source.name) ?: continue
            val name = Torrents.freeName(clean, taken)
            val part = DocumentsContract.createDocument(cr, Saf.docUri(tree, parent), "application/octet-stream", "$name.part")
                ?: throw IllegalStateException("the phone wouldn't create $name")
            try {
                cr.openOutputStream(part)!!.use { out -> source.inputStream().use { it.copyTo(out, 256 * 1024) } }
                DocumentsContract.renameDocument(cr, part, name) ?: throw IllegalStateException("the phone wouldn't rename $name")
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(cr, part) }
                throw e
            }
            taken += name
            added += name
        }
        return added
    }

    // ---- Helpers ----

    private val alerts = object : AlertListener {
        override fun types() = intArrayOf(
            AlertType.TORRENT_FINISHED.swig(), AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.TORRENT_ERROR.swig(), AlertType.FILE_ERROR.swig(),
        )

        override fun alert(alert: Alert<*>) {
            val id = (alert as? TorrentAlert<*>)?.handle()?.infoHash()?.toHex() ?: return
            when (alert) {
                is SaveResumeDataAlert -> runCatching {
                    if (synchronized(this@TorrentManager) { records.containsKey(id) }) {
                        File(dir, "$id.resume").writeBytes(AddTorrentParams.writeResumeDataBuf(alert.params()))
                    }
                }
                is FileErrorAlert -> note(id, "Couldn't write ${alert.filename().substringAfterLast('/')}: ${alert.error().message}")
                is TorrentErrorAlert -> note(id, alert.error().message)
                else -> if (alert.type() == AlertType.TORRENT_FINISHED) engine.execute { tick() }
            }
        }
    }

    private fun handle(id: String): TorrentHandle? =
        session?.find(Sha1Hash.parseHex(id))?.takeIf { it.isValid }

    /** The file indexes and names worth downloading, and their total size. */
    private fun wantedFiles(info: TorrentInfo): Pair<List<Pair<Int, String>>, Long> {
        val fs = info.files()
        val largest = (0 until fs.numFiles()).filter { MediaFiles.isVideo(fs.filePath(it)) }.maxOfOrNull { fs.fileSize(it) } ?: 0
        val files = (0 until fs.numFiles()).filter { Torrents.wanted(fs.filePath(it), fs.fileSize(it), largest) }
            .map { it to fs.filePath(it).substringAfterLast('/') }
        return files to files.sumOf { fs.fileSize(it.first) }
    }

    private fun pending(r: Record) = r.state != Torrents.DONE && r.state != Torrents.ERROR

    private fun requireEnabled() {
        if (!settings.enabled) throw HttpError(409, "Turn on torrent downloads in Settings first")
    }

    private fun checkSpace(size: Long, name: String) {
        val free = freeBytes()
        if (free < size + MIN_FREE) {
            throw HttpError(507, "$name needs ${Fmt.size(size)}, and the phone has ${Fmt.size(free)} free. Keep at least 1 GB spare.")
        }
    }

    private fun freeBytes(): Long = runCatching { StatFs(stage.path).availableBytes }.getOrDefault(0)

    private fun note(id: String, text: String?) = synchronized(this) {
        records[id]?.let { records[id] = it.copy(note = text); save() }
    }

    private fun fail(id: String, text: String) {
        synchronized(this) {
            val r = records[id] ?: return
            records[id] = r.copy(state = Torrents.ERROR, note = text)
            save()
            handle(id)?.let { h -> h.unsetFlags(TorrentFlags.AUTO_MANAGED); h.pause() }
            events.warn("Download failed: ${r.name}: $text")
        }
    }

    private fun item(r: Record): TorrentItem {
        val st = if (r.state == Torrents.QUEUED) handle(r.id)?.status() else null
        val state = when {
            r.state != Torrents.QUEUED -> r.state
            r.paused -> Torrents.PAUSED
            !File(dir, "${r.id}.torrent").exists() -> Torrents.DETAILS
            st != null && waiting == null && !(session?.isPaused ?: true) -> Torrents.DOWNLOADING
            else -> Torrents.QUEUED
        }
        val checking = st?.state() == TorrentStatus.State.CHECKING_FILES || st?.state() == TorrentStatus.State.CHECKING_RESUME_DATA
        return TorrentItem(
            id = r.id,
            name = r.name,
            size = st?.totalWanted()?.takeIf { it > 0 } ?: r.size,
            done = if (r.state == Torrents.DONE || r.state == Torrents.MOVING) r.size else st?.totalWantedDone() ?: 0,
            downRate = if (state == Torrents.DOWNLOADING) st?.downloadRate()?.toLong() ?: 0 else 0,
            upRate = if (state == Torrents.DOWNLOADING) st?.uploadRate()?.toLong() ?: 0 else 0,
            peers = st?.numPeers() ?: 0,
            state = state,
            note = r.note ?: if (checking) "Checking what's already downloaded…" else null,
            files = r.files,
            private = r.private,
            addedAt = r.addedAt,
            finishedAt = r.finishedAt,
        )
    }

    private fun holdLocks() {
        // Ended and begun again each time, so the 4-hour wake lock is renewed for a long download.
        if (holdingLocks) locks.end()
        locks.begin()
        holdingLocks = true
    }

    private fun releaseLocks() {
        if (!holdingLocks) return
        locks.end()
        holdingLocks = false
    }

    private fun save() {
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(ApiJson.encodeToString(serializer, records.values.toList()))
        tmp.renameTo(indexFile)
    }

    private companion object {
        const val MIN_FREE = 1024L * 1024 * 1024
        const val TRICKLE_KB = 300
        const val TRICKLE_UP_KB = 10
    }
}

private object Fmt {
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }
}
