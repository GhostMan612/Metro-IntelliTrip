# Atlas Maven repository

This branch is the immutable Maven repository for the portable IntelliTrip
Atlas SDK. It is generated, never hand-edited.

- Group: `com.intellitrip.atlas`
- Latest version: `0.2.0`
- Maven URL: `https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven`
- Provenance: `provenance/0.2.0.json`

## Consume

```kotlin
repositories {
    maven { url = uri("https://raw.githubusercontent.com/GhostMan612/Metro-IntelliTrip/atlas-maven") }
}

dependencies {
    implementation("com.intellitrip.atlas:atlas-engine:0.2.0")
}
```

The engine pulls its portable capabilities transitively. POMs, Gradle module
metadata, sources, and MD5/SHA-1/SHA-256/SHA-512 checksums are published for
every module.

## Immutability

A version directory is published once. The publication tool refuses to change
an existing version and records the source commit, build environment, and
artifact hashes under `provenance/`. To ship a change, bump `atlasVersion` in
the producer's `gradle.properties` and publish a new version.
