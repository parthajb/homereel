package com.parthajb.homereel.tv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.ShowDetail
import java.util.concurrent.Executors

/** A series: its backdrop and details, Resume or Play, a tab per season and that season's episodes. */
class ShowActivity : FragmentActivity(), CardHost {
    private val store by lazy { ServerStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val id by lazy { intent.getStringExtra(EXTRA_ID)!! }
    private val episodesAdapter = RowsAdapter(this)
    private var detail: ShowDetail? = null
    private var season: Int? = null

    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var genres: TextView
    private lateinit var description: TextView
    private lateinit var credits: TextView
    private lateinit var primary: Button
    private lateinit var restart: Button
    private lateinit var myList: Button
    private lateinit var seasons: LinearLayout
    private lateinit var episodeInfo: TextView
    private lateinit var episodes: RecyclerView
    private lateinit var backdrop: Backdrop

    override val api: ApiClient get() = store.api()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_show)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        genres = findViewById(R.id.genres)
        description = findViewById(R.id.description)
        credits = findViewById(R.id.credits)
        primary = findViewById(R.id.play)
        restart = findViewById(R.id.restart)
        myList = findViewById(R.id.my_list)
        seasons = findViewById(R.id.seasons)
        episodeInfo = findViewById(R.id.episode_info)
        episodes = findViewById(R.id.episodes)
        backdrop = Backdrop(findViewById(R.id.backdrop_a), findViewById(R.id.backdrop_b)) { store.api() }

        episodes.layoutManager = StartAlignLayoutManager(this, RecyclerView.VERTICAL)
        episodes.itemAnimator = null
        episodes.adapter = episodesAdapter

        primary.setOnClickListener { detail?.let { d -> (d.show.upNext ?: d.episodes.firstOrNull())?.let(::onPlay) } }
        restart.setOnClickListener { detail?.episodes?.firstOrNull()?.let { MainActivity.play(this, it, 0) } }
        myList.setOnClickListener {
            val d = detail ?: return@setOnClickListener
            toggleMyList(this, store, worker, main, "s:${d.show.id}", !d.show.inMyList, myList) { on ->
                detail = d.copy(show = d.show.copy(inMyList = on))
            }
        }
        listOf(primary, restart, myList).forEach { button ->
            button.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload every time, so Resume and the progress bars show what the player just saved.
        worker.execute {
            val result = runCatching { store.api().show(id) }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess(::show).onFailure {
                    Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    if (detail == null) finish()
                }
            }
        }
    }

    private fun show(d: ShowDetail) {
        val first = detail == null
        detail = d
        val s = d.show
        title.text = s.title
        meta.text = Format.showMeta(this, s)
        genres.text = Format.genres(s)
        genres.visibility = if (s.genres.isEmpty()) View.GONE else View.VISIBLE
        description.text = s.description
        description.visibility = if (s.description.isNullOrBlank()) View.GONE else View.VISIBLE
        val creditLines = listOfNotNull(
            s.cast.takeIf { it.isNotEmpty() }?.let { getString(R.string.starring, it.take(4).joinToString(", ")) },
            s.creators?.let { getString(R.string.created_by, it) },
        ).joinToString("\n")
        credits.text = creditLines
        credits.visibility = if (creditLines.isEmpty()) View.GONE else View.VISIBLE
        backdrop.showArt(s.art())

        val next = s.upNext
        primary.text = when {
            next != null && next.positionMs > 0 -> getString(R.string.resume_episode, Format.episodeCode(next))
            next != null -> getString(R.string.play_episode, Format.episodeCode(next))
            else -> getString(R.string.play_episode, d.episodes.firstOrNull()?.let(Format::episodeCode) ?: "")
        }
        restart.visibility = if (next != null) View.VISIBLE else View.GONE
        showMyList(myList, s.inMyList)

        // Open on the season of the episode to carry on with, the first time.
        if (season == null || season !in s.seasons) season = next?.season ?: s.seasons.firstOrNull()
        buildSeasonTabs(s.seasons)
        showSeason()
        if (first) primary.requestFocus()
    }

    private fun buildSeasonTabs(list: List<Int>) {
        seasons.removeAllViews()
        if (list.size < 2) {
            seasons.visibility = View.GONE
            return
        }
        seasons.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (n in list) {
            val tab = inflater.inflate(R.layout.season_tab, seasons, false) as Button
            tab.text = getString(R.string.season_n, n)
            tab.isSelected = n == season
            tab.setOnClickListener {
                season = n
                for (i in 0 until seasons.childCount) seasons.getChildAt(i).isSelected = seasons.getChildAt(i) === tab
                showSeason()
            }
            seasons.addView(tab)
        }
    }

    private fun showSeason() {
        val d = detail ?: return
        val inSeason = d.episodes.filter { it.season == season }
        val items = inSeason.map { MovieItem(it, landscape = true, caption = "${it.episode} · ${it.title}") }
        episodesAdapter.submit(listOf(HomeRow(getString(R.string.season_n, season ?: 1), items)))
        episodeInfo.text = ""
    }

    override fun cantPlay(id: String) = false

    /** The focused episode's number, title, length and description, above the row. */
    override fun onFocused(item: HomeItem) {
        val m = (item as? MovieItem)?.movie ?: return
        val facts = listOfNotNull(
            "${Format.episodeCode(m)}  ${m.title}",
            m.durationMs.takeIf { it > 0 }?.let(Format::duration),
            getString(R.string.watched_mark).takeIf { m.watched },
        ).joinToString("   ·   ")
        episodeInfo.text = listOfNotNull(facts, m.description).joinToString("\n")
    }

    override fun onClicked(item: HomeItem) {
        (item as? MovieItem)?.movie?.let(::onPlay)
    }

    override fun onPlay(movie: Movie) = MainActivity.play(this, movie, if (MainActivity.inProgress(movie)) movie.positionMs else 0)

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "show"
    }
}
