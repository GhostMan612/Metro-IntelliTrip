// ============================================================
// Atlas SDK publication
// ============================================================
//
// The core modules are pure Kotlin/JVM (ADR-004). This block is what makes them
// consumable from OUTSIDE this repository, which is the entire point of the SDK
// boundary (Phase 09).
//
// The consumer is Recovery for All, a Flutter app in a separate repository. It
// cannot use `includeBuild` across repositories, so these modules publish to a
// Maven coordinate and Recovery for All depends on that coordinate normally. Pure
// Kotlin runs on Android; `java.time` and friends need core library desugaring on
// the consumer's side, which Recovery for All already enables.
//
// Publishing is opt-in via `publishAtlasToLocalRepo`, never part of `build`, so an
// ordinary clone cannot publish anything by accident.

val atlasVersion = "0.1.0"

// `plugins {}` in the root applies the AGP/Kotlin plugins with `apply false`. Reading
// that block eagerly is what makes them resolvable in subprojects; without it a
// plain `apply(plugin = ...)` elsewhere can race plugin resolution and fail with
// "plugin not found" for no visible reason.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}

allprojects {
    // Only the portable modules are published. The app, the sample host and the
    // quarantined prototype are build-time-only and must never reach a consumer.
    //
    // Two modules are excluded, both for the same reason:
    //   atlas-map-android  - an Android library; depends on the MapLibre SDK and
    //                        ships with the host app that renders, not the engine.
    //   atlas-transit      - the quarantined prototype (ADR-014). It is an Android
    //                        module and non-authoritative; publishing it would put
    //                        superseded code on a resolvable coordinate.
    val moduleName = name
    val excluded = setOf("atlas-map-android", "atlas-transit")
    if (moduleName.startsWith("atlas-") && moduleName !in excluded) {
        apply(plugin = "maven-publish")
        apply(plugin = "java-library")

        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    artifactId = moduleName
                    groupId = "com.intellitrip.atlas"
                    version = atlasVersion
                    pom {
                        name.set(moduleName)
                        description.set(
                            "IntelliTrip Atlas engine: " +
                                moduleName.substring("atlas-".length) + " capability",
                        )
                    }
                }
            }
            repositories {
                // A repository inside the build directory rather than ~/.m2, so
                // published artifacts sit next to the checkout they came from: obvious
                // to inspect, and trivial to delete.
                maven {
                    name = "atlasLocal"
                    url = uri(rootProject.layout.buildDirectory.dir("atlas-maven"))
                }
            }
        }

        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }
    }
}

// Single entry point, so the consumer-facing workflow is one command rather than
// a remembered list of per-module tasks.
tasks.register("publishAtlasToLocalRepo") {
    group = "publishing"
    description = "Publishes the Atlas SDK to build/atlas-maven for consumption by another app."
    dependsOn(
        subprojects
            .filter { it.name.startsWith("atlas-") && it.name !in setOf("atlas-map-android", "atlas-transit") }
            .map { "${it.path}:publishAllPublicationsToAtlasLocalRepository" },
    )
}
