package com.rk.libapk

import com.rk.libapk.builder.ApkBuilder
import com.rk.libapk.compile.ICompiler
import com.rk.libapk.compile.ecj.EcjCompiler
import com.rk.libapk.dex.D8Dexer
import com.rk.libapk.dex.IDexer
import com.rk.libapk.dex.R8Dexer
import com.rk.libapk.model.ManifestSpec
import com.rk.libapk.resources.ArscResourceEncoder
import com.rk.libapk.resources.IResourceEncoder
import com.rk.libapk.sign.ApksigSigner
import com.rk.libapk.sign.IApkSigner
import com.rk.libapk.sign.SigningConfig
import java.io.File

/**
 * Convenience wiring for the common case: `libapk-core` plus every bundled implementation.
 *
 * Each piece is swappable; this object only picks sensible defaults:
 *
 *  * compiler: ECJ ([EcjCompiler])
 *  * dexer: D8 ([D8Dexer]) without minification, or R8 ([R8Dexer]) when asked
 *  * resources/manifest: ARSCLib-backed [ArscResourceEncoder]
 *  * signing: apksig ([ApksigSigner])
 */
object LibApk {

    /**
     * A fully wired [ApkBuilder].
     *
     * @param classpath application jars/class directories the sources compile against.
     * @param platformJars platform API jars, in practice `android.jar`. They are used as the
     *   compiler's **boot** classpath (android.jar contains `java.lang.*` stubs) and as the dexer's
     *   library files. Pass an empty list to compile pure-Java glue against the running JDK.
     */
    fun builder(
        manifest: ManifestSpec,
        signing: SigningConfig? = null,
        classpath: List<File> = emptyList(),
        platformJars: List<File> = emptyList(),
    ): ApkBuilder {
        val builder = ApkBuilder(manifest)
            .compiler(EcjCompiler(classpath = classpath, bootclasspath = platformJars))
            .classpath(*classpath.toTypedArray())
            .libraryJars(*platformJars.toTypedArray())
            .dexer(D8Dexer())
            .resourceEncoder(ArscResourceEncoder())
        signing?.let { builder.signer(ApksigSigner(it)) }
        return builder
    }

    fun signer(signing: SigningConfig): IApkSigner = ApksigSigner(signing)

    fun dexer(minify: Boolean = false): IDexer = if (minify) R8Dexer() else D8Dexer()

    fun resourceEncoder(): IResourceEncoder = ArscResourceEncoder()

    fun compiler(
        outputDir: File? = null,
        release: String? = EcjCompiler.DEFAULT_RELEASE,
        classpath: List<File> = emptyList(),
        platformJars: List<File> = emptyList(),
    ): ICompiler = EcjCompiler(
        outputDir = outputDir,
        release = release,
        classpath = classpath,
        bootclasspath = platformJars,
    )
}
