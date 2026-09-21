package com.rk.libapk.model

import java.io.File

/** A `<string>` resource. */
data class StringResource(val name: String, val value: String)

/** A `<bool>` resource. */
data class BoolResource(val name: String, val value: Boolean)

/** An `<integer>` resource. */
data class IntegerResource(val name: String, val value: Int)

/** A `<color>` resource. */
data class ColorResource(val name: String, val argb: Int)

/**
 * A file-backed resource: `mipmap`, `drawable`, `raw`, `font`, `xml`, ...
 *
 * @param type resource type directory, e.g. `mipmap`.
 * @param name entry name, e.g. `ic_launcher`.
 * @param qualifier resource config qualifier such as `mdpi`, `xxhdpi`, `land`, or `null` for the
 *   default config. Multiple entries with the same [type] and [name] but different qualifiers
 *   share one resource id, which is how density-specific icons work.
 * @param fileName overrides the file name inside `res/<type>[-<qualifier>]/`; defaults to
 *   `<name>.<extension of file>`.
 */
data class FileResource(
    val type: String,
    val name: String,
    val file: File,
    val qualifier: String? = null,
    val fileName: String? = null,
) {
    /** Path of this file inside the APK, e.g. `res/mipmap-xxhdpi/ic_launcher.png`. */
    val apkPath: String
        get() {
            val directory = if (qualifier.isNullOrEmpty()) type else "$type-$qualifier"
            return "res/$directory/${resolvedFileName()}"
        }

    private fun resolvedFileName(): String {
        fileName?.let { return it }
        val extension = file.extension.ifEmpty { DEFAULT_EXTENSION }
        return "$name.$extension"
    }

    private companion object {
        const val DEFAULT_EXTENSION = "png"
    }
}

/**
 * Everything libapk needs to generate a `resources.arsc` plus the `res/` entries that go with it.
 *
 * Deliberately small: an app label, an icon and a handful of values cover most embedded use cases.
 * Arbitrary values can be added through the typed lists.
 */
data class ResourceSpec(
    val packageName: String,
    /** Package id of the resource table. `0x7f` is the conventional app package id. */
    val packageId: Int = DEFAULT_PACKAGE_ID,
    val strings: List<StringResource> = emptyList(),
    val bools: List<BoolResource> = emptyList(),
    val integers: List<IntegerResource> = emptyList(),
    val colors: List<ColorResource> = emptyList(),
    val files: List<FileResource> = emptyList(),
) {
    val isEmpty: Boolean
        get() = strings.isEmpty() && bools.isEmpty() && integers.isEmpty() && colors.isEmpty() && files.isEmpty()

    companion object {
        const val DEFAULT_PACKAGE_ID = 0x7f
    }
}
