@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.agmada.tide.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agmada.tide.data.Song
import kotlin.math.roundToInt

fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

fun total(ms: Long): String {
    // Round up, so 45 seconds of music reads "1 min" rather than "0 min".
    val minutes = ((ms + 59_999) / 60_000).toInt()
    return if (minutes >= 60) "${minutes / 60} hr ${minutes % 60} min" else "$minutes min"
}

fun count(n: Int, one: String, many: String = one + "s") = "%,d %s".format(n, if (n == 1) one else many)

@Composable
fun IconButton(
    glyph: Glyphs,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = p.fg,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    background: Color = Color.Transparent,
    filled: Boolean = false,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Glyph(glyph, tint, iconSize, filled = filled) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(34.dp).clip(CircleShape).background(if (selected) p.fg else p.surface).clickable(onClick = onClick).padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.body.copy(fontSize = 14.sp), color = if (selected) p.bg else p.soft, maxLines = 1) }
}

@Composable
fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: Glyphs? = null, solid: Boolean = false) {
    Row(
        modifier.height(46.dp).clip(CircleShape).background(if (solid) p.fg else p.surface).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val color = if (solid) p.bg else p.fg
        glyph?.let { Glyph(it, color, 18.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, color = color, style = Type.body.copy(fontSize = 15.sp))
    }
}

/** Three bars that move while the song plays, shown beside the playing song. */
@Composable
fun Meter(playing: Boolean, color: Color = p.accent) {
    val transition = rememberInfiniteTransition(label = "meter")
    val heights = listOf(560, 420, 700).map { period ->
        transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(period), RepeatMode.Reverse), label = "bar")
    }
    Row(Modifier.height(14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        heights.forEachIndexed { i, h ->
            val still = listOf(0.45f, 0.95f, 0.65f)[i]
            Box(Modifier.width(3.dp).height(14.dp * (if (playing) h.value else still)).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    subtitle: String = "${song.artistLine} · ${duration(song.durationMs)}",
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMore).padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) leading() else Art(song, Modifier.size(48.dp))
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, color = if (isCurrent) p.accent else p.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = Type.small, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (isCurrent) { Spacer(Modifier.width(8.dp)); Meter(isPlaying) }
        trailing?.invoke() ?: IconButton(Glyphs.More, onMore, tint = p.muted, size = 36.dp, iconSize = 20.dp)
    }
}

/** A grey row for a playlist song that isn't on the phone yet. */
@Composable
fun MissingRow(title: String, artist: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(Shape.art)).background(p.surface), contentAlignment = Alignment.Center) {
            Glyph(Glyphs.Note, p.muted, 20.dp)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$artist · Not on this phone", style = Type.small, color = p.muted.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        trailing?.invoke()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.small, color = p.muted, modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun MiniPlayer(model: AppModel, now: NowState, modifier: Modifier = Modifier) {
    val song = now.song ?: return
    var progress by remember { mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(song.id, now.isPlaying, now.durationMs) {
        while (true) {
            progress = if (now.durationMs > 0) (model.position().toFloat() / now.durationMs).coerceIn(0f, 1f) else 0f
            if (!now.isPlaying) break
            kotlinx.coroutines.delay(500)
        }
    }
    Box(
        modifier.fillMaxWidth().padding(horizontal = 10.dp).height(66.dp)
            .shadow(if (p.dark) 18.dp else 10.dp, RoundedCornerShape(Shape.radius), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(Shape.radius)).background(p.surface)
            .clickable { model.nowPlayingOpen = true }
            .pointerInput(Unit) { detectVerticalDragGestures { _, drag -> if (drag < -12) model.nowPlayingOpen = true } },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 11.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(song, Modifier.size(44.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Type.body.copy(fontSize = 15.sp))
                Text(song.artistLine, style = Type.small.copy(fontSize = 12.5.sp), color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(if (now.isPlaying) Glyphs.Pause else Glyphs.Play, model::toggle)
            IconButton(Glyphs.Next, { model.next() })
        }
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).fillMaxWidth().height(2.dp).background(p.faint))
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp).fillMaxWidth(progress.coerceAtLeast(0.001f)).height(2.dp).background(p.fg))
    }
}

/** A bottom sheet in Ocean's style: scrim, rounded top, drag down to close. */
@Composable
fun BottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss))
        }
        AnimatedVisibility(
            visible, modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it }, exit = slideOutVertically { it },
        ) {
            var drag by remember { mutableFloatStateOf(0f) }
            Column(
                Modifier.fillMaxWidth().offset { IntOffset(0, drag.coerceAtLeast(0f).roundToInt()) }
                    .clip(RoundedCornerShape(topStart = Shape.radius, topEnd = Shape.radius)).background(p.surface)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding().padding(bottom = 12.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(26.dp).pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (drag > 120) onDismiss(); drag = 0f },
                            onDragCancel = { drag = 0f },
                        ) { _, delta -> drag += delta }
                    },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.width(38.dp).height(4.dp).clip(CircleShape).background(p.faint)) }
                content()
            }
        }
    }
}

@Composable
fun MenuItem(glyph: Glyphs, text: String, onClick: () -> Unit, tint: Color = p.fg) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(glyph, tint, 22.dp)
        Spacer(Modifier.width(16.dp))
        Text(text, color = tint)
    }
}

/** A pill text field, like Ocean's search box. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    glyph: Glyphs? = null,
    background: Color = p.surface,
    focus: FocusRequester? = null,
    onDone: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.height(46.dp).clip(CircleShape).background(background).padding(start = if (glyph != null) 15.dp else 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph?.let { Glyph(it, p.muted, 19.dp); Spacer(Modifier.width(10.dp)) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, color = p.muted, maxLines = 1)
            BasicTextField(
                value, onChange,
                modifier = Modifier.fillMaxWidth().let { m -> focus?.let { m.focusRequester(it) } ?: m },
                singleLine = true,
                textStyle = Type.body.copy(color = p.fg),
                cursorBrush = SolidColor(p.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun BoxScope.Toast(text: String?, onDone: () -> Unit) {
    AnimatedVisibility(text != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.widthIn(max = 320.dp).clip(CircleShape).background(p.fg).padding(horizontal = 18.dp, vertical = 11.dp)) {
            Text(text ?: "", color = p.bg, style = Type.small.copy(fontSize = 14.sp))
        }
    }
    androidx.compose.runtime.LaunchedEffect(text) { if (text != null) { kotlinx.coroutines.delay(1800); onDone() } }
}

val TextStyle.medium get() = copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
