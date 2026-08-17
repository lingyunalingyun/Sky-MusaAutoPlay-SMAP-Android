package com.smap.android.data

import android.content.Context
import com.smap.android.model.SkySong

/** 曲库条目：文件名 + 解析出的曲谱 */
data class LibraryItem(val fileName: String, val song: SkySong)

/** 本地曲库：从 assets/songs 读取曲谱 */
class SongRepository(context: Context) {
    private val assets = context.assets

    fun loadSongs(): List<LibraryItem> {
        val files = assets.list("songs") ?: emptyArray()
        return files
            .filter { it.endsWith(".txt") || it.endsWith(".json") }
            .sorted()
            .mapNotNull { f ->
                runCatching {
                    val text = assets.open("songs/$f").bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val song = SkySong.parse(text)
                    if (song != null) LibraryItem(f, song) else null
                }.getOrNull()
            }
    }
}
