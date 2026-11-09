import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "atlas-spike.js"
            }
        }
        binaries.executable()
        // NOTE: no resources plugin (not on the portal in 2.1.20), so the
        // vendored MapLibre dist in src/wasmJsResources is NOT wired into the
        // webpack output. The shell loads the pinned CDN build instead;
        // vendoring stays a documented follow-up (see KMP-01 M4d note).
    }
    // No `withJava()` needed: KMP 2.1 always configures the java component
    // (deprecation warning otherwise — same as atlas-domain).
    jvm()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // datetime only: serialization-core in commonMain breaks the
                // Kotlin 2.0.20 wasm compiler (Missing stdlib class). The spike
                // uses no serializers.
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
                // First browser screen (WebDemo): production engine + planner +
                // render pipeline, all KMP since M3c.
                implementation(project(":core:atlas-domain"))
                implementation(project(":core:atlas-contracts"))
                implementation(project(":core:atlas-scope"))
                implementation(project(":core:atlas-routing"))
                implementation(project(":core:atlas-map"))
                implementation(project(":core:atlas-map-web"))
                implementation(project(":core:atlas-engine"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        val jvmMain by getting
        val wasmJsMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging { showStandardStreams = true }
}

