plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // `api` (not `implementation`): kotlinx time types are in the public API
    // (M2b), so SDK consumers must see these without redeclaring them.
    // serialization-core is the runtime for datetime serializers (R8 needs it).
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
    // kotlin.time.Duration is stdlib; no extra dep needed.
    testImplementation(kotlin("test"))
}
