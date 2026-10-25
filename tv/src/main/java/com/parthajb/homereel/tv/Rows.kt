package com.parthajb.homereel.tv

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.Show

sealed interface HomeItem {
    val key: String
}

/** A film or an episode. [caption] replaces the title on a landscape card ("Panchayat · S2:E5"). */
data class MovieItem(val movie: Movie, val landscape: Boolean = false, val caption: String? = null) : HomeItem {
    override val key get() = "m:${movie.id}"
}

data class ShowItem(val show: Show) : HomeItem {
    override val key get() = "s:${show.id}"
}

data class ActionItem(val id: Int, val label: String, val icon: Int) : HomeItem {
    override val key get() = "a:$id"
}

data class HomeRow(val title: String, val items: List<HomeItem>)

/** What the rows need from the screen that shows them. */
interface CardHost {
    val api: ApiClient
    fun cantPlay(id: String): Boolean
    fun onFocused(item: HomeItem)
    fun onClicked(item: HomeItem)
    fun onPlay(movie: Movie)
}

/**
 * Scrolls so the focused child sits at the start (left for a row of cards, top for the list of rows), the way
 * Netflix keeps your place, instead of RecyclerView's default of scrolling only as far as needed.
 */
class StartAlignLayoutManager(context: Context, orientation: Int) : LinearLayoutManager(context, orientation, false) {
    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean,
    ): Boolean {
        val dx = if (orientation == HORIZONTAL) child.left - parent.paddingLeft else 0
        val dy = if (orientation == VERTICAL) child.top - parent.paddingTop else 0
        if (dx == 0 && dy == 0) return false
        if (immediate) parent.scrollBy(dx, dy) else parent.smoothScrollBy(dx, dy)
        return true
    }
}

/** The vertical list of rows. Each row keeps its own card list and scroll position. */
class RowsAdapter(private val host: CardHost) : RecyclerView.Adapter<RowsAdapter.RowHolder>() {
    var rows: List<HomeRow> = emptyList()
        private set
    private val pool = RecyclerView.RecycledViewPool().apply {
        setMaxRecycledViews(CardsAdapter.POSTER, 24)
    }

    /**
     * Shows [newRows]. Returns false when the rows and their cards are the same as before and were only
     * rebound in place, which keeps focus where it was; true when the layout changed.
     */
    fun submit(newRows: List<HomeRow>): Boolean {
        val same = rows.size == newRows.size && rows.indices.all { i ->
            rows[i].title == newRows[i].title && rows[i].items.map { it.key } == newRows[i].items.map { it.key }
        }
        rows = newRows
        if (same) notifyItemRangeChanged(0, rows.size, PAYLOAD_ITEMS) else notifyDataSetChanged()
        return !same
    }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.row, parent, false)
        return RowHolder(view, host, pool)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(rows[position])

    override fun onBindViewHolder(holder: RowHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_ITEMS)) holder.cards.update(rows[position].items) else holder.bind(rows[position])
    }

    class RowHolder(view: View, host: CardHost, pool: RecyclerView.RecycledViewPool) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.row_title)
        val list: RecyclerView = view.findViewById(R.id.cards)
        val cards = CardsAdapter(host)

        init {
            list.layoutManager = StartAlignLayoutManager(view.context, RecyclerView.HORIZONTAL)
            list.setRecycledViewPool(pool)
            list.itemAnimator = null
            list.adapter = cards
        }

        fun bind(row: HomeRow) {
            title.text = row.title
            cards.replace(row.items)
            list.scrollToPosition(0)
        }
    }

    companion object {
        private const val PAYLOAD_ITEMS = "items"
    }
}

class CardsAdapter(private val host: CardHost) : RecyclerView.Adapter<CardsAdapter.CardHolder>() {
    private var items: List<HomeItem> = emptyList()

