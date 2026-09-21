package com.rk.libapk

import com.rk.libapk.apk.ApkEntry
import com.rk.libapk.apk.ApkZipWriter
import com.rk.libapk.apk.ZipAlignments
import com.rk.libapk.builder.ApkBuildException
import com.rk.libapk.builder.ApkBuilder
import com.rk.libapk.compile.CompileResult
import com.rk.libapk.compile.Diagnostic
import com.rk.libapk.compile.ICompiler
import com.rk.libapk.compile.Severity
import com.rk.libapk.compile.ecj.EcjCompiler
import com.rk.libapk.dex.D8Dexer
import com.rk.libapk.dex.DexRequest
import com.rk.libapk.dex.DexResult
import com.rk.libapk.dex.IDexer
import com.rk.libapk.model.ActivitySpec
import com.rk.libapk.model.AttributeValueSpec
import com.rk.libapk.model.FileResource
import com.rk.libapk.model.LabelValue
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.model.MetaDataSpec
import com.rk.libapk.model.ResourceRef
import com.rk.libapk.model.ResourceSpec
import com.rk.libapk.model.StringResource
import com.rk.libapk.model.UsesFeatureSpec
import com.rk.libapk.model.XmlAttribute
import com.rk.libapk.model.XmlElementSpec
import com.rk.libapk.resources.ArscResourceEncoder
import com.rk.libapk.resources.IResourceEncoder
import com.rk.libapk.resources.ResourceRequest
import com.rk.libapk.resources.ResourceResult
import com.rk.libapk.sign.ApksigSigner
import com.rk.libapk.sign.IApkSigner
import com.rk.libapk.sign.SignRequest
import com.rk.libapk.sign.SignResult
import com.rk.libapk.sign.SigningConfig
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Every example in the README, kept in the build so the documented API cannot drift.
 *
 * Nothing here is executed; it lives in the test source set so ./gradlew build compiles it.
 */
@Suppress("unused", "UNUSED_PARAMETER")
object DocumentationExamples {

    // 1. The smallest useful build: you already have a dex, you just want an APK.
    suspend fun minimalWithPrebuiltDex(dex: File, output: File, signing: SigningConfig) {
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
    }

    // 2. Compile Java sources, dex them, generate resources, sign: the whole pipeline.
    suspend fun compileJavaSourcesWithEcj(
        sources: File,
        androidJar: File,
        output: File,
        signing: SigningConfig,
    ) {
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
    }

    // 3. An app with native libraries: several ABIs, an asset tree, density icons, minified with R8.
    suspend fun nativeLibraryApk(
        appClasses: File,
        output: File,
        androidJar: File,
        signing: SigningConfig,
    ) {
        val packageName = "com.example.app"
        val manifest = ManifestSpec(
            packageName = packageName,
            versionCode = 42,
            versionName = "2.1.0",
            minSdk = 26,
            targetSdk = 34,
            label = LabelValue.Reference(ResourceRef("string", "app_name")),
            icon = ResourceRef("mipmap", "ic_launcher"),
            applicationName = "$packageName.ExampleApplication",
            // Do not extract .so at install time; libapk stores them uncompressed and page-aligned.
            extractNativeLibs = false,
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
                FileResource("mipmap", "ic_launcher", File("icons/hdpi.png"), qualifier = "hdpi"),
                FileResource("mipmap", "ic_launcher", File("icons/xxhdpi.png"), qualifier = "xxhdpi"),
                FileResource("drawable", "splash", File("icons/splash.png")),
            ),
        )

        val result = LibApk.builder(
            manifest = manifest,
            signing = signing,
            platformJars = listOf(androidJar),
        )
            .resources(resources)
            .dexInputs(appClasses)                 // pre-built classes; skips the compiler entirely
            .nativeLibrary("arm64-v8a", File("libs/arm64-v8a/libnative.so"))
            .nativeLibrary("x86_64", File("libs/x86_64/libnative.so"))
            .assets(File("app_data"))                // -> assets/**
            .assets(File("extras"), targetPrefix = "data") // -> assets/data/**
            .minify(enabled = true, keepRules = listOf("-keep class com.example.app.NativeBridge { *; }"))
            .dexer(LibApk.dexer(minify = true))       // R8 instead of D8
            .output(output)
            .build()

