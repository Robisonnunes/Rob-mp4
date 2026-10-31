package com.robison.leitormp4

import android.content.Context

/** Faixas e pastas favoritas (guardadas pelo caminho completo). */
class Favorites(context: Context) {

    private val prefs = context.getSharedPreferences("favoritos", Context.MODE_PRIVATE)

    private var tracks: MutableSet<String> = prefs.getStringSet(KEY_TRACKS, emptySet())!!.toMutableSet()
    private var folders: MutableSet<String> = prefs.getStringSet(KEY_FOLDERS, emptySet())!!.toMutableSet()

    fun isTrack(id: String) = id in tracks
    fun isFolder(path: String) = path in folders

    fun trackIds(): Set<String> = tracks
    fun folderPaths(): Set<String> = folders

    /** Liga/desliga. Devolve true se ficou favorito. */
    fun toggleTrack(id: String): Boolean {
        val now = if (id in tracks) { tracks.remove(id); false } else { tracks.add(id); true }
        save()
        return now
    }

    fun toggleFolder(path: String): Boolean {
        val now = if (path in folders) { folders.remove(path); false } else { folders.add(path); true }
        save()
        return now
    }

    /** O ficheiro mudou de nome. */
    fun renamePath(oldPath: String, newPath: String) {
        if (tracks.remove(oldPath)) {
            tracks.add(newPath)
            save()
        }
    }

    private fun save() {
        prefs.edit()
            .putStringSet(KEY_TRACKS, HashSet(tracks))
            .putStringSet(KEY_FOLDERS, HashSet(folders))
            .apply()
    }

    private companion object {
        const val KEY_TRACKS = "faixas"
        const val KEY_FOLDERS = "pastas"
    }
}
