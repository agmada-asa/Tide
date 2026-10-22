package com.agmada.tide.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * ID3v2 tags, read and rewritten by hand so the audio after the tag is copied, never decoded.
 * Text and URL frames are decoded. Pictures and other binary frames are skipped when reading
 * and copied byte for byte when rewriting.
 */
object Id3 {
    /** Text and URL frames bigger than this are skipped rather than read. */
    private const val MAX_TEXT = 1L shl 20

    /** A v2.4 frame size that doesn't match the plain reading is checked this far ahead, then trusted. */
    private const val MAX_LOOKAHEAD = 1 shl 20

    private const val PADDING = 1024
    private const val MAX_SIZE = 0x0FFFFFFF

    private val readable: (String, Long) -> Boolean = { id, size -> size <= MAX_TEXT && (id[0] == 'T' || id[0] == 'W') }
    private val spotify = Regex("(?:open\\.spotify\\.com/track/|spotify:track:)([0-9A-Za-z]{22})(?![0-9A-Za-z])")

    /**
     * Reads the text and URL frames of the ID3v2 tag at the start of [input]. Large binary frames
     * (APIC pictures, PRIV, GEOB...) are skipped without being read into memory. Empty map if there's no tag.
     * Keys: the frame id for T*** text frames ("TALB", "TPE1"...), "TXXX:<description>" for user text frames,
     * the frame id for W*** URL frames, "WXXX:<description>" for user URL frames.
     * Values: decoded strings (ISO-8859-1, UTF-16 with BOM, UTF-16BE, UTF-8 encodings all supported);
     * multiple null-separated values (v2.4) joined with "/".
     */
    fun readFrames(input: InputStream): Map<String, String> {
        val h = header(readExact(input, 10) ?: return emptyMap()) ?: return emptyMap()
        if (h.major != 3 && h.major != 4) return emptyMap()
        val walked = if (h.unsync) {
            val body = undoUnsync(readExact(input, h.size.toInt()) ?: return emptyMap())
            walk(ByteArrayInputStream(body), body.size.toLong(), h, readable)
        } else {
            walk(input, h.size, h, readable)
        }
        val values = LinkedHashMap<String, String>()
        for (frame in walked.frames) {
            val (key, value) = entry(frame, h.major) ?: continue
            if (key !in values) values[key] = value
        }
        return values
    }

    /** The Spotify track id in any frame value containing "open.spotify.com/track/<id>" or "spotify:track:<id>" (ids are 22 base62 chars). */
    fun spotifyTrackId(frames: Map<String, String>): String? =
        frames.values.firstNotNullOfOrNull { spotify.find(it)?.groupValues?.get(1) }

