package com.parthajb.homereel.server

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.io.File

/**
 * Installs an update of this app from a file, through Android's own installer: the person confirms on screen, and
 * Android checks the update is signed with the same key.
 */
object ApkInstaller {
    /**
     * Android asks once whether this app may install apps. Returns false (after opening that setting, when the
     * device has it) if it may not yet.
     */
    fun allowedOrAsk(context: Context): Boolean {
        if (context.packageManager.canRequestPackageInstalls()) return true
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(intent)
            Toast.makeText(context, R.string.update_allow, Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.update_allow_manual, Toast.LENGTH_LONG).show()
        }
        return false
    }

    /** Hands [apk] to the installer. Call off the main thread: it copies the file. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val status = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
            session.commit(status.intentSender)
        }
    }
}

/** The installer's answer: show its confirmation screen, or say why it failed. Success replaces this app. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("HomeReel", "Update failed: $status $message")
                Toast.makeText(context, context.getString(R.string.update_failed, message ?: status.toString()), Toast.LENGTH_LONG).show()
            }
        }
    }
}
