package com.parthajb.homereel.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ByteRangeTest {
    private val size = 1000L

    @Test fun noHeaderIsFull() = assertEquals(RangeResult.Full, ByteRange.parse(null, size))
    @Test fun openEnded() = assertEquals(RangeResult.Partial(0, 999), ByteRange.parse("bytes=0-", size))
    @Test fun closed() = assertEquals(RangeResult.Partial(500, 999), ByteRange.parse("bytes=500-999", size))
    @Test fun endClampedToSize() = assertEquals(RangeResult.Partial(900, 999), ByteRange.parse("bytes=900-5000", size))
    @Test fun suffix() = assertEquals(RangeResult.Partial(900, 999), ByteRange.parse("bytes=-100", size))
    @Test fun suffixLargerThanFile() = assertEquals(RangeResult.Partial(0, 999), ByteRange.parse("bytes=-5000", size))
    @Test fun startPastEnd() = assertEquals(RangeResult.Unsatisfiable, ByteRange.parse("bytes=1000-", size))
    @Test fun zeroSuffix() = assertEquals(RangeResult.Unsatisfiable, ByteRange.parse("bytes=-0", size))
    @Test fun reversedIsIgnored() = assertEquals(RangeResult.Full, ByteRange.parse("bytes=500-100", size))
    @Test fun garbageIsIgnored() = assertEquals(RangeResult.Full, ByteRange.parse("bytes=abc-def", size))
    @Test fun otherUnitIsIgnored() = assertEquals(RangeResult.Full, ByteRange.parse("items=0-5", size))
    @Test fun multiRangeGetsWholeFile() = assertEquals(RangeResult.Full, ByteRange.parse("bytes=0-1,5-9", size))
    @Test fun length() = assertEquals(500L, (ByteRange.parse("bytes=500-", size) as RangeResult.Partial).length)
}

class TitleParserTest {
    private fun p(name: String) = TitleParser.parse(name)

    @Test fun dotted() = assertEquals(TitleParser.Parsed("Movie Name", 2019), p("Movie.Name.2019.1080p.BluRay.x264.mkv"))
    @Test fun parenthesisedYear() = assertEquals(TitleParser.Parsed("The Lunchbox", 2013), p("The Lunchbox (2013).mp4"))
    @Test fun noYear() = assertEquals(TitleParser.Parsed("Home Video", null), p("Home Video.mp4"))
    @Test fun noYearCutAtTag() = assertEquals(TitleParser.Parsed("Some Film", null), p("Some_Film_720p_WEBRip.mkv"))
    @Test fun titleThatIsAYear() = assertEquals(TitleParser.Parsed("1917", 2019), p("1917.2019.2160p.mkv"))
    @Test fun yearInsideTitle() = assertEquals(
        TitleParser.Parsed("Blade Runner 2049", 2017), p("Blade.Runner.2049.2017.1080p.mkv"),
    )
    @Test fun bracketsTrimmed() = assertEquals(TitleParser.Parsed("Dangal", 2016), p("Dangal [2016] [1080p].mkv"))
}

class MediaFilesTest {
    @Test fun safeName() = assertEquals("Movie (2019).mkv", MediaFiles.safeUploadName("Movie (2019).mkv"))
    @Test fun pathStripped() = assertEquals("x.mp4", MediaFiles.safeUploadName("../../etc/x.mp4"))
    @Test fun windowsPathStripped() = assertEquals("x.mp4", MediaFiles.safeUploadName("C:\\Users\\me\\x.mp4"))
    @Test fun hiddenDotsStripped() = assertEquals("x.srt", MediaFiles.safeUploadName("..x.srt"))
    @Test fun badCharacters() = assertEquals("a_b_.mp4", MediaFiles.safeUploadName("a:b?.mp4"))
    @Test fun refusesOtherTypes() = assertNull(MediaFiles.safeUploadName("run.sh"))
    @Test fun refusesEmpty() = assertNull(MediaFiles.safeUploadName("  "))
    @Test fun mime() = assertEquals("video/x-matroska", MediaFiles.videoMime("a.MKV"))
}

