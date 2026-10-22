package com.agmada.tide.importer

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

enum class RemoteKind { Track, Album, Playlist }

data class RemoteTrack(val title: String, val artists: List<String>, val durationMs: Long)

/**
 * A track, album or playlist read from Spotify's public embed page.
 * [truncated] is true only when the embed says it has cut the list short.
 */
data class RemoteCollection(
    val kind: RemoteKind,
    val name: String,
    val subtitle: String?,
    val coverUrl: String?,
    val tracks: List<RemoteTrack>,
    val sourceUrl: String,
    val truncated: Boolean,
)

private const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

object SpotifyLinks {
    // open.spotify.com/track|album|playlist/<id>, with an optional intl-xx path part,
    // or spotify:track|album|playlist:<id>. Spotify ids are always 22 base62 characters.
    private val linkPattern = Regex(
        """(?:(?:https?://)?open\.spotify\.com/(?:intl-[A-Za-z-]+/)?|spotify:)(track|album|playlist)[/:]([A-Za-z0-9]{22})"""
    )

    // Share links from the Spotify app. They need resolving before we know the kind and id.
    private val shortLinkPattern = Regex("""(?:https?://)?spotify\.link/[A-Za-z0-9]+""")

    /** Kind and id from any Spotify link form, or null if it isn't one. Short spotify.link URLs return null here (they need resolving). */
    fun parse(text: String): Pair<RemoteKind, String>? {
        val match = linkPattern.find(text) ?: return null
        val (what, id) = match.destructured
        val kind = when (what) {
            "track" -> RemoteKind.Track
            "album" -> RemoteKind.Album
            else -> RemoteKind.Playlist
        }
        return kind to id
    }

    fun isShortLink(text: String): Boolean = shortLinkPattern.containsMatchIn(text)

    /** The first spotify.link URL in the text, with https:// added if it was missing. */
    internal fun firstShortUrl(text: String): String? {
        val found = shortLinkPattern.find(text)?.value ?: return null
        return if (found.startsWith("http")) found else "https://$found"
    }
}

private val nextDataPattern = Regex(
    """<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""",
    RegexOption.DOT_MATCHES_ALL
)

/**
 * Pure parser so it can be unit-tested: the embed page HTML in, the collection out.
 * Throws IllegalArgumentException with a plain message if the shape isn't recognised.
 */
fun parseSpotifyEmbed(html: String, kind: RemoteKind, sourceUrl: String): RemoteCollection {
    val json = nextDataPattern.find(html)?.let { it.groupValues[1] }
        ?: throw IllegalArgumentException("This Spotify page has no music data")

    val root = try {
        JSONObject(json)
    } catch (e: JSONException) {
        throw IllegalArgumentException("This Spotify page has unreadable music data", e)
    }

    val entity = root.optJSONObject("props")
        ?.optJSONObject("pageProps")
        ?.optJSONObject("state")
        ?.optJSONObject("data")
        ?.optJSONObject("entity")
        ?: throw IllegalArgumentException("This Spotify page has no music data")

    val name = entity.optString("name").trim()
    if (name.isEmpty()) throw IllegalArgumentException("This Spotify page has no name")

    val (subtitle, tracks) = when (kind) {
        RemoteKind.Track -> {
            // A single track has no subtitle of its own, so the artists stand in for it.
            val artists = namesOf(entity.optJSONArray("artists"))
            val track = RemoteTrack(name, artists, entity.optLong("duration", 0L))
            artists.joinToString(", ").ifEmpty { null } to listOf(track)
        }
        RemoteKind.Album, RemoteKind.Playlist -> {
            val items = entity.optJSONArray("trackList")
                ?: throw IllegalArgumentException("This Spotify page has no track list")
            entity.optString("subtitle").ifEmpty { null } to tracksOf(items)
        }
    }

    return RemoteCollection(
        kind = kind,
        name = name,
        subtitle = subtitle,
        coverUrl = coverUrl(entity),
        tracks = tracks,
        sourceUrl = sourceUrl,
        // The embed JSON has no total track count, but embeds stop at 100 tracks
        // (seen on 2026-10-22), so a playlist of exactly 100 has most likely been cut short.
        truncated = kind == RemoteKind.Playlist && tracks.size >= 100,
    )
}

private fun tracksOf(items: JSONArray): List<RemoteTrack> {
    val out = ArrayList<RemoteTrack>(items.length())
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        out.add(
            RemoteTrack(
                title = item.optString("title"),
                artists = splitArtists(item.optString("subtitle")),
                durationMs = item.optLong("duration", 0L),
            )
        )
    }
    return out
}

// Embed subtitles look like "Artist A, Artist B". Swap the non-breaking spaces, then split.
private fun splitArtists(subtitle: String): List<String> =
    subtitle.replace('\u00A0', ' ')
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun namesOf(items: JSONArray?): List<String> {
    if (items == null) return emptyList()
    val names = ArrayList<String>()
    for (i in 0 until items.length()) {
        val name = items.optJSONObject(i)?.optString("name").orEmpty()
        if (name.isNotEmpty()) names.add(name)
    }
    return names
}

// Playlists carry coverArt.sources. Albums and tracks don't, so fall back to the
// largest image in visualIdentity.
private fun coverUrl(entity: JSONObject): String? {
    val fromCoverArt = entity.optJSONObject("coverArt")
        ?.optJSONArray("sources")
        ?.optJSONObject(0)
        ?.optString("url")
        .orEmpty()
    if (fromCoverArt.isNotEmpty()) return fromCoverArt

    val images = entity.optJSONObject("visualIdentity")?.optJSONArray("image") ?: return null
    var best: String? = null
    var bestWidth = -1
    for (i in 0 until images.length()) {
        val image = images.optJSONObject(i) ?: continue
        val width = image.optInt("maxWidth", 0)
        val url = image.optString("url")
        if (url.isNotEmpty() && width > bestWidth) {
            best = url
            bestWidth = width
        }
    }
    return best
}

private fun RemoteKind.path(): String = when (this) {
    RemoteKind.Track -> "track"
    RemoteKind.Album -> "album"
    RemoteKind.Playlist -> "playlist"
}

class SpotifyImporter(private val http: OkHttpClient) {

    /** Blocking; call off the main thread. Resolves short links, fetches the embed page, parses it. */
    fun fetch(link: String): RemoteCollection {
        val (kind, id) = resolve(link)
        val sourceUrl = "https://open.spotify.com/${kind.path()}/$id"
        val embedUrl = "https://open.spotify.com/embed/${kind.path()}/$id"

        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT)
            .build()

        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Spotify answered HTTP ${response.code} for that link")
            }
            val html = response.body?.string() ?: throw IOException("Spotify sent an empty page")
            parseSpotifyEmbed(html, kind, sourceUrl)
        }
    }

    private fun resolve(link: String): Pair<RemoteKind, String> {
        SpotifyLinks.parse(link)?.let { return it }

        val short = SpotifyLinks.firstShortUrl(link)
            ?: throw IllegalArgumentException("That isn't a Spotify link")

        // spotify.link doesn't redirect straight to open.spotify.com. It lands on a Branch
        // page (spotify.app.link) whose script contains the real open.spotify.com URL.
        val following = http.newBuilder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        val request = Request.Builder()
            .url(short)
            .header("User-Agent", USER_AGENT)
            .build()

        return following.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Could not open that short link (HTTP ${response.code})")
            }
            val page = response.body?.string().orEmpty()
            SpotifyLinks.parse(page)
                ?: throw IllegalArgumentException("Could not find the Spotify item behind that short link")
        }
    }
}
