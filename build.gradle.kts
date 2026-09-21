import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

/**
 * JitPack serves a multi-module repository at `com.github.<user>.<repo>:<module>:<tag>` and rewrites
 * whatever group the build publishes with to that path. Publishing under the same group keeps our
 * inter-module POM references resolvable from the same repository.
 *
 * JitPack exports `VERSION` while building (the tag or commit it is building), which is how the
 * released version is derived. Locally it falls back to a snapshot.
 */
val jitpackGroup = "com.github.RohitKushvaha01.libapk"

allprojects {
    group = jitpackGroup
    version = System.getenv("VERSION")?.takeIf { it.isNotBlank() } ?: "0.1.0-SNAPSHOT"
}

/** Human readable POM descriptions; `name` and `url` are shared. */
val moduleDescriptions = mapOf(
    "libapk-core" to "Models, DSL, APK zip writer and build pipeline for libapk.",
    "libapk-resources" to "Binary AndroidManifest (AXML) and resources.arsc encoder for libapk.",
    "libapk-signing" to "APK signing (v1/v2/v3) and alignment for libapk, backed by apksig.",
    "libapk-r8" to "D8/R8 dexer implementations for libapk.",
    "libapk-ecj" to "Eclipse Java Compiler and javax.tools compiler implementations for libapk.",
    "libapk-all" to "Convenience artifact that pulls in every libapk module.",
)

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "maven-publish")

        // Captured here because `name` inside the `pom { }` block is MavenPom.name.
        val projectName = name
        val projectDescription = moduleDescriptions[projectName] ?: projectName

        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }

        // Test support only, never published.
        if (name != "libapk-testkit") {
            extensions.configure<PublishingExtension> {
                publications {
                    create<MavenPublication>("maven") {
                        from(components["java"])

                        pom {
                            name.set(projectName)
                            description.set(projectDescription)
                            url.set("https://github.com/RohitKushvaha01/libapk")
                            licenses {
                                license {
                                    name.set("MIT License")
                                    url.set("https://opensource.org/licenses/MIT")
                                }
                            }
                            developers {
                                developer {
                                    id.set("RohitKushvaha01")
                                    name.set("Rohit Kushwaha")
                                }
                            }
                            scm {
                                url.set("https://github.com/RohitKushvaha01/libapk")
                                connection.set("scm:git:https://github.com/RohitKushvaha01/libapk.git")
                                developerConnection.set("scm:git:ssh://git@github.com/RohitKushvaha01/libapk.git")
                            }
                        }
                    }
                }
            }
        }
    }
}
