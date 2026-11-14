import org.gradle.api.publish.maven.MavenPublication
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("maven-publish")
}

// SDK publishing: the root build.gradle.kts `maven` publication is disabled
// for this KMP module (java-library is incompatible with KMP). Publish the
// KMP variants with SDK coordinates so `publishAtlasToLocalRepo` picks this
// module up via `publishAllPublicationsToAtlasLocalRepository`. Keep version
// in sync with root `atlasVersion` (0.2.0). With Kotlin 2.1.20 the
// `kotlinMultiplatform` metadata publication resolves (2.0.20 rejected the
// 2.1.0 stdlib klib).
publishing {
    publications {
        // `withType` (not `named`): KMP creates publications lazily, after
        // this block, so `named` fails with "not found". Variant artifactIds
        // stay suffixed (`-jvm`, `-wasmJs`); only the main module gets the
        // bare id consumers resolve.
        withType<MavenPublication> {
            groupId = "com.intellitrip.atlas"
            version = providers.gradleProperty("atlasVersion").get()
            when (name) {
                "kotlinMultiplatform" -> artifactId = "atlas-offline"
                "jvm" -> artifactId = "atlas-offline-jvm"
                "wasmJs" -> artifactId = "atlas-offline-wasmJs"
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
        browser { commonWebpackConfig { outputFileName = "atlas-offline.js" } }
        binaries.library()
    }
    // No `withJava()` needed: KMP 2.1 always configures the java component
    // for Maven publishing (deprecation warning otherwise).
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api` (not `implementation`): domain and datetime types are in
                // the public API, so SDK consumers must see them without
                // redeclaring.
                api(project(":core:atlas-domain"))
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
                // NOTE: `atlas-gtfs-static` is deliberately a jvmMain-only
                // dependency here (even though it is now KMP), because the
                // common offline facade exposes no gtfs-static types — its
                // snapshot adapter (`JvmOfflineSnapshotPort` + `loadStaticFeed`)
                // is jvmMain-only by design (it needs payload bytes + paths).
                // Promoting to commonMain is possible but adds nothing.
            }
        }
        val jvmMain by getting {
            dependencies {
                // Runtime for datetime serializers on JVM/Android (R8 needs it).
                // JVM-only: serialization-core in commonMain breaks wasm compile.
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
                // Kept as `implementation` (preserves the kotlin.jvm visibility);
                // the portable facade in commonMain does not expose gtfs-static
                // types, only the jvmMain adapter does.
                implementation(project(":core:atlas-gtfs-static"))
            }
        }
        val wasmJsMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
                implementation(project(":core:atlas-gtfs-static"))
                implementation(project(":core:atlas-routing"))
                // Test-only: the planner's supertypes live in contracts (routing
                // keeps it `implementation`, so it is not transitive here).
                implementation(project(":core:atlas-contracts"))
            }
        }
    }
}