        println("built ${result.apk} with ${result.entries.size} entries")
    }

    // 4. Escape hatches: attributes and elements the DSL does not model.
    fun manifestEscapeHatch(): ManifestSpec = ManifestSpec(
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
            XmlAttribute("largeHeap", AttributeValueSpec.Bool(true)),
            // Not in the bundled platform table? Supply the framework id explicitly.
            XmlAttribute("someFutureAttribute", AttributeValueSpec.Bool(true), resourceId = 0x01010600),
            // Non-Android namespaces work too.
            XmlAttribute("targetApi", AttributeValueSpec.IntValue(34), namespace = "http://schemas.android.com/tools"),
        ),
    )

    // 5. Bring your own compiler (a custom toolchain, or on-device Android).
    class CustomCompiler(private val compile: (List<File>) -> List<File>) : ICompiler {
        override suspend fun compileJava(inputFiles: List<File>): CompileResult = try {
            CompileResult.success(compile(inputFiles))
        } catch (e: Exception) {
            CompileResult.failure(
                listOf(Diagnostic(line = -1, col = -1, source = "custom-compiler", message = e.message ?: "compile failed")),
            )
        }
    }

    // 6. Bring your own dexer (for example a bundled native tool, or a prebuilt dex pipeline).
    class PassthroughDexer : IDexer {
        override suspend fun dex(request: DexRequest): DexResult {
            val dexFiles = request.inputs.filter { it.extension.equals("dex", ignoreCase = true) }
            return if (dexFiles.isEmpty()) {
                DexResult.failure("no .dex inputs found")
            } else {
                DexResult.success(dexFiles)
            }
        }
    }

    // 7. Bring your own resource encoder (e.g. reuse a manifest you already generated).
    class CachingResourceEncoder(private val delegate: IResourceEncoder) : IResourceEncoder {
        private var cache: ResourceResult? = null

        override suspend fun encode(request: ResourceRequest): ResourceResult =
            cache ?: delegate.encode(request).also { cache = it }
    }

    // 8. Sign with a key that is already in memory (no keystore file on disk).
    fun signingFromMemory(keystore: File, alias: String, password: CharArray): SigningConfig.InMemory {
        val store = KeyStore.getInstance("PKCS12")
        keystore.inputStream().use { store.load(it, password) }

        val privateKey = store.getKey(alias, password) as PrivateKey
        val chain = store.getCertificateChain(alias).map { it as X509Certificate }

        return SigningConfig.InMemory(privateKey, chain, name = alias)
    }

    fun signingFromKeystore(keystore: File, alias: String, password: CharArray): SigningConfig.Keystore =
        SigningConfig.Keystore(
            keystore = keystore,
            alias = alias,
            storePassword = password,
            // storeType is auto-detected (PKCS12 vs JKS) when left null.
        )

    // 9. Unsigned builds and explicit scheme selection.
    suspend fun unsignedBuild(output: File, signing: SigningConfig) {
        val manifest = ManifestSpec(packageName = "com.example.app", label = LabelValue.Literal("My App"))

        ApkBuilder(manifest)
            .resourceEncoder(ArscResourceEncoder())
            .prebuiltDex(File("classes.dex"))
            .unsignedOutput(File("build/unsigned.apk")) // keep the aligned, unsigned archive
            .output(output)
            .build() // no signer => the unsigned APK is copied to `output`

        // Or sign with only v2+v3 (anything below minSdk 24 also needs v1):
        ApkBuilder(manifest)
            .resourceEncoder(ArscResourceEncoder())
            .prebuiltDex(File("classes.dex"))
            .signer(ApksigSigner(signing))
            .signingSchemes(v1 = false, v2 = true, v3 = true)
            .output(output)
            .build()
    }

    // 10. Progress, cancellation-friendly errors and warnings.
    suspend fun progressAndErrors(builder: ApkBuilder): File? = try {
        val result = builder.build { progress ->
            println("[${progress.completedStages + 1}/${progress.totalStages}] ${progress.stage}: ${progress.message}")
        }
        result.warnings.forEach { println("warning: $it") }
        result.apk
    } catch (e: ApkBuildException) {
        System.err.println(e.message)
        e.diagnostics
            .filter { it.severity == Severity.ERROR }
            .forEach { System.err.println("  ${it.source}:${it.line}:${it.col} ${it.message}") }
        null
    }

    // 11. Reproducible output: fixed timestamps make repeated builds byte-identical.
    suspend fun reproducible(output: File) {
        ApkBuilder(ManifestSpec(packageName = "com.example.app"))
            .resourceEncoder(ArscResourceEncoder())
            .prebuiltDex(File("classes.dex"))
            .buildTimestamp(1_700_000_000_000) // omit for the DOS epoch (also deterministic)
            .output(output)
            .build()
    }

    // 12. Drive the stages yourself when the pipeline does not fit.
    suspend fun lowLevelStages(
        sources: List<File>,
        manifest: ManifestSpec,
        resources: ResourceSpec,
        signing: SigningConfig,
        output: File,
        scratch: File,
    ) {
        val compiled = EcjCompiler(classpath = emptyList()).compileJava(sources)
        check(compiled.isSuccess) {
            compiled.diagnostics.joinToString("\n") { "${it.source}:${it.line}: ${it.message}" }
        }

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

        // Signing also finalises alignment.
        ApksigSigner(signing).sign(
            SignRequest(inputApk = unsigned, outputApk = output, minSdk = manifest.minSdk),
        )
    }

    // 13. On-device Android: no javax.tools, and the process is the app itself.
    suspend fun onDeviceAndroid(
        classesDir: File,
        output: File,
        signing: SigningConfig,
        appFilesDir: File,
    ) {
        // The classes arrive as a dex from your own toolchain (or shipped pre-dexed), so no
        // ICompiler is involved here. D8/R8, ARSCLib and apksig all run in-process.
        val builder = ApkBuilder(
            ManifestSpec(
                packageName = "com.example.app",
                label = LabelValue.Literal("My App"),
                minSdk = 26,
                targetSdk = 34,
                activities = listOf(ActivitySpec("com.example.app.MainActivity", launcher = true)),
            ),
        )
            .resourceEncoder(ArscResourceEncoder())
            .dexer(D8Dexer())
            .dexInputs(classesDir)
            .signer(ApksigSigner(signing))
            .workDirectory(File(appFilesDir, "libapk-work"), keep = false)
            .output(output)

        builder.build()
    }

    // 14. Extra zip entries (anything the DSL does not cover).
    suspend fun extraEntries(output: File) {
        val tinyPng = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )
        ApkBuilder(ManifestSpec(packageName = "com.example.app"))
            .resourceEncoder(ArscResourceEncoder())
            .prebuiltDex(File("classes.dex"))
            .entry(ApkEntry.deflated("assets/icon.png", tinyPng))
            .entry(ApkEntry.stored("custom/data.bin", File("data.bin"), ZipAlignments.RESOURCES))
            .output(output)
            .build()
    }
}

// `IResourceEncoder` is implemented above (CachingResourceEncoder); keep the SPI referenced so an
// accidental removal breaks this build.
@Suppress("unused")
private val resourceEncoderTypeCheck: Class<*> = IResourceEncoder::class.java

@Suppress("unused")
private fun noopSignerExample(output: File): IApkSigner = object : IApkSigner {
    override suspend fun sign(request: SignRequest): SignResult {
        request.inputApk.copyTo(request.outputApk, overwrite = true)
        return SignResult(request.outputApk)
    }
}
