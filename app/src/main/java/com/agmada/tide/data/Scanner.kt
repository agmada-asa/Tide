package com.agmada.tide.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** Reads every audio file Android knows about. Only Tidy up changes files, and only after the system asks. */
object Scanner {
    private val collection: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    fun uriOf(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    fun scan(context: Context, hiddenFolders: Set<String>, minDurationMs: Long): List<RawSong> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.SIZE,
        )
        // Ringtones, notification sounds and recordings aren't music.
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} >= ?"
        val songs = mutableListOf<RawSong>()
        context.contentResolver.query(collection, projection, selection, arrayOf("90000"), null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val track = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val year = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val folder = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val added = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val mime = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (c.moveToNext()) {
                val path = c.getString(folder) ?: ""
                val length = c.getLong(duration)
                if (length < minDurationMs) continue
                if (hiddenFolders.any { path.startsWith(it) }) continue
                songs += RawSong(
                    id = c.getLong(id),
                    title = c.getString(title),
                    artist = c.getString(artist),
                    albumArtist = c.getString(albumArtist),
                    album = c.getString(album),
                    track = c.getInt(track),
                    year = c.getInt(year),
                    durationMs = length,
                    folder = path,
                    fileName = c.getString(name) ?: "",
                    dateAdded = c.getLong(added),
                    mime = c.getString(mime),
                    size = c.getLong(size),
                )
            }
        }
        return songs
    }
}
