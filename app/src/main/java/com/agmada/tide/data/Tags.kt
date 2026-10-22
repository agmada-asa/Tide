package com.agmada.tide.data

import java.text.Normalizer

/**
 * Forgiving tag reading. Most stock players trust every tag exactly as written, so one stray
 * space or a "feat." in the title splits an album in two. These helpers decide what a
 * file most likely is, the way Spotify would show it, without touching the file.
 */
object Tags {
    private val unknowns = setOf("", "<unknown>", "unknown", "unknown artist", "unknown album", "various", "various artists", "va", "untitled")

    /** Folders MediaStore names an untagged file's album after. They aren't albums. */
    private val genericFolders = setOf(
        "music", "download", "downloads", "audio", "sounds", "media", "songs", "mp3", "telegram audio",
        "whatsapp audio", "recordings", "voice recorder", "call", "snaptube", "0", "emulated",
    )

    fun isUnknown(value: String?): Boolean = value == null || value.trim().lowercase() in unknowns

    /** A comparison key: case, accents, punctuation and "&"/"and" don't matter. */
    fun key(value: String): String {
        val folded = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return folded
            .replace("&", " and ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("^the "), "")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    private val featInBrackets = Regex("\\s*[(\\[]\\s*(?:feat\\.?|ft\\.?|featuring|with)\\s+([^)\\]]+)[)\\]]", RegexOption.IGNORE_CASE)
    private val featTrailing = Regex("\\s+(?:feat\\.?|ft\\.?|featuring)\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** "Song (feat. X & Y)" → "Song" and [X, Y], so X shows as an artist rather than in the title. */
    fun splitFeaturing(title: String): Pair<String, List<String>> {
        val featured = mutableListOf<String>()
        var clean = featInBrackets.replace(title) { featured += splitArtists(it.groupValues[1], splitAmpersand = true); "" }
        featTrailing.find(clean)?.let { featured += splitArtists(it.groupValues[1], splitAmpersand = true); clean = clean.substring(0, it.range.first) }
        return clean.trim().ifEmpty { title.trim() } to featured
    }

    private val artistSeparators = Regex("\\s*(?:;|/|,|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s+x\\s+|\\s+with\\s+)\\s*", RegexOption.IGNORE_CASE)
    private val listMark = Regex("[,;/]")

    /** Names with a comma, slash or "&" in them that are one artist, not a list. */
    private val wholeNames = listOf(
        "AC/DC", "Tyler, The Creator", "Earth, Wind & Fire", "Crosby, Stills, Nash & Young", "Crosby, Stills & Nash",
        "Emerson, Lake & Palmer", "Peter, Paul and Mary", "Blood, Sweat & Tears", "Hootie & the Blowfish",
    ).associateBy { key(it) }

    /**
     * One artist tag as separate artists. "&" stays inside a name ("Simon & Garfunkel") unless the tag
     * is clearly a list ("A, B & C") or the caller knows it is one, like the names after "feat.".
     */
    fun splitArtists(value: String, splitAmpersand: Boolean = false): List<String> {
        wholeNames[key(value)]?.let { return listOf(value.trim()) }
        var rest = value
        val kept = mutableListOf<String>()
        // Pull out known names first so "Tyler, The Creator & Kali Uchis" keeps Tyler whole.
        for (name in wholeNames.values) {
            val at = Regex(Regex.escape(name), RegexOption.IGNORE_CASE).find(rest) ?: continue
            kept += name
            rest = rest.removeRange(at.range).let { r -> r.replace(Regex("^\\s*(?:[,;/&]|and\\b)\\s*|\\s*(?:[,;/&]|and)\\s*$", RegexOption.IGNORE_CASE), "") }
        }
        val isList = splitAmpersand || listMark.containsMatchIn(value)
        val pattern = if (isList) Regex("${artistSeparators.pattern}|\\s+&\\s+" + if (splitAmpersand) "|\\s+and\\s+" else "", RegexOption.IGNORE_CASE) else artistSeparators
        val split = if (rest.isBlank()) emptyList() else rest.split(pattern)
        return (kept + split).map { it.trim() }.filter { !isUnknown(it) }.distinctBy { key(it) }
    }

    /** What a file's name says about it, for files with missing tags. */
    data class FileGuess(val track: Int?, val artist: String?, val title: String)

    private val leadingTrack = Regex("^(\\d{1,3})(?:\\s*[-._)]\\s*|\\s+)(.+)$")

    fun parseFileName(fileName: String): FileGuess {
        var name = fileName.substringBeforeLast('.', fileName).trim()
        if (!name.contains(' ')) name = name.replace('_', ' ')
        var track: Int? = null
        leadingTrack.find(name)?.let { match ->
            // "1979 - Song" is more likely a title than track 1979.
            val number = match.groupValues[1].toInt()
            if (number in 1..199) { track = number; name = match.groupValues[2] }
        }
        val parts = name.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            FileGuess(track, parts[0].trim(), parts[1].trim())
        } else {
            FileGuess(track, null, name.trim())
        }
    }

    /** The file name without its extension or a leading track number, for when its "Artist - " split is wrong. */
    fun nameWithoutTrack(fileName: String): String {
        val guess = parseFileName(fileName)
        return if (guess.artist != null) "${guess.artist} - ${guess.title}" else guess.title
    }

    private val releaseSuffix = Regex("\\s*(?:-\\s*(?:single|ep)|[(\\[](?:single|ep)[)\\]])\\s*$", RegexOption.IGNORE_CASE)

    /** "Name - Single" → "Name": stores add these, and Spotify doesn't show them. */
    fun cleanAlbum(album: String): String = releaseSuffix.replace(album, "").trim().ifEmpty { album.trim() }

    /** True when MediaStore made the album up from the folder name. */
    fun isFolderAlbum(album: String, folder: String?): Boolean {
        val leaf = folder?.trimEnd('/')?.substringAfterLast('/')?.trim().orEmpty()
        val albumKey = album.trim().lowercase()
        return albumKey in genericFolders || (leaf.isNotEmpty() && albumKey == leaf.lowercase() && leaf.lowercase() in genericFolders)
    }

    private val versionNoise = Regex(
        "\\s*(?:[(\\[][^)\\]]*(?:remaster|version|edit|mono|stereo|live|explicit|clean|deluxe|bonus|mix|from)[^)\\]]*[)\\]]" +
            "|-\\s*(?:\\d{4}\\s+)?(?:remaster(?:ed)?|radio edit|single version|album version|mono|stereo|live)[^-]*)",
        RegexOption.IGNORE_CASE,
    )

    /** A title key that ignores "(Remastered 2011)", "- Radio Edit" and "feat." parts, for matching across sources. */
    fun looseTitleKey(title: String): String = key(versionNoise.replace(splitFeaturing(title).first, ""))
}
