package com.smap.android.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.math.pow

/** 使用桌面版同一套 Piano 采样播放光遇 15 键音色。 */
class AudioEngine(context: Context) {
    private val appContext = context.applicationContext
    private val desktopOrder = listOf(
        "Piano", "Harp", "Guitar", "Flute", "Ukulele", "Winter Piano", "Xylophone", "Electric Guitar",
        "Bassoon", "Orff", "Kalimba", "Ocarina", "Cello", "Violin", "Saxophone", "Pipa", "Quena",
        "Bugle", "Glock", "LightGuitar", "GoldPiano", "Horn", "Handpan", "GoldHandpan", "Dundun",
        "APBell1", "APBell2", "Harmonica", "AP18Ocarina", "AP29Piccolo", "GoldBugle", "APPiano",
        "4thAnnivArp", "4thAnnivLead", "Contrabass", "4thAnnivBass", "GoldDundun"
    )
    private val available = context.assets.list("instruments").orEmpty().toSet()
    val instruments: List<String> = desktopOrder.filter { it in available }
    var currentInstrument: String = "Piano"
        private set
    var pitchSemitones: Int = 0
        private set
    private var caveEnabled = false

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

    private fun loadSounds(instrument: String): IntArray {
        val caveFiles = if (caveEnabled) CaveRenderer.renderAll(appContext, instrument) else null
        return IntArray(15) { key ->
            if (caveFiles != null) {
                soundPool.load(caveFiles[key].absolutePath, 1)
            } else {
                appContext.assets.openFd("instruments/$instrument/$key.wav").use { descriptor ->
                    soundPool.load(descriptor, 1)
                }
            }
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

    @Synchronized
    fun setCave(enabled: Boolean) {
        if (enabled == caveEnabled) return
        soundPool.release()
        soundPool = createPool()
        caveEnabled = enabled
        sounds = loadSounds(currentInstrument)
    }

    fun stopAll() = soundPool.autoPause()

    @Synchronized
    fun release() {
        soundPool.release()
    }
}
