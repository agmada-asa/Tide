package com.agmada.tide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agmada.tide.data.Library
import com.agmada.tide.importer.AlbumFinder
import com.agmada.tide.importer.FoundAlbum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * "!!!" is a playlist a download tool saved as one album. This page keeps it as a playlist and
 * looks up which album each song is really from, then shows the result before anything is saved.
 */
@Composable
fun SortOutScreen(model: AppModel, library: Library, albumKey: String) {
    val album = remember(albumKey) { library.album(albumKey) } ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<Map<Long, FoundAlbum?>?>(null) }
    var looking by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }

    fun lookUp() {
        looking = true
        scope.launch {
            val finder = AlbumFinder(context, ArtCache.http)
            val gate = Semaphore(4)
            val results = withContext(Dispatchers.IO) {
                album.songs.map { song ->
                    async { gate.withPermit { (song.id to finder.find(song)).also { withContext(Dispatchers.Main) { done++ } } } }
                }.awaitAll().toMap()
            }
            found = results
            looking = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 200.dp)) {
            item {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { IconButton(Glyphs.Back, { model.back() }, iconSize = 24.dp) }
                    Text("Sort out “${album.title}”", style = Type.display, modifier = Modifier.padding(horizontal = 20.dp))
                    Text(
                        "These ${count(album.songs.size, "song")} are tagged as one album, but they're a playlist. " +
                            "Tide will keep them together as a playlist called “${album.title}”, in this order, and put each song on the album it's really from.",
                        color = p.soft, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    )
                    Text(
                        "Albums are looked up on Spotify, using the link many download tools leave in the file, or on Apple Music by title and artist. Only Tide sees the change; the files stay as they are.",
                        style = Type.small, color = p.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
                    )
                }
            }
            found?.let { results ->
                val hits = results.values.count { it != null }
                item {
                    Text(
                        "Found albums for $hits of ${results.size}." + if (hits < results.size) " The rest will show as singles." else "",
                        style = Type.body, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
            items(album.songs, key = { it.id }) { song ->
                val result = found?.get(song.id)
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(song, Modifier.size(46.dp), 11.dp)
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val line = when {
                            found == null -> song.artistLine
                            result == null -> "No album found · stays a single"
                            else -> "${result.album} · ${result.artists.joinToString(", ")}"
                        }
                        Text(line, style = Type.small, color = if (found != null && result != null) p.accent else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg, p.bg)))
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 90.dp),
        ) {
            when {
                looking -> {
                    Text("Looked up $done of ${album.songs.size}", style = Type.small, color = p.muted, modifier = Modifier.padding(bottom = 8.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.faint)) {
                        Box(Modifier.fillMaxWidth(done / album.songs.size.toFloat()).height(4.dp).clip(CircleShape).background(p.fg))
                    }
                }
                found == null -> Pill("Find their albums", ::lookUp, Modifier.fillMaxWidth(), glyph = Glyphs.Search, solid = true)
                else -> Pill("Save as a playlist", {
                    model.repo.unpackAlbum(album, found!!) { id ->
                        model.stack.removeAll { it is Route.SortOut || it == Route.Album(album.key) }
                        model.go(Route.Playlist(id))
                        model.toast = "“${album.title}” is a playlist now"
                    }
                }, Modifier.fillMaxWidth(), glyph = Glyphs.Check, solid = true)
            }
        }
    }
}
