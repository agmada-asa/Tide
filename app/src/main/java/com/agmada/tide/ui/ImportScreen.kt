package com.agmada.tide.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agmada.tide.data.Library
import com.agmada.tide.data.Song
import com.agmada.tide.importer.RemoteCollection
import com.agmada.tide.importer.RemoteKind
import com.agmada.tide.importer.SpotifyImporter
import com.agmada.tide.importer.SpotifyLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface ImportState {
    data object Idle : ImportState
    data object Loading : ImportState
    data class Failed(val message: String) : ImportState
    data class Loaded(val remote: RemoteCollection) : ImportState
}

/**
 * Paste (or share from Spotify) a song, album or playlist link. Tide reads its track list from
 * Spotify's public embed page, matches it against the phone, and saves it as a playlist.
 */
@Composable
fun ImportScreen(model: AppModel, library: Library?, initial: String, now: NowState) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(initial) }
    var state by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    val importer = remember { SpotifyImporter(ArtCache.http) }
    val isLink = SpotifyLinks.parse(text) != null || SpotifyLinks.isShortLink(text)

    LaunchedEffect(text) {
        if (!isLink) { state = ImportState.Idle; return@LaunchedEffect }
        state = ImportState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { ImportState.Loaded(importer.fetch(text)) }
                .getOrElse { ImportState.Failed(if (it is java.io.IOException) "Couldn't reach Spotify. Check the connection and try again." else it.message ?: "That link didn't work.") }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 130.dp)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { IconButton(Glyphs.Back, { model.back() }, iconSize = 24.dp) }
                Text("From Spotify", style = Type.display, modifier = Modifier.padding(horizontal = 20.dp))
                Text(
                    "Paste a link to a song, album or playlist, or share one to Tide from Spotify. Songs you already have are matched however the files are named; the rest wait in the playlist until you add them.",
                    color = p.muted, style = Type.small, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
                Field(
                    text, { text = it }, "open.spotify.com/…", Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp), glyph = Glyphs.Link,
                    trailing = {
                        if (text.isEmpty()) {
                            Box(Modifier.padding(end = 2.dp)) {
                                Pill("Paste", {
                                    val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                                    text = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                }, Modifier.padding(vertical = 4.dp), solid = true)
                            }
                        } else {
                            IconButton(Glyphs.Close, { text = "" }, tint = p.muted, size = 36.dp, iconSize = 18.dp)
                        }
                    },
                )
            }
        }
        when (val s = state) {
            ImportState.Idle -> if (text.isNotBlank() && !isLink) item { Note("That doesn't look like a Spotify link.") }
            ImportState.Loading -> item { Note("Reading it from Spotify…") }
            is ImportState.Failed -> item { Note(s.message) }
            is ImportState.Loaded -> preview(model, library, s.remote, now)
        }
    }

    (state as? ImportState.Loaded)?.let { loaded ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Pill(
                if (loaded.remote.kind == RemoteKind.Track) "Add to a new playlist" else "Save as playlist",
                { model.repo.importCollection(loaded.remote) { id -> model.back(); model.go(Route.Playlist(id)) } },
                Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 92.dp).fillMaxWidth(),
                glyph = Glyphs.Add, solid = true,
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = p.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
}

private fun androidx.compose.foundation.lazy.LazyListScope.preview(model: AppModel, library: Library?, remote: RemoteCollection, now: NowState) {
    val matches: List<Pair<com.agmada.tide.importer.RemoteTrack, Song?>> = remote.tracks.map { t -> t to library?.match(t.title, t.artists.joinToString(", "), t.durationMs) }
    val found = matches.mapNotNull { it.second }
    item {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteArt(remote.coverUrl, found.firstOrNull(), remote.sourceUrl, Modifier.size(92.dp), 18.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(remote.kind.name, style = Type.tiny, color = p.muted)
                Text(remote.name, style = Type.heading, maxLines = 2, overflow = TextOverflow.Ellipsis)
                remote.subtitle?.let { Text(it, style = Type.small, color = p.soft, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(
                    "${found.size} of ${remote.tracks.size} on this phone",
                    style = Type.small, color = if (found.size == remote.tracks.size) p.accent else p.muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
    if (remote.truncated) item { Note("Spotify only shares the first 100 songs of a playlist without signing in, so that's what Tide can see.") }
    if (found.isNotEmpty()) {
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Play what's here", { model.play(found, from = "Playlist · ${remote.name}") }, Modifier.weight(1f), glyph = Glyphs.Play)
            }
        }
    }
    items(matches.withIndex().toList(), key = { it.index }) { (_, pair) ->
        val (track, song) = pair
        if (song != null) {
            SongRow(song, now.song?.id == song.id, now.isPlaying, onClick = { model.play(found, found.indexOf(song), from = "Playlist · ${remote.name}") }, onMore = { model.sheet = Sheet.SongMenu(song) })
        } else {
            MissingRow(track.title, track.artists.joinToString(", "))
        }
    }
}
