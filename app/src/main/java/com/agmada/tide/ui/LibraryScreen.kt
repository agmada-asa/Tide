@file:OptIn(ExperimentalFoundationApi::class)

package com.agmada.tide.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agmada.tide.data.Album
import com.agmada.tide.data.Artist
import com.agmada.tide.data.Library
import com.agmada.tide.data.PlaylistView
import com.agmada.tide.data.Tags

enum class Tab { Songs, Albums, Artists, Playlists, Folders }

enum class Sort(val label: String) { Title("A–Z"), Artist("Artist"), Added("Recently added"), Year("Year"), Count("Most songs") }

private val sortsFor = mapOf(
    Tab.Songs to listOf(Sort.Title, Sort.Artist, Sort.Added),
    Tab.Albums to listOf(Sort.Title, Sort.Artist, Sort.Year, Sort.Added),
    Tab.Artists to listOf(Sort.Title, Sort.Count),
)

@Composable
fun LibraryScreen(model: AppModel, library: Library?, playlists: List<PlaylistView>, now: NowState) {
    // Tab, sort and search live in the model so they're still there after visiting an album.
    var tab by model::libraryTab
    var query by model::libraryQuery
    var sorts by model::librarySorts
    var sortOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var lastTab by rememberSaveable { mutableStateOf(tab) }
    LaunchedEffect(tab) { if (tab != lastTab && listState.firstVisibleItemIndex > 3) listState.scrollToItem(3); lastTab = tab }

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 110.dp)) {
        item(key = "header") {
            Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Library", style = Type.display, modifier = Modifier.weight(1f))
                sortsFor[tab]?.let { options ->
                    Box {
                        IconButton(Glyphs.Sort, { sortOpen = true })
                        DropdownMenu(
                            sortOpen, { sortOpen = false },
                            shape = RoundedCornerShape(20.dp), containerColor = p.surface,
                        ) {
                            options.forEach { option ->
                                val selected = sorts[tab] == option
                                Row(
                                    Modifier.width(200.dp).clickable { sorts = sorts + (tab to option); sortOpen = false }.padding(horizontal = 18.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(option.label, color = if (selected) p.fg else p.soft, modifier = Modifier.weight(1f))
                                    if (selected) Glyph(Glyphs.Check, p.accent, 18.dp)
                                }
                            }
                        }
                    }
                }
                IconButton(Glyphs.Settings, { model.go(Route.Settings) })
            }
        }
        item(key = "search") {
            Field(
                query, { query = it }, "Songs, artists, albums",
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), glyph = Glyphs.Search,
                trailing = { if (query.isNotEmpty()) IconButton(Glyphs.Close, { query = "" }, tint = p.muted, size = 36.dp, iconSize = 18.dp) },
            )
        }
        if (library == null) {
            item { Empty("Reading your music…", null) }
            return@LazyColumn
        }
        if (query.isNotBlank()) {
            searchResults(model, library, playlists, query, now)
            return@LazyColumn
        }
        item(key = "chips") {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) {
                items(Tab.entries) { t -> Chip(t.name, t == tab, { tab = t }) }
            }
        }
        if (library.songs.isEmpty()) {
            item { Empty("No music on this phone yet", "Tide shows every song Android can see. Short clips and hidden folders are left out; change that in Settings.") }
            return@LazyColumn
        }
        when (tab) {
            Tab.Songs -> songs(model, library, sorts.getValue(Tab.Songs), now)
            Tab.Albums -> albums(model, library, sorts.getValue(Tab.Albums))
            Tab.Artists -> artists(model, library, sorts.getValue(Tab.Artists))
            Tab.Playlists -> playlists(model, playlists)
            Tab.Folders -> folders(model, library)
        }
    }
}

