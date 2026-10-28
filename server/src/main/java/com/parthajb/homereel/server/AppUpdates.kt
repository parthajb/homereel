package com.parthajb.homereel.server

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.AppUpdate
import com.parthajb.homereel.shared.UpdateStatus
import com.parthajb.homereel.shared.Updates
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * App updates without ADB: the admin page uploads a newer TV or phone APK, the phone keeps it, and each app offers
 * to install it. Only an APK of the right app, signed with the same key as this one, is kept; Android itself also
 * refuses an update signed with a different key.
 */
class AppUpdates(private val context: Context, private val events: EventLog) {
    private val dir = File(context.filesDir, "updates").apply { mkdirs() }

    fun file(target: String) = File(dir, "${check(target)}.apk")

    fun info(target: String): AppUpdate? {
        val meta = File(dir, "${check(target)}.json")
        if (!meta.exists() || !file(target).exists()) return null
        return runCatching { ApiJson.decodeFromString(AppUpdate.serializer(), meta.readText()) }.getOrNull()
    }

    fun status(): UpdateStatus {
        val own = ownInfo()
        return UpdateStatus(
            phoneVersionCode = versionCode(own),
            phoneVersionName = own.versionName ?: "?",
            tv = info(Updates.TV),
            // An uploaded phone update that's now installed isn't news.
            phone = info(Updates.PHONE)?.takeIf { Updates.isNewer(it, versionCode(own)) },
        )
    }

    /** A phone update newer than the running app, if one was uploaded. */
    fun pendingPhoneUpdate(): AppUpdate? = info(Updates.PHONE)?.takeIf { Updates.isNewer(it, versionCode(ownInfo())) }

    @Synchronized fun store(target: String, input: InputStream, length: Long): AppUpdate {
        check(target)
        if (length <= 0) throw HttpError(411, "Content-Length is missing")
        if (length > MAX_BYTES) throw HttpError(413, "An app is at most 200 MB")
        val tmp = File(dir, "$target.apk.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    total += n
                }
            }
            if (total != length) throw HttpError(400, "The upload was cut short. Try again.")
            val apk = archiveInfo(tmp) ?: throw HttpError(400, "That isn't an Android app (.apk)")
            val expected = if (target == Updates.TV) Updates.TV_PACKAGE else context.packageName
            if (apk.packageName != expected) {
                val which = if (target == Updates.TV) "TV app" else "phone app"
                throw HttpError(400, "That's ${apk.packageName}, not the HomeReel $which")
            }
            if (signers(apk).isEmpty() || signers(apk) != signers(ownInfo())) {
                throw HttpError(400, "That app isn't signed with the same key as this HomeReel, so it can't update it")
            }
            if (target == Updates.PHONE) {
                val abis = ZipFile(tmp).use { zip -> Updates.abisIn(zip.entries().asSequence().map { it.name }) }
                Updates.wrongAbi(abis, Build.SUPPORTED_ABIS.toList())?.let { throw HttpError(400, it) }
            }
            if (target == Updates.PHONE && versionCode(apk) <= versionCode(ownInfo())) {
                throw HttpError(400, "That's version ${apk.versionName}; the phone already runs ${ownInfo().versionName}")
            }
            val update = AppUpdate(
                target, versionCode(apk), apk.versionName ?: "?", total,
                digest.digest().joinToString("") { "%02x".format(it) }, System.currentTimeMillis(),
            )
            if (!tmp.renameTo(file(target))) throw HttpError(500, "Couldn't save the app")
            File(dir, "$target.json").writeText(ApiJson.encodeToString(AppUpdate.serializer(), update))
            if (target == Updates.TV) {
                events.info("TV app ${update.versionName} uploaded. TVs offer it the next time HomeReel opens.")
            } else {
                events.info("Phone app ${update.versionName} uploaded. Install it on the phone.")
                notifyPhoneUpdate(update)
            }
            return update
        } finally {
            tmp.delete()
        }
    }

    @Synchronized fun delete(target: String) {
        file(target).delete()
        File(dir, "${check(target)}.json").delete()
        if (target == Updates.PHONE) context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** "Update ready: tap to install", since installing needs someone at the phone to confirm. */
    private fun notifyPhoneUpdate(update: AppUpdate) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, ServerService.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_ready_title, update.versionName))
            .setContentText(context.getString(R.string.update_ready_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification) }
    }

    private fun check(target: String): String {
        if (target != Updates.TV && target != Updates.PHONE) throw HttpError(404, "No such app")
        return target
    }

    private fun ownInfo(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlag())

    @Suppress("DEPRECATION")
    private fun archiveInfo(apk: File): PackageInfo? = context.packageManager.getPackageArchiveInfo(apk.path, signingFlag())

    @Suppress("DEPRECATION")
    private fun signingFlag(): Int =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val sha = MessageDigest.getInstance("SHA-256")
        return sigs.orEmpty().map { s -> sha.digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private companion object {
        const val MAX_BYTES = 200L * 1024 * 1024
        const val NOTIFICATION_ID = 7
    }
}
