package com.smap.android.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.smap.android.midi.MidiImporter
import com.smap.android.model.SkySong
import java.io.File
import org.json.JSONArray

/** 曲库条目：文件名 + 解析出的曲谱 */
data class LibraryItem(val fileName: String, val song: SkySong, val coverBytes: ByteArray? = null)

/** 本地曲库：从 assets/songs 读取曲谱，支持 JSON / TXT / MIDI 三种格式 */
class SongRepository(private val context: Context) {
    private val assets = context.assets
    private val importedDir = File(context.filesDir, "songs")
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun loadSongs(): List<LibraryItem> {
        val importedFiles = importedDir.listFiles().orEmpty().filter { it.isFile && supported(it.name) }
        val importedNames = importedFiles.map { it.name }.toSet()
        val hidden = prefs.getStringSet("hidden_songs", emptySet()).orEmpty()
        val bundled = (assets.list("songs") ?: emptyArray())
            .filter { it.endsWith(".txt") || it.endsWith(".json") || it.endsWith(".mid") || it.endsWith(".midi") }
            .filterNot { it in importedNames }
            .filterNot { it in hidden }
            .mapNotNull { f ->
                runCatching {
                    val bytes = assets.open("songs/$f").readBytes()
                    val song = parse(f, bytes)
                    if (song != null) LibraryItem(f, song, extractCover(f, bytes)) else null
                }.getOrNull()
            }
        val imported = importedFiles.filterNot { it.name in hidden }
            .mapNotNull { file ->
                runCatching {
                    val song = parse(file.name, file.readBytes())
                    if (song != null) LibraryItem(file.name, song, extractCover(file.name, file.readBytes())) else null
                }.getOrNull()
            }
        return (bundled + imported).sortedBy { it.song.name.lowercase() }
    }

    fun importSong(uri: Uri): Result<LibraryItem> = runCatching {
        val displayName = queryName(uri).ifBlank { "导入曲谱.txt" }
        require(supported(displayName)) { "仅支持 JSON、TXT、MID、MIDI 文件" }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取文件")
        val song = parse(displayName, bytes) ?: error("曲谱格式无效或没有可用音符")
        importedDir.mkdirs()
        val target = File(importedDir, displayName)
        target.writeBytes(bytes)
        unhide(target.name)
        LibraryItem(target.name, song, extractCover(target.name, bytes))
    }

    fun importMidi(
        bytes: ByteArray,
        name: String,
        selectedTracks: Set<Int>,
        autoAlign: Boolean,
        octaveShift: Int
    ): Result<LibraryItem> = runCatching {
        val song = MidiImporter.convert(bytes, name, selectedTracks, autoAlign, octaveShift)
            ?: error("MIDI 文件中没有可用音符")
        require(song.songNotes.isNotEmpty()) { "MIDI 文件中没有可用音符" }
        importedDir.mkdirs()
        val target = uniqueFile("$name.json")
        target.writeText(song.toJson(), Charsets.UTF_8)
        unhide(target.name)
        LibraryItem(target.name, song)
    }

    fun deleteSong(fileName: String): Result<Unit> = runCatching {
        val imported = File(importedDir, fileName)
        if (imported.exists() && !imported.delete()) error("无法删除曲谱文件")
        val hidden = prefs.getStringSet("hidden_songs", emptySet()).orEmpty().toMutableSet()
        hidden.add(fileName)
        prefs.edit().putStringSet("hidden_songs", hidden).apply()
    }

    private fun unhide(fileName: String) {
        val hidden = prefs.getStringSet("hidden_songs", emptySet()).orEmpty().toMutableSet()
        if (hidden.remove(fileName)) prefs.edit().putStringSet("hidden_songs", hidden).apply()
    }

    private fun parse(fileName: String, bytes: ByteArray): SkySong? = when {
        fileName.endsWith(".mid", true) || fileName.endsWith(".midi", true) ->
            MidiImporter.convert(bytes, fileName.substringBeforeLast('.').ifBlank { fileName })
        else -> SkySong.parse(decodeText(bytes))
    }

    private fun supported(name: String): Boolean =
        name.endsWith(".txt", true) || name.endsWith(".json", true) ||
            name.endsWith(".mid", true) || name.endsWith(".midi", true)

    private fun extractCover(fileName: String, bytes: ByteArray): ByteArray? {
        if (fileName.endsWith(".mid", true) || fileName.endsWith(".midi", true)) return null
        return runCatching {
            val text = decodeText(bytes)
            if (!text.contains("\"cover\"")) return null
            val array = JSONArray(text)
            var encoded = array.optJSONObject(0)?.optString("cover").orEmpty()
            if (encoded.startsWith("data:")) encoded = encoded.substringAfter(',')
            encoded.takeIf { it.isNotBlank() }?.let { Base64.decode(it, Base64.DEFAULT) }
        }.getOrNull()
    }

    private fun queryName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0) ?: ""
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: ""
    }

    private fun uniqueFile(originalName: String): File {
        val base = originalName.substringBeforeLast('.', originalName)
        val ext = originalName.substringAfterLast('.', "")
        val bundledNames = assets.list("songs").orEmpty().toSet()
        var candidate = File(importedDir, originalName)
        var index = 2
        while (candidate.exists() || candidate.name in bundledNames) {
            candidate = File(importedDir, if (ext.isEmpty()) "$base ($index)" else "$base ($index).$ext")
            index++
        }
        return candidate
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
