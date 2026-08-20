package com.smap.android.model

import org.json.JSONArray
import org.json.JSONObject

/** 单个音符：time 为毫秒，key 为光遇琴键索引 0~14 */
data class SongNote(val time: Int, val key: Int) {
    companion object {
        fun fromJson(o: JSONObject): SongNote {
            val keyStr = o.optString("key", "1Key0")
            val key = keyStr.removePrefix("1Key").toIntOrNull() ?: 0
            return SongNote(o.optInt("time", 0), key)
        }
    }
}

/** Sky 曲谱（SMAP 标准格式，JSON 数组首元素） */
data class SkySong(
    val name: String,
    val author: String?,
    val transcribedBy: String?,
    val bpm: Int,
    val bitsPerPage: Int,
    val pitchLevel: Int,
    val isComposed: Boolean,
    val isEncrypted: Boolean,
    val keyCount: Int,
    val songNotes: List<SongNote>
) {
    val durationMs: Long
        get() = songNotes.maxOfOrNull { it.time }?.toLong() ?: 0L

    fun toJson(): String {
        val notes = JSONArray()
        songNotes.forEach { notes.put(JSONObject().put("time", it.time).put("key", "1Key${it.key}")) }
        val song = JSONObject()
            .put("name", name)
            .put("author", author.orEmpty())
            .put("transcribedBy", transcribedBy.orEmpty())
            .put("isComposed", isComposed)
            .put("bpm", bpm)
            .put("bitsPerPage", bitsPerPage)
            .put("pitchLevel", pitchLevel)
            .put("isEncrypted", isEncrypted)
            .put("keyCount", keyCount)
            .put("songNotes", notes)
        return JSONArray().put(song).toString()
    }

    companion object {
        /** 解析 Sky 曲谱文本（JSON 数组格式），失败返回 null */
        fun parse(text: String): SkySong? {
            return try {
                val arr = JSONArray(text)
                if (arr.length() == 0) null else fromJson(arr.getJSONObject(0))
            } catch (e: Exception) {
                null
            }
        }

        fun fromJson(o: JSONObject): SkySong {
            val notesArr = o.optJSONArray("songNotes") ?: JSONArray()
            val notes = buildList {
                for (i in 0 until notesArr.length()) {
                    add(SongNote.fromJson(notesArr.getJSONObject(i)))
                }
            }
            return SkySong(
                name = o.optString("name", "未命名"),
                author = o.optString("author", "").ifBlank { null },
                transcribedBy = o.optString("transcribedBy", "").ifBlank { null },
                bpm = o.optInt("bpm", 120),
                bitsPerPage = o.optInt("bitsPerPage", 16),
                pitchLevel = o.optInt("pitchLevel", 0),
                isComposed = o.optBoolean("isComposed", false),
                isEncrypted = o.optBoolean("isEncrypted", false),
                keyCount = o.optInt("keyCount", 15),
                songNotes = notes
            )
        }
    }
}
