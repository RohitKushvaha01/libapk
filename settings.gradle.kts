pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "libapk"

include(
    ":libapk-core",
    ":libapk-resources",
    ":libapk-signing",
    ":libapk-r8",
    ":libapk-ecj",
    ":libapk-all",
    ":libapk-testkit",
)
