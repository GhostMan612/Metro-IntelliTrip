import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
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
