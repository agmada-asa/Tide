package com.agmada.tide.data

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.provider.MediaStore

/**
 * The doing half of Tidy up. Android only lets an app change music it didn't create after the
 * user agrees in a system dialog, so each action starts with one of the requests here.
 */
object Tidy {
    /** Asks once for permission to change all of [songs]. */
    fun writeRequest(context: Context, songs: List<Song>): IntentSender =
        MediaStore.createWriteRequest(context.contentResolver, songs.map { Scanner.uriOf(it.id) }).intentSender

    /** Asks once to move [songs] to the bin, where they stay for 30 days. */
    fun binRequest(context: Context, songs: List<Song>): IntentSender =
        MediaStore.createTrashRequest(context.contentResolver, songs.map { Scanner.uriOf(it.id) }, true).intentSender

    /** Moves and renames files. Returns how many moved; ones Android refuses are left where they are. */
    suspend fun move(context: Context, repo: TideRepo, moves: List<Move>, progress: (Int) -> Unit): Int {
        var moved = 0
        moves.forEachIndexed { i, move ->
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.RELATIVE_PATH, move.folder)
                put(MediaStore.Audio.Media.DISPLAY_NAME, move.fileName)
            }
            val ok = runCatching { context.contentResolver.update(Scanner.uriOf(move.song.id), values, null, null) > 0 }.getOrDefault(false)
            if (ok) {
                repo.moved(move.song, move.path)
                moved++
            }
            progress(i + 1)
        }
        return moved
    }

    /**
     * Writes what Tide shows (title, artists, album, track, year) into each MP3's own tags, so other
     * apps see the fixes too. The audio isn't touched. Returns the paths written.
     */
    fun writeTags(context: Context, songs: List<Song>, progress: (Int) -> Unit): List<String> {
        val written = mutableListOf<String>()
        val resolver = context.contentResolver
        songs.forEachIndexed { i, song ->
            runCatching {
                val uri = Scanner.uriOf(song.id)
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching
                val v4 = bytes.size > 3 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte() && bytes[3] == 4.toByte()
                val changes = mutableMapOf(
                    "TIT2" to song.title,
                    "TPE1" to song.artistLine,
                    "TALB" to song.album,
                    "TPE2" to song.albumArtist,
                    "TRCK" to song.track.takeIf { it > 0 }?.toString(),
                )
                // One year frame for the tag's version. Download tools often leave a stray one of the
                // other kind holding the download date, which some apps would show instead.
                if (song.year > 0) {
                    changes[if (v4) "TDRC" else "TYER"] = song.year.toString()
                    changes[if (v4) "TYER" else "TDRC"] = null
                }
                val out = Id3.rewrite(bytes, changes) ?: return@runCatching
                // Read it back before replacing the file, so a bad rewrite never reaches the disk.
                check(Id3.readFrames(out.inputStream())["TALB"] == song.album)
                resolver.openOutputStream(uri, "wt")?.use { it.write(out) } ?: return@runCatching
                written += song.path
            }
            progress(i + 1)
        }
        // Have Android read the new tags now rather than whenever it next looks.
        val absolute = songs.filter { it.path in written }.map { "/storage/emulated/0/${it.path}" }
        if (absolute.isNotEmpty()) MediaScannerConnection.scanFile(context, absolute.toTypedArray(), null, null)
        return written
    }
}
