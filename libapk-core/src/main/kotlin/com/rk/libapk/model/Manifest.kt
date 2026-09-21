package com.rk.libapk.model

/** Reference to an app resource by type and name, e.g. `ResourceRef("mipmap", "ic_launcher")`. */
data class ResourceRef(val type: String, val name: String)

/** Resolves [ResourceRef]s to the numeric ids assigned by the generated resource table. */
fun interface ResourceIdLookup {
    fun idOf(ref: ResourceRef): Int?

    companion object {
        val NONE = ResourceIdLookup { null }
    }
}

/** How a text-ish attribute such as `android:label` is expressed. */
sealed interface LabelValue {
    data class Literal(val text: String) : LabelValue
    data class Reference(val ref: ResourceRef) : LabelValue

    companion object {
        fun of(text: String): LabelValue = Literal(text)
        fun of(ref: ResourceRef): LabelValue = Reference(ref)
    }
}

/** Units accepted by [AttributeValueSpec.Dimension]. */
enum class DimensionUnit(val suffix: String) {
    PX("px"), DIP("dip"), SP("sp"), PT("pt"), IN("in"), MM("mm")
}

/** A typed attribute value, mapped onto the binary `Res_value` types. */
sealed interface AttributeValueSpec {
    data class Text(val value: String) : AttributeValueSpec
    data class IntValue(val value: Int, val hex: Boolean = false) : AttributeValueSpec
    data class Bool(val value: Boolean) : AttributeValueSpec
    data class Reference(val ref: ResourceRef) : AttributeValueSpec
    data class Color(val argb: Int) : AttributeValueSpec
    data class FloatValue(val value: Float) : AttributeValueSpec
    data class Dimension(val value: Float, val unit: DimensionUnit) : AttributeValueSpec

    /** Escape hatch: raw `Res_value` type + data. */
    data class Raw(val valueType: Int, val data: Int) : AttributeValueSpec
}

/** Well-known XML namespaces. */
object Namespaces {
    const val ANDROID = "http://schemas.android.com/apk/res/android"
    const val ANDROID_PREFIX = "android"
    const val TOOLS = "http://schemas.android.com/tools"
    const val TOOLS_PREFIX = "tools"
    const val APP = "http://schemas.android.com/apk/res-auto"
    const val APP_PREFIX = "app"
}

/** One XML attribute. [namespace] defaults to the Android namespace. */
data class XmlAttribute(
    val name: String,
    val value: AttributeValueSpec,
    val namespace: String? = Namespaces.ANDROID,
    /**
     * Explicit framework attribute id for `android:` attributes.
     *
     * Usually `null`: the encoder resolves the id from the platform attribute table bundled with
     * `libapk-resources`. Set it to use an attribute newer than that table.
     */
    val resourceId: Int? = null,
)

/** An arbitrary XML element, used as the escape hatch for anything not modelled explicitly. */
data class XmlElementSpec(
    val tag: String,
    val attributes: List<XmlAttribute> = emptyList(),
    val children: List<XmlElementSpec> = emptyList(),
    val text: String? = null,
)

/** A `<meta-data>` entry. */
data class MetaDataSpec(
    val name: String,
    val value: AttributeValueSpec,
)

/** An `<activity>`. */
data class ActivitySpec(
    val name: String,
    val launcher: Boolean = false,
    val exported: Boolean? = null,
    val label: LabelValue? = null,
    val theme: ResourceRef? = null,
    val launchMode: String? = null,
    val screenOrientation: String? = null,
    val configChanges: String? = null,
    val resizeableActivity: Boolean? = null,
    val process: String? = null,
    val extraAttributes: List<XmlAttribute> = emptyList(),
    val metaData: List<MetaDataSpec> = emptyList(),
    val intentFilters: List<XmlElementSpec> = emptyList(),
)

/** A `<uses-feature>` declaration. */
data class UsesFeatureSpec(
    val name: String,
    val required: Boolean? = null,
    val version: Int? = null,
)

/** A `<uses-library>` declaration. */
data class UsesLibrarySpec(
    val name: String,
    val required: Boolean? = null,
)

/**
 * Everything libapk needs to emit a binary `AndroidManifest.xml`.
 *
 * Defaults suit a self-contained app: minSdk 26, native libraries left uncompressed in the APK,
 * no implicit permissions.
 */
data class ManifestSpec(
    val packageName: String,
    val versionCode: Int = 1,
    val versionName: String? = null,
    val minSdk: Int = DEFAULT_MIN_SDK,
    val targetSdk: Int = DEFAULT_MIN_SDK,
    val compileSdk: Int? = null,
    val compileSdkCodename: String? = null,
    val label: LabelValue? = null,
    val icon: ResourceRef? = null,
    val roundIcon: ResourceRef? = null,
    val applicationName: String? = null,
    val debuggable: Boolean = false,
    val extractNativeLibs: Boolean = false,
    val hardwareAccelerated: Boolean? = null,
    val usesCleartextTraffic: Boolean? = null,
    val largeHeap: Boolean? = null,
    val appCategory: String? = null,
    val theme: ResourceRef? = null,
    val networkSecurityConfig: ResourceRef? = null,
    val permissions: List<String> = emptyList(),
    val features: List<UsesFeatureSpec> = emptyList(),
    val libraries: List<UsesLibrarySpec> = emptyList(),
    val activities: List<ActivitySpec> = emptyList(),
    val applicationMetaData: List<MetaDataSpec> = emptyList(),
    /** Extra attributes on `<application>`. */
    val applicationAttributes: List<XmlAttribute> = emptyList(),
    /** Extra attributes on `<manifest>`. */
    val manifestAttributes: List<XmlAttribute> = emptyList(),
    /** Extra top-level elements, inserted before `<application>`. */
    val extraManifestElements: List<XmlElementSpec> = emptyList(),
) {
    init {
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "invalid package name: '$packageName'" }
        require(minSdk > 0) { "minSdk must be positive, was $minSdk" }
        require(targetSdk > 0) { "targetSdk must be positive, was $targetSdk" }
        require(versionCode > 0) { "versionCode must be positive, was $versionCode" }
    }

    /** The activity that receives `android.intent.action.MAIN` / `android.intent.category.LAUNCHER`. */
    val launcherActivity: ActivitySpec? get() = activities.firstOrNull { it.launcher }

    companion object {
        /** Android 8.0. Matches the library's own minimum (`java.util.function`, apksig, ARSCLib). */
        const val DEFAULT_MIN_SDK = 26

        private val PACKAGE_NAME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    }
}