    fun replace(newItems: List<HomeItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    /** Same cards in the same order: rebind in place, so the focused card stays focused. */
    fun update(newItems: List<HomeItem>) {
        items = newItems
        notifyItemRangeChanged(0, items.size)
    }

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = when (val item = items[position]) {
        is MovieItem -> if (item.landscape) LANDSCAPE else POSTER
        is ShowItem -> POSTER
        is ActionItem -> ACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardHolder {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(if (viewType == ACTION) R.layout.card_action else R.layout.card_movie, parent, false)
        val (w, h) = when (viewType) {
            LANDSCAPE -> 260 to 146
            ACTION -> 210 to 118
            else -> 132 to 198
        }
        view.layoutParams = RecyclerView.LayoutParams(dp(parent.context, w), dp(parent.context, h)).apply {
            marginEnd = dp(parent.context, 14)
        }
        view.clipToOutline = true
        return CardHolder(view, host, viewType)
    }

    override fun onBindViewHolder(holder: CardHolder, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: CardHolder) = holder.recycle()

    class CardHolder(view: View, private val host: CardHost, private val type: Int) : RecyclerView.ViewHolder(view) {
        private val image: ImageView? = view.findViewById(R.id.image)
        private val placeholder: TextView? = view.findViewById(R.id.placeholder)
        private val caption: TextView? = view.findViewById(R.id.caption)
        private val badge: View? = view.findViewById(R.id.badge)
        private val progress: ProgressBar? = view.findViewById(R.id.progress)
        private val label: TextView? = view.findViewById(R.id.label)
        private val icon: ImageView? = view.findViewById(R.id.icon)
        private var item: HomeItem? = null

        init {
            view.setOnFocusChangeListener { v, focused ->
                val scale = if (focused) 1.1f else 1f
                v.animate().scaleX(scale).scaleY(scale).translationZ(if (focused) 12f else 0f).setDuration(150).start()
                if (focused) item?.let(host::onFocused)
            }
            view.setOnClickListener { item?.let(host::onClicked) }
            view.setOnKeyListener { _, keyCode, event ->
                val movie = (item as? MovieItem)?.movie
                val play = keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                if (play && movie != null && event.action == KeyEvent.ACTION_UP) host.onPlay(movie)
                play && movie != null
            }
        }

        fun bind(item: HomeItem) {
            this.item = item
            when (item) {
                is ActionItem -> {
                    label?.text = item.label
                    icon?.setImageResource(item.icon)
                }
                is MovieItem -> bindMovie(item.movie, item.caption)
                is ShowItem -> bindArt(item.show.title, item.show.art(), null, false, 0)
            }
            if (itemView.isFocused) host.onFocused(item)
        }

        private fun bindMovie(movie: Movie, captionText: String?) {
            val pct = if (movie.durationMs > 0 && movie.positionMs > 0 && !movie.watched) {
                (movie.positionMs * 100 / movie.durationMs).toInt().coerceIn(2, 100)
            } else {
                0
            }
            bindArt(captionText ?: movie.title, movie.art(), movie.id, host.cantPlay(movie.id), pct)
        }

        private fun bindArt(title: String, art: ArtRef, id: String?, warn: Boolean, pct: Int) {
            val landscape = type == LANDSCAPE
            placeholder?.text = title
            placeholder?.visibility = View.VISIBLE
            caption?.visibility = if (landscape) View.VISIBLE else View.GONE
            caption?.text = title
            badge?.visibility = if (warn && id != null) View.VISIBLE else View.GONE
            progress?.visibility = if (pct > 0) View.VISIBLE else View.GONE
            progress?.progress = pct
            val img = image ?: return
            val width = dp(img.context, if (landscape) 260 else 132)
            Images.into(host.api, if (landscape) Art.BACKDROP else Art.POSTER, art, img, width) { loaded ->
                placeholder?.visibility = if (loaded) View.GONE else View.VISIBLE
            }
        }

        fun recycle() {
            image?.let(Images::forget)
            itemView.animate().cancel()
            itemView.scaleX = 1f
            itemView.scaleY = 1f
            itemView.translationZ = 0f
        }
    }

    companion object {
        const val POSTER = 1
        const val LANDSCAPE = 2
        const val ACTION = 3
    }
}
