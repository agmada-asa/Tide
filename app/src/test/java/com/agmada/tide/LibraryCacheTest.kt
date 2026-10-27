package com.agmada.tide

import com.agmada.tide.data.LibraryCache
import com.agmada.tide.data.RawSong
import com.agmada.tide.data.Snapshot
import com.agmada.tide.data.visible
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LibraryCacheTest {
    private val songs = listOf(
        RawSong(1, "Undertow", "Fennick", null, "Tidewater", 1003, 2021, 201_000, "Music/Fennick/Tidewater/", "03 Undertow.mp3", 1700000000, "audio/mpeg", 4_812_000),
        RawSong(2, null, null, null, null, 0, 0, 12_000, "Recordings/", "Voice 001.m4a", 1700000500, null),
        RawSong(3, "Café", "Marlow Kei, Beyoncé", "Marlow Kei", "Ünïcode ✓", 0, 0, 180_000, "Download/Hidden/", "cafe.flac", 1700001000, "audio/flac", 30_000_000),
    )

    private fun roundTrip(snapshot: Snapshot): Snapshot? {
        val bytes = ByteArrayOutputStream().also { LibraryCache.write(it, snapshot) }.toByteArray()
        return LibraryCache.read(ByteArrayInputStream(bytes))
    }

    @Test fun keepsEverySongExactly() {
        val snapshot = Snapshot("1a2b;external_primary=812", songs)
        assertEquals(snapshot, roundTrip(snapshot))
    }

    @Test fun keepsAMissingStampAndAnEmptyLibrary() {
        assertEquals(Snapshot(null, emptyList()), roundTrip(Snapshot(null, emptyList())))
    }

    @Test fun ignoresAFileThatIsNotACache() {
        assertNull(LibraryCache.read(ByteArrayInputStream(ByteArrayOutputStream().also { it.write(ByteArray(16) { 7 }) }.toByteArray())))
    }

    @Test fun hidesFoldersAndShortClipsWithoutRescanning() {
        assertEquals(listOf(1L, 3L), songs.visible(emptySet(), 30_000).map { it.id })
        assertEquals(listOf(1L), songs.visible(setOf("Download/Hidden/"), 30_000).map { it.id })
        assertEquals(listOf(1L, 2L, 3L), songs.visible(emptySet(), 0).map { it.id })
    }
}
