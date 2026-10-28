package com.parthajb.homereel.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.URL

class FeedSafetyTest {
    private fun ip(s: String) = InetAddress.getByName(s) // a literal address: no DNS lookup

    /** Made-up names, so no test asks real DNS. */
    private val names = mapOf(
        "nas.local" to listOf(ip("192.168.1.20")),
        "prowlarr.tailnet" to listOf(ip("100.101.102.103")),
        "indexer.example" to listOf(ip("93.184.216.34")),
        "rebound.example" to listOf(ip("93.184.216.34"), ip("192.168.1.1")),
        "tracker.example" to listOf(ip("93.184.216.35")),
    )

    /** Literal addresses as they are; any other name not above fails, as it would with no DNS answer. */
    private val resolve: (String) -> List<InetAddress> = {
        names[it] ?: if (it.first().isDigit() || ':' in it) listOf(ip(it)) else throw java.net.UnknownHostException(it)
    }
    private val isHome: (String) -> Boolean = { FeedSafety.isHomeHost(it, resolve) }

    @Test fun homeAddresses() {
        for (a in listOf("192.168.1.5", "10.0.0.2", "172.16.4.4", "127.0.0.1", "169.254.1.1", "100.64.0.1", "100.127.255.254", "fd12::1", "fe80::1")) {
            assertTrue(a, FeedSafety.isHomeAddress(ip(a)))
        }
    }

    @Test fun publicAddresses() {
        for (a in listOf("93.184.216.34", "8.8.8.8", "100.63.255.255", "100.128.0.1", "172.32.0.1", "2001:db8::1")) {
            assertFalse(a, FeedSafety.isHomeAddress(ip(a)))
        }
    }

    @Test fun aNameIsHomeOnlyIfEveryAddressIs() {
        assertTrue(isHome("nas.local"))
        assertTrue(isHome("prowlarr.tailnet"))
        assertFalse(isHome("indexer.example"))
        assertFalse("one public address is enough to say no", isHome("rebound.example"))
        assertFalse("a name that doesn't resolve isn't home", isHome("nowhere.invalid"))
        assertTrue("but one home address reaches home", FeedSafety.reachesHome("rebound.example", resolve))
        assertFalse(FeedSafety.reachesHome("indexer.example", resolve))
    }

    @Test fun sameHostNeedsHostAndPort() {
        assertTrue(FeedSafety.sameHost("http://nas.local:9696/1/download?x=1", "http://nas.local:9696"))
        assertTrue(FeedSafety.sameHost("http://NAS.local:9696/x", "http://nas.local:9696"))
        assertFalse(FeedSafety.sameHost("http://nas.local:9117/x", "http://nas.local:9696"))
        assertFalse(FeedSafety.sameHost("http://other.local:9696/x", "http://nas.local:9696"))
        assertFalse(FeedSafety.sameHost("ftp://nas.local:9696/x", "http://nas.local:9696"))
        assertFalse(FeedSafety.sameHost("not a link", "http://nas.local:9696"))
    }

    private fun hop(target: String, feed: String) = FeedSafety.hop(URL(target), feed, resolve)

    @Test fun theFeedsOwnAddressGetsTheKey() {
        assertEquals(FeedSafety.Hop.Own, hop("http://nas.local:9696/api/v1/search?q=x", "http://nas.local:9696"))
        assertEquals(FeedSafety.Hop.Own, hop("https://indexer.example/torznab/api?t=search", "https://indexer.example/torznab"))
    }

    @Test fun aPublicHttpsRedirectIsFollowedWithoutTheKey() =
        assertEquals(FeedSafety.Hop.Public, hop("https://tracker.example/file.torrent", "http://nas.local:9696"))

    @Test fun neverToAnotherHomeAddress() {
        assertTrue(hop("http://192.168.1.1/admin", "http://nas.local:9696") is FeedSafety.Hop.Refused)
        assertTrue(hop("https://192.168.1.1/admin", "http://nas.local:9696") is FeedSafety.Hop.Refused)
        assertTrue(hop("http://nas.local:9117/dl", "http://nas.local:9696") is FeedSafety.Hop.Refused)
        assertTrue("a name with one home address", hop("https://rebound.example/x", "https://indexer.example/torznab") is FeedSafety.Hop.Refused)
    }

    @Test fun neverPlainHttpToTheInternet() {
        val own = hop("http://indexer.example/torznab/api", "http://indexer.example/torznab")
        assertTrue("even the feed's own address", own is FeedSafety.Hop.Refused && own.plainHttp)
        val elsewhere = hop("http://tracker.example/x.torrent", "http://nas.local:9696")
        assertTrue(elsewhere is FeedSafety.Hop.Refused && elsewhere.plainHttp)
    }

