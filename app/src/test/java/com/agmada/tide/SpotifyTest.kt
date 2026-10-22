package com.agmada.tide

import com.agmada.tide.importer.RemoteKind
import com.agmada.tide.importer.SpotifyLinks
import com.agmada.tide.importer.parseSpotifyEmbed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// Trimmed from real embed pages fetched on 2026-10-22. Only the fields the parser reads are kept,
// and the track lists are cut to three entries.
private const val PLAYLIST_ID = "37i9dQZF1DXcBWIGoYBM5M"
private const val ALBUM_ID = "4LH4d3cOWNNsVw41Gqt2kv"
private const val TRACK_ID = "0VjIjW4GlUZAMYd2vXMi3b"

private val playlistJson = """{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","name":"Today’s Top Hits","uri":"spotify:playlist:37i9dQZF1DXcBWIGoYBM5M","id":"37i9dQZF1DXcBWIGoYBM5M","subtitle":"Spotify","coverArt":{"sources":[{"height":null,"width":null,"url":"https://i.scdn.co/image/ab67706f0000000230888e59410296f950502d00"}]},"visualIdentity":{"image":[{"url":"https://image-cdn-ak.spotifycdn.com/image/ab67706f0000000130888e59410296f950502d00","maxHeight":64,"maxWidth":64},{"url":"https://image-cdn-ak.spotifycdn.com/image/ab67706f0000000230888e59410296f950502d00","maxHeight":300,"maxWidth":300}]},"trackList":[{"uri":"spotify:track:3XmwAZpIMNpTJy8faw3ajD","title":"expectations (feat. Chappell Roan)","subtitle":"Olivia Rodrigo, Chappell Roan","duration":223935},{"uri":"spotify:track:70cHKK8bHAfJrOGVnfRG9J","title":"Nicole Kidman","subtitle":"ADÉLA","duration":181270},{"uri":"spotify:track:3zOYmiNOuyDL0ClxJcEJ0T","title":"Stop The Wedding!","subtitle":"Ashe","duration":198840}]}}}}}}"""

private val albumJson = """{"props":{"pageProps":{"state":{"data":{"entity":{"type":"album","name":"The Dark Side of the Moon","uri":"spotify:album:4LH4d3cOWNNsVw41Gqt2kv","id":"4LH4d3cOWNNsVw41Gqt2kv","subtitle":"Pink Floyd","visualIdentity":{"image":[{"url":"https://image-cdn-fa.spotifycdn.com/image/ab67616d00001e02db216ca805faf5fe35df4ee6","maxHeight":300,"maxWidth":300},{"url":"https://image-cdn-fa.spotifycdn.com/image/ab67616d00004851db216ca805faf5fe35df4ee6","maxHeight":64,"maxWidth":64}]},"trackList":[{"uri":"spotify:track:574y1r7o2tRA009FW0LE7v","title":"Speak to Me","subtitle":"Pink Floyd","duration":64333},{"uri":"spotify:track:2ctvdKmETyOzPb2GiJJT53","title":"Breathe (In the Air)","subtitle":"Pink Floyd","duration":169560},{"uri":"spotify:track:73OIUNKRi2y24Cu9cOLrzM","title":"On the Run","subtitle":"Pink Floyd","duration":216106}]}}}}}}"""

private val trackJson = """{"props":{"pageProps":{"state":{"data":{"entity":{"type":"track","name":"Blinding Lights","uri":"spotify:track:0VjIjW4GlUZAMYd2vXMi3b","id":"0VjIjW4GlUZAMYd2vXMi3b","artists":[{"name":"The Weeknd","uri":"spotify:artist:1Xyo4u8uXC1ZmMpatF05PJ"}],"duration":200040,"visualIdentity":{"image":[{"url":"https://image-cdn-fa.spotifycdn.com/image/ab67616d00001e028863bc11d2aa12b54f5aeb36","maxHeight":300,"maxWidth":300},{"url":"https://image-cdn-fa.spotifycdn.com/image/ab67616d000048518863bc11d2aa12b54f5aeb36","maxHeight":64,"maxWidth":64}]}}}}}}}"""

private fun embedPage(json: String): String =
    """<!DOCTYPE html><html><head><title>Spotify</title></head><body><script id="__NEXT_DATA__" type="application/json">$json</script></body></html>"""

class SpotifyTest {

    // Link parsing

    @Test
    fun parsesPlaylistUrlWithTrackingQuery() {
        val parsed = SpotifyLinks.parse("https://open.spotify.com/playlist/$PLAYLIST_ID?si=abc123")
        assertEquals(RemoteKind.Playlist to PLAYLIST_ID, parsed)
    }

    @Test
    fun parsesIntlAlbumUrl() {
        val parsed = SpotifyLinks.parse("https://open.spotify.com/intl-de/album/$ALBUM_ID")
        assertEquals(RemoteKind.Album to ALBUM_ID, parsed)
    }

    @Test
    fun parsesTrackUrl() {
        val parsed = SpotifyLinks.parse("https://open.spotify.com/track/$TRACK_ID?si=xyz")
        assertEquals(RemoteKind.Track to TRACK_ID, parsed)
    }

