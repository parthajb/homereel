package com.parthajb.homereel.server

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.Genres
import com.parthajb.homereel.shared.Matching
import com.parthajb.homereel.shared.TmdbResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The lookup service refused the key: no point retrying until the user fixes it. */
class KeyRejected(message: String) : IOException(message)

/** A definite answer, such as 404, that asking again won't change. */
private class Refused(message: String) : IOException(message)

/** A film from TMDB, with its pictures as JPEG bytes ready to save. */
class Fetched(val info: TmdbInfo, val poster: ByteArray?, val backdrop: ByteArray?)

/** A series from TMDB, with its pictures. */
class FetchedShow(val info: TmdbShowInfo, val poster: ByteArray?, val backdrop: ByteArray?)

/** One episode from a TMDB season listing. */
class FetchedEpisode(val number: Int, val title: String?, val overview: String?, val year: Int?, val stillPath: String?)

/**
 * Talks to TMDB, and to OMDb for the IMDb rating. Only the phone does this: the TV never goes online. Release
 * builds always use the real services; debug builds can point at a fake one for tests.
 */
class Metadata(private val prefs: Prefs) {
    private val tmdbBase get() = if (BuildConfig.DEBUG) prefs.tmdbBaseUrl else "https://api.themoviedb.org/3"
    private val imageBase get() = if (BuildConfig.DEBUG) prefs.tmdbImageBaseUrl else "https://image.tmdb.org/t/p"
    private val omdbBase get() = if (BuildConfig.DEBUG) prefs.omdbBaseUrl else "https://www.omdbapi.com/"
    private var lastRequestAt = 0L

    val configured: Boolean get() = !prefs.tmdbKey.isNullOrBlank()

    /** Throws [KeyRejected] for a bad key; any answer at all from TMDB otherwise means the key works. */
    fun checkTmdbKey(key: String) {
        get("$tmdbBase/configuration", key, Configuration.serializer())
    }

    fun search(query: String, year: Int?): List<TmdbResult> =
        searchHits(query, year).take(12).map { hit ->
            TmdbResult(hit.id, hit.title, year(hit.releaseDate), hit.overview?.take(300), hit.posterPath?.let { "$imageBase/w185$it" })
        }

    /**
     * The confident match for a file, or null when no result is certain enough. With a year in the file name,
     * a search filtered by that year comes first, then an unfiltered one in case the release year differs by one.
     */
    fun autoMatch(title: String, year: Int?): Int? {
        val hits = if (year != null) {
            searchHits(title, year).ifEmpty { searchHits(title, null) }
        } else {
            searchHits(title, null)
        }
        val best = hits.take(8)
            .map { it to Matching.score(title, year, it.title, it.originalTitle, year(it.releaseDate)) }
            .maxByOrNull { it.second } ?: return null
        return best.first.id.takeIf { Matching.confident(best.second, year) }
    }

    /**
     * The film's details and pictures. [previous] is what the movie had before. When OMDb or a picture download
     * doesn't answer, its old rating or picture is kept rather than lost to a passing network blip.
     */
    fun fetch(tmdbId: Int, previous: TmdbInfo?): Fetched {
        val same = previous?.takeIf { it.tmdbId == tmdbId }
        val d = get(
            "$tmdbBase/movie/$tmdbId?append_to_response=credits,release_dates&language=en-US",
            prefs.tmdbKey ?: throw KeyRejected("Add a TMDB key in Settings first"),
            Details.serializer(),
        )
        val imdbRating = d.imdbId?.ifBlank { null }?.let { id ->
            prefs.omdbKey?.let { key ->
                try {
                    omdbRating(id, key)
                } catch (e: IOException) {
                    Log.w(TAG, "OMDb didn't answer for $id, keeping the previous rating", e)
                    same?.imdbRating
                }
            }
        }
        val info = TmdbInfo(
            tmdbId = d.id,
            imdbId = d.imdbId?.ifBlank { null },
            title = d.title,
            year = year(d.releaseDate),
            overview = d.overview?.trim()?.ifEmpty { null },
            tagline = d.tagline?.trim()?.ifEmpty { null },
            genres = Genres.normalize(d.genres.map { genreName(it.name) }),
            cast = d.credits?.cast.orEmpty().sortedBy { it.order }.take(6).map { it.name },
            director = d.credits?.crew.orEmpty().filter { it.job == "Director" }.joinToString(", ") { it.name }.ifEmpty { null },
            certification = certification(d.releaseDates),
            collection = d.collection?.name?.ifBlank { null },
            // A score from a handful of votes says little.
            tmdbRating = d.voteAverage?.takeIf { it > 0 && (d.voteCount ?: 0) >= 20 }?.let { Math.round(it * 10) / 10.0 },
            imdbRating = imdbRating,
            fetchedAt = System.currentTimeMillis(),
        )
        val poster = d.posterPath?.let { tryDownload("$imageBase/w500$it") }
        val backdrop = d.backdropPath?.let { tryDownload("$imageBase/w1280$it") }
        // A picture TMDB has but that didn't download: keep the copy already on the phone, if there is one.
        return Fetched(
            info.copy(
                poster = poster != null || (d.posterPath != null && same?.poster == true),
                backdrop = backdrop != null || (d.backdropPath != null && same?.backdrop == true),
            ),
            poster,
            backdrop,
        )
    }

