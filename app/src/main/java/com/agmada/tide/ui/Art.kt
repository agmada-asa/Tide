package com.agmada.tide.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette as ColorPalette
import com.agmada.tide.data.Scanner
import com.agmada.tide.data.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Cover art, read from the files' embedded pictures by Android's thumbnailer and kept in memory. */
object ArtCache {
    private val none = Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val io = Dispatchers.IO.limitedParallelism(4)
    val http = OkHttpClient()

    /** Small for rows and tiles, large for the album page and Now playing. */
    fun bucket(px: Int) = if (px <= 220) 220 else 900

    fun peek(key: String): Bitmap? = cache.get(key)?.takeIf { it !== none }

    suspend fun song(context: Context, id: Long, px: Int): Bitmap? {
        val key = "$id@${bucket(px)}"
        cache.get(key)?.let { return it.takeIf { b -> b !== none } }
        val bitmap = withContext(io) {
            runCatching { context.contentResolver.loadThumbnail(Scanner.uriOf(id), Size(bucket(px), bucket(px)), null) }.getOrNull()
        }
        cache.put(key, bitmap ?: none)
        return bitmap
    }

    suspend fun remote(url: String): Bitmap? {
        cache.get(url)?.let { return it.takeIf { b -> b !== none } }
        val bitmap = withContext(io) {
            runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { r -> r.body?.byteStream()?.let(BitmapFactory::decodeStream) }
            }.getOrNull()
        }
        cache.put(url, bitmap ?: none)
        return bitmap
    }

    /** The cover's strongest colour, for tinting Now playing. */
    suspend fun tint(bitmap: Bitmap): Color? = withContext(Dispatchers.Default) {
        val palette = ColorPalette.from(bitmap).maximumColorCount(16).generate()
        val swatch = palette.vibrantSwatch ?: palette.darkVibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch
        swatch?.rgb?.let { Color(it) }
    }
}

/** Placeholder covers for files with no picture: a gradient picked by album, so an album keeps its colour. */
private val placeholderColors = listOf(
    Color(0xFF1F3B73) to Color(0xFF4DA8FF), Color(0xFF3A1E2E) to Color(0xFFC2456B), Color(0xFF14302A) to Color(0xFF2F8F6B),
    Color(0xFF2A2016) to Color(0xFFB8860B), Color(0xFF221A3A) to Color(0xFF7B5BD6), Color(0xFF0E2A33) to Color(0xFF2B8C9C),
)

fun placeholderTint(key: String): Color = placeholderColors[Math.floorMod(key.hashCode(), placeholderColors.size)].second

@Composable
private fun Placeholder(key: String, size: Dp) {
    val (dark, light) = placeholderColors[Math.floorMod(key.hashCode(), placeholderColors.size)]
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(light, dark))),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(Glyphs.Note, Color.White.copy(alpha = 0.55f), size = (size * 0.38f).coerceIn(14.dp, 96.dp))
    }
}

/** A song's cover at any size; fills its [modifier]'s bounds. */
@Composable
fun Art(song: Song?, modifier: Modifier, radius: Dp = Shape.art) {
    val context = LocalContext.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(radius))) {
        val px = with(LocalDensity.current) { maxWidth.roundToPx() }
        val key = song?.let { "${it.id}@${ArtCache.bucket(px)}" }
        val bitmap by produceState(key?.let(ArtCache::peek), key) {
            value = song?.let { ArtCache.song(context, it.id, px) }
        }
        val image = bitmap
        if (image != null) {
            Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Placeholder(song?.albumKey ?: "", maxWidth)
        }
    }
}

/** An imported playlist's Spotify cover, falling back to its first local song. */
@Composable
fun RemoteArt(url: String?, fallback: Song?, key: String, modifier: Modifier, radius: Dp = Shape.art) {
    if (url == null) {
        if (fallback != null) Art(fallback, modifier, radius)
        else BoxWithConstraints(modifier.clip(RoundedCornerShape(radius))) { Placeholder(key, maxWidth) }
        return
    }
    val bitmap by produceState<ImageBitmap?>(ArtCache.peek(url)?.asImageBitmap(), url) { value = ArtCache.remote(url)?.asImageBitmap() }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(radius))) {
        val image = bitmap
        if (image != null) Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else if (fallback != null) Art(fallback, Modifier.fillMaxSize(), 0.dp)
        else Placeholder(key, maxWidth)
    }
}
