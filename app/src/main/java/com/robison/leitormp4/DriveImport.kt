package com.robison.leitormp4

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Importar músicas do Google Drive (ou de qualquer app que apareça no seletor de ficheiros
 * do Android). As músicas escolhidas são copiadas uma vez para a pasta "Google Drive" do app,
 * e depois tocam sem internet, também no carro.
 *
 * A pasta fica em Android/media/com.robison.leitormp4/Google Drive: o app escreve lá sem
 * permissões extra. Atenção: se o app for desinstalado, o Android apaga esta pasta
 * (atualizar por cima não apaga).
 */
object DriveImport {

    const val FOLDER_NAME = "Google Drive"

    /** Tipos mostrados no seletor. */
    val MIME_TYPES = arrayOf("audio/*", "video/mp4", "video/webm", "video/3gpp", "application/ogg")

    @Suppress("DEPRECATION")
    fun folder(context: Context): File {
        val base = context.externalMediaDirs.firstOrNull { it != null }
            ?: context.getExternalFilesDir(null)
            ?: context.filesDir
        return File(base, FOLDER_NAME).apply { mkdirs() }
    }

    fun isInFolder(context: Context, path: String): Boolean =
        FolderTree.isInside(File(path).parent ?: "", folder(context).absolutePath)

    /** Ficheiros de áudio já importados. */
    fun localFiles(context: Context): List<File> =
        folder(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in TrackRepository.EXTENSIONS }
            .orEmpty()

    sealed class Result {
        data class Copied(val file: File) : Result()
        data class AlreadyThere(val file: File) : Result()
        object Unsupported : Result()
        object Failed : Result()
    }

    /** Copia um ficheiro escolhido no seletor para a pasta do app. */
    fun copy(context: Context, uri: Uri): Result {
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val iName = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val iSize = c.getColumnIndex(OpenableColumns.SIZE)
                        if (iName >= 0) name = c.getString(iName)
                        if (iSize >= 0 && !c.isNull(iSize)) size = c.getLong(iSize)
                    }
                }
        }

        // Nome e extensão do ficheiro
        var base = (name ?: uri.lastPathSegment ?: "musica").substringAfterLast('/')
        var ext = base.substringAfterLast('.', "").lowercase()
        if (ext !in TrackRepository.EXTENSIONS) {
            val fromMime = resolver.getType(uri)?.let { mime ->
                when (mime) {
                    "audio/mpeg", "audio/mp3" -> "mp3"
                    "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
                    "video/mp4" -> "mp4"
                    else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                }
            }?.lowercase()
            if (fromMime == null || fromMime !in TrackRepository.EXTENSIONS) return Result.Unsupported
            ext = fromMime
        } else {
            base = base.substringBeforeLast('.')
        }
        val clean = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').ifEmpty { "musica" }

        val dir = folder(context)
        var target = File(dir, "$clean.$ext")
        // Mesmo nome e mesmo tamanho: já foi importado antes
        if (target.exists() && size > 0 && target.length() == size) return Result.AlreadyThere(target)
        var n = 2
        while (target.exists()) target = File(dir, "$clean ($n).$ext").also { n++ }

        val tmp = File(dir, ".${target.name}.part")
        return try {
            val input = resolver.openInputStream(uri) ?: return Result.Failed
            input.use { inp -> tmp.outputStream().use { out -> inp.copyTo(out, 64 * 1024) } }
            if (tmp.length() == 0L || !tmp.renameTo(target)) {
                tmp.delete()
                Result.Failed
            } else {
                Result.Copied(target)
            }
        } catch (_: Exception) {
            tmp.delete()
            Result.Failed
        }
    }
}