    /**
     * The whole file again with text frames set (non-null value) or removed (null). Keys like readFrames:
     * "TALB", "TPE2", "TRCK", "TYER"/"TDRC", "TXXX:desc". Every other frame is kept byte for byte (pictures included),
     * and the audio after the tag is untouched. Keeps the tag's version (2.3 or 2.4); a file with no ID3v2 tag gets a new v2.3 tag.
     * New/changed text frames are written as UTF-16 with BOM in v2.3 and UTF-8 in v2.4.
     * Returns null when it can't safely rewrite: ID3v2.2, tag-level unsynchronisation, compressed/encrypted frames
     * among the ones being changed, or a malformed tag. Callers then leave the file alone.
     * Keep some padding (e.g. 1024 bytes) after the frames.
     */
    fun rewrite(file: ByteArray, changes: Map<String, String?>): ByteArray? {
        val h = if (hasId3(file)) (header(file) ?: return null) else null
        if (h != null && (h.major !in 3..4 || h.unsync)) return null
        val major = h?.major ?: 3
        val audioStart = if (h == null) 0L else 10 + h.size + (if (h.footer) 10 else 0)
        if (audioStart > file.size) return null

        val existing = if (h == null) {
            emptyList()
        } else {
            val walked = walk(ByteArrayInputStream(file, 10, h.size.toInt()), h.size, h) { _, _ -> true }
            if (walked.malformed) return null
            walked.frames
        }

        val wanted = LinkedHashMap<String, Pair<String, String?>>()
        for ((key, value) in changes) wanted[normal(key)] = key to value
        val placed = HashSet<String>()
        val parts = ByteArrayOutputStream()
        for (old in existing) {
            val body = old.body ?: return null
            val p = payload(old, major)
            val key = matchKey(old, p)
            if (p == null && key == null && old.id == "TXXX" && wanted.keys.any { it.startsWith("TXXX:") }) return null
            if (key == null || key !in wanted) {
                parts.write(frame(old.id, old.flags, body, major))
                continue
            }
            if (p == null) return null
            if (!placed.add(key)) continue
            val (origKey, value) = wanted.getValue(key)
            if (value != null) parts.write(newFrame(origKey, value, major) ?: return null)
        }
        for ((key, pair) in wanted) {
            if (key in placed) continue
            val value = pair.second ?: continue
            parts.write(newFrame(pair.first, value, major) ?: return null)
        }

        val size = parts.size() + PADDING
        if (size > MAX_SIZE) return null
        val flags = if (h == null) 0 else h.flags and 0x40.inv() and (if (major == 4) 0x10.inv() else -1)
        val out = ByteArrayOutputStream(10 + size + file.size - audioStart.toInt())
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, flags.toByte()))
        out.write(syncsafeBytes(size))
        parts.writeTo(out)
        out.write(ByteArray(PADDING))
        out.write(file, audioStart.toInt(), file.size - audioStart.toInt())
        return out.toByteArray()
    }

    private class Header(val major: Int, val flags: Int, val size: Long) {
        val extended = (flags and 0x40) != 0
        val unsync = (flags and 0x80) != 0
        val footer = major == 4 && (flags and 0x10) != 0
    }

    /** A frame as stored. [body] is null when it was skipped without being read. */
    private class Frame(val id: String, val flags: Int, val body: ByteArray?)

    private class Walked(val frames: List<Frame>, val malformed: Boolean)

    private fun hasId3(b: ByteArray) = b.size >= 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

    private fun header(b: ByteArray): Header? {
        if (b.size < 10 || !hasId3(b)) return null
        val size = syncsafe(b, 6) ?: return null
        return Header(b[3].toInt() and 0xFF, b[5].toInt() and 0xFF, size)
    }

    /** Forward reads through one tag body. Looks ahead only as far as asked and never past the end of the body. */
    private class Body(private val input: InputStream, private var left: Long) {
        private var ahead = ByteArray(0)

        /** Bytes of the body not yet taken. */
        val unread: Long get() = ahead.size.toLong() + left

        private fun fill(n: Int) {
            if (ahead.size >= n || left <= 0) return
            val want = minOf((n - ahead.size).toLong(), left).toInt()
            val chunk = ByteArray(want)
            var got = 0
            while (got < want) {
                val r = input.read(chunk, got, want - got)
                if (r < 0) break
                got += r
            }
            left = if (got < want) 0L else left - got
            ahead += chunk.copyOf(got)
        }

        /** The next [n] bytes, not taken. Fewer at the end of the body. */
        fun peek(n: Int): ByteArray {
            fill(n)
            return ahead.copyOf(minOf(n, ahead.size))
        }

        /** Takes the next [n] bytes. The caller checks [unread] first. */
        fun take(n: Int): ByteArray {
            val out = peek(n)
            ahead = ahead.copyOfRange(out.size, ahead.size)
            return out
        }

        /** Skips [n] bytes without holding on to them. */
        fun skip(n: Long) {
            val fromAhead = minOf(n, ahead.size.toLong()).toInt()
            ahead = ahead.copyOfRange(fromAhead, ahead.size)
            var rest = n - fromAhead
            val scratch = ByteArray(8192)
            while (rest > 0 && left > 0) {
                val r = input.read(scratch, 0, minOf(rest, left, scratch.size.toLong()).toInt())
                if (r < 0) {
                    left = 0
                    break
                }
                rest -= r
                left -= r
            }
        }
    }

    /** The frames of one tag body of [budget] bytes, starting after the 10-byte header. */
    private fun walk(source: InputStream, budget: Long, h: Header, keep: (String, Long) -> Boolean): Walked {
        val body = Body(source, budget)
        if (h.extended) {
            if (body.unread < 4) return Walked(emptyList(), true)
            val ext = body.take(4)
            // v2.3 counts the extended header's size without its own four bytes, v2.4 counts them.
            val rest = (if (h.major == 4) syncsafe(ext, 0)?.minus(4) else u32(ext, 0)) ?: return Walked(emptyList(), true)
            if (rest < 0 || rest > body.unread) return Walked(emptyList(), true)
            body.skip(rest)
        }
        val frames = mutableListOf<Frame>()
        while (body.unread >= 10) {
            if (body.peek(1)[0] == 0.toByte()) break // padding
            val head = body.take(10)
            val id = String(head, 0, 4, Charsets.ISO_8859_1)
            if (!id.all { validIdChar(it) }) return Walked(frames, true)
            val flags = ((head[8].toInt() and 0xFF) shl 8) or (head[9].toInt() and 0xFF)
            val size = frameSize(body, h.major, head)
            if (size < 0 || size > body.unread) return Walked(frames, true)
            if (keep(id, size)) {
                frames += Frame(id, flags, body.take(size.toInt()))
            } else {
                body.skip(size)
                frames += Frame(id, flags, null)
            }
        }
        return Walked(frames, false)
    }

    /**
     * The body size of a frame. v2.4 sizes are syncsafe, but some taggers write plain sizes. A syncsafe
     * size is used when a frame or padding starts there, otherwise the plain size is.
     */
    private fun frameSize(body: Body, major: Int, head: ByteArray): Long {
        val plain = u32(head, 4)
        if (major != 4) return plain
        val sync = syncsafe(head, 4) ?: return plain
        return when {
            sync == plain -> sync
            sync > body.unread -> plain
            sync > MAX_LOOKAHEAD -> sync
            frameStartsAt(body, sync.toInt()) -> sync
            else -> plain
        }
    }

    private fun frameStartsAt(body: Body, end: Int): Boolean {
        if (end.toLong() == body.unread) return true
        val at = body.peek(end + 4)
        if (at.size <= end) return false
        if (at[end] == 0.toByte()) return true
        return at.size >= end + 4 && (0 until 4).all { validIdChar((at[end + it].toInt() and 0xFF).toChar()) }
    }

    private fun validIdChar(c: Char) = c in 'A'..'Z' || c in '0'..'9'

    /** The bytes a frame's text is in: after its group byte and length indicator, un-unsynchronised, or null if it can't be read. */
    private fun payload(frame: Frame, major: Int): ByteArray? {
        var p = frame.body ?: return null
        val flags = frame.flags and 0xFF
        if (major == 4) {
            if ((flags and 0x0C) != 0) return null // compressed or encrypted
            if ((flags and 0x40) != 0) p = tail(p, 1)
            if ((flags and 0x01) != 0) p = tail(p, 4)
            if ((flags and 0x02) != 0) p = undoUnsync(p)
        } else {
            if ((flags and 0xC0) != 0) return null // compressed or encrypted
            if ((flags and 0x20) != 0) p = tail(p, 1)
        }
        return p.takeIf { it.isNotEmpty() }
    }

    private fun tail(b: ByteArray, n: Int) = b.copyOfRange(minOf(n, b.size), b.size)

    private fun entry(frame: Frame, major: Int): Pair<String, String>? {
        val p = payload(frame, major) ?: return null
        val (key, value) = when {
            frame.id == "TXXX" -> userFrame(p, iso = false)?.let { "TXXX:${it.first}" to it.second } ?: return null
            frame.id == "WXXX" -> userFrame(p, iso = true)?.let { "WXXX:${it.first}" to it.second } ?: return null
            frame.id[0] == 'T' -> frame.id to text(p[0].toInt(), p.copyOfRange(1, p.size))
            frame.id[0] == 'W' -> frame.id to text(0, p)
            else -> return null
        }
        return if (value.isEmpty()) null else key to value
    }

    /** A TXXX or WXXX body: encoding, description, NUL, then the value. */
    private fun userFrame(p: ByteArray, iso: Boolean): Pair<String, String>? {
        val enc = p[0].toInt()
        val wide = enc == 1 || enc == 2
        val end = terminator(p, wide)
        if (end < 0) return null
        val desc = text(enc, p.copyOfRange(1, end))
        val from = minOf(end + (if (wide) 2 else 1), p.size)
        return desc to text(if (iso) 0 else enc, p.copyOfRange(from, p.size))
    }

    /** Index of the NUL that ends a description, or -1. UTF-16 terminators are two bytes and sit on an even offset. */
    private fun terminator(p: ByteArray, wide: Boolean): Int {
        val step = if (wide) 2 else 1
        var i = 1
        while (i + step <= p.size) {
            if (p[i] == 0.toByte() && (!wide || p[i + 1] == 0.toByte())) return i
            i += step
        }
        return -1
    }

    /** A text frame's value. Several null-separated values are joined with "/". */
    private fun text(enc: Int, bytes: ByteArray): String {
        val decoded = when (enc) {
            1 -> when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
                else -> String(bytes, Charsets.UTF_16LE)
            }
            2 -> String(bytes, Charsets.UTF_16BE)
            3 -> String(bytes, Charsets.UTF_8)
            else -> String(bytes, Charsets.ISO_8859_1)
        }
        return decoded.split('\u0000')
            .map { it.trim { c -> c.isWhitespace() || c == '﻿' } }
            .filter { it.isNotEmpty() }
            .joinToString("/")
    }

    private fun matchKey(frame: Frame, p: ByteArray?): String? = when {
        frame.id == "TXXX" -> p?.let { userFrame(it, iso = false) }?.let { "TXXX:" + it.first.lowercase() }
        frame.id[0] == 'T' -> frame.id
        else -> null
    }

    /** Keys as readFrames gives them, with user-frame descriptions lower-cased so they match without case. */
    private fun normal(key: String): String = if (key.startsWith("TXXX:")) "TXXX:" + key.substring(5).lowercase() else key

    private fun newFrame(key: String, value: String, major: Int): ByteArray? {
        if (key.startsWith("TXXX:")) return frame("TXXX", 0, userBody(key.substring(5), value, major), major)
        if (key.length != 4 || !key.all { validIdChar(it) }) return null
        val body = if (major == 4) concat(byteArrayOf(3), value.toByteArray(Charsets.UTF_8))
        else concat(byteArrayOf(1), BOM, value.toByteArray(Charsets.UTF_16LE))
        return frame(key, 0, body, major)
    }

    private fun userBody(desc: String, value: String, major: Int): ByteArray =
        if (major == 4) {
            concat(byteArrayOf(3), desc.toByteArray(Charsets.UTF_8), byteArrayOf(0), value.toByteArray(Charsets.UTF_8))
        } else {
            concat(
                byteArrayOf(1), BOM, desc.toByteArray(Charsets.UTF_16LE), byteArrayOf(0, 0),
                BOM, value.toByteArray(Charsets.UTF_16LE),
            )
        }

    private val BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    /** A whole frame: id, size in the tag's encoding, flags, body. */
    private fun frame(id: String, flags: Int, body: ByteArray, major: Int): ByteArray {
        val out = ByteArrayOutputStream(10 + body.size)
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        out.write(if (major == 4) syncsafeBytes(body.size) else u32Bytes(body.size))
        out.write(byteArrayOf((flags ushr 8).toByte(), flags.toByte()))
        out.write(body)
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    private fun undoUnsync(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        for (i in b.indices) {
            if (i > 0 && b[i] == 0.toByte() && b[i - 1] == 0xFF.toByte()) continue
            out.write(b[i].toInt())
        }
        return out.toByteArray()
    }

    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) return null
            got += r
        }
        return out
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    /** Four 7-bit bytes, or null if any has its top bit set. */
    private fun syncsafe(b: ByteArray, o: Int): Long? {
        var v = 0L
        for (i in 0 until 4) {
            val x = b[o + i].toInt() and 0xFF
            if (x >= 0x80) return null
            v = (v shl 7) or x.toLong()
        }
        return v
    }

    private fun syncsafeBytes(n: Int): ByteArray = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
    )

    private fun u32Bytes(n: Int): ByteArray = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
}