    @Test
    fun parsesSpotifyUris() {
        assertEquals(RemoteKind.Track to TRACK_ID, SpotifyLinks.parse("spotify:track:$TRACK_ID"))
        assertEquals(RemoteKind.Album to ALBUM_ID, SpotifyLinks.parse("spotify:album:$ALBUM_ID"))
        assertEquals(RemoteKind.Playlist to PLAYLIST_ID, SpotifyLinks.parse("spotify:playlist:$PLAYLIST_ID"))
    }

    @Test
    fun findsTheLinkInsidePastedText() {
        val text = "have a listen https://open.spotify.com/album/$ALBUM_ID lol"
        assertEquals(RemoteKind.Album to ALBUM_ID, SpotifyLinks.parse(text))
    }

    @Test
    fun shortLinksAreDetectedButNotParsed() {
        val short = "https://spotify.link/hRkBrwub9xb"
        assertTrue(SpotifyLinks.isShortLink(short))
        assertTrue(SpotifyLinks.isShortLink("listen here spotify.link/hRkBrwub9xb"))
        assertNull(SpotifyLinks.parse(short))
    }

    @Test
    fun fullLinksAreNotShortLinks() {
        assertFalse(SpotifyLinks.isShortLink("https://open.spotify.com/track/$TRACK_ID"))
    }

    @Test
    fun junkTextParsesToNull() {
        assertNull(SpotifyLinks.parse(""))
        assertNull(SpotifyLinks.parse("just some words"))
        assertNull(SpotifyLinks.parse("https://example.com/track/$TRACK_ID"))
        assertNull(SpotifyLinks.parse("https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ"))
        assertNull(SpotifyLinks.parse("https://open.spotify.com/track/short"))
        assertFalse(SpotifyLinks.isShortLink("just some words"))
    }

    // Embed parsing

    @Test
    fun parsesPlaylistEmbed() {
        val url = "https://open.spotify.com/playlist/$PLAYLIST_ID"
        val result = parseSpotifyEmbed(embedPage(playlistJson), RemoteKind.Playlist, url)

        assertEquals(RemoteKind.Playlist, result.kind)
        assertEquals("Today’s Top Hits", result.name)
        assertEquals("Spotify", result.subtitle)
        assertEquals("https://i.scdn.co/image/ab67706f0000000230888e59410296f950502d00", result.coverUrl)
        assertEquals(url, result.sourceUrl)
        assertFalse(result.truncated)

        assertEquals(3, result.tracks.size)
        val first = result.tracks[0]
        assertEquals("expectations (feat. Chappell Roan)", first.title)
        assertEquals(listOf("Olivia Rodrigo", "Chappell Roan"), first.artists)
        assertEquals(223935L, first.durationMs)
        assertEquals(listOf("ADÉLA"), result.tracks[1].artists)
    }

    @Test
    fun parsesAlbumEmbedAndPicksLargestCover() {
        val url = "https://open.spotify.com/album/$ALBUM_ID"
        val result = parseSpotifyEmbed(embedPage(albumJson), RemoteKind.Album, url)

        assertEquals(RemoteKind.Album, result.kind)
        assertEquals("The Dark Side of the Moon", result.name)
        assertEquals("Pink Floyd", result.subtitle)
        assertEquals(
            "https://image-cdn-fa.spotifycdn.com/image/ab67616d00001e02db216ca805faf5fe35df4ee6",
            result.coverUrl
        )
        assertFalse(result.truncated)

        assertEquals(3, result.tracks.size)
        assertEquals("Speak to Me", result.tracks[0].title)
        assertEquals(listOf("Pink Floyd"), result.tracks[0].artists)
        assertEquals(64333L, result.tracks[0].durationMs)
    }

    @Test
    fun parsesTrackEmbedAsOneTrackCollection() {
        val url = "https://open.spotify.com/track/$TRACK_ID"
        val result = parseSpotifyEmbed(embedPage(trackJson), RemoteKind.Track, url)

        assertEquals(RemoteKind.Track, result.kind)
        assertEquals("Blinding Lights", result.name)
        assertEquals("The Weeknd", result.subtitle)
        assertEquals(
            "https://image-cdn-fa.spotifycdn.com/image/ab67616d00001e028863bc11d2aa12b54f5aeb36",
            result.coverUrl
        )
        assertFalse(result.truncated)

        assertEquals(1, result.tracks.size)
        assertEquals("Blinding Lights", result.tracks[0].title)
        assertEquals(listOf("The Weeknd"), result.tracks[0].artists)
        assertEquals(200040L, result.tracks[0].durationMs)
    }

    @Test
    fun unrecognisedPageThrowsPlainMessage() {
        val url = "https://open.spotify.com/playlist/$PLAYLIST_ID"

        val noData = assertThrows(IllegalArgumentException::class.java) {
            parseSpotifyEmbed("<html><body>Nothing here</body></html>", RemoteKind.Playlist, url)
        }
        assertEquals("This Spotify page has no music data", noData.message)

        val badJson = assertThrows(IllegalArgumentException::class.java) {
            parseSpotifyEmbed(
                """<script id="__NEXT_DATA__" type="application/json">{not json</script>""",
                RemoteKind.Playlist,
                url
            )
        }
        assertEquals("This Spotify page has unreadable music data", badJson.message)

        val noTrackList = assertThrows(IllegalArgumentException::class.java) {
            parseSpotifyEmbed(embedPage(trackJson), RemoteKind.Playlist, url)
        }
        assertEquals("This Spotify page has no track list", noTrackList.message)
    }
}