class MovieIdsTest {
    @Test fun stable() = assertEquals(MovieIds.of("content://x/1"), MovieIds.of("content://x/1"))
    @Test fun distinct() = assertNotEquals(MovieIds.of("content://x/1"), MovieIds.of("content://x/2"))
    @Test fun sixteenHex() = assertEquals(16, MovieIds.of("content://x/1").length)
}

class JsonTest {
    @Test fun movieRoundTrip() {
        val movie = Movie(
            id = "abc", title = "T", year = 2020, fileName = "t.mkv", size = 5, durationMs = 9,
            video = VideoTrack("video/avc", "avc1.640028", 1920, 1080),
            audio = listOf(AudioTrack("audio/ac3", language = "hin", channels = 6)),
            subtitles = listOf(Subtitle(0, "English", "en")),
            description = "A family drama.", genres = listOf("Drama", "Comedy"), imdbRating = 7.8,
        )
        val text = ApiJson.encodeToString(Movie.serializer(), movie)
        assertEquals(movie, ApiJson.decodeFromString(Movie.serializer(), text))
    }

    @Test fun olderServerWithoutMetadataStillReads() {
        val movie = ApiJson.decodeFromString(Movie.serializer(), """{"id":"a","title":"T","fileName":"t.mp4"}""")
        assertEquals(emptyList<String>(), movie.genres)
        assertNull(movie.imdbRating)
    }

    @Test fun unknownKeysIgnored() {
        val info = ApiJson.decodeFromString(
            ServerInfo.serializer(),
            """{"name":"n","version":"1","api":1,"movieCount":2,"scanning":false,"future":true}""",
        )
        assertEquals(2, info.movieCount)
    }
}

class PlayabilityTest {
    private val ok = Movie(
        id = "1", title = "t", fileName = "t.mkv",
        video = VideoTrack("video/avc", width = 1920, height = 1080),
        audio = listOf(AudioTrack("audio/mp4a-latm")),
    )

    @Test fun commonFilePlays() = assertNull(Playability.baseline(ok))
    @Test fun dtsOnlyFlagged() = assertEquals(
        "DTS audio isn't supported", Playability.baseline(ok.copy(audio = listOf(AudioTrack("audio/vnd.dts")))),
    )
    @Test fun oneGoodAudioTrackIsEnough() = assertNull(
        Playability.baseline(ok.copy(audio = listOf(AudioTrack("audio/vnd.dts"), AudioTrack("audio/ac3")))),
    )
    @Test fun unreadable() = assertEquals(
        "Couldn't read the file (bad header)", Playability.baseline(ok.copy(probeError = "bad header")),
    )
    @Test fun av1Flagged() = assertEquals(
        "AV1 video at 1920×1080 isn't supported",
        Playability.baseline(ok.copy(video = VideoTrack("video/av01", width = 1920, height = 1080))),
    )
}

class MetadataTest {
    @Test fun genresKeepCommonSpelling() =
        assertEquals(listOf("Sci-Fi", "Drama"), Genres.normalize(listOf(" sci-fi ", "DRAMA", "drama", "")))

    @Test fun customGenreCapitalised() = assertEquals(listOf("Bollywood"), Genres.normalize(listOf("bollywood")))

    @Test fun atMostFiveGenres() = assertEquals(5, Genres.normalize(Genres.COMMON).size)

    @Test fun ratingRounded() = assertEquals(7.9, Ratings.normalize(7.86)!!, 0.0)

    @Test fun ratingOutOfRange() = assertNull(Ratings.normalize(11.0))
}

