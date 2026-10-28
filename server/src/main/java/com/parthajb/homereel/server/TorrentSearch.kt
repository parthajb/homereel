package com.parthajb.homereel.server

import android.util.Log
import com.parthajb.homereel.shared.ExpiringCache
import com.parthajb.homereel.shared.FeedBudget
import com.parthajb.homereel.shared.FeedEdit
import com.parthajb.homereel.shared.FeedSafety
import com.parthajb.homereel.shared.FeedList
import com.parthajb.homereel.shared.IndexerCheck
import com.parthajb.homereel.shared.IndexerKind
import com.parthajb.homereel.shared.SearchFeed
import com.parthajb.homereel.shared.TorrentItem
import com.parthajb.homereel.shared.TorrentSearchPage
import com.parthajb.homereel.shared.TorrentSearchResult
import com.parthajb.homereel.shared.Torrents
import com.parthajb.homereel.shared.Torznab
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Torrent search across the feeds the admin set up, asked together, with the results merged. A chosen result is
 * handed to [TorrentManager] like a magnet link pasted by hand. Only searches when asked: nothing is crawled, and
 * searching never starts the torrent engine.
 *
 * - **Prowlarr:** its own search (`/api/v1/search`), which asks every indexer switched on in it.
 * - **Jackett:** its "all indexers" Torznab feed, unless the address is one indexer's feed.
 * - **A Torznab feed:** one site's own search, such as one that offers Torznab without a key.
 *
 * Prowlarr and Jackett give download links on their own address, which they answer with the .torrent or a redirect
 * to a magnet. Those are the only links the phone fetches.
 *
 * Sites limit requests (some ask for the same address no more than once a minute), so each feed has its own budget,
 * kept across restarts, and repeats of the same search come from a 15-minute cache.
 */
