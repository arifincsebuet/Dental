package com.spala.dental

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Short "porcelain tap" played when a tooth is selected on a 3D model (res/raw/tooth_tap.wav, 90 ms),
 * and a softer, lower gliding "tock" when it is deselected (res/raw/tooth_untap.wav, 80 ms).
 * Uses the sonification stream, so it follows the device's touch-sound volume. The full Godot
 * chart synthesises the same sound (chart.gd: tap_sound) and receives the on/off preference.
 */
object TapSound {
    var enabled by mutableStateOf(true)
        private set
    private var pool: SoundPool? = null
    private var soundId = 0
    private var deselectId = 0
    private val loaded = HashSet<Int>()

    fun load(context: Context) {
        enabled = context.getSharedPreferences("display", Context.MODE_PRIVATE).getBoolean("tapSounds", true)
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build().also { sounds ->
            sounds.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
            soundId = sounds.load(context.applicationContext, R.raw.tooth_tap, 1)
            deselectId = sounds.load(context.applicationContext, R.raw.tooth_untap, 1)
        }
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putBoolean("tapSounds", value).apply()
    }

    /** Tooth selected. */
    fun play() {
        if (!enabled || soundId !in loaded) return
        pool?.play(soundId, 0.8f, 0.8f, 1, 0, 1f)
    }

    /** Tooth deselected. */
    fun playDeselect() {
        if (!enabled || deselectId !in loaded) return
        pool?.play(deselectId, 0.7f, 0.7f, 1, 0, 1f)
    }
}
