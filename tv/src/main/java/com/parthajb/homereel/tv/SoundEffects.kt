package com.parthajb.homereel.tv

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log

/**
 * Night mode and the volume boost, as Android audio effects on the player's sound. Night mode squeezes the loud and
 * the quiet together, so explosions don't wake the house and voices stay clear (Android 9 and later). The boost
 * raises everything. Neither works when the TV sends Dolby sound untouched to an amplifier.
 */
class SoundEffects {
    private var session = 0
    private var night: DynamicsProcessing? = null
    private var boost: LoudnessEnhancer? = null
    private var nightOn = false
    private var boostDb = 0

    /** Night mode needs Android 9's DynamicsProcessing. */
    val nightAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** The player's audio session changed (a new film, or new audio): the effects move to it. */
    fun attach(audioSession: Int) {
        if (audioSession == session || audioSession == 0) return
        release()
        session = audioSession
        apply()
    }

    fun setNight(on: Boolean) {
        nightOn = on
        apply()
    }

    fun setBoost(db: Int) {
        boostDb = db
        apply()
    }

    private fun apply() {
        if (session == 0) return
        runCatching {
            if (nightOn && nightAvailable) {
                (night ?: nightMode(session).also { night = it }).enabled = true
            } else {
                night?.enabled = false
            }
        }.onFailure { Log.w(TAG, "Night mode isn't available on this TV", it) }
        runCatching {
            if (boostDb > 0) {
                val b = boost ?: LoudnessEnhancer(session).also { boost = it }
                b.setTargetGain(boostDb * 100) // millibels
                b.enabled = true
            } else {
                boost?.enabled = false
            }
        }.onFailure { Log.w(TAG, "The volume boost isn't available on this TV", it) }
    }

    fun release() {
        runCatching { night?.release() }
        runCatching { boost?.release() }
        night = null
        boost = null
        session = 0
    }

    /**
     * One full-range compressor (4:1 above −30 dB, with quiet parts lifted by 10 dB) and a limiter, on up to eight
     * channels so 5.1 and 7.1 sound is covered too.
     */
    private fun nightMode(session: Int): DynamicsProcessing {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw UnsupportedOperationException()
        val channels = 8
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, channels,
            false, 0, true, 1, false, 0, true,
        )
            .setMbcAllChannelsTo(
                DynamicsProcessing.Mbc(true, true, 1).apply {
                    setBand(0, DynamicsProcessing.MbcBand(true, 20_000f, 10f, 250f, 4f, -30f, 6f, -90f, 1f, 0f, 10f))
                },
            )
            .setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -3f, 0f))
            .build()
        return DynamicsProcessing(0, session, config)
    }

    private companion object {
        const val TAG = "HomeReel"
    }
}
