package com.agmada.tide.ui

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.agmada.tide.data.Layout
import com.agmada.tide.data.Library
import com.agmada.tide.data.Organiser
import com.agmada.tide.data.Song
import com.agmada.tide.data.Tags
import com.agmada.tide.data.Tidy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of whatever Tidy up is doing: "Moving files", 40 of 412. */
private data class Busy(val what: String, val done: Int, val total: Int)

/**
 * Tidy up: duplicates to the bin, every file into Music/, and Tide's fixes written into the files.
 * Each one asks Android first, and Android shows its own confirmation.
 */
@Composable
fun TidyScreen(model: AppModel, library: Library) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val edits by model.repo.edits.collectAsState()
    var layout by remember { mutableStateOf(Layout.ArtistAlbum) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    var showAllDuplicates by remember { mutableStateOf(false) }
    var afterApproval by remember { mutableStateOf<(() -> Unit)?>(null) }
    val approve = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) afterApproval?.invoke()
        afterApproval = null
    }
    fun ask(request: IntentSender, then: () -> Unit) {
        afterApproval = then
        approve.launch(IntentSenderRequest.Builder(request).build())
    }

    val duplicates = remember(library) { Organiser.duplicates(library) }
    val moves = remember(library, layout) { Organiser.plan(library, layout) }
    val playlists by model.repo.playlists.collectAsState()
    // Folders that are really playlists, less any already saved as one (like a sorted-out "!!!").
    val playlistFolders = remember(library, playlists) {
        val saved = playlists.map { Tags.key(it.row.name) }.toSet()
        Organiser.playlistFolders(library).filter { Tags.key(it.name) !in saved }
    }
    val fixed = remember(library, edits) { library.songs.filter { it.path in edits } }
    val (writable, notMp3) = fixed.partition { it.mime?.contains("mpeg") == true }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 130.dp)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) { IconButton(Glyphs.Back, { model.back() }, iconSize = 24.dp) }
                Text("Tidy up", style = Type.display, modifier = Modifier.padding(horizontal = 20.dp))
                Text(
                    "Android asks you before Tide changes any file, and says what's about to happen.",
                    color = p.muted, style = Type.small, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                )
            }
        }
        busy?.let { b ->
            item {
                Card {
                    Text("${b.what}… ${b.done} of ${b.total}", style = Type.body)
                    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(p.faint)) {
                        Box(Modifier.fillMaxWidth(if (b.total == 0) 0f else b.done / b.total.toFloat()).height(4.dp).clip(CircleShape).background(p.fg))
                    }
                }
            }
        }

        item {
            Card {
                Heading("Duplicates", if (duplicates.isEmpty()) "No song is on here twice." else "${count(duplicates.size, "song")} on here more than once. Tide keeps the best copy: the one on an album, then the biggest file.")
                (if (showAllDuplicates) duplicates else duplicates.take(4)).forEach { group -> DuplicateGroup(group) }
                if (duplicates.size > 4 && !showAllDuplicates) {
                    Text("Show all ${duplicates.size}", color = p.accent, style = Type.small, modifier = Modifier.padding(top = 10.dp).clickable { showAllDuplicates = true })
                }
                val extra = duplicates.flatMap { it.drop(1) }
                if (extra.isNotEmpty()) {
                    Action("Move ${count(extra.size, "copy", "copies")} to the bin", enabled = busy == null) {
                        ask(Tidy.binRequest(context, extra)) {
                            model.toast = "Moved ${count(extra.size, "copy", "copies")} to the bin"
                            model.repo.rescan()
                        }
                    }
                    Text("Deleted files stay in the bin for 30 days.", style = Type.tiny, color = p.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        item {
            Card {
                Heading(
                    "Put everything in Music",
                    if (moves.isEmpty()) "Every file is already where it should be." else "${count(moves.size, "file")} will move into Music/, named after what Tide shows, wherever they are now.",
                )
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Layout.entries.forEach { option -> Chip(option.label, option == layout, { layout = option }) }
                }
                moves.take(3).forEach { move ->
                    Column(Modifier.padding(top = 10.dp)) {
                        Text(move.song.path, style = Type.tiny, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("→ ${move.path}", style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (playlistFolders.isNotEmpty()) {
                    Text(
                        "${count(playlistFolders.size, "folder")} ${if (playlistFolders.size == 1) "looks like a playlist" else "look like playlists"} (${playlistFolders.take(3).joinToString(", ") { it.name }}${if (playlistFolders.size > 3) ", …" else ""}). " +
                            if (playlistFolders.size == 1) "It's saved as a playlist first, so nothing's lost when the files move." else "They're saved as playlists first, so nothing's lost when the files move.",
                        style = Type.small, color = p.soft, modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (moves.isNotEmpty()) {
                    Action("Move ${count(moves.size, "file")}", enabled = busy == null) {
                        ask(Tidy.writeRequest(context, moves.map { it.song })) {
                            scope.launch {
                                busy = Busy("Moving files", 0, moves.size)
                                val made = model.repo.saveFoldersAsPlaylists(playlistFolders)
                                val moved = withContext(Dispatchers.IO) {
                                    Tidy.move(context, model.repo, moves) { n -> busy = Busy("Moving files", n, moves.size) }
                                }
                                busy = null
                                model.repo.rescan()
                                model.toast = buildString {
                                    append("Moved ${count(moved, "file")}")
                                    if (made > 0) append(" and made ${count(made, "playlist")}")
                                    if (moved < moves.size) append(". ${moves.size - moved} couldn't move")
                                }
                            }
                        }
                    }
                    Text("The old folders are left empty. Delete them in My Files.", style = Type.tiny, color = p.muted, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        item {
            Card {
                Heading(
                    "Save Tide's fixes into the files",
                    if (fixed.isEmpty()) "Nothing to save. Fixes you make in Tide show up here." else
                        "${count(fixed.size, "song")} ${if (fixed.size == 1) "has" else "have"} fixes only Tide knows about, like albums you sorted out. Writing them into the files lets other apps show them too. The audio isn't touched.",
                )
                if (notMp3.isNotEmpty()) {
                    Text("${count(notMp3.size, "file")} ${if (notMp3.size == 1) "isn't an MP3" else "aren't MP3s"}; Tide keeps those fixes to itself.", style = Type.small, color = p.muted, modifier = Modifier.padding(top = 8.dp))
                }
                if (writable.isNotEmpty()) {
                    Action("Write to ${count(writable.size, "file")}", enabled = busy == null) {
                        ask(Tidy.writeRequest(context, writable)) {
                            scope.launch {
                                busy = Busy("Writing tags", 0, writable.size)
                                val written = withContext(Dispatchers.IO) {
                                    Tidy.writeTags(context, writable) { n -> busy = Busy("Writing tags", n, writable.size) }
                                }
                                model.repo.clearEdits(written)
                                busy = null
                                model.toast = if (written.size == writable.size) "Saved into ${count(written.size, "file")}" else "Saved into ${written.size} of ${writable.size} files"
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(Shape.radius)).background(p.surface).padding(18.dp), content = content)
}

@Composable
private fun Heading(title: String, body: String) {
    Text(title, style = Type.heading)
    Text(body, style = Type.small, color = p.muted, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Action(text: String, enabled: Boolean, onClick: () -> Unit) {
    Pill(text, { if (enabled) onClick() }, Modifier.fillMaxWidth().padding(top = 14.dp), solid = enabled)
}

@Composable
private fun DuplicateGroup(group: List<Song>) {
    Column(Modifier.padding(top = 12.dp)) {
        Text("${group.first().title} · ${group.first().artistLine}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        group.forEachIndexed { i, song ->
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (i == 0) Glyphs.Check else Glyphs.Delete, if (i == 0) p.accent else p.muted, 15.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${song.path} · ${"%.1f".format(song.size / 1_048_576.0)} MB",
                    style = Type.tiny, color = if (i == 0) p.soft else p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
