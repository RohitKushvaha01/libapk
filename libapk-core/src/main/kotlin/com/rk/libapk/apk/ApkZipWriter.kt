package com.rk.libapk.apk

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater

/** A DOS timestamp as stored in zip headers. Immutable, so builds are reproducible. */
data class ZipTimestamp(val dosTime: Int, val dosDate: Int) {

    companion object {
        /** 1980-01-01 00:00:00, the earliest representable DOS timestamp. */
        val DOS_EPOCH = ZipTimestamp(dosTime = 0, dosDate = (1 shl 5) or 1)

        /** Converts wall-clock UTC time into a DOS timestamp (2 second resolution). */
        fun fromEpochMillis(epochMillis: Long): ZipTimestamp {
            val cal = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochMillis }
            val year = cal.get(Calendar.YEAR).coerceAtLeast(1980)
            val dosTime = (cal.get(Calendar.HOUR_OF_DAY) shl 11) or
                (cal.get(Calendar.MINUTE) shl 5) or
                (cal.get(Calendar.SECOND) / 2)
            val dosDate = ((year - 1980) shl 9) or ((cal.get(Calendar.MONTH) + 1) shl 5) or cal.get(Calendar.DAY_OF_MONTH)
            return ZipTimestamp(dosTime, dosDate)
        }
    }
}

/** Summary of a written archive. */
data class ApkWriteResult(val file: File, val size: Long, val entryCount: Int)

/**
 * Minimal, deterministic zip writer producing APK-compatible archives without any SDK tooling.
 *
 * Differences from `java.util.zip.ZipOutputStream` that matter for APKs:
 *
 *  * per-entry control over STORED vs DEFLATED (Android requires `resources.arsc` stored),
 *  * alignment of stored entries by inserting a `0xd935` padding extra field, the same
 *    mechanism `zipalign`/apksig use,
 *  * fixed timestamps and a stable entry order, so repeated builds are byte-identical,
 *  * no zip64 (APKs above 4 GiB / 65535 entries are out of scope),
 *  * constant memory: large inputs are deflated to a temp file, never fully buffered.
 */