    /** The IMDb rating, or null when IMDb has none yet; throws when OMDb doesn't answer. */
    // ---- Series ----

    fun searchShows(query: String, year: Int?): List<TmdbResult> =
        showHits(query, year).take(12).map { hit ->
            TmdbResult(hit.id, hit.name, year(hit.firstAirDate), hit.overview?.take(300), hit.posterPath?.let { "$imageBase/w185$it" })
        }

    /** The same rules as films: a near-exact name, and the first-air year ±1 when the file names a year. */
    fun autoMatchShow(title: String, year: Int?): Int? {
        val hits = if (year != null) showHits(title, year).ifEmpty { showHits(title, null) } else showHits(title, null)
        val best = hits.take(8)
            .map { it to Matching.score(title, year, it.name, it.originalName, year(it.firstAirDate)) }
            .maxByOrNull { it.second } ?: return null
        return best.first.id.takeIf { Matching.confident(best.second, year) }
    }

    fun fetchShow(tmdbId: Int, previous: TmdbShowInfo?): FetchedShow {
        val same = previous?.takeIf { it.tmdbId == tmdbId }
        val d = get(
            "$tmdbBase/tv/$tmdbId?append_to_response=aggregate_credits,content_ratings,external_ids&language=en-US",
            prefs.tmdbKey ?: throw KeyRejected("Add a TMDB key in Settings first"),
            ShowDetails.serializer(),
        )
        val imdbId = d.externalIds?.imdbId?.ifBlank { null }
        val imdbRating = imdbId?.let { id ->
            prefs.omdbKey?.let { key ->
                try {
                    omdbRating(id, key)
                } catch (e: IOException) {
                    Log.w(TAG, "OMDb didn't answer for $id, keeping the previous rating", e)
                    same?.imdbRating
                }
            }
        }
        val ratings = d.contentRatings?.results.orEmpty().associate { it.country to it.rating.trim() }
        val info = TmdbShowInfo(
            tmdbId = d.id,
            imdbId = imdbId,
            title = d.name,
            year = year(d.firstAirDate),
            endYear = if (d.inProduction == true) null else year(d.lastAirDate),
            overview = d.overview?.trim()?.ifEmpty { null },
            genres = Genres.normalize(d.genres.flatMap { showGenres(it.name) }),
            cast = d.aggregateCredits?.cast.orEmpty().sortedBy { it.order }.take(6).map { it.name },
            creators = d.createdBy.joinToString(", ") { it.name }.ifEmpty { null },
            certification = ratings["IN"]?.ifEmpty { null } ?: ratings["US"]?.ifEmpty { null },
            tmdbRating = d.voteAverage?.takeIf { it > 0 && (d.voteCount ?: 0) >= 20 }?.let { Math.round(it * 10) / 10.0 },
            imdbRating = imdbRating,
            fetchedAt = System.currentTimeMillis(),
        )
        val poster = d.posterPath?.let { tryDownload("$imageBase/w500$it") }
        val backdrop = d.backdropPath?.let { tryDownload("$imageBase/w1280$it") }
        return FetchedShow(
            info.copy(
                poster = poster != null || (d.posterPath != null && same?.poster == true),
                backdrop = backdrop != null || (d.backdropPath != null && same?.backdrop == true),
            ),
            poster,
            backdrop,
        )
    }

    fun fetchSeason(tmdbId: Int, season: Int): List<FetchedEpisode> {
        val d = get(
            "$tmdbBase/tv/$tmdbId/season/$season?language=en-US",
            prefs.tmdbKey ?: throw KeyRejected("Add a TMDB key in Settings first"),
            SeasonDetails.serializer(),
        )
        return d.episodes.map {
            FetchedEpisode(it.number, it.name?.trim()?.ifEmpty { null }, it.overview?.trim()?.ifEmpty { null }, year(it.airDate), it.stillPath)
        }
    }

    /** An episode's still, 300 px wide: enough for a card, small enough to fetch a whole season. */
    fun still(path: String): ByteArray? = tryDownload("$imageBase/w300$path")

