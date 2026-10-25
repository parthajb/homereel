package com.parthajb.homereel.tv

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.Show
import java.util.concurrent.Executors

enum class Art(val path: String) { POSTER("poster"), BACKDROP("backdrop") }

/** Where a picture lives on the phone: a film's or episode's, or a show's. */
data class ArtRef(val base: String, val version: Long, val hasPoster: Boolean, val hasBackdrop: Boolean)

fun Movie.art() = ArtRef("/movies/$id", artVersion, hasPoster, hasBackdrop)

fun Show.art() = ArtRef("/shows/$id", artVersion, hasPoster, hasBackdrop)

/**
 * Posters and backdrops from the phone, with a memory cache of a sixth of the app's heap. A request for a picture
 * already on its way joins it instead of downloading it twice. Keys carry the movie's artVersion, so a new
 * thumbnail from the admin page shows up on the next refresh.
 */
object Images {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val workers = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** Calls [done] on the main thread, straight away when the picture is cached. */
    fun load(api: ApiClient, art: Art, ref: ArtRef, width: Int, done: (Bitmap?) -> Unit) {
        val available = if (art == Art.POSTER) ref.hasPoster else ref.hasBackdrop
        if (!available) {
            done(null)
            return
        }
        val key = key(art, ref, width)
        cache.get(key)?.let {
            done(it)
            return
        }
        waiting[key]?.let {
            it += done
            return
        }
        waiting[key] = mutableListOf(done)
        workers.execute {
            val bitmap = runCatching {
                api.image("${ref.base}/${art.path}?v=${ref.version}", width, art == Art.BACKDROP)
            }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            main.post { waiting.remove(key)?.forEach { it(bitmap) } }
        }
    }

    /** Loads into a recycled view; a view rebound to another movie meanwhile ignores the late answer. */
    fun into(api: ApiClient, art: Art, ref: ArtRef, view: ImageView, width: Int, done: (Boolean) -> Unit) {
        val key = key(art, ref, width)
        view.setTag(R.id.poster_key, key)
        load(api, art, ref, width) { bitmap ->
            if (view.getTag(R.id.poster_key) != key) return@load
            view.setImageBitmap(bitmap)
            done(bitmap != null)
        }
    }

    fun forget(view: ImageView) {
        view.setTag(R.id.poster_key, null)
        view.setImageDrawable(null)
    }

    fun clear() = cache.evictAll()

    private fun key(art: Art, ref: ArtRef, width: Int) = "${art.path}:${ref.base}:${ref.version}:$width"
}