@Composable
private fun Empty(title: String, body: String?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Glyph(Glyphs.Note, p.muted, 36.dp)
        Text(title, style = Type.heading, modifier = Modifier.padding(top = 16.dp))
        body?.let { Text(it, color = p.muted, style = Type.small, modifier = Modifier.padding(top = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

private fun LazyListScope.songs(model: AppModel, library: Library, sort: Sort, now: NowState) {
    val sorted = when (sort) {
        Sort.Artist -> library.songs.sortedWith(compareBy({ Library.sortKey(it.artistLine) }, { Library.sortKey(it.album) }, { it.disc }, { it.track }))
        Sort.Added -> library.songs.sortedByDescending { it.dateAdded }
        else -> library.songs
    }
    item(key = "shuffle") {
        Row(
            Modifier.fillMaxWidth().clickable { model.play(sorted, shuffle = true, from = "Library") }.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(p.surface), contentAlignment = Alignment.Center) { Glyph(Glyphs.Shuffle, p.fg, 18.dp) }
            Spacer(Modifier.width(10.dp))
            Text("Shuffle ${count(sorted.size, "song")}", color = p.soft, style = Type.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
            Text(sort.label, color = p.muted, style = Type.small)
        }
    }
    items(sorted, key = { it.id }) { song ->
        SongRow(
            song, now.song?.id == song.id, now.isPlaying,
            onClick = { model.play(sorted, sorted.indexOf(song), from = "Library") },
            onMore = { model.sheet = Sheet.SongMenu(song) },
        )
    }
}

private fun LazyListScope.albums(model: AppModel, library: Library, sort: Sort) {
    val sorted = when (sort) {
        Sort.Artist -> library.albums.sortedWith(compareBy({ Library.sortKey(it.artist) }, { -it.year }))
        Sort.Year -> library.albums.sortedByDescending { it.year }
        Sort.Added -> library.albums.sortedByDescending { it.dateAdded }
        else -> library.albums
    }
    if (sort == Sort.Title && library.albums.size > 8) {
        item(key = "recent-label") { SectionLabel("Recently added") }
        item(key = "recent") {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(library.albums.sortedByDescending { it.dateAdded }.take(10), key = { it.key }) { album ->
                    AlbumTile(album, Modifier.width(140.dp)) { model.go(Route.Album(album.key)) }
                }
            }
        }
        item(key = "all-label") { SectionLabel("All albums") }
    }
    items(sorted.chunked(2), key = { row -> row.first().key }) { row ->
        Row(Modifier.padding(horizontal = 20.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { album -> AlbumTile(album, Modifier.weight(1f)) { model.go(Route.Album(album.key)) } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun AlbumTile(album: Album, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onClick)) {
        Art(album.cover, Modifier.fillMaxWidth().aspectRatio(1f), Shape.tile)
        Text(album.title, style = Type.body.copy(fontSize = Type.body.fontSize * 0.94f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(if (album.isSingle) "Single · ${album.artist}" else album.artist, style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun LazyListScope.artists(model: AppModel, library: Library, sort: Sort) {
    val sorted = if (sort == Sort.Count) library.artists.sortedByDescending { it.songs.size } else library.artists
    item { Spacer(Modifier.height(6.dp)) }
    items(sorted, key = { it.key }) { artist -> ArtistRow(artist) { model.go(Route.Artist(artist.key)) } }
}

@Composable
private fun ArtistRow(artist: Artist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(artist.albums.firstOrNull()?.cover ?: artist.songs.first(), Modifier.size(52.dp), radius = 26.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(artist.albums.count { !it.isSingle }.takeIf { it > 0 }?.let { count(it, "album") }, count(artist.songs.size, "song")).joinToString(" · "),
                style = Type.small, color = p.muted,
            )
        }
    }
}

private fun LazyListScope.playlists(model: AppModel, playlists: List<PlaylistView>) {
    item(key = "playlist-actions") {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("New playlist", { model.sheet = Sheet.NewPlaylist }, Modifier.weight(1f), glyph = Glyphs.Add)
            Pill("From Spotify", { model.go(Route.Import()) }, Modifier.weight(1f), glyph = Glyphs.Link)
        }
    }
    items(playlists, key = { it.row.id }) { list -> PlaylistRowView(list) { model.go(Route.Playlist(list.row.id)) } }
}

@Composable
fun PlaylistRowView(list: PlaylistView, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (list.isLiked) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(Shape.art)).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Ocean.light, Ocean.deep))), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Heart, androidx.compose.ui.graphics.Color.White, 22.dp, filled = true)
            }
        } else {
            RemoteArt(list.row.coverUrl, list.songs.firstOrNull(), "playlist:${list.row.id}", Modifier.size(52.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(list.row.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val missing = if (list.missing > 0) " · ${list.missing} not on phone" else ""
            Text(count(list.entries.size, "song") + missing, style = Type.small, color = p.muted, maxLines = 1)
        }
    }
}

private fun LazyListScope.folders(model: AppModel, library: Library) {
    item { Spacer(Modifier.height(6.dp)) }
    items(library.folders, key = { it.path }) { folder ->
        Row(Modifier.fillMaxWidth().clickable { model.go(Route.Folder(folder.path)) }.padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(Shape.art)).background(p.surface), contentAlignment = Alignment.Center) { Glyph(Glyphs.Folder, p.soft, 22.dp) }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${count(folder.songs.size, "song")} · ${folder.path.trimEnd('/')}", style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(Glyphs.Hide, {
                model.repo.hideFolder(folder.path)
                model.toast = "Hid ${folder.name}. Show it again in Settings."
            }, tint = p.muted, size = 36.dp, iconSize = 20.dp)
        }
    }
}

