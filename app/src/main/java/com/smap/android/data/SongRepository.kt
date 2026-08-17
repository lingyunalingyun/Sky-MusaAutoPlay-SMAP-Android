package com.smap.android.data

import android.content.Context
import com.smap.android.midi.MidiImporter
import com.smap.android.model.SkySong

/** 曲库条目：文件名 + 解析出的曲谱 */
data class LibraryItem(val fileName: String, val song: SkySong)

/** 本地曲库：从 assets/songs 读取曲谱，支持 JSON / TXT / MIDI 三种格式 */
class SongRepository(context: Context) {
    private val assets = context.assets

    fun loadSongs(): List<LibraryItem> {
        val files = assets.list("songs") ?: emptyArray()
        return files
            .filter { it.endsWith(".txt") || it.endsWith(".json") || it.endsWith(".mid") || it.endsWith(".midi") }
            .sorted()
            .mapNotNull { f ->
                runCatching {
                    val bytes = assets.open("songs/$f").readBytes()
                    val song = when {
                        f.endsWith(".mid") || f.endsWith(".midi") -> {
                            val name = f.substringBeforeLast('.').ifBlank { f }
                            MidiImporter.convert(bytes, name)
                        }
                        else -> SkySong.parse(decodeText(bytes))
                    }
                    if (song != null) LibraryItem(f, song) else null
                }.getOrNull()
            }
    }

    /** 自动识别 BOM 解码（桌面版曲谱存在 UTF-16 编码） */
    private fun decodeText(bytes: ByteArray): String {
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
                bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
            else -> bytes.toString(Charsets.UTF_8)
        }
    }
}
