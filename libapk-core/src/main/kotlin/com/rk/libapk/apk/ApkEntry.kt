package com.rk.libapk.apk

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** How an entry's payload is stored inside the APK. */
enum class Compression {
    /** No compression. Required for `resources.arsc` and recommended for `lib/**/*.so`. */
    STORED,

    /** Deflate. Fine for `classes*.dex`, assets and most resources. */
    DEFLATED,
}

/** Where an entry's bytes come from. */
sealed interface EntrySource {

    /** Uncompressed size in bytes. */
    val size: Long

    fun openStream(): InputStream

    class Bytes(private val bytes: ByteArray) : EntrySource {
        override val size: Long get() = bytes.size.toLong()
        override fun openStream(): InputStream = bytes.inputStream()
    }

    class Path(val file: File) : EntrySource {
        override val size: Long get() = file.length()
        override fun openStream(): InputStream = FileInputStream(file)
    }
}

/**
 * One file inside the APK.
 *
 * @param name forward-slash separated path, e.g. `classes.dex` or `lib/arm64-v8a/libnative.so`.
 * @param alignment required data offset alignment for [Compression.STORED] entries. Ignored
 *   for deflated entries (their payload is not mmapped). See [ZipAlignments].
 */
data class ApkEntry(
    val name: String,
    val source: EntrySource,
    val compression: Compression = Compression.DEFLATED,
    val alignment: Int = ZipAlignments.NONE,
) {
    companion object {
        fun stored(name: String, bytes: ByteArray, alignment: Int = ZipAlignments.NONE) =
            ApkEntry(name, EntrySource.Bytes(bytes), Compression.STORED, alignment)

        fun stored(name: String, file: File, alignment: Int = ZipAlignments.NONE) =
            ApkEntry(name, EntrySource.Path(file), Compression.STORED, alignment)

        fun deflated(name: String, bytes: ByteArray) =
            ApkEntry(name, EntrySource.Bytes(bytes), Compression.DEFLATED)

        fun deflated(name: String, file: File) =
            ApkEntry(name, EntrySource.Path(file), Compression.DEFLATED)
    }
}

/**
 * Alignment values that matter for Android.
 *
 * Android mmaps `resources.arsc` and uncompressed native libraries straight out of the APK, so
 * those entries must be stored uncompressed and aligned. `apksig` re-aligns during signing, but
 * libapk aligns while writing so that the *unsigned* archive is already correct.
 */
object ZipAlignments {
    const val NONE = 1

    /** `resources.arsc` and other mmapped, page-cache friendly entries. */
    const val RESOURCES = 4

    /** Uncompressed `lib/**/*.so`, matching `zipalign -p` and apksig's default page size. */
    const val NATIVE_LIB = 16384
}