    private fun showHits(query: String, year: Int?): List<ShowHit> {
        val key = prefs.tmdbKey ?: throw KeyRejected("Add a TMDB key in Settings first")
        val url = "$tmdbBase/search/tv?query=${enc(query)}&include_adult=false&language=en-US" +
            (year?.let { "&first_air_date_year=$it" } ?: "")
        return get(url, key, ShowPage.serializer()).results
    }

    /** TMDB pairs some TV genres ("Action & Adventure"); split them into the film genres used everywhere else. */
    private fun showGenres(name: String): List<String> = when (name) {
        "Action & Adventure" -> listOf("Action", "Adventure")
        "Sci-Fi & Fantasy" -> listOf("Sci-Fi", "Fantasy")
        "War & Politics" -> listOf("War")
        else -> listOf(name)
    }

    private fun omdbRating(imdbId: String, key: String): Double? {
        val url = "$omdbBase?i=${enc(imdbId)}&apikey=${enc(key)}"
        val r = try {
            decode(request(url, null), Omdb.serializer())
        } catch (e: KeyRejected) {
            throw KeyRejected("OMDb rejected the key")
        }
        if (r.response == "False" && r.error?.contains("API key", ignoreCase = true) == true) throw KeyRejected("OMDb rejected the key")
        return r.imdbRating?.toDoubleOrNull()
    }

    private fun searchHits(query: String, year: Int?): List<SearchHit> {
        val key = prefs.tmdbKey ?: throw KeyRejected("Add a TMDB key in Settings first")
        val url = "$tmdbBase/search/movie?query=${enc(query)}&include_adult=false&language=en-US" +
            (year?.let { "&year=$it" } ?: "")
        return get(url, key, SearchPage.serializer()).results
    }

    /** The Indian age rating when TMDB has one (U, UA 13+, A…), else the American one. */
    private fun certification(dates: ReleaseDates?): String? {
        val byCountry = dates?.results.orEmpty().associateBy { it.country }
        for (country in listOf("IN", "US")) {
            val cert = byCountry[country]?.dates.orEmpty()
                .sortedBy { if (it.type == 3) 0 else 1 }
                .firstOrNull { it.certification.isNotBlank() }?.certification?.trim()
            if (cert != null) return cert
        }
        return null
    }

    private fun genreName(name: String) = when (name) {
        "Science Fiction" -> "Sci-Fi"
        "TV Movie" -> "TV movie"
        else -> name
    }

    private fun year(date: String?): Int? = date?.take(4)?.toIntOrNull()

    private fun <T> get(url: String, key: String, serializer: KSerializer<T>): T {
        // A v4 read access token is a long JWT; a v3 API key is a short hex string.
        val bearer = key.startsWith("eyJ")
        val full = if (bearer) url else url + (if ('?' in url) "&" else "?") + "api_key=" + enc(key)
        return decode(request(full, if (bearer) key else null), serializer)
    }

    private fun <T> decode(text: String, serializer: KSerializer<T>): T = try {
        ApiJson.decodeFromString(serializer, text)
    } catch (e: Exception) {
        throw IOException("Unexpected answer from the lookup service", e)
    }

    /** A request, tried a second time if the network drops it or the service has a passing problem. */
    private fun request(url: String, bearer: String?): String = retryOnce { requestOnce(url, bearer) }

    private fun tryDownload(url: String): ByteArray? = try {
        retryOnce { downloadOnce(url) }
    } catch (e: IOException) {
        Log.w(TAG, "Picture didn't download: $url", e)
        null
    }

    private fun <T> retryOnce(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        if (e is KeyRejected || e is Refused) throw e
        Log.i(TAG, "Lookup failed (${e.message}), trying once more")
        Thread.sleep(RETRY_DELAY_MS)
        block()
    }

