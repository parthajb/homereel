package com.parthajb.homereel.tv

import android.app.Activity
import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.Kind
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.PlayNext
import com.parthajb.homereel.shared.VoiceSearch
import java.io.File
import java.io.FileNotFoundException

/**
 * Continue watching on the TV's own home screen: Android TV's Play next row. Filled in from the phone while HomeReel
 * is open, never in the background. Does nothing on a TV whose home screen has no such row.
 */
class PlayNextRow(private val context: Context) {
    private val cr = context.contentResolver

    /** Android TV's programme list, where the row lives; absent on phones and some TVs. */
    private val supported: Boolean by lazy {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) &&
            context.packageManager.resolveContentProvider(TvContract.AUTHORITY, 0) != null
    }

    /** One title to carry on with: a film, or a show's next episode. */
    class Item(val key: String, val movie: Movie, val showTitle: String?, val lastPlayedAt: Long)

    /** Makes the row match [items], the most recently played first. Runs on a worker thread. */
    fun publish(api: ApiClient, items: List<Item>) {
        if (!supported) return
        try {
            val existing = existing()
            val plan = PlayNext.plan(items.map { it.key }, existing)
            plan.remove.forEach { cr.delete(TvContract.buildWatchNextProgramUri(it), null, null) }
            val byKey = items.associateBy { it.key }
            for ((programId, key) in plan.update) byKey[key]?.let { cr.update(TvContract.buildWatchNextProgramUri(programId), values(api, it), null, null) }
            for (key in plan.add) byKey[key]?.let { cr.insert(TvContract.WatchNextPrograms.CONTENT_URI, values(api, it)) }
            // Pictures of titles no longer in the row aren't needed.
            val keep = items.map { artName(it.key) }.toSet()
            ArtProvider.dir(context).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't update the Play next row", e)
        }
    }

    /** Empties the row, for a kids profile or a TV that's no longer paired. */
    fun clear() {
        if (!supported) return
        runCatching { existing().forEach { cr.delete(TvContract.buildWatchNextProgramUri(it.programId), null, null) } }
            .onFailure { Log.w(TAG, "Couldn't empty the Play next row", it) }
    }

    /** The rows HomeReel put there; the TV only shows an app its own. */
    private fun existing(): List<PlayNext.Existing> {
        val columns = arrayOf(TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, TvContract.WatchNextPrograms.COLUMN_BROWSABLE)
        val out = ArrayList<PlayNext.Existing>()
        cr.query(TvContract.WatchNextPrograms.CONTENT_URI, columns, null, null, null)?.use { c: Cursor ->
            while (c.moveToNext()) {
                val key = c.getString(1) ?: continue
                out += PlayNext.Existing(c.getLong(0), key, c.getInt(2) == 1)
            }
        }
        return out
    }

    private fun values(api: ApiClient, item: Item): ContentValues {
        val m = item.movie
        val episode = m.kind == Kind.EPISODE
        return ContentValues().apply {
            put(TvContract.WatchNextPrograms.COLUMN_TYPE, if (episode) TvContract.WatchNextPrograms.TYPE_TV_EPISODE else TvContract.WatchNextPrograms.TYPE_MOVIE)
            put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            put(TvContract.WatchNextPrograms.COLUMN_TITLE, if (episode) item.showTitle ?: m.title else m.title)
            if (episode) {
                put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, m.title)
                m.season?.let { put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, it.toString()) }
                m.episode?.let { put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, it.toString()) }
            }
            m.description?.let { put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, it.take(300)) }
            put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, item.key)
            put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, item.lastPlayedAt)
            if (m.durationMs > 0) put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, m.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, m.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val link = Intent(Intent.ACTION_VIEW, Uri.parse(PlayNext.link(item.key))).setPackage(context.packageName)
            put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, link.toUri(Intent.URI_INTENT_SCHEME))
            art(api, item)?.let {
                put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, it.toString())
                put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9)
            }
        }
    }

    /** The title's wide picture, kept on the TV so the home screen can show it without the phone's pairing token. */
    private fun art(api: ApiClient, item: Item): Uri? {
        val m = item.movie
        if (!m.hasBackdrop && !m.hasPoster) return null
        val name = artName(item.key)
        val file = File(ArtProvider.dir(context), name)
        val stamp = File(ArtProvider.dir(context), "$name.v")
        val version = "${m.id}:${m.artVersion}"
        if (!file.exists() || stamp.takeIf { it.exists() }?.readText() != version) {
            val path = if (m.hasBackdrop) "/movies/${m.id}/backdrop?v=${m.artVersion}" else "/movies/${m.id}/poster?v=${m.artVersion}"
            val bytes = api.bytes(path) ?: return null
            file.writeBytes(bytes)
            stamp.writeText(version)
        }
        return Uri.parse("content://${context.packageName}.playnext/${name}?v=${m.artVersion}")
    }

    private fun artName(key: String) = key.replace(':', '-') + ".jpg"

    /** Pictures for the Play next row, read-only, and only the ones HomeReel put there. */
    class ArtProvider : ContentProvider() {
        override fun onCreate() = true

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (mode != "r") throw SecurityException("Read only")
            val name = uri.lastPathSegment?.takeIf { it.matches(Regex("[ms]-[A-Za-z0-9_-]{1,64}\\.jpg")) } ?: throw FileNotFoundException()
            val file = File(dir(context!!), name)
            if (!file.exists()) throw FileNotFoundException()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun getType(uri: Uri) = "image/jpeg"
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

        companion object {
            fun dir(context: Context) = File(context.cacheDir, "playnext").apply { mkdirs() }
        }
    }

    /**
     * Opens what a Play next row or a search suggestion points at: a film's page, or a show's. "Play … on HomeReel"
     * plays the clear winner, or shows HomeReel's search when it isn't clear. Anything else opens HomeReel's home.
     */
    class LinkActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val paired = ServerStore(this).host != null
            val home = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
                startActivities(arrayOf(home) + listOfNotNull(if (paired) playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty()) else null))
                finish()
                return
            }
            val key = PlayNext.keyOf(intent?.dataString)
            val page = when {
                key == null || !paired -> null
                key.startsWith("m:") -> Intent(this, DetailsActivity::class.java).putExtra(DetailsActivity.EXTRA_ID, key.removePrefix("m:"))
                else -> Intent(this, ShowActivity::class.java).putExtra(ShowActivity.EXTRA_ID, key.removePrefix("s:"))
            }
            // HomeReel's home underneath, so Back from the page goes there rather than out of the app.
            if (page == null) startActivity(home) else startActivities(arrayOf(home, page))
            finish()
        }

        /** The film, or a show's next episode, to play; else HomeReel's search with what was said. */
        private fun playFromSearch(query: String): Intent {
            val (movies, shows) = LibraryCache.load(this)
            val hit = VoiceSearch.toPlay(query, movies, shows)
            val film = hit?.movie ?: hit?.show?.upNext
            if (film != null) {
                return Intent(this, PlaybackActivity::class.java)
                    .putExtra(PlaybackActivity.EXTRA_MOVIE, ApiJson.encodeToString(Movie.serializer(), film))
                    .putExtra(PlaybackActivity.EXTRA_START, if (MainActivity.inProgress(film)) film.positionMs else 0L)
            }
            hit?.show?.let { return Intent(this, ShowActivity::class.java).putExtra(ShowActivity.EXTRA_ID, it.id) }
            return Intent(this, SearchActivity::class.java).putExtra(SearchManager.QUERY, query)
        }
    }

    private companion object {
        const val TAG = "HomeReel"
    }
}
