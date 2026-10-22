package com.agmada.tide.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agmada.tide.data.Library
import com.agmada.tide.data.PlaylistView
import com.agmada.tide.data.Song

@Composable
private fun TopBar(model: AppModel, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Glyphs.Back, { model.back() }, iconSize = 24.dp)
        Spacer(Modifier.weight(1f))
        onMore?.let { IconButton(Glyphs.More, it) }
    }
}

/** Cover, title, a line or two under it, then Play and Shuffle: the top of every collection page. */
private fun LazyListScope.header(
    model: AppModel,
    songs: List<Song>,
    from: String,
    title: String,
    subtitle: String?,
    meta: String,
    /** Names in [subtitle] that open something when tapped, e.g. each artist of an album. */
    links: Map<String, () -> Unit> = emptyMap(),
    onMore: (() -> Unit)? = null,
    art: @Composable () -> Unit,
) {
    item(key = "top") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            TopBar(model, onMore)
            Spacer(Modifier.height(4.dp))
            art()
            Text(title, style = Type.title.copy(fontSize = Type.title.fontSize * 1.08f), textAlign = TextAlign.Center, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp))
            subtitle?.let {
                val text = buildAnnotatedString {
                    append(it)
                    for ((name, open) in links) {
                        val at = it.indexOf(name).takeIf { i -> i >= 0 } ?: continue
                        addLink(LinkAnnotation.Clickable(name) { open() }, at, at + name.length)
                    }
                }
                Text(text, color = p.soft, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp))
            }
            Text(meta, style = Type.small, color = p.muted, modifier = Modifier.padding(top = 4.dp))
            if (songs.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Play", { model.play(songs, from = from) }, Modifier.weight(1f), glyph = Glyphs.Play, solid = true)
                    Pill("Shuffle", { model.play(songs, shuffle = true, from = from) }, Modifier.weight(1f), glyph = Glyphs.Shuffle)
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(model: AppModel, library: Library, key: String, now: NowState) {
    val album = library.album(key) ?: return Gone(model)
    val discs = album.songs.map { it.disc }.distinct().size > 1
    val from = "Album · ${album.title}"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        header(
            model, album.songs, from, album.title, album.artist,
            listOfNotNull(album.year.takeIf { it > 0 }?.toString(), count(album.songs.size, "song"), total(album.durationMs)).joinToString(" · "),
            links = album.artists.mapNotNull { name -> library.artistFor(name)?.let { a -> name to { model.go(Route.Artist(a.key)) } } }.toMap(),
            onMore = { model.sheet = Sheet.AlbumMenu(album) },
        ) { Art(album.cover, Modifier.size(232.dp), Shape.radius) }
        if (album.looksLikePlaylist) {
            item(key = "playlist-hint") {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface)
                        .clickable { model.go(Route.SortOut(album.key)) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Looks like a playlist, not an album", style = Type.body)
                        Text(
                            "${count(album.songs.size, "song")} by ${count(album.songs.map { it.artists.first() }.distinct().size, "artist")}. Tide can keep them as a playlist and find each song's real album.",
                            style = Type.small, color = p.muted, modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Glyph(Glyphs.Forward, p.muted, 20.dp)
                }
            }
        }
        var lastDisc = -1
        album.songs.forEachIndexed { i, song ->
            if (discs && song.disc != lastDisc) {
                lastDisc = song.disc
                item(key = "disc${song.disc}") { SectionLabel("Disc ${song.disc}") }
            }
            item(key = song.id) {
                val current = now.song?.id == song.id
                SongRow(
                    song, current, now.isPlaying,
                    onClick = { model.play(album.songs, i, from = from) },
                    onMore = { model.sheet = Sheet.SongMenu(song) },
                    subtitle = song.artistLine,
                    leading = {
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                            Text(if (song.track > 0) song.track.toString() else "–", style = Type.small.copy(fontSize = Type.body.fontSize * 0.9f), color = p.muted)
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun ArtistScreen(model: AppModel, library: Library, key: String, now: NowState) {
    val artist = library.artist(key) ?: return Gone(model)
    val songs = artist.songs.sortedWith(compareBy({ -(it.year) }, { Library.sortKey(it.album) }, { it.disc }, { it.track }))
    val from = "Artist · ${artist.name}"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        header(
            model, songs, from, artist.name, null,
            listOfNotNull(artist.albums.count { !it.isSingle }.takeIf { it > 0 }?.let { count(it, "album") }, count(songs.size, "song")).joinToString(" · "),
        ) { Art(artist.albums.firstOrNull()?.cover ?: songs.first(), Modifier.size(200.dp), radius = 100.dp) }
        val (albums, singles) = artist.albums.partition { !it.isSingle }
        for ((label, list) in listOf("Albums" to albums, "Singles" to singles, "Appears on" to artist.appearsOn)) {
            if (list.isEmpty()) continue
            item(key = "label:$label") { SectionLabel(label) }
            item(key = "row:$label") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.key }) { album -> AlbumTile(album, Modifier.width(140.dp)) { model.go(Route.Album(album.key)) } }
                }
            }
        }
        item { SectionLabel("Songs") }
        itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
            SongRow(song, now.song?.id == song.id, now.isPlaying, onClick = { model.play(songs, i, from = from) }, onMore = { model.sheet = Sheet.SongMenu(song) }, subtitle = song.album)
        }
    }
}

