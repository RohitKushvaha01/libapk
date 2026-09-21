package com.rk.libapk.resources

/**
 * Android framework attribute ids for the `android:` namespace.
 *
 * A binary manifest stores each attribute as a numeric resource id, not a name. ARSCLib does not
 * bundle a framework table (and we have no `android.jar` at build time), so libapk ships the
 * name -> id mapping extracted from a platform `android.jar`:
 * `src/main/resources/com/rk/libapk/resources/android-attributes.txt`
 * (regenerate with `tools/generate-android-attribute-ids/generate.sh`).
 *
 * The table is loaded lazily from the classpath and only needed when a manifest contains
 * attributes in the Android namespace.
 */
object AndroidAttributeIds {

    private const val RESOURCE_PATH = "/com/rk/libapk/resources/android-attributes.txt"

    private val byName: Map<String, Int> by lazy { load() }

    /** `null` when the attribute is not part of the platform's public attribute set. */
    fun idOrNull(name: String): Int? = byName[name]

    /** @throws IllegalStateException with the exact attribute name when it cannot be resolved. */
    fun idOf(name: String): Int = idOrNull(name) ?: error(
        "unknown android: attribute '$name'. " +
            "Pass XmlAttribute(..., resourceId = <id>) to set it explicitly.",
    )

    /** All known framework attribute names. Useful for diagnostics. */
    fun names(): Set<String> = byName.keys

    private fun load(): Map<String, Int> {
        val stream = AndroidAttributeIds::class.java.getResourceAsStream(RESOURCE_PATH)
            ?: error("bundled resource $RESOURCE_PATH is missing from the jar")
        val result = HashMap<String, Int>(2048)
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val separator = trimmed.indexOf('=')
                if (separator <= 0) continue
                val name = trimmed.substring(0, separator)
                val value = trimmed.substring(separator + 1).removePrefix("0x").toIntOrNull(16) ?: continue
                result[name] = value
            }
        }
        check(result.isNotEmpty()) { "bundled attribute table $RESOURCE_PATH is empty" }
        return result
    }
}
