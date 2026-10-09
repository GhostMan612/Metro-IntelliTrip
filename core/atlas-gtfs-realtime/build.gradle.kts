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
    // Android-compatible GTFS-Realtime bindings (Java 8 bytecode). Newer
    // bindings publish Java 21 class files that Android runtimes cannot load.
    implementation("org.mobilitydata:gtfs-realtime-bindings:0.0.8")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}