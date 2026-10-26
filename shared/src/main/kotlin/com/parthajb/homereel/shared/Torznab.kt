package com.parthajb.homereel.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

/** One search result, as the admin page shows it. The magnet stays on the phone. */
@Serializable
data class TorrentSearchResult(
    /** What to send back to add it: the info hash, or a stand-in for a result that only has a download link. */
    val id: String,
    /** Null when the indexer only gives a download link; the hash is known once the .torrent arrives. */
    val infoHash: String? = null,
    val name: String,
    /** Null when the indexer didn't say, which is not the same as zero. */
    val size: Long? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val published: String? = null,
    /** Already in Downloads (by info hash). */
    val inDownloads: Boolean = false,
    /** Which of Prowlarr's or Jackett's indexers found it. */
    val indexer: String? = null,
    /** The search feeds that found it, most seeders first (one torrent found by several shows once). */
    val sources: List<String> = emptyList(),
)

@Serializable
data class TorrentSearchPage(
    val query: String,
    val items: List<TorrentSearchResult>,
    /** Results left out because HomeReel couldn't add them: no info hash and no download link from the indexer. */
    val skipped: Int = 0,
    /** Feeds that didn't answer, each as "Name: why"; the others' results are still shown. */
    val failed: List<String> = emptyList(),
    /** How many feeds were asked. */
    val feeds: Int = 0,
)

/**
 * One place torrent search asks. [IndexerKind.PROWLARR] or [IndexerKind.JACKETT] ask every indexer set up in them; a
 * Torznab feed is one site's own search, with or without a key. A search asks every feed that is on, together. The
 * key is never sent back.
 */
@Serializable
data class SearchFeed(
    val id: String,
    val kind: String = IndexerKind.TORZNAB,
    val name: String,
    val url: String,
    val keySet: Boolean = false,
    val enabled: Boolean = true,
)

@Serializable
data class FeedList(val feeds: List<SearchFeed>)

/**
 * Adds or changes a feed; null leaves a field as it is. Test connection sends the same, with [id] to fall back on
 * that feed's saved address and key.
 */
@Serializable
data class FeedEdit(
    val id: String? = null,
    val kind: String? = null,
    val name: String? = null,
    val url: String? = null,
    val apiKey: String? = null,
    val clearKey: Boolean = false,
    val enabled: Boolean? = null,
)

object IndexerKind {
    const val PROWLARR = "prowlarr"
    const val JACKETT = "jackett"
    const val TORZNAB = "torznab"
    val ALL = setOf(PROWLARR, JACKETT, TORZNAB)
}

/** What Test connection found: whether it answered with the key, and the indexers it will search. */
@Serializable
data class IndexerCheck(val ok: Boolean, val message: String, val indexers: List<String> = emptyList())

@Serializable
data class SearchAdd(val id: String, val private: Boolean = false)

/**
 * Reads search results. Torznab (Jackett, single indexers, Prowlarr's per-indexer feeds) is RSS items with
 * `<torznab:attr name=… value=…/>` for seeders, peers, the info hash and the magnet link; Prowlarr's own search is
 * JSON. Anything malformed in one item drops that item, not the page.
 */
object Torznab {
    data class Item(
        val infoHash: String?,
        val name: String,
        val size: Long?,
        val seeders: Int?,
        val peers: Int?,
        val published: String?,
        val magnet: String?,
        /** A link to a .torrent file (or, from Prowlarr and Jackett, one that redirects to a magnet). */
        val link: String?,
        /** The indexer behind Prowlarr or Jackett that found it. */
        val indexer: String? = null,
    ) {
        /** The hash, or for a link-only result a stand-in made from its link (never mistaken for a hash). */
        val id: String get() = infoHash ?: ("link-" + sha1(link.orEmpty()).take(32))
    }

    data class Parsed(val items: List<Item>, val skipped: Int, val error: String?)

    /** A result as one feed gave it. */
    data class Found(val feedId: String, val feedName: String, val item: Item)

    /** A torrent after merging: the best copy of it (a magnet if any feed had one, then most seeders), and who found it. */
    data class Merged(val best: Found, val sources: List<String>)

    /**
     * Merges several feeds' results: the same info hash shows once, keeping the copy with a magnet and the most
     * seeders. Results known only by a download link can't be matched, so they stay apart. Most seeders first.
     */
    fun merge(found: List<Found>): List<Merged> =
        found.groupBy { it.item.infoHash ?: "${it.feedId}|${it.item.id}" }.values.map { copies ->
            val ranked = copies.sortedWith(compareByDescending<Found> { it.item.seeders ?: -1 })
            val best = ranked.firstOrNull { it.item.magnet != null } ?: ranked.first()
            val most = ranked.mapNotNull { it.item.seeders }.maxOrNull()
            Merged(best.copy(item = best.item.copy(seeders = most ?: best.item.seeders)), ranked.map { it.feedName }.distinct())
        }.sortedByDescending { it.best.item.seeders ?: -1 }

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32 = Regex("^[A-Za-z2-7]{32}$")

