package com.agmada.tide.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.agmada.tide.data.Song
import com.agmada.tide.playback.SleepTimer
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The cover's colour washed into the top of the screen, as in the mockup. */
@Composable
private fun rememberTint(song: Song?): Color {
    val context = LocalContext.current
    var tint by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(song?.id) {
        tint = song?.let { s -> ArtCache.song(context, s.id, 900)?.let { ArtCache.tint(it) } ?: placeholderTint(s.albumKey) }
    }
    val target = tint ?: p.bg
    return animateColorAsState(target, tween(600), label = "tint").value
}

fun formatOf(mime: String?): String? = when {
    mime == null -> null
    "flac" in mime -> "FLAC"
    "mpeg" in mime || "mp3" in mime -> "MP3"
    "mp4" in mime || "m4a" in mime || "aac" in mime -> "AAC"
    "ogg" in mime || "opus" in mime -> "OGG"
    "wav" in mime -> "WAV"
    "alac" in mime -> "ALAC"
    else -> null
}

@Composable
fun NowPlaying(model: AppModel, now: NowState, liked: Boolean) {
    val song = now.song ?: return
    val tint = rememberTint(song)
    val bg = p.bg
    val brush = if (p.dark) {
        Brush.verticalGradient(0f to lerp(bg, tint, 0.42f), 0.38f to lerp(bg, tint, 0.16f), 0.72f to bg)
    } else {
        Brush.verticalGradient(0f to lerp(bg, tint, 0.24f), 0.62f to bg)
    }
    var drag by remember { mutableFloatStateOf(0f) }
    val sleep by SleepTimer.state.collectAsState()

    Box(
        Modifier.fillMaxSize()
            .offset { IntOffset(0, drag.coerceAtLeast(0f).roundToInt()) }
            .background(bg).background(brush)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag > 220) model.nowPlayingOpen = false; drag = 0f },
                    onDragCancel = { drag = 0f },
                ) { _, delta -> drag += delta }
            },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Glyphs.Down, { model.nowPlayingOpen = false }, iconSize = 26.dp)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    now.source?.let { source ->
                        val (kind, name) = source.split(" · ", limit = 2).let { it.first() to it.getOrNull(1) }
                        Text("Playing from ${kind.lowercase()}", style = Type.tiny, color = p.muted)
                        name?.let { Text(it, style = Type.small.copy(fontSize = Type.body.fontSize * 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
                IconButton(Glyphs.More, { model.sheet = Sheet.SongMenu(song) })
            }
            CoverPager(model, now, song, Modifier.weight(1f).fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 14.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        song.artistLine, color = p.soft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp).clickable {
                            model.repo.library.value?.artistFor(song.artists.first())?.let { model.go(Route.Artist(it.key)) }
                        },
                    )
                }
                IconButton(Glyphs.Heart, { model.repo.toggleLike(song) }, tint = if (liked) p.accent else p.fg, iconSize = 24.dp, filled = liked)
            }
            SeekBar(model, now, song)
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(Glyphs.Shuffle, { model.setShuffle(!now.shuffle) }, tint = if (now.shuffle) p.accent else p.soft)
                IconButton(Glyphs.Prev, { model.previous() }, size = 56.dp, iconSize = 30.dp)
                Box(Modifier.size(76.dp).clip(CircleShape).background(p.fg).clickable { model.toggle() }, contentAlignment = Alignment.Center) {
                    Glyph(if (now.isPlaying) Glyphs.Pause else Glyphs.Play, p.bg, 30.dp)
                }
                IconButton(Glyphs.Next, { model.next() }, size = 56.dp, iconSize = 30.dp)
                Box {
                    IconButton(Glyphs.Repeat, { model.cycleRepeat() }, tint = if (now.repeat != Player.REPEAT_MODE_OFF) p.accent else p.soft)
                    if (now.repeat == Player.REPEAT_MODE_ONE) {
                        Text("1", style = Type.tiny.copy(fontSize = Type.tiny.fontSize * 0.8f), color = p.accent, modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 4.dp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(Glyphs.Timer, { model.sheet = Sheet.Sleep }, tint = if (sleep != SleepTimer.State.Off) p.accent else p.soft, iconSize = 21.dp)
                IconButton(Glyphs.Addlist, { model.sheet = Sheet.AddToPlaylist(listOf(song)) }, tint = p.soft, iconSize = 21.dp)
                IconButton(Glyphs.Queue, { model.queueOpen = true }, tint = p.soft, iconSize = 21.dp)
            }
        }
    }
}

/**
 * The cover with the previous and next ones either side, as on Spotify: dragging shows the next
 * cover sliding in, and letting go past a third of the way finishes the slide and changes song.
 */
