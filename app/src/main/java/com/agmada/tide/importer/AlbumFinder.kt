package com.agmada.tide.importer

import android.content.Context
import com.agmada.tide.data.Id3
import com.agmada.tide.data.Scanner
import com.agmada.tide.data.Song
import com.agmada.tide.data.Tags
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** The album a song really comes from, found online. */
data class FoundAlbum(val album: String, val artists: List<String>, val track: Int?, val year: Int?, val source: String)

/** What Spotify's public track page says about a track's album. */
data class SpotifyTrackPage(val albumId: String?, val track: Int?, val year: Int?)

fun parseSpotifyTrackPage(html: String): SpotifyTrackPage {
    fun meta(name: String) = Regex("""<meta (?:name|property)="${Regex.escape(name)}" content="([^"]*)"""").find(html)?.groupValues?.get(1)
    return SpotifyTrackPage(
        albumId = meta("music:album")?.let { Regex("album/([A-Za-z0-9]{22})").find(it)?.groupValues?.get(1) },
        track = meta("music:album:track")?.toIntOrNull(),
        year = meta("music:release_date")?.take(4)?.toIntOrNull(),
    )
}

/** One song from Apple's public iTunes Search API. */
data class ItunesSong(
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val track: Int?,
    val trackCount: Int,
    val durationMs: Long,
    val year: Int?,
)

fun parseItunes(json: String): List<ItunesSong> {
    val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
    return (0 until results.length()).mapNotNull { i ->
        val r = results.optJSONObject(i) ?: return@mapNotNull null
        if (r.optString("wrapperType") != "track") return@mapNotNull null
        ItunesSong(
            title = r.optString("trackName"),
            artist = r.optString("artistName"),
            album = r.optString("collectionName"),
            albumId = r.optLong("collectionId"),
            track = r.optInt("trackNumber").takeIf { it > 0 },
            trackCount = r.optInt("trackCount"),
            durationMs = r.optLong("trackTimeMillis"),
            year = r.optString("releaseDate").take(4).toIntOrNull(),
        )
    }
}

/**
 * The iTunes result that is this song: same title (ignoring "feat." and version noise), a shared
 * artist and about the same length. When a song came out as a single and then on an album, the album wins.
 */
fun pickItunes(candidates: List<ItunesSong>, title: String, artists: List<String>, durationMs: Long): ItunesSong? {
    val titleKey = Tags.looseTitleKey(title)
    val ours = artists.map(Tags::key).filter { it.isNotEmpty() }
    val matches = candidates.filter { c ->
        Tags.looseTitleKey(c.title) == titleKey &&
            Tags.splitArtists(c.artist, splitAmpersand = true).map(Tags::key).any { theirs -> ours.any { it == theirs } } &&
            (durationMs <= 0 || c.durationMs <= 0 || kotlin.math.abs(c.durationMs - durationMs) < 6_000)
    }
    return matches.maxWithOrNull(compareBy<ItunesSong>({ it.trackCount > 1 }, { -kotlin.math.abs(it.durationMs - durationMs) }))
}

/**
 * Looks songs up online to find the album each one is really from. Files downloaded from Spotify
 * often carry the track's link in their tags, which gives the exact album; otherwise Apple's
 * public search is matched by title, artist and length. Blocking; call off the main thread.
 */
class AlbumFinder(private val context: Context, private val http: OkHttpClient) {
    private val spotify = SpotifyImporter(http)
    private val spotifyAlbums = mutableMapOf<String, RemoteCollection?>()
    private val itunesByArtist = mutableMapOf<String, List<ItunesSong>>()
    private val itunesAlbumArtists = mutableMapOf<Long, String>()

    fun find(song: Song): FoundAlbum? =
        runCatching { spotifyIdOf(song)?.let(::fromSpotify) }.getOrNull()
            ?: runCatching { fromItunes(song) }.getOrNull()

    private fun spotifyIdOf(song: Song): String? {
        if (song.mime?.contains("mpeg") != true) return null
        return context.contentResolver.openInputStream(Scanner.uriOf(song.id))?.use { Id3.spotifyTrackId(Id3.readFrames(it)) }
    }

    private fun fromSpotify(trackId: String): FoundAlbum? {
        val page = parseSpotifyTrackPage(get("https://open.spotify.com/track/$trackId"))
        val albumId = page.albumId ?: return null
        val album = synchronized(spotifyAlbums) { spotifyAlbums[albumId] }
            ?: spotify.fetch("spotify:album:$albumId").also { synchronized(spotifyAlbums) { spotifyAlbums[albumId] = it } }
        val artists = album.subtitle?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return FoundAlbum(album.name, artists, page.track, page.year, "Spotify")
    }

    // Apple's rate limit is per app, so its lookups go one at a time even when Spotify's run side by side.
    @Synchronized
    private fun fromItunes(song: Song): FoundAlbum? {
        val primary = song.artists.first()
        val byArtist = itunesByArtist.getOrPut(Tags.key(primary)) {
            parseItunes(itunes("search", "term" to primary, "attribute" to "artistTerm", "entity" to "song", "limit" to "200"))
        }
        val hit = pickItunes(byArtist, song.title, song.artists, song.durationMs)
            ?: pickItunes(parseItunes(itunes("search", "term" to "$primary ${song.title}", "entity" to "song", "limit" to "25")), song.title, song.artists, song.durationMs)
            ?: return null
        // A featured artist puts "A & B" on the track; the album itself is credited to whoever made it.
        val albumArtist = itunesAlbumArtists.getOrPut(hit.albumId) {
            parseItunesArtist(itunes("lookup", "id" to hit.albumId.toString())) ?: hit.artist
        }
        return FoundAlbum(Tags.cleanAlbum(hit.album), Tags.splitArtists(albumArtist), hit.track, hit.year, "Apple Music")
    }

    private fun parseItunesArtist(json: String): String? =
        JSONObject(json).optJSONArray("results")?.optJSONObject(0)?.optString("artistName")?.takeIf { it.isNotEmpty() }

    private var lastItunes = 0L

    /** Apple allows about 20 searches a minute, so these are spaced out and retried once when refused. */
    private fun itunes(endpoint: String, vararg params: Pair<String, String>): String {
        val url = "https://itunes.apple.com/$endpoint".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("country", "GB")
        }.build().toString()
        repeat(3) { attempt ->
            val wait = lastItunes + 3_000 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastItunes = System.currentTimeMillis()
            val request = Request.Builder().url(url).header("User-Agent", "Tide").build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) return response.body?.string().orEmpty()
                if (response.code != 403 && response.code != 429) throw IOException("Apple answered HTTP ${response.code}")
            }
            Thread.sleep(20_000L * (attempt + 1))
        }
        throw IOException("Apple's search is busy")
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36").build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }
}
