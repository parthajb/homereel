package com.parthajb.homereel.server

import android.util.Log
import com.parthajb.homereel.shared.IndexerSettings
import com.parthajb.homereel.shared.IndexerUpdate
import com.parthajb.homereel.shared.TorrentItem
import com.parthajb.homereel.shared.TorrentSearchPage
import com.parthajb.homereel.shared.TorrentSearchResult
import com.parthajb.homereel.shared.Torrents
import com.parthajb.homereel.shared.Torznab
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * Torrent search through a Torznab indexer the admin sets up (Search Magnet Link, Jackett, Prowlarr…), with the
 * result handed to [TorrentManager] like a magnet link pasted by hand. Only searches when asked: nothing is crawled,
 * and searching never starts the torrent engine.
 *
 * Indexers limit requests (Search Magnet Link: 2 a second, 100 an hour, 1,000 a day), so HomeReel stays under its
 * own, lower budget, kept across restarts, and repeats of the same search come from a 15-minute cache.
 */
class TorrentSearch(private val prefs: Prefs, private val torrents: TorrentManager) {
    private val cache = object : LinkedHashMap<String, Pair<Long, Torznab.Parsed>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Torznab.Parsed>>) = size > 100
    }

    /** What each recent result pointed at, so adding one needs only its hash from the browser. */
    private val seen = object : LinkedHashMap<String, Torznab.Item>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Torznab.Item>) = size > 1000
    }

    fun settings() = IndexerSettings(prefs.indexerUrl, prefs.indexerKey != null)

    fun update(u: IndexerUpdate): IndexerSettings {
        if (u.clearKey && u.apiKey != null) throw HttpError(400, "Either a new key or Remove key, not both")
        u.url?.let { raw ->
            val url = raw.trim().trimEnd('/')
            if (url.isEmpty()) {
                prefs.indexerUrl = null
            } else {
                val parsed = runCatching { URL(url) }.getOrNull()
                if (parsed == null || parsed.protocol !in setOf("http", "https") || parsed.host.isNullOrBlank() || parsed.query != null) {
                    throw HttpError(400, "The indexer address should look like https://www.searchmagnetlink.com")
                }
                if (parsed.protocol == "http" && !isPrivate(parsed.host)) {
                    throw HttpError(400, "Use https:// for an indexer on the internet, so the key isn't sent in the clear. Plain http:// is for one on your home network.")
                }
                prefs.indexerUrl = url
            }
        }
        if (u.clearKey) prefs.indexerKey = null
        u.apiKey?.trim()?.let { if (it.isEmpty()) throw HttpError(400, "The key is empty") else prefs.indexerKey = it }
        synchronized(this) { cache.clear() }
        return settings()
    }

    fun search(query: String, category: String?, offset: Int): TorrentSearchPage {
        if (!torrents.settings.enabled) throw HttpError(409, "Turn on torrent downloads in Settings first")
        val base = prefs.indexerUrl ?: throw HttpError(409, "Add a torrent indexer in Settings → Torrent downloads first")
        val q = query.trim()
        if (q.length !in 2..200) throw HttpError(400, "Type 2 to 200 characters to search for")
        val cat = when (category) {
            null, "", "all" -> null
            "movies" -> "2000"
            "tv" -> "5000"
            else -> throw HttpError(400, "Unknown category")
        }
        val start = offset.coerceIn(0, 1000)
        val key = "$q|$cat|$start"
        val parsed = synchronized(this) { cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second }
            ?: fetch(base, q, cat, start).also { p -> if (p.error == null) synchronized(this) { cache[key] = System.currentTimeMillis() to p } }
        parsed.error?.let { throw HttpError(502, it) }
        synchronized(this) { parsed.items.forEach { seen[it.infoHash] = it } }
        val items = parsed.items.distinctBy { it.infoHash }.map {
            TorrentSearchResult(it.infoHash, it.name, it.size, it.seeders, it.peers, it.published, torrents.has(it.infoHash))
        }
        return TorrentSearchPage(q, items, start, more = parsed.items.size + parsed.skipped >= PAGE, skipped = parsed.skipped)
    }

    /** Adds a result to Downloads: its magnet (cleaned), or its .torrent file when the indexer only gives that. */
    fun add(infoHash: String, private: Boolean): TorrentItem {
        val hash = Torznab.normaliseHash(infoHash) ?: throw HttpError(400, "That isn't an info hash")
        val item = synchronized(this) { seen[hash] } ?: throw HttpError(409, "Search again; that result is too old")
        if (torrents.has(hash)) throw HttpError(409, "${item.name} is already in Downloads")
        val base = prefs.indexerUrl
        val link = item.link?.takeIf { item.magnet == null && base != null && sameHost(it, base) }
        if (link != null) {
            // Only from the indexer's own address: a result can't send the phone anywhere else.
            return torrents.add(download(link), private)
        }
        return torrents.addMagnet(Torznab.cleanMagnet(item.magnet, hash, item.name), private)
    }

    // ---- The indexer ----

    private fun fetch(base: String, q: String, cat: String?, offset: Int): Torznab.Parsed {
        val apiKey = prefs.indexerKey ?: throw HttpError(409, "Add the indexer's API key in Settings → Torrent downloads first")
        spend()
        val url = buildString {
            append(base).append("/api?t=search&q=").append(URLEncoder.encode(q, "UTF-8"))
            append("&limit=").append(PAGE).append("&offset=").append(offset)
            cat?.let { append("&cat=").append(it) }
            append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
        }
        val body = try {
            get(url, apiKey, MAX_REPLY)
        } catch (e: HttpError) {
            throw e
        } catch (e: IOException) {
            throw HttpError(503, "Couldn't reach the indexer: ${e.message?.replace(apiKey, "…")}")
        }
        return Torznab.parse(body)
    }

    private fun download(link: String): ByteArray {
        spend()
        return try {
            get(link, prefs.indexerKey, Torrents.MAX_TORRENT_BYTES)
        } catch (e: IOException) {
            throw HttpError(503, "Couldn't get the .torrent from the indexer: ${e.message}")
        }
    }

    private fun get(url: String, apiKey: String?, limit: Int): ByteArray {
        val target = URL(url)
        // Checked again at request time: a home-network name could point elsewhere now.
        if (target.protocol == "http" && !isPrivate(target.host)) throw HttpError(400, "Plain http:// only works for an indexer on your home network")
        val c = target.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5_000
            c.readTimeout = 12_000
            c.instanceFollowRedirects = false // a redirect could point anywhere
            apiKey?.let { c.setRequestProperty("X-Api-Key", it) }
            c.setRequestProperty("User-Agent", "HomeReel")
            when (val code = c.responseCode) {
                200 -> Unit
                401, 403 -> throw HttpError(503, "The indexer refused the API key. Check it in Settings → Torrent downloads.")
                429 -> {
                    prefs.indexerCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                    throw HttpError(429, "The indexer says too many searches; HomeReel waits 15 minutes before the next one.")
                }
                else -> throw HttpError(503, "The indexer answered $code")
            }
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw HttpError(502, "The indexer's reply is too big")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    /** One request a second at most, 90 an hour and 900 a day: under Search Magnet Link's limits, kept across restarts. */
    @Synchronized
    private fun spend() {
        val now = System.currentTimeMillis()
        val cooldown = prefs.indexerCooldownUntil
        if (now < cooldown) throw HttpError(429, "Waiting ${(cooldown - now) / 60_000 + 1} min before searching again (the indexer asked).")
        val times = prefs.indexerRequests.filter { now - it < DAY_MS }
        if (times.count { now - it < HOUR_MS } >= PER_HOUR) throw HttpError(429, "That's $PER_HOUR searches this hour; try again later.")
        if (times.size >= PER_DAY) throw HttpError(429, "That's $PER_DAY searches today; try again tomorrow.")
        times.maxOrNull()?.let { last -> (last + 1000 - now).takeIf { it > 0 }?.let(Thread::sleep) }
        prefs.indexerRequests = times + System.currentTimeMillis()
        Log.i(TAG, "Indexer request ${times.size + 1} today")
    }

    /** Whether every address [host] resolves to is on the home network (or this phone). */
    private fun isPrivate(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host).all { it.isSiteLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }
    }.getOrDefault(false)

    private fun sameHost(link: String, base: String): Boolean = runCatching {
        val a = URL(link)
        val b = URL(base)
        a.protocol in setOf("http", "https") && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }.getOrDefault(false)

    private companion object {
        const val TAG = "HomeReel"
        const val PAGE = 50
        const val MAX_REPLY = 2 * 1024 * 1024
        const val CACHE_MS = 15 * 60_000L
        const val COOLDOWN_MS = 15 * 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val PER_HOUR = 90
        const val PER_DAY = 900
    }
}
