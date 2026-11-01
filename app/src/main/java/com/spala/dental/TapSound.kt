package com.spala.dental

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Short "porcelain tap" played when a tooth is tapped on a 3D model (res/raw/tooth_tap.wav, 90 ms).
 * Uses the sonification stream, so it follows the device's touch-sound volume. The full Godot
 * chart synthesises the same sound (chart.gd: tap_sound) and receives the on/off preference.
 */
object TapSound {
    var enabled by mutableStateOf(true)
        private set
    private var pool: SoundPool? = null
    private var soundId = 0
    private var loaded = false

    fun load(context: Context) {
        enabled = context.getSharedPreferences("display", Context.MODE_PRIVATE).getBoolean("tapSounds", true)
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build().also { sounds ->
            sounds.setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
            soundId = sounds.load(context.applicationContext, R.raw.tooth_tap, 1)
        }
    }

    fun set(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putBoolean("tapSounds", value).apply()
    }

    fun play() {
        if (!enabled || !loaded) return
        pool?.play(soundId, 0.8f, 0.8f, 1, 0, 1f)
    }
}