class MatchingTest {
    /** Only a leading article goes; punctuation becomes spaces. */
    @Test fun normalisation() = assertEquals("lord of the rings the return of the king", Matching.normalise("The Lord of the Rings: The Return of the King"))
    @Test fun articlesAndPunctuation() = assertEquals(Matching.normalise("Lunchbox"), Matching.normalise("The Lunchbox!"))
    @Test fun ampersand() = assertEquals(1.0, Matching.similarity("Fast & Furious", "Fast and Furious"), 0.0)
    @Test fun accents() = assertEquals(1.0, Matching.similarity("Amelie", "Amélie"), 0.0)
    @Test fun exactWithYear() = assertEquals(true, Matching.confident(Matching.score("Dangal", 2016, "Dangal", null, 2016), 2016))
    @Test fun yearOffByOne() = assertEquals(true, Matching.confident(Matching.score("Dangal", 2017, "Dangal", null, 2016), 2017))
    @Test fun yearTooFar() = assertEquals(0.0, Matching.score("Dangal", 2010, "Dangal", null, 2016), 0.0)
    @Test fun wrongFilm() = assertEquals(false, Matching.confident(Matching.score("Night Train", 2016, "Bullet Train", null, 2016), 2016))
    @Test fun originalTitleCounts() = assertEquals(true, Matching.confident(Matching.score("Amelie", 2001, "Amélie", "Le Fabuleux Destin d'Amélie Poulain", 2001), 2001))
    @Test fun noYearNeedsNearExact() {
        assertEquals(true, Matching.confident(Matching.score("Dangal", null, "Dangal", null, 2016), null))
        assertEquals(false, Matching.confident(Matching.score("Dangl", null, "Dangal", null, 2016), null))
    }
    @Test fun droneClipIsHomeVideo() = assertEquals(true, Matching.isHomeVideo("DJI_20261020090147_0058_D.MP4", "Movies"))
    @Test fun phoneClipIsHomeVideo() = assertEquals(true, Matching.isHomeVideo("PXL_20260101_120000.mp4", null))
    @Test fun homeVideosFolder() = assertEquals(true, Matching.isHomeVideo("Goa trip.mp4", "Home videos"))
    @Test fun filmIsNotHomeVideo() = assertEquals(false, Matching.isHomeVideo("Dangal (2016).mkv", "Movies"))
}

/** Similar-sounding different films must never fill in automatically, even with the same year. */
class NearMissTest {
    private val pairs = listOf(
        "Night Train" to "Night Shift", "Night Train" to "Bullet Train", "Desert Run" to "Desert Rain",
        "Paper Boats" to "Paper Towns", "Monsoon Diaries" to "Monsoon Wedding", "Drishyam" to "Drishyam 2",
        "Kahaani" to "Kahaani 2", "Home Alone" to "Home Alone 2: Lost in New York", "Andhadhun" to "Andaz",
    )

    @Test fun nearMissesAreNotConfident() {
        for ((file, result) in pairs) {
            val score = Matching.score(file, 2016, result, null, 2016)
            println("%-16s %-30s %.3f".format(file, result, score))
            assertEquals("$file vs $result scored $score", false, Matching.confident(score, 2016))
        }
    }
}

class EpisodeParserTest {
    private fun p(name: String, vararg folders: String) = EpisodeParser.parse(name, folders.toList())

    @Test fun sxe() = assertEquals(
        EpisodeParser.Episode("Breaking Bad", null, 1, 2, "Cats in the Bag"),
        p("Breaking.Bad.S01E02.Cats.in.the.Bag.720p.BluRay.mkv", "TV", "Breaking Bad"),
    )
    @Test fun lowercaseWithYear() = assertEquals(
        EpisodeParser.Episode("Panchayat", 2020, 2, 5, null), p("panchayat.2020.s02e05.1080p.mkv"),
    )
    @Test fun nxn() = assertEquals(EpisodeParser.Episode("The Office", null, 3, 12, "Traveling Salesmen"), p("The Office 3x12 Traveling Salesmen.mp4"))
    @Test fun seasonFolder() = assertEquals(
        EpisodeParser.Episode("Kota Factory", null, 1, 3, "Physics"), p("03 - Physics.mkv", "Kota Factory", "Season 1"),
    )
    @Test fun seasonFolderEpisodeWord() = assertEquals(
        EpisodeParser.Episode("Kota Factory", null, 2, 4, null), p("Episode 4.mkv", "Kota Factory", "S02"),
    )
    @Test fun bareSxeUsesFolder() = assertEquals(EpisodeParser.Episode("Mirzapur", null, 1, 1, null), p("S01E01.mkv", "Mirzapur"))
    @Test fun filmIsNotEpisode() = assertNull(p("Dangal (2016).mkv", "Movies"))
    @Test fun filmWithNumberIsNotEpisode() = assertNull(p("2001 A Space Odyssey (1968).mkv"))
    @Test fun resolutionIsNotNxN() = assertNull(p("Some Film 1920x1080.mkv"))
    @Test fun sameShowSameId() = assertEquals(EpisodeParser.showId("The Office", null), EpisodeParser.showId("Office", null))
}

