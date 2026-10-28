package com.parthajb.homereel.shared

import java.util.Locale

/**
 * Moves every cue in an .srt or .vtt file earlier or later, for subtitles that are out of step with the video. Only
 * the cue timings change; the text stays byte for byte, whatever its character set.
 */
object SubtitleShift {
    /** The furthest either way: past this the subtitles belong to another cut of the film. */
    const val MAX_MS = 10 * 60_000L

    private val cueLine = Regex("(?m)^.*-->.*$")
    private val timing = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})([,.])(\d{3})""")

    /**
     * [bytes] with each cue [offsetMs] later (or earlier when negative), never before 0:00. A UTF-16 file is left as
     * it is, since its timings aren't plain bytes.
     */
    fun shift(bytes: ByteArray, offsetMs: Long): ByteArray {
        if (offsetMs == 0L || isUtf16(bytes)) return bytes
        // ISO-8859-1 maps every byte to one character and back, so text in any 8-bit set or UTF-8 survives untouched.
        val text = String(bytes, Charsets.ISO_8859_1)
        // Only the timing lines, each up to its line ending, so \r\n endings stay as they were.
        val shifted = cueLine.replace(text) { line -> timing.replace(line.value) { m -> format(parse(m) + offsetMs, m.groupValues[4]) } }
        return shifted.toByteArray(Charsets.ISO_8859_1)
    }

    fun clamp(offsetMs: Long) = offsetMs.coerceIn(-MAX_MS, MAX_MS)

    private fun isUtf16(b: ByteArray) =
        b.size >= 2 && ((b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) || (b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()))

    private fun parse(m: MatchResult): Long {
        val (h, min, s, _, ms) = m.destructured
        return ((h.ifEmpty { "0" }.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000 + ms.toLong()
    }

    /** Always with hours, which both formats allow and .srt needs. */
    private fun format(ms: Long, separator: String): String {
        val t = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d%s%03d".format(Locale.ROOT, t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, separator, t % 1000)
    }

    /** "+0.5 s", "−1.2 s" or "0 s", as the TV and web app show it. */
    fun label(offsetMs: Long): String = when {
        offsetMs == 0L -> "0 s"
        offsetMs > 0 -> "+%.1f s".format(Locale.ROOT, offsetMs / 1000.0)
        else -> "−%.1f s".format(Locale.ROOT, -offsetMs / 1000.0)
    }
}
