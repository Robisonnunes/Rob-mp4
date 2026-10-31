package com.robison.leitormp4

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.io.File

/**
 * Funções partilhadas entre o ecrã e o serviço de reprodução:
 * listas (playlists + favoritos) e montagem das faixas para o leitor / carro.
 */
object Library {

    const val FAV_ID = "fav"
    const val EXTRA_LIST = "list"

    /** Prefixos dos identificadores usados no menu do carro. */
    const val ROOT = "root"
    const val LIST_PREFIX = "list|"     // list|<idDaLista>
    const val TRACK_PREFIX = "t|"       // t|<idDaLista>|<caminho>

    data class ListInfo(val id: String, val name: String)

    /** Favoritos primeiro, depois as playlists por ordem alfabética. */
    fun lists(context: Context): List<ListInfo> {
        val store = PlaylistStore(context)
        return listOf(ListInfo(FAV_ID, context.getString(R.string.favorites))) +
            store.sorted().map { ListInfo(it.id, it.name) }
    }

    fun listName(context: Context, id: String): String =
        if (id == FAV_ID) context.getString(R.string.favorites)
        else PlaylistStore(context).get(id)?.name.orEmpty()

    /** Faixas de uma lista, lidas diretamente do disco (usado pelo serviço / carro). */
    fun tracksOfList(context: Context, id: String): List<Track> {
        val paths: List<String> = if (id == FAV_ID) {
            val favs = Favorites(context)
            val fromFolders = favs.folderPaths().sorted().flatMap { audioFilesIn(File(it)) }
            (favs.trackIds().sortedBy { File(it).name.lowercase() } + fromFolders).distinct()
        } else {
            PlaylistStore(context).get(id)?.items.orEmpty()
        }
        return paths.map { File(it) }.filter { it.isFile }.map { trackFromFile(it) }
    }

    fun trackFromFile(f: File): Track {
        val dir = f.parent ?: "/"
        return Track(
            id = f.absolutePath,
            uri = Uri.fromFile(f),
            title = f.nameWithoutExtension,
            folder = dir.substringAfterLast('/'),
            durationMs = 0L,
            dir = dir
        )
    }

    private fun audioFilesIn(dir: File, depth: Int = 0): List<File> {
        if (depth > 10) return emptyList()
        val children = dir.listFiles() ?: return emptyList()
        val files = children.filter {
            it.isFile && it.extension.lowercase() in TrackRepository.EXTENSIONS
        }.sortedBy { it.name.lowercase() }
        val sub = children.filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .flatMap { audioFilesIn(it, depth + 1) }
        return files + sub
    }

    /**
     * Faixa pronta para o leitor. O "artista" mostra a playlist e o nº da faixa,
     * que é o que os rádios mais simples mostram além do título.
     */
    fun mediaItem(t: Track, listId: String?, listName: String?, index: Int, total: Int): MediaItem {
        val extras = Bundle().apply { if (listId != null) putString(EXTRA_LIST, listId) }
        val subtitle = listName?.takeIf { it.isNotBlank() } ?: t.folder
        val meta = MediaMetadata.Builder()
            .setTitle(t.title)
            .setDisplayTitle(t.title)
            .setArtist(subtitle)
            .setAlbumTitle(subtitle)
            .setTrackNumber(index + 1)
            .setTotalTrackCount(total)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setMediaId(if (listId != null) "$TRACK_PREFIX$listId|${t.id}" else t.id)
            .setUri(t.uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(t.uri).build())
            .setMediaMetadata(meta)
            .build()
    }

    fun queueOf(context: Context, listId: String): List<MediaItem> {
        val name = listName(context, listId)
        val tracks = tracksOfList(context, listId)
        return tracks.mapIndexed { i, t -> mediaItem(t, listId, name, i, tracks.size) }
    }

    /** Caminho do ficheiro a partir do mediaId (com ou sem prefixo de lista). */
    fun pathOf(mediaId: String): String =
        if (mediaId.startsWith(TRACK_PREFIX)) mediaId.removePrefix(TRACK_PREFIX).substringAfter('|')
        else mediaId

    fun listIdOf(mediaId: String): String? =
        if (mediaId.startsWith(TRACK_PREFIX)) mediaId.removePrefix(TRACK_PREFIX).substringBefore('|')
        else null

    /** Menu do carro: pasta navegável (playlist). */
    fun browsableItem(id: String, title: String, subtitle: String?, mediaType: Int, playable: Boolean): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(playable)
                    .setMediaType(mediaType)
                    .build()
            )
            .build()
}
