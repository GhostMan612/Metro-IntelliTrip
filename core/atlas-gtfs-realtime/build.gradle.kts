plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        // GTFS-Realtime protobuf bindings are published for a newer JVM bytecode
        // level, so tests execute on JDK 21 while production code targets 17.
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(project(":core:atlas-domain"))
    implementation(project(":core:atlas-contracts"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.opentripplanner:gtfs-realtime-protobuf:2.7.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}