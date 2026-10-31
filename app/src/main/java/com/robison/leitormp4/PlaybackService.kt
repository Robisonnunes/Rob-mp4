package com.robison.leitormp4

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * Serviço que toca o áudio em segundo plano e fala com o Bluetooth / carro.
 *
 *  - Som pelo Bluetooth (carro, caixinha), botões do volante/rádio, notificação e ecrã bloqueado.
 *  - Envia título, playlist e nº da faixa para o rádio.
 *  - Segurar ⏭ / ⏮ no rádio (avanço/recuo rápido) muda para a playlist seguinte/anterior.
 *  - Menu de playlists e faixas para rádios com navegação (AVRCP 1.4+) e Android Auto.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var exo: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // baixa o volume para o GPS e pausa nas chamadas
            )
            .setHandleAudioBecomingNoisy(true) // pausa quando o Bluetooth desliga
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        // Avanço/recuo rápido (segurar ⏭/⏮ no carro) = mudar de playlist
        val player = object : ForwardingPlayer(exo) {
            override fun seekForward() = switchList(+1)
            override fun seekBack() = switchList(-1)
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ mudar de playlist

    private fun switchList(direction: Int) {
        val lists = Library.lists(this)
            .filter { Library.tracksOfList(this, it.id).isNotEmpty() }
        if (lists.isEmpty()) return
        val currentId = exo.currentMediaItem?.mediaMetadata?.extras?.getString(Library.EXTRA_LIST)
        val i = lists.indexOfFirst { it.id == currentId }
        val next = when {
            i < 0 -> lists.first()
            else -> lists[(i + direction).mod(lists.size)]
        }
        val queue = Library.queueOf(this, next.id)
        if (queue.isEmpty()) return
        exo.setMediaItems(queue, 0, 0L)
        exo.prepare()
        exo.play()
        Toast.makeText(this, next.name, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ menu do carro

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = Library.browsableItem(
                Library.ROOT, getString(R.string.app_name), null,
                MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, playable = false
            )
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val ctx = this@PlaybackService
            val children: List<MediaItem> = when {
                parentId == Library.ROOT -> Library.lists(ctx).map { l ->
                    val n = Library.tracksOfList(ctx, l.id).size
                    Library.browsableItem(
                        Library.LIST_PREFIX + l.id, l.name, faixas(n),
                        MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = n > 0
                    )
                }
                parentId.startsWith(Library.LIST_PREFIX) ->
                    Library.queueOf(ctx, parentId.removePrefix(Library.LIST_PREFIX))
                else -> emptyList()
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val f = File(Library.pathOf(mediaId))
            if (!f.isFile) {
                // -3 = ERROR_BAD_VALUE
                return Futures.immediateFuture(LibraryResult.ofError(-3))
            }
            val item = Library.mediaItem(Library.trackFromFile(f), Library.listIdOf(mediaId), null, 0, 1)
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        /** Garante que cada faixa chega ao leitor com o endereço do ficheiro. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { resolve(it) }.toMutableList())

        /**
         * Tocar a partir do menu do carro: escolher uma playlist toca-a toda;
         * escolher uma faixa toca a playlist a partir dessa faixa.
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val ctx = this@PlaybackService
            val single = mediaItems.singleOrNull()
            if (single != null) {
                val id = single.mediaId
                if (id.startsWith(Library.LIST_PREFIX)) {
                    val queue = Library.queueOf(ctx, id.removePrefix(Library.LIST_PREFIX))
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(queue, 0, C.TIME_UNSET)
                    )
                }
                val listId = Library.listIdOf(id)
                if (listId != null && single.localConfiguration == null) {
                    val queue = Library.queueOf(ctx, listId)
                    val path = Library.pathOf(id)
                    val idx = queue.indexOfFirst { Library.pathOf(it.mediaId) == path }
                    if (idx >= 0) {
                        return Futures.immediateFuture(
                            MediaSession.MediaItemsWithStartPosition(queue, idx, startPositionMs)
                        )
                    }
                }
            }
            val resolved = mediaItems.map { resolve(it) }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
            )
        }

        private fun resolve(item: MediaItem): MediaItem {
            if (item.localConfiguration != null) return item
            val uri = item.requestMetadata.mediaUri
                ?: Uri.fromFile(File(Library.pathOf(item.mediaId)))
            return item.buildUpon().setUri(uri).build()
        }
    }

    private fun faixas(n: Int) = if (n == 1) "1 faixa" else "$n faixas"
}
