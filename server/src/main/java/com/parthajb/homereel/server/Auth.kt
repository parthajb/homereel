package com.parthajb.homereel.server

import android.util.Base64
import com.parthajb.homereel.shared.Api
import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.PairRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class Client(val role: String, val name: String, val created: Long)

sealed interface PairResult {
    data class Ok(val token: String, val role: String) : PairResult
    data object WrongCode : PairResult
    data class Locked(val seconds: Long) : PairResult
}

/** Pairing codes and bearer tokens. A TV pairs as a viewer; a browser pairs as an admin with the same code. */
class Auth(private val prefs: Prefs) {
    private val serializer = MapSerializer(String.serializer(), Client.serializer())
    private val clients: MutableMap<String, Client> =
        prefs.clientsJson?.let { runCatching { ApiJson.decodeFromString(serializer, it) }.getOrNull() }
            ?.toMutableMap() ?: mutableMapOf()
    private var failures = 0
    private var lockedUntil = 0L

    @Synchronized
    fun pair(request: PairRequest): PairResult {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return PairResult.Locked((lockedUntil - now + 999) / 1000)
        val expected = prefs.pairCode.toByteArray()
        if (!MessageDigest.isEqual(expected, request.code.trim().toByteArray())) {
            failures++
            if (failures >= 5) {
                failures = 0
                lockedUntil = now + 60_000
            }
            return PairResult.WrongCode
        }
        failures = 0
        val role = if (request.role == Api.ROLE_ADMIN) Api.ROLE_ADMIN else Api.ROLE_VIEWER
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        clients[token] = Client(role, request.client.take(80), now)
        save()
        return PairResult.Ok(token, role)
    }

    @Synchronized
    fun roleOf(token: String?): String? = token?.let { clients[it]?.role }

    @Synchronized
    fun clientCount(): Int = clients.size

    @Synchronized
    fun forgetAll() {
        clients.clear()
        save()
    }

    private fun save() {
        prefs.clientsJson = ApiJson.encodeToString(serializer, clients)
    }
}
