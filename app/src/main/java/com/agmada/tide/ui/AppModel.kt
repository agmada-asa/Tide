package com.agmada.tide.ui

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.agmada.tide.data.Song
import com.agmada.tide.data.TideRepo
import com.agmada.tide.playback.PlaybackService
import com.agmada.tide.playback.toMediaItem
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class QueueItem(val index: Int, val song: Song)

data class NowState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val upNext: List<QueueItem> = emptyList(),
    /** The songs a swipe on the cover goes to, after repeat and shuffle. */
    val previous: Song? = null,
    val next: Song? = null,
    /** Where the queue came from: "Album · Tidewater". */
    val source: String? = null,
)

sealed interface Route {
    data object Library : Route
    data class Album(val key: String) : Route
    data class Artist(val key: String) : Route
    data class Playlist(val id: Long) : Route
    data class Folder(val path: String) : Route
    data class Import(val text: String = "") : Route
    data object Settings : Route
    data object Tidy : Route
    /** Turning an album that's really a playlist ("!!!") back into one. */
    data class SortOut(val albumKey: String) : Route
}

/** Overlays that sit above every screen. */
sealed interface Sheet {
    data class SongMenu(val song: Song, val queueIndex: Int? = null, val playlistItemId: Long? = null) : Sheet
    data class AddToPlaylist(val songs: List<Song>) : Sheet
    data class EditSong(val song: Song) : Sheet
    data class PlaylistMenu(val id: Long) : Sheet
    data class AlbumMenu(val album: com.agmada.tide.data.Album) : Sheet
    data object NewPlaylist : Sheet
    data object Sleep : Sheet
}

class AppModel(app: Application) : AndroidViewModel(app) {
    val repo = TideRepo.get(app)
    private var controller: MediaController? = null

    private val _now = MutableStateFlow(NowState())
    val now: StateFlow<NowState> = _now

    val stack = mutableStateListOf<Route>(Route.Library)
    var nowPlayingOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var sheet by mutableStateOf<Sheet?>(null)
    var toast by mutableStateOf<String?>(null)
    private var source: String? = null

    var libraryTab by mutableStateOf(Tab.Songs)
    var libraryQuery by mutableStateOf("")
    var librarySorts by mutableStateOf(mapOf(Tab.Songs to Sort.Title, Tab.Albums to Sort.Title, Tab.Artists to Sort.Title))

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.getOrNull()?.let { c ->
                controller = c
                c.addListener(listener)
                refresh()
            }
        }, MoreExecutors.directExecutor())
        viewModelScope.launch { repo.library.collect { refresh() } }
    }

    fun go(route: Route) { stack.add(route); nowPlayingOpen = false; queueOpen = false }
    fun back(): Boolean = when {
        sheet != null -> { sheet = null; true }
        queueOpen -> { queueOpen = false; true }
        nowPlayingOpen -> { nowPlayingOpen = false; true }
        stack.size > 1 -> { stack.removeAt(stack.lastIndex); true }
        else -> false
    }

    private fun refresh() {
        val c = controller ?: return
        val library = repo.library.value
        val song = c.currentMediaItem?.mediaId?.toLongOrNull()?.let { library?.byId?.get(it) }
        val upNext = mutableListOf<QueueItem>()
        val timeline = c.currentTimeline
        if (!timeline.isEmpty && c.currentMediaItemIndex != C.INDEX_UNSET) {
            var i = timeline.getNextWindowIndex(c.currentMediaItemIndex, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            while (i != C.INDEX_UNSET && upNext.size < 500) {
                c.getMediaItemAt(i).mediaId.toLongOrNull()?.let { library?.byId?.get(it) }?.let { upNext += QueueItem(i, it) }
                i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            }
        }
        fun songAt(index: Int) = index.takeIf { it != C.INDEX_UNSET }?.let { c.getMediaItemAt(it).mediaId.toLongOrNull() }?.let { library?.byId?.get(it) }
        _now.value = NowState(
            song = song,
            isPlaying = c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: song?.durationMs ?: 0,
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
            upNext = upNext,
            previous = songAt(c.previousMediaItemIndex),
            next = songAt(c.nextMediaItemIndex),
            source = source,
        )
    }

    fun position(): Long = controller?.currentPosition ?: 0

    /** Plays [songs] from [start]; with [shuffle], from a random one in shuffled order. */
    fun play(songs: List<Song>, start: Int = 0, shuffle: Boolean = false, from: String? = null) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        source = from
        c.shuffleModeEnabled = shuffle
        val first = if (shuffle && start == 0) songs.indices.random() else start.coerceIn(songs.indices)
        c.setMediaItems(songs.map { it.toMediaItem() }, first, 0)
        c.prepare()
        c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    /** A swipe always changes song; the previous button restarts one that's been playing a while. */
    fun skipTo(forward: Boolean) = controller?.let { if (forward) it.seekToNextMediaItem() else it.seekToPreviousMediaItem() }
    fun seek(ms: Long) = controller?.seekTo(ms)
    fun jumpTo(index: Int) = controller?.let { it.seekToDefaultPosition(index); it.play() }
    fun setShuffle(on: Boolean) { controller?.shuffleModeEnabled = on }
    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(songs)
        c.addMediaItems(c.currentMediaItemIndex + 1, songs.map { it.toMediaItem() })
        toast = if (songs.size == 1) "Playing next" else "${songs.size} songs playing next"
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) return play(songs)
        c.addMediaItems(songs.map { it.toMediaItem() })
        toast = if (songs.size == 1) "Added to queue" else "${songs.size} songs added to queue"
    }

    fun removeFromQueue(index: Int) = controller?.removeMediaItem(index)
    fun moveInQueue(from: Int, to: Int) = controller?.moveMediaItem(from, to)
    fun clearQueue() {
        val c = controller ?: return
        val current = c.currentMediaItemIndex
        if (current + 1 < c.mediaItemCount) c.removeMediaItems(current + 1, c.mediaItemCount)
        if (current > 0) c.removeMediaItems(0, current)
    }

    override fun onCleared() {
        controller?.removeListener(listener)
        controller?.release()
    }
}