private fun LazyListScope.searchResults(model: AppModel, library: Library, playlists: List<PlaylistView>, query: String, now: NowState) {
    val q = Tags.key(query)
    val songs = library.songs.filter { Tags.key(it.title).contains(q) || Tags.key(it.artistLine).contains(q) || Tags.key(it.album).contains(q) }
    val artists = library.artists.filter { Tags.key(it.name).contains(q) }.take(6)
    val albums = library.albums.filter { !it.isSingle && (Tags.key(it.title).contains(q) || Tags.key(it.artist).contains(q)) }.take(10)
    val lists = playlists.filter { Tags.key(it.row.name).contains(q) }
    if (songs.isEmpty() && artists.isEmpty() && albums.isEmpty() && lists.isEmpty()) {
        item { Empty("Nothing matches \"$query\"", null) }
        return
    }
    if (artists.isNotEmpty()) {
        item { SectionLabel("Artists") }
        items(artists, key = { "a:" + it.key }) { ArtistRow(it) { model.go(Route.Artist(it.key)) } }
    }
    if (albums.isNotEmpty()) {
        item { SectionLabel("Albums") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(albums, key = { it.key }) { album -> AlbumTile(album, Modifier.width(130.dp)) { model.go(Route.Album(album.key)) } }
            }
        }
    }
    if (lists.isNotEmpty()) {
        item { SectionLabel("Playlists") }
        items(lists, key = { "p:" + it.row.id }) { PlaylistRowView(it) { model.go(Route.Playlist(it.row.id)) } }
    }
    if (songs.isNotEmpty()) {
        item { SectionLabel("Songs") }
        items(songs.take(60), key = { "s:" + it.id }) { song ->
            SongRow(song, now.song?.id == song.id, now.isPlaying, onClick = { model.play(listOf(song) + songs.filter { it.id != song.id }.take(49), from = "Search") }, onMore = { model.sheet = Sheet.SongMenu(song) })
        }
    }
}

/** Ocean's logo gradient. */
object Ocean {
    val light = androidx.compose.ui.graphics.Color(0xFFD3FBFF)
    val deep = androidx.compose.ui.graphics.Color(0xFF0088FF)
}

