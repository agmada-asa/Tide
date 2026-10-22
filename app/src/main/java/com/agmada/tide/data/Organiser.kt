package com.agmada.tide.data

/** Where Tidy up puts files under Music/. */
enum class Layout(val label: String) {
    /** Music/Artist/Album/01 Song.mp3, singles in Music/Artist/Singles/. */
    ArtistAlbum("Artist › Album"),
    /** Music/Artist - Song.mp3, everything side by side. */
    OneFolder("One folder"),
}

/** A file to move: from where it is to [folder] + [fileName]. */
data class Move(val song: Song, val folder: String, val fileName: String) {
    val path: String get() = folder + fileName
}

/**
 * The planning half of Tidy up: what's a duplicate, which folders are really playlists, and where
 * each file should go. Pure, so it's testable; doing it is in [Tidy].
 */
object Organiser {
    const val ROOT = "Music/"

    /**
     * Copies of the same song: same title (ignoring version noise), same main artist, within two
     * seconds of each other. Each group is best copy first: on an album, then the biggest file
     * (usually the better quality), then the oldest.
     */
    fun duplicates(library: Library): List<List<Song>> =
        library.songs.groupBy { Tags.looseTitleKey(it.title) + "|" + Tags.key(it.artists.first()) }.values
            .flatMap { same -> clusterByLength(same) }
            .filter { it.size > 1 }
            .map { group -> group.sortedWith(compareBy<Song>({ it.isSingle }, { -it.size }, { it.dateAdded })) }
            .sortedBy { Library.sortKey(it.first().title) }

    private fun clusterByLength(songs: List<Song>): List<List<Song>> {
        val clusters = mutableListOf<MutableList<Song>>()
        for (song in songs.sortedBy { it.durationMs }) {
            val last = clusters.lastOrNull()
            if (last != null && song.durationMs - last.last().durationMs <= 2_000) last += song else clusters += mutableListOf(song)
        }
        return clusters
    }

    private val topLevel = Regex("^[^/]*/$")
    /** An artist's Singles folder that Tidy up made: songs from many "albums", but not a playlist. */
    private val tidySingles = Regex("^${Regex.escape(ROOT)}[^/]+/Singles/$")

    /**
     * Folders someone made as playlists ("Kijan Boone selections", "!!!"): songs from several albums,
     * or one album that's really a playlist. Top-level folders like Download/ don't count.
     */
    fun playlistFolders(library: Library): List<Folder> = library.folders.filter { folder ->
        if (folder.songs.size < 3 || topLevel.matches(folder.path) || tidySingles.matches(folder.path)) return@filter false
        val albums = folder.songs.map { it.albumKey }.distinct()
        albums.size >= 3 || albums.mapNotNull { library.album(it) }.any { it.looksLikePlaylist }
    }

    /** Where every song should go. Songs already in place are left out. */
    fun plan(library: Library, layout: Layout): List<Move> {
        val taken = library.songs.map { it.path.lowercase() }.toMutableSet()
        val moves = mutableListOf<Move>()
        for (song in library.songs.sortedBy { it.path }) {
            val ext = song.fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".${it.lowercase()}" }
            val (folder, base) = target(song, layout, library)
            if ((folder + base + ext).equals(song.path, ignoreCase = true)) continue
            taken -= song.path.lowercase()
            var name = base + ext
            var n = 2
            while ((folder + name).lowercase() in taken) name = "$base ($n)$ext".also { n++ }
            taken += (folder + name).lowercase()
            moves += Move(song, folder, name)
        }
        // A file that was going to swap places with another is fine: the planned names are all unique.
        return moves
    }

    private fun target(song: Song, layout: Layout, library: Library): Pair<String, String> {
        val artist = clean(if (song.isSingle) song.artists.first() else song.albumArtist.ifEmpty { song.artists.first() })
        return when (layout) {
            Layout.OneFolder -> ROOT to clean("${song.artists.first()} - ${song.title}")
            Layout.ArtistAlbum -> if (song.isSingle) {
                "$ROOT$artist/Singles/" to clean(song.title)
            } else {
                val discs = library.album(song.albumKey)?.songs?.map { it.disc }?.distinct()?.size ?: 1
                val number = when {
                    song.track <= 0 -> ""
                    discs > 1 -> "${song.disc}-${song.track.toString().padStart(2, '0')} "
                    else -> "${song.track.toString().padStart(2, '0')} "
                }
                "$ROOT$artist/${clean(song.album)}/" to clean(number + song.title)
            }
        }
    }

    /** A name that's safe as a file or folder on any filesystem Android uses. */
    fun clean(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().trim('.').take(100).trim().ifEmpty { "Untitled" }
}