class ApkZipWriter(
    private val timestamp: ZipTimestamp = ZipTimestamp.DOS_EPOCH,
    private val deflateLevel: Int = Deflater.DEFAULT_COMPRESSION,
    /** Inputs at or below this size are compressed in memory, larger ones spill to a temp file. */
    private val memorySpoolLimitBytes: Long = 1L shl 20,
) {

    fun write(target: File, entries: List<ApkEntry>): ApkWriteResult {
        val seen = HashSet<String>(entries.size)
        val central = ArrayList<CentralRecord>(entries.size)
        target.parentFile?.mkdirs()

        CountingOutputStream(BufferedOutputStream(FileOutputStream(target), BUFFER_SIZE)).use { out ->
            for (entry in entries) {
                validateName(entry.name)
                require(seen.add(entry.name)) { "duplicate zip entry: ${entry.name}" }

                val prepared = prepare(entry)
                try {
                    val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
                    val offset = out.count
                    val alignment = if (prepared.method == METHOD_STORED) {
                        normalizeAlignment(entry.alignment)
                    } else {
                        ZipAlignments.NONE
                    }
                    val extra = alignmentExtra(offset, LOCAL_HEADER_SIZE + nameBytes.size, alignment)

                    writeLocalHeader(out, prepared, nameBytes, extra)
                    prepared.copyTo(out)
                    central += CentralRecord(offset, prepared, nameBytes)
                } finally {
                    prepared.dispose()
                }
            }

            val centralOffset = out.count
            for (record in central) writeCentralHeader(out, record)
            val centralSize = out.count - centralOffset

            writeEndOfCentralDirectory(out, central.size, centralSize, centralOffset)
        }

        return ApkWriteResult(target, target.length(), entries.size)
    }


    private fun prepare(entry: ApkEntry): PreparedEntry = when (entry.compression) {
        Compression.STORED -> {
            val crc = crc32Of(entry.source)
            PreparedEntry(
                method = METHOD_STORED,
                crc = crc,
                uncompressedSize = entry.source.size,
                compressedSize = entry.source.size,
                spool = null,
                source = entry.source,
            )
        }

        Compression.DEFLATED -> {
            val spool = Spool(useMemory = entry.source.size <= memorySpoolLimitBytes)
            val crc = CRC32()
            var uncompressed = 0L
            val inputBuffer = ByteArray(BUFFER_SIZE)
            val outputBuffer = ByteArray(BUFFER_SIZE)
            val deflater = Deflater(deflateLevel, true)

            val sink = spool.openOutputStream()
            try {
                entry.source.openStream().use { input ->
                    while (true) {
                        val read = input.read(inputBuffer)
                        if (read < 0) break
                        uncompressed += read
                        crc.update(inputBuffer, 0, read)
                        deflater.setInput(inputBuffer, 0, read)
                        while (!deflater.needsInput()) {
                            val written = deflater.deflate(outputBuffer)
                            if (written > 0) sink.write(outputBuffer, 0, written)
                        }
                    }
                }
                deflater.finish()
                while (!deflater.finished()) {
                    val written = deflater.deflate(outputBuffer)
                    if (written > 0) sink.write(outputBuffer, 0, written)
                }
            } finally {
                deflater.end()
            }
            spool.close()

            PreparedEntry(
                method = METHOD_DEFLATED,
                crc = crc.value,
                uncompressedSize = uncompressed,
                compressedSize = spool.length,
                spool = spool,
                source = null,
            )
        }
    }

    private fun crc32Of(source: EntrySource): Long {
        val crc = CRC32()
        val buffer = ByteArray(BUFFER_SIZE)
        source.openStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }


    private fun writeLocalHeader(out: CountingOutputStream, entry: PreparedEntry, name: ByteArray, extra: ByteArray) {
        out.writeInt(HEADER_LOCAL)
        out.writeShort(VERSION_NEEDED)
        out.writeShort(FLAG_UTF8)
        out.writeShort(entry.method)
        out.writeShort(timestamp.dosTime)
        out.writeShort(timestamp.dosDate)
        out.writeInt(entry.crc.toInt())
        out.writeInt(entry.compressedSize.toInt())
        out.writeInt(entry.uncompressedSize.toInt())
        out.writeShort(name.size)
        out.writeShort(extra.size)
        out.write(name)
        out.write(extra)
    }

    private fun writeCentralHeader(out: CountingOutputStream, record: CentralRecord) {
        val entry = record.entry
        out.writeInt(HEADER_CENTRAL)
        out.writeShort(VERSION_MADE_BY)
        out.writeShort(VERSION_NEEDED)
        out.writeShort(FLAG_UTF8)
        out.writeShort(entry.method)
        out.writeShort(timestamp.dosTime)
        out.writeShort(timestamp.dosDate)
        out.writeInt(entry.crc.toInt())
        out.writeInt(entry.compressedSize.toInt())
        out.writeInt(entry.uncompressedSize.toInt())
        out.writeShort(record.name.size)
        out.writeShort(0) // central extra field: alignment padding lives in the local header only
        out.writeShort(0) // comment
        out.writeShort(0) // disk number start
        out.writeShort(0) // internal attributes
        out.writeInt(EXTERNAL_ATTRIBUTES)
        out.writeInt(record.localHeaderOffset.toInt())
        out.write(record.name)
    }

    private fun writeEndOfCentralDirectory(out: CountingOutputStream, count: Int, size: Long, offset: Long) {
        out.writeInt(HEADER_EOCD)
        out.writeShort(0) // this disk
        out.writeShort(0) // disk with central directory
        out.writeShort(count)
        out.writeShort(count)
        out.writeInt(size.toInt())
        out.writeInt(offset.toInt())
        out.writeShort(0) // comment length
    }

    /**
     * Builds the `0xd935` alignment extra field so the payload starts at a multiple of [alignment].
     *
     * The on-disk format (what `zipalign` writes and apksig reads) is:
     *
     * ```
     * uint16 headerId        = 0xd935
     * uint16 payloadSize
     *   uint16 alignmentMultiple
     *   (payloadSize - 2) padding bytes
     * ```
     *
     * The field must be at least 6 bytes (4 header + 2 alignment multiple), so a smaller gap is
     * widened by whole alignment steps. Encoding the multiple matters: apksig reads it while
     * re-aligning during signing, and misreads the field as "alignment 0" if it is absent.
     */
    private fun alignmentExtra(offset: Long, headerLength: Int, alignment: Int): ByteArray {
        if (alignment <= 1) return EMPTY
        require(alignment <= MAX_ALIGNMENT) { "alignment must be <= $MAX_ALIGNMENT, was $alignment" }

        val base = offset + headerLength
        val misalignment = base % alignment
        var extraLength = if (misalignment == 0L) 0L else alignment - misalignment
        while (extraLength < MIN_ALIGNMENT_EXTRA_FIELD) {
            extraLength += alignment
        }

        val extra = ByteArray(extraLength.toInt())
        extra[0] = (ZIPALIGN_EXTRA_ID and 0xFF).toByte()
        extra[1] = ((ZIPALIGN_EXTRA_ID ushr 8) and 0xFF).toByte()
        val payloadSize = extra.size - 4
        extra[2] = (payloadSize and 0xFF).toByte()
        extra[3] = ((payloadSize ushr 8) and 0xFF).toByte()
        extra[4] = (alignment and 0xFF).toByte()
        extra[5] = ((alignment ushr 8) and 0xFF).toByte()
        // Remaining bytes stay zero: they are the padding.
        return extra
    }

    private fun normalizeAlignment(alignment: Int): Int = when {
        alignment <= 1 -> ZipAlignments.NONE
        else -> alignment
    }

    private fun validateName(name: String) {
        require(name.isNotEmpty()) { "zip entry name must not be empty" }
        require(!name.startsWith("/")) { "zip entry name must be relative: $name" }
        require(!name.contains('\\')) { "zip entry name must use forward slashes: $name" }
        require(!name.contains("..")) { "zip entry name must not contain '..': $name" }
    }


    private class CentralRecord(
        val localHeaderOffset: Long,
        val entry: PreparedEntry,
        val name: ByteArray,
    )

    private class PreparedEntry(
        val method: Int,
        val crc: Long,
        val uncompressedSize: Long,
        val compressedSize: Long,
        private val spool: Spool?,
        private val source: EntrySource?,
    ) {
        fun copyTo(out: OutputStream) {
            if (spool != null) {
                spool.writeTo(out)
            } else {
                source!!.openStream().use { it.copyTo(out, BUFFER_SIZE) }
            }
        }

        fun dispose() {
            spool?.dispose()
        }
    }

    /** Holds deflated bytes either in memory or in a temp file. */
    private class Spool(useMemory: Boolean) {
        private val memory: ByteArrayOutputStream? = if (useMemory) ByteArrayOutputStream() else null
        private val file: File? = if (useMemory) null else File.createTempFile("libapk-deflate-", ".tmp")
        private var sink: OutputStream? = null

        var length: Long = 0L
            private set

        fun openOutputStream(): OutputStream {
            val stream: OutputStream = memory ?: BufferedOutputStream(FileOutputStream(file!!), BUFFER_SIZE)
            sink = stream
            return stream
        }

        /** Flushes and finalises the spool. Idempotent: the caller must not also close the stream. */
        fun close() {
            val stream = sink ?: return
            stream.flush()
            if (memory == null) stream.close()
            sink = null
            length = memory?.size()?.toLong() ?: file!!.length()
        }

        fun writeTo(out: OutputStream) {
            if (memory != null) {
                out.write(memory.toByteArray())
            } else {
                FileInputStream(file!!).use { it.copyTo(out, BUFFER_SIZE) }
            }
        }

        fun dispose() {
            runCatching { sink?.close() }
            sink = null
            file?.delete()
        }
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            count += b.size
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        fun writeShort(value: Int) {
            write(value and 0xFF)
            write((value ushr 8) and 0xFF)
        }

        fun writeInt(value: Int) {
            write(value and 0xFF)
            write((value ushr 8) and 0xFF)
            write((value ushr 16) and 0xFF)
            write((value ushr 24) and 0xFF)
        }
    }

    private companion object {
        const val HEADER_LOCAL = 0x04034b50
        const val HEADER_CENTRAL = 0x02014b50
        const val HEADER_EOCD = 0x06054b50

        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        const val VERSION_NEEDED = 20
        const val VERSION_MADE_BY = 0x031E // unix, zip spec 3.0
        const val FLAG_UTF8 = 0x0800
        const val EXTERNAL_ATTRIBUTES = 0x81A4 shl 16 // 0100644 regular file

        const val LOCAL_HEADER_SIZE = 30
        const val MIN_ALIGNMENT_EXTRA_FIELD = 6 // 4 byte header + uint16 alignment multiple
        const val MAX_ALIGNMENT = 0xFFFF
        const val ZIPALIGN_EXTRA_ID = 0xD935

        const val BUFFER_SIZE = 1 shl 16

        val EMPTY = ByteArray(0)
    }
}
