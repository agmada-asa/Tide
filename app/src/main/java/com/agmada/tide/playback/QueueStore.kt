package com.agmada.tide.playback

import android.content.Context
import androidx.media3.common.Player
import com.agmada.tide.data.Library
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The queue and position survive the app being closed, so play carries on where it stopped. */
object QueueStore {
    private const val PREFS = "queue"

    fun save(context: Context, player: Player) {
        val ids = (0 until player.mediaItemCount).joinToString(",") { player.getMediaItemAt(it).mediaId }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ids", ids)
            .putInt("index", player.currentMediaItemIndex)
            .putLong("position", player.currentPosition.coerceAtLeast(0))
            .putBoolean("shuffle", player.shuffleModeEnabled)
            .putInt("repeat", player.repeatMode)
            .apply()
    }

    fun restore(context: Context, player: Player, library: Library) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getString("ids", null)?.split(",")?.mapNotNull { it.toLongOrNull() } ?: return
        val savedIndex = prefs.getInt("index", 0)
        val savedId = ids.getOrNull(savedIndex)
        val songs = ids.mapNotNull { library.byId[it] }
        if (songs.isEmpty()) return
        val index = songs.indexOfFirst { it.id == savedId }.coerceAtLeast(0)
        player.setMediaItems(songs.map { it.toMediaItem() }, index, if (songs[index].id == savedId) prefs.getLong("position", 0) else 0)
        player.shuffleModeEnabled = prefs.getBoolean("shuffle", false)
        player.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
        player.prepare()
    }
}

/** Pauses after a set time or at the end of the current song. */
object SleepTimer {
    sealed interface Event {
        data object Pause : Event
        data class EndOfTrack(val on: Boolean) : Event
    }

    sealed interface State {
        data object Off : State
        data class At(val endsAt: Long) : State
        data object EndOfTrack : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun minutes(count: Int) {
        cancel()
        val endsAt = System.currentTimeMillis() + count * 60_000L
        _state.value = State.At(endsAt)
        job = scope.launch {
            delay(count * 60_000L)
            _events.tryEmit(Event.Pause)
            _state.value = State.Off
        }
    }

    fun endOfTrack() {
        cancel()
        _state.value = State.EndOfTrack
        _events.tryEmit(Event.EndOfTrack(true))
    }

    fun cancel() {
        job?.cancel()
        if (_state.value == State.EndOfTrack) _events.tryEmit(Event.EndOfTrack(false))
        _state.value = State.Off
    }

    /** Called by the service when a song ends by itself. */
    fun trackEnded() {
        if (_state.value == State.EndOfTrack) {
            _state.value = State.Off
            _events.tryEmit(Event.EndOfTrack(false))
        }
    }

    fun apply(event: Event, player: Player) {
        when (event) {
            Event.Pause -> player.pause()
            is Event.EndOfTrack -> (player as? androidx.media3.exoplayer.ExoPlayer)?.pauseAtEndOfMediaItems = event.on
        }
    }
}
