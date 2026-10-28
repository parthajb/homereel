package com.parthajb.homereel.shared

import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Why a search feed couldn't be reached, in plain words. Each message starts in lower case, to follow the feed's
 * name ("Torlock: didn't answer…"). HomeReel says when an internet provider may be blocking a site; it never works
 * around the block.
 */
object FeedErrors {
    enum class Failure { NO_NAME, POINTS_NOWHERE, CONNECT_TIMEOUT, READ_TIMEOUT, REFUSED, NO_ROUTE, CERTIFICATE, OTHER }

    /** What went wrong, from the exception the connection threw. */
    fun classify(e: Throwable): Failure {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is UnknownHostException -> return Failure.NO_NAME
                is SocketTimeoutException ->
                    return if (cause.message.orEmpty().contains("connect", ignoreCase = true)) Failure.CONNECT_TIMEOUT else Failure.READ_TIMEOUT
                is NoRouteToHostException -> return Failure.NO_ROUTE
                is ConnectException ->
                    return if (cause.message.orEmpty().contains("timed out", ignoreCase = true)) Failure.CONNECT_TIMEOUT else Failure.REFUSED
                is SSLException, is CertificateException -> return Failure.CERTIFICATE
            }
            cause = cause.cause
        }
        return Failure.OTHER
    }

    /**
     * A name that resolves only to 0.0.0.0 or this device is how DNS blockers, and some internet providers, block a
     * site. Only a name can be blocked this way: an address typed as numbers is what the person meant.
     */
    fun pointsNowhere(host: String, addresses: List<InetAddress>): Boolean =
        !isLiteral(host) && addresses.isNotEmpty() && addresses.all { it.isAnyLocalAddress || it.isLoopbackAddress }

    private fun isLiteral(host: String) = host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) || ':' in host || host.startsWith('[')

    /**
     * The message for [failure] reaching [host]:[port]. [home] is a feed on the home network (Prowlarr or Jackett on
     * a computer), where the fix is that computer; otherwise it's a site on the internet, which a provider may block.
     */
    fun message(failure: Failure, host: String, port: Int, home: Boolean, detail: String? = null): String {
        val where = if (port > 0) "$host:$port" else host
        val maybeBlocked = "If the site opens on another network or over mobile data, your internet provider may block it."
        return when (failure) {
            Failure.NO_NAME -> if (home) {
                "couldn't find $host on the network. Check the address, or use the computer's IP address instead."
            } else {
                "couldn't find $host: the name didn't resolve. Check the address and that the phone is online. $maybeBlocked"
            }
            Failure.POINTS_NOWHERE ->
                "$host doesn't point to a real site, which is how some internet providers and DNS blockers block one."
            Failure.CONNECT_TIMEOUT -> if (home) {
                "didn't answer at $where. Check the computer is on, on the same Wi-Fi, and that the port is right."
            } else {
                "didn't answer at $where. $maybeBlocked"
            }
            Failure.READ_TIMEOUT -> "took too long to answer"
            Failure.REFUSED -> if (home) {
                "refused the connection at $where. Check the port, and that the program is running on that computer."
            } else {
                "refused the connection at $where. Check the address and port."
            }
            Failure.NO_ROUTE -> "can't be reached from the phone at $where. Check it's on the same network."
            Failure.CERTIFICATE ->
                "its security certificate didn't check out, so HomeReel stopped. The address may be wrong, or something " +
                    "between the phone and the site may be blocking it."
            Failure.OTHER -> "couldn't connect to $where" + (detail?.let { ": $it" } ?: "")
        }
    }

    /** The message for an HTTP status that isn't handled on its own (401, 403, 404 and 429 are). */
    fun forStatus(code: Int): String = when (code) {
        in 500..599 -> "had a problem of its own (it answered $code). Try again later."
        451 -> "won't answer from here (it answered 451, unavailable for legal reasons)"
        else -> "answered $code, which HomeReel doesn't understand"
    }
}
