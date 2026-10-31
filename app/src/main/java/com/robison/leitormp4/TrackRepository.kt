package com.robison.leitormp4

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Track(
    val uri: Uri,
    val title: String,
    val folder: String,
    val durationMs: Long,
    /** Pasta completa onde o ficheiro está, ex.: /storage/emulated/0/Music/Viagem */
    val dir: String
) {
    val id: String get() = uri.toString()
}

/** Procura ficheiros MP4 (e M4A) na memória do celular através do MediaStore do Android. */
object TrackRepository {

    @Suppress("DEPRECATION")
    private val DATA = MediaStore.MediaColumns.DATA

    fun loadAll(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()

        // Ficheiros .mp4 com vídeo ficam catalogados como "vídeo" pelo Android
        tracks += query(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            selection = "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            args = arrayOf("video/mp4", "%.mp4")
        )

        // Ficheiros .mp4 / .m4a só com áudio ficam catalogados como "áudio"
        tracks += query(
            context,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            selection = "${MediaStore.MediaColumns.MIME_TYPE} = ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            args = arrayOf("audio/mp4", "%.mp4")
        )

        return tracks
            .distinctBy { it.uri }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    private fun query(
        context: Context,
        collection: Uri,
        selection: String,
        args: Array<String>
    ): List<Track> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DURATION,
            DATA
        )
        val result = mutableListOf<Track>()
        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val iDur = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
                val iData = c.getColumnIndex(DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: continue
                    val path = if (iData >= 0) c.getString(iData) else null
                    val dir = path?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
                        ?: "/Outros"
                    result += Track(
                        uri = ContentUris.withAppendedId(collection, id),
                        title = name.substringBeforeLast('.'),
                        folder = dir.substringAfterLast('/'),
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                        dir = dir
                    )
                }
            }
        } catch (_: SecurityException) {
            // Sem permissão para esta categoria — ignora
        } catch (_: IllegalArgumentException) {
            // Coluna inexistente nalguma versão do Android — ignora
        }
        return result
    }
}

/** Ajuda a navegar na árvore de pastas a partir das faixas encontradas. */
object FolderTree {

    /** Raiz de cada armazenamento: memória interna (/storage/emulated/0) ou cartão SD (/storage/XXXX-XXXX). */
    fun volumeOf(dir: String): String {
        val parts = dir.trimStart('/').split('/')
        return when {
            parts.size >= 3 && parts[0] == "storage" && parts[1] == "emulated" -> "/storage/emulated/${parts[2]}"
            parts.size >= 2 && parts[0] == "storage" -> "/storage/${parts[1]}"
            else -> "/" + parts.first()
        }
    }

    fun displayName(path: String): String = when {
        path == "/storage/emulated/0" -> "Memória interna"
        path.startsWith("/storage/emulated/") && path.count { it == '/' } == 3 -> "Memória interna (outro utilizador)"
        Regex("^/storage/[^/]+$").matches(path) -> "Cartão SD"
        else -> path.substringAfterLast('/')
    }

    fun isInside(dir: String, folder: String) = dir == folder || dir.startsWith("$folder/")

    data class Child(val path: String, val name: String, val count: Int)

    /** Subpastas diretas de [folder] que têm MP4 (nelas ou mais abaixo), com a contagem de faixas. */
    fun subfolders(tracks: List<Track>, folder: String): List<Child> {
        val counts = linkedMapOf<String, Int>()
        val prefix = "$folder/"
        for (t in tracks) {
            if (!t.dir.startsWith(prefix)) continue
            val next = t.dir.removePrefix(prefix).substringBefore('/')
            val childPath = prefix + next
            counts[childPath] = (counts[childPath] ?: 0) + 1
        }
        return counts.map { (p, n) -> Child(p, p.substringAfterLast('/'), n) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
