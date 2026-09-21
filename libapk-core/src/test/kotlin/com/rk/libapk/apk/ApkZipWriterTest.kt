package com.rk.libapk.apk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApkZipWriterTest {

    @TempDir
    lateinit var tempDir: Path

    private val writer = ApkZipWriter()

    @Test
    fun `round trips stored and deflated entries`() {
        val stored = "hello stored".toByteArray()
        val deflated = "hello deflated, and a bit longer so deflate actually helps".repeat(50).toByteArray()
        val target = file("round-trip.apk")

        val result = writer.write(
            target,
            listOf(
                ApkEntry.stored("resources.arsc", stored, ZipAlignments.RESOURCES),
                ApkEntry.deflated("classes.dex", deflated),
            ),
        )

        assertEquals(2, result.entryCount)
        assertEquals(target.length(), result.size)

        ZipFile(target).use { zip ->
            val arsc = zip.getEntry("resources.arsc")
            val dex = zip.getEntry("classes.dex")
            assertEquals(0, arsc.method, "resources.arsc must be STORED")
            assertEquals(8, dex.method, "classes.dex must be DEFLATED")
            assertContentEquals(stored, zip.getInputStream(arsc).readBytes())
            assertContentEquals(deflated, zip.getInputStream(dex).readBytes())
        }
    }

    @Test
    fun `aligns stored entries by inserting a padding extra field`() {
        val target = file("aligned.apk")
        // An odd-sized deflated entry first, so subsequent entries do not land on boundaries by luck.
        val entries = listOf(
            ApkEntry.deflated("assets/blob.bin", ByteArray(7) { it.toByte() }),
            ApkEntry.stored("resources.arsc", ByteArray(100) { 1 }, ZipAlignments.RESOURCES),
            ApkEntry.deflated("classes.dex", ByteArray(33) { 2 }),
            ApkEntry.stored("lib/arm64-v8a/libnative.so", ByteArray(4096) { 3 }, ZipAlignments.NATIVE_LIB),
        )

        writer.write(target, entries)

        val arscOffset = localHeaderDataOffset(target, "resources.arsc")
        val soOffset = localHeaderDataOffset(target, "lib/arm64-v8a/libnative.so")
        assertEquals(0L, arscOffset % ZipAlignments.RESOURCES, "resources.arsc data offset $arscOffset")
        assertEquals(0L, soOffset % ZipAlignments.NATIVE_LIB, "libnative.so data offset $soOffset")

        // Content must survive the padding.
        ZipFile(target).use { zip ->
            assertContentEquals(ByteArray(4096) { 3 }, zip.getInputStream(zip.getEntry("lib/arm64-v8a/libnative.so")).readBytes())
        }
    }

    @Test
    fun `pads by a whole alignment step when the gap is smaller than an extra field`() {
        val target = file("small-gap.apk")
        // Sweep a range of preceding sizes; every stored entry must still be 4-byte aligned.
        for (prefixSize in 0..16) {
            val entries = listOf(
                ApkEntry.deflated("a.bin", ByteArray(prefixSize)),
                ApkEntry.stored("resources.arsc", ByteArray(8), ZipAlignments.RESOURCES),
            )
            writer.write(target, entries)
            val offset = localHeaderDataOffset(target, "resources.arsc")
            assertEquals(0L, offset % ZipAlignments.RESOURCES, "prefix=$prefixSize offset=$offset")
            ZipFile(target).use { zip ->
                assertContentEquals(ByteArray(8), zip.getInputStream(zip.getEntry("resources.arsc")).readBytes())
            }
        }
    }

    @Test
    fun `is deterministic`() {
        val entries = listOf(
            ApkEntry.stored("resources.arsc", ByteArray(64) { (it * 3).toByte() }, ZipAlignments.RESOURCES),
            ApkEntry.deflated("classes.dex", "some bytecode".repeat(200).toByteArray()),
        )
        val first = file("deterministic-1.apk")
        val second = file("deterministic-2.apk")
        writer.write(first, entries)
        writer.write(second, entries)
        assertContentEquals(first.readBytes(), second.readBytes())
    }

    @Test
    fun `streams large deflated entries to a temp file and stays correct`() {
        val big = Random(42).nextBytes(2 * 1024 * 1024)
        val target = file("big.apk")

        writer.write(target, listOf(ApkEntry.deflated("assets/pack.bin", big)))

        ZipFile(target).use { zip ->
            assertContentEquals(big, zip.getInputStream(zip.getEntry("assets/pack.bin")).readBytes())
        }
        // No temp spool files left behind.
        val leftovers = tempDir.toFile().listFiles { f -> f.name.startsWith("libapk-deflate-") } ?: emptyArray()
        assertTrue(leftovers.isEmpty(), "temp spools not cleaned up: ${leftovers.map { it.name }}")
    }

    @Test
    fun `stores entries sourced from files`() {
        val payload = Random(7).nextBytes(9000)
        val source = file("payload.bin").apply { writeBytes(payload) }
        val target = file("from-file.apk")

        writer.write(target, listOf(ApkEntry.stored("lib/x86_64/libnative.so", source, ZipAlignments.NATIVE_LIB)))

        ZipFile(target).use { zip ->
            assertContentEquals(payload, zip.getInputStream(zip.getEntry("lib/x86_64/libnative.so")).readBytes())
        }
        assertEquals(0L, localHeaderDataOffset(target, "lib/x86_64/libnative.so") % ZipAlignments.NATIVE_LIB)
    }

    @Test
    fun `encodes the alignment multiple in the zipalign extra field`() {
        val target = file("extra-field.apk")
        writer.write(
            target,
            listOf(
                ApkEntry.deflated("a.bin", ByteArray(5)),
                ApkEntry.stored("resources.arsc", ByteArray(16), ZipAlignments.RESOURCES),
                ApkEntry.stored("lib/arm64-v8a/libnative.so", ByteArray(64), ZipAlignments.NATIVE_LIB),
            ),
        )

        // Format apksig and zipalign expect: id, payload size, uint16 alignment multiple, padding.
        assertExtraField(target, "resources.arsc", ZipAlignments.RESOURCES)
        assertExtraField(target, "lib/arm64-v8a/libnative.so", ZipAlignments.NATIVE_LIB)
    }

    @Test
    fun `rejects duplicate and unsafe entry names`() {
        val target = file("bad.apk")
        assertFailsWith<IllegalArgumentException> {
            writer.write(
                target,
                listOf(ApkEntry.deflated("a.txt", ByteArray(1)), ApkEntry.deflated("a.txt", ByteArray(2))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            writer.write(target, listOf(ApkEntry.deflated("/absolute.txt", ByteArray(1))))
        }
        assertFailsWith<IllegalArgumentException> {
            writer.write(target, listOf(ApkEntry.deflated("../escape.txt", ByteArray(1))))
        }
    }


    private fun file(name: String): File = tempDir.resolve(name).toFile()

    private fun assertExtraField(archive: File, entryName: String, expectedAlignment: Int) {
        val extra = localHeaderExtra(archive, entryName)
        assertTrue(extra.size >= 6, "extra field for $entryName is too short: ${extra.size}")
        val id = (extra[0].toInt() and 0xFF) or ((extra[1].toInt() and 0xFF) shl 8)
        assertEquals(0xD935, id, "unexpected extra field id for $entryName")
        val payloadSize = (extra[2].toInt() and 0xFF) or ((extra[3].toInt() and 0xFF) shl 8)
        assertEquals(extra.size - 4, payloadSize, "payload size mismatch for $entryName")
        val alignment = (extra[4].toInt() and 0xFF) or ((extra[5].toInt() and 0xFF) shl 8)
        assertEquals(expectedAlignment, alignment, "alignment multiple mismatch for $entryName")
        assertEquals(
            0L,
            localHeaderDataOffset(archive, entryName) % expectedAlignment,
            "data offset is not aligned for $entryName",
        )
    }

    /** Returns the local header extra field of [name]. */
    private fun localHeaderExtra(archive: File, name: String): ByteArray {
        RandomAccessFile(archive, "r").use { raf ->
            var position = 0L
            while (position < raf.length() - 4) {
                raf.seek(position)
                if (readIntLe(raf) != 0x04034b50) break

                raf.seek(position + 18)
                val compressedSize = readIntLe(raf)
                raf.seek(position + 26)
                val nameLength = readShortLe(raf)
                val extraLength = readShortLe(raf)
                raf.seek(position + 30)
                val entryName = String(ByteArray(nameLength).also { raf.readFully(it) }, Charsets.UTF_8)
                val extra = ByteArray(extraLength).also { raf.readFully(it) }

                if (entryName == name) return extra
                position += 30 + nameLength + extraLength + compressedSize
            }
        }
        error("entry not found: $name")
    }

    /** Walks the local file headers and returns the absolute data offset of [name]. */
    private fun localHeaderDataOffset(archive: File, name: String): Long {
        RandomAccessFile(archive, "r").use { raf ->
            var position = 0L
            while (position < raf.length() - 4) {
                raf.seek(position)
                if (readIntLe(raf) != 0x04034b50) break

                raf.seek(position + 18)
                val compressedSize = readIntLe(raf)
                raf.seek(position + 26)
                val nameLength = readShortLe(raf)
                val extraLength = readShortLe(raf)
                raf.seek(position + 30)
                val entryName = String(ByteArray(nameLength).also { raf.readFully(it) }, Charsets.UTF_8)

                val dataOffset = position + 30 + nameLength + extraLength
                if (entryName == name) return dataOffset
                position = dataOffset + compressedSize
            }
        }
        error("entry not found: $name")
    }

    private fun readShortLe(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8)
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return (b0 and 0xFF) or ((b1 and 0xFF) shl 8) or ((b2 and 0xFF) shl 16) or ((b3 and 0xFF) shl 24)
    }
}
