package com.agmada.tide.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agmada.tide.data.Library
import com.agmada.tide.data.PlaylistView
import com.agmada.tide.data.SongEdit
import com.agmada.tide.data.TideDao
import com.agmada.tide.playback.SleepTimer
import kotlinx.coroutines.delay

/** Whichever sheet is open, drawn above everything. */
@Composable
fun Sheets(model: AppModel, library: Library?, playlists: List<PlaylistView>) {
    val sheet = model.sheet
    // Keep the last sheet drawn while it slides away.
    var shown by remember { mutableStateOf(sheet) }
    if (sheet != null) shown = sheet
    BottomSheet(sheet != null, { model.sheet = null }) {
        when (val s = shown) {
            is Sheet.SongMenu -> SongMenu(model, s, library)
            is Sheet.AddToPlaylist -> AddToPlaylist(model, s, playlists)
            is Sheet.EditSong -> EditSong(model, s)
            is Sheet.PlaylistMenu -> PlaylistMenu(model, playlists.firstOrNull { it.row.id == s.id })
            is Sheet.AlbumMenu -> AlbumMenu(model, s.album)
            Sheet.NewPlaylist -> NameSheet(model, "New playlist", "", "Create") { name -> model.repo.createPlaylist(name, emptyList()) { model.go(Route.Playlist(it)) } }
            Sheet.Sleep -> SleepSheet(model)
            null -> Unit
        }
    }
}

@Composable
private fun ColumnScope.SongMenu(model: AppModel, s: Sheet.SongMenu, library: Library?) {
    val song = s.song
    fun close() { model.sheet = null }
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(song, Modifier.size(52.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artistLine} · ${song.album}", style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    MenuItem(Glyphs.Playnext, "Play next", { model.playNext(listOf(song)); close() })
    MenuItem(Glyphs.Queue, "Add to queue", { model.addToQueue(listOf(song)); close() })
    MenuItem(Glyphs.Addlist, "Add to playlist", { model.sheet = Sheet.AddToPlaylist(listOf(song)) })
    s.playlistItemId?.let { id -> MenuItem(Glyphs.Delete, "Remove from this playlist", { model.repo.removeEntry(id); close() }) }
    if (!song.isSingle) MenuItem(Glyphs.Album, "Go to album", { close(); model.go(Route.Album(song.albumKey)) })
    library?.artistFor(song.artists.first())?.let { artist -> MenuItem(Glyphs.Artist, "Go to artist", { close(); model.go(Route.Artist(artist.key)) }) }
    MenuItem(Glyphs.Edit, "Fix title, artist or album", { model.sheet = Sheet.EditSong(song) })
    Text(song.path, style = Type.tiny, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp))
}

@Composable
private fun ColumnScope.AddToPlaylist(model: AppModel, s: Sheet.AddToPlaylist, playlists: List<PlaylistView>) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        NameSheet(model, "New playlist", "", "Create") { name ->
            model.repo.createPlaylist(name, s.songs)
            model.toast = "Added to $name"
        }
        return
    }
    Text("Add to playlist", style = Type.heading, modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
    MenuItem(Glyphs.Add, "New playlist", { naming = true })
    LazyColumn(Modifier.heightIn(max = 380.dp)) {
        items(playlists.filter { it.row.source == null || it.row.id == TideDao.LIKED }, key = { it.row.id }) { list ->
            PlaylistRowView(list) {
                if (list.isLiked) s.songs.forEach { song -> if (song.id !in model.repo.likedIds.value) model.repo.toggleLike(song) }
                else model.repo.addToPlaylist(list.row.id, s.songs)
                model.toast = "Added to ${list.row.name}"
                model.sheet = null
            }
        }
    }
}

@Composable
private fun ColumnScope.EditSong(model: AppModel, s: Sheet.EditSong) {
    val song = s.song
    val saved = model.repo.edits.collectAsState().value[song.path]
    var title by remember { mutableStateOf(song.title) }
    var artist by remember { mutableStateOf(song.artistLine) }
    var album by remember { mutableStateOf(if (song.isSingle) "" else song.album) }
    var albumArtist by remember { mutableStateOf(if (song.isSingle) "" else song.albumArtist) }
    Column(Modifier.imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Fix song info", style = Type.heading, modifier = Modifier.padding(start = 2.dp))
        Text("Only Tide sees this. The file isn't changed.", style = Type.small, color = p.muted, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Labelled("Title") { Field(title, { title = it }, "Title", Modifier.fillMaxWidth()) }
        Labelled("Artists, separated by commas") { Field(artist, { artist = it }, "Artist", Modifier.fillMaxWidth()) }
        Labelled("Album (empty for a single)") { Field(album, { album = it }, "Album", Modifier.fillMaxWidth()) }
        Labelled("Album artist") { Field(albumArtist, { albumArtist = it }, "Same as the first artist", Modifier.fillMaxWidth()) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (saved != null) Pill("Undo my changes", { model.repo.clearEdit(song); model.sheet = null }, Modifier.weight(1f))
            Pill("Save", {
                model.repo.saveEdit(song, SongEdit(title.trim().ifEmpty { null }, artist.trim().ifEmpty { null }, album.trim().ifEmpty { "<unknown>" }, albumArtist.trim().ifEmpty { null }, saved?.track, saved?.year))
                model.sheet = null
                model.toast = "Saved"
            }, Modifier.weight(1f), solid = true)
        }
    }
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, style = Type.tiny, color = p.muted, modifier = Modifier.padding(start = 16.dp, bottom = 5.dp))
        content()
    }
}

