package com.parthajb.homereel.shared

import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.URLDecoder

/**
 * The rules torrent search follows so a search feed can't send the phone somewhere it shouldn't, kept apart from
 * the network so they can be tested. The phone app applies them to every request.
 */
object FeedSafety {
    /** Looks a name up; tests pass their own. */
    val dns: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }

    /** On the home network, this phone, or a Tailscale network (100.64.0.0/10), or an IPv6 unique local address. */
    fun isHomeAddress(a: InetAddress): Boolean =
        a.isSiteLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || isCarrierGrade(a) ||
            (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc) // fc00::/7

    /** Whether every address [host] resolves to is on the home network. A name that doesn't resolve isn't. */
    fun isHomeHost(host: String, resolve: (String) -> List<InetAddress> = dns): Boolean = runCatching {
        resolve(host).let { it.isNotEmpty() && it.all(::isHomeAddress) }
    }.getOrDefault(false)

    /**
     * Whether any address [host] resolves to is on the home network: a public name that also points home could be
     * used to reach the home network from outside, so it counts as home when deciding where not to go.
     */
    fun reachesHome(host: String, resolve: (String) -> List<InetAddress> = dns): Boolean = runCatching {
        resolve(host).any(::isHomeAddress)
    }.getOrDefault(false)

    private fun isCarrierGrade(a: InetAddress) =
        a is Inet4Address && a.address[0].toInt() == 100 && (a.address[1].toInt() and 0xc0) == 64

    /** Same scheme family, host and port: a download link must be on the feed's own address. */
    fun sameHost(link: String, base: String): Boolean = runCatching {
        val a = URL(link)
        val b = URL(base)
        a.protocol in setOf("http", "https") && a.host.equals(b.host, ignoreCase = true) && a.port == b.port
    }.getOrDefault(false)

    /** What to do with one hop of a request, its first or a redirect. */
    sealed interface Hop {
        /** The feed's own address: send its key. */
        data object Own : Hop

        /** A public https:// address: go, without the key. */
        data object Public : Hop

        /**
         * Don't go. [reason] starts in lower case, to follow the feed's name. [plainHttp] is for http:// to the
         * internet, a mistake in the address rather than the feed misbehaving.
         */
        data class Refused(val reason: String, val plainHttp: Boolean = false) : Hop
    }

    /**
     * Plain http:// only to the home network, checked at request time because a name can point elsewhere later.
     * Anywhere but the feed's own address must be public https://, never another address on the home network.
     */
    fun hop(target: URL, feedUrl: String, resolve: (String) -> List<InetAddress> = dns): Hop {
        if (target.protocol == "http" && !isHomeHost(target.host, resolve)) {
            return Hop.Refused("plain http:// only works for a feed on your home network", plainHttp = true)
        }
        if (sameHost(target.toString(), feedUrl)) return Hop.Own
        if (target.protocol != "https" || reachesHome(target.host, resolve)) return Hop.Refused("it sent HomeReel to another address, which it won't follow")
        return Hop.Public
    }

    /** A feed address as typed, tidied, and the key found in it (a copied Jackett feed address has one). */
    data class Cleaned(val url: String, val key: String?)

    /**
     * Tidies a typed address: http or https with a host, no trailing slash and no query. http:// is refused for an
     * address on the internet. Throws [IllegalArgumentException] with a message to show.
     */
    fun clean(raw: String, kind: String, resolve: (String) -> List<InetAddress> = dns): Cleaned {
        val parsed = runCatching { URL(raw.trim()) }.getOrNull()
        require(parsed != null && parsed.protocol in setOf("http", "https") && !parsed.host.isNullOrBlank()) {
            "The address should look like ${example(kind)}"
        }
        require(parsed.protocol != "http" || isHomeHost(parsed.host, resolve)) {
            "Use https:// for a feed on the internet, so nothing is sent in the clear. Plain http:// is for one on your home network."
        }
        val key = parsed.query?.split('&')?.firstNotNullOfOrNull { part ->
            val name = part.substringBefore('=').lowercase()
            if (name == "apikey" || name == "jackett_apikey") runCatching { URLDecoder.decode(part.substringAfter('='), "UTF-8") }.getOrNull()?.ifBlank { null } else null
        }
        return Cleaned("${parsed.protocol}://${parsed.authority}${parsed.path}".trimEnd('/'), key)
    }

    fun example(kind: String) = when (kind) {
        IndexerKind.PROWLARR -> "http://192.168.1.20:9696"
        IndexerKind.JACKETT -> "http://192.168.1.20:9117"
        else -> "https://indexer.example/torznab"
    }

    /** Prowlarr's own address, from it or from one of its per-indexer feed addresses (…/1/api). */
    fun prowlarrRoot(url: String) = url.replace(Regex("(/\\d+)?(/api(/v1)?)?/?$"), "")

    /** The Torznab endpoint: Jackett's all-indexers feed when the address is just Jackett's; the address itself otherwise. */
    fun torznabApi(kind: String, url: String): String {
        val base = url.removeSuffix("/api")
        if (kind == IndexerKind.JACKETT && "/torznab" !in base) {
            return "${base.replace(Regex("/(UI/Dashboard|api/v2\\.0)$"), "")}/api/v2.0/indexers/all/results/torznab/api"
        }
        return "$base/api"
    }
}

/**
 * One feed's request budget, kept across restarts: one request a second at most, [PER_HOUR] an hour and [PER_DAY] a
 * day, and a pause of [COOLDOWN_MS] after the feed says too many.
 */
@Serializable
data class FeedBudget(val requests: List<Long> = emptyList(), val cooldownUntil: Long = 0) {
    sealed interface Spend {
        /** Go after [waitMs], and keep [budget]. */
        data class Allowed(val budget: FeedBudget, val waitMs: Long) : Spend

        /** Don't ask the feed. [reason] starts in lower case, to follow the feed's name. */
        data class Refused(val reason: String) : Spend
    }

    fun spend(now: Long): Spend {
        if (now < cooldownUntil) return Spend.Refused("waiting ${(cooldownUntil - now) / 60_000 + 1} min before asking it again (it asked)")
        val times = requests.filter { now - it < DAY_MS }
        if (times.count { now - it < HOUR_MS } >= PER_HOUR) return Spend.Refused("that's $PER_HOUR searches this hour; try again later")
        if (times.size >= PER_DAY) return Spend.Refused("that's $PER_DAY searches today; try again tomorrow")
        val at = maxOf(now, (times.maxOrNull() ?: 0L) + 1000)
        return Spend.Allowed(copy(requests = times + at), at - now)
    }

    fun coolDown(now: Long) = copy(cooldownUntil = now + COOLDOWN_MS)

    companion object {
        const val PER_HOUR = 90
        const val PER_DAY = 900
        const val COOLDOWN_MS = 15 * 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}

/** At most [max] entries, each kept for [ttlMs]; the least recently used goes first. Not thread-safe. */
class ExpiringCache<K, V>(private val max: Int, private val ttlMs: Long) {
    private val map = object : LinkedHashMap<K, Pair<Long, V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Pair<Long, V>>) = size > max
    }

    operator fun get(key: K, now: Long): V? {
        val (at, value) = map[key] ?: return null
        if (now - at >= ttlMs) {
            map.remove(key)
            return null
        }
        return value
    }

    fun put(key: K, value: V, now: Long) {
        map[key] = now to value
    }

    fun clear() = map.clear()

    val size: Int get() = map.size
}
