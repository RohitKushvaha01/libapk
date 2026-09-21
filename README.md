# libapk

[![Build](https://github.com/RohitKushvaha01/libapk/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/RohitKushvaha01/libapk/actions/workflows/build.yml)
[![Tests](https://github.com/RohitKushvaha01/libapk/actions/workflows/tests.yml/badge.svg?branch=main)](https://github.com/RohitKushvaha01/libapk/actions/workflows/tests.yml)

Build Android APK files **programmatically from Kotlin/JVM**, with **no `aapt2`, no Android SDK,
and no Gradle** involved in producing the APK.

libapk exists for tools that must emit an installable APK themselves, Everything an APK needs is
synthesized in-process:

| APK part | How libapk produces it |
|---|---|
| `AndroidManifest.xml` | binary AXML written from scratch: namespace-aware, with real framework attribute ids |
| `resources.arsc` | built from scratch for strings/bools/ints/colors and file resources (`mipmap`, `drawable`, `raw`, …) including density qualifiers |
| `res/**` | the declared payload files, referenced from the table |
| `classes.dex` | D8 or R8 from the R8 project (no `android.jar` required for dexing) |
| `lib/<abi>/*.so` | stored uncompressed and page-aligned |
| `assets/**` | copied from a directory tree |
| ZIP container | own deterministic writer with `zipalign`-equivalent alignment |
| signature | apksig: v1 + v2 + v3, which also re-aligns the archive |

## Verified behaviour

The whole pipeline is implemented and covered by tests that validate output with Google's own
build-tools (used **only as test-time verifiers**, never as a runtime dependency):

* `aapt2 dump badging` / `dump xmltree` / `dump resources` parse the generated manifest, resource
  table and density-specific icons;
* `apksigner verify --min-sdk-version 21 --max-sdk-version 34` reports v1, v2 and v3 all verified;
* `zipalign -c -p 4` accepts both the signed and the unsigned APK;
* `dexdump` finds the compiled classes in `classes.dex`;
* a tampered APK is correctly rejected.

## Modules

| Module | Contents | Dependencies |
|---|---|---|
| `libapk-core` | models, DSL, zip writer, build pipeline, SPIs (`ICompiler`, `IDexer`, `IResourceEncoder`, `IApkSigner`) | coroutines only |
| `libapk-resources` | binary manifest (AXML) + `resources.arsc` encoder, bundled Android attribute table | ARSCLib |
| `libapk-signing` | apksig-backed signing and alignment | apksig |
| `libapk-r8` | `D8Dexer` (dex only) and `R8Dexer` (shrink/obfuscate) | r8 |
| `libapk-ecj` | `EcjCompiler` (Eclipse Java Compiler) and `JavaxToolsCompiler` (JDK `javax.tools`) | ECJ |
| `libapk-all` | `LibApk` convenience wiring for the common case | all of the above |
| `libapk-testkit` | test-only helpers that locate and run SDK build-tools | none |

Every stage is swappable. `libapk-core` alone can build an APK from **pre-built** `.class`/`.dex`
files with your own signer and resource encoder.

## Requirements and Android compatibility

* **Building**: JVM 17+ for the tests/toolchain in this repo. At runtime, the compiler module needs
  Java 17 (ECJ 3.46) and the dexer needs Java 11+ (R8's classes are Java 11).
* **The library on Android**: `minSdk 26`. ARSCLib uses `java.util.function` (API 24+) and
  `java.util.Base64` in a JSON helper (API 26+); apksig's v1 signing also uses API 26 APIs.
* **Java compilation is optional**: pass pre-built classes and no compiler is required.
* **`android.jar` is optional**: dexing works without it. Supplying it gives better desugaring and
  lets R8 shrink accurately. When you do, pass it as `platformJars` so ECJ uses it as a *boot*
  classpath, because `android.jar` contains `java.lang.*` stubs, so putting it on the regular classpath
  makes ECJ reject the JDK's `java.base`.

---

# Usage

Every snippet below also exists as compilable code in
[`libapk-all/src/test/kotlin/com/rk/libapk/DocumentationExamples.kt`](libapk-all/src/test/kotlin/com/rk/libapk/DocumentationExamples.kt),
so it is checked against the real API by `./gradlew build`. A complete, executable end-to-end build
lives in
[`EndToEndApkTest.kt`](libapk-all/src/test/kotlin/com/rk/libapk/EndToEndApkTest.kt).

1. [Smallest useful build](#1-smallest-useful-build)
2. [Compile Java, dex, resource, sign](#2-compile-java-dex-resource-sign)
3. [App with native libraries](#3-app-with-native-libraries)
4. [Escape hatches for manifest attributes](#4-escape-hatches-for-manifest-attributes)
5. [Bring your own compiler](#5-bring-your-own-compiler)
6. [Bring your own dexer](#6-bring-your-own-dexer)
7. [Bring your own resource encoder](#7-bring-your-own-resource-encoder)
8. [Signing](#8-signing)
9. [Unsigned builds and scheme selection](#9-unsigned-builds-and-scheme-selection)
10. [Progress, warnings, errors](#10-progress-warnings-errors)
11. [Reproducible builds](#11-reproducible-builds)
12. [Driving the stages yourself](#12-driving-the-stages-yourself)
13. [Using it on Android](#13-using-it-on-android)
14. [Arbitrary zip entries](#14-arbitrary-zip-entries)

## 1. Smallest useful build

You already have a `classes.dex`; you just want a signed APK.

```kotlin
val manifest = ManifestSpec(
    packageName = "com.example.app",
    versionCode = 1,
    versionName = "1.0",
    minSdk = 26,
    targetSdk = 34,
    label = LabelValue.Literal("My App"),
    activities = listOf(ActivitySpec("com.example.app.MainActivity", launcher = true)),
)

val result = ApkBuilder(manifest)
    .resourceEncoder(ArscResourceEncoder())   // required: writes the binary manifest
    .prebuiltDex(dex)                          // no compiler, no dexer needed
    .signer(ApksigSigner(signing))
    .output(output)
    .build()

println("built ${result.apk}")
```

Only `resourceEncoder` is mandatory. `ApkBuilder` has no defaults on purpose: pass exactly the
pieces you want, or use `LibApk.builder(...)` (below) for the full stack.

## 2. Compile Java, dex, resource, sign

`LibApk.builder(...)` wires ECJ + D8 + ARSCLib + apksig.

```kotlin
val manifest = ManifestSpec(
    packageName = "com.example.app",
    label = LabelValue.Reference(ResourceRef("string", "app_name")),
    icon = ResourceRef("mipmap", "ic_launcher"),
    minSdk = 26,
    targetSdk = 34,
    activities = listOf(ActivitySpec("com.example.app.MainActivity", launcher = true)),
)

val resources = ResourceSpec(
    packageName = "com.example.app",
    strings = listOf(StringResource("app_name", "My App")),
    files = listOf(FileResource("mipmap", "ic_launcher", File("icon.png"))),
)

val result = LibApk.builder(
    manifest = manifest,
    signing = signing,
    platformJars = listOf(androidJar),  // android.jar -> ECJ boot classpath + dexer library
)
    .resources(resources)
    .sources(sources)                   // a file or a directory of .java
    .output(output)
    .build { progress ->
        println("${progress.stage} ${progress.completedStages}/${progress.totalStages}")
    }

println("built ${result.apk} (signed=${result.signed})")
```

`sources(...)` accepts directories and expands them to the `.java` files inside. `platformJars`
is optional, but pass it when you have it: it gives ECJ the real Android API surface and lets R8
shrink accurately.

## 3. App with native libraries

Multi-ABI native libraries, an asset tree, density icons, R8 minification.

```kotlin
val packageName = "com.example.app"
val manifest = ManifestSpec(
    packageName = packageName,
    extractNativeLibs = false,          // .so stay uncompressed and page-aligned
    hardwareAccelerated = true,
    permissions = listOf("android.permission.INTERNET", "android.permission.VIBRATE"),
    features = listOf(UsesFeatureSpec("android.hardware.vulkan.version", required = false)),
    activities = listOf(
        ActivitySpec(
            name = "$packageName.MainActivity",
            launcher = true,
            screenOrientation = "landscape",
            configChanges = "orientation|screenSize|keyboardHidden",
        ),
    ),
    applicationMetaData = listOf(
        MetaDataSpec("com.example.app.version", AttributeValueSpec.Text("2.1.0")),
    ),
)

val resources = ResourceSpec(
    packageName = packageName,
    strings = listOf(StringResource("app_name", "My App")),
    files = listOf(
        FileResource("mipmap", "ic_launcher", File("icons/mdpi.png"), qualifier = "mdpi"),
        FileResource("mipmap", "ic_launcher", File("icons/xxhdpi.png"), qualifier = "xxhdpi"),
        FileResource("drawable", "splash", File("icons/splash.png")),
    ),
)

LibApk.builder(manifest, signing, platformJars = listOf(androidJar))
    .resources(resources)
    .dexInputs(appClasses)                          // pre-built classes; skips the compiler entirely
    .nativeLibrary("arm64-v8a", File("libs/arm64-v8a/libnative.so"))
    .nativeLibrary("x86_64", File("libs/x86_64/libnative.so"))
    .assets(File("app_data"))                      // -> assets/**
    .assets(File("extras"), targetPrefix = "data")  // -> assets/data/**
    .minify(enabled = true, keepRules = listOf("-keep class com.example.app.NativeBridge { *; }"))
    .dexer(LibApk.dexer(minify = true))             // R8 instead of D8
    .output(output)
    .build()
```

With `minify(true)`, `ApkBuilder` automatically adds keep rules for the activities and application
class named in the manifest, plus `-keepclasseswithmembernames class * { native <methods>; }` for
JNI holders. Add your own rules for anything reached only reflectively.

`FileResource`s sharing a type and name but using different `qualifier`s collapse into one resource
id with per-density payloads, exactly like aapt2.

## 4. Escape hatches for manifest attributes

Anything the DSL does not model can be expressed directly. Android-namespaced attributes are looked
up in the bundled platform table; pass `resourceId` for attributes newer than that table.

```kotlin
ManifestSpec(
    packageName = "com.example.app",
    extraManifestElements = listOf(
        XmlElementSpec(
            tag = "uses-feature",
            attributes = listOf(
                XmlAttribute("name", AttributeValueSpec.Text("android.hardware.vulkan.version")),
                XmlAttribute("version", AttributeValueSpec.IntValue(0x400003)),
                XmlAttribute("required", AttributeValueSpec.Bool(true)),
            ),
        ),
        XmlElementSpec(
            tag = "queries",
            children = listOf(
                XmlElementSpec(
                    tag = "package",
                    attributes = listOf(XmlAttribute("name", AttributeValueSpec.Text("com.android.vending"))),
                ),
            ),
        ),
    ),
    applicationAttributes = listOf(
        XmlAttribute("hardwareAccelerated", AttributeValueSpec.Bool(true)),
        XmlAttribute("usesCleartextTraffic", AttributeValueSpec.Bool(false)),
        XmlAttribute("someFutureAttribute", AttributeValueSpec.Bool(true), resourceId = 0x01010600),
        XmlAttribute("targetApi", AttributeValueSpec.IntValue(34), namespace = "http://schemas.android.com/tools"),
    ),
)
```

Value shapes: `Text`, `IntValue(value, hex)`, `Bool`, `Reference(ResourceRef)`, `Color(argb)`,
`FloatValue`, `Dimension(value, unit)`, `Raw(valueType, data)`.

## 5. Bring your own compiler

Your own toolchain, or on-device compilation where `javax.tools` does not exist.

```kotlin
class CustomCompiler(private val compile: (List<File>) -> List<File>) : ICompiler {
    override suspend fun compileJava(inputFiles: List<File>): CompileResult = try {
        CompileResult.success(compile(inputFiles))
    } catch (e: Exception) {
        CompileResult.failure(
            listOf(Diagnostic(line = -1, col = -1, source = "custom-compiler", message = e.message ?: "compile failed")),
        )
    }
}

builder.compiler(CustomCompiler { files -> myToolchain.compile(files) })
```

Report source errors as `Diagnostic`s rather than throwing, because `ApkBuilder` turns them into an
`ApkBuildException` whose message is a one-line summary and whose `diagnostics` carry line/column
detail. Reserve throwing for environmental failures.

`libapk-ecj` ships two ready implementations: `EcjCompiler` (Eclipse, no JDK toolchain needed) and
`JavaxToolsCompiler` (the JDK's own compiler, JVM only).

## 6. Bring your own dexer

```kotlin
class PassthroughDexer : IDexer {
    override suspend fun dex(request: DexRequest): DexResult {
        val dexFiles = request.inputs.filter { it.extension.equals("dex", ignoreCase = true) }
        return if (dexFiles.isEmpty()) DexResult.failure("no .dex inputs found")
        else DexResult.success(dexFiles)
    }
}

builder.dexer(PassthroughDexer())
```

`DexRequest` carries `minApiLevel`, `classpath`, `libraryFiles`, `release`, `enableDesugaring`,
`minify`, `keepRules`, `keepRulesFiles` and an optional `outputZip`. Inputs may be `.class` files,
directories (expanded recursively), jars or zips.

## 7. Bring your own resource encoder

The manifest encoder needs the resource table's ids, which is why resources and manifest are a
single stage.

```kotlin
class CachingResourceEncoder(private val delegate: IResourceEncoder) : IResourceEncoder {
    private var cache: ResourceResult? = null

    override suspend fun encode(request: ResourceRequest): ResourceResult =
        cache ?: delegate.encode(request).also { cache = it }
}
```

## 8. Signing

From a keystore file (type auto-detected as PKCS12 or JKS):

```kotlin
SigningConfig.Keystore(
    keystore = File("release.p12"),
    alias = "key",
    storePassword = password,
    keyPassword = null,      // defaults to storePassword
    storeType = null,        // auto-detect
)
```

From key material already in memory, with no keystore on disk:

```kotlin
val store = KeyStore.getInstance("PKCS12")
keystore.inputStream().use { store.load(it, password) }

SigningConfig.InMemory(
    privateKey = store.getKey(alias, password) as PrivateKey,
    certificates = store.getCertificateChain(alias).map { it as X509Certificate },
    name = alias,
)
```

`ApksigSigner` produces v1 + v2 + v3 and also performs the final alignment, so no `zipalign` step
is needed. `SignRequest` exposes `enableV1/V2/V3/V4`, `alignmentPreserved` and `debuggable` if you
need finer control.

## 9. Unsigned builds and scheme selection

```kotlin
ApkBuilder(manifest)
    .resourceEncoder(ArscResourceEncoder())
    .prebuiltDex(File("classes.dex"))
    .unsignedOutput(File("build/unsigned.apk"))  // keep the aligned, unsigned archive
    .output(output)
    .build()  // no signer => the unsigned APK is copied to `output`

ApkBuilder(manifest)
    .resourceEncoder(ArscResourceEncoder())
    .prebuiltDex(File("classes.dex"))
    .signer(ApksigSigner(signing))
    .signingSchemes(v1 = false, v2 = true, v3 = true)  // below minSdk 24 you also need v1
    .output(output)
    .build()
```

The unsigned APK libapk writes is already correctly aligned, so it can be signed by any other tool
later without re-running zipalign.

## 10. Progress, warnings, errors

```kotlin
try {
    val result = builder.build { progress ->
        println("[${progress.completedStages + 1}/${progress.totalStages}] ${progress.stage}: ${progress.message}")
    }
    result.warnings.forEach { println("warning: $it") }
    println(result.apk)
} catch (e: ApkBuildException) {
    System.err.println(e.message)
    e.diagnostics
        .filter { it.severity == Severity.ERROR }
        .forEach { System.err.println("  ${it.source}:${it.line}:${it.col} ${it.message}") }
}
```

Stages are reported in order: `COMPILE`, `DEX`, `RESOURCES`, `ASSEMBLE`, `SIGN` (optional stages are
omitted). `build` is a `suspend` function, so it composes with structured concurrency and
cancellation.

## 11. Reproducible builds

Zip timestamps default to the DOS epoch, so builds are already deterministic. Set an explicit
release timestamp when you want one:

```kotlin
builder.buildTimestamp(1_700_000_000_000)
```

Work directories are created under the system temp directory and removed afterwards. Use
`workDirectory(dir, keep = true)` to inspect the intermediates.

## 12. Driving the stages yourself

When the pipeline does not fit, the pieces work standalone.

```kotlin
// 1. Compile.
val compiled = EcjCompiler(classpath = emptyList()).compileJava(sources)
check(compiled.isSuccess) { compiled.diagnostics.joinToString("\n") { "${it.source}:${it.line}: ${it.message}" } }

// 2. Dex.
val dexed = D8Dexer().dex(
    DexRequest(
        inputs = compiled.classes.orEmpty(),
        outputDir = File(scratch, "dex"),
        minApiLevel = manifest.minSdk,
        minify = false,
    ),
)
check(dexed.isSuccess) { dexed.errorMessage.orEmpty() }

// 3. Manifest (AXML) + resources.arsc, sharing one id table.
val encoded = ArscResourceEncoder().encode(ResourceRequest(manifest, resources))

// 4. Assemble: stored+aligned for mmapped entries, deflated for the rest.
val entries = buildList {
    add(ApkEntry.stored("AndroidManifest.xml", encoded.manifestBytes, ZipAlignments.RESOURCES))
    encoded.resourcesArsc?.let { add(ApkEntry.stored("resources.arsc", it, ZipAlignments.RESOURCES)) }
    addAll(encoded.entries)
    dexed.dexFiles.forEach { add(ApkEntry.deflated(it.name, it)) }
    add(ApkEntry.stored("lib/arm64-v8a/libnative.so", File("libs/arm64-v8a/libnative.so"), ZipAlignments.NATIVE_LIB))
}
val unsigned = File(scratch, "unsigned.apk")
ApkZipWriter().write(unsigned, entries)

// 5. Sign (which also finalises alignment).
ApksigSigner(signing).sign(SignRequest(inputApk = unsigned, outputApk = output, minSdk = manifest.minSdk))
```

`ZipAlignments.NATIVE_LIB` is 16384 (page alignment for uncompressed `.so`),
`ZipAlignments.RESOURCES` is 4.

## 13. Using it on Android

The library is `minSdk 26` on Android and never touches `javax.tools`, so it runs in-process. Give
it classes to dex (or a pre-built dex) instead of Java sources, and keep work directories inside the
app's own storage:

```kotlin
ApkBuilder(manifest)
    .resourceEncoder(ArscResourceEncoder())
    .dexer(D8Dexer())
    .dexInputs(classesDir)
    .signer(ApksigSigner(signing))
    .workDirectory(File(appFilesDir, "libapk-work"), keep = false)
    .output(output)
    .build()
```

Note that the R8 dexer and the ECJ compiler are Java libraries with their own runtime requirements
(R8's classes are Java 11, ECJ 3.46 is Java 17). On a plain ART runtime you typically provide the
dex yourself, or run the build under a JVM-on-Android.

## 14. Arbitrary zip entries

```kotlin
ApkBuilder(manifest)
    .resourceEncoder(ArscResourceEncoder())
    .prebuiltDex(File("classes.dex"))
    .entry(ApkEntry.deflated("assets/icon.png", tinyPng))
    .entry(ApkEntry.stored("custom/data.bin", File("data.bin"), ZipAlignments.RESOURCES))
    .output(output)
    .build()
```

`ApkEntry.stored(...)` takes a `ByteArray` or a `File`; `ApkEntry.deflated(...)` likewise. The
default compression choice libapk uses for known entries: `AndroidManifest.xml` and
`resources.arsc` stored and 4-byte aligned, `lib/**/*.so` stored and 16384-byte aligned, everything
else deflated.

---

## Build pipeline

```
COMPILE  (ICompiler)        sources -> .class files
DEX      (IDexer)           classes -> classes.dex (D8 or R8)
RESOURCES(IResourceEncoder) resources.arsc + binary AndroidManifest.xml + res/**
ASSEMBLE (ApkZipWriter)     deterministic zip, stored+aligned resources.arsc and lib/*.so
SIGN     (IApkSigner)       v1/v2/v3 signatures and alignment
```

Progress is reported per stage through `build { progress -> … }`. Failures throw
`ApkBuildException`, which carries compiler `Diagnostic`s and a one-line summary.

## R8 minification

`R8Dexer`/`minify(true)` removes unreachable code. `ApkBuilder` automatically keeps the classes the
manifest names (activities and the application class) and JNI holders
(`-keepclasseswithmembernames class * { native <methods>; }`). When no library jars are configured
it also adds `-dontwarn`, because R8 cannot resolve platform classes without them. For accurate
shrinking, supply `libraryJars(androidJar)`.

## Testing

```bash
./gradlew test    # unit + integration tests
./gradlew build   # compiles the documented examples too
```

SDK-dependent tests skip themselves when no SDK is present (`ANDROID_HOME`, `ANDROID_SDK_ROOT`, or
`sdk.dir` in `local.properties`); everything else (zip writer, manifest encoding, resource table,
dexing, signing) has SDK-free coverage too. The tests workflow fails if any test was skipped, so a
missing SDK cannot quietly turn into a green run.

CI runs on every commit:

* [`.github/workflows/build.yml`](.github/workflows/build.yml) assembles the jars with **no Android
  SDK installed at all**, which is the same guarantee the library makes to consumers.
* [`.github/workflows/tests.yml`](.github/workflows/tests.yml) installs `build-tools;36.0.0` and
  `platforms;android-36`, runs the whole suite, and uploads the test reports on every run.

## Maintenance notes

* The Android framework attribute id table (`libapk-resources/src/main/resources/.../android-attributes.txt`)
  is generated from a platform `android.jar`. Regenerate it for a newer platform with
  `tools/generate-android-attribute-ids/generate.sh`. Unknown attributes can always be set
  explicitly via `XmlAttribute(..., resourceId = …)`.
* Research notes and verified Maven coordinates live in `docs/research/`.

## Limitations

* Not a general-purpose resource compiler: there is no XML layout/style compilation, no PNG
  crunching, no resource-reference resolution inside resources. The table supports the value types
  and file resources listed above.
* No support for AAB/APK splits, zip64 (>4 GiB / >65535 entries), or v4 signing by default
  (`enableV4` exists on `SignRequest`).
* Builds are single-pass; there is no incremental cache.
