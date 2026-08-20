package com.smap.android.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.math.pow

/** 使用桌面版同一套 Piano 采样播放光遇 15 键音色。 */
class AudioEngine(context: Context) {
    private val appContext = context.applicationContext
    val instruments: List<String> = context.assets.list("instruments").orEmpty().sorted()
    var currentInstrument: String = "Piano"
        private set
    var pitchSemitones: Int = 0
        private set

    private var soundPool = createPool()
    private var sounds = loadSounds(currentInstrument)

    private fun createPool() = SoundPool.Builder()
        .setMaxStreams(15)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .build()

    private fun loadSounds(instrument: String) = IntArray(15) { key ->
        appContext.assets.openFd("instruments/$instrument/$key.wav").use { descriptor ->
            soundPool.load(descriptor, 1)
        }
    }

    @Synchronized
    fun play(key: Int) {
        val rate = 2.0.pow(pitchSemitones / 12.0).toFloat().coerceIn(0.5f, 2f)
        if (key in sounds.indices) soundPool.play(sounds[key], 1f, 1f, 1, 0, rate)
    }

    @Synchronized
    fun setInstrument(name: String) {
        if (name == currentInstrument || name !in instruments) return
        soundPool.release()
        soundPool = createPool()
        currentInstrument = name
        sounds = loadSounds(name)
    }

    fun setPitch(semitones: Int) { pitchSemitones = semitones.coerceIn(-24, 24) }

    fun stopAll() = soundPool.autoPause()

    @Synchronized
    fun release() = soundPool.release()
}
