package com.rk.libapk.resources

import com.rk.libapk.model.ResourceSpec

/**
 * [IResourceEncoder] that writes a real `resources.arsc` with ARSCLib and then encodes the binary
 * manifest against the ids it assigned.
 *
 * Nothing external is invoked: no `aapt2`, no Android SDK, no Gradle. When [ResourceSpec.isEmpty]
 * the table is skipped entirely and the manifest is encoded with no resource references.
 */
class ArscResourceEncoder : IResourceEncoder {

    override suspend fun encode(request: ResourceRequest): ResourceResult {
        val manifestEncoder = AxmlManifestEncoder()

        if (request.resources.isEmpty) {
            val manifestBytes = manifestEncoder.encode(request.manifest)
            return ResourceResult(
                manifestBytes = manifestBytes,
                resourcesArsc = null,
                entries = emptyList(),
                ids = com.rk.libapk.model.ResourceIdLookup.NONE,
            )
        }

        // The table must exist before the manifest so references can be resolved to real ids.
        val table = ResourceTableBuilder().build(request.resources)
        val manifestBytes = manifestEncoder.encode(request.manifest, table.ids)

        return ResourceResult(
            manifestBytes = manifestBytes,
            resourcesArsc = table.arscBytes,
            entries = table.entries,
            ids = table.ids,
        )
    }
}