@Composable
private fun ColumnScope.AlbumMenu(model: AppModel, album: com.agmada.tide.data.Album) {
    fun close() { model.sheet = null }
    Text(album.title, style = Type.heading, modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
    MenuItem(Glyphs.Playnext, "Play next", { model.playNext(album.songs); close() })
    MenuItem(Glyphs.Queue, "Add to queue", { model.addToQueue(album.songs); close() })
    MenuItem(Glyphs.Addlist, "Add to playlist", { model.sheet = Sheet.AddToPlaylist(album.songs) })
    if (!album.isSingle) MenuItem(Glyphs.Shuffle, "This is really a playlist", { close(); model.go(Route.SortOut(album.key)) })
}

@Composable
private fun ColumnScope.PlaylistMenu(model: AppModel, list: PlaylistView?) {
    list ?: return
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (renaming) {
        NameSheet(model, "Rename playlist", list.row.name, "Save") { model.repo.renamePlaylist(list.row.id, it) }
        return
    }
    Text(list.row.name, style = Type.heading, modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
    MenuItem(Glyphs.Queue, "Add to queue", { model.addToQueue(list.songs); model.sheet = null })
    MenuItem(Glyphs.Edit, "Rename", { renaming = true })
    if (confirmDelete) {
        MenuItem(Glyphs.Delete, "Tap again to delete for good", { model.repo.deletePlaylist(list.row.id); model.sheet = null; model.back() }, tint = Ocean.deep)
    } else {
        MenuItem(Glyphs.Delete, "Delete playlist", { confirmDelete = true })
    }
}

@Composable
private fun ColumnScope.NameSheet(model: AppModel, title: String, initial: String, action: String, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(250); runCatching { focus.requestFocus() } }
    fun save() { if (name.isNotBlank()) { onSave(name.trim()); model.sheet = null } }
    Column(Modifier.imePadding().padding(horizontal = 20.dp)) {
        Text(title, style = Type.heading, modifier = Modifier.padding(start = 2.dp, bottom = 14.dp))
        Field(name, { name = it }, "Name", Modifier.fillMaxWidth(), focus = focus, onDone = ::save)
        Pill(action, ::save, Modifier.fillMaxWidth().padding(top = 12.dp), solid = true)
    }
}

@Composable
private fun ColumnScope.SleepSheet(model: AppModel) {
    val state by SleepTimer.state.collectAsState()
    Text("Sleep timer", style = Type.heading, modifier = Modifier.padding(start = 22.dp, bottom = 4.dp))
    val status = when (val s = state) {
        is SleepTimer.State.At -> "Stops at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.endsAt))}"
        SleepTimer.State.EndOfTrack -> "Stops when this song ends"
        SleepTimer.State.Off -> "Off"
    }
    Text(status, style = Type.small, color = if (state == SleepTimer.State.Off) p.muted else p.accent, modifier = Modifier.padding(start = 22.dp, bottom = 8.dp))
    listOf(15, 30, 45, 60, 90).forEach { minutes ->
        Row(Modifier.fillMaxWidth().clickable { SleepTimer.minutes(minutes); model.sheet = null }.padding(horizontal = 22.dp, vertical = 15.dp)) {
            Text(if (minutes < 60) "$minutes minutes" else if (minutes == 60) "1 hour" else "1 hour 30 minutes")
        }
    }
    Row(Modifier.fillMaxWidth().clickable { SleepTimer.endOfTrack(); model.sheet = null }.padding(horizontal = 22.dp, vertical = 15.dp)) { Text("End of this song") }
    if (state != SleepTimer.State.Off) {
        Row(Modifier.fillMaxWidth().clickable { SleepTimer.cancel(); model.sheet = null }.padding(horizontal = 22.dp, vertical = 15.dp)) { Text("Turn off", color = p.accent) }
    }
}
