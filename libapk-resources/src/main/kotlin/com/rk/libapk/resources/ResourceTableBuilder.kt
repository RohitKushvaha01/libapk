package com.rk.libapk.resources

import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import com.rk.libapk.apk.ApkEntry
import com.rk.libapk.model.FileResource
import com.rk.libapk.model.ResourceIdLookup
import com.rk.libapk.model.ResourceRef
import com.rk.libapk.model.ResourceSpec

/**
 * Result of [ResourceTableBuilder.build].
 *
 * @param arscBytes serialized `resources.arsc`. Store it uncompressed and 4-byte aligned in the APK.
 * @param entries the `res/...` files referenced by the table, ready to be zipped into the APK.
 * @param ids resolves `ResourceRef(type, name)` to the id assigned by the table, for use by the
 *   manifest encoder.
 */
data class ResourceTableResult(
    val arscBytes: ByteArray,
    val entries: List<ApkEntry>,
    val ids: ResourceIdLookup,
)

/**
 * Builds a `resources.arsc` from scratch with ARSCLib, with no `aapt2` and no Android SDK.
 *
 * The table gets a single package, one entry per declared resource, and one config variant per
 * qualifier. File-backed resources are recorded as their APK path (`res/mipmap-xxhdpi/ic.png`),
 * which is exactly what aapt2 does.
 */
class ResourceTableBuilder {

    fun build(spec: ResourceSpec): ResourceTableResult {
        val table = TableBlock()
        val pkg = table.newPackage(spec.packageId, spec.packageName)
        val ids = HashMap<ResourceRef, Int>()
        val entries = ArrayList<ApkEntry>()

        fun record(type: String, name: String, entry: Entry) {
            ids.putIfAbsent(ResourceRef(type, name), entry.resourceId)
        }

        spec.strings.forEach { resource ->
            val entry = pkg.getOrCreate(DEFAULT_QUALIFIER, TYPE_STRING, resource.name)
            entry.setValueAsString(resource.value)
            record(TYPE_STRING, resource.name, entry)
        }

        spec.bools.forEach { resource ->
            val entry = pkg.getOrCreate(DEFAULT_QUALIFIER, TYPE_BOOL, resource.name)
            entry.setValueAsBoolean(resource.value)
            record(TYPE_BOOL, resource.name, entry)
        }

        spec.integers.forEach { resource ->
            val entry = pkg.getOrCreate(DEFAULT_QUALIFIER, TYPE_INTEGER, resource.name)
            entry.setValueAsRaw(ValueType.DEC, resource.value)
            record(TYPE_INTEGER, resource.name, entry)
        }

        spec.colors.forEach { resource ->
            val entry = pkg.getOrCreate(DEFAULT_QUALIFIER, TYPE_COLOR, resource.name)
            entry.setValueAsRaw(ValueType.COLOR_ARGB8, resource.argb)
            record(TYPE_COLOR, resource.name, entry)
        }

        spec.files.forEach { resource ->
            addFileResource(pkg, resource, ::record, entries)
            entries += ApkEntry.deflated(resource.apkPath, resource.file)
        }

        // Serializing requires a full refresh: it recomputes chunk sizes, string pools and ids.
        table.refreshFull()

        val lookup = ResourceIdLookup { ref -> ids[ref] }
        return ResourceTableResult(table.getBytes(), entries, lookup)
    }

    private fun addFileResource(
        pkg: com.reandroid.arsc.chunk.PackageBlock,
        resource: FileResource,
        record: (String, String, Entry) -> Unit,
        entries: MutableList<ApkEntry>,
    ) {
        require(resource.file.isFile) { "resource file does not exist: ${resource.file}" }
        val qualifier = resource.qualifier.orEmpty()
        val entry = pkg.getOrCreate(qualifier, resource.type, resource.name)
        // File-backed entries store the APK path of the payload as a string value.
        entry.setValueAsString(resource.apkPath)
        record(resource.type, resource.name, entry)
    }

    private companion object {
        const val DEFAULT_QUALIFIER = ""
        const val TYPE_STRING = "string"
        const val TYPE_BOOL = "bool"
        const val TYPE_INTEGER = "integer"
        const val TYPE_COLOR = "color"
    }
}
