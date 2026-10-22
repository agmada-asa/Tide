package com.agmada.tide.data

/** A file as MediaStore lists it, before any guessing. */
data class RawSong(
    val id: Long,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    /** MediaStore's TRACK: disc × 1000 + track. */
    val track: Int,
    val year: Int,
    val durationMs: Long,
    /** "Music/Artist/Album/", with the trailing slash. */
    val folder: String,
    val fileName: String,
    val dateAdded: Long,
    val mime: String?,
    val size: Long = 0,
) {
    val path: String get() = folder + fileName
}

/**
 * What the user changed by hand in Tide, kept in Tide's database. An empty [album] means "not on an album".
 * [track] and [year] come from looking a song up online.
 */
data class SongEdit(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int? = null,
    val year: Int? = null,
)

data class Song(
    val id: Long,
    val title: String,
    val artists: List<String>,
    val album: String,
    val albumArtist: String,
    val albumKey: String,
    val track: Int,
    val disc: Int,
    val year: Int,
    val durationMs: Long,
    val folder: String,
    val fileName: String,
    val dateAdded: Long,
    val mime: String?,
    val isSingle: Boolean,
    val size: Long = 0,
    /** The artist came from the file name ("Fennick - Undertow.mp3"), so renaming the file would lose it. */
    val namedByFile: Boolean = false,
) {
    val artistLine: String get() = artists.joinToString(", ")
    val path: String get() = folder + fileName
}

data class Album(
    val key: String,
    val title: String,
    /** Everyone credited on the album; most have one. */
    val artists: List<String>,
    val year: Int,
    val songs: List<Song>,
    val isSingle: Boolean,
) {
    /** "DJ Mykael V, 1995". */
    val artist: String get() = artists.joinToString(", ")
    val cover: Song get() = songs.first()
    /** A download tool's playlist with every song tagged as one album ("!!!"): lots of artists, several songs each. */
    val looksLikePlaylist: Boolean get() {
        if (isSingle || artists != listOf(Library.VARIOUS_ARTISTS) || songs.size < 8) return false
        val perArtist = songs.groupingBy { Tags.key(it.artists.first()) }.eachCount()
        return perArtist.size >= 3 && perArtist.values.max() >= 3
    }
    val durationMs: Long get() = songs.sumOf { it.durationMs }
    val dateAdded: Long get() = songs.maxOf { it.dateAdded }
}

/** [albums] are their own; [appearsOn] are other people's albums with a song of theirs, as on Spotify. */
data class Artist(val key: String, val name: String, val songs: List<Song>, val albums: List<Album>, val appearsOn: List<Album> = emptyList())

data class Folder(val path: String, val songs: List<Song>) {
    val name: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "Internal storage" }
}