    fun parse(xml: ByteArray): Parsed {
        val doc = runCatching { document(xml) }.getOrElse { return Parsed(emptyList(), 0, "The indexer's reply isn't valid Torznab (XML).") }
        val root = doc.documentElement
        if (root.tagName == "error") {
            return Parsed(emptyList(), 0, root.getAttribute("description").ifBlank { null }?.let { "The indexer said: $it" } ?: "The indexer refused the search.")
        }
        val nodes = root.getElementsByTagName("item")
        val items = ArrayList<Item>()
        var skipped = 0
        for (i in 0 until nodes.length) {
            val item = runCatching { item(nodes.item(i) as Element) }.getOrNull()
            if (item == null) skipped++ else items += item
        }
        return Parsed(items, skipped, null)
    }

    private fun document(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false
        isExpandEntityReferences = false
        // No DOCTYPE, so no external entities: results come from a server on the internet.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(xml))

    private fun item(e: Element): Item? {
        val attrs = HashMap<String, String>()
        val list = e.getElementsByTagName("torznab:attr")
        for (i in 0 until list.length) {
            val a = list.item(i) as Element
            attrs[a.getAttribute("name").lowercase()] = a.getAttribute("value")
        }
        val name = text(e, "title")?.trim()?.take(300)?.takeIf { it.isNotEmpty() } ?: return null
        val link = text(e, "link")?.trim()
        val enclosure = (e.getElementsByTagName("enclosure").item(0) as? Element)?.getAttribute("url")?.trim()
        val magnet = attrs["magneturl"]?.trim()?.takeIf { isMagnet(it) }
            ?: listOfNotNull(link, enclosure, text(e, "guid")?.trim()).firstOrNull { isMagnet(it) }
        val torrentLink = listOfNotNull(enclosure, link).firstOrNull { isHttp(it) }
        // Prowlarr and Jackett often only give their own download link; the hash is known once it's fetched.
        val hash = attrs["infohash"]?.let(::normaliseHash) ?: magnet?.let(::hashFromMagnet)
        if (hash == null && torrentLink == null) return null
        // A magnet must be for the same torrent as the hash it claims.
        if (magnet != null && hashFromMagnet(magnet) != hash) return null
        return Item(
            infoHash = hash,
            name = name,
            size = (attrs["size"] ?: text(e, "size"))?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
            seeders = attrs["seeders"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
            peers = attrs["peers"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
            published = text(e, "pubDate")?.trim()?.take(60),
            magnet = magnet,
            link = torrentLink,
            indexer = (text(e, "jackettindexer") ?: text(e, "prowlarrindexer"))?.trim()?.take(80)?.ifEmpty { null },
        )
    }

    /** The torrent results of Prowlarr's own search (`/api/v1/search`), which covers every indexer set up in it. */
    fun parseProwlarr(json: ByteArray): Parsed {
        val releases = runCatching { JSON.decodeFromString(ListSerializer(ProwlarrRelease.serializer()), json.decodeToString()) }
            .getOrElse { return Parsed(emptyList(), 0, "Prowlarr's reply isn't a list of search results. Is the address the one you open Prowlarr at?") }
        val items = ArrayList<Item>()
        var skipped = 0
        for (r in releases) {
            if (r.protocol != null && !r.protocol.equals("torrent", ignoreCase = true)) continue // Usenet
            val name = r.title?.trim()?.take(300)?.ifEmpty { null }
            val magnet = listOfNotNull(r.magnetUrl, r.guid).map { it.trim() }.firstOrNull { isMagnet(it) }
            val link = listOfNotNull(r.downloadUrl, r.magnetUrl).map { it.trim() }.firstOrNull { isHttp(it) }
            val hash = r.infoHash?.let(::normaliseHash) ?: magnet?.let(::hashFromMagnet)
            if (name == null || (hash == null && link == null) || (magnet != null && hashFromMagnet(magnet) != hash)) {
                skipped++
                continue
            }
            items += Item(hash, name, r.size?.takeIf { it >= 0 }, r.seeders?.takeIf { it >= 0 },
                r.leechers?.takeIf { it >= 0 }?.let { it + (r.seeders ?: 0) }, r.publishDate?.take(60), magnet, link,
                r.indexer?.trim()?.take(80)?.ifEmpty { null })
        }
        return Parsed(items, skipped, null)
    }

    /** The torrent indexers switched on in Prowlarr (`/api/v1/indexer`), by name; null if the reply isn't that list. */
    fun parseProwlarrIndexers(json: ByteArray): List<String>? =
        runCatching { JSON.decodeFromString(ListSerializer(ProwlarrIndexer.serializer()), json.decodeToString()) }.getOrNull()
            ?.filter { it.enable && (it.protocol == null || it.protocol.equals("torrent", ignoreCase = true)) }
            ?.mapNotNull { it.name?.trim()?.ifEmpty { null } }

    /**
     * Jackett's `t=indexers` reply: the indexers set up in it, by name. For a plain Torznab feed's `t=caps` reply, the
     * feed's own name. Null if it is neither, or an error message from the indexer.
     */
    fun parseIndexers(xml: ByteArray): Result<List<String>> {
        val doc = runCatching { document(xml) }.getOrElse { return Result.failure(IllegalArgumentException("The reply isn't Torznab (XML).")) }
        val root = doc.documentElement
        return when (root.tagName) {
            "error" -> Result.failure(IllegalArgumentException(root.getAttribute("description").ifBlank { "It refused the request." }))
            "indexers" -> {
                val nodes = root.getElementsByTagName("indexer")
                Result.success((0 until nodes.length).map { nodes.item(it) as Element }
                    .filter { it.getAttribute("configured").ifEmpty { "true" } == "true" }
                    .mapNotNull { (text(it, "title") ?: it.getAttribute("id")).trim().ifEmpty { null } })
            }
            "caps" -> Result.success(listOfNotNull(
                (root.getElementsByTagName("server").item(0) as? Element)?.getAttribute("title")?.trim()?.ifEmpty { null } ?: "Torznab feed"))
            else -> Result.failure(IllegalArgumentException("The reply isn't Torznab."))
        }
    }

    @Serializable
    private data class ProwlarrRelease(
        val title: String? = null,
        val size: Long? = null,
        val seeders: Int? = null,
        val leechers: Int? = null,
        val publishDate: String? = null,
        val magnetUrl: String? = null,
        val downloadUrl: String? = null,
        val guid: String? = null,
        val infoHash: String? = null,
        val indexer: String? = null,
        val protocol: String? = null,
    )

    @Serializable
    private data class ProwlarrIndexer(val name: String? = null, val enable: Boolean = false, val protocol: String? = null)

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private fun isHttp(s: String) = s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun text(e: Element, tag: String): String? = e.getElementsByTagName(tag).item(0)?.textContent

    fun isMagnet(text: String) = Torrents.isMagnet(text)

    /** A 40-character lowercase hex v1 hash, from hex or base32; null for anything else (v2-only hashes too). */
    fun normaliseHash(raw: String): String? {
        val s = raw.trim()
        return when {
            HEX40.matches(s) -> s.lowercase()
            BASE32.matches(s) -> base32ToHex(s.uppercase())
            else -> null
        }
    }

    fun hashFromMagnet(magnet: String): String? =
        params(magnet)["xt"].orEmpty().firstNotNullOfOrNull { xt ->
            xt.takeIf { it.startsWith("urn:btih:", ignoreCase = true) }?.substring(9)?.let(::normaliseHash)
        }

    /**
     * The magnet with only what HomeReel needs: the hash, the name and its trackers. Web-seed and "exact source"
     * addresses are dropped, so a search result can't make the phone fetch an arbitrary web address.
     */
    fun cleanMagnet(magnet: String?, hash: String, name: String): String {
        val trackers = magnet?.let { params(it)["tr"] }.orEmpty().filter { it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://") }
        return buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            append("&dn=").append(java.net.URLEncoder.encode(name, "UTF-8"))
            for (t in trackers.take(20)) append("&tr=").append(java.net.URLEncoder.encode(t, "UTF-8"))
        }
    }

    private fun params(magnet: String): Map<String, List<String>> =
        magnet.substringAfter('?', "").split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "").lowercase().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            key to runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrDefault("")
        }.groupBy({ it.first }, { it.second })

    private fun base32ToHex(s: String): String? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0L
        var bits = 0
        val out = StringBuilder()
        for (c in s) {
            buffer = (buffer shl 5) or alphabet.indexOf(c).toLong()
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.append("%02x".format(((buffer shr bits) and 0xff).toInt()))
            }
        }
        return out.toString().takeIf { it.length == 40 }
    }
}