@Composable
private fun CoverPager(model: AppModel, now: NowState, song: Song, modifier: Modifier) {
    // A fresh offset for each song, so the row is back in place the same frame the song changes.
    val offset = remember(song.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val neighbours by rememberUpdatedState(now.previous to now.next)
    val current by rememberUpdatedState(offset)
    BoxWithConstraints(modifier.padding(horizontal = 22.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        val gap = 22.dp
        val step = with(LocalDensity.current) { (side + gap).toPx() }
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                var settling: Job? = null
                detectHorizontalDragGestures(
                    onDragStart = { settling?.cancel() },
                    onDragEnd = {
                        val (previous, next) = neighbours
                        val value = current.value
                        val forward = value < 0
                        val target = if (forward) next else previous
                        settling = scope.launch {
                            if (target != null && kotlin.math.abs(value) > step / 3) {
                                current.animateTo(if (forward) -step else step, tween(220))
                                model.skipTo(forward)
                            } else {
                                current.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                            }
                        }
                    },
                    onDragCancel = { scope.launch { current.animateTo(0f) } },
                ) { change, delta ->
                    change.consume()
                    val (previous, next) = neighbours
                    // Nothing on that side: let it give a little, then stop.
                    val edge = (current.value + delta < 0 && next == null) || (current.value + delta > 0 && previous == null)
                    scope.launch { current.snapTo(current.value + if (edge) delta * 0.25f else delta) }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            listOf(-1 to now.previous, 0 to song, 1 to now.next).forEach { (slot, s) ->
                if (s == null) return@forEach
                key(slot, s.id) {
                    Art(
                        s,
                        Modifier.size(side)
                            .graphicsLayer { translationX = offset.value + slot * step }
                            .shadow(30.dp, RoundedCornerShape(Shape.radius), ambientColor = Color.Black, spotColor = Color.Black),
                        Shape.radius,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekBar(model: AppModel, now: NowState, song: Song) {
    var position by remember { mutableLongStateOf(model.position()) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(song.id, now.isPlaying) {
        while (true) {
            if (scrub == null) position = model.position()
            if (!now.isPlaying) break
            delay(250)
        }
    }
    val total = now.durationMs.coerceAtLeast(1)
    val fraction = scrub ?: (position.toFloat() / total).coerceIn(0f, 1f)
    Column(Modifier.padding(horizontal = 24.dp).padding(top = 18.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(24.dp)
                .pointerInput(total) {
                    detectTapGestures { model.seek((it.x / size.width * total).toLong()); position = (it.x / size.width * total).toLong() }
                }
                .pointerInput(total) {
                    detectDragGestures(
                        onDragStart = { scrub = (it.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { scrub?.let { f -> model.seek((f * total).toLong()); position = (f * total).toLong() }; scrub = null },
                        onDragCancel = { scrub = null },
                    ) { change, _ -> scrub = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val width = maxWidth
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.faint))
            Box(Modifier.width(width * fraction).height(4.dp).clip(CircleShape).background(p.fg))
            val thumb = if (scrub != null) 18.dp else 14.dp
            Box(Modifier.offset(x = width * fraction - thumb / 2).size(thumb).clip(CircleShape).background(p.fg))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(duration((fraction * total).toLong()), style = Type.tiny, color = p.muted)
            Text(formatOf(song.mime) ?: "", style = Type.tiny, color = p.muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(duration(now.durationMs), style = Type.tiny, color = p.muted)
        }
    }
}

/** Up next: tap to jump, swipe away to remove, drag the handle to reorder (not while shuffled). */
@Composable
fun QueueSheet(model: AppModel, now: NowState) {
    val song = now.song ?: return
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
    var dragging by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(song, Modifier.size(52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artistLine, style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(if (now.isPlaying) Glyphs.Pause else Glyphs.Play, model::toggle)
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Up next", style = Type.heading, modifier = Modifier.weight(1f))
            IconButton(Glyphs.Shuffle, { model.setShuffle(!now.shuffle) }, tint = if (now.shuffle) p.accent else p.fg, background = p.faint, size = 36.dp, iconSize = 17.dp)
            Spacer(Modifier.width(6.dp))
            if (now.upNext.isNotEmpty()) {
                Box(Modifier.height(36.dp).clip(CircleShape).background(p.faint).clickable { model.clearQueue() }.padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
                    Text("Clear", style = Type.small.copy(fontSize = Type.body.fontSize * 0.9f))
                }
            }
        }
        val left = now.upNext.sumOf { it.song.durationMs }
        Text(
            listOfNotNull(now.source?.substringAfter(" · ")?.let { "From $it" }, count(now.upNext.size, "song"), if (left > 0) "${total(left)} left" else null).joinToString(" · "),
            style = Type.small, color = p.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 6.dp),
        )
        if (now.upNext.isEmpty()) {
            Text("Nothing after this song.", color = p.muted, modifier = Modifier.padding(20.dp))
        }
        LazyColumn(Modifier.fillMaxWidth().height(440.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            itemsIndexed(now.upNext, key = { _, item -> "${item.index}:${item.song.id}" }) { i, item ->
                val state = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.EndToStart) { model.removeFromQueue(item.index); true } else false
                })
                val isDragged = dragging == i
                Box(
                    Modifier.zIndex(if (isDragged) 1f else 0f)
                        .offset { IntOffset(0, if (isDragged) dragOffset.roundToInt() else 0) },
                ) {
                    SwipeToDismissBox(
                        state, enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(p.faint).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) { Glyph(Glyphs.Delete, p.soft, 20.dp) }
                        },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(rowHeight).background(if (isDragged) p.faint else p.surface)
                                .clickable { model.jumpTo(item.index) }.padding(start = 20.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Art(item.song, Modifier.size(44.dp), 11.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.song.artistLine, style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!now.shuffle) {
                                Box(
                                    Modifier.size(40.dp).pointerInput(item.index) {
                                        detectDragGestures(
                                            onDragStart = { dragging = i; dragOffset = 0f },
                                            onDragEnd = {
                                                val to = (i + (dragOffset / rowPx).roundToInt()).coerceIn(0, now.upNext.lastIndex)
                                                if (to != i) model.moveInQueue(item.index, now.upNext[to].index)
                                                dragging = -1; dragOffset = 0f
                                            },
                                            onDragCancel = { dragging = -1; dragOffset = 0f },
                                        ) { change, amount -> change.consume(); dragOffset += amount.y }
                                    },
                                    contentAlignment = Alignment.Center,
                                ) { Glyph(Glyphs.Menu, p.muted, 18.dp) }
                            }
                        }
                    }
                }
            }
        }
    }
}
