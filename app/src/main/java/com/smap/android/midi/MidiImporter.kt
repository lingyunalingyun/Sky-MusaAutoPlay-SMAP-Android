package com.smap.android.midi

import com.smap.android.model.SkySong
import com.smap.android.model.SongNote

/**
 * MIDI → 光遇 15 键曲谱（移植桌面版 MidiImporter 核心逻辑）：
 * 全轨 note on 提取 → tempo map tick→ms → 自动移调(白键最多+八度居中) → 黑键方向吸附。
 */
object MidiImporter {

    data class TrackInfo(val index: Int, val name: String, val noteCount: Int)
    data class Analysis(val tracks: List<TrackInfo>, val initialBpm: Double)

    // 光遇 15 白键对应 MIDI 音高: C4 D4 E4 F4 G4 A4 B4 C5 D5 E5 F5 G5 A5 B5 C6
    private val SkyMidi = intArrayOf(60, 62, 64, 65, 67, 69, 71, 72, 74, 76, 77, 79, 81, 83, 84)
    private val WhitePc = setOf(0, 2, 4, 5, 7, 9, 11) // C 大调白键音级

    /** 解析 MIDI 字节 → SkySong（名字取文件名去掉扩展名） */
    fun analyze(bytes: ByteArray): Analysis {
        val midi = MidiParser.parse(bytes)
        val tracks = midi.events.filter { it.isNoteOn }
            .groupBy { it.trackIndex }
            .map { (index, events) ->
                TrackInfo(index, midi.trackNames[index].orEmpty().ifBlank { "Track $index" }, events.size)
            }
            .sortedBy { it.index }
        val tempo = midi.events.firstOrNull { it.isTempo }?.data2?.toLong() ?: 500_000L
        return Analysis(tracks, 60_000_000.0 / tempo)
    }

    fun suggestShift(bytes: ByteArray, selectedTracks: Set<Int>): Int {
        val pitches = MidiParser.parse(bytes).events
            .filter { it.isNoteOn && it.trackIndex in selectedTracks }
            .map { it.data1 }
        return suggestSemitone(pitches) + centerOctave(pitches, suggestSemitone(pitches)) * 12
    }

    fun whiteRatio(bytes: ByteArray, selectedTracks: Set<Int>, shift: Int): Double {
        val pitches = MidiParser.parse(bytes).events
            .filter { it.isNoteOn && it.trackIndex in selectedTracks }
            .map { it.data1 }
        if (pitches.isEmpty()) return 0.0
        return pitches.count { ((it + shift) % 12 + 12) % 12 in WhitePc }.toDouble() / pitches.size
    }

    fun convert(
        bytes: ByteArray,
        name: String,
        selectedTracks: Set<Int>? = null,
        autoAlign: Boolean = true,
        octaveShift: Int = 0
    ): SkySong? {
        return try {
            val midi = MidiParser.parse(bytes)
            buildSong(midi, name, selectedTracks, autoAlign, octaveShift)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildSong(
        midi: MidiFileData,
        name: String,
        selectedTracks: Set<Int>?,
        autoAlign: Boolean,
        octaveShift: Int
    ): SkySong {
        val division = midi.division.coerceAtLeast(1)

        // 1. tempo map: tick → (tick, 微秒/四分)，缺省 500000 = 120BPM
        val tempos = midi.events.filter { it.isTempo }
            .map { it.absoluteTick to it.data2.toLong() }
            .sortedBy { it.first }
            .toMutableList()
        if (tempos.isEmpty() || tempos[0].first > 0) tempos.add(0, 0 to 500_000L)

        // 2. 音符: (tick, midiNote)，按 tick 排序
        val notes = midi.events.filter { it.isNoteOn && (selectedTracks == null || it.trackIndex in selectedTracks) }
            .sortedWith(compareBy<MidiEvent> { it.trackIndex }.thenBy { it.absoluteTick })

        if (notes.isEmpty()) {
            return SkySong(name, null, "MIDI导入", 120, 16, 0, false, false, 15, emptyList())
        }

        // 3. tick → ms（tempo map 二分）
        fun tickToMs(tick: Int): Double {
            var microseconds = 0L
            var previousTick = 0
            var mpq = 500_000L
            for ((tempoTick, tempoMpq) in tempos) {
                if (tempoTick >= tick) break
                microseconds += (tempoTick - previousTick).toLong() * mpq / division
                previousTick = tempoTick
                mpq = tempoMpq
            }
            microseconds += (tick - previousTick).toLong() * mpq / division
            return microseconds / 1000.0
        }

        // 4. 自动移调: 白键率最高 + 中位音八度居中
        val rawNotes = notes.map { it.data1 }
        val semitone = if (autoAlign) suggestSemitone(rawNotes) else 0
        val oct = if (autoAlign) centerOctave(rawNotes, semitone) else octaveShift
        val shift = semitone + oct * 12

        // 5. 黑键方向吸附（当前 vs 前一个音高）
        val previousPitch = mutableMapOf<Int, Int>()
        val skyNotes = notes.map { event ->
            val pitch = event.data1
            val previous = previousPitch[event.trackIndex]
            val dir = if (previous == null) 0 else pitch.compareTo(previous)
            previousPitch[event.trackIndex] = pitch
            SongNote(tickToMs(event.absoluteTick).toInt(), toSkyKey(pitch + shift, dir))
        }.sortedBy { it.time }.distinctBy { "${it.key}@${it.time}" }

        val bpm = if (tempos[0].second > 0) (60_000_000.0 / tempos[0].second).toInt() else 120

        return SkySong(
            name = name,
            author = null,
            transcribedBy = "SMAP MIDI Import",
            bpm = bpm.coerceIn(1, 999),
            bitsPerPage = 16,
            pitchLevel = 0,
            isComposed = false,
            isEncrypted = false,
            keyCount = 15,
            songNotes = skyNotes
        )
    }

    /** 0~11 半音移调中选白键率最高者；>6 取负向 */
    private fun suggestSemitone(notes: List<Int>): Int {
        var best = 0
        var bestWhite = -1
        for (s in 0..11) {
            val white = notes.count { ((it + s) % 12 + 12) % 12 in WhitePc }
            if (white > bestWhite) {
                bestWhite = white
                best = s
            }
        }
        return if (best > 6) best - 12 else best
    }

    private fun centerOctave(notes: List<Int>, semitone: Int): Int {
        if (notes.isEmpty()) return 0
        val median = notes.sorted()[notes.size / 2] + semitone
        return kotlin.math.round((72f - median) / 12f).toInt()
    }

    /** MIDI 音高 → 光遇键索引：折叠八度进 [60,84]；白键直用；黑键按方向吸附 */
    private fun toSkyKey(n: Int, dir: Int): Int {
        var v = n
        while (v < 60) v += 12
        while (v > 84) v -= 12
        val idx = SkyMidi.indexOf(v)
        if (idx >= 0) return idx
        // 黑键
        var cand = if (dir > 0) v + 1 else v - 1
        if (cand < 60 || cand > 84 || ((cand % 12 + 12) % 12) !in WhitePc) {
            cand = if (v + 1 <= 84) v + 1 else v - 1
        }
        return SkyMidi.indexOf(cand)
    }
}
