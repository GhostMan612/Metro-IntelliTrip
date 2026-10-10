plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:atlas-domain"))
    implementation(project(":core:atlas-contracts"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.withType<Test>().configureEach {
    // Real GTFS feeds are large; parsing needs more than the default heap.
    maxHeapSize = "2g"
    // Path to an optional real GTFS fixture; see RealFeedSmokeTest.
    System.getProperty("intellitrip.gtfs.fixture")?.let { path ->
        systemProperty("intellitrip.gtfs.fixture", path)
    }
    testLogging {
        showStandardStreams = true
    }
}