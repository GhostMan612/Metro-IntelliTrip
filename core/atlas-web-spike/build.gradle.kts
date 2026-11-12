import org.gradle.api.tasks.Copy
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
                implementation(project(":core:atlas-gtfs-static"))
                implementation(project(":core:atlas-gtfs-realtime"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        val jvmMain by getting
        val wasmJsMain by getting {
            dependencies {
                // js-joda core (pulled in by kotlinx-datetime) ships UTC only.
                // The transit feed is scheduled in America/Chicago, so the
                // browser needs the IANA rules registered by this package.
                implementation(npm("@js-joda/timezone", "2.26.0"))
            }
        }
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

// Vendor check: the shell loads maplibre-gl.js/maplibre-gl.css from the
// same origin (vendored dist under src/wasmJsResources). These Copy tasks
// place the assets next to the webpack bundles so `wasmJsBrowser*Run`
// serves them locally with no CDN dependency.
tasks.register<Copy>("vendorMapLibreDev") {
    group = "web"
    description = "Copy vendored MapLibre GL dist next to the dev bundle."
    dependsOn("wasmJsBrowserDevelopmentWebpack")
    from(layout.projectDirectory.dir("src/wasmJsResources")) {
        include("index.html", "maplibre-gl.js", "maplibre-gl.css")
    }
    into(layout.projectDirectory.dir("build/kotlin-webpack/wasmJs/developmentExecutable"))
}
tasks.register<Copy>("vendorMapLibreProd") {
    group = "web"
    description = "Copy vendored MapLibre GL dist next to the prod bundle."
    dependsOn("wasmJsBrowserProductionWebpack")
    from(layout.projectDirectory.dir("src/wasmJsResources")) {
        include("index.html", "maplibre-gl.js", "maplibre-gl.css")
    }
    into(layout.projectDirectory.dir("build/kotlin-webpack/wasmJs/productionExecutable"))
}

