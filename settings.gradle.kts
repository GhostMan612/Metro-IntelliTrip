import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Auto-provisions the JDK 17 toolchain (`jvmToolchain(17)`) when the machine
// has no Java 17 installation — e.g. a fresh Android Studio checkout that
// ships only JBR 21. Without this, Android Studio fails with
// `NoToolchainAvailableException` and "Project source sets cannot be resolved".
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "IntelliTrip"


dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
include(":app")
include(":core:atlas-transit")
include(":core:atlas-domain")
include(":core:atlas-contracts")
include(":core:atlas-gtfs-static")
include(":core:atlas-gtfs-realtime")
include(":core:atlas-map")
include(":core:atlas-map-android")
include(":core:atlas-weather")
include(":core:atlas-scope")
include(":core:atlas-routing")
include(":core:atlas-offline")
include(":core:atlas-engine")
include(":core:atlas-web-spike")
include(":sample:host")
