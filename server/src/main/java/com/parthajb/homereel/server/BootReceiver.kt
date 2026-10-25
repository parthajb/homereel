package com.parthajb.homereel.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the server after a reboot or an app update, unless the person stopped it or hasn't picked a folder. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = (context.applicationContext as ServerApp).prefs
        if (prefs.enabled && prefs.treeUri != null) ServerService.start(context)
    }
}
