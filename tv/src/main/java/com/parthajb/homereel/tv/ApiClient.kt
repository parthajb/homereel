package com.parthajb.homereel.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.parthajb.homereel.shared.Api
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.ErrorBody
import com.parthajb.homereel.shared.Movie
import com.parthajb.homereel.shared.MovieList
import com.parthajb.homereel.shared.PairRequest
import com.parthajb.homereel.shared.PairResponse
import com.parthajb.homereel.shared.PinCheck
import com.parthajb.homereel.shared.ProfileList
import com.parthajb.homereel.shared.PlayabilityReport
import com.parthajb.homereel.shared.ProgressUpdate
import com.parthajb.homereel.shared.ServerInfo
import com.parthajb.homereel.shared.Show
import com.parthajb.homereel.shared.ShowDetail
import com.parthajb.homereel.shared.ShowList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

open class ApiException(val code: Int, message: String) : IOException(message)

class Unauthorized(message: String) : ApiException(401, message)

/** Talks to the phone. Every call blocks, so call it off the main thread. */
/** [profileId] goes with every request, so progress, My List and kids filtering follow whoever is watching. */
class ApiClient(val host: String, val port: Int, private val token: String?, private val profileId: String? = null) {
    private val hostPart = if (host.contains(':')) "[$host]" else host
    val base = "http://$hostPart:$port"

    fun url(path: String) = base + Api.PREFIX + path

    fun headers(): Map<String, String> = buildMap {
        token?.let { put("Authorization", "Bearer $it") }
        profileId?.let { put("X-Profile", it) }
    }

    fun info(): ServerInfo = get("/info", ServerInfo.serializer())

    fun movies(): List<Movie> = get("/movies", MovieList.serializer()).movies

    fun movie(id: String): Movie = get("/movies/$id", Movie.serializer())

    fun shows(): List<Show> = get("/shows", ShowList.serializer()).shows

    fun profiles(): ProfileList = get("/profiles", ProfileList.serializer())

    /** Throws [ApiException] (403) for a wrong PIN. */
    fun checkPin(pin: String) {
        request("POST", "/profiles/pin", ApiJson.encodeToString(PinCheck.serializer(), PinCheck(pin)))
    }

    fun myList(): List<String> = get("/me/list", ListSerializer(String.serializer()))

    fun setInList(key: String, on: Boolean) {
        request(if (on) "PUT" else "DELETE", "/me/list/$key", null)
    }

    fun show(id: String): ShowDetail = get("/shows/$id", ShowDetail.serializer())

    fun pair(code: String, client: String): PairResponse = decode(
        request("POST", "/pair", ApiJson.encodeToString(PairRequest.serializer(), PairRequest(code, client))),
        PairResponse.serializer(),
    )

    fun progress(id: String, positionMs: Long, durationMs: Long) {
        request("PUT", "/movies/$id/progress", ApiJson.encodeToString(ProgressUpdate.serializer(), ProgressUpdate(positionMs, durationMs)))
    }

    fun rescan() {
        request("POST", "/rescan", null)
    }

    fun report(report: PlayabilityReport) {
        request("PUT", "/playability", ApiJson.encodeToString(PlayabilityReport.serializer(), report))
    }

    /**
     * A picture scaled down to about [width] px wide while decoding, so a big JPEG never sits in memory whole.
     * Backdrops use RGB_565, half the memory, since they sit under a dark gradient anyway.
     */
    fun image(path: String, width: Int, lowColour: Boolean): Bitmap? {
        val bytes = open("GET", path, null).let { c ->
            try {
                if (c.responseCode != 200) return null
                c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= width) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            if (lowColour) inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun <T> get(path: String, serializer: KSerializer<T>): T = decode(request("GET", path, null), serializer)

    private fun <T> decode(text: String, serializer: KSerializer<T>): T = try {
        ApiJson.decodeFromString(serializer, text)
    } catch (e: Exception) {
        throw IOException("The phone sent something this app doesn't understand. Update both apps.", e)
    }

    private fun request(method: String, path: String, body: String?): String {
        val c = open(method, path, body)
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { it.readBytes().decodeToString() } ?: ""
            if (code in 200..299) return text
            val message = runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), text).error }.getOrNull()
                ?: "The phone answered $code"
            if (code == 401) throw Unauthorized(message)
            throw ApiException(code, message)
        } finally {
            c.disconnect()
        }
    }

    private fun open(method: String, path: String, body: String?): HttpURLConnection {
        requireLocal(host)
        val c = URL(url(path)).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 15000
        c.instanceFollowRedirects = false
        headers().forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) {
            val bytes = body.toByteArray()
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(bytes.size)
            c.outputStream.use { it.write(bytes) }
        }
        return c
    }

    companion object {
        /** Plain HTTP is only acceptable on the home network: refuse anything that resolves outside it. */
        fun requireLocal(host: String) {
            val address = InetAddress.getByName(host)
            val ula = address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc
            if (!(address.isSiteLocalAddress || address.isLinkLocalAddress || address.isLoopbackAddress || ula)) {
                throw IOException("$host isn't on your home network")
            }
        }
    }
}

/** The paired phone: its address, name and token. */
class ServerStore(context: Context) {
    private val sp = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    val host: String? get() = sp.getString("host", null)
    val port: Int get() = sp.getInt("port", Api.DEFAULT_PORT)
    val name: String? get() = sp.getString("name", null)
    private val token: String? get() = sp.getString("token", null)

    val paired: Boolean get() = host != null && token != null

    fun api(): ApiClient = ApiClient(host!!, port, token, profileId)

    /** The profile watching on this TV; null until one is picked. */
    val profileId: String? get() = sp.getString("profile", null)
    val profileName: String? get() = sp.getString("profileName", null)
    val profileKids: Boolean get() = sp.getBoolean("profileKids", false)

    fun setProfile(id: String, name: String, kids: Boolean) {
        sp.edit().putString("profile", id).putString("profileName", name).putBoolean("profileKids", kids).apply()
    }

    fun save(host: String, port: Int, name: String, token: String) {
        sp.edit().putString("host", host).putInt("port", port).putString("name", name).putString("token", token).apply()
    }

    /** The phone's address changed (DHCP); the token stays valid. */
    fun moveTo(host: String, port: Int) {
        sp.edit().putString("host", host).putInt("port", port).apply()
    }

    fun forget() {
        sp.edit().clear().apply()
    }
}

/** Settings that belong to this TV rather than the phone. */
class TvPrefs(context: Context) {
    private val sp = context.getSharedPreferences("tv", Context.MODE_PRIVATE)

    /** Switch the TV to the film's frame rate (24p for most films), so pans don't judder. */
    var matchFrameRate: Boolean
        get() = sp.getBoolean("matchFrameRate", true)
        set(value) = sp.edit().putBoolean("matchFrameRate", value).apply()
}
