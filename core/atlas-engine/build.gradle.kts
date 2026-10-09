plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The engine depends only on portable modules. Provider adapters are
    // injected by the host, never hard-wired here.
    api(project(":core:atlas-domain"))
    api(project(":core:atlas-contracts"))
    api(project(":core:atlas-scope"))
    api(project(":core:atlas-routing"))
    // Types reachable through the public engine API are exported to hosts.
    api(project(":core:atlas-map"))
    api(project(":core:atlas-offline"))
    implementation(project(":core:atlas-gtfs-static"))
    implementation(project(":core:atlas-gtfs-realtime"))
    implementation(project(":core:atlas-weather"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}