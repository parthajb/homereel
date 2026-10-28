package com.parthajb.homereel.shared

/** Who sees the Private folder, and who sees a title at all. The phone app applies these to every request. */
object PrivateRules {
    /** Private unlocks for 5 minutes at the least and 12 hours at the most. */
    const val MIN_UNLOCK_MINUTES = 5
    const val MAX_UNLOCK_MINUTES = 12 * 60

    /** The admin always sees Private; a TV or browser only while it's unlocked; a kids profile never. */
    fun seesPrivate(admin: Boolean, unlocked: Boolean, kidsProfile: Boolean): Boolean = (admin || unlocked) && !kidsProfile

    /** When an unlock for [minutes] ends, kept within the limits above. */
    fun unlockUntil(now: Long, minutes: Int): Long = now + minutes.coerceIn(MIN_UNLOCK_MINUTES, MAX_UNLOCK_MINUTES) * 60_000L

    /**
     * Whether a viewer sees a title. A private one needs [seesPrivate]; one on a missing USB drive shows only on the
     * admin page; a kids profile sees only titles cleared for kids ([kidsOk]).
     */
    fun canSee(private: Boolean, offline: Boolean, kidsOk: Boolean, seesPrivate: Boolean, admin: Boolean, kidsProfile: Boolean): Boolean {
        if (private && !seesPrivate) return false
        if (offline && !admin) return false
        return !kidsProfile || kidsOk
    }

    /** Private videos are never looked up online. */
    fun mayLookUp(private: Boolean): Boolean = !private
}

/** How a backup's details find their way back onto the files a phone has now. */
object Restore {
    /**
     * The file a backed-up entry belongs to: the same id (the same folder), or else the one file with the same name
     * and size (a new phone or folder). Two files alike match neither, rather than guess.
     */
    fun <T> target(id: String, fileName: String, size: Long, byId: (String) -> T?, byNameAndSize: Map<Pair<String, Long>, List<T>>): T? =
        byId(id) ?: byNameAndSize[fileName to size]?.singleOrNull()

    /** For each profile, whichever progress was played last wins. */
    fun <P> newestPerProfile(now: Map<String, P>, old: Map<String, P>, playedAt: (P) -> Long): Map<String, P> =
        (now.keys + old.keys).associateWith { pid -> listOfNotNull(now[pid], old[pid]).maxBy(playedAt) }
}
