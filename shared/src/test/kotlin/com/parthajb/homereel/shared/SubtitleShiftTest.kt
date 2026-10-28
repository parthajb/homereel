package com.parthajb.homereel.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleShiftTest {
    private fun shift(text: String, ms: Long, charset: java.nio.charset.Charset = Charsets.UTF_8) =
        String(SubtitleShift.shift(text.toByteArray(charset), ms), charset)

    private val srt = "1\r\n00:00:01,500 --> 00:00:03,000\r\nHello\r\n\r\n2\r\n00:59:59,900 --> 01:00:00,400\r\nBye\r\n"

    @Test fun later() = assertEquals(
        "1\r\n00:00:02,000 --> 00:00:03,500\r\nHello\r\n\r\n2\r\n01:00:00,400 --> 01:00:00,900\r\nBye\r\n", shift(srt, 500))

    @Test fun earlierStopsAtZero() = assertEquals(
        "1\r\n00:00:00,000 --> 00:00:01,000\r\nHello\r\n\r\n2\r\n00:59:57,900 --> 00:59:58,400\r\nBye\r\n", shift(srt, -2000))

    @Test fun vttKeepsItsDotAndGainsHours() = assertEquals(
        "WEBVTT\n\n00:01:01.250 --> 00:01:02.000 line:90%\nHi 00:00:01.000 in the text stays\n",
        shift("WEBVTT\n\n01:00.250 --> 01:01.000 line:90%\nHi 00:00:01.000 in the text stays\n", 1000),
    )

    @Test fun textInAnyCharacterSetSurvives() {
        val latin1 = "1\n00:00:01,000 --> 00:00:02,000\nCafé crème, ¿qué?\n"
        assertEquals(latin1.replace("00:00:01,000 --> 00:00:02,000", "00:00:01,100 --> 00:00:02,100"), shift(latin1, 100, Charsets.ISO_8859_1))
        val hindi = "1\n00:00:01,000 --> 00:00:02,000\nनमस्ते\n"
        assertEquals(hindi.replace("00:00:01,000 --> 00:00:02,000", "00:00:00,900 --> 00:00:01,900"), shift(hindi, -100))
    }

    @Test fun nothingToDo() {
        val bytes = srt.toByteArray()
        assertArrayEquals(bytes, SubtitleShift.shift(bytes, 0))
        val utf16 = srt.toByteArray(Charsets.UTF_16)
        assertArrayEquals("UTF-16 is left alone", utf16, SubtitleShift.shift(utf16, 500))
    }

    @Test fun labels() {
        assertEquals("0 s", SubtitleShift.label(0))
        assertEquals("+0.5 s", SubtitleShift.label(500))
        assertEquals("−1.2 s", SubtitleShift.label(-1200))
    }

    @Test fun clamped() {
        assertEquals(SubtitleShift.MAX_MS, SubtitleShift.clamp(Long.MAX_VALUE))
        assertEquals(-SubtitleShift.MAX_MS, SubtitleShift.clamp(-SubtitleShift.MAX_MS - 1))
    }
}
