package com.parthajb.homereel.server

import android.app.Application
import java.io.File

class ServerApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var auth: Auth
        private set
    lateinit var library: Library
        private set
    lateinit var locks: ActivityLocks
        private set
    lateinit var profiles: Profiles
        private set
    lateinit var events: EventLog
        private set
    lateinit var health: HealthMonitor
        private set
    lateinit var updates: AppUpdates
        private set

    override fun onCreate() {
        super.onCreate()
        events = EventLog(File(filesDir, "events.json"))
        // Note a crash before the process dies; Android then restarts the server (it's a sticky service).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            events.error("Crashed: ${e.javaClass.simpleName}: ${e.message}. Android restarts the server by itself.")
            previous?.uncaughtException(thread, e)
        }
        prefs = Prefs(this)
        auth = Auth(prefs)
        profiles = Profiles(prefs)
        locks = ActivityLocks(this)
        library = Library(this, prefs, locks, events)
        health = HealthMonitor(this, prefs, library, events)
        updates = AppUpdates(this, events)
    }
}
