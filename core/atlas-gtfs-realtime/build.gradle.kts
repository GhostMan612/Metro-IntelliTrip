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
                "kotlinMultiplatform" -> artifactId = "atlas-gtfs-realtime"
                "jvm" -> artifactId = "atlas-gtfs-realtime-jvm"
                "wasmJs" -> artifactId = "atlas-gtfs-realtime-wasmJs"
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
        browser { commonWebpackConfig { outputFileName = "atlas-gtfs-realtime.js" } }
        binaries.library()
    }
    // No `withJava()` needed: KMP 2.1 always configures the java component
    // for Maven publishing (deprecation warning otherwise).
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // `api` (not `implementation`): domain/contracts/coroutines/
                // datetime types are in the public API, so SDK consumers must
                // see them without redeclaring.
                api(project(":core:atlas-domain"))
                api(project(":core:atlas-contracts"))
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                // Runtime for datetime serializers on JVM/Android (R8 needs it).
                // JVM-only: serialization-core in commonMain breaks wasm compile.
                api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
                // GTFS-Realtime protobuf bindings stay JVM/Android-only
                // (Java 8 bytecode). Newer bindings publish Java 21 class files
                // that Android runtimes cannot load. Decoding lives in the
                // jvmMain actual behind the common expect FeedDecoder seam.
                implementation("org.mobilitydata:gtfs-realtime-bindings:0.0.8")
            }
        }
        val wasmJsMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                // Real-protobuf fixtures/decoder roundtrip (jvmTest only:
                // commonTest cannot see the Java protobuf bindings).
                implementation("org.mobilitydata:gtfs-realtime-bindings:0.0.8")
            }
        }
    }
}
