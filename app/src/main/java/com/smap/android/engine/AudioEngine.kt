package com.smap.android.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/** 使用桌面版同一套 Piano 采样播放光遇 15 键音色。 */
class AudioEngine(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(15)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .build()

    private val sounds = IntArray(15) { key ->
        context.assets.openFd("instruments/Piano/$key.wav").use { descriptor ->
            soundPool.load(descriptor, 1)
        }
    }

    fun play(key: Int) {
        if (key in sounds.indices) soundPool.play(sounds[key], 1f, 1f, 1, 0, 1f)
    }

    fun stopAll() = soundPool.autoPause()

    fun release() = soundPool.release()
}
