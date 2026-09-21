plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Convenience artifact: everything a typical consumer needs.
    api(project(":libapk-core"))
    api(project(":libapk-resources"))
    api(project(":libapk-signing"))
    api(project(":libapk-r8"))
    api(project(":libapk-ecj"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter.api)
    testImplementation(project(":libapk-testkit"))
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
