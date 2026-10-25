package com.parthajb.homereel.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.parthajb.homereel.shared.Api

/**
 * Finds HomeReel servers on the Wi-Fi. Android before 14 resolves one service at a time, so finds queue up.
 * Callbacks arrive on the main thread.
 */
class Discovery(context: Context, private val onFound: (Found) -> Unit) {
    data class Found(val name: String, val host: String, val port: Int)

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var started = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceType.contains("_homereel")) main.post { queue += info; next() }
        }

        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            started = false
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() {
        if (started) return
        started = true
        nsd.discoverServices(Api.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { nsd.stopServiceDiscovery(listener) }
        queue.clear()
    }

    private fun next() {
        if (resolving || !started) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress
                    main.post {
                        resolving = false
                        if (host != null && started) onFound(Found(resolved.serviceName, host, resolved.port))
                        next()
                    }
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        next()
                    }
                }
            },
        )
    }
}
