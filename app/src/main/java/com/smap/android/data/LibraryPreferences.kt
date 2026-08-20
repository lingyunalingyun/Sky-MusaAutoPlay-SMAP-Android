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

    fun instrument(): String = prefs.getString("instrument", "Piano") ?: "Piano"

    fun saveInstrument(name: String) { prefs.edit().putString("instrument", name).apply() }

    fun pitch(instrument: String): Int = prefs.getInt("pitch_$instrument", defaultPitch(instrument)).coerceIn(-24, 24)

    fun savePitch(instrument: String, semitones: Int) {
        prefs.edit().putInt("pitch_$instrument", semitones.coerceIn(-24, 24)).apply()
    }

    fun cave(): Boolean = prefs.getBoolean("cave", false)

    fun saveCave(enabled: Boolean) { prefs.edit().putBoolean("cave", enabled).apply() }

    fun lastSong(): String? = prefs.getString("last_song", null)

    fun lastPosition(): Long = prefs.getLong("last_position", 0L).coerceAtLeast(0L)

    fun savePlayback(fileName: String, positionMs: Long) {
        prefs.edit().putString("last_song", fileName).putLong("last_position", positionMs.coerceAtLeast(0L)).apply()
    }

    private fun defaultPitch(instrument: String): Int = when (instrument) {
        "Cello", "Horn", "Handpan", "GoldHandpan", "Dundun", "APBell1", "APBell2" -> -12
        "Contrabass", "4thAnnivBass", "GoldDundun" -> -24
        else -> 0
    }

    fun removeSong(fileName: String): Set<String> {
        val updatedFavorites = favorites() - fileName
        prefs.edit().putStringSet("favorites", updatedFavorites).apply()
        savePlaylist(playlist() - fileName)
        return updatedFavorites
    }
}
