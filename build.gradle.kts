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
// Kotlin runs on Android; the engine's time API is `kotlinx.datetime`
// (M2b, pure Kotlin, no desugaring needed).
//
// Publishing is opt-in via `publishAtlasToLocalRepo`, never part of `build`, so an
// ordinary clone cannot publish anything by accident.

val atlasVersion = "0.2.0"

// `plugins {}` in the root applies the AGP/Kotlin plugins with `apply false`. Reading
// that block eagerly is what makes them resolvable in subprojects; without it a
// plain `apply(plugin = ...)` elsewhere can race plugin resolution and fail with
// "plugin not found" for no visible reason.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.1.20" apply false
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
    // KMP modules (jvm+wasmJs) publish via their own KMP publications.
    // Applying the `java-library` `maven` publication below to a KMP module is
    // incompatible (Gradle warns) and creates a coordinate collision.
    // `atlas-domain` (M3b) plus the pure-Kotlin M3a modules; I/O modules stay
    // JVM-only until their Files/HTTP seams land.
    val excluded = setOf(
        "atlas-map-android", "atlas-transit", "atlas-web-spike",
        "atlas-domain", "atlas-contracts", "atlas-scope", "atlas-map", "atlas-map-web",
        "atlas-routing", "atlas-engine",
        "atlas-gtfs-static", "atlas-gtfs-realtime", "atlas-offline", "atlas-weather",
    )
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
            .filter { it.name.startsWith("atlas-") && it.name !in setOf("atlas-map-android", "atlas-transit", "atlas-web-spike") }
            .map { "${it.path}:publishAllPublicationsToAtlasLocalRepository" },
    )
}
