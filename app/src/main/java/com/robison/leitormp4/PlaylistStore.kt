package com.robison.leitormp4

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(
    val id: String,
    var name: String,
    /** Endereços (URI) das faixas, pela ordem da playlist */
    val items: MutableList<String>
)

/** Guarda as playlists dentro do app, num ficheiro JSON privado. */
class PlaylistStore(context: Context) {

    private val file = File(context.filesDir, "playlists.json")
    val playlists = mutableListOf<Playlist>()

    init {
        load()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val items = o.getJSONArray("items")
                playlists += Playlist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    items = MutableList(items.length()) { items.getString(it) }
                )
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (p in playlists) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("items", JSONArray(p.items))
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    /** Converte endereços antigos (content://...) para o caminho do ficheiro. */
    fun migrate(aliases: Map<String, String>) {
        var changed = false
        for (p in playlists) {
            for (i in p.items.indices) {
                val new = aliases[p.items[i]] ?: continue
                p.items[i] = new
                changed = true
            }
            val dedup = p.items.distinct()
            if (dedup.size != p.items.size) {
                p.items.clear(); p.items.addAll(dedup); changed = true
            }
        }
        if (changed) save()
    }

    fun get(id: String?): Playlist? = playlists.firstOrNull { it.id == id }

    fun sorted(): List<Playlist> = playlists.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    fun create(name: String): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim(), mutableListOf())
        playlists += p
        save()
        return p
    }

    fun rename(id: String, name: String) {
        get(id)?.name = name.trim()
        save()
    }

    fun delete(id: String) {
        playlists.removeAll { it.id == id }
        save()
    }

    /** Acrescenta faixas no fim (sem duplicar). Devolve quantas foram acrescentadas. */
    fun add(id: String, trackIds: List<String>): Int {
        val p = get(id) ?: return 0
        var added = 0
        for (t in trackIds) {
            if (t !in p.items) {
                p.items += t
                added++
            }
        }
        save()
        return added
    }

    fun remove(id: String, trackId: String) {
        get(id)?.items?.remove(trackId)
        save()
    }

    fun move(id: String, trackId: String, delta: Int) {
        val items = get(id)?.items ?: return
        val from = items.indexOf(trackId)
        val to = from + delta
        if (from < 0 || to !in items.indices) return
        items.removeAt(from)
        items.add(to, trackId)
        save()
    }
}
