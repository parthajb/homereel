package com.parthajb.homereel.tv

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.MovieList
import com.parthajb.homereel.shared.PlayNext
import com.parthajb.homereel.shared.Show
import com.parthajb.homereel.shared.ShowList
import com.parthajb.homereel.shared.VoiceSearch
import java.io.File

/**
 * The library as the TV last loaded it, for the TV's own search and for voice search, which can't wait for the
 * phone. It holds what the profile watching could see, never Private, and is deleted when the TV is unpaired.
 */
object LibraryCache {
    private fun movies(context: Context) = File(context.filesDir, "search-movies.json")
    private fun shows(context: Context) = File(context.filesDir, "search-shows.json")

    fun save(context: Context, movies: List<Movie>, shows: List<Show>) {
        movies(context).writeText(ApiJson.encodeToString(MovieList.serializer(), MovieList(movies.filter { !it.private })))
        shows(context).writeText(ApiJson.encodeToString(ShowList.serializer(), ShowList(shows)))
    }

    fun load(context: Context): Pair<List<Movie>, List<Show>> {
        val m = runCatching { ApiJson.decodeFromString(MovieList.serializer(), movies(context).readText()).movies }.getOrDefault(emptyList())
        val s = runCatching { ApiJson.decodeFromString(ShowList.serializer(), shows(context).readText()).shows }.getOrDefault(emptyList())
        return m to s
    }

    fun clear(context: Context) {
        movies(context).delete()
        shows(context).delete()
    }
}

/**
 * HomeReel's titles in the TV's own search (and Google Assistant's), from [LibraryCache]. Only the system's search may
 * ask: the manifest guards it with GLOBAL_SEARCH. A suggestion opens the title's page in HomeReel.
 */
class TvSearchProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val query = args?.firstOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
        val cursor = MatrixCursor(COLUMNS)
        if (query.isBlank() || query == SearchManager.SUGGEST_URI_PATH_QUERY) return cursor
        val (movies, shows) = LibraryCache.load(context!!)
        val limit = uri.getQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT)?.toIntOrNull()?.coerceIn(1, 50) ?: 10
        VoiceSearch.matches(query, movies, shows, limit).forEachIndexed { i, hit ->
            val sub = listOfNotNull(hit.year?.toString(), (hit.movie?.genres ?: hit.show?.genres)?.take(2)?.joinToString(", ")?.ifEmpty { null })
            cursor.addRow(arrayOf<Any?>(
                i, hit.title, sub.joinToString(" · "), Intent.ACTION_VIEW, PlayNext.link(hit.key),
                hit.year, hit.movie?.durationMs?.takeIf { it > 0 }, "video/mp4", hit.key,
            ))
        }
        return cursor
    }

    override fun getType(uri: Uri) = SearchManager.SUGGEST_MIME_TYPE
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object {
        val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_ACTION,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_PRODUCTION_YEAR,
            SearchManager.SUGGEST_COLUMN_DURATION,
            SearchManager.SUGGEST_COLUMN_CONTENT_TYPE,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID,
        )
    }
}
