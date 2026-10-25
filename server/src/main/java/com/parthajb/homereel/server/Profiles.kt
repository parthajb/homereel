package com.parthajb.homereel.server

import com.parthajb.homereel.shared.ApiJson
import com.parthajb.homereel.shared.Profile
import com.parthajb.homereel.shared.ProfileEdit
import com.parthajb.homereel.shared.ProfileList
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

/** Who's asking: their profile, what's on their My List, and whether they may see private videos. */
data class Viewer(val profile: Profile, val myList: Set<String>, val seePrivate: Boolean) {
    val pid: String get() = profile.id
    val kids: Boolean get() = profile.kids
}

@Serializable
private data class ProfileData(
    val profiles: List<Profile> = emptyList(),
    /** Each profile's My List: "m:<movie id>" and "s:<show id>" keys, newest first. */
    val myList: Map<String, List<String>> = emptyMap(),
    val pinSalt: String? = null,
    val pinHash: String? = null,
)

/**
 * The household's profiles, their My Lists, and the PIN that guards leaving a kids profile. There's always at
 * least one profile; the first one, "Family", inherits the progress saved before profiles existed.
 */
class Profiles(private val prefs: Prefs) {
    private var data: ProfileData = load()
    private var failures = 0
    private var lockedUntil = 0L

    private fun load(): ProfileData {
        val saved = prefs.profilesJson?.let { runCatching { ApiJson.decodeFromString(ProfileData.serializer(), it) }.getOrNull() }
        return if (saved == null || saved.profiles.isEmpty()) {
            (saved ?: ProfileData()).copy(profiles = listOf(Profile(MAIN, "Family")))
        } else {
            saved
        }
    }

    private fun save() {
        prefs.profilesJson = ApiJson.encodeToString(ProfileData.serializer(), data)
    }

    @Synchronized fun list() = ProfileList(data.profiles, pinSet = data.pinHash != null)

    /** The profile a request names, or the first one when it names none (older TV apps, the admin page). */
    @Synchronized fun resolve(id: String?): Profile = data.profiles.firstOrNull { it.id == id } ?: data.profiles.first()

    @Synchronized fun create(edit: ProfileEdit): Profile {
        if (data.profiles.size >= 8) throw HttpError(400, "Up to 8 profiles")
        val profile = Profile(UUID.randomUUID().toString().take(8), clean(edit.name), edit.avatar.coerceIn(0, 11), edit.kids)
        data = data.copy(profiles = data.profiles + profile)
        save()
        return profile
    }

    @Synchronized fun update(id: String, edit: ProfileEdit): Profile {
        val old = data.profiles.firstOrNull { it.id == id } ?: throw HttpError(404, "No such profile")
        val next = old.copy(name = clean(edit.name), avatar = edit.avatar.coerceIn(0, 11), kids = edit.kids)
        val profiles = data.profiles.map { if (it.id == id) next else it }
        // Someone has to be able to leave a kids profile.
        if (profiles.all { it.kids }) throw HttpError(400, "Keep at least one grown-up profile")
        data = data.copy(profiles = profiles)
        save()
        return next
    }

    @Synchronized fun delete(id: String) {
        if (data.profiles.none { it.id == id }) throw HttpError(404, "No such profile")
        val rest = data.profiles.filter { it.id != id }
        if (rest.isEmpty()) throw HttpError(400, "Keep at least one profile")
        if (rest.all { it.kids }) throw HttpError(400, "Keep at least one grown-up profile")
        data = data.copy(profiles = rest, myList = data.myList - id)
        save()
    }

    @Synchronized fun myList(pid: String): List<String> = data.myList[pid].orEmpty()

    @Synchronized fun setInList(pid: String, key: String, on: Boolean) {
        if (!key.matches(Regex("[ms]:[0-9a-f]{16}"))) throw HttpError(400, "Not a movie or show")
        val list = myList(pid).filter { it != key }
        data = data.copy(myList = data.myList + (pid to if (on) listOf(key) + list else list))
        save()
    }

    /** Sets or clears the PIN (4 to 8 digits). */
    @Synchronized fun setPin(pin: String?) {
        if (pin == null) {
            data = data.copy(pinSalt = null, pinHash = null)
        } else {
            if (!pin.matches(Regex("\\d{4,8}"))) throw HttpError(400, "A PIN is 4 to 8 digits")
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            data = data.copy(pinSalt = salt, pinHash = hash(salt, pin))
        }
        save()
    }

    /** Checks a PIN typed on the TV. Five wrong tries lock checking for a minute. No PIN set lets anyone through. */
    @Synchronized fun checkPin(pin: String) {
        val salt = data.pinSalt ?: return
        val expected = data.pinHash ?: return
        val now = System.currentTimeMillis()
        if (now < lockedUntil) throw HttpError(429, "Too many wrong PINs. Try again in ${(lockedUntil - now + 999) / 1000} s.")
        if (!MessageDigest.isEqual(expected.toByteArray(), hash(salt, pin).toByteArray())) {
            if (++failures >= 5) {
                failures = 0
                lockedUntil = now + 60_000
            }
            throw HttpError(403, "That PIN isn't right")
        }
        failures = 0
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun clean(name: String): String = name.trim().take(24).ifEmpty { throw HttpError(400, "A profile needs a name") }

    companion object {
        /** The first profile's id: it keeps the progress saved before profiles existed. */
        const val MAIN = "main"
    }
}
