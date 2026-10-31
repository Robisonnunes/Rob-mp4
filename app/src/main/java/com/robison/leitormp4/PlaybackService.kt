package com.robison.leitormp4

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Serviço que toca o áudio em segundo plano.
 *
 * A MediaSession é o que faz o app funcionar com Bluetooth:
 *  - o som sai automaticamente pelo dispositivo ligado (carro, caixinha, fones);
 *  - os botões do rádio do carro / caixinha (play, pausa, próxima, anterior) controlam o app;
 *  - o nome da faixa aparece no ecrã do carro (quando o rádio suporta);
 *  - controlos aparecem na notificação e no ecrã bloqueado.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            // "true" = gere o foco de áudio: pausa numa chamada e retoma depois
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            // Pausa automaticamente quando o Bluetooth desliga (ex.: desligar o carro)
            .setHandleAudioBecomingNoisy(true)
            // Mantém o CPU ativo com o ecrã desligado
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Só áudio: ignora a faixa de vídeo do MP4 (poupa bateria)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                // Garante que cada faixa enviada pelo ecrã chega com o endereço do ficheiro
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>
                ): ListenableFuture<MutableList<MediaItem>> {
                    val resolved = mediaItems.map { item ->
                        val uri = item.requestMetadata.mediaUri ?: Uri.parse(item.mediaId)
                        item.buildUpon().setUri(uri).build()
                    }.toMutableList()
                    return Futures.immediateFuture(resolved)
                }
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    // Se o app for fechado nas "apps recentes" sem estar a tocar, encerra o serviço
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
