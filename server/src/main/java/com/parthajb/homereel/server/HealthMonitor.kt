package com.parthajb.homereel.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import com.parthajb.homereel.shared.Health

/**
 * How the phone is doing as a server that never sleeps: battery heat and charge, storage, Wi-Fi, when it last
 * scanned, and what went wrong. Checked every five minutes; a hot battery is logged (once an hour) so it shows up
 * on the admin page even if nobody was looking at the time.
 */
class HealthMonitor(
    private val context: Context,
    private val prefs: Prefs,
    private val library: Library,
    private val events: EventLog,
) {
    fun report(): Health {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val (band, link) = wifi()
        val folders = library.folders()
        val warnings = ArrayList<String>()
        if (temp != null && temp >= HOT_C) warnings += "The phone's battery is at ${"%.0f".format(temp)}°C. Move it somewhere cooler, out of its case."
        if (!plugged) warnings += "The phone isn't charging. As a server it should stay plugged in."
        if (band == null) warnings += "The phone isn't on Wi-Fi, so the TV can't reach it."
        for (f in folders) {
            if (!f.available) warnings += "The folder ${f.name} isn't available (USB drive unplugged?). Its videos are hidden from the TV."
            val free = f.freeBytes
            if (f.main && free != null && free < 2L * 1024 * 1024 * 1024) warnings += "Less than 2 GB free in ${f.name}."
        }
        library.scanError?.let { warnings += "The last scan failed: $it" }
        return Health(
            serverName = prefs.serverName,
            version = BuildConfig.VERSION_NAME,
            batteryPercent = if (level >= 0) level * 100 / scale else null,
            charging = plugged,
            batteryTempC = temp,
            uptimeMs = ServerService.startedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0,
            wifiBand = band,
            wifiLinkMbps = link,
            folders = folders,
            lastScanAt = library.lastScanAt,
            lastScanMs = library.lastScanMs,
            movieCount = library.count(),
            showCount = library.showCount(),
            optimizeQueue = library.optimizer.queued(),
            warnings = warnings,
            events = events.recent(),
        )
    }

    /** The five-minute check: logs a hot battery once an hour. */
    fun check() {
        val temp = report().batteryTempC ?: return
        if (temp < HOT_C) return
        val hourAgo = System.currentTimeMillis() - 3_600_000
        if (events.recent().any { it.text.startsWith(HOT) && it.time > hourAgo }) return
        events.warn("$HOT ${"%.0f".format(temp)}°C. A phone that runs hot all day wears its battery out; keep it cool and out of its case.")
    }

    @Suppress("DEPRECATION")
    private fun wifi(): Pair<String?, Int?> {
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo ?: return null to null
        if (info.networkId == -1 && info.linkSpeed <= 0) return null to null
        return (if (info.frequency >= 4900) "5 GHz" else "2.4 GHz") to info.linkSpeed.takeIf { it > 0 }
    }

    private companion object {
        /** Lithium batteries age fast above about 40°C; 42°C leaves room for a busy moment. */
        const val HOT_C = 42.0
        const val HOT = "The battery reached"
    }
}
