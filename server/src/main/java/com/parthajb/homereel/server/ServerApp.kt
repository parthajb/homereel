package com.parthajb.homereel.server

import android.app.Application

class ServerApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var auth: Auth
        private set
    lateinit var library: Library
        private set
    lateinit var locks: ActivityLocks
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        auth = Auth(prefs)
        locks = ActivityLocks(this)
        library = Library(this, prefs)
    }
}
