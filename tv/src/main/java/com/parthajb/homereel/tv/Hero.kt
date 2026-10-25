package com.parthajb.homereel.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.Show

/**
 * The big picture behind the top of the screen. Two image views take turns, so a new backdrop fades in over the
 * old one. Changes wait a moment, so scrolling quickly through a row doesn't download every backdrop on the way.
 */
class Backdrop(private val a: ImageView, private val b: ImageView, private val api: () -> ApiClient) {
    private val main = Handler(Looper.getMainLooper())
    private var front = a
    private var shownKey: String? = null
    private var pending: Runnable? = null

    fun show(movie: Movie?) = showArt(movie?.art())

    fun showArt(ref: ArtRef?) {
        pending?.let(main::removeCallbacks)
        val key = ref?.let { "${it.base}:${it.version}" }
        if (key == shownKey) return
        val task = Runnable {
            if (ref == null) {
                swap(null, null)
                return@Runnable
            }
            val width = a.resources.displayMetrics.widthPixels.coerceAtMost(1280)
            Images.load(api(), Art.BACKDROP, ref, width) { bitmap -> swap(key, bitmap) }
        }
        pending = task
        main.postDelayed(task, 220)
    }

    private fun swap(key: String?, bitmap: Bitmap?) {
        shownKey = key
        val back = if (front === a) b else a
        back.setImageBitmap(bitmap)
        back.animate().cancel()
        back.alpha = 0f
        back.animate().alpha(if (bitmap == null) 0f else 1f).setDuration(350).start()
        front.animate().alpha(0f).setDuration(350).start()
        front = back
    }
}

object Format {
    fun duration(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(1)
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min"
    }

    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun size(bytes: Long): String =
        if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${(bytes / 1_000_000).coerceAtLeast(1)} MB"

    fun resolution(movie: Movie): String? {
        val v = movie.video ?: return null
        val long = maxOf(v.width, v.height)
        val short = minOf(v.width, v.height)
        return when {
            long >= 3800 || short >= 2000 -> "4K"
            long >= 1900 || short >= 1000 -> "HD"
            long > 0 -> "SD"
            else -> null
        }
    }

    /**
     * "★ 7.8 IMDb  2019  [UA 13+]  2h 10m  4K", with the rating in the accent colour, the age rating in a box,
     * and a gap between facts, like a streaming app.
     */
    fun meta(context: Context, movie: Movie): CharSequence {
        val out = SpannableStringBuilder()
        fun gap() {
            if (out.isNotEmpty()) out.append("   ")
        }
        movie.imdbRating?.let {
            val start = out.length
            out.append("★ %.1f".format(it))
            out.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(" ").append(movie.ratingSource ?: "IMDb")
        }
        movie.year?.let {
            gap()
            out.append(it.toString())
        }
        movie.certification?.let {
            gap()
            val start = out.length
            out.append(it)
            out.setSpan(BadgeSpan(context), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        listOfNotNull(
            movie.durationMs.takeIf { it > 0 }?.let(::duration),
            resolution(movie),
            "Subtitles".takeIf { movie.subtitles.isNotEmpty() },
        ).forEach {
            gap()
            out.append(it)
        }
        return out
    }

    /** "Starring A, B, C" and "Director D", on their own lines; null when TMDB hasn't filled them in. */
    fun credits(context: Context, movie: Movie): String? = listOfNotNull(
        movie.cast.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.starring, it.take(4).joinToString(", ")) },
        movie.director?.let { context.getString(R.string.director, it) },
        movie.collection?.let { context.getString(R.string.part_of, it) },
    ).joinToString("\n").ifEmpty { null }

    fun genres(movie: Movie): String = movie.genres.joinToString("  •  ")

    fun genres(show: Show): String = show.genres.joinToString("  •  ")

    /** "★ 8.5 IMDb  2008–2013  [UA 16+]  5 Seasons", the show's version of [meta]. */
    fun showMeta(context: Context, show: Show): CharSequence {
        val out = SpannableStringBuilder()
        fun gap() {
            if (out.isNotEmpty()) out.append("   ")
        }
        show.imdbRating?.let {
            val start = out.length
            out.append("★ %.1f".format(it))
            out.setSpan(ForegroundColorSpan(ContextCompat.getColor(context, R.color.accent)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append(" ").append(show.ratingSource ?: "IMDb")
        }
        show.year?.let {
            gap()
            out.append(if (show.endYear != null && show.endYear != it) "$it–${show.endYear}" else it.toString())
        }
        show.certification?.let {
            gap()
            val start = out.length
            out.append(it)
            out.setSpan(BadgeSpan(context), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        gap()
        out.append(context.resources.getQuantityString(R.plurals.seasons, show.seasons.size, show.seasons.size))
        return out
    }

    /** "S1:E3" for an episode, the way streaming apps label them. */
    fun episodeCode(movie: Movie): String = "S${movie.season}:E${movie.episode}"
}

/** Draws its text in a thin rounded box, the way streaming apps show an age rating. */
class BadgeSpan(context: Context) : ReplacementSpan() {
    private val density = context.resources.displayMetrics.density
    private val padding = 6 * density

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        (paint.measureText(text, start, end) * 0.85f + padding * 2).toInt()

    override fun draw(
        canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {
        val textSize = paint.textSize
        val colour = paint.color
        paint.textSize = textSize * 0.85f
        val width = paint.measureText(text, start, end) + padding * 2
        val fm = paint.fontMetrics
        val boxTop = y + fm.ascent - density * 2
        val boxBottom = y + fm.descent + density * 2
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = (colour and 0x00FFFFFF) or 0xB3000000.toInt()
        canvas.drawRoundRect(RectF(x, boxTop, x + width, boxBottom), 3 * density, 3 * density, paint)
        paint.style = Paint.Style.FILL
        paint.color = colour
        canvas.drawText(text, start, end, x + padding, y.toFloat(), paint)
        paint.textSize = textSize
    }
}

fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
