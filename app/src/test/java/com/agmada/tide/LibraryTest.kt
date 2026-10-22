package com.agmada.tide

import com.agmada.tide.data.Library
import com.agmada.tide.data.RawSong
import com.agmada.tide.data.SongEdit
import com.agmada.tide.data.Tags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private var nextId = 1L
    private fun raw(
        title: String?, artist: String?, album: String?, folder: String = "Music/", file: String = "$title.mp3",
        albumArtist: String? = null, track: Int = 0, duration: Long = 200_000,
    ) = RawSong(nextId++, title, artist, albumArtist, album, track, 2020, duration, folder, file, nextId, "audio/mpeg")

    @Test fun keyIgnoresCaseAccentsPunctuationAndThe() {
        assertEquals(Tags.key("The Beatles"), Tags.key("beatles"))
        assertEquals(Tags.key("Beyoncé"), Tags.key("BEYONCE"))
        assertEquals(Tags.key("Simon & Garfunkel"), Tags.key("Simon and Garfunkel"))
    }

    @Test fun featuringMovesFromTitleToArtists() {
        val (title, feats) = Tags.splitFeaturing("Song Name (feat. Artist B & Artist C)")
        assertEquals("Song Name", title)
        assertEquals(listOf("Artist B", "Artist C"), feats)
        assertEquals("Song" to listOf("X"), Tags.splitFeaturing("Song ft. X"))
    }

    @Test fun ampersandStaysInsideBandNames() {
        assertEquals(listOf("Simon & Garfunkel"), Tags.splitArtists("Simon & Garfunkel"))
        assertEquals(listOf("A", "B"), Tags.splitArtists("A, B"))
        assertEquals(listOf("A", "B"), Tags.splitArtists("A feat. B"))
    }

    @Test fun fileNamesFillInMissingTags() {
        assertEquals(Tags.FileGuess(3, "Daft Punk", "Digital Love"), Tags.parseFileName("03 - Daft Punk - Digital Love.mp3"))
        assertEquals(Tags.FileGuess(null, "Daft Punk", "One More Time"), Tags.parseFileName("Daft Punk - One More Time.flac"))
        assertEquals(Tags.FileGuess(7, null, "Intro"), Tags.parseFileName("07. Intro.mp3"))
        assertEquals(Tags.FileGuess(null, null, "1979"), Tags.parseFileName("1979.mp3"))
        assertEquals(Tags.FileGuess(null, null, "my song"), Tags.parseFileName("my_song.mp3"))
    }

    @Test fun untaggedFileTakesTitleAndArtistFromItsName() {
        // MediaStore fills a missing title with the file name and the album with the folder.
        val lib = Library.build(listOf(raw("Daft Punk - Digital Love", "<unknown>", "Download", folder = "Download/", file = "Daft Punk - Digital Love.mp3")))
        val song = lib.songs.single()
        assertEquals("Digital Love", song.title)
        assertEquals(listOf("Daft Punk"), song.artists)
        assertTrue(song.isSingle)
        assertEquals("Daft Punk", lib.artists.single().name)
    }

    @Test fun inconsistentArtistTagsDontSplitAnAlbum() {
        val lib = Library.build(listOf(
            raw("One", "Kendrick Lamar", "DAMN.", folder = "Music/DAMN/", track = 1),
            raw("Two", "Kendrick Lamar feat. Rihanna", "DAMN.", folder = "Music/DAMN/", track = 2),
            raw("Three", "kendrick lamar", "DAMN. ", folder = "Music/DAMN/", track = 3),
        ))
        assertEquals(1, lib.albums.size)
        val album = lib.albums.single()
        assertEquals("Kendrick Lamar", album.artist)
        assertEquals(listOf("One", "Two", "Three"), album.songs.map { it.title })
        assertEquals(listOf("Kendrick Lamar", "Rihanna"), lib.songs.first { it.title == "Two" }.artists)
        assertEquals(1, lib.artists.count { it.name.equals("Kendrick Lamar", ignoreCase = true) })
    }

    @Test fun compilationWithoutAlbumArtistIsVariousArtists() {
        val lib = Library.build(listOf(
            raw("A", "One", "Hits", folder = "Music/Hits/"),
            raw("B", "Two", "Hits", folder = "Music/Hits/"),
            raw("C", "Three", "Hits", folder = "Music/Hits/"),
        ))
        assertEquals(Library.VARIOUS_ARTISTS, lib.albums.single().artist)
    }

    @Test fun sameAlbumAcrossDiscFoldersMerges() {
        val lib = Library.build(listOf(
            raw("A", "Band", "Big Album", folder = "Music/Big Album/CD1/", track = 1001),
            raw("B", "Band", "Big Album", folder = "Music/Big Album/CD2/", track = 2001),
        ))
        assertEquals(1, lib.albums.size)
        assertEquals(listOf(1, 2), lib.albums.single().songs.map { it.disc })
    }

    @Test fun singleSuffixIsDropped() {
        val lib = Library.build(listOf(raw("Song", "Artist", "Song - Single")))
        assertEquals("Song", lib.albums.single().title)
    }

    @Test fun editsWinOverTags() {
        val r = raw("wrong", "nobody", "Download", folder = "Download/", file = "x.mp3")
        val lib = Library.build(listOf(r), mapOf(r.path to SongEdit(title = "Right", artist = "Somebody", album = "Real Album")))
        val song = lib.songs.single()
        assertEquals("Right", song.title)
        assertEquals("Real Album", song.album)
        assertEquals(listOf("Somebody"), song.artists)
    }

    @Test fun matchingIgnoresVersionNoiseAndFileNames() {
        val lib = Library.build(listOf(
            raw("Bohemian Rhapsody - Remastered 2011", "Queen", "A Night at the Opera"),
            raw("Hey Jude", "<unknown>", "Download", folder = "Download/", file = "Hey Jude.mp3"),
        ))
        assertNotNull(lib.match("Bohemian Rhapsody", "Queen", 354_000))
        assertNotNull(lib.match("Bohemian Rhapsody (Remastered 2011)", "Queen", 0))
        assertNotNull(lib.match("Hey Jude - Remastered 2015", "The Beatles", 200_500))
        assertNull(lib.match("Hey Jude", "The Beatles", 431_000))
        assertNull(lib.match("Something Else", "Queen", 0))
    }

    @Test fun oneSpellingPerArtist() {
        val lib = Library.build(listOf(
            raw("A", "marlow kei", "X", folder = "Music/X/"),
            raw("B", "MARLOW KEI", "Y", folder = "Music/Y/"),
            raw("C", "Marlow Kei", "Z", folder = "Music/Z/"),
        ))
        assertEquals(setOf("Marlow Kei"), lib.songs.flatMap { it.artists }.toSet())
        assertEquals(setOf("Marlow Kei"), lib.albums.map { it.artist }.toSet())
    }

    @Test fun commaListsSplitTheirLastAmpersandToo() {
        assertEquals(listOf("indie tribe", "nobigdyl.", "DJ Mykael V"), Tags.splitArtists("indie tribe, nobigdyl. & DJ Mykael V"))
        assertEquals(listOf("Simon & Garfunkel"), Tags.splitArtists("Simon & Garfunkel"))
        assertEquals(listOf("Tyler, The Creator"), Tags.splitArtists("Tyler, The Creator"))
        assertEquals(listOf("Tyler, The Creator", "Kali Uchis"), Tags.splitArtists("Tyler, The Creator & Kali Uchis"))
        assertEquals(listOf("AC/DC"), Tags.splitArtists("AC/DC"))
    }

    @Test fun albumWithTwoArtistsIsOnBothPages() {
        val lib = Library.build(listOf(
            raw("INTRO", "DJ Mykael V, 1995", "BODEGA! DELUXE", folder = "Music/BODEGA/", albumArtist = "DJ Mykael V, 1995", track = 1),
            raw("LOTTO", "DJ Mykael V, 1995, nobigdyl.", "BODEGA! DELUXE", folder = "Music/BODEGA/", albumArtist = "DJ Mykael V, 1995", track = 2),
        ))
        val album = lib.albums.single()
        assertEquals(listOf("DJ Mykael V", "1995"), album.artists)
        assertEquals(listOf(album.key), lib.artistFor("DJ Mykael V")!!.albums.map { it.key })
        assertEquals(listOf(album.key), lib.artistFor("1995")!!.albums.map { it.key })
        assertEquals(listOf(album.key), lib.artistFor("nobigdyl.")!!.appearsOn.map { it.key })
    }

    @Test fun duoCreditSplitsWhenBothArtistsAreKnown() {
        val lib = Library.build(listOf(
            raw("LOWKEY!", "DJ Mykael V & 350", "LOWKEY! - Single", folder = "Music/A/"),
            raw("X", "DJ Mykael V", "Y", folder = "Music/B/"),
            raw("Z", "350", "W", folder = "Music/C/"),
            raw("The Boxer", "Simon & Garfunkel", "Bridge", folder = "Music/D/"),
        ))
        assertEquals(listOf("DJ Mykael V", "350"), lib.songs.first { it.title == "LOWKEY!" }.artists)
        assertTrue(lib.albums.first { it.title == "LOWKEY!" }.isSingle)
        assertEquals(listOf("Simon & Garfunkel"), lib.songs.first { it.title == "The Boxer" }.artists)
    }
}
