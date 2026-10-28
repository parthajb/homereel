package com.parthajb.homereel.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import java.net.Inet4Address

/**
 * Keeps the phone findable and answering on the Wi-Fi while the screen is off.
 *
 * - **Finding the phone:** with the screen off, Android drops multicast packets unless an app holds a multicast lock,
 *   and mDNS (how the TV finds the phone by name) is multicast. The lock is held for as long as the server runs.
 * - **While charging** (on by default): the CPU and Wi-Fi stay awake, as they do during a stream, so the first
 *   request after a quiet spell isn't lost to a sleeping radio. On battery, they sleep between streams as before.
 * - **A new address** (after a router restart, say): [onAddress] is told, so the server announces itself again.
 */
class Reachability(
    private val context: Context,
    private val prefs: Prefs,
    private val locks: ActivityLocks,
    private val onAddress: (String?) -> Unit,
) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val multicast = wifi.createMulticastLock("HomeReel:mdns").apply { setReferenceCounted(false) }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    @Volatile var address: String? = null
        private set

    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Battery-changed comes with every percent: only a change of charger state matters.
            val now = if (intent.action == Intent.ACTION_BATTERY_CHANGED) intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 else charging()
            if (now != pluggedIn) {
                pluggedIn = now
                apply()
            }
        }
    }
    @Volatile private var pluggedIn: Boolean? = null

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = changed(ipv4(link))
        override fun onLost(network: Network) = changed(null)
    }

    fun start() {
        runCatching { multicast.acquire() }
        // Battery changes too: some chargers and phones send it without the plugged/unplugged broadcasts.
        context.registerReceiver(power, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        })
        val wifiOnly = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        runCatching { connectivity.registerNetworkCallback(wifiOnly, network) }
        apply()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(power) }
        runCatching { connectivity.unregisterNetworkCallback(network) }
        if (multicast.isHeld) runCatching { multicast.release() }
        locks.pinned = false
    }

    /** Applies the setting and the charger's state; also called every few minutes, which renews the locks. */
    fun apply() {
        locks.pinned = prefs.stayAwakeWhileCharging && charging()
    }

    private fun charging(): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    @Synchronized private fun changed(ip: String?) {
        if (ip == address) return
        address = ip
        onAddress(ip)
    }

    private fun ipv4(link: LinkProperties): String? =
        link.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
}
