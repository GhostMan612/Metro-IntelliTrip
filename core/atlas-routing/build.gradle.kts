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
    // Test-only: lets the real-feed latency guard build a real network.
    testImplementation(project(":core:atlas-gtfs-static"))
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    System.getProperty("intellitrip.gtfs.fixture")?.let { path ->
        systemProperty("intellitrip.gtfs.fixture", path)
    }
}