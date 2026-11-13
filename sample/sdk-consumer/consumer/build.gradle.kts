plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The only Atlas dependency. Everything else is transitive, which proves
    // the published POM graph is complete and the engine genuinely stands alone.
    implementation("com.intellitrip.atlas:atlas-engine:0.2.0")
}

application {
    mainClass.set("com.intellitrip.consumer.MainKt")
}
