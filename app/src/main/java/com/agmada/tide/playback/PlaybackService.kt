package com.agmada.tide.playback

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.util.Size
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.agmada.tide.MainActivity
import com.agmada.tide.R
import com.agmada.tide.data.Scanner
import com.agmada.tide.data.Song
import com.agmada.tide.data.TideRepo
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Plays in the background, with lock-screen and notification controls from the media session. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(Callback())
            .build()
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) })

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                fillArtwork(player)
                QueueStore.save(this@PlaybackService, player)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) SleepTimer.trackEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) QueueStore.save(this@PlaybackService, player)
            }
        })
        scope.launch { SleepTimer.events.collect { event -> SleepTimer.apply(event, player) } }
        scope.launch {
            val library = TideRepo.get(this@PlaybackService).library.filter { it != null }.first()!!
            if (player.mediaItemCount == 0) QueueStore.restore(this@PlaybackService, player, library)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            QueueStore.save(this@PlaybackService, player)
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /** Embedded cover art for the notification and lock screen, loaded when a song comes up. */
    private fun fillArtwork(player: Player) {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        if (item.mediaMetadata.artworkData != null) return
        val id = item.mediaId.toLongOrNull() ?: return
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = contentResolver.loadThumbnail(Scanner.uriOf(id), Size(512, 512), null)
                    ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out); out.toByteArray() }
                }.getOrNull()
            } ?: return@launch
            if (index < player.mediaItemCount && player.getMediaItemAt(index).mediaId == item.mediaId) {
                val metadata = item.mediaMetadata.buildUpon().setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()
                player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build())
            }
        }
    }

    /** Rebuilds items from their ids, so what plays is always the file the library knows. */
    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val library = TideRepo.get(this@PlaybackService).library.value
            val items = mediaItems.mapNotNull { item ->
                item.mediaId.toLongOrNull()?.let { id -> library?.byId?.get(id)?.toMediaItem() } ?: item.takeIf { it.localConfiguration != null }
            }
            return Futures.immediateFuture(items.toMutableList())
        }
    }
}

fun Song.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(Scanner.uriOf(id))
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artistLine)
            .setAlbumTitle(album)
            .setAlbumArtist(albumArtist)
            .setTrackNumber(track)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build(),
    )
    .build()
