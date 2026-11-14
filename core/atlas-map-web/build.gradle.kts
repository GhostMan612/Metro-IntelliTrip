import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
}

publishing {
    publications {
        withType<MavenPublication> {
            groupId = "com.intellitrip.atlas"
            version = providers.gradleProperty("atlasVersion").get()
            when (name) {
                "kotlinMultiplatform" -> artifactId = "atlas-map-web"
                "jvm" -> artifactId = "atlas-map-web-jvm"
                "wasmJs" -> artifactId = "atlas-map-web-wasmJs"
            }
        }
    }
    repositories {
        maven {
            name = "atlasLocal"
            url = uri(rootProject.layout.buildDirectory.dir("atlas-maven"))
        }
    }
}

kotlin {
    jvmToolchain(17)
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        // No npm/webpack dependency: the host page loads MapLibre GL JS (global
        // `maplibregl`) from a pinned CDN build, and the Kotlin renderer talks
        // to that global through JS interop. Vendoring maplibre-gl via npm is a
        // later hardening step (offline builds) once the shell ships.
        browser {}
        binaries.library()
    }
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core:atlas-map"))
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }
        val wasmJsMain by getting {
            dependencies {
                // The wasm renderer pushes plain GeoJSON strings (GeoJsonWriter);
                // MapLibre itself is loaded through webpack/npm above, so no
                // kotlinx-interop dependency is needed in common code.
                implementation(kotlin("test"))
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}
