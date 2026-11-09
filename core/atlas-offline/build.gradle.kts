plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:atlas-domain"))
    implementation(project(":core:atlas-gtfs-static"))
    implementation(project(":core:atlas-contracts"))
    implementation(project(":core:atlas-gtfs-realtime"))
    implementation(project(":core:atlas-routing"))
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