class Library(
    val songs: List<Song>,
    val albums: List<Album>,
    val artists: List<Artist>,
    val folders: List<Folder>,
) {
    val byId: Map<Long, Song> = songs.associateBy { it.id }
    val byPath: Map<String, Song> = songs.associateBy { it.path }
    private val albumsByKey = albums.associateBy { it.key }
    private val artistsByKey = artists.associateBy { it.key }
    private val byLooseTitle: Map<String, List<Song>> = songs.groupBy { Tags.looseTitleKey(it.title) }

    fun album(key: String): Album? = albumsByKey[key]
    fun artist(key: String): Artist? = artistsByKey[key]
    fun artistFor(name: String): Artist? = artistsByKey[Tags.key(name)]

    /**
     * The song this library has for a title and artist from somewhere else (a Spotify list, a
     * playlist saved before a file was renamed). Version noise like "Remastered" doesn't matter.
     */
    fun match(title: String, artist: String, durationMs: Long, pathHint: String? = null): Song? {
        pathHint?.let { byPath[it] }?.let { return it }
        val candidates = byLooseTitle[Tags.looseTitleKey(title)].orEmpty()
        if (candidates.isEmpty()) return null
        val wanted = Tags.splitArtists(artist, splitAmpersand = true).map(Tags::key).toSet() + Tags.key(artist)
        fun sharesArtist(song: Song): Boolean {
            val theirs = song.artists.map(Tags::key) + Tags.key(song.albumArtist)
            return theirs.any { it in wanted || wanted.any { w -> w.isNotEmpty() && (it.contains(w) || w.contains(it)) } }
        }
        fun gap(song: Song): Long = if (durationMs > 0) kotlin.math.abs(song.durationMs - durationMs) else 0
        val sameArtist = candidates.filter(::sharesArtist)
        if (sameArtist.isNotEmpty()) return sameArtist.minBy(::gap)
        // Untagged files often have no artist at all: trust a unique title of about the same length.
        return candidates.singleOrNull()?.takeIf { durationMs <= 0 || gap(it) < 4_000 }
    }

    companion object {
        val Empty = Library(emptyList(), emptyList(), emptyList(), emptyList())
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val VARIOUS_ARTISTS = "Various artists"

        /**
         * Builds the library the way Spotify would show it. Untagged files take their title and
         * artist from the file name; "feat." moves from the title to the artists; an album split
         * by inconsistent artist tags is put back together when its files share a folder.
         */
        fun build(raws: List<RawSong>, edits: Map<String, SongEdit> = emptyMap()): Library {
            val drafts = raws.map { raw ->
                val edit = edits[raw.path]
                val guess = Tags.parseFileName(raw.fileName)
                val baseName = raw.fileName.substringBeforeLast('.', raw.fileName)
                val titleTag = edit?.title ?: raw.title
                // MediaStore fills a missing title tag with the file name.
                val tagged = !Tags.isUnknown(titleTag) && titleTag!!.trim() != baseName.trim()
                val artistTag = (edit?.artist ?: raw.artist)?.takeIf { !Tags.isUnknown(it) }
                // A file named "Artist - Title" only gives its artist away when the tag agrees or is missing;
                // otherwise the " - " is part of the title ("Song - Remastered 2011").
                val nameHasArtist = guess.artist != null && (artistTag == null || Tags.key(artistTag).contains(Tags.key(guess.artist)))
                val rawTitle = when {
                    tagged -> titleTag!!
                    nameHasArtist -> guess.title
                    else -> Tags.nameWithoutTrack(raw.fileName)
                }
                val (title, featured) = Tags.splitFeaturing(rawTitle)
                val named = when {
                    artistTag != null -> Tags.splitArtists(artistTag)
                    nameHasArtist -> Tags.splitArtists(guess.artist!!)
                    else -> emptyList()
                }
                val artists = (named + featured).distinctBy(Tags::key).ifEmpty { listOf(UNKNOWN_ARTIST) }
                val albumTag = (edit?.album ?: raw.album)?.takeIf { !Tags.isUnknown(it) && (edit?.album != null || !Tags.isFolderAlbum(it, raw.folder)) }
                val albumArtists = (edit?.albumArtist ?: raw.albumArtist)?.takeIf { !Tags.isUnknown(it) }?.let(Tags::splitArtists).orEmpty()
                val track = edit?.track ?: (raw.track % 1000).takeIf { it > 0 } ?: guess.track ?: 0
                val namedByFile = artistTag == null && nameHasArtist
                Draft(raw, title, artists, albumTag?.trim(), albumArtists, track, (raw.track / 1000).coerceAtLeast(1), edit?.year ?: raw.year, namedByFile)
            }
                .let { all ->
                    // "DJ Mykael V & 350" is two people when both turn up on their own elsewhere;
                    // "Simon & Garfunkel" stays one name because "Simon" never does.
                    val solo = all.flatMap { it.artists + it.albumArtists }.filter { !it.contains(" & ") }.map(Tags::key).toSet()
                    fun duo(names: List<String>) = names.flatMap { name ->
                        val parts = name.split(" & ").map(String::trim)
                        if (parts.size > 1 && parts.all { Tags.key(it) in solo }) parts else listOf(name)
                    }.distinctBy(Tags::key)
                    // One spelling per artist everywhere ("marlow kei" on one file shows as "Marlow Kei").
                    val spellings = all.flatMap { duo(it.artists) + duo(it.albumArtists) }.groupBy(Tags::key).mapValues { (_, names) -> mostCommonSpelling(names) }
                    fun canon(names: List<String>) = duo(names).map { spellings[Tags.key(it)] ?: it }
                    all.map { d -> d.copy(artists = canon(d.artists), albumArtists = canon(d.albumArtists)) }
                }

            // Albums: first by name within a folder, whatever the artist tags say...
            val (tagged, singles) = drafts.partition { it.albumTag != null }
            val byFolder = tagged.groupBy { Tags.key(Tags.cleanAlbum(it.albumTag!!)) + "@" + it.raw.folder }
            fun albumArtistsOf(group: List<Draft>): List<String> {
                group.filter { it.albumArtists.isNotEmpty() }.groupBy { it.albumArtists.joinToString("|") { a -> Tags.key(a) } }
                    .maxByOrNull { it.value.size }?.let { return it.value.first().albumArtists }
                val primaries = group.map { it.artists.first() }
                val counts = primaries.groupingBy(Tags::key).eachCount()
                val (topKey, top) = counts.maxBy { it.value }
                return listOf(if (counts.size == 1 || top * 2 > group.size) mostCommonSpelling(primaries.filter { Tags.key(it) == topKey }) else VARIOUS_ARTISTS)
            }
            // ...then the same album and artist across folders (a "CD1" and "CD2" folder, say).
            val merged = byFolder.values
                .map { group -> albumArtistsOf(group) to group }
                .groupBy({ (artists, group) -> Tags.key(Tags.cleanAlbum(group.first().albumTag!!)) + "|" + artists.joinToString("|") { Tags.key(it) } }, { it })

            val songs = mutableListOf<Song>()
            val albums = mutableListOf<Album>()
            for ((key, parts) in merged) {
                val artists = parts.first().first
                val group = parts.flatMap { it.second }
                val title = mostCommonSpelling(group.map { Tags.cleanAlbum(it.albumTag!!) })
                // A one-song album named after its song is a single, whatever the tag calls it.
                val isSingle = group.size == 1 && Tags.looseTitleKey(group.single().title) == Tags.looseTitleKey(title)
                val albumSongs = group.map { it.toSong(title, artists.joinToString(", "), key, isSingle) }
                    .sortedWith(compareBy<Song>({ it.disc }, { it.track.takeIf { t -> t > 0 } ?: Int.MAX_VALUE }, { it.fileName }))
                songs += albumSongs
                albums += Album(key, title, artists, albumSongs.maxOf { it.year }, albumSongs, isSingle)
            }
            for ((key, group) in singles.groupBy { "single:" + Tags.looseTitleKey(it.title) + "|" + Tags.key(it.artists.first()) }) {
                val artists = group.first().albumArtists.ifEmpty { group.first().artists.take(1) }
                val singleSongs = group.map { it.toSong(it.title, artists.joinToString(", "), key, isSingle = true) }
                songs += singleSongs
                albums += Album(key, singleSongs.first().title, artists, singleSongs.maxOf { it.year }, singleSongs, isSingle = true)
            }

            val artistNames = mutableMapOf<String, MutableList<String>>()
            val artistSongs = mutableMapOf<String, MutableList<Song>>()
            for (song in songs) for (name in song.artists) {
                val k = Tags.key(name)
                artistNames.getOrPut(k) { mutableListOf() } += name
                artistSongs.getOrPut(k) { mutableListOf() } += song
            }
            // An album belongs to every one of its artists: "DJ Mykael V, 1995" shows on both pages.
            val albumsByArtist = mutableMapOf<String, MutableList<Album>>()
            for (album in albums) for (name in album.artists) if (name != VARIOUS_ARTISTS) {
                val k = Tags.key(name)
                albumsByArtist.getOrPut(k) { mutableListOf() } += album
                if (k !in artistNames) artistNames[k] = mutableListOf(name)
                artistSongs.getOrPut(k) { mutableListOf() }.let { list -> album.songs.forEach { if (it !in list) list += it } }
            }
            val albumsBySong = albums.filter { !it.isSingle }.flatMap { a -> a.songs.map { it.id to a } }.toMap()
            val artists = artistNames.map { (k, names) ->
                val own = albumsByArtist[k].orEmpty().sortedWith(compareBy({ it.isSingle }, { -it.year }))
                val appearsOn = artistSongs[k].orEmpty().mapNotNull { albumsBySong[it.id] }.distinct().filter { it !in own }.sortedByDescending { it.year }
                Artist(k, mostCommonSpelling(names), artistSongs[k].orEmpty(), own, appearsOn)
            }

            return Library(
                songs = songs.sortedBy { sortKey(it.title) },
                albums = albums.sortedBy { sortKey(it.title) },
                artists = artists.filter { it.name != UNKNOWN_ARTIST || it.songs.isNotEmpty() }.sortedBy { sortKey(it.name) },
                folders = songs.groupBy { it.folder }.map { (path, list) -> Folder(path, list.sortedBy { it.fileName }) }.sortedBy { it.path.lowercase() },
            )
        }

        private fun mostCommonSpelling(names: List<String>): String =
            names.groupingBy { it }.eachCount().maxWith(
                // Most used, then "Marlow Kei" over "marlow kei" and over "MARLOW KEI".
                compareBy<Map.Entry<String, Int>>({ it.value }, { e -> e.key.split(' ').count { w -> w.firstOrNull()?.isUpperCase() == true } }, { -it.key.count(Char::isUpperCase) }),
            ).key

        /** Sorts like Spotify: "The Beatles" under B, case and accents ignored. */
        fun sortKey(text: String): String = Tags.key(text).ifEmpty { text.lowercase() }
    }
}

/** A file part-way through [Library.build]: title and artists settled, album not yet. */
private data class Draft(
    val raw: RawSong,
    val title: String,
    val artists: List<String>,
    val albumTag: String?,
    val albumArtists: List<String>,
    val track: Int,
    val disc: Int,
    val year: Int,
    val namedByFile: Boolean,
) {
    fun toSong(album: String, albumArtist: String, albumKey: String, isSingle: Boolean) = Song(
        id = raw.id, title = title, artists = artists, album = album, albumArtist = albumArtist, albumKey = albumKey,
        track = track, disc = disc, year = year, durationMs = raw.durationMs, folder = raw.folder,
        fileName = raw.fileName, dateAdded = raw.dateAdded, mime = raw.mime, isSingle = isSingle, size = raw.size,
        namedByFile = namedByFile,
    )
}
