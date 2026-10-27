package com.agmada.tide.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.agmada.tide.importer.FoundAlbum
import com.agmada.tide.importer.RemoteCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PlaylistEntry(val item: PlaylistItemRow, val song: Song?)

data class PlaylistView(val row: PlaylistRow, val entries: List<PlaylistEntry>) {
    val songs: List<Song> get() = entries.mapNotNull { it.song }
    val missing: Int get() = entries.count { it.song == null }
    val isLiked: Boolean get() = row.id == TideDao.LIKED
}

/** The one place the app's data lives, shared by the screens and the playback service. */
class TideRepo private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dao = TideDb.get(app).dao()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _hidden = MutableStateFlow(prefs.getStringSet("hidden", emptySet())!!.toSet())
    val hiddenFolders: StateFlow<Set<String>> = _hidden
    private val _skipShort = MutableStateFlow(prefs.getBoolean("skipShort", true))
    /** Leave out audio under 30 seconds: voice notes, ringtones, WhatsApp clips. */
    val skipShort: StateFlow<Boolean> = _skipShort

    /** Every music file from the last scan, or from the saved one until that finishes. */
    private val scanned = MutableStateFlow<List<RawSong>?>(null)
    private val raws = combine(scanned, _hidden, _skipShort) { songs, hidden, skipShort -> songs?.visible(hidden, if (skipShort) 30_000 else 0) }

    val library: StateFlow<Library?> = combine(raws, dao.edits()) { raws, edits ->
        raws?.let { Library.build(it, edits.associate { e -> e.path to e.toEdit() }) }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, null)

    val edits: StateFlow<Map<String, SongEditRow>> = dao.edits().map { list -> list.associateBy { it.path } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val playlists: StateFlow<List<PlaylistView>> = combine(library, dao.playlists(), dao.items()) { library, rows, items ->
        val byList = items.groupBy { it.playlistId }
        rows.map { row ->
            PlaylistView(row, byList[row.id].orEmpty().map { item -> PlaylistEntry(item, library?.match(item.title, item.artist, item.durationMs, item.path)) })
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyList())

    val likedIds: StateFlow<Set<Long>> = playlists.map { lists -> lists.firstOrNull { it.isLiked }?.songs?.map { it.id }?.toSet().orEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    private var scanJob: Job? = null
    private val scanning = Mutex()
    private var watching = false
    /** The last scan and when it ran, saved or fresh. */
    private var known: Snapshot? = null
    // Only with audio permission: a cache from before it was taken away shouldn't still show.
    private val saved = scope.async(Dispatchers.IO) { if (canReadAudio()) LibraryCache.load(app) else null }

    init {
        scope.launch { dao.insertPlaylist(PlaylistRow(id = TideDao.LIKED, name = "Liked songs")) }
        scope.launch { saved.await()?.let { scanned.compareAndSet(null, it.songs) } }
    }

    private fun canReadAudio() = ContextCompat.checkSelfPermission(
        app, if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Shows the saved library straight away and reads the phone's music again in the background,
     * only if Android says something changed. Call once audio permission is granted; after that it follows changes itself.
     */
    fun watch() {
        if (!watching) {
            watching = true
            app.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = update(debounce = true, force = false)
                },
            )
        }
        update(debounce = false, force = false)
    }

    /** Reads the phone's music again, even if Android says nothing changed. */
    fun rescan() = update(debounce = false, force = true)

    private fun update(debounce: Boolean, force: Boolean) {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            if (debounce) delay(1500)
            scanning.withLock {
                val last = known ?: saved.await()
                val stamp = runCatching { LibraryCache.stamp(app) }.getOrNull()
                if (!force && stamp != null && last != null && last.stamp == stamp) {
                    scanned.compareAndSet(null, last.songs)
                    return@launch
                }
                val songs = runCatching { Scanner.scan(app) }.getOrNull()
                if (songs == null) {
                    // Keep showing what we had rather than an empty library.
                    scanned.compareAndSet(null, last?.songs ?: emptyList())
                    return@launch
                }
                val fresh = Snapshot(stamp, songs)
                known = fresh
                // Equal lists don't emit, so an unchanged library isn't rebuilt.
                scanned.value = songs
                if (fresh != last) LibraryCache.save(app, fresh)
            }
        }
    }

    fun hideFolder(path: String) = setHidden(_hidden.value + path)
    fun showFolder(path: String) = setHidden(_hidden.value - path)

    private fun setHidden(folders: Set<String>) {
        _hidden.value = folders
        prefs.edit().putStringSet("hidden", folders).apply()
    }

    fun setSkipShort(on: Boolean) {
        _skipShort.value = on
        prefs.edit().putBoolean("skipShort", on).apply()
    }

    private fun Song.toItem(playlistId: Long, position: Int) =
        PlaylistItemRow(playlistId = playlistId, position = position, title = title, artist = artists.first(), durationMs = durationMs, path = path)

    fun toggleLike(song: Song) = scope.launch {
        val liked = dao.itemsOf(TideDao.LIKED)
        val library = library.value
        val existing = liked.filter { library?.match(it.title, it.artist, it.durationMs, it.path)?.id == song.id }
        if (existing.isNotEmpty()) existing.forEach { dao.deleteItem(it.id) }
        else dao.insertItems(listOf(song.toItem(TideDao.LIKED, dao.nextPosition(TideDao.LIKED))))
    }

    fun addToPlaylist(playlistId: Long, songs: List<Song>) = scope.launch {
        val start = dao.nextPosition(playlistId)
        dao.insertItems(songs.mapIndexed { i, song -> song.toItem(playlistId, start + i) })
    }

    fun createPlaylist(name: String, songs: List<Song>, onCreated: (Long) -> Unit = {}) = scope.launch {
        val id = dao.insertPlaylist(PlaylistRow(name = name.trim().ifEmpty { "New playlist" }))
        dao.insertItems(songs.mapIndexed { i, song -> song.toItem(id, i) })
        launch(Dispatchers.Main) { onCreated(id) }
    }

    /** Saves a Spotify list as a playlist. Songs not on the phone stay in it and fill in when the files turn up. */
    fun importCollection(remote: RemoteCollection, onCreated: (Long) -> Unit = {}) = scope.launch {
        val subtitle = listOfNotNull(remote.kind.name.takeIf { remote.kind.name != "Playlist" }, remote.subtitle).joinToString(" · ").ifEmpty { null }
        val id = dao.insertPlaylist(PlaylistRow(name = remote.name, subtitle = subtitle, source = remote.sourceUrl, coverUrl = remote.coverUrl))
        dao.insertItems(remote.tracks.mapIndexed { i, t ->
            val artist = t.artists.joinToString(", ")
            PlaylistItemRow(playlistId = id, position = i, title = t.title, artist = artist, durationMs = t.durationMs, path = library.value?.match(t.title, artist, t.durationMs)?.path)
        })
        launch(Dispatchers.Main) { onCreated(id) }
    }

    fun renamePlaylist(id: Long, name: String) = scope.launch { dao.renamePlaylist(id, name.trim()) }
    fun deletePlaylist(id: Long) = scope.launch { if (id != TideDao.LIKED) dao.deletePlaylist(id) }
    fun removeEntry(itemId: Long) = scope.launch { dao.deleteItem(itemId) }

    fun moveEntry(playlistId: Long, from: Int, to: Int) = scope.launch {
        val items = dao.itemsOf(playlistId).toMutableList()
        if (from !in items.indices || to !in items.indices) return@launch
        items.add(to, items.removeAt(from))
        dao.replaceItems(playlistId, items)
    }

    /**
     * A playlist that was saved as an album ("!!!"): keeps it as a playlist in the same order, and moves
     * each song to the album it was [found] on. Songs with no album found become singles.
     */
    fun unpackAlbum(album: Album, found: Map<Long, FoundAlbum?>, onCreated: (Long) -> Unit = {}) = scope.launch {
        val id = dao.insertPlaylist(PlaylistRow(name = album.title))
        dao.insertItems(album.songs.mapIndexed { i, song -> song.toItem(id, i) })
        val saved = edits.value
        dao.saveEdits(album.songs.map { song ->
            val f = found[song.id]
            val old = saved[song.path]
            SongEditRow(song.path, old?.title, old?.artist, f?.album ?: "", f?.artists?.joinToString(", "), f?.track, f?.year)
        })
        launch(Dispatchers.Main) { onCreated(id) }
    }

    /** Saves each folder as a playlist of the same name, in file order, unless one by that name exists. */
    suspend fun saveFoldersAsPlaylists(folders: List<Folder>): Int {
        val names = playlists.value.map { Tags.key(it.row.name) }.toSet()
        var made = 0
        for (folder in folders) {
            if (Tags.key(folder.name) in names) continue
            val id = dao.insertPlaylist(PlaylistRow(name = folder.name, subtitle = "From the folder ${folder.path.trimEnd('/')}"))
            val ordered = folder.songs.sortedWith(compareBy({ it.fileName.takeWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }, { it.fileName }))
            dao.insertItems(ordered.mapIndexed { i, song -> song.toItem(id, i) })
            made++
        }
        return made
    }

    /**
     * A file Tidy up moved: its edit and playlist hints follow it. An artist that only came from
     * the old name ("Fennick - Undertow.mp3") is kept as an edit, and can be saved into the file later.
     */
    suspend fun moved(song: Song, to: String) {
        dao.moved(song.path, to)
        if (song.namedByFile && song.path !in edits.value) {
            dao.saveEdit(SongEditRow(to, song.title, song.artistLine, null, null, null, null))
        }
    }

    /** The fixes are in the files now, so Tide's copies can go. */
    suspend fun clearEdits(paths: List<String>) = dao.clearEdits(paths)

    fun saveEdit(song: Song, edit: SongEdit) = scope.launch {
        dao.saveEdit(SongEditRow(song.path, edit.title, edit.artist, edit.album, edit.albumArtist, edit.track, edit.year))
    }

    fun clearEdit(song: Song) = scope.launch { dao.clearEdit(song.path) }

    companion object {
        @Volatile private var instance: TideRepo? = null

        fun get(context: Context): TideRepo = instance ?: synchronized(this) {
            instance ?: TideRepo(context.applicationContext).also { instance = it }
        }
    }
}
