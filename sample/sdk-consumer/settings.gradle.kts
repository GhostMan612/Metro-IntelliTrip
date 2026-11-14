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
        // The default is the local publication produced by
        // `publishAtlasToLocalRepo`. CI and the publication workflow set
        // `-PatlasMavenUrl=<url or path>` so the same consumer can resolve from
        // a staged directory or the published Maven branch without changing
        // this file. When the property is set, the local default is not used.
        val atlasMavenUrl = providers.gradleProperty("atlasMavenUrl").orNull
        if (atlasMavenUrl != null) {
            maven { url = uri(atlasMavenUrl) }
        } else {
            maven { url = uri("../../build/atlas-maven") }
        }
    }
}

rootProject.name = "atlas-sdk-consumer"
include(":consumer")
