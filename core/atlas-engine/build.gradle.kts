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
            version = "0.2.0"
            when (name) {
                "kotlinMultiplatform" -> artifactId = "atlas-engine"
                "jvm" -> artifactId = "atlas-engine-jvm"
                "wasmJs" -> artifactId = "atlas-engine-wasmJs"
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
        browser { commonWebpackConfig { outputFileName = "atlas-engine.js" } }
        binaries.library()
    }
    // No `withJava()` needed: KMP 2.1 always configures the java component
    // for Maven publishing (deprecation warning otherwise).
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // The engine depends only on portable modules. Provider adapters are
                // injected by the host, never hard-wired here.
                // `api` for types reachable through the public engine API so hosts
                // see them without redeclaring. Preserves the kotlin.jvm
                // `api(...)` visibility from `core/atlas-engine/build.gradle.kts`.
                api(project(":core:atlas-domain"))
                api(project(":core:atlas-contracts"))
                api(project(":core:atlas-scope"))
                api(project(":core:atlas-routing"))
                // Types reachable through the public engine API are exported to hosts.
                api(project(":core:atlas-map"))
                // NOTE: `atlas-offline` is deliberately NOT a dependency here
                // (M3a): it is JVM-only (java.nio Files) and blocked engine
                // wasmJs compile. Offline detail is queried by the host from
                // its own OfflineRegistry; the engine reports only
                // `offlineAvailable: Boolean`. See AtlasEngine KDoc.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                // Runtime for datetime serializers on JVM/Android (R8 needs it).
                // JVM-only: serialization-core in commonMain breaks wasm compile.
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
                // I/O providers are JVM-only until Files/HTTP seams land
                // (java.nio/zip). Kept as `implementation` (preserves the
                // kotlin.jvm `implementation(...)` visibility). Unused in
                // commonMain code (host injects contracts providers), so
                // jvmMain-only does not change the portable facade.
                implementation(project(":core:atlas-gtfs-static"))
                implementation(project(":core:atlas-gtfs-realtime"))
                implementation(project(":core:atlas-weather"))
            }
        }
        val wasmJsMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }
}
