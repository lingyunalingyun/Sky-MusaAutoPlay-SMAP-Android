package com.smap.android.midi

/** SMF（标准 MIDI 文件）解析出的独立事件，absoluteTick 为绝对 tick */
data class MidiEvent(
    val absoluteTick: Int,
    val trackIndex: Int,
    val status: Int,   // 0x8n~0xEn 通道消息 / 0xFF meta / 0xF0 sysex
    val data1: Int,
    val data2: Int
) {
    val isNoteOn: Boolean get() = status in 0x90..0x9F && data2 > 0
    val isTempo: Boolean get() = status == 0xFF && data1 == 0x51
}

/** 解析结果 */
data class MidiFileData(
    val format: Int,
    val division: Int,
    val events: List<MidiEvent>,
    val trackNames: Map<Int, String>
)

/**
 * 极简 SMF 解析器（零依赖）。
 * 支持 format 0/1/2，running status，变长 delta time，meta 事件，sysex。
 */
object MidiParser {

    fun parse(bytes: ByteArray): MidiFileData {
        var pos = 0
        fun need(n: Int) {
            if (pos + n > bytes.size) throw IllegalArgumentException("MIDI 文件截断 at $pos")
        }
        fun u8(): Int {
            need(1); return bytes[pos++].toInt() and 0xFF
        }
        fun u16(): Int {
            need(2)
            val v = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            pos += 2
            return v
        }
        fun u32(): Int {
            need(4)
            val v = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }
        fun readVarLen(): Int {
            var value = 0
            var b: Int
            do {
                b = u8()
                value = (value shl 7) or (b and 0x7F)
            } while (b and 0x80 != 0)
            return value
        }

        // Header chunk
        if (u32() != 0x4D546864) throw IllegalArgumentException("不是 MIDI 文件（缺 MThd）") // MThd
        val headerLen = u32()
        if (headerLen < 6) throw IllegalArgumentException("MThd 长度异常")
        val format = u16()
        val ntrks = u16()
        val division = u16()
        if (division and 0x8000 != 0) {
            // SMPTE 格式 division，不支持（极罕见）
            throw IllegalArgumentException("SMPTE division 暂不支持")
        }
        pos += headerLen - 6

        val events = mutableListOf<MidiEvent>()
        val trackNames = mutableMapOf<Int, String>()

        for (track in 0 until ntrks) {
            if (u32() != 0x4D54726B) throw IllegalArgumentException("缺 MTrk 块") // MTrk
            val trackLen = u32()
            val trackEnd = pos + trackLen
            var absTick = 0
            var lastStatus = 0

            while (pos < trackEnd) {
                absTick += readVarLen()
                var status = u8()
                if (status and 0x80 == 0) {
                    // running status：该字节是 data1
                    if (lastStatus == 0) throw IllegalArgumentException("running status 无前值")
                    status = lastStatus
                    pos-- // 回退一个字节，统一按 data 读取
                } else {
                    lastStatus = status
                }

                when {
                    status in 0x80..0xEF -> {
                        val d1 = u8()
                        val d2 = if (status in 0xC0..0xDF) 0 else u8()
                        events.add(MidiEvent(absTick, track, status, d1, d2))
                    }
                    status == 0xF0 || status == 0xF7 -> { // sysex
                        val len = readVarLen()
                        pos += len
                    }
                    status == 0xFF -> { // meta
                        val type = u8()
                        val len = readVarLen()
                        if (type == 0x51 && len == 3) { // tempo
                            val t = (u8() shl 16) or (u8() shl 8) or u8()
                            events.add(MidiEvent(absTick, track, 0xFF, 0x51, t))
                        } else if (type == 0x03) {
                            val nameBytes = bytes.copyOfRange(pos, (pos + len).coerceAtMost(bytes.size))
                            trackNames[track] = nameBytes.toString(Charsets.UTF_8).trim()
                            pos += len
                        } else {
                            pos += len
                        }
                    }
                    else -> { /* 系统实时消息 0xF8~0xFE，单字节无数据 */ }
                }
            }
            // 防止解析越界
            if (pos > trackEnd) pos = trackEnd
        }

        return MidiFileData(format, division, events, trackNames)
    }
}
