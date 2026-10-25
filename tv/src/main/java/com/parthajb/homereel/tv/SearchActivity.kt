package com.parthajb.homereel.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.Search
import com.parthajb.homereel.shared.Show
import java.util.concurrent.Executors

/**
 * Search as you type, across titles, cast, directors, creators, genres and years. Results come from the lists the
 * phone already sent for this profile, so a kids profile only ever finds kids' titles.
 */
class SearchActivity : FragmentActivity(), CardHost {
    private val store by lazy { ServerStore(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val adapter = RowsAdapter(this)
    private var movies: List<Movie> = emptyList()
    private var shows: List<Show> = emptyList()
    private lateinit var box: EditText
    private lateinit var empty: TextView
    private lateinit var results: RecyclerView
    private val search = Runnable { update() }

    override val api: ApiClient get() = store.api()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        box = findViewById(R.id.search_box)
        empty = findViewById(R.id.search_empty)
        results = findViewById(R.id.results)
        results.layoutManager = StartAlignLayoutManager(this, RecyclerView.VERTICAL)
        results.itemAnimator = null
        results.adapter = adapter

        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                main.removeCallbacks(search)
                main.postDelayed(search, 250)
            }
        })
        // "Search" on the keyboard (or Down) moves to the results, so the remote can reach them.
        box.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                results.post { results.requestFocus() }
                true
            } else {
                false
            }
        }
        worker.execute {
            val api = store.api()
            val m = runCatching { api.movies() }.getOrDefault(emptyList())
            val s = runCatching { api.shows() }.getOrDefault(emptyList())
            main.post {
                movies = m.filter { !it.private }
                shows = s
                update()
            }
        }
        box.requestFocus()
    }

    private fun update() {
        val query = box.text.toString().trim()
        if (query.isEmpty()) {
            adapter.submit(emptyList())
            empty.text = ""
            return
        }
        val films = movies.map { m ->
            m to Search.score(query, m.title, listOf(m.director, m.year?.toString(), m.collection) + m.cast + m.genres)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { MovieItem(it.first) }
        val series = shows.map { s ->
            s to Search.score(query, s.title, listOf(s.creators, s.year?.toString()) + s.cast + s.genres)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { ShowItem(it.first) }
        val rows = listOfNotNull(
            films.takeIf { it.isNotEmpty() }?.let { HomeRow(getString(R.string.row_movies), it) },
            series.takeIf { it.isNotEmpty() }?.let { HomeRow(getString(R.string.row_shows), it) },
        )
        // Shows first when the best match is a show.
        val ordered = if (series.isNotEmpty() && (films.isEmpty() || bestIsShow(query))) rows.reversed() else rows
        adapter.submit(ordered)
        empty.text = if (ordered.isEmpty()) getString(R.string.search_empty, query) else ""
    }

    private fun bestIsShow(query: String): Boolean {
        val film = movies.maxOfOrNull { Search.score(query, it.title, emptyList()) } ?: 0
        val show = shows.maxOfOrNull { Search.score(query, it.title, emptyList()) } ?: 0
        return show > film
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(box.windowToken, 0)
    }

    override fun cantPlay(id: String) = false

    override fun onFocused(item: HomeItem) = Unit

    override fun onClicked(item: HomeItem) {
        when (item) {
            is MovieItem -> startActivity(Intent(this, DetailsActivity::class.java).putExtra(DetailsActivity.EXTRA_ID, item.movie.id))
            is ShowItem -> startActivity(Intent(this, ShowActivity::class.java).putExtra(ShowActivity.EXTRA_ID, item.show.id))
            is ActionItem -> Unit
        }
    }

    override fun onPlay(movie: Movie) = MainActivity.play(this, movie, if (MainActivity.inProgress(movie)) movie.positionMs else 0)

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}
