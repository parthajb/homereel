package com.parthajb.homereel.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenSubtitlesTest {
    @Test fun hashOfZeroesIsTheSize() {
        val zeros = ByteArray(OpenSubtitles.CHUNK)
        assertEquals("0000000000020000", OpenSubtitles.hash(131072, zeros, zeros))
    }

    @Test fun hashAddsLittleEndianWords() {
        val head = ByteArray(16).also { it[0] = 1; it[8] = 2 } // words 1 and 2
        val tail = ByteArray(8).also { it[1] = 1 } // word 256
        assertEquals("%016x".format(100L + 1 + 2 + 256), OpenSubtitles.hash(100, head, tail))
    }

    @Test fun hashWrapsOnOverflow() {
        val ff = ByteArray(8) { -1 } // the word -1
        assertEquals("ffffffffffffffff", OpenSubtitles.hash(1, ff, ff))
        assertEquals("0000000000000000", OpenSubtitles.hash(2, ff, ff))
    }

    @Test fun languages() {
        assertEquals(listOf("en", "hi", "pt-br"), OpenSubtitles.languages("en, hi; pt-BR, en, english, 12"))
        assertEquals(emptyList<String>(), OpenSubtitles.languages(null))
    }

    private fun result(lang: String, id: Long, downloads: Long = 0, hash: Boolean = false, machine: Boolean = false, hi: Boolean = false) =
        OpenSubtitles.Result(OpenSubtitles.Attributes(lang, downloads, hash, hi, machine, false, listOf(OpenSubtitles.File(id))))

    @Test fun picksTheBestForEachLanguage() {
        val results = listOf(
            result("en", 1, downloads = 9000),
            result("en", 2, downloads = 10, hash = true),
            result("hi", 3, downloads = 500, machine = true),
            result("hi", 4, downloads = 5),
            result("hi", 5, downloads = 50, hi = true),
            result("fr", 6),
        )
        assertEquals(
            listOf(OpenSubtitles.Choice("hi", 4), OpenSubtitles.Choice("en", 2)),
            OpenSubtitles.pick(results, listOf("hi", "en", "de")),
        )
    }

    @Test fun readsTheSearchReply() {
        val json = """{"total_count":1,"data":[{"id":"9","type":"subtitle","attributes":{"language":"en","download_count":12,
            "moviehash_match":true,"files":[{"file_id":42,"file_name":"x.srt","cd_number":1}],"extra":"ignored"}}]}"""
        val page = ApiJson.decodeFromString(OpenSubtitles.SearchPage.serializer(), json)
        assertEquals(listOf(OpenSubtitles.Choice("en", 42)), OpenSubtitles.pick(page.data, listOf("en")))
    }

    @Test fun labels() = assertEquals("Hindi · OpenSubtitles", OpenSubtitles.label("hi"))
}
