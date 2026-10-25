package com.parthajb.homereel.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.Playability
import java.util.Locale
import java.util.concurrent.Executors

/** One movie, full screen: backdrop, details, Play / Resume, and More like this underneath. */
class DetailsActivity : FragmentActivity(), CardHost {
    private val store by lazy { ServerStore(this) }
    private val codecs by lazy { TvCodecs(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val id by lazy { intent.getStringExtra(EXTRA_ID)!! }
    private val moreAdapter = RowsAdapter(this)
    private var movie: Movie? = null
    private var reasons: Map<String, String> = emptyMap()

    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var genres: TextView
    private lateinit var description: TextView
    private lateinit var tagline: TextView
    private lateinit var credits: TextView
    private lateinit var warning: TextView
    private lateinit var tech: TextView
    private lateinit var primary: Button
    private lateinit var restart: Button
    private lateinit var watched: Button
    private lateinit var myList: Button
    private lateinit var more: RecyclerView
    private lateinit var backdrop: Backdrop

    override val api: ApiClient get() = store.api()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        title = findViewById(R.id.title)
        meta = findViewById(R.id.meta)
        genres = findViewById(R.id.genres)
        description = findViewById(R.id.description)
        tagline = findViewById(R.id.tagline)
        credits = findViewById(R.id.credits)
        warning = findViewById(R.id.warning)
        tech = findViewById(R.id.tech)
        primary = findViewById(R.id.play)
        restart = findViewById(R.id.restart)
        watched = findViewById(R.id.watched)
        myList = findViewById(R.id.my_list)
        more = findViewById(R.id.more)
        backdrop = Backdrop(findViewById(R.id.backdrop_a), findViewById(R.id.backdrop_b)) { store.api() }

        more.layoutManager = StartAlignLayoutManager(this, RecyclerView.VERTICAL)
        more.itemAnimator = null
        more.adapter = moreAdapter

        primary.setOnClickListener { movie?.let { MainActivity.play(this, it, if (MainActivity.inProgress(it)) it.positionMs else 0) } }
        restart.setOnClickListener { movie?.let { MainActivity.play(this, it, 0) } }
        watched.setOnClickListener { toggleWatched() }
        myList.setOnClickListener {
            val m = movie ?: return@setOnClickListener
            toggleMyList(this, store, worker, main, "m:${m.id}", !m.inMyList, myList) { on -> movie = m.copy(inMyList = on) }
        }
        listOf(primary, restart, watched, myList).forEach { button ->
            button.setOnFocusChangeListener { v, focused ->
                v.animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f).setDuration(120).start()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload every time, so Resume shows the point the player just saved.
        worker.execute {
            val result = runCatching { store.api().movies() }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { list ->
                    val m = list.firstOrNull { it.id == id } ?: run {
                        Toast.makeText(this, R.string.movie_gone, Toast.LENGTH_LONG).show()
                        finish()
                        return@post
                    }
                    reasons = list.mapNotNull { x -> codecs.reason(x)?.let { x.id to it } }.toMap()
                    show(m, list)
                }.onFailure {
                    Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    if (movie == null) finish()
                }
            }
        }
    }

    private fun show(m: Movie, all: List<Movie>) {
        val first = movie == null
        movie = m
        title.text = m.title
        meta.text = Format.meta(this, m)
        genres.text = Format.genres(m)
        genres.visibility = if (m.genres.isEmpty()) View.GONE else View.VISIBLE
        description.text = m.description
        description.visibility = if (m.description.isNullOrBlank()) View.GONE else View.VISIBLE
        tagline.text = m.tagline
        tagline.visibility = if (m.tagline.isNullOrBlank()) View.GONE else View.VISIBLE
        val creditLines = Format.credits(this, m)
        credits.text = creditLines
        credits.visibility = if (creditLines == null) View.GONE else View.VISIBLE
        val reason = reasons[m.id]
        warning.text = reason?.let { getString(R.string.cant_play_line, it) }
        warning.visibility = if (reason == null) View.GONE else View.VISIBLE
        tech.text = techLine(m)
        backdrop.show(m)

        val resuming = MainActivity.inProgress(m)
        primary.text = if (resuming) getString(R.string.resume_at, Format.clock(m.positionMs)) else getString(R.string.play)
        restart.visibility = if (resuming) View.VISIBLE else View.GONE
        watched.text = getString(if (m.watched) R.string.mark_unwatched else R.string.mark_watched)
        showMyList(myList, m.inMyList)

        moreAdapter.submit(listOfNotNull(similar(m, all)?.let { HomeRow(getString(R.string.row_more_like_this), it) }))
        if (first) primary.requestFocus()
    }

    /** Same genres first (most shared genres, then rating); without genres, the most recently added. */
    private fun similar(m: Movie, all: List<Movie>): List<HomeItem>? {
        val others = all.filter { it.id != m.id }
        val picks = if (m.genres.isNotEmpty()) {
            others.map { it to it.genres.count { g -> g in m.genres } }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Movie, Int>> { it.second }.thenByDescending { it.first.imdbRating ?: 0.0 })
                .map { it.first }
        } else {
            emptyList()
        }.ifEmpty { others.sortedByDescending { it.addedAt } }
        return picks.take(15).map { MovieItem(it) }.ifEmpty { null }
    }

    private fun techLine(m: Movie): String {
        val parts = ArrayList<String>()
        m.video?.let { v -> parts += Playability.describe(v.mime) + if (v.width > 0) " ${v.width}×${v.height}" else "" }
        if (m.audio.isNotEmpty()) {
            parts += m.audio.joinToString(", ") { a ->
                Playability.describe(a.mime) + (a.language?.takeIf { it != "und" }?.let { " (${Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).ifEmpty { it }})" } ?: "")
            }
        }
        if (m.subtitles.isNotEmpty()) parts += getString(R.string.subtitles_list, m.subtitles.joinToString(", ") { it.label })
        if (m.size > 0) parts += Format.size(m.size)
        return parts.joinToString("   ·   ")
    }

    private fun toggleWatched() {
        val m = movie ?: return
        worker.execute {
            val target = if (m.watched) 0 else m.durationMs.coerceAtLeast(1)
            val result = runCatching {
                store.api().progress(m.id, target, m.durationMs.coerceAtLeast(1))
                store.api().movies()
            }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { list -> list.firstOrNull { it.id == m.id }?.let { show(it, list) } }
            }
        }
    }

    override fun cantPlay(id: String) = reasons.containsKey(id)

    override fun onFocused(item: HomeItem) = Unit

    override fun onClicked(item: HomeItem) {
        if (item !is MovieItem) return
        startActivity(Intent(this, DetailsActivity::class.java).putExtra(EXTRA_ID, item.movie.id))
        finish()
    }

    override fun onPlay(movie: Movie) = MainActivity.play(this, movie, if (MainActivity.inProgress(movie)) movie.positionMs else 0)

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
