package com.parthajb.homereel.tv

import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.AppUpdate
import com.parthajb.homereel.shared.Collections
import com.parthajb.homereel.shared.Kind
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.PlayabilityItem
import com.parthajb.homereel.shared.PlayabilityReport
import com.parthajb.homereel.shared.Recommend
import com.parthajb.homereel.shared.Show
import com.parthajb.homereel.shared.Updates
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * The home screen: a big backdrop and the focused title's details at the top, rows of posters underneath.
 * Rows: Continue watching, Recently added, Top rated, one per genre, then everything A–Z, and Settings.
 * The menu along the top switches to just Movies, TV shows, a genre, My List or Private.
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
    private var myList: List<String> = emptyList()
    private var privateUntil: Long? = null
    private var update: AppUpdate? = null
    private var loading = false
    private var firstLoad = true
    private var focusedKey: String? = null

    /** The section the top menu has chosen, and the genre when it's Genres. */
    private var section = Section.HOME
    private var genre: String? = null
    private var genreNames: List<String> = emptyList()

    /** The collection chosen among the Genres page's Collections tiles, instead of a genre. */
    private var collection: String? = null
    private var collectionNames: List<String> = emptyList()
    private lateinit var nav: LinearLayout

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
        if (!Session.profileChosen) {
            startActivity(Intent(this, ProfileActivity::class.java))
            finish()
            return
        }
        setContentView(R.layout.activity_home)
        rows = findViewById(R.id.rows)
        nav = findViewById(R.id.nav)
        title = findViewById(R.id.hero_title)
        meta = findViewById(R.id.hero_meta)
        genres = findViewById(R.id.hero_genres)
        description = findViewById(R.id.hero_description)
        backdrop = Backdrop(findViewById(R.id.backdrop_a), findViewById(R.id.backdrop_b)) { store.api() }
        // "HOMEREEL · ANU": whose home screen this is.
        val who = store.profileName ?: store.name?.removePrefix("HomeReel on ")?.trim()
        findViewById<TextView>(R.id.brand).text = if (who.isNullOrEmpty()) getString(R.string.app_name) else getString(R.string.brand_line, who)
        findViewById<View>(R.id.top_search).setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }
        findViewById<TextView>(R.id.top_profile).apply {
            text = store.profileName ?: getString(R.string.action_profile)
            setOnClickListener { switchProfile() }
        }

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
                val listKeys = runCatching { api.myList() }.getOrDefault(emptyList())
                val until = runCatching { api.info().privateUntil }.getOrNull()
                val newer = runCatching { api.update() }.getOrNull()?.takeIf { Updates.isNewer(it, BuildConfig.VERSION_CODE.toLong()) }
                val report = list.mapNotNull { m -> codecs.reason(m)?.let { PlayabilityItem(m.id, it) } }
                main.post {
                    loading = false
                    if (isDestroyed) return@post
                    movies = list
                    shows = showList
                    myList = listKeys
                    privateUntil = until
                    update = newer
                    // Mentioned once per version and run of the app; the Settings row keeps offering it.
                    if (newer != null && newer.versionCode != announcedUpdate && !store.profileKids) {
                        announcedUpdate = newer.versionCode
                        Toast.makeText(this, getString(R.string.update_available, newer.versionName), Toast.LENGTH_LONG).show()
                    }
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
        if (section !in available(list)) {
            section = Section.HOME
            genre = null
            collection = null
        }
        renderNav(list)
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

    // ---- The top menu ----

    enum class Section(val label: Int) {
        HOME(R.string.nav_home), MOVIES(R.string.nav_movies), SHOWS(R.string.nav_shows), GENRES(R.string.nav_genres),
        MY_LIST(R.string.nav_my_list), PRIVATE(R.string.nav_private),
    }

    /** Only sections with something in them; Private only while it's unlocked (the phone sends nothing otherwise). */
    private fun available(all: List<Movie>): List<Section> = listOfNotNull(
        Section.HOME,
        Section.MOVIES.takeIf { all.any { !it.private } },
        Section.SHOWS.takeIf { shows.isNotEmpty() },
        Section.GENRES.takeIf { all.any { !it.private && it.genres.isNotEmpty() } || shows.any { it.genres.isNotEmpty() } },
        Section.MY_LIST.takeIf { myList.isNotEmpty() },
        Section.PRIVATE.takeIf { all.any { it.private } },
    )

    private fun renderNav(all: List<Movie>) {
        val sections = available(all)
        val current = (0 until nav.childCount).map { nav.getChildAt(it).tag }
        if (current != sections) {
            val focused = nav.findFocus()?.tag
            nav.removeAllViews()
            for (s in sections) {
                val tab = TextView(this, null, 0, R.style.HomeReel_NavTab).apply {
                    tag = s
                    setText(s.label)
                    setOnClickListener { choose(s) }
                }
                nav.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(this@MainActivity, 4)
                })
                if (s == focused) tab.requestFocus()
            }
        }
        for (i in 0 until nav.childCount) nav.getChildAt(i).isActivated = nav.getChildAt(i).tag == section
    }

    /** Switches section; focus stays on the menu, and Down goes into the new rows. */
    private fun choose(s: Section) {
        if (s == section && s != Section.GENRES) return
        section = s
        genre = null
        collection = null
        focusedKey = null
        renderNav(movies)
        adapter.submit(buildRows(movies))
        rows.scrollToPosition(0)
        adapter.rows.firstOrNull()?.items?.firstOrNull()?.let(::onFocused)
    }

    private fun buildRows(all: List<Movie>): List<HomeRow> = when (section) {
        Section.HOME -> homeRows(all)
        Section.MOVIES -> movieRows(all.filter { !it.private })
        Section.SHOWS -> showRows()
        Section.GENRES -> genreRows(all.filter { !it.private })
        Section.MY_LIST -> listRows(all.filter { !it.private })
        Section.PRIVATE -> listOf(HomeRow(getString(R.string.row_private_all), all.filter { it.private }.sortedByDescending { it.addedAt }.map { MovieItem(it) }))
    }

    private fun movieRows(list: List<Movie>): List<HomeRow> {
        val out = ArrayList<HomeRow>()
        val watching = list.filter { inProgress(it) }.sortedByDescending { it.lastPlayedAt }.map { MovieItem(it, landscape = true) }
        if (watching.isNotEmpty()) out += HomeRow(getString(R.string.row_continue), watching)
        if (list.size > 4) out += HomeRow(getString(R.string.row_recent_movies), list.sortedByDescending { it.addedAt }.take(20).map { MovieItem(it) })
        val rated = list.filter { it.imdbRating != null }.sortedByDescending { it.imdbRating }
        if (rated.size >= 2) out += HomeRow(getString(R.string.row_top_rated), rated.take(20).map { MovieItem(it) })
        genresOf(list.map { it.genres }).take(12).forEach { g ->
            out += HomeRow(g, list.filter { g in it.genres }.sortedByDescending { it.imdbRating ?: 0.0 }.map { MovieItem(it) })
        }
        out += azRows(list)
        return out
    }

    private fun showRows(): List<HomeRow> {
        val out = ArrayList<HomeRow>()
        val watching = shows.mapNotNull { show ->
            show.upNext?.let { ep ->
                show.lastPlayedAt to MovieItem(ep, landscape = true, caption = getString(R.string.episode_caption, show.title, Format.episodeCode(ep)))
            }
        }.sortedByDescending { it.first }.map { it.second }
        if (watching.isNotEmpty()) out += HomeRow(getString(R.string.row_continue), watching)
        if (shows.size > 4) out += HomeRow(getString(R.string.row_recent_shows), shows.sortedByDescending { it.addedAt }.take(20).map { ShowItem(it) })
        genresOf(shows.map { it.genres }).take(12).forEach { g ->
            out += HomeRow(g, shows.filter { g in it.genres }.sortedByDescending { it.imdbRating ?: 0.0 }.map { ShowItem(it) })
        }
        out += HomeRow(getString(R.string.row_all_shows), shows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { sortKey(it.title) }).map { ShowItem(it) })
        return out
    }

    /** A row of genre tiles; once one is chosen, its films and shows underneath. */
    private fun genreRows(list: List<Movie>): List<HomeRow> {
        genreNames = genresOf(list.map { it.genres } + shows.map { it.genres })
        val count = { g: String -> list.count { g in it.genres } + shows.count { g in it.genres } }
        val tiles = genreNames.mapIndexed { i, g -> ActionItem(ACTION_GENRE + i, getString(R.string.genre_count, g, count(g)), R.drawable.ic_genre) }
        val out = arrayListOf(HomeRow(getString(R.string.row_genres), tiles))
        // Film series with two or more films here, as tiles under the genres.
        val sets = Collections.of(list.filter { it.kind != Kind.EPISODE })
        collectionNames = sets.map { it.first }
        if (sets.isNotEmpty()) {
            out += HomeRow(getString(R.string.row_collections), sets.mapIndexed { i, (name, films) ->
                ActionItem(ACTION_COLLECTION + i, getString(R.string.genre_count, name, films.size), R.drawable.ic_genre)
            })
        }
        collection?.let { c -> sets.firstOrNull { it.first == c }?.let { out += HomeRow(c, it.second.map(::MovieItem)) } }
        val g = genre ?: return out
        val films = list.filter { g in it.genres }
        val series = shows.filter { g in it.genres }
        val titles = films.map { Title(MovieItem(it), it.addedAt, it.imdbRating, it.genres, it.title) } +
            series.map { Title(ShowItem(it), it.addedAt, it.imdbRating, it.genres, it.title) }
        val rated = titles.filter { it.rating != null }.sortedByDescending { it.rating }
        if (rated.size >= 2) out += HomeRow(getString(R.string.row_genre_top, g), rated.take(20).map { it.item })
        if (films.isNotEmpty()) out += HomeRow(getString(R.string.row_genre_movies, g), films.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { sortKey(it.title) }).map { MovieItem(it) })
        if (series.isNotEmpty()) out += HomeRow(getString(R.string.row_genre_shows, g), series.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { sortKey(it.title) }).map { ShowItem(it) })
        return out
    }

    private fun listRows(list: List<Movie>): List<HomeRow> {
        val byKey: Map<String, HomeItem> = list.associate { "m:${it.id}" to MovieItem(it) } + shows.associate { "s:${it.id}" to ShowItem(it) }
        return listOf(HomeRow(getString(R.string.row_my_list), myList.mapNotNull { byKey[it] }))
    }

    /** Genres, most titles first. */
    private fun genresOf(lists: List<List<String>>): List<String> =
        lists.flatten().groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }

    private fun azRows(list: List<Movie>): List<HomeRow> {
        val sorted = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { sortKey(it.title) })
        if (sorted.isEmpty()) return emptyList()
        if (sorted.size <= 60) return listOf(HomeRow(getString(R.string.row_all), sorted.map { MovieItem(it) }))
        return sorted.groupBy { letter(it.title) }.map { (letter, group) -> HomeRow(getString(R.string.row_letter, letter), group.map { MovieItem(it) }) }
    }

    private fun homeRows(all: List<Movie>): List<HomeRow> {
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
        val byKey: Map<String, HomeItem> = list.associate { "m:${it.id}" to MovieItem(it) } + shows.associate { "s:${it.id}" to ShowItem(it) }
        val listed = myList.mapNotNull { byKey[it] }
        if (listed.isNotEmpty()) out += HomeRow(getString(R.string.row_my_list), listed)
        val titles = list.map { Title(MovieItem(it), it.addedAt, it.imdbRating, it.genres, it.title) } +
            shows.map { Title(ShowItem(it), it.addedAt, it.imdbRating, it.genres, it.title) }
        if (titles.size > 4) {
            out += HomeRow(getString(R.string.row_recent), titles.sortedByDescending { it.addedAt }.take(20).map { it.item })
        }
        out += becauseYouWatched(list, byKey)
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
        // A kids profile only gets "Switch profile" (which asks for the PIN): nothing that could get round it.
        if (store.profileKids) {
            out += HomeRow(getString(R.string.row_settings), listOf(ActionItem(ACTION_PROFILE, getString(R.string.action_profile), R.drawable.ic_person)))
            return out
        }
        out += HomeRow(
            getString(R.string.row_settings),
            listOfNotNull(
                update?.let { ActionItem(ACTION_UPDATE, getString(R.string.action_update, it.versionName), R.drawable.ic_update) },
                ActionItem(ACTION_PROFILE, getString(R.string.action_profile), R.drawable.ic_person),
                ActionItem(ACTION_REFRESH, getString(R.string.action_refresh), R.drawable.ic_refresh),
                ActionItem(ACTION_RESCAN, getString(R.string.action_rescan), R.drawable.ic_folder),
                ActionItem(
                    ACTION_FRAMERATE,
                    getString(if (TvPrefs(this).matchFrameRate) R.string.action_framerate_on else R.string.action_framerate_off),
                    R.drawable.ic_framerate,
                ),
                ActionItem(ACTION_ABOUT, getString(R.string.action_about), R.drawable.ic_info),
                ActionItem(ACTION_SWITCH, getString(R.string.action_switch), R.drawable.ic_phone),
            ),
        )
        return out
    }

    /**
     * Up to two "Because you watched…" rows, from the last two titles this profile played: titles sharing actors,
     * a director, or at least two genres with it.
     */
    private fun becauseYouWatched(list: List<Movie>, byKey: Map<String, HomeItem>): List<HomeRow> {
        val signals = list.map { Recommend.Signals("m:${it.id}", it.genres, it.cast, it.director) } +
            shows.map { Recommend.Signals("s:${it.id}", it.genres, it.cast, it.creators) }
        val played = (list.filter { it.lastPlayedAt > 0 }.map { Triple("m:${it.id}", it.title, it.lastPlayedAt) } +
            shows.filter { it.lastPlayedAt > 0 }.map { Triple("s:${it.id}", it.title, it.lastPlayedAt) })
            .sortedByDescending { it.third }.take(2)
        return played.mapNotNull { (key, title, _) ->
            val seed = signals.firstOrNull { it.key == key } ?: return@mapNotNull null
            val items = Recommend.similar(seed, signals).mapNotNull { byKey[it] }
            if (items.size >= 2) HomeRow(getString(R.string.row_because, title), items) else null
        }
    }

    private fun switchProfile() {
        startActivity(Intent(this, ProfileActivity::class.java).putExtra(ProfileActivity.EXTRA_SWITCH, true))
    }

    /** The remote's search button opens search from anywhere on the home screen. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_SEARCH) {
            startActivity(Intent(this, SearchActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
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
        in ACTION_GENRE until ACTION_GENRE + 1000 -> genreNames.getOrNull(id - ACTION_GENRE)?.let { g ->
            getString(R.string.help_genre, movies.count { !it.private && g in it.genres } + shows.count { g in it.genres })
        } ?: ""
        in ACTION_COLLECTION until ACTION_COLLECTION + 1000 -> collectionNames.getOrNull(id - ACTION_COLLECTION)?.let { c ->
            getString(R.string.help_collection, movies.count { it.collection == c })
        } ?: ""
        ACTION_REFRESH -> getString(R.string.help_refresh)
        ACTION_RESCAN -> getString(R.string.help_rescan, "http://${store.host}:${store.port}/admin")
        ACTION_ABOUT -> getString(R.string.help_about)
        ACTION_PROFILE -> getString(R.string.help_profile)
        ACTION_FRAMERATE -> getString(R.string.help_framerate)
        ACTION_UPDATE -> getString(R.string.help_update, update?.versionName ?: "")
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
        val chosenGenre = id in ACTION_GENRE until ACTION_GENRE + 1000
        if (chosenGenre || id in ACTION_COLLECTION until ACTION_COLLECTION + 1000) {
            if (chosenGenre) {
                genre = genreNames.getOrNull(id - ACTION_GENRE) ?: return
                collection = null
            } else {
                collection = collectionNames.getOrNull(id - ACTION_COLLECTION) ?: return
                genre = null
            }
            adapter.submit(buildRows(movies))
            // Into the first row of what was chosen, below the tiles.
            val first = adapter.rows.indexOfFirst { row -> row.items.none { it is ActionItem } }
            focusedKey = adapter.rows.getOrNull(first)?.items?.firstOrNull()?.key
            restoreFocus()
            return
        }
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
            ACTION_PROFILE -> switchProfile()
            ACTION_UPDATE -> installUpdate()
            ACTION_FRAMERATE -> {
                val prefs = TvPrefs(this)
                prefs.matchFrameRate = !prefs.matchFrameRate
                show(movies)
            }
            ACTION_SWITCH -> {
                store.forget()
                startActivity(Intent(this, ConnectActivity::class.java))
                finish()
            }
        }
    }

    /** Downloads the TV app update from the phone and hands it to Android's installer, which asks to confirm. */
    private fun installUpdate() {
        val target = update ?: return
        if (!ApkInstaller.allowedOrAsk(this)) return
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        val api = store.api()
        worker.execute {
            try {
                val file = File(cacheDir, "update.apk")
                api.downloadUpdate(target, file)
                ApkInstaller.install(this, file)
            } catch (e: Exception) {
                main.post { Toast.makeText(this, getString(R.string.update_failed, e.message), Toast.LENGTH_LONG).show() }
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
        if (section != Section.HOME) {
            choose(Section.HOME)
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
        private const val ACTION_PROFILE = 5
        private const val ACTION_FRAMERATE = 6
        private const val ACTION_UPDATE = 7

        /** Genre tiles: ACTION_GENRE + the genre's place in [genreNames]. */
        private const val ACTION_GENRE = 1000

        /** Collection tiles: ACTION_COLLECTION + the collection's place in [collectionNames]. */
        private const val ACTION_COLLECTION = 3000

        /** The update version already mentioned in a toast, so it isn't repeated on every visit to Home. */
        private var announcedUpdate = 0L

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
