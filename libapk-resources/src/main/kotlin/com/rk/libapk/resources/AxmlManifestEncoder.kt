package com.rk.libapk.resources

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.ValueType
import com.rk.libapk.model.ActivitySpec
import com.rk.libapk.model.AttributeValueSpec
import com.rk.libapk.model.LabelValue
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.model.MetaDataSpec
import com.rk.libapk.model.Namespaces
import com.rk.libapk.model.ResourceIdLookup
import com.rk.libapk.model.ResourceRef
import com.rk.libapk.model.UsesFeatureSpec
import com.rk.libapk.model.UsesLibrarySpec
import com.rk.libapk.model.XmlAttribute
import com.rk.libapk.model.XmlElementSpec

/**
 * Turns a [ManifestSpec] into a binary `AndroidManifest.xml` (AXML).
 *
 * Written from scratch via ARSCLib's from-scratch manifest builder: no `aapt2`, no Android SDK,
 * no Gradle. The only inputs are the spec plus a [ResourceIdLookup] that resolves references such
 * as `@mipmap/ic_launcher` to ids assigned by the generated `resources.arsc`.
 */
class AxmlManifestEncoder {

    /**
     * @param spec the manifest to emit.
     * @param lookup resolves [ResourceRef]s. Required only when [spec] (or its extras) actually
     *   references a resource; pass [ResourceIdLookup.NONE] for literal-only manifests.
     */
    fun encode(spec: ManifestSpec, lookup: ResourceIdLookup = ResourceIdLookup.NONE): ByteArray {
        val manifest = AndroidManifestBlock()

        // 1. Root attributes.
        manifest.setPackageName(spec.packageName)
        manifest.setVersionCode(spec.versionCode)
        spec.versionName?.let { manifest.setVersionName(it) }
        manifest.setMinSdkVersion(spec.minSdk)
        manifest.setTargetSdkVersion(spec.targetSdk)
        spec.compileSdk?.let { manifest.setCompileSdkVersion(it) }
        spec.compileSdkCodename?.let { manifest.setCompileSdkVersionCodename(it) }
        root(manifest).let { root ->
            spec.manifestAttributes.forEach { applyAttribute(root, it, lookup) }
        }

        // 2. Manifest-level children, added before <application> so the order is conventional.
        spec.permissions.forEach { manifest.addUsesPermission(it) }
        spec.features.forEach { addUsesFeature(manifest, it, lookup) }
        spec.libraries.forEach { addUsesLibrary(manifest, it, lookup) }
        spec.extraManifestElements.forEach { addElement(root(manifest), it, lookup) }

        // 3. Application subtree (this is what creates <application>).
        val application = manifest.getOrCreateApplicationElement()
        applyApplication(manifest, application, spec, lookup)

        manifest.refreshFull()
        return manifest.getBytes()
    }


    private fun applyApplication(
        manifest: AndroidManifestBlock,
        application: ResXmlElement,
        spec: ManifestSpec,
        lookup: ResourceIdLookup,
    ) {
        spec.applicationName?.let { manifest.setApplicationClassName(it) }
        spec.label?.let { label ->
            when (label) {
                is LabelValue.Literal -> manifest.setApplicationLabel(label.text)
                is LabelValue.Reference -> manifest.setApplicationLabel(requireId(lookup, label.ref))
            }
        }
        spec.icon?.let { manifest.setIconResourceId(requireId(lookup, it)) }
        spec.roundIcon?.let { manifest.setRoundIconResourceId(requireId(lookup, it)) }
        manifest.setDebuggable(spec.debuggable)
        manifest.setExtractNativeLibs(spec.extractNativeLibs)

        spec.hardwareAccelerated?.let { applyAttribute(application, XmlAttribute("hardwareAccelerated", AttributeValueSpec.Bool(it)), lookup) }
        spec.usesCleartextTraffic?.let { applyAttribute(application, XmlAttribute("usesCleartextTraffic", AttributeValueSpec.Bool(it)), lookup) }
        spec.largeHeap?.let { applyAttribute(application, XmlAttribute("largeHeap", AttributeValueSpec.Bool(it)), lookup) }
        spec.appCategory?.let { applyAttribute(application, XmlAttribute("appCategory", AttributeValueSpec.Text(it)), lookup) }
        spec.theme?.let { applyAttribute(application, XmlAttribute("theme", AttributeValueSpec.Reference(it)), lookup) }
        spec.networkSecurityConfig?.let { applyAttribute(application, XmlAttribute("networkSecurityConfig", AttributeValueSpec.Reference(it)), lookup) }
        spec.applicationAttributes.forEach { applyAttribute(application, it, lookup) }
        spec.applicationMetaData.forEach { addMetaData(application, it, lookup) }
        spec.activities.forEach { addActivity(application, it, lookup) }
    }

