package com.parthajb.homereel.server

import android.util.Log
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.Event
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The last 200 things worth knowing: starts and stops, scans, lookups and copies that failed, a hot battery, a
 * crash. Kept in a small file so a crash's entry survives the restart; shown on the admin page's Health panel.
 */
class EventLog(private val file: File) {
    private val serializer = ListSerializer(Event.serializer())
    private val events = ArrayDeque<Event>()

    init {
        runCatching { if (file.exists()) events.addAll(ApiJson.decodeFromString(serializer, file.readText())) }
    }

    fun info(text: String) = add(Event.INFO, text)

    fun warn(text: String) = add(Event.WARN, text)

    fun error(text: String) = add(Event.ERROR, text)

    /** Newest first. */
    @Synchronized fun recent(limit: Int = 50): List<Event> = events.reversed().take(limit)

    @Synchronized private fun add(level: String, text: String) {
        Log.i("HomeReel", "[$level] $text")
        events.addLast(Event(System.currentTimeMillis(), level, text.take(300)))
        while (events.size > 200) events.removeFirst()
        // Written straight away (it's small), so the entry for a crash is on disk before the process dies.
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(ApiJson.encodeToString(serializer, events.toList()))
            tmp.renameTo(file)
        }
    }
}