class TorrentSearch(private val prefs: Prefs, private val torrents: TorrentManager) {
    /** A feed as stored on the phone, key included. */
    @Serializable
    private data class StoredFeed(
        val id: String,
        val kind: String,
        val name: String,
        val url: String,
        val key: String? = null,
        val enabled: Boolean = true,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val feedsSerializer = ListSerializer(StoredFeed.serializer())
    private val budgetsSerializer = MapSerializer(String.serializer(), FeedBudget.serializer())

    private var feeds: List<StoredFeed> = load()
    private val budgets: MutableMap<String, FeedBudget> =
        prefs.feedBudgetsJson?.let { runCatching { json.decodeFromString(budgetsSerializer, it) }.getOrNull() }.orEmpty().toMutableMap()

    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "HomeReel-search").apply { isDaemon = true } }

    private val cache = ExpiringCache<String, Torznab.Parsed>(200, CACHE_MS)

    /** What each recent result pointed at, and which feed it came from, so adding one needs only its id. */
    private val seen = object : LinkedHashMap<String, Torznab.Found>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Torznab.Found>) = size > 2000
    }

    // ---- The feeds ----

    @Synchronized
    fun list() = FeedList(feeds.map { SearchFeed(it.id, it.kind, it.name, it.url, it.key != null, it.enabled) })

    @Synchronized
    fun add(e: FeedEdit): FeedList {
        if (feeds.size >= MAX_FEEDS) throw HttpError(409, "That's $MAX_FEEDS feeds already; remove one first")
        val kind = kindOf(e.kind ?: IndexerKind.TORZNAB)
        val (url, keyFromUrl) = cleanUrl(e.url?.takeIf { it.isNotBlank() } ?: throw HttpError(400, "Type the address"), kind)
        val key = e.apiKey?.trim()?.ifEmpty { null } ?: keyFromUrl
        if (key == null && kind != IndexerKind.TORZNAB) throw HttpError(400, "${kindName(kind)} needs its API key")
        val feed = StoredFeed(UUID.randomUUID().toString().take(8), kind, nameFor(e.name, kind, url), url, key, e.enabled ?: true)
        save(feeds + feed)
        return list()
    }

    @Synchronized
    fun update(id: String, e: FeedEdit): FeedList {
        val old = feed(id)
        if (e.clearKey && e.apiKey != null) throw HttpError(400, "Either a new key or Remove key, not both")
        val kind = kindOf(e.kind ?: old.kind)
        val (url, keyFromUrl) = e.url?.takeIf { it.isNotBlank() }?.let { cleanUrl(it, kind) } ?: (old.url to null)
        val typedKey = e.apiKey?.trim()?.ifEmpty { null }
        val key = when {
            typedKey != null -> typedKey
            keyFromUrl != null -> keyFromUrl
            e.clearKey -> null
            else -> old.key
        }
        if (key == null && kind != IndexerKind.TORZNAB) throw HttpError(400, "${kindName(kind)} needs its API key")
        val name = e.name?.let { nameFor(it, kind, url) } ?: old.name
        save(feeds.map { if (it.id == old.id) StoredFeed(old.id, kind, name, url, key, e.enabled ?: old.enabled) else it })
        return list()
    }

    @Synchronized
    fun remove(id: String): FeedList {
        val old = feed(id)
        save(feeds - old)
        synchronized(budgets) { budgets.remove(old.id) }
        saveBudgets()
        return list()
    }

    /** Tries an address and key, typed or saved, without saving them: does it answer, and which indexers will it ask? */
    fun check(e: FeedEdit): IndexerCheck {
        val saved = e.id?.let { synchronized(this) { feed(it) } }
        val kind = kindOf(e.kind ?: saved?.kind ?: IndexerKind.TORZNAB)
        val (url, keyFromUrl) = cleanUrl(e.url?.takeIf { it.isNotBlank() } ?: saved?.url ?: throw HttpError(400, "Type the address first"), kind)
        val key = e.apiKey?.trim()?.ifEmpty { null } ?: keyFromUrl ?: saved?.key?.takeUnless { e.clearKey }
        if (key == null && kind != IndexerKind.TORZNAB) return IndexerCheck(false, "Paste ${kindName(kind)}'s API key first")
        val feed = StoredFeed(saved?.id ?: "test", kind, e.name ?: saved?.name ?: kindName(kind), url, key)
        return try {
            val names = when (kind) {
                IndexerKind.PROWLARR -> {
                    val body = (request(feed, "${prowlarrRoot(url)}/api/v1/indexer", SEARCH_TIMEOUT_MS) as Reply.Body).bytes
                    Torznab.parseProwlarrIndexers(body)
                        ?: throw HttpError(502, "That address answered, but not like Prowlarr does. Is it the address you open Prowlarr at?")
                }
                else -> {
                    val all = kind == IndexerKind.JACKETT && "/indexers/all/" in torznabApi(feed)
                    val query = if (all) "t=indexers&configured=true" else "t=caps"
                    val body = (request(feed, withKey(feed, "${torznabApi(feed)}?$query"), SEARCH_TIMEOUT_MS) as Reply.Body).bytes
                    Torznab.parseIndexers(body).getOrElse { throw HttpError(502, "It answered, but: ${it.message}") }
                }
            }
            when {
                names.isEmpty() && kind == IndexerKind.PROWLARR ->
                    IndexerCheck(false, "Prowlarr answered, but has no torrent indexers switched on. Add some under Indexers in Prowlarr.")
                names.isEmpty() ->
                    IndexerCheck(false, "Jackett answered, but has no indexers set up. Add some with + Add indexer in Jackett.")
                kind == IndexerKind.TORZNAB -> IndexerCheck(true, "Connected to ${names.first()}${if (key == null) ", no key needed" else ""}.", names)
                else -> IndexerCheck(true, "Connected to ${kindName(kind)}. Searches ask ${names.size} indexer${if (names.size == 1) "" else "s"}.", names)
            }
        } catch (e: HttpError) {
            IndexerCheck(false, (e.message ?: "It didn't work").replaceFirstChar { it.uppercase() })
        }
    }

    // ---- Searching ----

    fun search(query: String, category: String?): TorrentSearchPage {
        if (!torrents.settings.enabled) throw HttpError(409, "Turn on torrent downloads in Settings first")
        val asked = synchronized(this) { feeds.filter { it.enabled } }
        if (asked.isEmpty()) throw HttpError(409, "Add a search feed in Settings → Downloads first")
        val q = query.trim()
        if (q.length !in 2..200) throw HttpError(400, "Type 2 to 200 characters to search for")
        val cat = when (category) {
            null, "", "all" -> null
            "movies" -> "2000"
            "tv" -> "5000"
            else -> throw HttpError(400, "Unknown category")
        }
        // Every feed at once; one that is slow or fails doesn't hold up or spoil the others.
        val tasks = asked.map { feed -> Callable { parsed(feed, q, cat) } }
        val futures = pool.invokeAll(tasks, SEARCH_TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS)
        val found = ArrayList<Torznab.Found>()
        val failed = ArrayList<String>()
        var skipped = 0
        futures.forEachIndexed { i, future ->
            val feed = asked[i]
            val result = try {
                future.get()
            } catch (e: CancellationException) {
                Torznab.Parsed(emptyList(), 0, "took too long to answer")
            } catch (e: ExecutionException) {
                Torznab.Parsed(emptyList(), 0, e.cause?.message ?: "didn't answer")
            }
            if (result.error != null) {
                failed += "${feed.name}: ${result.error}"
                return@forEachIndexed
            }
            // A result with only a download link can be added only if the link is on the feed's own address.
            val usable = result.items.filter { (it.magnet != null && it.infoHash != null) || it.link?.let { l -> FeedSafety.sameHost(l, feed.url) } == true }
            skipped += result.skipped + result.items.size - usable.size
            usable.forEach { found += Torznab.Found(feed.id, feed.name, it) }
        }
        if (found.isEmpty() && failed.size == asked.size) throw HttpError(502, failed.joinToString("; "))
        val merged = Torznab.merge(found).take(MAX_RESULTS)
        synchronized(this) { merged.forEach { seen[it.best.item.id] = it.best } }
        val items = merged.map { m ->
            val it = m.best.item
            TorrentSearchResult(it.id, it.infoHash, it.name, it.size, it.seeders, it.peers, it.published,
                it.infoHash?.let(torrents::has) ?: false, it.indexer, m.sources)
        }
        return TorrentSearchPage(q, items, skipped, failed, asked.size)
    }

    /** One feed's answer, from the cache when the same search was made in the last 15 minutes. */
    private fun parsed(feed: StoredFeed, q: String, cat: String?): Torznab.Parsed {
        val key = "${feed.id}|${feed.url}|$q|$cat"
        synchronized(this) { cache[key, System.currentTimeMillis()]?.let { return it } }
        val result = try {
            fetch(feed, q, cat)
        } catch (e: HttpError) {
            Torznab.Parsed(emptyList(), 0, e.message ?: "didn't answer")
        }
        if (result.error == null) synchronized(this) { cache.put(key, result, System.currentTimeMillis()) }
        return result
    }

    /** Adds a result to Downloads: its magnet (cleaned), or what its download link gives, a .torrent or a magnet. */
    fun add(id: String, private: Boolean): TorrentItem {
        val found = synchronized(this) { seen[id.trim().lowercase()] } ?: throw HttpError(409, "Search again; that result is too old")
        val item = found.item
        val hash = item.infoHash
        if (hash != null && torrents.has(hash)) throw HttpError(409, "${item.name} is already in Downloads")
        if (item.magnet != null && hash != null) return torrents.addMagnet(Torznab.cleanMagnet(item.magnet, hash, item.name), private)
        val feed = synchronized(this) { feeds.firstOrNull { it.id == found.feedId } } ?: throw HttpError(409, "That result's feed was removed; search again")
        val link = item.link?.takeIf { FeedSafety.sameHost(it, feed.url) }
            // Only from the feed's own address: a result can't send the phone anywhere else.
            ?: throw HttpError(409, "That result's download link isn't on ${feed.name}'s address, so HomeReel won't fetch it")
        try {
            spend(feed)
            return when (val reply = request(feed, link, DOWNLOAD_TIMEOUT_MS, Torrents.MAX_TORRENT_BYTES, magnetOk = true)) {
                is Reply.Body -> torrents.add(reply.bytes, private)
                is Reply.Magnet -> {
                    val got = Torznab.hashFromMagnet(reply.uri) ?: throw HttpError(502, "its link led to a magnet HomeReel can't read")
                    if (hash != null && got != hash) throw HttpError(502, "its link was for a different torrent than the result")
                    if (torrents.has(got)) throw HttpError(409, "${item.name} is already in Downloads")
                    torrents.addMagnet(Torznab.cleanMagnet(reply.uri, got, item.name), private)
                }
            }
        } catch (e: HttpError) {
            // The feed's own errors read "it …"; say which feed.
            if (e.message?.firstOrNull()?.isLowerCase() == true) throw HttpError(e.status, "${feed.name}: ${e.message}", e.body)
            throw e
        }
    }

    // ---- Asking a feed ----

    private fun fetch(feed: StoredFeed, q: String, cat: String?): Torznab.Parsed {
        spend(feed)
        val url = when (feed.kind) {
            IndexerKind.PROWLARR -> buildString {
                append(prowlarrRoot(feed.url)).append("/api/v1/search?type=search&query=").append(enc(q))
                append("&limit=").append(PER_FEED).append("&offset=0")
                cat?.let { append("&categories=").append(it) }
            }
            else -> withKey(feed, buildString {
                append(torznabApi(feed)).append("?t=search&q=").append(enc(q))
                append("&limit=").append(PER_FEED).append("&offset=0")
                cat?.let { append("&cat=").append(it) }
            })
        }
        val body = (request(feed, url, SEARCH_TIMEOUT_MS) as Reply.Body).bytes
        return if (feed.kind == IndexerKind.PROWLARR) Torznab.parseProwlarr(body) else Torznab.parse(body)
    }

    private fun withKey(feed: StoredFeed, url: String) = feed.key?.let { "$url&apikey=${enc(it)}" } ?: url

    private sealed interface Reply {
        class Body(val bytes: ByteArray) : Reply
        class Magnet(val uri: String) : Reply
    }

    /**
     * One request, following redirects by hand: on the feed's own address (with the key), to a public https://
     * address (without it), or, for a download link, to a magnet. Never to another address on the home network.
     * Its errors start in lower case ("it refused the API key") so the caller can say which feed.
     */
    private fun request(feed: StoredFeed, url: String, readTimeout: Int, limit: Int = MAX_REPLY, magnetOk: Boolean = false): Reply {
        var target = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            // Checked at every hop, at request time: a home-network name could point elsewhere now.
            val hop = FeedSafety.hop(target, feed.url)
            if (hop is FeedSafety.Hop.Refused) throw HttpError(if (hop.plainHttp) 400 else 502, hop.reason)
            val own = hop == FeedSafety.Hop.Own
            val c = target.openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 8_000
                c.readTimeout = readTimeout
                c.instanceFollowRedirects = false // a redirect could point anywhere: checked above, hop by hop
                if (own) feed.key?.let { c.setRequestProperty("X-Api-Key", it) }
                c.setRequestProperty("User-Agent", "HomeReel")
                val code = try {
                    c.responseCode
                } catch (e: SocketTimeoutException) {
                    throw HttpError(504, "took too long to answer")
                } catch (e: IOException) {
                    val why = feed.key?.let { e.message?.replace(it, "…") } ?: e.message
                    throw HttpError(503, "couldn't reach ${feed.url}: $why")
                }
                when (code) {
                    200 -> return Reply.Body(read(c, limit, feed))
                    301, 302, 303, 307, 308 -> {
                        val location = c.getHeaderField("Location") ?: throw HttpError(502, "it answered $code without saying where to")
                        if (Torrents.isMagnet(location)) {
                            if (!magnetOk) throw HttpError(502, "it answered with a magnet link instead of results")
                            return Reply.Magnet(location)
                        }
                        target = URL(target, location)
                    }
                    401, 403 -> throw HttpError(503, if (feed.key == null) "it wants an API key, or turned HomeReel away" else "it refused the API key")
                    404 -> throw HttpError(503, when (feed.kind) {
                        IndexerKind.PROWLARR -> "no Prowlarr at ${feed.url} (it answered 404). Use the address you open Prowlarr at, like http://192.168.1.20:9696."
                        IndexerKind.JACKETT -> "no Jackett at ${feed.url} (it answered 404). Use the address you open Jackett at, like http://192.168.1.20:9117."
                        else -> "nothing at that address (it answered 404). Check the Torznab address."
                    })
                    429 -> {
                        coolDown(feed)
                        throw HttpError(429, "it says too many searches; HomeReel waits 15 minutes before asking it again")
                    }
                    else -> throw HttpError(503, "it answered $code")
                }
            } finally {
                c.disconnect()
            }
        }
        throw HttpError(502, "it redirected too many times")
    }

    private fun read(c: HttpURLConnection, limit: Int, feed: StoredFeed): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw HttpError(502, "its reply is too big")
                }
            }
        } catch (e: SocketTimeoutException) {
            throw HttpError(504, "took too long to answer")
        }
        val head = out.toByteArray().take(200).toByteArray().decodeToString().trimStart().lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) {
            throw HttpError(502, "it answered with a web page, not search results. Check the address, and that ${kindName(feed.kind)} is the right kind.")
        }
        return out.toByteArray()
    }

    /** Spends one of the feed's requests ([FeedBudget]), waiting if the last was under a second ago. */
    private fun spend(feed: StoredFeed) {
        val wait = synchronized(budgets) {
            when (val spend = (budgets[feed.id] ?: FeedBudget()).spend(System.currentTimeMillis())) {
                is FeedBudget.Spend.Refused -> throw HttpError(429, spend.reason)
                is FeedBudget.Spend.Allowed -> {
                    budgets[feed.id] = spend.budget
                    spend.waitMs
                }
            }
        }
        saveBudgets()
        if (wait > 0) Thread.sleep(wait)
        Log.i(TAG, "Search feed request: ${feed.name}")
    }

    private fun coolDown(feed: StoredFeed) {
        synchronized(budgets) { budgets[feed.id] = (budgets[feed.id] ?: FeedBudget()).coolDown(System.currentTimeMillis()) }
        saveBudgets()
    }

    private fun saveBudgets() {
        prefs.feedBudgetsJson = synchronized(budgets) { json.encodeToString(budgetsSerializer, budgets) }
    }

    // ---- Storing ----

    private fun load(): List<StoredFeed> {
        prefs.searchFeedsJson?.let { stored -> return runCatching { json.decodeFromString(feedsSerializer, stored) }.getOrDefault(emptyList()) }
        // 0.6's single indexer becomes the first feed.
        val (kind, url, key) = prefs.takeOldIndexer() ?: return emptyList()
        val k = kind.takeIf { it in IndexerKind.ALL } ?: IndexerKind.TORZNAB
        val migrated = listOf(StoredFeed(UUID.randomUUID().toString().take(8), k, nameFor(null, k, url), url, key))
        prefs.searchFeedsJson = json.encodeToString(feedsSerializer, migrated)
        return migrated
    }

    private fun save(list: List<StoredFeed>) {
        feeds = list
        prefs.searchFeedsJson = json.encodeToString(feedsSerializer, list)
        cache.clear()
    }

    private fun feed(id: String) = feeds.firstOrNull { it.id == id } ?: throw HttpError(404, "No such feed")

    // ---- Addresses ----

    private fun kindOf(kind: String) = kind.takeIf { it in IndexerKind.ALL } ?: throw HttpError(400, "Unknown kind of feed")

    /** The name typed, or one made up: "Prowlarr", "Jackett", or the site's address without www. */
    private fun nameFor(typed: String?, kind: String, url: String) = typed?.trim()?.take(40)?.ifEmpty { null } ?: when (kind) {
        IndexerKind.PROWLARR, IndexerKind.JACKETT -> kindName(kind)
        else -> runCatching { URL(url).host.removePrefix("www.") }.getOrNull() ?: "Torznab feed"
    }

    /** The address as typed, tidied ([FeedSafety.clean]), and a key found in it. */
    private fun cleanUrl(raw: String, kind: String): Pair<String, String?> = try {
        FeedSafety.clean(raw, kind).let { it.url to it.key }
    } catch (e: IllegalArgumentException) {
        throw HttpError(400, e.message ?: "That address won't work")
    }

    private fun prowlarrRoot(url: String) = FeedSafety.prowlarrRoot(url)

    private fun torznabApi(feed: StoredFeed) = FeedSafety.torznabApi(feed.kind, feed.url)

    private fun kindName(kind: String) = when (kind) {
        IndexerKind.PROWLARR -> "Prowlarr"
        IndexerKind.JACKETT -> "Jackett"
        else -> "Torznab feed"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "HomeReel"
        const val MAX_FEEDS = 20
        /** Results asked of each feed (Torznab's usual maximum). */
        const val PER_FEED = 100
        /** Results shown after merging every feed's. */
        const val MAX_RESULTS = 300
        const val MAX_REPLY = 4 * 1024 * 1024
        const val MAX_REDIRECTS = 3
        /** Prowlarr and Jackett wait for their slowest indexer. */
        const val SEARCH_TIMEOUT_MS = 60_000
        const val DOWNLOAD_TIMEOUT_MS = 30_000
        const val CACHE_MS = 15 * 60_000L
    }
}
