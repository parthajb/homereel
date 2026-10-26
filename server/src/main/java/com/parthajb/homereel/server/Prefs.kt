package com.parthajb.homereel.server

import android.content.Context
import android.net.Uri
import android.os.Build
import com.parthajb.homereel.shared.Api
import java.security.SecureRandom

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("homereel", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = sp.getString("tree", null)?.let(Uri::parse)
        set(value) = sp.edit().putString("tree", value?.toString()).apply()

    /** More folders the library also reads, such as a USB drive. Uploads and Private stay in [treeUri]. */
    var extraTrees: List<Uri>
        get() = sp.getString("extraTrees", null)?.split('\n')?.filter { it.isNotBlank() }?.map(Uri::parse).orEmpty()
        set(value) = sp.edit().putString("extraTrees", value.joinToString("\n")).apply()

    /** False after the person taps Stop, so a reboot doesn't start the server again. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(value) = sp.edit().putBoolean("enabled", value).apply()

    val pairCode: String
        get() = sp.getString("code", null) ?: newPairCode()

    fun newPairCode(): String {
        val code = "%06d".format(SecureRandom().nextInt(1_000_000))
        sp.edit().putString("code", code).apply()
        return code
    }

    val serverName: String
        get() = sp.getString("name", null) ?: "HomeReel on ${Build.MODEL}"

    val port: Int
        get() = sp.getInt("port", Api.DEFAULT_PORT)

    var clientsJson: String?
        get() = sp.getString("clients", null)
        set(value) = sp.edit().putString("clients", value).apply()

    var uploadsJson: String?
        get() = sp.getString("uploads", null)
        set(value) = sp.edit().putString("uploads", value).apply()

    /** A TMDB v3 API key or v4 read access token. Never sent to the TV or the admin page. */
    var tmdbKey: String?
        get() = sp.getString("tmdbKey", null)
        set(value) = sp.edit().putString("tmdbKey", value?.ifBlank { null }).apply()

    var omdbKey: String?
        get() = sp.getString("omdbKey", null)
        set(value) = sp.edit().putString("omdbKey", value?.ifBlank { null }).apply()

    var autoMatch: Boolean
        get() = sp.getBoolean("autoMatch", true)
        set(value) = sp.edit().putBoolean("autoMatch", value).apply()

    /** Debug builds point these at a fake TMDB for tests; release builds always use the real services. */
    var tmdbBaseUrl: String
        get() = sp.getString("tmdbBase", null) ?: "https://api.themoviedb.org/3"
        set(value) = sp.edit().putString("tmdbBase", value).apply()

    var tmdbImageBaseUrl: String
        get() = sp.getString("tmdbImageBase", null) ?: "https://image.tmdb.org/t/p"
        set(value) = sp.edit().putString("tmdbImageBase", value).apply()

    var omdbBaseUrl: String
        get() = sp.getString("omdbBase", null) ?: "https://www.omdbapi.com/"
        set(value) = sp.edit().putString("omdbBase", value).apply()

    var autoOptimize: Boolean
        get() = sp.getBoolean("autoOptimize", true)
        set(value) = sp.edit().putBoolean("autoOptimize", value).apply()

    /** When the Private folder locks itself again; 0 when locked. */
    var privateUntil: Long
        get() = sp.getLong("privateUntil", 0)
        set(value) = sp.edit().putLong("privateUntil", value).apply()

    var profilesJson: String?
        get() = sp.getString("profiles", null)
        set(value) = sp.edit().putString("profiles", value).apply()

    var torrentSettingsJson: String?
        get() = sp.getString("torrentSettings", null)
        set(value) = sp.edit().putString("torrentSettings", value).apply()

    /** What [indexerUrl] is: Prowlarr, Jackett, or any other Torznab feed (see IndexerKind). */
    var indexerKind: String
        get() = sp.getString("indexerKind", null) ?: "torznab"
        set(value) = sp.edit().putString("indexerKind", value).apply()

    /** Where torrent search asks: Prowlarr's or Jackett's address, or a Torznab feed's. */
    var indexerUrl: String?
        get() = sp.getString("indexerUrl", null)
        set(value) = sp.edit().putString("indexerUrl", value).apply()

    /** Never sent to the admin page; the API only says whether it's set. */
    var indexerKey: String?
        get() = sp.getString("indexerKey", null)
        set(value) = sp.edit().putString("indexerKey", value?.ifBlank { null }).apply()

    /** When each indexer request was made in the last day, so the budget survives a restart. */
    var indexerRequests: List<Long>
        get() = sp.getString("indexerRequests", null)?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty()
        set(value) = sp.edit().putString("indexerRequests", value.joinToString(",")).apply()

    var indexerCooldownUntil: Long
        get() = sp.getLong("indexerCooldown", 0)
        set(value) = sp.edit().putLong("indexerCooldown", value).apply()

    var playabilityJson: String?
        get() = sp.getString("playability", null)
        set(value) = sp.edit().putString("playability", value).apply()
}
