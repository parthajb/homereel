package com.parthajb.homereel.shared

sealed interface RangeResult {
    /** No usable Range header: send the whole file with 200. */
    data object Full : RangeResult

    data class Partial(val start: Long, val end: Long) : RangeResult {
        val length: Long get() = end - start + 1
    }

    /** The range starts past the end of the file: 416. */
    data object Unsatisfiable : RangeResult
}

/** Parses a single `Range: bytes=…` header as RFC 9110 describes. Multiple ranges get the whole file, which it allows. */
object ByteRange {
    fun parse(header: String?, size: Long): RangeResult {
        val h = header?.trim() ?: return RangeResult.Full
        if (!h.startsWith("bytes=", ignoreCase = true)) return RangeResult.Full
        val spec = h.substring(6).trim()
        if (spec.contains(',')) return RangeResult.Full
        val dash = spec.indexOf('-')
        if (dash < 0) return RangeResult.Full
        val first = spec.substring(0, dash).trim()
        val last = spec.substring(dash + 1).trim()
        if (first.isEmpty()) {
            // Suffix range: the last N bytes.
            val n = last.toLongOrNull() ?: return RangeResult.Full
            if (n <= 0 || size <= 0) return RangeResult.Unsatisfiable
            return RangeResult.Partial(maxOf(0, size - n), size - 1)
        }
        val start = first.toLongOrNull() ?: return RangeResult.Full
        if (start < 0) return RangeResult.Full
        if (start >= size) return RangeResult.Unsatisfiable
        val end = if (last.isEmpty()) size - 1 else last.toLongOrNull() ?: return RangeResult.Full
        // An invalid range-spec makes the whole header ignored.
        if (end < start) return RangeResult.Full
        return RangeResult.Partial(start, minOf(end, size - 1))
    }
}
