package com.agmada.tide

import com.agmada.tide.data.Layout
import com.agmada.tide.data.Library
import com.agmada.tide.data.Organiser
import com.agmada.tide.data.RawSong
import com.agmada.tide.data.SongEdit
import com.agmada.tide.importer.ItunesSong
import com.agmada.tide.importer.parseItunes
import com.agmada.tide.importer.parseSpotifyTrackPage
import com.agmada.tide.importer.pickItunes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganiserTest {
    private var nextId = 1L
    private fun raw(
        title: String, artist: String, album: String?, folder: String, file: String = "$title.mp3",
        albumArtist: String? = null, track: Int = 0, duration: Long = 200_000, size: Long = 4_000_000,
    ) = RawSong(nextId++, title, artist, albumArtist, album, track, 2020, duration, folder, file, nextId, "audio/mpeg", size)

    /** A download tool's playlist saved as one album, like "!!!" on the phone. */
    private fun bangBang() = (1..9).map { i ->
        val artist = listOf("Vennisay", "kappaa!", "jet")[i % 3]
        raw("Song $i", artist, "!!!", "Download/Brother/!!!/", "%02d - $artist - Song $i.mp3".format(i), albumArtist = "Various Artists", track = i)
    }

    @Test fun playlistSavedAsAnAlbumIsSpotted() {
        val lib = Library.build(bangBang())
        assertTrue(lib.albums.single().looksLikePlaylist)
        assertEquals(listOf("Download/Brother/!!!/"), Organiser.playlistFolders(lib).map { it.path })
    }

    @Test fun realAlbumIsNotAPlaylist() {
        val lib = Library.build((1..10).map { raw("T$it", "Band", "Record", "Music/Record/", track = it) })
        assertFalse(lib.albums.single().looksLikePlaylist)
        assertTrue(Organiser.playlistFolders(lib).isEmpty())
    }

    @Test fun sortedOutSongsLandOnTheirRealAlbums() {
        val raws = bangBang()
        val edits = raws.associate { it.path to SongEdit(album = if (it.artist == "jet") "WHERE THE CROWS FLY 2" else "", albumArtist = if (it.artist == "jet") "jet" else null, track = 17) }
        val lib = Library.build(raws, edits)
        assertEquals(1, lib.albums.count { !it.isSingle })
        assertEquals(3, lib.artistFor("jet")!!.albums.single { !it.isSingle }.songs.size)
        assertTrue(lib.songs.filter { it.artists.first() != "jet" }.all { it.isSingle })
    }

    @Test fun duplicatesKeepTheAlbumCopy() {
        val lib = Library.build(listOf(
            raw("WIT IT", "Artist", "WEST INDIES", "Download/WEST INDIES/", track = 3, size = 3_000_000),
            raw("WIT IT", "Artist", null, "Downloaded Music/", size = 5_000_000),
            raw("WIT IT", "Artist", null, "Elsewhere/", duration = 120_000),
            raw("INTRO", "Artist", "One", "Music/One/", duration = 67_000),
            raw("INTRO", "Artist", "Two", "Music/Two/", duration = 57_000),
        ))
        val groups = Organiser.duplicates(lib)
        assertEquals(1, groups.size)
        assertEquals(listOf("Download/WEST INDIES/", "Downloaded Music/"), groups.single().map { it.folder })
    }

    @Test fun planPutsFilesUnderArtistAndAlbum() {
        val lib = Library.build(listOf(
            raw("INTRO", "DJ Mykael V, 1995", "BODEGA! DELUXE", "Downloaded Music/BODEGA! DELUXE/", "[SPOTIFY-DOWNLOADER.COM] INTRO.mp3", albumArtist = "DJ Mykael V, 1995", track = 1),
            raw("LOTTO", "DJ Mykael V, 1995", "BODEGA! DELUXE", "Downloaded Music/BODEGA! DELUXE/", "LOTTO.mp3", albumArtist = "DJ Mykael V, 1995", track = 2),
            raw("Wouldn’t U Rather", "DJ Mykael V", "Wouldn’t U Rather", "Downloaded Music/", "spotifydown.com - Wouldn’t U Rather.mp3"),
            raw("Already: Here?", "Band", "Record", "Music/Band/Record/", "01 Already Here.mp3", track = 1),
        ))
        val moves = Organiser.plan(lib, Layout.ArtistAlbum).associate { it.song.title to it.path }
        assertEquals("Music/DJ Mykael V, 1995/BODEGA! DELUXE/01 INTRO.mp3", moves["INTRO"])
        assertEquals("Music/DJ Mykael V/Singles/Wouldn’t U Rather.mp3", moves["Wouldn’t U Rather"])
        assertNull("already in place", moves["Already: Here?"])
        assertEquals("Music/DJ Mykael V - Wouldn’t U Rather.mp3", Organiser.plan(lib, Layout.OneFolder).single { it.song.title.startsWith("Wouldn") }.path)
    }

    @Test fun planNeverGivesTwoFilesTheSameName() {
        val lib = Library.build(listOf(
            raw("Same", "A", null, "X/", "a.mp3", duration = 100_000),
            raw("Same", "A", null, "Y/", "b.mp3", duration = 300_000),
        ))
        val paths = Organiser.plan(lib, Layout.OneFolder).map { it.path }
        assertEquals(setOf("Music/A - Same.mp3", "Music/A - Same (2).mp3"), paths.toSet())
    }

    @Test fun spotifyTrackPageGivesTheAlbum() {
        val html = """<meta name="music:album" content="https://open.spotify.com/album/4kfyC1z1n8PuCVTIvI7jPo"/><meta name="music:album:track" content="3"/><meta name="music:release_date" content="2025-12-12"/>"""
        val page = parseSpotifyTrackPage(html)
        assertEquals("4kfyC1z1n8PuCVTIvI7jPo", page.albumId)
        assertEquals(3, page.track)
        assertEquals(2025, page.year)
        assertNull(parseSpotifyTrackPage("<html></html>").albumId)
    }

    @Test fun itunesPrefersTheAlbumOverTheSingle() {
        val json = """{"resultCount":3,"results":[
            {"wrapperType":"track","trackName":"I wonder","artistName":"kappaa!","collectionName":"I wonder - Single","collectionId":1,"trackNumber":1,"trackCount":1,"trackTimeMillis":173309,"releaseDate":"2024-01-01T00:00:00Z"},
            {"wrapperType":"track","trackName":"I Wonder","artistName":"kappaa! & Tawv","collectionName":"Brotherhood","collectionId":2,"trackNumber":4,"trackCount":12,"trackTimeMillis":174000,"releaseDate":"2024-06-01T00:00:00Z"},
            {"wrapperType":"track","trackName":"I wonder","artistName":"Someone Else","collectionName":"Other","collectionId":3,"trackNumber":1,"trackCount":10,"trackTimeMillis":173309}]}"""
        val all = parseItunes(json)
        assertEquals(3, all.size)
        val hit = pickItunes(all, "I wonder", listOf("kappaa!"), 173_352)!!
        assertEquals("Brotherhood", hit.album)
        assertNull(pickItunes(all, "I wonder", listOf("kappaa!"), 240_000))
        assertNull(pickItunes(listOf<ItunesSong>(), "x", listOf("y"), 0))
    }

    @Test fun tidiedSinglesFolderIsNotAPlaylist() {
        val lib = Library.build((1..4).map { raw("Single $it", "Vennisay", null, "Music/Vennisay/Singles/") })
        assertTrue(Organiser.playlistFolders(lib).isEmpty())
    }

    @Test fun untaggedFileIsMarkedAsNamedByItsFile() {
        val untagged = RawSong(nextId++, "03 - Fennick - Undertow", null, null, null, 0, 0, 200_000, "Download/", "03 - Fennick - Undertow.mp3", 1, "audio/mpeg")
        val tagged = raw("Lanterns", "Odessa Lane", "Lights", "Music/")
        val lib = Library.build(listOf(untagged, tagged))
        val song = lib.songs.single { it.title == "Undertow" }
        assertEquals(listOf("Fennick"), song.artists)
        assertTrue(song.namedByFile)
        assertFalse(lib.songs.single { it.title == "Lanterns" }.namedByFile)
    }
}
