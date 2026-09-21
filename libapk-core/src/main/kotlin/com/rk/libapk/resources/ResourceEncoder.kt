package com.rk.libapk.resources

import com.rk.libapk.apk.ApkEntry
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.model.ResourceIdLookup
import com.rk.libapk.model.ResourceSpec

/**
 * Pluggable "resources + manifest" stage.
 *
 * This is one stage rather than two because the binary manifest must reference ids that only exist
 * after the resource table is built. The default implementation
 * (`com.rk.libapk.resources.ArscResourceEncoder`, module `libapk-resources`) uses ARSCLib to write
 * both `resources.arsc` and the binary `AndroidManifest.xml` without `aapt2`.
 */
interface IResourceEncoder {
    suspend fun encode(request: ResourceRequest): ResourceResult
}

/** Input for [IResourceEncoder]. */
data class ResourceRequest(
    val manifest: ManifestSpec,
    val resources: ResourceSpec,
)

/**
 * Output of [IResourceEncoder].
 *
 * @param manifestBytes binary `AndroidManifest.xml` (AXML), ready to be stored in the APK.
 * @param resourcesArsc serialized `resources.arsc`, or `null` when [ResourceSpec] was empty.
 * @param entries `res/...` payload files referenced by the table, ready to be zipped.
 * @param ids map used to resolve references while encoding the manifest.
 */
data class ResourceResult(
    val manifestBytes: ByteArray,
    val resourcesArsc: ByteArray?,
    val entries: List<ApkEntry>,
    val ids: ResourceIdLookup,
)