    @Test fun cleanTidiesAndFindsAKey() {
        val c = FeedSafety.clean(" http://nas.local:9117/api/v2.0/indexers/1337x/results/torznab/api?apikey=abc123&t=caps ", IndexerKind.JACKETT, resolve)
        assertEquals("http://nas.local:9117/api/v2.0/indexers/1337x/results/torznab/api", c.url)
        assertEquals("abc123", c.key)
        assertEquals(FeedSafety.Cleaned("https://indexer.example/torznab", null), FeedSafety.clean("https://indexer.example/torznab/", IndexerKind.TORZNAB, resolve))
        assertEquals("k y", FeedSafety.clean("http://nas.local:9117/dl/?jackett_apikey=k%20y", IndexerKind.JACKETT, resolve).key)
    }

    @Test fun cleanRefusesHttpToTheInternet() {
        val e = runCatching { FeedSafety.clean("http://indexer.example/torznab", IndexerKind.TORZNAB, resolve) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.startsWith("Use https://"))
    }

    @Test fun cleanRefusesWhatIsntAnAddress() {
        for (raw in listOf("", "nas.local:9696", "ftp://nas.local/x", "javascript:alert(1)", "file:///etc/passwd")) {
            assertTrue(raw, runCatching { FeedSafety.clean(raw, IndexerKind.PROWLARR, resolve) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun prowlarrRoot() {
        assertEquals("http://nas.local:9696", FeedSafety.prowlarrRoot("http://nas.local:9696"))
        assertEquals("http://nas.local:9696", FeedSafety.prowlarrRoot("http://nas.local:9696/1/api"))
        assertEquals("http://nas.local:9696", FeedSafety.prowlarrRoot("http://nas.local:9696/api/v1"))
    }

    @Test fun torznabApi() {
        assertEquals("http://nas.local:9117/api/v2.0/indexers/all/results/torznab/api", FeedSafety.torznabApi(IndexerKind.JACKETT, "http://nas.local:9117"))
        assertEquals("http://nas.local:9117/api/v2.0/indexers/all/results/torznab/api", FeedSafety.torznabApi(IndexerKind.JACKETT, "http://nas.local:9117/UI/Dashboard"))
        assertEquals("http://nas.local:9117/api/v2.0/indexers/yts/results/torznab/api",
            FeedSafety.torznabApi(IndexerKind.JACKETT, "http://nas.local:9117/api/v2.0/indexers/yts/results/torznab/api"))
        assertEquals("https://www.torlock.com/torznab/api", FeedSafety.torznabApi(IndexerKind.TORZNAB, "https://www.torlock.com/torznab"))
    }
}

class FeedBudgetTest {
    private val start = 1_800_000_000_000L

    private fun allowed(b: FeedBudget, now: Long) = b.spend(now) as FeedBudget.Spend.Allowed

    @Test fun oneASecond() {
        val first = allowed(FeedBudget(), start)
        assertEquals(0, first.waitMs)
        val second = allowed(first.budget, start + 200)
        assertEquals("waits out the rest of the second", 800, second.waitMs)
        assertEquals(0, allowed(second.budget, start + 5_000).waitMs)
    }

    @Test fun ninetyAnHour() {
        var b = FeedBudget()
        repeat(FeedBudget.PER_HOUR) { b = allowed(b, start + it * 1_000L).budget }
        assertTrue(b.spend(start + 120_000) is FeedBudget.Spend.Refused)
        assertTrue("an hour after the first, one frees up", b.spend(start + FeedBudget.HOUR_MS) is FeedBudget.Spend.Allowed)
    }

    @Test fun nineHundredADay() {
        val b = FeedBudget(requests = List(FeedBudget.PER_DAY) { start + it * 60_000L })
        val later = start + FeedBudget.PER_DAY * 60_000L + FeedBudget.HOUR_MS
        assertTrue(b.spend(later) is FeedBudget.Spend.Refused)
        assertTrue(b.spend(start + FeedBudget.DAY_MS + 1) is FeedBudget.Spend.Allowed)
    }

    @Test fun aFeedThatSaysTooManyWaitsFifteenMinutes() {
        val b = FeedBudget().coolDown(start)
        assertTrue(b.spend(start + FeedBudget.COOLDOWN_MS - 1) is FeedBudget.Spend.Refused)
        assertTrue(b.spend(start + FeedBudget.COOLDOWN_MS) is FeedBudget.Spend.Allowed)
    }

    @Test fun oldRequestsAreForgotten() {
        val b = allowed(FeedBudget(requests = listOf(start - FeedBudget.DAY_MS - 1, start - 10_000)), start).budget
        assertEquals(listOf(start - 10_000, start), b.requests)
    }

    @Test fun readsWhat06Stored() {
        val stored = """{"requests":[1,2],"cooldownUntil":3}"""
        assertEquals(FeedBudget(listOf(1, 2), 3), ApiJson.decodeFromString(FeedBudget.serializer(), stored))
    }
}

class ExpiringCacheTest {
    @Test fun keepsForItsTime() {
        val c = ExpiringCache<String, Int>(10, 1_000)
        c.put("a", 1, 0)
        assertEquals(1, c["a", 999])
        assertNull(c["a", 1_000])
        assertEquals(0, c.size)
    }

    @Test fun dropsTheLeastRecentlyUsed() {
        val c = ExpiringCache<String, Int>(2, 1_000)
        c.put("a", 1, 0)
        c.put("b", 2, 0)
        c["a", 1]
        c.put("c", 3, 2)
        assertEquals(1, c["a", 3])
        assertNull(c["b", 3])
        assertEquals(3, c["c", 3])
    }
}

class PrivateRulesTest {
    @Test fun whoSeesPrivate() {
        assertTrue(PrivateRules.seesPrivate(admin = true, unlocked = false, kidsProfile = false))
        assertTrue(PrivateRules.seesPrivate(admin = false, unlocked = true, kidsProfile = false))
        assertFalse("locked", PrivateRules.seesPrivate(admin = false, unlocked = false, kidsProfile = false))
        assertFalse("never a kids profile", PrivateRules.seesPrivate(admin = false, unlocked = true, kidsProfile = true))
        assertFalse("not even the admin's", PrivateRules.seesPrivate(admin = true, unlocked = true, kidsProfile = true))
    }

    private fun canSee(private: Boolean = false, offline: Boolean = false, kidsOk: Boolean = true, sees: Boolean = false, admin: Boolean = false, kids: Boolean = false) =
        PrivateRules.canSee(private, offline, kidsOk, sees, admin, kids)

    @Test fun aPrivateTitleNeedsPrivate() {
        assertFalse(canSee(private = true))
        assertFalse("kids-rated doesn't matter", canSee(private = true, kidsOk = true))
        assertTrue(canSee(private = true, sees = true))
    }

    @Test fun anOfflineTitleOnlyOnTheAdminPage() {
        assertFalse(canSee(offline = true))
        assertTrue(canSee(offline = true, admin = true))
    }

    @Test fun aKidsProfileOnlySeesKidsTitles() {
        assertFalse(canSee(kidsOk = false, kids = true))
        assertTrue(canSee(kidsOk = true, kids = true))
        assertTrue(canSee(kidsOk = false))
    }

    @Test fun unlockStaysWithinLimits() {
        assertEquals(5 * 60_000L, PrivateRules.unlockUntil(0, 0))
        assertEquals(30 * 60_000L, PrivateRules.unlockUntil(0, 30))
        assertEquals(12 * 60 * 60_000L, PrivateRules.unlockUntil(0, 100_000))
    }

    @Test fun privateIsNeverLookedUp() {
        assertFalse(PrivateRules.mayLookUp(private = true))
        assertTrue(PrivateRules.mayLookUp(private = false))
    }
}

class RestoreTest {
    private val here = mapOf("id1" to "Dangal on this phone")
    private val byNameAndSize = mapOf(
        ("Dangal (2016).mkv" to 100L) to listOf("Dangal by name"),
        ("Copy.mkv" to 5L) to listOf("first copy", "second copy"),
    )

    @Test fun theSameIdFirst() =
        assertEquals("Dangal on this phone", Restore.target("id1", "Dangal (2016).mkv", 100, here::get, byNameAndSize))

    @Test fun elseTheSameNameAndSize() {
        assertEquals("Dangal by name", Restore.target("old", "Dangal (2016).mkv", 100, here::get, byNameAndSize))
        assertNull("a different size", Restore.target("old", "Dangal (2016).mkv", 101, here::get, byNameAndSize))
    }

    @Test fun neverAGuessBetweenTwo() = assertNull(Restore.target("old", "Copy.mkv", 5, here::get, byNameAndSize))

    @Test fun theNewestProgressWins() {
        val now = mapOf("main" to (10L to "phone"), "kid" to (50L to "phone"))
        val old = mapOf("main" to (20L to "backup"), "kid" to (40L to "backup"), "gone" to (1L to "backup"))
        val merged = Restore.newestPerProfile(now, old) { it.first }
        assertEquals(mapOf("main" to (20L to "backup"), "kid" to (50L to "phone"), "gone" to (1L to "backup")), merged)
    }
}