@Composable
fun PlaylistScreen(model: AppModel, list: PlaylistView, now: NowState) {
    val context = LocalContext.current
    val songs = list.songs
    val from = "Playlist · ${list.row.name}"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        header(
            model, songs, from, list.row.name, list.row.subtitle,
            listOfNotNull(count(list.entries.size, "song"), songs.sumOf { it.durationMs }.takeIf { it > 0 }?.let(::total)).joinToString(" · "),
            onMore = if (list.isLiked) null else ({ model.sheet = Sheet.PlaylistMenu(list.row.id) }),
        ) {
            if (list.isLiked) {
                Box(Modifier.size(212.dp).clip(RoundedCornerShape(Shape.radius)).background(Brush.linearGradient(listOf(Ocean.light, Ocean.deep))), contentAlignment = Alignment.Center) {
                    Glyph(Glyphs.Heart, Color.White, 64.dp, filled = true)
                }
            } else {
                RemoteArt(list.row.coverUrl, songs.firstOrNull(), "playlist:${list.row.id}", Modifier.size(212.dp), Shape.radius)
            }
        }
        if (list.missing > 0) {
            item(key = "missing") {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.surface).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${count(list.missing, "song")} not on this phone", style = Type.body)
                        Text("They fill in as soon as matching files turn up, whatever they're called.", style = Type.small, color = p.muted, modifier = Modifier.padding(top = 3.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    IconButton(Glyphs.Clipboard, {
                        val text = list.entries.filter { it.song == null }.joinToString("\n") { "${it.item.artist} - ${it.item.title}" }
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Missing songs", text))
                        model.toast = "Copied the missing songs"
                    }, background = p.faint, size = 42.dp, iconSize = 20.dp)
                }
            }
        }
        if (list.entries.isEmpty()) {
            item {
                Text(
                    if (list.isLiked) "Tap the heart on Now playing to keep a song here." else "Add songs from any song's menu.",
                    color = p.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp),
                )
            }
        }
        items(list.entries, key = { it.item.id }) { entry ->
            val song = entry.song
            if (song != null) {
                SongRow(
                    song, now.song?.id == song.id, now.isPlaying,
                    onClick = { model.play(songs, songs.indexOf(song), from = from) },
                    onMore = { model.sheet = Sheet.SongMenu(song, playlistItemId = entry.item.id) },
                )
            } else {
                MissingRow(entry.item.title, entry.item.artist, trailing = {
                    IconButton(Glyphs.Close, { model.repo.removeEntry(entry.item.id) }, tint = p.muted, size = 36.dp, iconSize = 18.dp)
                })
            }
        }
    }
}

@Composable
fun FolderScreen(model: AppModel, library: Library, path: String, now: NowState) {
    val folder = library.folders.firstOrNull { it.path == path } ?: return Gone(model)
    val from = "Folder · ${folder.name}"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        header(model, folder.songs, from, folder.name, folder.path.trimEnd('/'), count(folder.songs.size, "song")) {
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(Shape.radius)).background(p.surface), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Folder, p.soft, 54.dp, stroke = 1.3f)
            }
        }
        itemsIndexed(folder.songs, key = { _, s -> s.id }) { i, song ->
            SongRow(song, now.song?.id == song.id, now.isPlaying, onClick = { model.play(folder.songs, i, from = from) }, onMore = { model.sheet = Sheet.SongMenu(song) }, subtitle = song.fileName)
        }
    }
}

/** The page's subject vanished (a rescan, a hidden folder): step back rather than show nothing. */
@Composable
private fun Gone(model: AppModel) {
    androidx.compose.runtime.LaunchedEffect(Unit) { model.back() }
}