    private fun addActivity(
        application: ResXmlElement,
        spec: ActivitySpec,
        lookup: ResourceIdLookup,
    ) {
        val activity = application.newElement(ACTIVITY)
        applyAttribute(activity, XmlAttribute("name", AttributeValueSpec.Text(spec.name)), lookup)

        // A launcher activity must be exported explicitly for targetSdk >= 31. `getOrCreateActivity`
        // in ARSCLib takes an "isActivityAlias" flag, not "isMain", so we build the element here.
        if (spec.launcher) {
            val intentFilter = activity.newElement(INTENT_FILTER)
            applyAttribute(
                intentFilter.newElement(ACTION),
                XmlAttribute("name", AttributeValueSpec.Text("android.intent.action.MAIN")),
                lookup,
            )
            applyAttribute(
                intentFilter.newElement(CATEGORY),
                XmlAttribute("name", AttributeValueSpec.Text("android.intent.category.LAUNCHER")),
                lookup,
            )
        }

        val exported = spec.exported ?: if (spec.launcher) true else null
        exported?.let { applyAttribute(activity, XmlAttribute("exported", AttributeValueSpec.Bool(it)), lookup) }
        spec.label?.let { label ->
            when (label) {
                is LabelValue.Literal -> applyAttribute(activity, XmlAttribute("label", AttributeValueSpec.Text(label.text)), lookup)
                is LabelValue.Reference -> applyAttribute(activity, XmlAttribute("label", AttributeValueSpec.Reference(label.ref)), lookup)
            }
        }
        spec.theme?.let { applyAttribute(activity, XmlAttribute("theme", AttributeValueSpec.Reference(it)), lookup) }
        spec.launchMode?.let { applyAttribute(activity, XmlAttribute("launchMode", AttributeValueSpec.Text(it)), lookup) }
        spec.screenOrientation?.let { applyAttribute(activity, XmlAttribute("screenOrientation", AttributeValueSpec.Text(it)), lookup) }
        spec.configChanges?.let { applyAttribute(activity, XmlAttribute("configChanges", AttributeValueSpec.Text(it)), lookup) }
        spec.resizeableActivity?.let { applyAttribute(activity, XmlAttribute("resizeableActivity", AttributeValueSpec.Bool(it)), lookup) }
        spec.process?.let { applyAttribute(activity, XmlAttribute("process", AttributeValueSpec.Text(it)), lookup) }
        spec.extraAttributes.forEach { applyAttribute(activity, it, lookup) }
        spec.metaData.forEach { addMetaData(activity, it, lookup) }
        spec.intentFilters.forEach { addElement(activity, it, lookup) }
    }

    private fun addMetaData(parent: ResXmlElement, spec: MetaDataSpec, lookup: ResourceIdLookup) {
        val element = parent.newElement(META_DATA)
        applyAttribute(element, XmlAttribute("name", AttributeValueSpec.Text(spec.name)), lookup)
        applyAttribute(element, XmlAttribute("value", spec.value), lookup)
    }


    private fun addUsesFeature(manifest: AndroidManifestBlock, spec: UsesFeatureSpec, lookup: ResourceIdLookup) {
        val element = root(manifest).newElement(USES_FEATURE)
        applyAttribute(element, XmlAttribute("name", AttributeValueSpec.Text(spec.name)), lookup)
        spec.required?.let { applyAttribute(element, XmlAttribute("required", AttributeValueSpec.Bool(it)), lookup) }
        spec.version?.let { applyAttribute(element, XmlAttribute("version", AttributeValueSpec.IntValue(it)), lookup) }
    }

    private fun addUsesLibrary(manifest: AndroidManifestBlock, spec: UsesLibrarySpec, lookup: ResourceIdLookup) {
        val element = root(manifest).newElement(USES_LIBRARY)
        applyAttribute(element, XmlAttribute("name", AttributeValueSpec.Text(spec.name)), lookup)
        spec.required?.let { applyAttribute(element, XmlAttribute("required", AttributeValueSpec.Bool(it)), lookup) }
    }

