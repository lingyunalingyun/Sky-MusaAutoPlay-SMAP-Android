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
}
