package com.agmada.tide

import com.agmada.tide.data.Id3
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class Id3Test {
    private val audio = ByteArray(400) { i -> intArrayOf(0xFF, 0xFB, 0x90, 0x00)[i % 4].toByte() }

    private fun syncsafe(n: Int) = byteArrayOf(
        ((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
    )

    private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    /** A frame: id, size (syncsafe in v2.4, plain otherwise), flags, body. */
    private fun frame(id: String, body: ByteArray, major: Int = 3, flags: Int = 0): ByteArray {
        val size = if (major == 4) syncsafe(body.size) else be32(body.size)
        return id.toByteArray(Charsets.ISO_8859_1) + size + byteArrayOf((flags shr 8).toByte(), flags.toByte()) + body
    }

    private fun tag(major: Int, frames: ByteArray, flags: Int = 0, padding: Int = 0, audio: ByteArray = this.audio): ByteArray =
        "ID3".toByteArray() + byteArrayOf(major.toByte(), 0, flags.toByte()) + syncsafe(frames.size + padding) +
            frames + ByteArray(padding) + audio

    private fun utf16(s: String) = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)
    private fun latin(s: String) = byteArrayOf(0) + s.toByteArray(Charsets.ISO_8859_1)
    private fun utf8(s: String) = byteArrayOf(3) + s.toByteArray(Charsets.UTF_8)

    /** A v2.3 TXXX body in UTF-16: each string has its own BOM, the description ends with two NULs. */
    private fun userUtf16(desc: String, value: String) =
        byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + desc.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0) +
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)

    private fun txxxLatin(desc: String, value: String) = byteArrayOf(0) + desc.toByteArray() + byteArrayOf(0) + value.toByteArray()

    private fun read(bytes: ByteArray) = Id3.readFrames(ByteArrayInputStream(bytes))

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        for (i in 0..size - needle.size) if (copyOfRange(i, i + needle.size).contentEquals(needle)) return i
        return -1
    }

    private val picture = ByteArray(5000) { i -> (i * 37 % 251).toByte() }

    @Test fun readsV23UnicodeAndLatinText() {
        val file = tag(3, frame("TALB", utf16("Album Name")) + frame("TPE1", latin("Artist")) +
            frame("TXXX", userUtf16("CATALOG_URL", "https://example.com/x")))
        val frames = read(file)
        assertEquals("Album Name", frames["TALB"])
        assertEquals("Artist", frames["TPE1"])
        assertEquals("https://example.com/x", frames["TXXX:CATALOG_URL"])
    }

    @Test fun readsV24UtfEightWithSyncsafeSizesAndMultipleValues() {
        val long = "x".repeat(300)
        val file = tag(4, frame("TPE1", utf8("First\u0000Second"), major = 4) +
            frame("TXXX", byteArrayOf(3) + "NOTE".toByteArray() + byteArrayOf(0) + long.toByteArray(), major = 4) +
            frame("TIT2", utf8("Title"), major = 4))
        val frames = read(file)
        assertEquals("First/Second", frames["TPE1"])
        assertEquals(long, frames["TXXX:NOTE"])
        assertEquals("Title", frames["TIT2"])
    }

    @Test fun skipsLargePictureFrame() {
        val file = tag(3, frame("APIC", byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3, 0) + picture) +
            frame("TALB", utf16("After Picture")))
        val frames = read(file)
        assertEquals("After Picture", frames["TALB"])
        assertTrue(frames.keys.none { it.startsWith("APIC") })
    }

    @Test fun spotifyTrackIdFromTxxxAndWxxx() {
        val id = "7lrw5VPoBgIChkAEgn4MiT"
        val txxx = tag(3, frame("TXXX", txxxLatin("CATALOG_URL", "https://open.spotify.com/track/$id")))
        assertEquals(id, Id3.spotifyTrackId(read(txxx)))

        val wxxx = tag(3, frame("WXXX", byteArrayOf(0) + "src".toByteArray() + byteArrayOf(0) + "spotify:track:$id".toByteArray()))
        assertEquals(id, Id3.spotifyTrackId(read(wxxx)))
        assertNull(Id3.spotifyTrackId(mapOf("TALB" to "No link here")))
    }

    @Test fun rewriteChangesTextKeepsPictureAndAudio() {
        val apic = frame("APIC", byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3, 0) + picture)
        val file = tag(3, frame("TALB", utf16("Old")) + apic + frame("TRCK", latin("3")) +
            frame("TXXX", userUtf16("CATALOG_URL", "https://open.spotify.com/track/7lrw5VPoBgIChkAEgn4MiT")))
        val out = Id3.rewrite(file, mapOf("TALB" to "madmax", "TPE2" to "Vennisay", "TRCK" to null))!!

        assertEquals(3, out[3].toInt())
        val frames = read(out)
        assertEquals("madmax", frames["TALB"])
        assertEquals("Vennisay", frames["TPE2"])
        assertNull(frames["TRCK"])
        assertEquals("7lrw5VPoBgIChkAEgn4MiT", Id3.spotifyTrackId(frames))
        assertTrue(out.indexOf(apic) >= 0)
        assertArrayEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
    }

    @Test fun rewriteOfV24KeepsVersionAndWritesUtf8() {
        val file = tag(4, frame("TALB", utf8("Old"), major = 4) + frame("TIT2", utf8("Keep"), major = 4))
        val out = Id3.rewrite(file, mapOf("TALB" to "Ü new")) ?: error("null")
        assertEquals(4, out[3].toInt())
        val frames = read(out)
        assertEquals("Ü new", frames["TALB"])
        assertEquals("Keep", frames["TIT2"])
        assertArrayEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
    }

    @Test fun rewriteOfFileWithoutTagAddsV23TagAndKeepsAudio() {
        val out = Id3.rewrite(audio, mapOf("TALB" to "Added", "TPE2" to "Band")) ?: error("null")
        assertEquals(3, out[3].toInt())
        val frames = read(out)
        assertEquals("Added", frames["TALB"])
        assertEquals("Band", frames["TPE2"])
        assertArrayEquals(audio, out.copyOfRange(out.size - audio.size, out.size))
    }

    @Test fun extendedHeaderIsSkippedAndDroppedOnRewrite() {
        val extended = be32(6) + byteArrayOf(0, 0, 0, 0, 0, 0)
        val file = tag(3, extended + frame("TALB", utf16("Ext")), flags = 0x40)
        assertEquals("Ext", read(file)["TALB"])

        val out = Id3.rewrite(file, mapOf("TPE2" to "Band")) ?: error("null")
        assertEquals(0, out[5].toInt() and 0x40)
        val frames = read(out)
        assertEquals("Ext", frames["TALB"])
        assertEquals("Band", frames["TPE2"])
    }

    @Test fun v22AndTagLevelUnsynchronisationReturnNullOnRewrite() {
        val v22 = "ID3".toByteArray() + byteArrayOf(2, 0, 0) + syncsafe(0) + audio
        assertNull(Id3.rewrite(v22, mapOf("TALB" to "x")))
        assertNull(read(v22)["TALB"])

        val unsync = tag(3, frame("TALB", utf16("u")), flags = 0x80)
        assertNull(Id3.rewrite(unsync, mapOf("TALB" to "x")))
    }

    @Test fun fileWithoutTagHasNoFrames() {
        assertTrue(read(audio).isEmpty())
    }

    @Test fun v24PlainSizeIsUsedWhenSyncsafeLandsOnNothing() {
        // A plain 300 is 00 00 01 2C, which also reads as a valid syncsafe 172. Nothing starts there.
        val body = utf8("p".repeat(299))
        val plain = "TALB".toByteArray() + be32(body.size) + byteArrayOf(0, 0) + body
        val file = tag(4, plain + frame("TIT2", utf8("Second"), major = 4))
        val frames = read(file)
        assertEquals("p".repeat(299), frames["TALB"])
        assertEquals("Second", frames["TIT2"])
    }
}
