package com.parthajb.homereel.tv

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.Kind
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.PlayabilityItem
import com.parthajb.homereel.shared.PlayabilityReport
import com.parthajb.homereel.shared.Show
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The home screen: a big backdrop and the focused title's details at the top, rows of posters underneath.
 * Rows: Continue watching, Recently added, Top rated, one per genre, then everything A–Z, and Settings.
 */
class MainActivity : FragmentActivity(), CardHost {
    private val store by lazy { ServerStore(this) }
    private val codecs by lazy { TvCodecs(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val adapter = RowsAdapter(this)
    private var cantPlayReasons: Map<String, String> = emptyMap()
    private var lastReport: List<PlayabilityItem>? = null
    private var movies: List<Movie> = emptyList()
    private var shows: List<Show> = emptyList()
    private var privateUntil: Long? = null
    private var loading = false
    private var firstLoad = true
    private var focusedKey: String? = null

    private lateinit var rows: RecyclerView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var genres: TextView
    private lateinit var description: TextView
    private lateinit var backdrop: Backdrop

    override val api: ApiClient get() = store.api()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!store.paired) {
            startActivity(Intent(this, ConnectActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_home)
        rows = findViewById(R.id.rows)
        title = findViewById(R.id.hero_title)
        meta = findViewById(R.id.hero_meta)
        genres = findViewById(R.id.hero_genres)
        description = findViewById(R.id.hero_description)
        backdrop = Backdrop(findViewById(R.id.backdrop_a), findViewById(R.id.backdrop_b)) { store.api() }
        // "HomeReel on Redmi Note 9 Pro" reads "HOMEREEL · REDMI NOTE 9 PRO" in the brand line.
        val phone = store.name?.removePrefix("HomeReel on ")?.trim()
        findViewById<TextView>(R.id.brand).text = if (phone.isNullOrEmpty()) getString(R.string.app_name) else getString(R.string.brand_line, phone)

        rows.layoutManager = StartAlignLayoutManager(this, RecyclerView.VERTICAL)
        rows.itemAnimator = null
        rows.adapter = adapter
        hero(getString(R.string.loading), null, null, null)
    }

    override fun onResume() {
        super.onResume()
        if (store.paired) load()
    }

    // ---- Data ----

    private fun load(afterRediscovery: Boolean = false) {
        if (loading) return
        loading = true
        val api = store.api()
        worker.execute {
            try {
                val list = api.movies()
                val showList = api.shows()
                val until = runCatching { api.info().privateUntil }.getOrNull()
                val report = list.mapNotNull { m -> codecs.reason(m)?.let { PlayabilityItem(m.id, it) } }
                main.post {
                    loading = false
                    if (isDestroyed) return@post
                    movies = list
                    shows = showList
                    privateUntil = until
                    cantPlayReasons = report.associate { it.id to it.reason }
                    show(list)
                }
                if (report != lastReport) {
                    runCatching { api.report(PlayabilityReport("TV: ${Build.MODEL}", report)) }
                    lastReport = report
                }
            } catch (e: Unauthorized) {
                main.post {
                    loading = false
                    store.forget()
                    Toast.makeText(this, R.string.pair_again, Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, ConnectActivity::class.java))
                    finish()
                }
            } catch (e: IOException) {
                main.post {
                    loading = false
                    if (isDestroyed) return@post
                    if (afterRediscovery) showOffline(e.message) else rediscover(e.message)
                }
            }
        }
    }

    /** The phone may have a new address after a router restart: look for it by name before giving up. */
    private fun rediscover(error: String?) {
        val name = store.name ?: return showOffline(error)
        var done = false
        lateinit var discovery: Discovery
        discovery = Discovery(this) { found ->
            if (done || found.name != name) return@Discovery
            done = true
            discovery.stop()
            store.moveTo(found.host, found.port)
            load(afterRediscovery = true)
        }
        discovery.start()
        main.postDelayed({
            if (!done) {
                done = true
                discovery.stop()
                if (!isDestroyed) showOffline(error)
            }
        }, 6000)
    }

    private fun show(list: List<Movie>) {
        val built = buildRows(list)
        val changed = adapter.submit(built)
        if (list.isEmpty() && shows.isEmpty()) {
            hero(getString(R.string.empty_title), null, null, getString(R.string.empty_body, "http://${store.host}:${store.port}/admin"))
        }
        if (firstLoad || changed) {
            firstLoad = false
            restoreFocus()
        }
    }

    private fun showOffline(error: String?) {
        adapter.submit(
            listOf(
                HomeRow(
                    getString(R.string.row_offline),
                    listOf(
                        ActionItem(ACTION_REFRESH, getString(R.string.action_retry), R.drawable.ic_refresh),
                        ActionItem(ACTION_SWITCH, getString(R.string.action_switch), R.drawable.ic_phone),
                    ),
                ),
            ),
        )
        hero(getString(R.string.offline_title, store.name ?: ""), null, null, getString(R.string.offline_body, error ?: ""))
        restoreFocus()
    }

    /** A film or a show, for the rows that mix them: newest, best rated, by genre. */
    private class Title(val item: HomeItem, val addedAt: Long, val rating: Double?, val genres: List<String>, val name: String)

    private fun buildRows(all: List<Movie>): List<HomeRow> {
        val out = ArrayList<HomeRow>()
        // Private videos only ever appear in their own row, so locking Private again leaves no trace elsewhere.
        val list = all.filter { !it.private }
        val privateOnes = all.filter { it.private }
        val watching = list.filter { inProgress(it) }.map { it.lastPlayedAt to MovieItem(it, landscape = true) } +
            shows.mapNotNull { show ->
                show.upNext?.let { ep ->
                    show.lastPlayedAt to MovieItem(ep, landscape = true, caption = getString(R.string.episode_caption, show.title, Format.episodeCode(ep)))
                }
            }
        if (watching.isNotEmpty()) out += HomeRow(getString(R.string.row_continue), watching.sortedByDescending { it.first }.map { it.second })
        val titles = list.map { Title(MovieItem(it), it.addedAt, it.imdbRating, it.genres, it.title) } +
            shows.map { Title(ShowItem(it), it.addedAt, it.imdbRating, it.genres, it.title) }
        if (titles.size > 4) {
            out += HomeRow(getString(R.string.row_recent), titles.sortedByDescending { it.addedAt }.take(20).map { it.item })
        }
        if (shows.isNotEmpty()) {
            out += HomeRow(getString(R.string.row_shows), shows.sortedByDescending { it.lastPlayedAt.coerceAtLeast(it.addedAt) }.map { ShowItem(it) })
        }
        val rated = titles.filter { it.rating != null }.sortedByDescending { it.rating }
        if (rated.size >= 2) out += HomeRow(getString(R.string.row_top_rated), rated.take(20).map { it.item })
        titles.flatMap { it.genres }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(12)
            .forEach { (genre, _) ->
                val inGenre = titles.filter { genre in it.genres }
                    .sortedWith(compareByDescending<Title> { it.rating ?: 0.0 }.thenBy { it.name.lowercase() })
                out += HomeRow(genre, inGenre.map { it.item })
            }
        if (privateOnes.isNotEmpty()) {
            val until = privateUntil?.let { android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(it)) } ?: ""
            out += HomeRow(
                getString(R.string.row_private, until),
                privateOnes.sortedByDescending { it.addedAt }.map { MovieItem(it, landscape = true) },
            )
        }
        val sorted = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { sortKey(it.title) })
        if (sorted.size <= 60) {
            if (sorted.isNotEmpty()) out += HomeRow(getString(R.string.row_all), sorted.map { MovieItem(it) })
        } else {
            sorted.groupBy { letter(it.title) }.forEach { (letter, group) ->
                out += HomeRow(getString(R.string.row_letter, letter), group.map { MovieItem(it) })
            }
        }
        out += HomeRow(
            getString(R.string.row_settings),
            listOf(
                ActionItem(ACTION_REFRESH, getString(R.string.action_refresh), R.drawable.ic_refresh),
                ActionItem(ACTION_RESCAN, getString(R.string.action_rescan), R.drawable.ic_folder),
                ActionItem(ACTION_ABOUT, getString(R.string.action_about), R.drawable.ic_info),
                ActionItem(ACTION_SWITCH, getString(R.string.action_switch), R.drawable.ic_phone),
            ),
        )
        return out
    }

    // ---- Focus and the hero area ----

    /** Puts focus back on the card it was on (by movie), or on the first card. */
    private fun restoreFocus(attempt: Int = 0) {
        rows.post {
            val key = focusedKey
            val rowIndex = adapter.rows.indexOfFirst { row -> row.items.any { it.key == key } }.takeIf { it >= 0 } ?: 0
            if (adapter.rows.isEmpty()) return@post
            rows.scrollToPosition(rowIndex)
            rows.post {
                val holder = rows.findViewHolderForAdapterPosition(rowIndex) as? RowsAdapter.RowHolder
                if (holder == null) {
                    if (attempt < 5) restoreFocus(attempt + 1)
                    return@post
                }
                val cardIndex = adapter.rows[rowIndex].items.indexOfFirst { it.key == key }.coerceAtLeast(0)
                holder.list.scrollToPosition(cardIndex)
                holder.list.post {
                    val card = holder.list.findViewHolderForAdapterPosition(cardIndex)?.itemView ?: holder.list.getChildAt(0)
                    card?.requestFocus()
                }
            }
        }
    }

    override fun onFocused(item: HomeItem) {
        focusedKey = item.key
        when (item) {
            is ShowItem -> {
                val show = item.show
                val body = show.upNext?.let { getString(R.string.episode_caption, getString(R.string.next_episode), "${Format.episodeCode(it)} ${it.title}") }
                    ?.let { next -> listOfNotNull(next, show.description).joinToString("\n") } ?: show.description
                hero(show.title, Format.showMeta(this, show), Format.genres(show).ifEmpty { null }, body)
                backdrop.showArt(show.art())
            }
            is MovieItem -> if (item.movie.kind == Kind.EPISODE) {
                val m = item.movie
                hero(m.showTitle ?: m.title, "${Format.episodeCode(m)}   ${m.title}", null, m.description)
                backdrop.show(m)
            } else {
                val m = item.movie
                val body = when {
                    cantPlay(m.id) -> getString(R.string.cant_play_line, cantPlayReasons[m.id])
                    !m.description.isNullOrBlank() -> m.description
                    inProgress(m) -> getString(R.string.resume_line, Format.clock(m.positionMs))
                    else -> null
                }
                hero(m.title, Format.meta(this, m), Format.genres(m).ifEmpty { null }, body)
                backdrop.show(m)
            }
            is ActionItem -> {
                hero(item.label, null, null, actionHelp(item.id))
                backdrop.show(null)
            }
        }
    }

    private fun hero(heading: String, metaLine: CharSequence?, genreLine: String?, body: String?) {
        title.text = heading
        meta.text = metaLine
        meta.visibility = if (metaLine.isNullOrEmpty()) View.GONE else View.VISIBLE
        genres.text = genreLine
        genres.visibility = if (genreLine.isNullOrEmpty()) View.GONE else View.VISIBLE
        description.text = body
        description.visibility = if (body.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun actionHelp(id: Int): String = when (id) {
        ACTION_REFRESH -> getString(R.string.help_refresh)
        ACTION_RESCAN -> getString(R.string.help_rescan, "http://${store.host}:${store.port}/admin")
        ACTION_ABOUT -> getString(R.string.help_about)
        else -> getString(R.string.help_switch)
    }

    // ---- Actions ----

    override fun cantPlay(id: String) = cantPlayReasons.containsKey(id)

    override fun onClicked(item: HomeItem) {
        when (item) {
            // An episode card (Continue watching) plays straight away, like a streaming app.
            is MovieItem -> if (item.movie.kind == Kind.EPISODE) {
                onPlay(item.movie)
            } else {
                startActivity(Intent(this, DetailsActivity::class.java).putExtra(DetailsActivity.EXTRA_ID, item.movie.id))
            }
            is ShowItem -> startActivity(Intent(this, ShowActivity::class.java).putExtra(ShowActivity.EXTRA_ID, item.show.id))
            is ActionItem -> onAction(item.id)
        }
    }

    override fun onPlay(movie: Movie) = play(this, movie, if (inProgress(movie)) movie.positionMs else 0)

    private fun onAction(id: Int) {
        when (id) {
            ACTION_REFRESH -> {
                Images.clear()
                load()
            }
            ACTION_RESCAN -> worker.execute {
                val ok = runCatching { store.api().rescan() }.isSuccess
                main.post {
                    Toast.makeText(this, if (ok) R.string.rescan_started else R.string.rescan_failed, Toast.LENGTH_SHORT).show()
                    main.postDelayed({ if (!isDestroyed) load() }, 4000)
                }
            }
            ACTION_ABOUT -> AlertDialog.Builder(this)
                .setTitle(store.name)
                .setMessage(getString(R.string.about, "${store.host}:${store.port}", movies.size, cantPlayReasons.size, BuildConfig.VERSION_NAME))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            ACTION_SWITCH -> {
                store.forget()
                startActivity(Intent(this, ConnectActivity::class.java))
                finish()
            }
        }
    }

    /** Back from deep in the rows goes to the top first, as streaming apps do; Back at the top leaves. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val focusedRow = rows.focusedChild?.let { rows.getChildAdapterPosition(it) } ?: 0
        val firstKey = adapter.rows.firstOrNull()?.items?.firstOrNull()?.key
        if (focusedRow > 0 || (focusedKey != null && focusedKey != firstKey)) {
            focusedKey = null
            restoreFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_REFRESH = 1
        private const val ACTION_RESCAN = 2
        private const val ACTION_ABOUT = 3
        private const val ACTION_SWITCH = 4

        /** Started but not finished: more than 15 seconds or 2% in, and not marked watched. */
        fun inProgress(m: Movie) = !m.watched && m.positionMs > 0 &&
            (m.positionMs > 15_000 || (m.durationMs > 0 && m.positionMs > m.durationMs / 50))

        fun play(activity: FragmentActivity, movie: Movie, fromMs: Long) {
            activity.startActivity(
                Intent(activity, PlaybackActivity::class.java)
                    .putExtra(PlaybackActivity.EXTRA_MOVIE, ApiJson.encodeToString(Movie.serializer(), movie))
                    .putExtra(PlaybackActivity.EXTRA_START, fromMs),
            )
        }

        private fun sortKey(title: String): String = title.removePrefix("The ").removePrefix("A ").trim()

        private fun letter(title: String): String {
            val c = sortKey(title).firstOrNull()?.uppercaseChar() ?: '#'
            return if (c in 'A'..'Z') c.toString() else "#"
        }
    }
}
