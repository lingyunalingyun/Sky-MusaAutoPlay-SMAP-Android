package com.smap.android.engine

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

/** 离线生成与桌面端相同参数的 Freeverb 洞穴混响采样。 */
object CaveRenderer {
    private val combTune = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allPassTune = intArrayOf(556, 441, 341, 225)
    private const val spread = 23
    private const val gain = 0.015f
    private const val wet = 0.32f

    fun renderAll(context: Context, instrument: String): List<File> {
        val pool = Executors.newFixedThreadPool(4)
        return try {
            pool.invokeAll((0 until 15).map { key -> Callable { render(context, instrument, key) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun render(context: Context, instrument: String, key: Int): File {
        val output = File(context.cacheDir, "cave-v4/$instrument/$key.wav")
        if (output.isFile) return output
        output.parentFile?.mkdirs()
        val wav = context.assets.open("instruments/$instrument/$key.wav").use { it.readBytes() }
        val pcm = decodePcm16(wav)
        val temporary = File(output.parentFile, "$key.tmp")
        writeWav(temporary, process(pcm.samples, pcm.channels, pcm.sampleRate), pcm.sampleRate)
        check(temporary.renameTo(output)) { "Unable to cache cave sample" }
        return output
    }

    private data class Pcm(val samples: ShortArray, val channels: Int, val sampleRate: Int)

    private fun decodePcm16(bytes: ByteArray): Pcm {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.int == 0x46464952 && buffer.int > 0 && buffer.int == 0x45564157) { "Invalid WAV" }
        var channels = 1
        var sampleRate = 44100
        var bits = 16
        var dataOffset = -1
        var dataSize = 0
        while (buffer.remaining() >= 8) {
            val id = buffer.int
            val size = buffer.int.coerceAtLeast(0)
            val start = buffer.position()
            if (id == 0x20746D66 && size >= 16) {
                require(buffer.short.toInt() == 1) { "Only PCM WAV is supported" }
                channels = buffer.short.toInt()
                sampleRate = buffer.int
                buffer.position(buffer.position() + 6)
                bits = buffer.short.toInt()
            } else if (id == 0x61746164) {
                dataOffset = start
                dataSize = size.coerceAtMost(bytes.size - start)
                break
            }
            buffer.position((start + size + (size and 1)).coerceAtMost(bytes.size))
        }
        require(dataOffset >= 0 && bits == 16 && channels in 1..2) { "Unsupported WAV format" }
        val samples = ShortArray(dataSize / 2)
        ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return Pcm(samples, channels, sampleRate)
    }

    private fun process(source: ShortArray, channels: Int, sampleRate: Int): ShortArray {
        val frames = source.size / channels
        val tailFrames = sampleRate * 3
        val mixed = FloatArray((frames + tailFrames) * 2)
        val scale = sampleRate / 44100f
        val feedback = 0.92f * 0.28f + 0.7f
        val damp = 0.05f * 0.4f
        val combL = combTune.map { Comb((it * scale).toInt().coerceAtLeast(1), feedback, damp) }
        val combR = combTune.map { Comb(((it + spread) * scale).toInt().coerceAtLeast(1), feedback, damp) }
        val passL = allPassTune.map { AllPass((it * scale).toInt().coerceAtLeast(1)) }
        val passR = allPassTune.map { AllPass(((it + spread) * scale).toInt().coerceAtLeast(1)) }
        for (frame in 0 until frames + tailFrames) {
            val dryL = if (frame < frames) source[frame * channels] / 32768f else 0f
            val dryR = if (frame < frames) source[frame * channels + if (channels == 2) 1 else 0] / 32768f else 0f
            val input = (dryL + dryR) * gain
            var outL = 0f
            var outR = 0f
            for (index in combL.indices) {
                outL += combL[index].process(input)
                outR += combR[index].process(input)
            }
            passL.forEach { outL = it.process(outL) }
            passR.forEach { outR = it.process(outR) }
            mixed[frame * 2] = dryL + outL * wet
            mixed[frame * 2 + 1] = dryR + outR * wet
        }
        val peak = mixed.maxOf { abs(it) }.coerceAtLeast(0.001f)
        val volumeGain = minOf(2f, 0.92f / peak)
        return ShortArray(mixed.size) { index ->
            (mixed[index] * volumeGain).coerceIn(-1f, 1f).times(32767).toInt().toShort()
        }
    }

    private fun writeWav(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
        bytes.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(2)
        bytes.putInt(sampleRate).putInt(sampleRate * 4).putShort(4).putShort(16)
        bytes.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        samples.forEach(bytes::putShort)
        file.writeBytes(bytes.array())
    }

    private class Comb(size: Int, private val feedback: Float, private val damp: Float) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var store = 0f
        fun process(input: Float): Float {
            val output = buffer[index]
            store = output * (1 - damp) + store * damp
            buffer[index] = input + store * feedback
            index = (index + 1) % buffer.size
            return output
        }
    }

    private class AllPass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        fun process(input: Float): Float {
            val buffered = buffer[index]
            val output = -input + buffered
            buffer[index] = input + buffered * 0.5f
            index = (index + 1) % buffer.size
            return output
        }
    }
}
