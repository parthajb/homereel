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

class PhoneAbiTest {
    private val arm64Phone = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val arm32Phone = listOf("armeabi-v7a", "armeabi")

    @Test fun readsTheAbisFromTheApksFiles() = assertEquals(
        setOf("arm64-v8a"),
        Updates.abisIn(sequenceOf("classes.dex", "lib/arm64-v8a/libtorrent4j.so", "res/lib/x.png", "lib/")),
    )

    @Test fun theRightCopyFits() {
        assertNull(Updates.wrongAbi(setOf("arm64-v8a"), arm64Phone))
        assertNull(Updates.wrongAbi(setOf("armeabi-v7a"), arm32Phone))
        assertNull("a universal APK fits both", Updates.wrongAbi(setOf("arm64-v8a", "armeabi-v7a"), arm32Phone))
        assertNull("no native code fits anything", Updates.wrongAbi(emptySet(), arm64Phone))
    }

    @Test fun the64BitCopyOnA32BitPhone() =
        assertEquals("That copy is for 64-bit phones. This phone needs the one for 32-bit phones, server-armeabi-v7a-release.apk.",
            Updates.wrongAbi(setOf("arm64-v8a"), arm32Phone))

    @Test fun the32BitCopyOnA64BitPhone() =
        assertEquals("That copy is for 32-bit phones. This phone needs the one for 64-bit phones, server-arm64-v8a-release.apk.",
            Updates.wrongAbi(setOf("armeabi-v7a"), arm64Phone))

    @Test fun anUnsupportedCpu() = assertTrue(Updates.wrongAbi(setOf("arm64-v8a"), listOf("x86_64"))!!.contains("x86_64"))
}

class FeedErrorsTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun classifiesWhatTheConnectionThrew() {
        assertEquals(FeedErrors.Failure.NO_NAME, FeedErrors.classify(java.net.UnknownHostException("torlock.com")))
        assertEquals(FeedErrors.Failure.CONNECT_TIMEOUT, FeedErrors.classify(java.net.SocketTimeoutException("failed to connect to torlock.com/13.127.247.216 (port 443) after 8000ms")))
        assertEquals(FeedErrors.Failure.CONNECT_TIMEOUT, FeedErrors.classify(java.net.SocketTimeoutException("connect timed out")))
        assertEquals(FeedErrors.Failure.READ_TIMEOUT, FeedErrors.classify(java.net.SocketTimeoutException("Read timed out")))
        assertEquals(FeedErrors.Failure.REFUSED, FeedErrors.classify(java.net.ConnectException("Connection refused")))
        assertEquals(FeedErrors.Failure.CONNECT_TIMEOUT, FeedErrors.classify(java.net.ConnectException("Connection timed out")))
        assertEquals(FeedErrors.Failure.NO_ROUTE, FeedErrors.classify(java.net.NoRouteToHostException("Host unreachable")))
        assertEquals(FeedErrors.Failure.CERTIFICATE, FeedErrors.classify(javax.net.ssl.SSLHandshakeException("Chain validation failed")))
        assertEquals("a wrapped cause counts", FeedErrors.Failure.NO_NAME, FeedErrors.classify(java.io.IOException("x", java.net.UnknownHostException("y"))))
        assertEquals(FeedErrors.Failure.OTHER, FeedErrors.classify(java.io.IOException("Broken pipe")))
    }

    @Test fun aNameThatPointsNowhereLooksBlocked() {
        assertTrue(FeedErrors.pointsNowhere("torlock.com", listOf(ip("0.0.0.0"))))
        assertTrue(FeedErrors.pointsNowhere("torlock.com", listOf(ip("127.0.0.1"))))
        assertFalse(FeedErrors.pointsNowhere("torlock.com", listOf(ip("13.127.247.216"))))
        assertFalse("typed as numbers, it's what was meant", FeedErrors.pointsNowhere("127.0.0.1", listOf(ip("127.0.0.1"))))
        assertTrue("a name that starts with digits is still a name", FeedErrors.pointsNowhere("127.0.0.1.nip.io", listOf(ip("127.0.0.1"))))
    }

    @Test fun aSiteOnTheInternetMayBeBlocked() {
        val m = FeedErrors.message(FeedErrors.Failure.CONNECT_TIMEOUT, "www.torlock.com", 443, home = false)
        assertTrue(m, m.startsWith("didn't answer at www.torlock.com:443.") && "internet provider may block it" in m)
    }

    @Test fun aComputerAtHomeIsCheckedAtHome() {
        val m = FeedErrors.message(FeedErrors.Failure.CONNECT_TIMEOUT, "192.168.1.20", 9696, home = true)
        assertTrue(m, "computer is on" in m && "provider" !in m)
        assertTrue("refused" in FeedErrors.message(FeedErrors.Failure.REFUSED, "192.168.1.20", 9696, home = true))
    }

    @Test fun everyMessageFollowsTheFeedsName() {
        for (f in FeedErrors.Failure.values()) for (home in listOf(true, false)) {
            val m = FeedErrors.message(f, "host", 1, home)
            assertTrue("$f: $m", m.first().isLowerCase() || m.startsWith("host"))
        }
    }

    @Test fun statuses() {
        assertTrue("problem of its own" in FeedErrors.forStatus(502))
        assertTrue("451" in FeedErrors.forStatus(451))
    }

    @Test fun aLocalNameThatDoesntResolve() {
        val e = runCatching { FeedSafety.clean("http://nas.local:9696", IndexerKind.PROWLARR) { throw java.net.UnknownHostException(it) } }.exceptionOrNull()
        assertTrue(e?.message, e is IllegalArgumentException && e.message!!.startsWith("Couldn't find nas.local"))
    }
}

class CollectionsTest {
    private fun film(id: String, title: String, year: Int?, collection: String?) = Movie(id, title, year, "$title.mkv", collection = collection)
    private val films = listOf(
        film("1", "Chamber of Secrets", 2002, "Harry Potter Collection"),
        film("2", "Philosopher's Stone", 2001, "Harry Potter Collection"),
        film("3", "Toy Story 2", 1999, "Toy Story Collection"),
        film("4", "Toy Story", 1995, "Toy Story Collection"),
        film("5", "Toy Story 4", null, "Toy Story Collection"),
        film("6", "Dangal", 2016, null),
        film("7", "Lone Sequel", 2010, "Only One Here Collection"),
    )

    @Test fun collectionsWithTwoOrMoreBiggestFirst() {
        val c = Collections.of(films)
        assertEquals(listOf("Toy Story Collection", "Harry Potter Collection"), c.map { it.first })
        assertEquals(listOf("Toy Story", "Toy Story 2", "Toy Story 4"), c.first().second.map { it.title })
    }

    @Test fun theOthersInAFilmsCollection() {
        assertEquals(listOf("Philosopher's Stone"), Collections.sameAs(films[0], films).map { it.title })
        assertEquals(emptyList<Movie>(), Collections.sameAs(films[5], films))
        assertEquals(emptyList<Movie>(), Collections.sameAs(films[6], films))
    }
}