    private fun addElement(parent: ResXmlElement, spec: XmlElementSpec, lookup: ResourceIdLookup) {
        val element = parent.newElement(spec.tag)
        spec.attributes.forEach { applyAttribute(element, it, lookup) }
        spec.text?.let { element.getOrCreateLastText().setText(it) }
        spec.children.forEach { addElement(element, it, lookup) }
    }


    private fun applyAttribute(element: ResXmlElement, attribute: XmlAttribute, lookup: ResourceIdLookup) {
        val uri = attribute.namespace
        val explicitId = attribute.resourceId
        val target: ResXmlAttribute = when {
            explicitId != null ->
                element.createAttribute(attribute.name, explicitId).also { created ->
                    uri?.let { created.setNamespace(it, prefixFor(it)) }
                }

            // Android framework attributes are identified by their numeric resource id. ARSCLib
            // has no framework table, so we look the id up in the table bundled with this module
            // and let ARSCLib create a properly namespaced attribute from it.
            uri == Namespaces.ANDROID ->
                element.createAndroidAttribute(attribute.name, AndroidAttributeIds.idOf(attribute.name))

            uri == null -> element.createAttribute(attribute.name, 0)

            else -> element.createAttribute(attribute.name, 0).also { created ->
                created.setNamespace(uri, prefixFor(uri))
            }
        }
        applyValue(target, attribute.value, lookup)
    }

    private fun applyValue(target: ResXmlAttribute, value: AttributeValueSpec, lookup: ResourceIdLookup) {
        when (value) {
            is AttributeValueSpec.Text -> target.setValueAsString(value.value)
            is AttributeValueSpec.Bool -> target.setValueAsBoolean(value.value)
            is AttributeValueSpec.IntValue -> {
                target.setValueType(if (value.hex) ValueType.HEX else ValueType.DEC)
                target.setData(value.value)
            }
            is AttributeValueSpec.Reference -> {
                target.setValueType(ValueType.REFERENCE)
                target.setData(requireId(lookup, value.ref))
            }
            is AttributeValueSpec.Color -> {
                target.setValueType(ValueType.COLOR_ARGB8)
                target.setData(value.argb)
            }
            is AttributeValueSpec.FloatValue -> {
                target.setValueType(ValueType.FLOAT)
                target.setData(java.lang.Float.floatToIntBits(value.value))
            }
            is AttributeValueSpec.Dimension -> {
                target.setValueType(ValueType.DIMENSION)
                target.setData(encodeDimension(value))
            }
            is AttributeValueSpec.Raw -> {
                target.setValueType(ValueType.valueOf(value.valueType))
                target.setData(value.data)
            }
        }
    }

    /** AOSP complex dimension encoding with `COMPLEX_RADIX_23p0`. */
    private fun encodeDimension(value: AttributeValueSpec.Dimension): Int {
        val unit = when (value.unit) {
            com.rk.libapk.model.DimensionUnit.PX -> 0
            com.rk.libapk.model.DimensionUnit.DIP -> 1
            com.rk.libapk.model.DimensionUnit.SP -> 2
            com.rk.libapk.model.DimensionUnit.PT -> 3
            com.rk.libapk.model.DimensionUnit.IN -> 4
            com.rk.libapk.model.DimensionUnit.MM -> 5
        }
        val mantissa = value.value.toInt()
        return (mantissa shl 8) or unit
    }

    private fun requireId(lookup: ResourceIdLookup, ref: ResourceRef): Int =
        lookup.idOf(ref) ?: error(
            "unresolved resource reference @${ref.type}/${ref.name}: " +
                "add it to the resource table passed to the builder, or use a literal value",
        )

    private fun root(manifest: AndroidManifestBlock): ResXmlElement =
        manifest.getManifestElement() ?: error("manifest document has no <manifest> element")

    private fun prefixFor(uri: String): String = when (uri) {
        Namespaces.ANDROID -> Namespaces.ANDROID_PREFIX
        Namespaces.TOOLS -> Namespaces.TOOLS_PREFIX
        Namespaces.APP -> Namespaces.APP_PREFIX
        else -> Namespaces.ANDROID_PREFIX
    }

    private companion object {
        const val USES_FEATURE = "uses-feature"
        const val USES_LIBRARY = "uses-library"
        const val META_DATA = "meta-data"
        const val ACTIVITY = "activity"
        const val INTENT_FILTER = "intent-filter"
        const val ACTION = "action"
        const val CATEGORY = "category"
    }
}
