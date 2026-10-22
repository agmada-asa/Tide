package com.agmada.tide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agmada.tide.data.Library

@Composable
fun SettingsScreen(model: AppModel, library: Library?) {
    val hidden by model.repo.hiddenFolders.collectAsState()
    val skipShort by model.repo.skipShort.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { IconButton(Glyphs.Back, { model.back() }, iconSize = 24.dp) }
                Text("Settings", style = Type.display, modifier = Modifier.padding(horizontal = 20.dp))
                library?.let {
                    Text(
                        "${count(it.songs.size, "song")} · ${count(it.albums.count { a -> !a.isSingle }, "album")} · ${count(it.artists.size, "artist")}",
                        color = p.muted, style = Type.small, modifier = Modifier.padding(start = 20.dp, top = 6.dp),
                    )
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().clickable { model.repo.setSkipShort(!skipShort) }.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Leave out short clips")
                    Text("Audio under 30 seconds: voice notes, ringtones, WhatsApp clips.", style = Type.small, color = p.muted)
                }
                Spacer(Modifier.width(12.dp))
                Switch(skipShort)
            }
        }
        item { SectionLabel("Hidden folders") }
        if (hidden.isEmpty()) {
            item { Text("None. Hide a folder from Library › Folders.", color = p.muted, style = Type.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        }
        items(hidden.sorted(), key = { it }) { path ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Glyphs.Hide, p.muted, 20.dp)
                Spacer(Modifier.width(14.dp))
                Text(path.trimEnd('/'), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.height(34.dp).clip(CircleShape).background(p.surface).clickable { model.repo.showFolder(path) }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text("Show", style = Type.small)
                }
            }
        }
        item { SectionLabel("Library") }
        item {
            Row(Modifier.fillMaxWidth().clickable { model.go(Route.Tidy) }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Glyphs.Folder, p.fg, 20.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Tidy up")
                    Text("Remove duplicates, move everything into Music, save fixes into files.", style = Type.small, color = p.muted)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().clickable { model.repo.rescan(); model.toast = "Reading your music again" }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Glyphs.Refresh, p.fg, 20.dp)
                Spacer(Modifier.width(14.dp))
                Text("Rescan now")
            }
        }
    }
}

/** Ocean-style switch: a pill that fills when on. */
@Composable
private fun Switch(on: Boolean) {
    Box(Modifier.size(width = 46.dp, height = 28.dp).clip(CircleShape).background(if (on) p.fg else p.faint).padding(3.dp), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (on) p.bg else p.muted))
    }
}
