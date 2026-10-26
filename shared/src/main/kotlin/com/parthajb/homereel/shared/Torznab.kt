package com.parthajb.homereel.shared

import kotlinx.serialization.Serializable
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import javax.xml.parsers.DocumentBuilderFactory

/** One search result, as the admin page shows it. The magnet stays on the phone. */
@Serializable
data class TorrentSearchResult(
    val infoHash: String,
    val name: String,
    /** Null when the indexer didn't say, which is not the same as zero. */
    val size: Long? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val published: String? = null,
    /** Already in Downloads (by info hash). */
    val inDownloads: Boolean = false,
)

@Serializable
data class TorrentSearchPage(
    val query: String,
    val items: List<TorrentSearchResult>,
    val offset: Int,
    /** Whether asking for the next page may find more. */
    val more: Boolean,
    /** Results left out because they had no usable info hash. */
    val skipped: Int = 0,
)

/** The torrent search provider: any Torznab indexer (Search Magnet Link, Jackett, Prowlarr…). The key is never sent back. */
@Serializable
data class IndexerSettings(val url: String? = null, val keySet: Boolean = false)

@Serializable
data class IndexerUpdate(val url: String? = null, val apiKey: String? = null, val clearKey: Boolean = false)

@Serializable
data class SearchAdd(val infoHash: String, val private: Boolean = false)

/**
 * Reads Torznab results: standard RSS items with `<torznab:attr name=… value=…/>` for seeders, peers, the info
 * hash and the magnet link. Anything malformed in one item drops that item, not the page.
 */
object Torznab {
    data class Item(
        val infoHash: String,
        val name: String,
        val size: Long?,
        val seeders: Int?,
        val peers: Int?,
        val published: String?,
        val magnet: String?,
        /** A link to a .torrent file, when the indexer gives one instead of a magnet. */
        val link: String?,
    )

    data class Parsed(val items: List<Item>, val skipped: Int, val error: String?)

    private val HEX40 = Regex("^[0-9a-fA-F]{40}$")
    private val BASE32 = Regex("^[A-Za-z2-7]{32}$")

    fun parse(xml: ByteArray): Parsed {
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                // No DOCTYPE, so no external entities: results come from a server on the internet.
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }
            factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
        }.getOrElse { return Parsed(emptyList(), 0, "The indexer's reply isn't valid Torznab (XML).") }
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
        val hash = attrs["infohash"]?.let(::normaliseHash) ?: magnet?.let(::hashFromMagnet) ?: return null
        // A magnet must be for the same torrent as the hash it claims.
        if (magnet != null && hashFromMagnet(magnet) != hash) return null
        val torrentLink = listOfNotNull(enclosure, link).firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        return Item(
            infoHash = hash,
            name = name,
            size = (attrs["size"] ?: text(e, "size"))?.trim()?.toLongOrNull()?.takeIf { it >= 0 },
            seeders = attrs["seeders"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
            peers = attrs["peers"]?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
            published = text(e, "pubDate")?.trim()?.take(60),
            magnet = magnet,
            link = torrentLink,
        )
    }

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
