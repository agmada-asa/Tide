package com.agmada.tide.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The last scan, saved so Tide opens on the library it had and only reads MediaStore again in the background. */
data class Snapshot(
    /** MediaStore's version and generations when the scan ran; when they haven't moved, nothing has changed. */
    val stamp: String?,
    val songs: List<RawSong>,
)

object LibraryCache {
    private const val MAGIC = 0x54494445 // "TIDE"
    private const val FORMAT = 1

    private fun file(context: Context) = AtomicFile(File(context.filesDir, "library.cache"))

    fun load(context: Context): Snapshot? = runCatching { file(context).openRead().buffered().use(::read) }.getOrNull()

    fun save(context: Context, snapshot: Snapshot) {
        val f = file(context)
        val out = f.startWrite()
        try {
            val buffered = out.buffered()
            write(buffered, snapshot)
            buffered.flush()
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
        }
    }

    /**
     * Changes whenever any file on any volume is added, changed or removed. Android 10 has no
     * generations, so there it's always null and every launch scans (still behind the cached library).
     */
    fun stamp(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val volumes = MediaStore.getExternalVolumeNames(context).sorted()
        return MediaStore.getVersion(context) + volumes.joinToString("") { ";$it=${MediaStore.getGeneration(context, it)}" }
    }

    fun write(stream: OutputStream, snapshot: Snapshot) {
        val out = DataOutputStream(stream)
        fun str(s: String?) { out.writeBoolean(s != null); if (s != null) out.writeUTF(s) }
        out.writeInt(MAGIC)
        out.writeInt(FORMAT)
        str(snapshot.stamp)
        out.writeInt(snapshot.songs.size)
        for (s in snapshot.songs) {
            out.writeLong(s.id)
            str(s.title); str(s.artist); str(s.albumArtist); str(s.album)
            out.writeInt(s.track)
            out.writeInt(s.year)
            out.writeLong(s.durationMs)
            out.writeUTF(s.folder)
            out.writeUTF(s.fileName)
            out.writeLong(s.dateAdded)
            str(s.mime)
            out.writeLong(s.size)
        }
        out.flush()
    }

    /** Null for a missing, damaged or older-format cache: Tide then just waits for the scan, as before. */
    fun read(stream: InputStream): Snapshot? {
        val input = DataInputStream(stream)
        fun str(): String? = if (input.readBoolean()) input.readUTF() else null
        if (input.readInt() != MAGIC || input.readInt() != FORMAT) return null
        val stamp = str()
        val songs = List(input.readInt()) {
            RawSong(
                id = input.readLong(),
                title = str(), artist = str(), albumArtist = str(), album = str(),
                track = input.readInt(),
                year = input.readInt(),
                durationMs = input.readLong(),
                folder = input.readUTF(),
                fileName = input.readUTF(),
                dateAdded = input.readLong(),
                mime = str(),
                size = input.readLong(),
            )
        }
        return Snapshot(stamp, songs)
    }
}
