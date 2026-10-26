package com.parthajb.homereel.server

import android.util.Log
import com.parthajb.homereel.shared.IndexerCheck
import com.parthajb.homereel.shared.IndexerKind
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
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Torrent search through the indexers the admin set up, with the result handed to [TorrentManager] like a magnet
 * link pasted by hand. Only searches when asked: nothing is crawled, and searching never starts the torrent engine.
 *
 * - **Prowlarr:** its own search (`/api/v1/search`), which asks every indexer switched on in it.
 * - **Jackett:** its "all indexers" Torznab feed, unless the address is one indexer's feed.
 * - **Any other Torznab feed**, such as Search Magnet Link: that feed alone.
 *
 * Prowlarr and Jackett give download links on their own address, which they answer with the .torrent or a redirect
 * to a magnet. Those are the only links the phone fetches.
 *
 * Indexers limit requests (Search Magnet Link: 2 a second, 100 an hour, 1,000 a day), so HomeReel stays under its
 * own, lower budget, kept across restarts, and repeats of the same search come from a 15-minute cache.
 */
class TorrentSearch(private val prefs: Prefs, private val torrents: TorrentManager) {
    private val cache = object : LinkedHashMap<String, Pair<Long, Torznab.Parsed>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Torznab.Parsed>>) = size > 100
    }

    /** What each recent result pointed at, so adding one needs only its id from the browser. */
    private val seen = object : LinkedHashMap<String, Torznab.Item>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Torznab.Item>) = size > 1000
    }

    /** Where to search, as the admin set it up. */
    private data class Source(val kind: String, val url: String, val key: String)

    fun settings() = IndexerSettings(prefs.indexerKind, prefs.indexerUrl, prefs.indexerKey != null)

    fun update(u: IndexerUpdate): IndexerSettings {
        if (u.clearKey && u.apiKey != null) throw HttpError(400, "Either a new key or Remove key, not both")
        val kind = kindOf(u.kind ?: prefs.indexerKind)
        var keyFromUrl: String? = null
        u.url?.let { raw ->
            if (raw.isBlank()) {
                prefs.indexerUrl = null
            } else {
                val (url, key) = cleanUrl(raw, kind)
                prefs.indexerUrl = url
                keyFromUrl = key
            }
        }
        prefs.indexerKind = kind
        if (u.clearKey) prefs.indexerKey = null
        (u.apiKey ?: keyFromUrl)?.trim()?.let { if (it.isEmpty()) throw HttpError(400, "The key is empty") else prefs.indexerKey = it }
        synchronized(this) { cache.clear() }
        return settings()
    }

    /** Tries the address and key, typed or saved, without saving them: does it answer, and which indexers will it ask? */
    fun check(u: IndexerUpdate): IndexerCheck {
        val kind = kindOf(u.kind ?: prefs.indexerKind)
        val (url, keyFromUrl) = cleanUrl(u.url?.takeIf { it.isNotBlank() } ?: prefs.indexerUrl ?: throw HttpError(400, "Type the address first"), kind)
        val key = u.apiKey?.trim()?.ifEmpty { null } ?: keyFromUrl ?: prefs.indexerKey ?: throw HttpError(400, "Paste the API key first")
        val source = Source(kind, url, key)
        return try {
            val names = when (kind) {
                IndexerKind.PROWLARR -> {
                    val body = (request(source, "${prowlarrRoot(url)}/api/v1/indexer", SEARCH_TIMEOUT_MS) as Reply.Body).bytes
                    Torznab.parseProwlarrIndexers(body)
                        ?: throw HttpError(502, "That address answered, but not like Prowlarr does. Is it the address you open Prowlarr at?")
                }
                else -> {
                    val all = kind == IndexerKind.JACKETT && "/indexers/all/" in torznabApi(source)
                    val query = if (all) "t=indexers&configured=true" else "t=caps"
                    val body = (request(source, "${torznabApi(source)}?$query&apikey=${enc(key)}", SEARCH_TIMEOUT_MS) as Reply.Body).bytes
                    Torznab.parseIndexers(body).getOrElse { throw HttpError(502, "${name(kind)} answered, but: ${it.message}") }
                }
            }
            when {
                names.isEmpty() && kind == IndexerKind.PROWLARR ->
                    IndexerCheck(false, "Prowlarr answered, but has no torrent indexers switched on. Add some under Indexers in Prowlarr.")
                names.isEmpty() ->
                    IndexerCheck(false, "Jackett answered, but has no indexers set up. Add some with + Add indexer in Jackett.")
                kind == IndexerKind.TORZNAB -> IndexerCheck(true, "Connected to ${names.first()}.", names)
                else -> IndexerCheck(true, "Connected to ${name(kind)}. Searches ask ${names.size} indexer${if (names.size == 1) "" else "s"}.", names)
            }
        } catch (e: HttpError) {
            IndexerCheck(false, e.message ?: "It didn't work")
        }
    }

    fun search(query: String, category: String?, offset: Int): TorrentSearchPage {
        if (!torrents.settings.enabled) throw HttpError(409, "Turn on torrent downloads in Settings first")
        val source = source()
        val q = query.trim()
        if (q.length !in 2..200) throw HttpError(400, "Type 2 to 200 characters to search for")
        val cat = when (category) {
            null, "", "all" -> null
            "movies" -> "2000"
            "tv" -> "5000"
            else -> throw HttpError(400, "Unknown category")
        }
        // Prowlarr and Jackett gather every indexer's results into one answer: there are no further pages.
        val pages = source.kind == IndexerKind.TORZNAB
        val start = if (pages) offset.coerceIn(0, 1000) else 0
        val key = "${source.kind}|${source.url}|$q|$cat|$start"
        val parsed = synchronized(this) { cache[key]?.takeIf { System.currentTimeMillis() - it.first < CACHE_MS }?.second }
            ?: fetch(source, q, cat, start).also { p -> if (p.error == null) synchronized(this) { cache[key] = System.currentTimeMillis() to p } }
        parsed.error?.let { throw HttpError(502, it) }
        // A result with only a download link can be added only if the link is on the indexer's own address.
        val usable = parsed.items.filter { (it.magnet != null && it.infoHash != null) || it.link?.let { l -> sameHost(l, source.url) } == true }
        synchronized(this) { usable.forEach { seen[it.id] = it } }
        val items = usable.distinctBy { it.id }
            .let { list -> if (pages) list else list.sortedByDescending { it.seeders ?: -1 } }
            .map {
                TorrentSearchResult(it.id, it.infoHash, it.name, it.size, it.seeders, it.peers, it.published,
                    it.infoHash?.let(torrents::has) ?: false, it.indexer)
            }
        val skipped = parsed.skipped + parsed.items.size - usable.size
        return TorrentSearchPage(q, items, start, more = pages && parsed.items.size + parsed.skipped >= PAGE, skipped = skipped)
    }

    /** Adds a result to Downloads: its magnet (cleaned), or what its download link gives, a .torrent or a magnet. */
    fun add(id: String, private: Boolean): TorrentItem {
        val item = synchronized(this) { seen[id.trim().lowercase()] } ?: throw HttpError(409, "Search again; that result is too old")
        val hash = item.infoHash
        if (hash != null && torrents.has(hash)) throw HttpError(409, "${item.name} is already in Downloads")
        if (item.magnet != null && hash != null) return torrents.addMagnet(Torznab.cleanMagnet(item.magnet, hash, item.name), private)
        val source = source()
        val link = item.link?.takeIf { sameHost(it, source.url) }
            // Only from the indexer's own address: a result can't send the phone anywhere else.
            ?: throw HttpError(409, "That result's download link isn't on ${name(source.kind)}'s address, so HomeReel won't fetch it")
        spend()
        return when (val reply = request(source, link, DOWNLOAD_TIMEOUT_MS, Torrents.MAX_TORRENT_BYTES, magnetOk = true)) {
            is Reply.Body -> torrents.add(reply.bytes, private)
            is Reply.Magnet -> {
                val got = Torznab.hashFromMagnet(reply.uri) ?: throw HttpError(502, "The indexer's link led to a magnet HomeReel can't read")
                if (hash != null && got != hash) throw HttpError(502, "The indexer's link was for a different torrent than the result")
                if (torrents.has(got)) throw HttpError(409, "${item.name} is already in Downloads")
                torrents.addMagnet(Torznab.cleanMagnet(reply.uri, got, item.name), private)
            }
        }
    }

    // ---- The indexer ----

    private fun source(): Source {
        val url = prefs.indexerUrl ?: throw HttpError(409, "Set up torrent search in Settings → Downloads first")
        val key = prefs.indexerKey ?: throw HttpError(409, "Add the API key in Settings → Downloads first")
        return Source(kindOf(prefs.indexerKind), url, key)
    }

    private fun fetch(source: Source, q: String, cat: String?, offset: Int): Torznab.Parsed {
        spend()
        val url = when (source.kind) {
            IndexerKind.PROWLARR -> buildString {
                append(prowlarrRoot(source.url)).append("/api/v1/search?type=search&query=").append(enc(q))
                append("&limit=").append(AGGREGATE_LIMIT).append("&offset=0")
                cat?.let { append("&categories=").append(it) }
            }
            else -> buildString {
                append(torznabApi(source)).append("?t=search&q=").append(enc(q))
                append("&limit=").append(if (source.kind == IndexerKind.JACKETT) AGGREGATE_LIMIT else PAGE).append("&offset=").append(offset)
                cat?.let { append("&cat=").append(it) }
                append("&apikey=").append(enc(source.key))
            }
        }
        val body = (request(source, url, SEARCH_TIMEOUT_MS) as Reply.Body).bytes
        return if (source.kind == IndexerKind.PROWLARR) Torznab.parseProwlarr(body) else Torznab.parse(body)
    }

    private sealed interface Reply {
        class Body(val bytes: ByteArray) : Reply
        class Magnet(val uri: String) : Reply
    }

    /**
     * One request, following redirects by hand: on the indexer's own address (with the key), to a public https://
     * address (without it), or, for a download link, to a magnet. Never to another address on the home network.
     */
    private fun request(source: Source, url: String, readTimeout: Int, limit: Int = MAX_REPLY, magnetOk: Boolean = false): Reply {
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            val own = sameHost(target.toString(), source.url)
            // Checked again at request time: a home-network name could point elsewhere now.
            if (target.protocol == "http" && !isPrivate(target.host)) throw HttpError(400, "Plain http:// only works for an indexer on your home network")
            if (!own && (target.protocol != "https" || isPrivate(target.host))) throw HttpError(502, "The indexer sent HomeReel to another address, which it won't follow")
            val c = target.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 5_000
                c.readTimeout = readTimeout
                c.instanceFollowRedirects = false // a redirect could point anywhere: checked above, hop by hop
                if (own) c.setRequestProperty("X-Api-Key", source.key)
                c.setRequestProperty("User-Agent", "HomeReel")
                val code = try {
                    c.responseCode
                } catch (e: SocketTimeoutException) {
                    throw HttpError(504, "${name(source.kind)} took too long to answer. Try again, or switch off slow indexers in it.")
                } catch (e: IOException) {
                    throw HttpError(503, "Couldn't reach ${name(source.kind)} at ${source.url}: ${e.message?.replace(source.key, "…")}")
                }
                when (code) {
                    200 -> return Reply.Body(read(c, limit, source))
                    301, 302, 303, 307, 308 -> {
                        val location = c.getHeaderField("Location") ?: throw HttpError(502, "The indexer answered $code without saying where to")
                        if (Torrents.isMagnet(location)) {
                            if (!magnetOk) throw HttpError(502, "The indexer answered with a magnet link instead of results")
                            return Reply.Magnet(location)
                        }
                        target = URL(target, location)
                    }
                    401, 403 -> throw HttpError(503, "${name(source.kind)} refused the API key. Check it in Settings → Downloads.")
                    404 -> throw HttpError(503, when (source.kind) {
                        IndexerKind.PROWLARR -> "No Prowlarr at ${source.url} (it answered 404). Use the address you open Prowlarr at, like http://192.168.1.20:9696."
                        IndexerKind.JACKETT -> "No Jackett at ${source.url} (it answered 404). Use the address you open Jackett at, like http://192.168.1.20:9117."
                        else -> "Nothing at that address (it answered 404). Check the Torznab address."
                    })
                    429 -> {
                        prefs.indexerCooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
                        throw HttpError(429, "The indexer says too many searches; HomeReel waits 15 minutes before the next one.")
                    }
                    else -> throw HttpError(503, "${name(source.kind)} answered $code")
                }
            } finally {
                c.disconnect()
            }
        }
        throw HttpError(502, "The indexer redirected too many times")
    }

    private fun read(c: HttpURLConnection, limit: Int, source: Source): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw HttpError(502, "The indexer's reply is too big")
                }
            }
        } catch (e: SocketTimeoutException) {
            throw HttpError(504, "${name(source.kind)} took too long to answer. Try again, or switch off slow indexers in it.")
        }
        val head = out.toByteArray().take(200).toByteArray().decodeToString().trimStart().lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            throw HttpError(502, "That address answered with a web page, not search results. Check the address, and that ${name(source.kind)} is the right choice.")
        }
        return out.toByteArray()
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

    // ---- Addresses ----

    private fun kindOf(kind: String) = kind.takeIf { it in IndexerKind.ALL } ?: throw HttpError(400, "Unknown kind of indexer")

    /**
     * The address as typed, tidied: no trailing slash, and no query, whose `apikey` (as in a copied Jackett feed
     * address) is returned so it can become the key.
     */
    private fun cleanUrl(raw: String, kind: String): Pair<String, String?> {
        val parsed = runCatching { URL(raw.trim()) }.getOrNull()
        if (parsed == null || parsed.protocol !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            throw HttpError(400, "The address should look like ${example(kind)}")
        }
        if (parsed.protocol == "http" && !isPrivate(parsed.host)) {
            throw HttpError(400, "Use https:// for an indexer on the internet, so the key isn't sent in the clear. Plain http:// is for one on your home network.")
        }
        val key = parsed.query?.split('&')?.firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=').lowercase()
            if (name == "apikey" || name == "jackett_apikey") runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()?.ifBlank { null } else null
        }
        val url = "${parsed.protocol}://${parsed.authority}${parsed.path}".trimEnd('/')
        return url to key
    }

    /** Prowlarr's own address, from it or from one of its per-indexer feed addresses (…/1/api). */
    private fun prowlarrRoot(url: String) = url.replace(Regex("(/\\d+)?(/api(/v1)?)?/?$"), "")

    /** The Torznab endpoint: Jackett's all-indexers feed when the address is just Jackett's; the address itself otherwise. */
    private fun torznabApi(source: Source): String {
        val base = source.url.removeSuffix("/api")
        if (source.kind == IndexerKind.JACKETT && "/torznab" !in base) {
            return "${base.replace(Regex("/(UI/Dashboard|api/v2\\.0)$"), "")}/api/v2.0/indexers/all/results/torznab/api"
        }
        return "$base/api"
    }

    private fun name(kind: String) = when (kind) {
        IndexerKind.PROWLARR -> "Prowlarr"
        IndexerKind.JACKETT -> "Jackett"
        else -> "The indexer"
    }

    private fun example(kind: String) = when (kind) {
        IndexerKind.PROWLARR -> "http://192.168.1.20:9696"
        IndexerKind.JACKETT -> "http://192.168.1.20:9117"
        else -> "https://indexer.example/api"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Whether every address [host] resolves to is on the home network, this phone, or a Tailscale network. */
    private fun isPrivate(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host).all {
            it.isSiteLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || isCarrierGrade(it) ||
                (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc) // IPv6 unique local, fc00::/7
        }
    }.getOrDefault(false)

    /** 100.64.0.0/10, which Tailscale uses for the devices on a tailnet. */
    private fun isCarrierGrade(a: InetAddress) =
        a is Inet4Address && a.address[0].toInt() == 100 && (a.address[1].toInt() and 0xc0) == 64

    private fun sameHost(link: String, base: String): Boolean = runCatching {
        val a = URL(link)
        val b = URL(base)
        a.protocol in setOf("http", "https") && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }.getOrDefault(false)

    private companion object {
        const val TAG = "HomeReel"
        const val PAGE = 50
        /** Prowlarr and Jackett: results asked for in one go, across every indexer. */
        const val AGGREGATE_LIMIT = 100
        const val MAX_REPLY = 4 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        /** Prowlarr and Jackett wait for their slowest indexer. */
        const val SEARCH_TIMEOUT_MS = 60_000
        const val DOWNLOAD_TIMEOUT_MS = 30_000
        const val CACHE_MS = 15 * 60_000L
        const val COOLDOWN_MS = 15 * 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
        const val PER_HOUR = 90
        const val PER_DAY = 900
    }
}
