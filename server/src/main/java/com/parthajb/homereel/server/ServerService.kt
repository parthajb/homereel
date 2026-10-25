package com.parthajb.homereel.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.parthajb.homereel.shared.Api
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs the HTTP server in the foreground, so Android doesn't stop it, advertises it on the Wi-Fi for the TV to find,
 * and rescans the folder every 15 minutes to pick up files copied in by cable or a file manager.
 */
class ServerService : Service() {
    private var http: HttpServer? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var scheduler: ScheduledExecutorService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as ServerApp
        startInForeground()
        try {
            http = HttpServer(app, app.prefs.port).also { it.start(30_000, false) }
            error = null
            running = true
            advertise(app.prefs.serverName, app.prefs.port)
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't start the server", e)
            error = "Couldn't open port ${app.prefs.port}: ${e.message}"
            stopSelf()
            return
        }
        app.library.requestScan()
        scheduler = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ app.library.requestScan() }, 15, 15, TimeUnit.MINUTES)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        scheduler?.shutdownNow()
        registration?.let { runCatching { nsd().unregisterService(it) } }
        http?.stop()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun nsd() = getSystemService(Context.NSD_SERVICE) as NsdManager

    private fun advertise(name: String, port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = Api.SERVICE_TYPE
            setPort(port)
            setAttribute("api", Api.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "Advertised as ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD registration failed: $code")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        registration = listener
        nsd().registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    companion object {
        private const val TAG = "HomeReel"
        private const val CHANNEL = "server"
        private const val NOTIFICATION_ID = 1

        @Volatile var running = false
            private set

        @Volatile var error: String? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ServerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
