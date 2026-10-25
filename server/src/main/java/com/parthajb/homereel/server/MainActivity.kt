package com.parthajb.homereel.server

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** The phone's one screen: pick the folder, see the address and pairing code, and what the TV may not play. */
class MainActivity : Activity() {
    private val app get() = application as ServerApp
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var admin: TextView
    private lateinit var cantPlay: TextView
    private lateinit var startStop: Button
    private lateinit var battery: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        code = findViewById(R.id.code)
        admin = findViewById(R.id.admin)
        cantPlay = findViewById(R.id.cant_play)
        startStop = findViewById(R.id.start_stop)
        battery = findViewById(R.id.battery)

        findViewById<Button>(R.id.choose_folder).setOnClickListener { chooseFolder() }
        findViewById<Button>(R.id.rescan).setOnClickListener { app.library.requestScan() }
        findViewById<Button>(R.id.new_code).setOnClickListener {
            app.prefs.newPairCode()
            refresh()
        }
        findViewById<Button>(R.id.forget).setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.forget_confirm)
                .setPositiveButton(R.string.forget) { _, _ ->
                    app.auth.forgetAll()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        startStop.setOnClickListener {
            if (ServerService.running) {
                app.prefs.enabled = false
                ServerService.stop(this)
            } else {
                app.prefs.enabled = true
                ServerService.start(this)
            }
            handler.postDelayed({ refresh() }, 500)
        }
        battery.setOnClickListener { askToIgnoreBatteryOptimisation() }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (app.prefs.treeUri != null && app.prefs.enabled && !ServerService.running) ServerService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun chooseFolder() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK || uri == null) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.persistedUriPermissions
            .filter { it.uri != uri }
            .forEach { runCatching { contentResolver.releasePersistableUriPermission(it.uri, flags) } }
        contentResolver.takePersistableUriPermission(uri, flags)
        app.library.setFolder(uri)
        app.prefs.enabled = true
        if (!ServerService.running) ServerService.start(this)
        refresh()
    }

    @SuppressLint("BatteryLife")
    private fun askToIgnoreBatteryOptimisation() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun refresh() {
        val library = app.library
        val ip = wifiAddress()
        val port = app.prefs.port
        val running = ServerService.running
        val lines = ArrayList<String>()
        lines += when {
            running && ip != null -> getString(R.string.status_running, "http://$ip:$port")
            running -> getString(R.string.status_no_wifi)
            ServerService.error != null -> ServerService.error!!
            else -> getString(R.string.status_stopped)
        }
        wifiLink()?.let { lines += it }
        val tree = library.tree
        lines += if (tree == null) {
            getString(R.string.no_folder)
        } else {
            getString(R.string.library_line, Saf.folderName(tree), library.count())
        }
        if (library.scanning) lines += getString(R.string.scanning)
        library.scanError?.let { lines += getString(R.string.scan_error, it) }
        val private = library.privateState()
        if (private.count > 0 || private.unlocked) {
            lines += if (private.unlocked) {
                getString(R.string.private_unlocked, android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(private.until!!)))
            } else {
                getString(R.string.private_locked, private.count)
            }
        }
        lines += getString(R.string.paired_devices, app.auth.clientCount())
        status.text = lines.joinToString("\n")

        code.text = app.prefs.pairCode.chunked(3).joinToString(" ")
        admin.text = if (ip != null) getString(R.string.admin_hint, "http://$ip:$port/admin") else ""
        startStop.setText(if (running) R.string.stop else R.string.start)
        val power = getSystemService(PowerManager::class.java)
        battery.visibility = if (power.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE

        val (items, source) = library.cantPlay()
        val titles = library.movies(withPrivate = true).associate { it.id to it.title }
        cantPlay.text = if (items.isEmpty()) {
            getString(R.string.cant_play_none)
        } else {
            val head = getString(if (source == "tv") R.string.cant_play_tv else R.string.cant_play_guess)
            head + "\n\n" + items.joinToString("\n") { "• ${titles[it.id] ?: it.id}: ${it.reason}" }
        }
    }

    private fun wifiAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun wifiLink(): String? {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        if (info.linkSpeed <= 0) return null
        val band = if (info.frequency >= 4900) "5 GHz" else "2.4 GHz"
        return getString(R.string.wifi_line, band, info.linkSpeed)
    }

    private companion object {
        const val REQUEST_FOLDER = 1
    }
}
