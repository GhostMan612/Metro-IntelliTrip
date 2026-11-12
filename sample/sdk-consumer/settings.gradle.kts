import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    // A consumer must not add ad-hoc repositories of its own. This mirrors the
    // strictness the real SDK consumer uses: the Atlas repository has to be
    // declared here, not smuggled into a module build file.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
        // Populated by Metro-IntelliTrip's publishAtlasToLocalRepo task.
        maven { url = uri("../../build/atlas-maven") }
    }
}

rootProject.name = "atlas-sdk-consumer"
include(":consumer")