class FamilyTest {
    @Test fun indianU() = assertEquals(true, Certifications.kidsSafe("U"))
    @Test fun indianUa7() = assertEquals(true, Certifications.kidsSafe("U/A 7+"))
    @Test fun indianUa13() = assertEquals(false, Certifications.kidsSafe("UA 13+"))
    @Test fun adult() = assertEquals(false, Certifications.kidsSafe("A"))
    @Test fun usPg13() = assertEquals(false, Certifications.kidsSafe("PG-13"))
    @Test fun tvY7() = assertEquals(true, Certifications.kidsSafe("TV-Y7"))
    @Test fun unratedIsNo() = assertEquals(false, Certifications.kidsSafe(null))
    @Test fun adminYesWins() = assertEquals(true, Certifications.kidsOk(KidsSetting.YES, "A", private = false))
    @Test fun adminNoWins() = assertEquals(false, Certifications.kidsOk(KidsSetting.NO, "U", private = false))
    @Test fun privateNeverForKids() = assertEquals(false, Certifications.kidsOk(KidsSetting.YES, "U", private = true))

    private fun s(key: String, genres: List<String>, people: List<String> = emptyList(), director: String? = null) =
        Recommend.Signals(key, genres, people, director)

    @Test fun becauseYouWatched() {
        val seed = s("a", listOf("Drama", "Sport"), listOf("Aamir"), "Nitesh")
        val all = listOf(
            seed,
            s("b", listOf("Drama", "Sport")),
            s("c", listOf("Comedy"), listOf("Aamir")),
            s("d", listOf("Drama")),
            s("e", listOf("Horror")),
            s("f", listOf("Thriller"), director = "Nitesh"),
        )
        assertEquals(listOf("f", "c", "b"), Recommend.similar(seed, all))
    }

    @Test fun searchTitleBeatsCast() {
        val title = Search.score("dang", "Dangal", listOf("Aamir Khan"))
        val cast = Search.score("aamir", "Lagaan", listOf("Aamir Khan"))
        assertEquals(true, title > cast && cast > 0)
    }
    @Test fun searchNeedsEveryWord() = assertEquals(0, Search.score("night shift", "Night Train", emptyList()))
    @Test fun searchIgnoresAccentsAndCase() = assertEquals(true, Search.score("AMELIE", "Amélie", emptyList()) > 0)
    @Test fun searchByGenre() = assertEquals(true, Search.score("comedy", "Coco", listOf("Animation", "Comedy")) > 0)
}

class PlaybackTest {
    @Test fun droneClipIsHeavy() = assertEquals(true, Bitrate.heavy(Bitrate.mbps(306_353_291, 26_326)))
    @Test fun droneClipMbps() = assertEquals(93.1, Bitrate.mbps(306_353_291, 26_326)!!, 0.05)
    @Test fun normalFilmIsNot() = assertEquals(false, Bitrate.heavy(Bitrate.mbps(2_000_000_000, 2 * 3600_000L)))
    @Test fun unknownDuration() = assertNull(Bitrate.mbps(100, 0))
    @Test fun portrait4kTarget() = assertEquals(24_000_000, Bitrate.target(2160, 3840))
    @Test fun hdTarget() = assertEquals(10_000_000, Bitrate.target(1920, 1080))

