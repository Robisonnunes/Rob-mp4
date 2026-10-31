package com.robison.leitormp4

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class Track(
    /** Identificador estável = caminho completo do ficheiro (usado nas playlists) */
    val id: String,
    val uri: Uri,
    val title: String,
    val folder: String,
    val durationMs: Long,
    /** Pasta completa onde o ficheiro está, ex.: /storage/emulated/0/Music/WhatsApp */
    val dir: String
)

class LoadResult(
    val tracks: List<Track>,
    /** Endereços antigos (content://...) -> caminho, para migrar playlists antigas */
    val aliases: Map<String, String>
)

object TrackRepository {

    /** Formatos de áudio que o app toca (o ExoPlayer suporta todos). */
    val EXTENSIONS = setOf("mp4", "m4a", "mp3", "aac", "opus", "ogg", "oga", "wav", "flac", "amr", "3gp", "weba", "webm")

    @Suppress("DEPRECATION")
    private val DATA = MediaStore.MediaColumns.DATA

    /**
     * Com "acesso a todos os ficheiros", lê as pastas diretamente (vê também pastas com .nomedia,
     * como as do WhatsApp). Sem esse acesso, usa o catálogo de mídia do Android (MediaStore).
     */
    fun loadAll(context: Context, fullAccess: Boolean): LoadResult {
        val catalog = queryMediaStore(context)
        val aliases = catalog.associate { it.uri.toString() to it.id }

        val tracks = if (fullAccess) {
            val durations = catalog.associate { it.id to it.durationMs }
            scanFileSystem(context).map { f ->
                val path = f.absolutePath
                val dir = f.parent ?: "/"
                Track(
                    id = path,
                    uri = Uri.fromFile(f),
                    title = f.name.substringBeforeLast('.'),
                    folder = dir.substringAfterLast('/'),
                    durationMs = durations[path] ?: 0L,
                    dir = dir
                )
            }
        } else {
            catalog
        }

        return LoadResult(
            tracks.distinctBy { it.id }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
            aliases
        )
    }

    // ------------------------------------------------------------------ MediaStore

    private fun queryMediaStore(context: Context): List<Track> {
        val likes = EXTENSIONS.joinToString(" OR ") { "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?" }
        val args = EXTENSIONS.map { "%.$it" }.toTypedArray()
        return query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, likes, args) +
            query(context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, likes, args)
    }

    private fun query(context: Context, collection: Uri, selection: String, args: Array<String>): List<Track> {
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
                    val name = c.getString(iName) ?: continue
                    val uri = ContentUris.withAppendedId(collection, c.getLong(iId))
                    val path = (if (iData >= 0) c.getString(iData) else null) ?: uri.toString()
                    val dir = path.substringBeforeLast('/', "").ifEmpty { "/Outros" }
                    result += Track(
                        id = path,
                        uri = uri,
                        title = name.substringBeforeLast('.'),
                        folder = dir.substringAfterLast('/'),
                        durationMs = if (iDur >= 0) c.getLong(iDur) else 0L,
                        dir = dir
                    )
                }
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
        return result
    }

    // ------------------------------------------------------------------ pastas diretamente

    /** Raízes: memória interna + cartões SD. */
    fun storageRoots(context: Context): List<File> {
        val roots = linkedSetOf<String>()
        @Suppress("DEPRECATION")
        roots += Environment.getExternalStorageDirectory().absolutePath
        context.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            roots += f.absolutePath.substringBefore("/Android/")
        }
        return roots.map { File(it) }.filter { it.canRead() }
    }

    private fun scanFileSystem(context: Context): List<File> {
        val found = mutableListOf<File>()
        val stack = ArrayDeque<Pair<File, Int>>()
        storageRoots(context).forEach { stack.addLast(it to 0) }
        while (stack.isNotEmpty() && found.size < 50_000) {
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                val name = f.name
                if (f.isDirectory) {
                    if (name.startsWith(".")) continue                       // pastas ocultas (miniaturas, estados…)
                    if (depth == 0 && name == "Android") {
                        // Só Android/media é acessível (é onde fica o WhatsApp nos celulares recentes)
                        File(f, "media").takeIf { it.isDirectory }?.let { stack.addLast(it to depth + 2) }
                        continue
                    }
                    if (depth < 15) stack.addLast(f to depth + 1)
                } else if (name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                    found += f
                }
            }
        }
        return found
    }

    /** O app pode ler todas as pastas? */
    fun hasFullAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        else -> context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
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
            parts.size >= 2 && parts[0] == "sdcard" -> "/sdcard"
            else -> "/" + parts.first()
        }
    }

    fun displayName(path: String): String = when {
        path == "/storage/emulated/0" || path == "/sdcard" -> "Memória interna"
        path.startsWith("/storage/emulated/") && path.count { it == '/' } == 3 -> "Memória interna (outro utilizador)"
        Regex("^/storage/[^/]+$").matches(path) -> "Cartão SD"
        else -> path.substringAfterLast('/')
    }

    fun isInside(dir: String, folder: String) = dir == folder || dir.startsWith("$folder/")

    data class Child(val path: String, val name: String, val count: Int)

    /** Subpastas diretas de [folder] que têm áudio (nelas ou mais abaixo), com a contagem de faixas. */
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
