package com.parthajb.homereel.shared

/**
 * What to change in Android TV's Play next row, on the TV's own home screen, so it matches HomeReel's Continue
 * watching. A title the person removed from the row stays out until it's no longer being watched.
 */
object PlayNext {
    /** At most this many: the row is for carrying on, not a second library. */
    const val MAX = 10

    /** A row HomeReel already put there: its id in the TV's list, the title's key, and whether it's still shown. */
    data class Existing(val programId: Long, val key: String, val shown: Boolean)

    data class Plan(val add: List<String>, val update: List<Pair<Long, String>>, val remove: List<Long>)

    /** [wanted] is Continue watching's keys, the most recently played first. */
    fun plan(wanted: List<String>, existing: List<Existing>): Plan {
        val keep = wanted.distinct().take(MAX)
        val byKey = existing.groupBy { it.key }
        val add = keep.filter { it !in byKey }
        // One row per title; a removed (hidden) one isn't touched, so it stays out.
        val update = keep.mapNotNull { key -> byKey[key]?.firstOrNull { it.shown }?.let { it.programId to key } }
        val duplicates = byKey.values.flatMap { rows -> rows.filter { it.shown }.drop(1) }
        val remove = existing.filter { it.key !in keep }.map { it.programId } + duplicates.map { it.programId }
        return Plan(add, update, remove.distinct())
    }

    /** "m:<id>" for a film, "s:<show id>" for a show, as the row and the launch link know them. */
    fun key(movie: Movie): String = movie.showId?.let { "s:$it" } ?: "m:${movie.id}"

    /** The link a row opens: homereel://play-next/m/<id> or …/s/<show id>. */
    fun link(key: String): String = "homereel://play-next/" + key.replaceFirst(':', '/')

    /** The key in a link, if it's one HomeReel made; null for anything else. */
    fun keyOf(link: String?): String? {
        val m = Regex("""^homereel://play-next/([ms])/([A-Za-z0-9_-]{1,64})$""").find(link ?: return null) ?: return null
        return "${m.groupValues[1]}:${m.groupValues[2]}"
    }
}
