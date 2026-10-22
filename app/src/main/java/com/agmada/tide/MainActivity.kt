package com.agmada.tide

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.agmada.tide.ui.AlbumScreen
import com.agmada.tide.ui.AppModel
import com.agmada.tide.ui.ArtistScreen
import com.agmada.tide.ui.BottomSheet
import com.agmada.tide.ui.FolderScreen
import com.agmada.tide.ui.Glyph
import com.agmada.tide.ui.Glyphs
import com.agmada.tide.ui.ImportScreen
import com.agmada.tide.ui.LibraryScreen
import com.agmada.tide.ui.MiniPlayer
import com.agmada.tide.ui.NowPlaying
import com.agmada.tide.ui.Pill
import com.agmada.tide.ui.PlaylistScreen
import com.agmada.tide.ui.QueueSheet
import com.agmada.tide.ui.Route
import com.agmada.tide.ui.SettingsScreen
import com.agmada.tide.ui.SortOutScreen
import com.agmada.tide.ui.TidyScreen
import com.agmada.tide.ui.Sheets
import com.agmada.tide.ui.TideTheme
import com.agmada.tide.ui.Toast
import com.agmada.tide.ui.Type
import com.agmada.tide.ui.p

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    private val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // No grey band behind 3-button navigation; Tide draws its own background there.
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            TideTheme {
                var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED) }
                val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                    granted = results[audioPermission] == true
                }
                LaunchedEffect(granted) { if (granted) model.repo.rescan() }
                if (granted) App(model) else Welcome {
                    request.launch(listOfNotNull(audioPermission, Manifest.permission.POST_NOTIFICATIONS.takeIf { Build.VERSION.SDK_INT >= 33 }).toTypedArray())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share → Tide" from Spotify lands on the import page with the link filled in. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        model.go(Route.Import(text))
    }
}

@Composable
private fun Welcome(onAllow: () -> Unit) {
    Box(Modifier.fillMaxSize().background(p.bg).navigationBarsPadding().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(Glyphs.Note, p.accent, 54.dp, stroke = 1.4f)
            Text("Tide", style = Type.display, modifier = Modifier.padding(top = 18.dp))
            Text(
                "Tide plays the music on this phone. It needs to see your audio files; it never changes, moves or uploads them.",
                color = p.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, bottom = 28.dp),
            )
            Pill("Allow access to music", onAllow, Modifier.fillMaxWidth(), solid = true)
        }
    }
}

@Composable
private fun App(model: AppModel) {
    val library by model.repo.library.collectAsState()
    val playlists by model.repo.playlists.collectAsState()
    val liked by model.repo.likedIds.collectAsState()
    val now by model.now.collectAsState()

    BackHandler(enabled = model.sheet != null || model.queueOpen || model.nowPlayingOpen || model.stack.size > 1) { model.back() }

    // Each page keeps its scroll position while it's in the back stack.
    val pages = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(p.bg)) {
        AnimatedContent(model.stack.last(), transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "route") { route ->
            val lib = library
            pages.SaveableStateProvider(route.toString()) { when (route) {
                Route.Library -> LibraryScreen(model, lib, playlists, now)
                is Route.Album -> lib?.let { AlbumScreen(model, it, route.key, now) }
                is Route.Artist -> lib?.let { ArtistScreen(model, it, route.key, now) }
                is Route.Folder -> lib?.let { FolderScreen(model, it, route.path, now) }
                is Route.Playlist -> playlists.firstOrNull { it.row.id == route.id }?.let { PlaylistScreen(model, it, now) }
                is Route.Import -> ImportScreen(model, lib, route.text, now)
                Route.Settings -> SettingsScreen(model, lib)
                is Route.SortOut -> lib?.let { SortOutScreen(model, it, route.albumKey) }
                Route.Tidy -> lib?.let { TidyScreen(model, it) }
            } }
        }
        MiniPlayer(model, now, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp))
        AnimatedVisibility(
            model.nowPlayingOpen && now.song != null,
            enter = slideInVertically(tween(320)) { it }, exit = slideOutVertically(tween(260)) { it },
        ) {
            NowPlaying(model, now, now.song?.id in liked)
        }
        BottomSheet(model.queueOpen, { model.queueOpen = false }) { QueueSheet(model, now) }
        Sheets(model, library, playlists)
        Toast(model.toast) { model.toast = null }
    }
}
