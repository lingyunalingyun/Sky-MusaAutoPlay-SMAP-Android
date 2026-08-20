package com.smap.android.data

import android.content.Context

class LibraryPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun favorites(): Set<String> = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()

    fun toggleFavorite(fileName: String): Set<String> {
        val updated = favorites().toMutableSet().apply {
            if (!add(fileName)) remove(fileName)
        }
        prefs.edit().putStringSet("favorites", updated).apply()
        return updated
    }

    fun playlist(): List<String> = prefs.getString("playlist", "")
        .orEmpty()
        .split('\n')
        .filter { it.isNotBlank() }

    fun savePlaylist(fileNames: List<String>) {
        prefs.edit().putString("playlist", fileNames.joinToString("\n")).apply()
    }

    fun playMode(): Int = prefs.getInt("play_mode", 0).coerceIn(0, 2)

    fun savePlayMode(mode: Int) {
        prefs.edit().putInt("play_mode", mode.coerceIn(0, 2)).apply()
    }

    fun speed(): Float = prefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)

    fun randomSpeed(): Boolean = prefs.getBoolean("random_speed", false)

    fun saveSpeed(speed: Float, random: Boolean) {
        prefs.edit().putFloat("speed", speed).putBoolean("random_speed", random).apply()
    }
}
