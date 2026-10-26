package com.parthajb.homereel.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps the CPU and Wi-Fi awake only while a stream or an upload is open, and for a minute after, because a
 * player opens and closes connections as it seeks. An idle server lets the phone sleep.
 */
class ActivityLocks(context: Context) {
    private val wake = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HomeReel:serve")
        .apply { setReferenceCounted(false) }

    @Suppress("DEPRECATION")
    private val wifi = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HomeReel:serve")
        .apply { setReferenceCounted(false) }

    private val handler = Handler(Looper.getMainLooper())
    private var active = 0
    private var streams = 0
    private val release = Runnable {
        synchronized(this) {
            if (active == 0) {
                if (wake.isHeld) wake.release()
                if (wifi.isHeld) wifi.release()
            }
        }
    }

    /** [watching] marks a video stream, so torrent downloads know to slow down. */
    fun begin(watching: Boolean = false) = synchronized(this) {
        active++
        if (watching) streams++
        handler.removeCallbacks(release)
        // Renewed by every request, so the timeout only matters if a stream is somehow never closed.
        wake.acquire(4 * 60 * 60 * 1000L)
        if (!wifi.isHeld) wifi.acquire()
    }

    fun end(watching: Boolean = false) = synchronized(this) {
        active = maxOf(0, active - 1)
        if (watching) streams = maxOf(0, streams - 1)
        if (active == 0) handler.postDelayed(release, 60_000)
    }

    val busy: Boolean get() = synchronized(this) { active > 0 }

    /** Whether someone is watching something right now. */
    val watching: Boolean get() = synchronized(this) { streams > 0 }
}