    /** At most four requests a second: TMDB allows far more, but there's no hurry. */
    @Synchronized
    private fun requestOnce(url: String, bearer: String?): String {
        val wait = lastRequestAt + 250 - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.setRequestProperty("Accept", "application/json")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            val code = c.responseCode
            if (code == 401) throw KeyRejected("TMDB rejected the key. Check it in Settings.")
            if (code == 429) {
                val retry = c.getHeaderField("Retry-After")?.toLongOrNull() ?: 2
                Thread.sleep(retry.coerceIn(1, 10) * 1000)
                throw IOException("TMDB asked to slow down")
            }
            if (code in 400..499) throw Refused("The lookup service answered $code")
            if (code !in 200..299) throw IOException("The lookup service answered $code")
            return c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    private fun downloadOnce(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            val code = c.responseCode
            if (code in 400..499) throw Refused("Image answered $code")
            if (code != 200) throw IOException("Image answered $code")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "HomeReel"
        const val RETRY_DELAY_MS = 1500L
    }

    @Serializable private class Configuration
    @Serializable private class SearchPage(val results: List<SearchHit> = emptyList())

    @Serializable
    private class SearchHit(
        val id: Int,
        val title: String = "",
        @SerialName("original_title") val originalTitle: String? = null,
        @SerialName("release_date") val releaseDate: String? = null,
        val overview: String? = null,
        @SerialName("poster_path") val posterPath: String? = null,
    )

    @Serializable
    private class Details(
        val id: Int,
        val title: String = "",
        @SerialName("release_date") val releaseDate: String? = null,
        val overview: String? = null,
        val tagline: String? = null,
        val genres: List<Named> = emptyList(),
        @SerialName("vote_average") val voteAverage: Double? = null,
        @SerialName("vote_count") val voteCount: Int? = null,
        @SerialName("poster_path") val posterPath: String? = null,
        @SerialName("backdrop_path") val backdropPath: String? = null,
        @SerialName("belongs_to_collection") val collection: Named? = null,
        @SerialName("imdb_id") val imdbId: String? = null,
        val credits: Credits? = null,
        @SerialName("release_dates") val releaseDates: ReleaseDates? = null,
    )

    @Serializable private class ShowPage(val results: List<ShowHit> = emptyList())

    @Serializable
    private class ShowHit(
        val id: Int,
        val name: String = "",
        @SerialName("original_name") val originalName: String? = null,
        @SerialName("first_air_date") val firstAirDate: String? = null,
        val overview: String? = null,
        @SerialName("poster_path") val posterPath: String? = null,
    )

    @Serializable
    private class ShowDetails(
        val id: Int,
        val name: String = "",
        @SerialName("first_air_date") val firstAirDate: String? = null,
        @SerialName("last_air_date") val lastAirDate: String? = null,
        @SerialName("in_production") val inProduction: Boolean? = null,
        val overview: String? = null,
        val genres: List<Named> = emptyList(),
        @SerialName("vote_average") val voteAverage: Double? = null,
        @SerialName("vote_count") val voteCount: Int? = null,
        @SerialName("poster_path") val posterPath: String? = null,
        @SerialName("backdrop_path") val backdropPath: String? = null,
        @SerialName("created_by") val createdBy: List<Named> = emptyList(),
        @SerialName("aggregate_credits") val aggregateCredits: Credits? = null,
        @SerialName("content_ratings") val contentRatings: ContentRatings? = null,
        @SerialName("external_ids") val externalIds: ExternalIds? = null,
    )

    @Serializable private class ContentRatings(val results: List<ContentRating> = emptyList())
    @Serializable private class ContentRating(@SerialName("iso_3166_1") val country: String = "", val rating: String = "")
    @Serializable private class ExternalIds(@SerialName("imdb_id") val imdbId: String? = null)
    @Serializable private class SeasonDetails(val episodes: List<SeasonEpisode> = emptyList())

    @Serializable
    private class SeasonEpisode(
        @SerialName("episode_number") val number: Int = 0,
        val name: String? = null,
        val overview: String? = null,
        @SerialName("air_date") val airDate: String? = null,
        @SerialName("still_path") val stillPath: String? = null,
    )

    @Serializable private class Named(val name: String = "")
    @Serializable private class Credits(val cast: List<CastMember> = emptyList(), val crew: List<CrewMember> = emptyList())
    @Serializable private class CastMember(val name: String = "", val order: Int = 999)
    @Serializable private class CrewMember(val name: String = "", val job: String = "")
    @Serializable private class ReleaseDates(val results: List<CountryDates> = emptyList())

    @Serializable
    private class CountryDates(
        @SerialName("iso_3166_1") val country: String = "",
        @SerialName("release_dates") val dates: List<ReleaseDate> = emptyList(),
    )

    @Serializable private class ReleaseDate(val certification: String = "", val type: Int = 0)

    @Serializable
    private class Omdb(
        val imdbRating: String? = null,
        @SerialName("Response") val response: String? = null,
        @SerialName("Error") val error: String? = null,
    )
}

/** Saves a picture as a JPEG no wider than [maxWidth]; decoding it also refuses anything that isn't an image. */
object JpegFiles {
    fun save(bytes: ByteArray, maxWidth: Int, out: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return false
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        val bitmap = if (decoded.width > maxWidth) {
            Bitmap.createScaledBitmap(decoded, maxWidth, decoded.height * maxWidth / decoded.width, true)
        } else {
            decoded
        }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return true
    }
}