    @Test fun markers() = assertEquals(
        Markers.Times(30_000, 90_000, 1_740_000),
        Markers.forEpisode(SeasonMarkers(1, 30_000, 90_000, 60_000), 1_800_000),
    )
    @Test fun backwardsIntroDropped() = assertEquals(
        Markers.Times(null, null, null), Markers.forEpisode(SeasonMarkers(1, 90_000, 30_000), 1_800_000),
    )
    @Test fun creditsLongerThanEpisodeDropped() = assertNull(Markers.forEpisode(SeasonMarkers(1, creditsMs = 99_000), 60_000).creditsStartMs)
    @Test fun parseMinutes() = assertEquals(65_000L, Markers.parse("1:05"))
    @Test fun parseHours() = assertEquals(3_723_000L, Markers.parse("1:02:03"))
    @Test fun parseNonsense() = assertNull(Markers.parse("1:75"))
    @Test fun parseEmpty() = assertNull(Markers.parse(" "))
}

class UpdatesTest {
    private fun update(code: Long) = AppUpdate(Updates.TV, code, "0.2.$code", 1, "x", 0)

    @Test fun newerIsOffered() = assertEquals(true, Updates.isNewer(update(3), 2))
    @Test fun sameIsNotOffered() = assertEquals(false, Updates.isNewer(update(2), 2))
    @Test fun olderIsNotOffered() = assertEquals(false, Updates.isNewer(update(1), 2))
    @Test fun noneIsNotOffered() = assertEquals(false, Updates.isNewer(null, 2))
}

class TorrentsTest {
    private val gb = 1024L * 1024 * 1024

    @Test fun keepsTheFilm() = assertEquals(true, Torrents.wanted("Film (2019)/Film.2019.1080p.mkv", 2 * gb, 2 * gb))
    @Test fun keepsSubtitles() = assertEquals(true, Torrents.wanted("Film/Subs/English.srt", 50_000, 2 * gb))
    @Test fun skipsTextAndPictures() {
        assertEquals(false, Torrents.wanted("Film/README.txt", 1000, 2 * gb))
        assertEquals(false, Torrents.wanted("Film/cover.jpg", 100_000, 2 * gb))
        assertEquals(false, Torrents.wanted("Film/setup.exe", 100_000, 2 * gb))
    }
    @Test fun skipsTheSample() = assertEquals(false, Torrents.wanted("Film/Sample/film-sample.mkv", 40_000_000, 2 * gb))
    @Test fun keepsAFilmCalledSample() = assertEquals(true, Torrents.wanted("The Sample (2010).mp4", 2 * gb, 2 * gb))
    @Test fun keepsEpisodes() = assertEquals(true, Torrents.wanted("Show S01/Show.S01E02.mkv", gb / 2, gb / 2 + 1))

    @Test fun anyTime() = assertEquals(true, Torrents.inHours(null, null, 15))
    @Test fun daytimeWindow() {
        assertEquals(true, Torrents.inHours(9, 17, 9))
        assertEquals(false, Torrents.inHours(9, 17, 17))
    }
    @Test fun overnightWindow() {
        assertEquals(true, Torrents.inHours(22, 6, 23))
        assertEquals(true, Torrents.inHours(22, 6, 2))
        assertEquals(false, Torrents.inHours(22, 6, 12))
    }

    @Test fun freeNameUnchanged() = assertEquals("Film.mkv", Torrents.freeName("Film.mkv", setOf("Other.mkv")))
    @Test fun freeNameNumbers() = assertEquals("Film (3).mkv", Torrents.freeName("Film.mkv", setOf("film.mkv", "Film (2).mkv")))

    @Test fun magnets() {
        assertEquals(true, Torrents.isMagnet("magnet:?xt=urn:btih:abcdef&dn=x"))
        assertEquals(false, Torrents.isMagnet("https://example.com/x.torrent"))
    }
}
