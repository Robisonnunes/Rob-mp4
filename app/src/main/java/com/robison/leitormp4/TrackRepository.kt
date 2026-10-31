package com.robison.leitormp4

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Track(
    val uri: Uri,
    val title: String,
    val folder: String,
    val durationMs: Long
)

/** Procura ficheiros MP4 (e M4A) na memória do celular através do MediaStore do Android. */
object TrackRepository {

    fun loadAll(context: Context): List<Track> {
        val tracks = mutableListOf<Track>()

        // Ficheiros .mp4 com vídeo ficam catalogados como "vídeo" pelo Android
        tracks += query(
            context,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            idCol = MediaStore.Video.Media._ID,
            nameCol = MediaStore.Video.Media.DISPLAY_NAME,
            durationCol = MediaStore.Video.Media.DURATION,
            folderCol = MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            selection = "${MediaStore.Video.Media.MIME_TYPE} = ? OR ${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?",
            args = arrayOf("video/mp4", "%.mp4")
        )

        // Ficheiros .mp4 / .m4a só com áudio ficam catalogados como "áudio"
        tracks += query(
            context,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            idCol = MediaStore.Audio.Media._ID,
            nameCol = MediaStore.Audio.Media.DISPLAY_NAME,
            durationCol = MediaStore.Audio.Media.DURATION,
            folderCol = null,
            selection = "${MediaStore.Audio.Media.MIME_TYPE} = ? OR ${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
            args = arrayOf("audio/mp4", "%.mp4")
        )

        return tracks
            .distinctBy { it.uri }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    private fun query(
        context: Context,
        collection: Uri,
        idCol: String,
        nameCol: String,
        durationCol: String,
        folderCol: String?,
        selection: String,
        args: Array<String>
    ): List<Track> {
        val projection = listOfNotNull(idCol, nameCol, durationCol, folderCol).toTypedArray()
        val result = mutableListOf<Track>()
        try {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val iId = c.getColumnIndexOrThrow(idCol)
                val iName = c.getColumnIndexOrThrow(nameCol)
                val iDur = c.getColumnIndexOrThrow(durationCol)
                val iFolder = folderCol?.let { c.getColumnIndex(it) } ?: -1
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: continue
                    result += Track(
                        uri = ContentUris.withAppendedId(collection, id),
                        title = name.substringBeforeLast('.'),
                        folder = if (iFolder >= 0) c.getString(iFolder).orEmpty() else "",
                        durationMs = c.getLong(iDur)
                    )
                }
            }
        } catch (_: SecurityException) {
            // Sem permissão para esta categoria — ignora
        }
        return result
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
